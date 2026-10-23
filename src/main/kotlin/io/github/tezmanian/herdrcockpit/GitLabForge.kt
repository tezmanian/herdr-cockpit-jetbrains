// GitLab as a forge provider: detection, sign-in (CLI, token, or the IDE's own GitLab account) and draft notes
// (GitLabReview). The GitLab plugin is an optional dependency (herdr-gitlab.xml).
package io.github.tezmanian.herdrcockpit

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import java.net.URI
import java.net.http.HttpResponse
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class GitLabForge : ForgeProvider {
    override val title = "GitLab"
    override val sign = "!"
    override val request = "merge request"
    override val pending = "Drafts"
    override val sendsAs = "Draft notes on"
    override val offDiff = "Findings on lines outside the diff become drafts on the merge request that name the file and line."
    override val cli = "glab"
    override val tokenHint = "Personal access token with scope api."
    override val knownHosts = setOf("gitlab.com")
    override val ideAccounts = "Settings | Version Control | GitLab"

    override fun cliSignIn(host: String) = "glab auth login --hostname $host"
    override fun cliApi(bin: String, host: String, env: Map<String, String>) = bin.takeIf { cliSignedIn(it, host, env) }?.let { glabApi(it, host, env) }
    /** GitLab answers /api/v4/version with 401 and JSON (or the version, when it's public). */
    override fun probe(get: (String) -> HttpResponse<String>?): Boolean {
        val r = get("/api/v4/version") ?: return false
        val o = jsonObject(r.body())
        return (r.statusCode() == 401 && o?.has("message") == true) || (r.statusCode() == 200 && o?.has("revision") == true)
    }
    override fun apiBase(web: String) = "$web/api/v4"
    override fun ideAccount(host: String) = ideGitLabAccount(host)
    override fun review(api: ForgeApi, repo: RemoteRepo): ForgeReview = GitLabReview(api, repo)
}

/**
 * The IDE's own GitLab account for `host` (Settings | Version Control | GitLab): its REST API base and token, or null.
 * shortcut: reflection, because the GitLab plugin's account API is internal and changed in 2026.2 (String token ->
 * GitLabCredentials). Upgrade when it gets a public API.
 */
private fun ideGitLabAccount(host: String): Pair<String, String>? = try {
    // Our optional dependency on the GitLab plugin puts its classes in reach when it's enabled.
    val cls = Class.forName("org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager", true, GitLabForge::class.java.classLoader)
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
    logger<GitLabForge>().info("GitLab account of the IDE: ${e.message}")
    null
}
