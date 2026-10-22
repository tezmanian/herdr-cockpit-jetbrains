// How review calls reach a forge: REST with a token, or a signed-in CLI (glab, gh, tea) with its own login. Bodies go
// over stdin, never through a shell. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ForgeError(message: String, val status: Int? = null) : Exception(message)

/** One API call; `path` is relative to the forge's API root ("repos/o/r/pulls"). Throws ForgeError. */
fun interface ForgeApi {
    fun call(method: String, path: String, body: String?): String
}

/** REST with an access token; `auth` is the Authorization header's value. */
class RestApi(private val base: String, private val auth: String, private val extra: Map<String, String> = emptyMap()) : ForgeApi {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun call(method: String, path: String, body: String?): String {
        val req = HttpRequest.newBuilder(URI.create("$base/$path")).timeout(Duration.ofSeconds(30))
            .header("Authorization", auth).header("Content-Type", "application/json")
            .apply { extra.forEach { (k, v) -> header(k, v) } }
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
        val res = try { http.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw ForgeError("${e.message}") }
        if (res.statusCode() !in 200..299) throw ForgeError("HTTP ${res.statusCode()}: ${res.body().take(300)}", res.statusCode())
        return res.body()
    }
}

/** A CLI's API command: `args(method, path, hasBody)` is what follows the binary. */
class CliApi(private val bin: String, private val env: Map<String, String> = System.getenv(),
             private val args: (String, String, Boolean) -> List<String>) : ForgeApi {
    override fun call(method: String, path: String, body: String?): String {
        val (code, out, err) = exec(listOf(bin) + args(method, path, body != null), env, body) ?: throw ForgeError("${bin.substringAfterLast('/')} didn't answer")
        if (code != 0) {
            val msg = err.ifBlank { out }.trim()
            throw ForgeError("${bin.substringAfterLast('/')}: " + msg.take(300), Regex("""HTTP (\d{3})""").find(msg)?.groupValues?.get(1)?.toInt())
        }
        return out
    }
}

fun glabApi(glab: String, host: String, env: Map<String, String> = System.getenv()) = CliApi(glab, env) { m, p, body ->
    listOf("api", "--hostname", host, "--method", m) + (if (body) listOf("-H", "Content-Type: application/json", "--input", "-") else emptyList()) + p
}

fun ghApi(gh: String, host: String, env: Map<String, String> = System.getenv()) = CliApi(gh, env) { m, p, body ->
    listOf("api", "--hostname", host, "--method", m) + (if (body) listOf("-H", "Content-Type: application/json", "--input", "-") else emptyList()) + p
}

/** `tea api` (newer tea versions) with the named login; it prefixes /api/v1 itself. */
fun teaApi(tea: String, login: String, env: Map<String, String> = System.getenv()) = CliApi(tea, env) { m, p, body ->
    listOf("api", "--login", login, "-X", m) + (if (body) listOf("-d", "@-") else emptyList()) + "/$p"
}

/** Whether `cli` (glab or gh) is signed in to `host`. */
fun cliSignedIn(cli: String, host: String, env: Map<String, String> = System.getenv()) =
    binaryFound(cli) && exec(listOf(cli, "auth", "status", "--hostname", host), env, null, 10)?.first == 0

/** tea's logins (`tea logins list --output json`): host -> login name. */
fun teaLogins(tea: String, env: Map<String, String> = System.getenv()): Map<String, String> {
    if (!binaryFound(tea)) return emptyMap()
    val (code, out) = exec(listOf(tea, "logins", "list", "--output", "json"), env, null, 10) ?: return emptyMap()
    return if (code == 0) parseTeaLogins(out.trim()) else emptyMap()
}

/** Each login object's URL host -> its name, whatever the fields are called. */
fun parseTeaLogins(json: String): Map<String, String> {
    val logins = linkedMapOf<String, String>()
    fun walk(e: JsonElement) {
        when {
            e.isJsonArray -> e.asJsonArray.forEach(::walk)
            e.isJsonObject -> {
                val fields = e.asJsonObject.entrySet().filter { it.value.isJsonPrimitive }.associate { it.key.lowercase() to it.value.asString }
                val host = fields["url"]?.let { runCatching { URI(it) }.getOrNull() }?.takeIf { it.scheme == "http" || it.scheme == "https" }?.host
                val name = fields["name"]
                if (host != null && name != null) logins.putIfAbsent(host.lowercase(), name)
                e.asJsonObject.entrySet().filter { !it.value.isJsonPrimitive }.forEach { walk(it.value) }
            }
        }
    }
    runCatching { walk(JsonParser.parseString(json)) }
    return logins
}

/** A command's (exit code, stdout, stderr) with `input` on stdin; null if it can't run or times out. */
private fun exec(cmd: List<String>, env: Map<String, String>, input: String?, timeoutSec: Long = 60): Triple<Int, String, String>? = try {
    val p = ProcessBuilder(cmd).apply { environment().clear(); environment().putAll(env) }.start()
    val err = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().readText() }
    p.outputStream.use { if (input != null) it.write(input.toByteArray()) }
    var timedOut = false
    val killer = CompletableFuture.runAsync({ if (p.isAlive) { timedOut = true; p.destroyForcibly() } },
        CompletableFuture.delayedExecutor(timeoutSec, TimeUnit.SECONDS))
    val out = p.inputStream.bufferedReader().readText()
    p.waitFor()
    killer.cancel(false)
    if (timedOut) null else Triple(p.exitValue(), out, err.get())
} catch (_: Exception) {
    null
}
