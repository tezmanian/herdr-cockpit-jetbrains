// GitLab: findings as draft notes on the merge request, one per finding. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser

class GitLabReview(private val api: ForgeApi, repo: RemoteRepo) : ForgeReview {
    override val forge = Forge.GitLab
    private val p = repo.gitlab

    private fun list(path: String) = JsonParser.parseString(api.call("GET", path, null)).asJsonArray.map { it.asJsonObject }
        .filter { it.str("state") == "opened" }
        .map { ChangeRef(it.get("iid").asInt, it.str("title") ?: "", it.str("source_branch") ?: "", it.str("target_branch") ?: "", it.str("sha")) }

    override fun candidates(branch: String?, head: String?): List<ChangeRef> {
        val withHead = head?.let {
            try {
                list("$p/repository/commits/${enc(it)}/merge_requests?state=opened")
            } catch (e: ForgeError) {
                if (e.status == 404) emptyList() else throw e // a commit GitLab doesn't have (not pushed)
            }
        }.orEmpty()
        val fromBranch = branch?.let { list("$p/merge_requests?state=opened&per_page=100&source_branch=" + enc(it)) }.orEmpty()
        return bestCandidates(withHead, fromBranch, head)
    }

    override fun load(number: Int): ChangeRequest {
        val mr = JsonParser.parseString(api.call("GET", "$p/merge_requests/$number", null)).asJsonObject
        val refs = mr.child("diff_refs") ?: throw ForgeError("Merge request !$number has no diff yet.")
        val files = linkedMapOf<String, ChangedFile>()
        // shortcut: at most 20 pages of 100 files. Upgrade when merge requests get bigger than that.
        for (page in 1..20) {
            val diffs = JsonParser.parseString(api.call("GET", "$p/merge_requests/$number/diffs?per_page=100&page=$page", null)).asJsonArray
            for (d in diffs.map { it.asJsonObject }) {
                if (d.get("deleted_file")?.asBoolean == true) continue
                val new = d.str("new_path") ?: continue
                files[new] = ChangedFile(d.str("old_path") ?: new, diffLines(d.str("diff") ?: ""))
            }
            if (diffs.size() < 100) break
        }
        return ChangeRequest(number, mr.str("title") ?: "", mr.str("web_url") ?: "", refs.str("head_sha") ?: "", files,
            refs.str("base_sha") ?: "", refs.str("start_sha") ?: "")
    }

    /** One draft per finding; one GitLab refuses on its line goes on the merge request as a whole. */
    override fun send(cr: ChangeRequest, findings: List<Finding>): Int {
        val path = "$p/merge_requests/${cr.number}/draft_notes"
        for (f in findings) {
            val body = draftNote(f, cr)
            try {
                api.call("POST", path, body)
            } catch (e: ForgeError) {
                if (e.status == 401 || e.status == 403 || !body.contains("\"position\"")) throw e
                api.call("POST", path, generalNote(f))
            }
        }
        return findings.size
    }
}

/** The draft note for a finding: positioned when the diff shows its line, else on the merge request naming it. */
fun draftNote(f: Finding, cr: ChangeRequest): String {
    val file = cr.files[f.path]
    if (file == null || f.line !in file.lines) return generalNote(f)
    return JsonObject().apply {
        addProperty("note", inertNote(f.body))
        add("position", JsonObject().apply {
            addProperty("position_type", "text")
            addProperty("base_sha", cr.baseSha); addProperty("start_sha", cr.startSha); addProperty("head_sha", cr.headSha)
            addProperty("old_path", file.oldPath); addProperty("new_path", f.path)
            addProperty("new_line", f.line)
            file.lines[f.line]?.let { addProperty("old_line", it) }
        })
    }.toString()
}

fun generalNote(f: Finding) = JsonObject().apply { addProperty("note", placedNote(f)) }.toString()
