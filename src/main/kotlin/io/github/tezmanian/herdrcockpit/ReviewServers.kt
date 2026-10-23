// Review servers (Settings | Tools | Herdr Cockpit): the servers you set up (of a registered forge: GitLab, GitHub,
// Gitea, …), each with how to sign in. Tokens live in the IDE's password safe, never in the settings file. A server
// without an entry is detected (Forge.kt) and signed in to automatically.
package io.github.tezmanian.herdrcockpit

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.google.gson.JsonParser
import java.net.URI
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JEditorPane

private val FORGES = ExtensionPointName<ForgeProvider>("io.github.tezmanian.herdrcockpit.forge")

/** The registered forges, in registration order (which is also the order detection asks them in). */
fun forges(): List<ForgeProvider> = FORGES.extensionList

/** The registered forge called `title`, else null. */
fun forgeNamed(title: String?) = forges().firstOrNull { it.title.equals(title, ignoreCase = true) }

/** How to sign in to a server. */
enum class ServerLogin {
    Auto, Cli, Token, Ide;

    fun title(forge: ForgeProvider) = when (this) {
        Auto -> "Automatic"
        Cli -> "CLI (${forge.cli})"
        Token -> "Access token"
        Ide -> "IDE account"
    }

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Auto
        /** The ways into a `forge`: an IDE account only where the IDE keeps them. */
        fun options(forge: ForgeProvider) = entries.filter { it != Ide || forge.ideAccounts != null }
    }
}

/** What "Automatic" tries for a `forge`, in order. */
private fun autoHint(forge: ForgeProvider) =
    "Automatic: a signed-in ${forge.cli}, " + (if (forge.ideAccounts != null) "then the IDE's ${forge.title} account, " else "") + "else the token."

private fun tokenHint(forge: ForgeProvider) = forge.tokenHint + " Empty keeps the stored one. Kept in the IDE's password safe."

/**
 * A server you set up: its web address ("https://host" or a subpath install), its kind, how to sign in and where its
 * CLI is (empty: the usual install folders, then PATH).
 */
data class ReviewServer(val url: String, val forge: ForgeProvider, val login: ServerLogin, val cli: String = "") {
    val host: String get() = URI(url).host.lowercase()
    /** The path the server lives under ("" at the root): a subpath install's remotes carry it in front of the repo. */
    val prefix: String get() = URI(url).path.trim('/')
    val cliPath: String get() = resolveTool(forge.cli, cli)
}

class ServerEntry : BaseState() {
    var url by string("")
    var forge by string("GitLab")
    var login by string("Auto")
    var cli by string("")
}

/** The server, else null: an invalid URL, or a forge no plugin registers now (`forges`). */
fun ServerEntry.toServer(forges: List<ForgeProvider>): ReviewServer? =
    forges.firstOrNull { it.title.equals(forge, ignoreCase = true) }?.let { f -> runCatching { ReviewServer(url ?: "", f, ServerLogin.of(login), cli ?: "").also { it.host } }.getOrNull() }
fun ReviewServer.toEntry() = ServerEntry().also { it.url = url; it.forge = forge.title; it.login = login.name; it.cli = cli }

/** The forges' CLIs (name -> path) in their usual install folders or on PATH: for servers without an entry. */
fun foundClis(forges: List<ForgeProvider>) = forges.associate { it.cli to findTool(it.cli) }

/**
 * Before 0.1.21 the CLI paths were global (`paths`: CLI name -> path). Each goes to the servers of its kind that have
 * none; the ones no server took are returned, to tell you.
 */
fun takeOverCliPaths(servers: List<ReviewServer>, paths: Map<String, String>): Pair<List<ReviewServer>, Map<String, String>> {
    val set = paths.filterValues { it.isNotBlank() }
    val updated = servers.map { s -> set[s.forge.cli]?.takeIf { s.cli.isBlank() }?.let { s.copy(cli = it) } ?: s }
    return updated to set.filterKeys { c -> servers.none { it.forge.cli == c } }
}

/** Tokens per server, in the password safe. */
object ReviewTokens {
    private fun key(web: String) = CredentialAttributes(generateServiceName("Herdr Cockpit", "Review token for ${web.trimEnd('/').lowercase()}"))
    fun get(web: String): String? = PasswordSafe.instance.getPassword(key(web))
    fun set(web: String, token: String?) = PasswordSafe.instance.setPassword(key(web), token)
}

/** How a review reaches a server. */
sealed interface Reach {
    data class Ready(val api: ForgeApi, val via: String, val ownToken: Boolean = false) : Reach
    /** No way in yet: a token for `web` would do. */
    data class NeedToken(val web: String) : Reach
    data class Fail(val message: String) : Reach
}

/**
 * How to reach the `forge` at `host`: as the server entry says, else automatically: a signed-in CLI, then the IDE's
 * account (where it keeps them), then a token you entered before. `token` overrides the stored one (the Test button).
 */
fun reach(forge: ForgeProvider, host: String, server: ReviewServer?, token: String? = null): Reach {
    val web = server?.url?.trimEnd('/') ?: "https://$host"
    val login = server?.login ?: ServerLogin.Auto
    if (token == null && (login == ServerLogin.Auto || login == ServerLogin.Cli)) {
        forge.cliApi(server?.cliPath ?: findTool(forge.cli), host, System.getenv())?.let { return Reach.Ready(it, forge.cli) }
        if (login == ServerLogin.Cli) return Reach.Fail("${forge.cli} isn't signed in to $host: run `${forge.cliSignIn(host)}`.")
    }
    val accounts = forge.ideAccounts
    if (token == null && accounts != null && (login == ServerLogin.Auto || login == ServerLogin.Ide)) {
        forge.ideAccount(host)?.let { (api, t) -> return Reach.Ready(RestApi(api, forge.auth(t), forge.headers()), "the IDE's ${forge.title} account") }
        if (login == ServerLogin.Ide) return Reach.Fail("There's no ${forge.title} account for $host in $accounts.")
    }
    val t = token ?: ReviewTokens.get(web) ?: return Reach.NeedToken(web)
    return Reach.Ready(RestApi(forge.apiBase(web), forge.auth(t), forge.headers()), "your token", ownToken = true)
}

/** Who the API is signed in as: the user endpoint is "user" on GitLab, GitHub and Gitea. */
fun signedInAs(api: ForgeApi): String {
    val u = JsonParser.parseString(api.call("GET", "user", null)).asJsonObject
    return u.str("login") ?: u.str("username") ?: "?"
}

/** The servers table: URL | Type | Sign-in. */
fun serverTable() = EntryTable<ReviewServer>(listOf(
    "URL" to { it.url },
    "Type" to { it.forge.title },
    "Sign-in" to { s -> if (s.login == ServerLogin.Token) "Access token" + if (ReviewTokens.get(s.url) != null) " ••••" else " (none yet)" else s.login.title(s.forge) + s.cli.takeIf { it.isNotBlank() && s.login != ServerLogin.Ide }?.let { " · $it" }.orEmpty() },
), ::editServer)

private fun editServer(parent: JComponent, s: ReviewServer?): ReviewServer? {
    val url = JBTextField(s?.url ?: "https://")
    val forge = ComboBox(forges().toTypedArray()).apply { s?.forge?.let { selectedItem = it }; renderer = textListCellRenderer<ForgeProvider> { it?.title } }
    val login = ComboBox<ServerLogin>().apply { renderer = textListCellRenderer<ServerLogin> { it?.title(forge.selectedItem as ForgeProvider) } }
    val token = JBPasswordField()
    val cli = JBTextField(s?.cli ?: "")
    val result = JBLabel(" ")
    fun parsed() = runCatching { URI(url.text.trim().trimEnd('/')).takeIf { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrEmpty() } }.getOrNull()
    lateinit var loginHint: JEditorPane
    lateinit var tokenHint: JEditorPane
    lateinit var tokenRow: Row
    lateinit var cliHint: JEditorPane
    lateinit var cliRow: Row
    val content = panel {
        row("URL:") { cell(url).align(AlignX.FILL).comment("The server's web address, e.g. https://gitlab.example.com.") }
        row("Type:") { cell(forge) }
        row("Sign-in:") { loginHint = cell(login).comment(" ").comment!! }
        cliRow = row("CLI path:") { cliHint = cell(cli).align(AlignX.FILL).comment(" ").comment!! }
        tokenRow = row("Token:") { tokenHint = cell(token).align(AlignX.FILL).comment(" ").comment!! }
        row {
            button("Test") {
                val u = parsed() ?: return@button run { result.text = "Enter a valid URL first." }
                val f = forge.selectedItem as ForgeProvider
                val typed = String(token.password).trim().ifEmpty { null }
                val server = ReviewServer(u.toString(), f, login.selectedItem as ServerLogin, cli.text.trim())
                result.text = ProgressManager.getInstance().runProcessWithProgressSynchronously<String, Exception>({
                    when (val r = reach(f, server.host, server, typed)) {
                        is Reach.Ready -> runCatching { "Signed in as ${signedInAs(r.api)} (via ${r.via})." }.getOrElse { "Failed: ${it.message}" }
                        is Reach.NeedToken -> "No CLI or account signed in: enter a token."
                        is Reach.Fail -> r.message
                    }
                }, "Testing ${u.host}", true, null)
            }
            cell(result)
        }
    }
    // Sign-in offers only what the type supports; the hints and the token row follow the choice.
    fun showLogin() {
        val f = forge.selectedItem as ForgeProvider
        val l = login.selectedItem as ServerLogin
        loginHint.text = when (l) {
            ServerLogin.Auto -> autoHint(f)
            ServerLogin.Cli -> "Sign in once with: ${f.cliSignIn(parsed()?.host ?: "<host>")}"
            ServerLogin.Token -> "Only the token below."
            ServerLogin.Ide -> "The account in ${f.ideAccounts}."
        }
        tokenHint.text = tokenHint(f)
        cliHint.text = "Empty: ${f.cli} in the usual install folders, then PATH."
        cliRow.visible(l == ServerLogin.Auto || l == ServerLogin.Cli)
        tokenRow.visible(l == ServerLogin.Auto || l == ServerLogin.Token)
    }
    fun showForge() {
        val options = ServerLogin.options(forge.selectedItem as ForgeProvider)
        val keep = (login.selectedItem as ServerLogin?) ?: s?.login
        login.model = DefaultComboBoxModel(options.toTypedArray())
        login.selectedItem = keep?.takeIf { it in options } ?: ServerLogin.Auto
        showLogin()
    }
    showForge()
    forge.addActionListener { showForge(); result.text = " " }
    login.addActionListener { showLogin(); result.text = " " }
    val dialog = object : DialogWrapper(parent, true) {
        init { title = if (s == null) "New Review Server" else "Edit Review Server"; init(); initValidation() }
        override fun createCenterPanel() = content
        override fun getPreferredFocusedComponent(): JComponent = url
        // A DSL panel turns continuous validation off: OK would stay disabled after the first check.
        override fun continuousValidation() = true
        override fun doValidate() = if (parsed() == null) ValidationInfo("Enter the server's address, e.g. https://gitlab.example.com", url) else null
    }
    if (!dialog.showAndGet()) return null
    val server = ReviewServer(parsed().toString(), forge.selectedItem as ForgeProvider, login.selectedItem as ServerLogin, cli.text.trim())
    String(token.password).trim().ifEmpty { null }?.takeIf { server.login == ServerLogin.Auto || server.login == ServerLogin.Token }?.let { ReviewTokens.set(server.url, it) }
    return server
}
