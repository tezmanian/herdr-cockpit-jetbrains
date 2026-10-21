// "Send to GitLab as Drafts…": an agent review's findings as draft notes on the merge request. Nothing is published;
// you submit the review in GitLab or the IDE. Through `glab api` when glab is signed in to the host, else REST with the
// IDE's GitLab account or a token you enter. The findings are the agent's output: draftsFor() only takes files the
// merge request changes, and note texts lose quick actions and mentions. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class GitLabError(message: String, val status: Int? = null) : Exception(message)

/** One GitLab API call; `path` is relative to /api/v4. Throws GitLabError. */
fun interface GitLabApi {
    fun call(method: String, path: String, body: String?): String
}

/** A GitLab project from a git remote URL (https, ssh:// or scp-like): its host and full path ("group/sub/project"). */
data class GitLabProject(val host: String, val path: String) {
    val api get() = "projects/" + enc(path)
}

private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")

// shortcut: the web/API host is taken to be the remote's host on https without its port. Upgrade when a GitLab with
// a different API host or port shows up.
fun gitlabProject(remote: String): GitLabProject? {
    val r = remote.trim().removeSuffix("/").removeSuffix(".git")
    val m = Regex("""^(?:https?|ssh)://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(.+)$""").find(r)
        ?: Regex("""^(?:[^@/:]+@)?([^/:]+):([^/].*)$""").find(r) ?: return null
    val (host, path) = m.destructured
    val parts = path.split('/')
    if (!Regex("^[A-Za-z0-9.-]+$").matches(host) || parts.size < 2 || !parts.all { SEGMENT.matches(it) && it != ".." }) return null
    return GitLabProject(host.lowercase(), path)
}

/** `glab api` with glab's own sign-in. The body goes over stdin, never through a shell. */
class GlabApi(private val glab: String, private val host: String, private val env: Map<String, String> = System.getenv()) : GitLabApi {
    override fun call(method: String, path: String, body: String?): String {
        val cmd = listOf(glab, "api", "--hostname", host, "--method", method) +
            (if (body != null) listOf("-H", "Content-Type: application/json", "--input", "-") else emptyList()) + path
        val (code, out, err) = exec(cmd, env, body) ?: throw GitLabError("glab didn't answer")
        if (code != 0) throw GitLabError("glab: " + err.ifBlank { out }.trim().take(300))
        return out
    }
}

/** REST with an access token (scope api); Bearer takes personal access tokens and OAuth tokens alike. */
class RestApi(private val base: String, private val token: String) : GitLabApi {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun call(method: String, path: String, body: String?): String {
        val req = HttpRequest.newBuilder(URI.create("$base/$path")).timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
        val res = try { http.send(req, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { throw GitLabError("${e.message}") }
        if (res.statusCode() !in 200..299) throw GitLabError("HTTP ${res.statusCode()}: ${res.body().take(300)}", res.statusCode())
        return res.body()
    }
}

/** `glab` (a path or bare name) if it's installed and signed in to `host`, else null. */
fun signedInGlab(glab: String, host: String): String? =
    glab.takeIf { binaryFound(it) && exec(listOf(it, "auth", "status", "--hostname", host), System.getenv(), null, 10)?.first == 0 }

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

private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

/**
 * An open merge request: its diff commits, and per changed file (new path) its old path and the new-version lines
 * the diff shows: added lines map to null, context lines to their old line number.
 */
data class MergeRequest(
    val iid: Int, val title: String, val url: String,
    val baseSha: String, val startSha: String, val headSha: String,
    val files: Map<String, Pair<String, Map<Int, Int?>>>,
)

/** The open merge request whose head is `head`, else the one from `branch`; null when there's none. */
fun findMergeRequest(api: GitLabApi, project: GitLabProject, branch: String?, head: String?): MergeRequest? {
    val list = JsonParser.parseString(api.call("GET", "${project.api}/merge_requests?state=opened&per_page=100" +
        (branch?.let { "&source_branch=" + enc(it) } ?: ""), null)).asJsonArray.map { it.asJsonObject }
    val hit = list.firstOrNull { head != null && it.str("sha") == head } ?: list.firstOrNull { branch != null && it.str("source_branch") == branch } ?: return null
    val iid = hit.get("iid").asInt
    val mr = JsonParser.parseString(api.call("GET", "${project.api}/merge_requests/$iid", null)).asJsonObject
    val refs = mr.get("diff_refs")?.takeIf { it.isJsonObject }?.asJsonObject ?: throw GitLabError("Merge request !$iid has no diff yet.")
    val files = linkedMapOf<String, Pair<String, Map<Int, Int?>>>()
    // shortcut: at most 20 pages of 100 files. Upgrade when merge requests get bigger than that.
    for (page in 1..20) {
        val diffs = JsonParser.parseString(api.call("GET", "${project.api}/merge_requests/$iid/diffs?per_page=100&page=$page", null)).asJsonArray
        for (d in diffs.map { it.asJsonObject }) {
            if (d.get("deleted_file")?.asBoolean == true) continue
            files[d.str("new_path") ?: continue] = (d.str("old_path") ?: d.str("new_path")!!) to diffLines(d.str("diff") ?: "")
        }
        if (diffs.size() < 100) break
    }
    return MergeRequest(iid, mr.str("title") ?: "", mr.str("web_url") ?: "", refs.str("base_sha") ?: "", refs.str("start_sha") ?: "",
        refs.str("head_sha") ?: "", files)
}

private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString

/** The new-version lines a unified diff shows: added -> null, context -> its old line number. */
fun diffLines(diff: String): Map<Int, Int?> {
    val lines = HashMap<Int, Int?>()
    var old = 0
    var new = 0
    for (l in diff.lineSequence()) {
        val h = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(l)
        when {
            h != null -> { old = h.groupValues[1].toInt(); new = h.groupValues[2].toInt() }
            new == 0 -> {}
            l.startsWith("+") -> lines[new++] = null
            l.startsWith("-") -> old++
            l.startsWith(" ") -> lines[new++] = old++
        }
    }
    return lines
}

/** Text that can't act on GitLab: no quick actions ("/merge" at a line start) and no mentions ("@all"). */
fun inertNote(text: String) =
    text.lines().joinToString("\n") { it.replace(Regex("""^(\s*)/"""), "$1\\\\/") }.replace(Regex("""@(?=[\w.-])"""), "@​")

/** The findings on files the merge request changes, each with its draft note body (positioned when its line is in the diff). */
fun draftsFor(findings: List<Finding>, mr: MergeRequest): List<Pair<Finding, String>> = findings.mapNotNull { f ->
    val (oldPath, lines) = mr.files[f.path] ?: return@mapNotNull null
    f to if (f.line in lines) JsonObject().apply {
        addProperty("note", inertNote(f.body))
        add("position", JsonObject().apply {
            addProperty("position_type", "text")
            addProperty("base_sha", mr.baseSha); addProperty("start_sha", mr.startSha); addProperty("head_sha", mr.headSha)
            addProperty("old_path", oldPath); addProperty("new_path", f.path)
            addProperty("new_line", f.line)
            lines[f.line]?.let { addProperty("old_line", it) }
        })
    }.toString() else generalNote(f)
}

/** A draft on the merge request as a whole that names the finding's place. */
fun generalNote(f: Finding) = JsonObject().apply { addProperty("note", inertNote("`${f.path}:${f.line}`\n\n${f.body}")) }.toString()

/** Posts the drafts; one GitLab refuses on its line goes on the merge request as a whole. Returns how many were sent. */
fun sendDrafts(api: GitLabApi, project: GitLabProject, mr: MergeRequest, drafts: List<Pair<Finding, String>>): Int {
    val path = "${project.api}/merge_requests/${mr.iid}/draft_notes"
    for ((f, body) in drafts) {
        try {
            api.call("POST", path, body)
        } catch (e: GitLabError) {
            if (e.status == 401 || e.status == 403 || !body.contains("\"position\"")) throw e
            api.call("POST", path, generalNote(f))
        }
    }
    return drafts.size
}
