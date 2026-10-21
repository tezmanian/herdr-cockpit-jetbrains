// "Review Branch with Agent…": the prompt, and the findings file the agent writes. Nothing is posted anywhere.
// parseReview() is the trust boundary: the file is the agent's output. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import java.io.File

/** One finding: `path` relative to the repo root, `line` in the branch's version of the file (1-based). */
data class Finding(val path: String, val line: Int, val body: String)

/** Where the agent writes its findings for this branch of this space. */
fun reviewFile(home: File, space: String, branch: String): File =
    File(home, ".herdr-cockpit/reviews/" + "$space-$branch".replace(Regex("[^A-Za-z0-9._-]+"), "_") + ".json")

fun reviewPrompt(root: String, branch: String, target: String, out: File) =
    "Review the changes of branch $branch against $target in $root: run `git diff $target...HEAD` there and read the " +
        "code around them. " + reviewRules(out)

/** A merge request's changes picked in the IDE: `base`..`head` limited to `paths` (relative to `root`). */
fun reviewChangesPrompt(root: String, base: String, head: String, paths: List<String>, out: File) =
    "Review these merge request changes in $root: run `git diff $base $head -- ${paths.joinToString(" ") { "'$it'" }}` " +
        "there (if git doesn't know the commits, fetch them first, e.g. `git fetch origin`) and read the code around " +
        "them as of $head (`git show $head:<path>`); line numbers refer to $head. " + reviewRules(out)

private fun reviewRules(out: File) =
    "Only read: don't edit or create files other than the one below, don't commit, push or post comments anywhere. " +
        "Write your findings to $out as a JSON array and nothing else: " +
        """[{"path": "file relative to the repo root", "line": line number in the reviewed version, "body": "the finding"}]""" +
        ", or [] if you found nothing."

private val HASH = Regex("^[0-9a-f]{7,64}$")

/** The commits a set of changes lies between, from each change's (before, after) revision: the most common of each. */
fun changeRange(revisions: List<Pair<String?, String?>>): Pair<String, String>? {
    fun most(list: List<String?>) = list.filterNotNull().filter { HASH.matches(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val base = most(revisions.map { it.first }) ?: return null
    val head = most(revisions.map { it.second }) ?: return null
    return if (base == head) null else base to head
}

/** The findings in `json` whose path stays inside `root`; null if it isn't a JSON array. */
fun parseReview(json: String, root: File): List<Finding>? {
    val array = runCatching { JsonParser.parseString(json) }.getOrNull()?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    val base = root.canonicalFile
    return array.mapNotNull { e ->
        val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val path = runCatching { o.get("path").asString.trim() }.getOrNull() ?: return@mapNotNull null
        val line = runCatching { o.get("line").asInt }.getOrNull()?.takeIf { it >= 1 } ?: return@mapNotNull null
        val body = runCatching { o.get("body").asString.trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val file = File(base, path).canonicalFile
        if (path.isEmpty() || File(path).isAbsolute || !file.path.startsWith(base.path + File.separator)) return@mapNotNull null
        Finding(file.relativeTo(base).path, line, body.take(4000))
    }.take(500)
}
