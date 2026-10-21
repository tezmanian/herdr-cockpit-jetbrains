// Which kind of server hosts a repository: GitLab, GitHub or Gitea. Known public hosts, then a CLI signed in to the
// host (glab, gh, tea can only sign in to their own kind), then one anonymous request to the server. Unknown stays
// null: better no "Send to …" than one that goes nowhere. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

enum class Forge(val title: String) { GitLab("GitLab"), GitHub("GitHub"), Gitea("Gitea") }

/** The CLIs to ask, as paths or bare names (looked up on PATH). */
data class ForgeClis(val glab: String, val gh: String, val tea: String)

private val KNOWN = mapOf(
    "github.com" to Forge.GitHub, "gitlab.com" to Forge.GitLab,
    "codeberg.org" to Forge.Gitea, "gitea.com" to Forge.Gitea,
)

fun knownForge(host: String): Forge? = KNOWN[host.lowercase()]

/** The forge whose CLI is signed in to `host`, else null. */
fun cliForge(host: String, clis: ForgeClis, env: Map<String, String> = System.getenv()): Forge? {
    fun ok(vararg cmd: String) = binaryFound(cmd[0]) && runCommand(cmd.toList(), env, 10)?.first == 0
    if (ok(clis.glab, "auth", "status", "--hostname", host)) return Forge.GitLab
    if (ok(clis.gh, "auth", "status", "--hostname", host)) return Forge.GitHub
    if (binaryFound(clis.tea)) {
        val out = runCommand(listOf(clis.tea, "logins", "list", "--output", "json"), env, 10)?.takeIf { it.first == 0 }?.second
        if (out != null && teaLoginHosts(out).any { it.equals(host, ignoreCase = true) }) return Forge.Gitea
    }
    return null
}

/** The hosts of the URLs in `tea logins list --output json` (whatever the field is called). */
fun teaLoginHosts(json: String): Set<String> {
    val hosts = mutableSetOf<String>()
    fun walk(e: JsonElement) {
        when {
            e.isJsonArray -> e.asJsonArray.forEach(::walk)
            e.isJsonObject -> e.asJsonObject.entrySet().forEach { walk(it.value) }
            e.isJsonPrimitive && e.asJsonPrimitive.isString ->
                runCatching { URI(e.asString) }.getOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }?.host?.let(hosts::add)
        }
    }
    runCatching { walk(JsonParser.parseString(json)) }
    return hosts
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
