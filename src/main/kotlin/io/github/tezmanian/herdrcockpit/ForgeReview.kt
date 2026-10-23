// "Send to <forge> as …": an agent review's findings as unpublished comments on the merge/pull request, the same flow
// for GitLab (draft notes), GitHub and Gitea (a pending review). Nothing is published; you submit it on the forge. The
// findings are the agent's output: only files the request changes are sent, and texts lose quick actions and
// mentions. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject

/** An open merge/pull request the review may belong to. */
data class ChangeRef(val number: Int, val title: String, val source: String, val target: String, val sha: String?)

/** A changed file: its old path and the new-version lines the diff shows (added -> null, context -> old line). */
data class ChangedFile(val oldPath: String, val lines: Map<Int, Int?>)

/** A merge/pull request with its commits and changed files (by new path). GitLab also needs base and start. */
data class ChangeRequest(
    val number: Int, val title: String, val url: String, val headSha: String,
    val files: Map<String, ChangedFile>, val baseSha: String = "", val startSha: String = "",
)

/** One forge's side of the flow. */
interface ForgeReview {
    /** Open requests that may hold the reviewed `head` (or come from `branch`), best first; more than one: ask. */
    fun candidates(branch: String?, head: String?): List<ChangeRef>
    fun load(number: Int): ChangeRequest
    /** Posts `findings` (all on changed files) unpublished: on their line when the diff shows it, else naming it. */
    fun send(cr: ChangeRequest, findings: List<Finding>): Int
}

/** Those whose head is `head`, else those from `branch`, else those that contain `head` (pushed to since). */
fun bestCandidates(withHead: List<ChangeRef>, fromBranch: List<ChangeRef>, head: String?): List<ChangeRef> =
    (withHead + fromBranch).distinctBy { it.number }.filter { head != null && it.sha == head }
        .ifEmpty { fromBranch }.ifEmpty { withHead }

/** The new-version lines a unified diff's hunks show: added -> null, context -> its old line number. */
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

/**
 * The files of a `git diff` (a whole merge/pull request's diff): new path -> old path and lines. Deleted files are
 * left out. shortcut: paths git quotes (unusual characters) aren't unquoted; such a file just gets no line comments.
 */
fun splitUnifiedDiff(diff: String): Map<String, ChangedFile> {
    val files = linkedMapOf<String, ChangedFile>()
    var old: String? = null
    var new: String? = null
    val hunks = StringBuilder()
    fun flush() {
        val n = new
        if (n != null && n != "/dev/null") files[n] = ChangedFile(old?.takeIf { it != "/dev/null" } ?: n, diffLines(hunks.toString()))
        old = null; new = null; hunks.clear()
    }
    for (l in diff.lineSequence()) {
        when {
            l.startsWith("diff --git ") -> {
                flush()
                // Pure renames and mode changes have no ---/+++ lines.
                Regex("""^diff --git a/(.+) b/(.+)$""").find(l)?.let { old = it.groupValues[1]; new = it.groupValues[2] }
            }
            hunks.isEmpty() && l.startsWith("--- ") -> old = l.removePrefix("--- ").removePrefix("a/")
            hunks.isEmpty() && l.startsWith("+++ ") -> new = l.removePrefix("+++ ").removePrefix("b/")
            hunks.isEmpty() && l.startsWith("rename from ") -> old = l.removePrefix("rename from ")
            hunks.isEmpty() && l.startsWith("rename to ") -> new = l.removePrefix("rename to ")
            l.startsWith("@@") || hunks.isNotEmpty() -> hunks.append(l).append('\n')
        }
    }
    flush()
    return files
}

/** Text that can't act on the forge: no quick actions ("/merge" at a line start) and no mentions ("@all"). */
fun inertNote(text: String) =
    text.lines().joinToString("\n") { it.replace(Regex("""^(\s*)/"""), "$1\\\\/") }.replace(Regex("""@(?=[\w.-])"""), "@​")

/** A finding off the diff, naming its place. */
fun placedNote(f: Finding) = inertNote("`${f.path}:${f.line}`\n\n${f.body}")

internal fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString
internal fun JsonObject.child(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
