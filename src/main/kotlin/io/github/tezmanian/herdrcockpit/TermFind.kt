// Searching a pane's whole history (Herdr's scrollback) for a pane tab's find bar. No IntelliJ imports.
// Port of upstream's termFind.ts.
//
// The tab only holds the screen. Herdr has the history (verified on 0.9.3):
//  - `pane.get` -> `scroll {offset_from_bottom, max_offset_from_bottom, viewport_rows}`: the history has
//    `max_offset_from_bottom + viewport_rows` screen rows, row 0 the oldest.
//  - `pane.selection.read` returns it as text, one line per logical line: a soft-wrapped line comes back joined, so
//    lines are mapped back to rows by wrapping them at the pane's width (`layout`).
//  - `pane.scroll {offset_from_bottom}` scrolls the view there (the top row is then `max - offset`).
//  - Herdr's own `pane.copy_search` is literal, case-sensitive and stops at 1024 matches, so the search runs here.
package io.github.tezmanian.herdrcockpit

import java.util.regex.Pattern

/** Display width of a code point in terminal cells (0 for combining marks, 2 for wide CJK and emoji). */
fun charWidth(c: Int): Int {
    if (c < 0x300) return if (c < 0x20) 0 else 1
    val type = Character.getType(c)
    if (c in 0x200b..0x200f || c in 0xfe00..0xfe0f ||
        type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    ) return 0
    val wide = c in 0x1100..0x115f || c in 0x2e80..0x303e || c in 0x3041..0x33ff || c in 0x3400..0x4dbf || c in 0x4e00..0x9fff ||
        c in 0xa000..0xa4cf || c in 0xac00..0xd7a3 || c in 0xf900..0xfaff || c in 0xfe30..0xfe4f || c in 0xff00..0xff60 ||
        c in 0xffe0..0xffe6 || c in 0x1f300..0x1f64f || c in 0x1f900..0x1f9ff || c in 0x1fa70..0x1faff || c in 0x1f680..0x1f6ff ||
        c in 0x20000..0x3fffd
    return if (wide) 2 else 1
}

/** Row offset and column of a character when its line is wrapped. */
data class RowCol(val row: Int, val col: Int)

/** Where each character of a line lands when it's wrapped at `cols`, per UTF-16 index; `rows` it takes. */
class Layout(line: String, cols: Int) {
    private val pos = arrayOfNulls<RowCol>(line.length)
    private val end: RowCol
    val rows: Int

    init {
        var row = 0
        var col = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val w = charWidth(cp)
            if (w > 0 && col + w > cols) {
                row++
                col = 0
            }
            val n = Character.charCount(cp)
            for (k in 0 until n) pos[i + k] = RowCol(row, col)
            col += w
            i += n
        }
        end = RowCol(row, col)
        rows = row + 1
    }

    fun at(index: Int): RowCol = pos.getOrNull(index) ?: end
}

/** A pane's history as text, with the screen row each line starts on. */
class PaneText(val lines: List<String>, val cols: Int, /** The content revision it was read at. */ val revision: Long? = null) {
    val layouts = lines.map { Layout(it, cols) }
    val starts: List<Int> = layouts.runningFold(0) { row, l -> row + l.rows }.dropLast(1)
}

/** One screen row of a match: cells `from` until `to`. */
data class Span(val row: Int, val from: Int, val to: Int)

/** A match: its line and range in it, the row it starts on (0 = the oldest of the history), the cells it covers. */
data class Match(val line: Int, val index: Int, val length: Int, val row: Int, val spans: List<Span>)

/**
 * The query as a pattern: `/…/` (optionally `/…/i`) is a regex, anything else literal; smart case (case-insensitive
 * unless the query has a capital letter). Null for an empty query; throws PatternSyntaxException for a bad regex.
 */
fun compileQuery(q: String): Pattern? {
    if (q.isEmpty()) return null
    val re = Regex("^/(.+)/([a-z]*)$").find(q)
    val source = re?.groupValues?.get(1) ?: Pattern.quote(q)
    val flags = re?.groupValues?.get(2) ?: ""
    val letters = if (re != null) source.replace(Regex("\\\\."), "") else q // `\S`, `\W` aren't capitals
    var f = 0
    if ('i' in flags || letters == letters.lowercase()) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    if ('m' in flags) f = f or Pattern.MULTILINE
    if ('s' in flags) f = f or Pattern.DOTALL
    return Pattern.compile(source, f)
}

/** Every match in the text, oldest first (at most `limit`). Empty matches are skipped. */
fun findMatches(text: PaneText, re: Pattern, limit: Int = 100_000): List<Match> {
    val out = ArrayList<Match>()
    for ((i, line) in text.lines.withIndex()) {
        val m = re.matcher(line)
        while (out.size < limit && m.find()) {
            if (m.end() == m.start()) continue
            val lay = text.layouts[i]
            val a = lay.at(m.start())
            val last = Character.offsetByCodePoints(line, m.end(), -1)
            val b = lay.at(last)
            val lastWidth = charWidth(line.codePointAt(last)).coerceAtLeast(1)
            val spans = (a.row..b.row).map { r ->
                Span(text.starts[i] + r, if (r == a.row) a.col else 0, if (r == b.row) b.col + lastWidth else text.cols)
            }
            out += Match(i, m.start(), m.end() - m.start(), text.starts[i] + a.row, spans)
        }
        if (out.size >= limit) break
    }
    return out
}

/** The scroll position (`offset_from_bottom`) that puts `row` in the middle of the view. */
fun offsetFor(row: Int, maxOffset: Int, viewportRows: Int): Int {
    val top = row - (viewportRows - 1) / 2
    return (maxOffset - top).coerceIn(0, maxOffset)
}

/** The match to start at: the last one above the bottom of the view (the newest, when at the live screen). */
fun startAt(matches: List<Match>, viewBottomRow: Int): Int =
    matches.indexOfLast { it.row <= viewBottomRow }.takeIf { it >= 0 } ?: (matches.size - 1)
