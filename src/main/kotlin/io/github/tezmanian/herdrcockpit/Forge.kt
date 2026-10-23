// Which forge (ForgeProvider) hosts a repository, and the repository from a git remote. Detection: known public
// hosts, then a CLI signed in to the host (glab, gh, tea can only sign in to their own kind), then one anonymous request
// to the server. Unknown stays null: better no "Send to …" than one that goes nowhere. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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

/** The forge one of `forges` knows `host` as (github.com, …). */
fun knownForge(host: String, forges: List<ForgeProvider>): ForgeProvider? = forges.firstOrNull { host.lowercase() in it.knownHosts }

/** The forge whose CLI is signed in to `host`, else null. `clis`: CLI name -> path or bare name (looked up on PATH). */
fun cliForge(host: String, clis: Map<String, String>, forges: List<ForgeProvider>, env: Map<String, String> = System.getenv()): ForgeProvider? =
    forges.firstOrNull { f -> clis[f.cli]?.let { f.cliApi(it, host, env) } != null }

/** The forge the server at `origin` ("https://host") answers like, asked without credentials and without following redirects. */
fun probeForge(origin: String, forges: List<ForgeProvider>): ForgeProvider? {
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
    fun get(path: String) = runCatching {
        http.send(HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
    }.getOrNull()
    return forges.firstOrNull { it.probe(::get) }
}

/** A JSON object, else null: for probes. */
fun jsonObject(body: String) = runCatching { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()

/** The forge at `host`: known hosts, then a signed-in CLI, then the server itself. */
fun detectForge(host: String, clis: Map<String, String>, forges: List<ForgeProvider>): ForgeProvider? =
    knownForge(host, forges) ?: cliForge(host, clis, forges) ?: probeForge("https://$host", forges)
