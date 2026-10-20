// File references in a terminal line (`src/foo.ts:42:7`, `./a/b.py(12)`, `~/x.md`), ported from upstream
// src/termLinks.ts: a click opens the file at that line. Relative paths resolve against the pane's folder, then the
// space's; only files that exist become links. No IntelliJ imports.
package io.github.tezmanian.herdrcockpit

import java.io.File

/** Where the link is in the line (start index and length, including the line/column suffix). */
data class FileRef(val start: Int, val length: Int, val path: String, val line: Int? = null, val col: Int? = null)

private val REF =
    Regex("""(?<![\w/.~@+-])((?:~/|\.{1,2}/|/)?(?:[\w@+.-]+/)*[\w@+-][\w@+.-]*)(?::(\d+)(?::(\d+))?|\((\d+)(?:, ?(\d+))?\)|#L(\d+))?""")

/** Path-like tokens: with a slash, or a name with an extension (`README.md`). */
fun findFileRefs(text: String): List<FileRef> {
    val out = mutableListOf<FileRef>()
    for (m in REF.findAll(text)) {
        val g = m.groupValues
        var path = g[1]
        var length = m.value.length
        if (g[2].isEmpty() && g[4].isEmpty() && g[6].isEmpty()) {
            // Sentence punctuation after a path isn't part of it.
            val trimmed = path.trimEnd('.')
            length -= path.length - trimmed.length
            path = trimmed
        }
        if (path.isEmpty() || path == "." || path == ".." || Regex("""^\d+(\.\d+)*$""").matches(path)) continue // versions, numbers
        if ('/' !in path && !Regex("""\.[A-Za-z][\w-]{0,9}$""").containsMatchIn(path)) continue
        if (Regex("^[a-z]\\.[a-z]$", RegexOption.IGNORE_CASE).matches(path)) continue // e.g, i.e
        if ('/' !in path && Regex("""^[\w.-]+\.(com|org|net|io|dev|ai)$""").matches(path)) continue // a host name
        val line = (g[2].ifEmpty { g[4].ifEmpty { g[6] } }).toIntOrNull()?.takeIf { it > 0 }
        val col = g[3].ifEmpty { g[5] }.toIntOrNull()?.takeIf { it > 0 }
        out += FileRef(m.range.first, length, path, line, col)
    }
    return out
}

/** The existing file a reference points to: absolute or `~/`, else relative to the first base that has it. */
fun resolveRef(ref: FileRef, bases: List<String>, home: String, isFile: (File) -> Boolean = File::isFile): File? {
    val p = if (ref.path.startsWith("~/")) "$home/${ref.path.substring(2)}" else ref.path
    val candidates = if (p.startsWith("/")) listOf(File(p)) else bases.map { File(it, p) }
    return candidates.map { it.normalize() }.firstOrNull(isFile)
}
