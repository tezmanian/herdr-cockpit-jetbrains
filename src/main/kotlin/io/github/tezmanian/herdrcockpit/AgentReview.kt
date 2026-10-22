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

/** What the agent writes: the findings file's format, for the `{format}` variable and the rules added to a template without `{out}`. */
const val FINDINGS_FORMAT =
    """[{"path": "file relative to the repo root", "line": line number in the reviewed version, "body": "the finding"}]"""

private const val RULES =
    "Only read: don't edit or create any file other than {out}, don't commit, push or post comments anywhere.\n" +
        "Write your findings to {out} as a JSON array and nothing else: {format}, or [] if you found nothing. " +
        "In each body, say what goes wrong and in which situation."

/** "Review Branch with Agent…": the template's default (Settings | Tools | Herdr Cockpit | Review prompts). */
const val DEFAULT_BRANCH_TEMPLATE =
    "Review the changes of branch {branch} against {target} in {repo}.\n\n" +
        "Run `{diff}` there and read the code around each change, not only the changed lines: callers, the types involved, " +
        "the tests.\n" +
        "Changed files: {files}\n\n" +
        "Additional instructions: {instructions}\n\n" + RULES

/** "Review with Agent…" on a merge request's changes: the template's default. */
const val DEFAULT_CHANGES_TEMPLATE =
    "Review these merge request changes in {repo}.\n\n" +
        "Run `{diff}` there (if git doesn't know the commits, fetch them first, e.g. `git fetch origin`) and read the code " +
        "around each change as of {head} (`git show {head}:<path>`), not only the changed lines. Line numbers refer to {head}.\n\n" +
        "Additional instructions: {instructions}\n\n" + RULES

/** The variables a review template can use, with what they stand for (shown in the settings). */
val REVIEW_VARIABLES = listOf(
    "repo" to "the repository's folder",
    "branch" to "the branch under review (branch reviews only)",
    "target" to "what it's compared against (branch reviews only)",
    "base" to "the commit or ref the changes start from",
    "head" to "the commit the changes end at",
    "diff" to "the git diff command for exactly these changes",
    "files" to "the changed files, quoted for the shell",
    "instructions" to "what you typed in the review dialog",
    "out" to "the file the findings go to",
    "format" to "the findings file's JSON format",
)

/**
 * `template` with each `{name}` of `vars` replaced, in one pass (a value's own braces stay as they are). A line whose
 * variables are all empty is left out, so "Additional instructions: {instructions}" disappears when there are none. A
 * template without `{out}` gets the rules for the findings file appended: without them the review never comes back.
 */
fun fillTemplate(template: String, vars: Map<String, String>): String {
    val known = Regex("\\{(" + vars.keys.joinToString("|") { Regex.escape(it) } + ")}")
    val full = if (template.contains("{out}")) template else template.trimEnd() + "\n\n" + RULES
    return full.lines().mapNotNull { line ->
        val used = known.findAll(line).map { vars.getValue(it.groupValues[1]) }.toList()
        if (used.isNotEmpty() && used.all { it.isBlank() }) null else known.replace(line) { vars.getValue(it.groupValues[1]) }
    }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

private fun quoted(paths: List<String>) = paths.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }

private fun reviewVars(root: String, base: String, head: String, diff: String, paths: List<String>, out: File, extra: String, branch: String = "", target: String = "") =
    mapOf("repo" to root, "branch" to branch, "target" to target, "base" to base, "head" to head, "diff" to diff,
        "files" to quoted(paths), "instructions" to extra.trim(), "out" to out.path, "format" to FINDINGS_FORMAT)

/** A branch against `target`; `paths` are the files it changes (may be empty when git couldn't tell). */
fun reviewPrompt(root: String, branch: String, target: String, out: File, extra: String = "", paths: List<String> = emptyList(), template: String = DEFAULT_BRANCH_TEMPLATE) =
    fillTemplate(template, reviewVars(root, target, "HEAD", "git diff $target...HEAD", paths, out, extra, branch, target))

/** A merge request's changes picked in the IDE: `base`..`head` limited to `paths` (relative to `root`). */
fun reviewChangesPrompt(root: String, base: String, head: String, paths: List<String>, out: File, extra: String = "", template: String = DEFAULT_CHANGES_TEMPLATE) =
    fillTemplate(template, reviewVars(root, base, head, "git diff $base $head -- ${quoted(paths)}", paths, out, extra))

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
