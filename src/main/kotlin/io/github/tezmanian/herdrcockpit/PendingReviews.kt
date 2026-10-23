// GitHub and Gitea: their providers, and findings as one pending review on the pull request. Line comments where the
// diff shows the line, the rest in the review's text. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpResponse

/**
 * The pending review's body: `comment` turns a finding on a diff line into a line comment. `event` is how the forge
 * spells "pending" (Gitea "PENDING"; GitHub: none). `inline` false puts every finding into the text.
 */
fun pendingReview(cr: ChangeRequest, findings: List<Finding>, event: String?, inline: Boolean = true, comment: (Finding) -> JsonObject): String {
    val (on, off) = findings.partition { inline && cr.files[it.path]?.lines?.containsKey(it.line) == true }
    return JsonObject().apply {
        addProperty("commit_id", cr.headSha)
        event?.let { addProperty("event", it) }
        if (off.isNotEmpty()) addProperty("body", off.joinToString("\n\n---\n\n") { placedNote(it) })
        if (on.isNotEmpty()) add("comments", JsonArray().apply { on.forEach { add(comment(it)) } })
    }.toString()
}

/** Posts the review; when the forge refuses a line comment, once more with everything in the text. */
private fun postReview(api: ForgeApi, path: String, body: (Boolean) -> String) {
    try {
        api.call("POST", path, body(true))
    } catch (e: ForgeError) {
        if (e.status == 401 || e.status == 403) throw e
        val plain = body(false)
        if (plain == body(true)) throw e
        api.call("POST", path, plain)
    }
}

private fun pullRef(o: JsonObject) = ChangeRef(o.get("number").asInt, o.str("title") ?: "",
    o.child("head")?.str("ref") ?: "", o.child("base")?.str("ref") ?: "", o.child("head")?.str("sha"))

class GitHubReview(private val api: ForgeApi, private val repo: RemoteRepo) : ForgeReview {
    private val r = repo.repos

    private fun list(path: String) = JsonParser.parseString(api.call("GET", path, null)).asJsonArray.map { it.asJsonObject }
        .filter { it.str("state") == "open" }.map(::pullRef)

    override fun candidates(branch: String?, head: String?): List<ChangeRef> {
        val withHead = head?.let {
            try {
                list("$r/commits/${enc(it)}/pulls")
            } catch (e: ForgeError) {
                if (e.status == 404 || e.status == 422) emptyList() else throw e // not pushed
            }
        }.orEmpty()
        val owner = repo.path.substringBefore('/')
        val fromBranch = branch?.let { list("$r/pulls?state=open&per_page=100&head=" + enc("$owner:$it")) }.orEmpty()
        return bestCandidates(withHead, fromBranch, head)
    }

    override fun load(number: Int): ChangeRequest {
        val pr = JsonParser.parseString(api.call("GET", "$r/pulls/$number", null)).asJsonObject
        val files = linkedMapOf<String, ChangedFile>()
        // GitHub lists at most 3000 files: 30 pages of 100.
        for (page in 1..30) {
            val list = JsonParser.parseString(api.call("GET", "$r/pulls/$number/files?per_page=100&page=$page", null)).asJsonArray
            for (f in list.map { it.asJsonObject }) {
                if (f.str("status") == "removed") continue
                val name = f.str("filename") ?: continue
                files[name] = ChangedFile(f.str("previous_filename") ?: name, diffLines(f.str("patch") ?: ""))
            }
            if (list.size() < 100) break
        }
        return ChangeRequest(number, pr.str("title") ?: "", pr.str("html_url") ?: "", pr.child("head")?.str("sha") ?: "", files)
    }

    override fun send(cr: ChangeRequest, findings: List<Finding>): Int {
        postReview(api, "$r/pulls/${cr.number}/reviews") { inline ->
            pendingReview(cr, findings, null, inline) { f ->
                JsonObject().apply {
                    addProperty("path", f.path); addProperty("line", f.line); addProperty("side", "RIGHT"); addProperty("body", inertNote(f.body))
                }
            }
        }
        return findings.size
    }
}

class GiteaReview(private val api: ForgeApi, repo: RemoteRepo) : ForgeReview {
    private val r = repo.repos

    // Gitea only maps a commit to its merged pull request, so open ones are matched from the list.
    override fun candidates(branch: String?, head: String?): List<ChangeRef> {
        val open = mutableListOf<ChangeRef>()
        // shortcut: the first 1000 open pull requests. Upgrade when a repository has more.
        for (page in 1..20) {
            val list = JsonParser.parseString(api.call("GET", "$r/pulls?state=open&limit=50&page=$page", null)).asJsonArray
            open += list.map { pullRef(it.asJsonObject) }
            if (list.size() < 50) break
        }
        return bestCandidates(open.filter { head != null && it.sha == head }, open.filter { branch != null && it.source == branch }, head)
    }

    override fun load(number: Int): ChangeRequest {
        val pr = JsonParser.parseString(api.call("GET", "$r/pulls/$number", null)).asJsonObject
        val files = splitUnifiedDiff(api.call("GET", "$r/pulls/$number.diff", null))
        return ChangeRequest(number, pr.str("title") ?: "", pr.str("html_url") ?: "", pr.child("head")?.str("sha") ?: "", files)
    }

    override fun send(cr: ChangeRequest, findings: List<Finding>): Int {
        postReview(api, "$r/pulls/${cr.number}/reviews") { inline ->
            pendingReview(cr, findings, "PENDING", inline) { f ->
                JsonObject().apply { addProperty("path", f.path); addProperty("new_position", f.line); addProperty("body", inertNote(f.body)) }
            }
        }
        return findings.size
    }
}

private const val PENDING_OFF_DIFF = "Findings on lines outside the diff go into the review's text, naming the file and line."

class GitHubForge : ForgeProvider {
    override val title = "GitHub"
    override val sign = "#"
    override val request = "pull request"
    override val pending = "Pending Review"
    override val sendsAs = "A pending review on"
    override val offDiff = PENDING_OFF_DIFF
    override val cli = "gh"
    override val tokenHint = "Fine-grained token with pull requests read and write."
    override val knownHosts = setOf("github.com")

    override fun cliSignIn(host: String) = "gh auth login --hostname $host"
    override fun cliApi(bin: String, host: String, env: Map<String, String>) = bin.takeIf { cliSignedIn(it, host, env) }?.let { ghApi(it, host, env) }
    /** GitHub Enterprise names itself in a header of /api/v3/meta. */
    override fun probe(get: (String) -> HttpResponse<String>?) = get("/api/v3/meta")?.headers()?.firstValue("X-GitHub-Enterprise-Version")?.isPresent == true
    override fun apiBase(web: String) = if (URI(web).host.equals("github.com", true)) "https://api.github.com" else "$web/api/v3"
    override fun headers() = mapOf("Accept" to "application/vnd.github+json")
    override fun review(api: ForgeApi, repo: RemoteRepo): ForgeReview = GitHubReview(api, repo)
}

class GiteaForge : ForgeProvider {
    override val title = "Gitea"
    override val sign = "#"
    override val request = "pull request"
    override val pending = "Pending Review"
    override val sendsAs = "A pending review on"
    override val offDiff = PENDING_OFF_DIFF
    override val cli = "tea"
    override val tokenHint = "Access token with repository read and write."
    override val knownHosts = setOf("codeberg.org", "gitea.com")

    override fun cliSignIn(host: String) = "tea login add --url https://$host"
    override fun cliApi(bin: String, host: String, env: Map<String, String>) = teaLogins(bin, env)[host.lowercase()]?.let { teaApi(bin, it, env) }
    /** Gitea tells its version to anyone. */
    override fun probe(get: (String) -> HttpResponse<String>?) =
        get("/api/v1/version")?.let { it.statusCode() == 200 && jsonObject(it.body())?.has("version") == true } == true
    override fun apiBase(web: String) = "$web/api/v1"
    override fun auth(token: String) = "token $token"
    override fun review(api: ForgeApi, repo: RemoteRepo): ForgeReview = GiteaReview(api, repo)
}
