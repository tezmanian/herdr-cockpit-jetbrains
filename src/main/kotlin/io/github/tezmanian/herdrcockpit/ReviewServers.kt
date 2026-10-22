// Review servers (Settings | Tools | Herdr Cockpit): the GitLab, GitHub and Gitea servers you set up, each with how to
// sign in. Tokens live in the IDE's password safe, never in the settings file. A server without an entry is detected
// (Forge.kt) and signed in to automatically.
package io.github.tezmanian.herdrcockpit

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.google.gson.JsonParser
import java.net.URI
import javax.swing.JComponent
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** How to sign in to a server. */
enum class ServerLogin(val title: String) {
    Auto("Automatic"), Cli("CLI (glab, gh, tea)"), Token("Access token"), Ide("IDE account (GitLab)");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: Auto
    }
}

/** A server you set up: its web address ("https://host" or a subpath install), its kind and how to sign in. */
data class ReviewServer(val url: String, val forge: Forge, val login: ServerLogin) {
    val host: String get() = URI(url).host.lowercase()
    /** The path the server lives under ("" at the root): a subpath install's remotes carry it in front of the repo. */
    val prefix: String get() = URI(url).path.trim('/')
}

class ServerEntry : BaseState() {
    var url by string("")
    var forge by string("GitLab")
    var login by string("Auto")
}

fun ServerEntry.toServer(): ReviewServer? = Forge.of(forge)?.let { f -> runCatching { ReviewServer(url ?: "", f, ServerLogin.of(login)).also { it.host } }.getOrNull() }
fun ReviewServer.toEntry() = ServerEntry().also { it.url = url; it.forge = forge.title; it.login = login.name }

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

fun signInHint(forge: Forge, host: String) = when (forge) {
    Forge.GitLab -> "glab auth login --hostname $host"
    Forge.GitHub -> "gh auth login --hostname $host"
    Forge.Gitea -> "tea login add --url https://$host"
}

/** The CLI's API for `host` when it's signed in there, else null. */
fun cliApi(forge: Forge, host: String, clis: ForgeClis): ForgeApi? = when (forge) {
    Forge.GitLab -> clis.glab.takeIf { cliSignedIn(it, host) }?.let { glabApi(it, host) }
    Forge.GitHub -> clis.gh.takeIf { cliSignedIn(it, host) }?.let { ghApi(it, host) }
    Forge.Gitea -> teaLogins(clis.tea)[host.lowercase()]?.let { teaApi(clis.tea, it) }
}

/**
 * How to reach the `forge` at `host`: as the server entry says, else automatically: a signed-in CLI, then (GitLab)
 * the IDE's GitLab account, then a token you entered before. `token` overrides the stored one (the Test button).
 */
fun reach(forge: Forge, host: String, server: ReviewServer?, token: String? = null): Reach {
    val web = server?.url?.trimEnd('/') ?: "https://$host"
    val login = server?.login ?: ServerLogin.Auto
    if (token == null && (login == ServerLogin.Auto || login == ServerLogin.Cli)) {
        cliApi(forge, host, HerdrSettings.forgeClis())?.let { return Reach.Ready(it, forge.cli) }
        if (login == ServerLogin.Cli) return Reach.Fail("${forge.cli} isn't signed in to $host: run `${signInHint(forge, host)}`.")
    }
    if (token == null && forge == Forge.GitLab && (login == ServerLogin.Auto || login == ServerLogin.Ide)) {
        ideGitLabAccount(host)?.let { (api, t) -> return Reach.Ready(RestApi(api, forge.auth(t)), "the IDE's GitLab account") }
        if (login == ServerLogin.Ide) return Reach.Fail("There's no GitLab account for $host in Settings | Version Control | GitLab.")
    }
    val t = token ?: ReviewTokens.get(web) ?: return Reach.NeedToken(web)
    return Reach.Ready(RestApi(forge.apiBase(web), forge.auth(t), forge.headers()), "your token", ownToken = true)
}

/** Who the API is signed in as: the user endpoint is "user" on all three. */
fun signedInAs(api: ForgeApi): String {
    val u = JsonParser.parseString(api.call("GET", "user", null)).asJsonObject
    return u.str("login") ?: u.str("username") ?: "?"
}

/**
 * The IDE's own GitLab account for `host` (Settings | Version Control | GitLab): its REST API base and token, or null.
 * shortcut: reflection, because the GitLab plugin's account API is internal and changed in 2026.2 (String token ->
 * GitLabCredentials). Upgrade when it gets a public API.
 */
fun ideGitLabAccount(host: String): Pair<String, String>? = try {
    // Our optional dependency on the GitLab plugin puts its classes in reach when it's enabled.
    val cls = Class.forName("org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager", true, ReviewTokens::class.java.classLoader)
    val manager = ApplicationManager.getApplication().getService(cls)
    if (manager == null) null else {
        val accounts = cls.getMethod("getAccountsState").invoke(manager) as kotlinx.coroutines.flow.StateFlow<*>
        val account = (accounts.value as Set<*>).filterNotNull().firstOrNull { a ->
            val server = a.javaClass.getMethod("getServer").invoke(a)
            (server.javaClass.getMethod("toURI").invoke(server) as URI).host.equals(host, ignoreCase = true)
        }
        account?.let { a ->
            val server = a.javaClass.getMethod("getServer").invoke(a)
            val api = server.javaClass.getMethod("getRestApiUri").invoke(server).toString().removeSuffix("/")
            val find = cls.methods.first { it.name == "findCredentials" && it.parameterCount == 2 }
            val creds = runBlockingCancellable { suspendCoroutineUninterceptedOrReturn<Any?> { find.invoke(manager, a, it) } }
            val token = creds as? String ?: creds?.javaClass?.getMethod("getAccessToken")?.invoke(creds) as? String
            token?.let { api to it }
        }
    }
} catch (_: ClassNotFoundException) {
    null
} catch (e: Exception) {
    logger<ReviewServer>().info("GitLab account of the IDE: ${e.message}")
    null
}

/** The servers table: URL | Type | Sign-in. */
fun serverTable() = EntryTable<ReviewServer>(listOf(
    "URL" to { it.url },
    "Type" to { it.forge.title },
    "Sign-in" to { s -> if (s.login == ServerLogin.Token) "Access token" + if (ReviewTokens.get(s.url) != null) " ••••" else " (none yet)" else s.login.title },
), ::editServer)

private fun editServer(parent: JComponent, s: ReviewServer?): ReviewServer? {
    val url = JBTextField(s?.url ?: "https://")
    val forge = ComboBox(Forge.entries.toTypedArray()).apply { selectedItem = s?.forge ?: Forge.GitLab; renderer = textListCellRenderer<Forge> { it?.title } }
    val login = ComboBox(ServerLogin.entries.toTypedArray()).apply { selectedItem = s?.login ?: ServerLogin.Auto; renderer = textListCellRenderer<ServerLogin> { it?.title } }
    val token = JBPasswordField()
    val result = JBLabel(" ")
    fun parsed() = runCatching { URI(url.text.trim().trimEnd('/')).takeIf { (it.scheme == "https" || it.scheme == "http") && !it.host.isNullOrEmpty() } }.getOrNull()
    val content = panel {
        row("URL:") { cell(url).align(AlignX.FILL).comment("The server's web address, e.g. https://gitlab.example.com.") }
        row("Type:") { cell(forge) }
        row("Sign-in:") {
            cell(login).comment("Automatic: a signed-in CLI (glab, gh, tea), for GitLab the IDE's account, else the token.")
        }
        row("Token:") {
            cell(token).align(AlignX.FILL).comment("Personal access token (GitLab: scope api; GitHub: pull requests read and write; Gitea: repository read and write). Empty keeps the stored one. Kept in the IDE's password safe.")
        }
        row {
            button("Test") {
                val u = parsed() ?: return@button run { result.text = "Enter a valid URL first." }
                val f = forge.selectedItem as Forge
                val typed = String(token.password).trim().ifEmpty { null }
                val server = ReviewServer(u.toString(), f, login.selectedItem as ServerLogin)
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
    val dialog = object : DialogWrapper(parent, true) {
        init { title = if (s == null) "New Review Server" else "Edit Review Server"; init(); initValidation() }
        override fun createCenterPanel() = content
        override fun getPreferredFocusedComponent(): JComponent = url
        override fun doValidate() = if (parsed() == null) ValidationInfo("Enter the server's address, e.g. https://gitlab.example.com", url) else null
    }
    if (!dialog.showAndGet()) return null
    val server = ReviewServer(parsed().toString(), forge.selectedItem as Forge, login.selectedItem as ServerLogin)
    String(token.password).trim().ifEmpty { null }?.let { ReviewTokens.set(server.url, it) }
    return server
}

