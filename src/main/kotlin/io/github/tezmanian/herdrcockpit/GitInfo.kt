// Branch, upstream sync and working-tree state per directory, cached briefly. Uses the git CLI directly
// (fast, works for folders that aren't part of the project). No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class GitInfo(
    val branch: String?,
    val upstream: String?,
    val detached: Boolean,
    val ahead: Int,
    val behind: Int,
    var staged: Int = 0,
    var modified: Int = 0,
    var untracked: Int = 0,
    var conflicts: Int = 0,
    val changes: Int,
    var lastCommitAt: Long? = null,
    var lastCommitSubject: String? = null,
)

private val GIT = findTool("git")
private const val TTL_MS = 8000L

/**
 * Git reads each repository's own config, and some settings make even read-only commands run programs.
 * Defense in depth on every call (no shell involved): no fsmonitor, no signature checks, no lazy fetch,
 * no hooks, every repo filter driver blanked, no submodules, no optional locks. This closes the known
 * routes but can't be proven complete, so callers also skip git entirely in untrusted projects.
 */
private val SAFE = listOf(
    "--no-pager", "--no-optional-locks",
    "-c", "core.fsmonitor=false",
    "-c", "log.showSignature=false",
    "-c", "protocol.allow=never",
    "-c", "core.hooksPath=/dev/null",
)

private val gitEnv: Map<String, String> = System.getenv() + mapOf("GIT_TERMINAL_PROMPT" to "0")

private fun exec(args: List<String>): Pair<Int, String> = runCommand(listOf(GIT) + args, gitEnv, 3) ?: (-1 to "")

/**
 * `-c` overrides that disable every filter driver the repo's config defines (reading config runs nothing).
 * Fails closed: null (= don't run git) unless the config was read and every driver name can be overridden.
 */
fun filterOverrides(cwd: String): List<String>? {
    val (code, out) = exec(listOf("-C", cwd, "config", "--null", "--get-regexp", "^filter\\."))
    if (code != 0 && !(code == 1 && out.isEmpty())) return null
    val names = linkedSetOf<String>()
    for (entry in out.split('\u0000')) {
        val key = entry.substringBefore('\n')
        if (key.isEmpty()) continue
        val m = Regex("""^filter\.(.+)\.[^.]+$""").find(key) ?: return null
        names.add(m.groupValues[1])
    }
    val args = mutableListOf<String>()
    for (name in names) {
        if (Regex("""[=\s]""").containsMatchIn(name)) return null
        for (v in listOf("clean=", "smudge=", "process=", "required=false")) args += listOf("-c", "filter.$name.$v")
    }
    return args
}

private fun run(cwd: String, args: List<String>): String? {
    val filters = filterOverrides(cwd) ?: return null
    val (code, out) = exec(SAFE + filters + listOf("-C", cwd) + args)
    return if (code == 0) out else null
}

private val UNMERGED = setOf("DD", "AU", "UD", "UA", "DU", "AA", "UU")

/** Parses `git status --porcelain=v1 --branch` output. */
fun parseStatus(out: String): GitInfo {
    val lines = out.split('\n').filter { it.isNotEmpty() }.toMutableList()
    // "## main...origin/main [ahead 1, behind 2]" | "## main" | "## HEAD (no branch)" | "## No commits yet on main"
    val head = if (lines.firstOrNull()?.startsWith("## ") == true) lines.removeAt(0).substring(3) else ""
    val refs = head.substringBefore(" [").removePrefix("No commits yet on ")
    var branch: String? = refs.substringBefore("...").ifEmpty { null }
    val upstream = refs.substringAfter("...", "").ifEmpty { null }
    val detached = branch?.startsWith("HEAD (no branch)") == true
    if (detached) branch = "detached HEAD"
    val info = GitInfo(
        branch = branch,
        upstream = upstream,
        detached = detached,
        ahead = Regex("""ahead (\d+)""").find(head)?.groupValues?.get(1)?.toInt() ?: 0,
        behind = Regex("""behind (\d+)""").find(head)?.groupValues?.get(1)?.toInt() ?: 0,
        changes = lines.size,
    )
    for (l in lines) {
        val xy = l.take(2)
        when {
            xy == "??" -> info.untracked++
            xy in UNMERGED -> info.conflicts++
            else -> {
                if (xy[0] != ' ') info.staged++
                if (xy.length > 1 && xy[1] != ' ') info.modified++
            }
        }
    }
    return info
}

private fun load(cwd: String): GitInfo? {
    if (!File(cwd).isDirectory) return null
    val status = run(cwd, listOf("status", "--porcelain=v1", "--branch", "--untracked-files=normal", "--ignore-submodules=all")) ?: return null
    val info = parseStatus(status)
    val log = run(cwd, listOf("log", "-1", "--format=%ct%x09%s"))?.trim().orEmpty()
    val ct = log.substringBefore('\t').toLongOrNull()
    if (ct != null) {
        info.lastCommitAt = ct * 1000
        info.lastCommitSubject = log.substringAfter('\t')
    }
    return info
}

/** Cached git info per directory; loads in the background and calls onUpdate when something changed. */
class GitCache(private val onUpdate: () -> Unit) {
    private class Entry(var at: Long = 0, var info: GitInfo? = null, var pending: Boolean = false)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "herdr-git").apply { isDaemon = true } }

    /** Returns cached info right away (possibly stale or null) and refreshes in the background. */
    fun get(cwd: String?): GitInfo? {
        if (cwd == null) return null
        val e = cache.getOrPut(cwd) { Entry() }
        synchronized(e) {
            if (System.currentTimeMillis() - e.at < TTL_MS || e.pending) return e.info
            e.pending = true
        }
        pool.execute {
            val info = runCatching { load(cwd) }.getOrNull()
            val changed: Boolean
            synchronized(e) {
                changed = info != e.info
                e.info = info
                e.at = System.currentTimeMillis()
                e.pending = false
            }
            if (changed) onUpdate()
        }
        return e.info
    }

    fun shutdown() = pool.shutdownNow()
}

/** An uncommitted change: `path` relative to the repo root, porcelain XY status, `from` the old path of a rename. */
data class GitChange(val path: String, val status: String, val from: String? = null)

/** Parses `git status --porcelain=v1 -z`: "XY path\0", renames and copies "XY new\0old\0". */
fun parseChanges(out: String): List<GitChange> {
    val tokens = out.split('\u0000').filter { it.isNotEmpty() }
    val changes = mutableListOf<GitChange>()
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i++]
        if (t.length < 4) continue
        val xy = t.substring(0, 2)
        val from = if ('R' in xy || 'C' in xy) tokens.getOrNull(i++) else null
        changes += GitChange(t.substring(3), xy, from)
    }
    return changes
}

fun gitRoot(cwd: String): String? = run(cwd, listOf("rev-parse", "--show-toplevel"))?.trim()?.ifEmpty { null }
fun gitChanges(root: String): List<GitChange>? = run(root, listOf("status", "--porcelain=v1", "-z", "--untracked-files=all"))?.let(::parseChanges)
/** The file at HEAD (shortcut: decoded as UTF-8 text, so binary files diff as garbage); null if it isn't in HEAD. */
fun gitShowHead(root: String, path: String): String? = run(root, listOf("show", "HEAD:$path"))
fun gitBranch(root: String): String? = run(root, listOf("symbolic-ref", "--short", "-q", "HEAD"))?.trim()?.ifEmpty { null }
fun gitHead(root: String): String? = run(root, listOf("rev-parse", "HEAD"))?.trim()?.ifEmpty { null }
/** The files `target...HEAD` changes, relative to `root`. */
fun gitChangedFiles(root: String, target: String): List<String>? =
    run(root, listOf("diff", "--name-only", "-z", "$target...HEAD", "--"))?.split('\u0000')?.filter { it.isNotEmpty() }
fun gitRemoteUrl(root: String): String? = run(root, listOf("remote", "get-url", "origin"))?.trim()?.ifEmpty { null }
/** The remote's default branch ("origin/main"), if the clone recorded it. */
fun gitDefaultBranch(root: String): String? = run(root, listOf("symbolic-ref", "--short", "-q", "refs/remotes/origin/HEAD"))?.trim()?.ifEmpty { null }
