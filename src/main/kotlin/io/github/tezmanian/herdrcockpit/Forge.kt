// Which kind of server hosts a repository (GitLab, GitHub, Gitea) and what differs between them. Detection: known
// public hosts, then a CLI signed in to the host (glab, gh, tea can only sign in to their own kind), then one
// anonymous request to the server. Unknown stays null: better no "Send to …" than one that goes nowhere. No IntelliJ
// imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class Forge(
    val title: String,
    /** How a merge/pull request is referenced: "!7" or "#7". */
    val sign: String,
    val request: String,
    /** What the unpublished comments are called in the forge's UI. */
    val pending: String,
    val cli: String,
) {
    GitLab("GitLab", "!", "merge request", "Drafts", "glab"),
    GitHub("GitHub", "#", "pull request", "Pending Review", "gh"),
    Gitea("Gitea", "#", "pull request", "Pending Review", "tea");

    /** The REST API root for the server at `web` ("https://host" or a subpath install). */
    fun apiBase(web: String): String {
        val w = web.trimEnd('/')
        return when (this) {
            GitLab -> "$w/api/v4"
            GitHub -> if (URI(w).host.equals("github.com", true)) "https://api.github.com" else "$w/api/v3"
            Gitea -> "$w/api/v1"
        }
    }

    /** The Authorization header's value for an access token. */
    fun auth(token: String) = if (this == Gitea) "token $token" else "Bearer $token"

    fun headers(): Map<String, String> = if (this == GitHub) mapOf("Accept" to "application/vnd.github+json") else emptyMap()

    companion object {
        fun of(title: String?) = entries.firstOrNull { it.title.equals(title, ignoreCase = true) }
    }
}

/** A repository from a git remote URL (https, ssh:// or scp-like): its host and full path ("group/sub/project"). */
data class RemoteRepo(val host: String, val path: String) {
    /** GitLab addresses projects by their URL-encoded full path. */
    val gitlab get() = "projects/" + enc(path)
    /** GitHub and Gitea: "repos/owner/name". */
    val repos get() = "repos/$path"
}

private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")

fun remoteRepo(remote: String): RemoteRepo? {
    val r = remote.trim().removeSuffix("/").removeSuffix(".git")
    val m = Regex("""^(?:https?|ssh)://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(.+)$""").find(r)
        ?: Regex("""^(?:[^@/:]+@)?([^/:]+):([^/].*)$""").find(r) ?: return null
    val (host, path) = m.destructured
    val parts = path.split('/')
    if (!Regex("^[A-Za-z0-9.-]+$").matches(host) || parts.size < 2 || !parts.all { SEGMENT.matches(it) && it != ".." }) return null
    return RemoteRepo(host.lowercase(), path)
}

fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

/** The CLIs to ask, as paths or bare names (looked up on PATH). */
data class ForgeClis(val glab: String, val gh: String, val tea: String)

private val KNOWN = mapOf(
    "github.com" to Forge.GitHub, "gitlab.com" to Forge.GitLab,
    "codeberg.org" to Forge.Gitea, "gitea.com" to Forge.Gitea,
)

fun knownForge(host: String): Forge? = KNOWN[host.lowercase()]

/** The forge whose CLI is signed in to `host`, else null. */
fun cliForge(host: String, clis: ForgeClis, env: Map<String, String> = System.getenv()): Forge? = when {
    cliSignedIn(clis.glab, host, env) -> Forge.GitLab
    cliSignedIn(clis.gh, host, env) -> Forge.GitHub
    host.lowercase() in teaLogins(clis.tea, env) -> Forge.Gitea
    else -> null
}

/**
 * Asks the server at `origin` ("https://host") without credentials: GitLab answers /api/v4/version with 401 and JSON,
 * Gitea /api/v1/version with its version, GitHub Enterprise /api/v3/meta with an X-GitHub-Enterprise-Version header.
 * No redirects are followed, so a sign-in proxy in front stays unknown.
 */
fun probeForge(origin: String): Forge? {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun get(path: String) = runCatching {
        http.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
    }.getOrNull()
    fun json(body: String) = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

    get("/api/v4/version")?.let { r ->
        val o = json(r.body())
        if ((r.statusCode() == 401 && o?.has("message") == true) || (r.statusCode() == 200 && o?.has("revision") == true)) return Forge.GitLab
    }
    get("/api/v1/version")?.let { r -> if (r.statusCode() == 200 && json(r.body())?.has("version") == true) return Forge.Gitea }
    get("/api/v3/meta")?.let { r -> if (r.headers().firstValue("X-GitHub-Enterprise-Version").isPresent) return Forge.GitHub }
    return null
}

/** The forge at `host`: known hosts, then a signed-in CLI, then the server itself. */
fun detectForge(host: String, clis: ForgeClis): Forge? = knownForge(host) ?: cliForge(host, clis) ?: probeForge("https://$host")
