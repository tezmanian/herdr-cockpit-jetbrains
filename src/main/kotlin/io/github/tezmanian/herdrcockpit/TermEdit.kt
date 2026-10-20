// Editing the line being typed in a pane tab, and selecting past the edge of the view. No IntelliJ imports.
// Port of upstream's termScreen.ts (deleteKeys, moveKeys, settle).
//
// A row is JediTerm's line text: one char per cell, `DWC` for the second cell of a wide character, shorter than
// the width when the rest is empty. Shells and agents (Claude Code, Codex) keep the real cursor in their input,
// so moving it there with arrow keys and deleting with Backspace edits what's typed.
package io.github.tezmanian.herdrcockpit

private const val ESC = "\u001b"
/** JediTerm's placeholder for the second cell of a wide character (CharUtils.DWC). */
const val DWC = ''

private fun lastText(row: String) = row.indexOfLast { it != DWC && !it.isWhitespace() }
/**
 * The last cell of the input the cursor is in: up to a gap of 2+ spaces after the cursor, so a right prompt
 * (zsh RPROMPT, powerlevel10k) isn't taken for input.
 */
// shortcut: two spaces after the cursor inside the typed command end the input there too; fine for deleting less
// than asked, upgrade if Herdr ever reports the input's extent
private fun inputEnd(row: String, cursorX: Int): Int {
    val gap = row.indexOf("  ", maxOf(0, cursorX))
    return if (gap < 0) lastText(row) else minOf(lastText(row), gap - 1)
}
private fun cellWidth(row: String, x: Int) = if (row.getOrNull(x) == DWC) 0 else 1
private fun chars(row: String, from: Int, to: Int) = (from until to).count { cellWidth(row, it) > 0 }

/**
 * The keys that delete cells `start..end` (inclusive) of the cursor's row: move the cursor to the end of the
 * selection, then one Backspace per character. Null when nothing would be deleted.
 */
fun deleteKeys(row: String, cursorX: Int, start: Int, end: Int): String? {
    var s = start
    if (s > 0 && cellWidth(row, s) == 0) s-- // half of a wide character: all of it
    // Past the end of the input is only padding.
    val e = minOf(end, maxOf(inputEnd(row, cursorX), cursorX - 1))
    if (e < s) return null
    var after = e + 1
    if (cellWidth(row, after) == 0) after++
    val back = chars(row, s, after)
    if (back == 0) return null
    val right = if (after > cursorX) chars(row, cursorX, after) else 0
    val left = if (after < cursorX) chars(row, after, cursorX) else 0
    return "$ESC[C".repeat(right) + "$ESC[D".repeat(left) + "\u007f".repeat(back)
}

/** The arrow keys that move the cursor to cell `x` of its own row (Alt+click), not past the end of the input. */
fun moveKeys(row: String, cursorX: Int, x: Int): String? {
    var to = x.coerceAtLeast(0).coerceAtMost(maxOf(inputEnd(row, cursorX) + 1, cursorX))
    if (to > 0 && cellWidth(row, to) == 0) to--
    val n = chars(row, minOf(to, cursorX), maxOf(to, cursorX))
    if (n == 0) return null
    return (if (to > cursorX) "$ESC[C" else "$ESC[D").repeat(n)
}

/**
 * How many rows the view moved for a scroll (`up`, asked for `lines`), from the rows before and now. The shift is
 * the one most non-blank rows agree with, and must beat "didn't move": a full-screen program (less, an agent's
 * transcript) moves by its own step and keeps header and footer rows fixed. 0: not the scroll's frame (yet).
 */
fun scrollShift(before: List<String>, now: List<String>, up: Boolean, lines: Int): Int {
    val n = minOf(before.size, now.size)
    fun score(k: Int): Int {
        var same = 0
        for (y in 0 until n - k) {
            val (a, b) = if (up) now[y + k] to before[y] else now[y] to before[y + k]
            if (a.isNotBlank() && a == b) same++
        }
        return same
    }
    var best = 0
    var bestScore = score(0)
    for (k in 1 until n) {
        val s = score(k)
        if (s > bestScore || (s == bestScore && best > 0 && Math.abs(k - lines) < Math.abs(best - lines))) {
            best = k
            bestScore = s
        }
    }
    return best
}

/**
 * The selected text from rows kept by content row (they may have scrolled out of view): rows `startRow..endRow`,
 * from cell `startX` on the first to `endX` (exclusive) on the last; a wrapped row continues the one before.
 */
fun keptText(kept: Map<Int, Pair<String, Boolean>>, startRow: Int, startX: Int, endRow: Int, endX: Int): String {
    val out = StringBuilder()
    for (r in startRow..endRow) {
        val (text, wrapped) = kept[r] ?: continue
        if (r > startRow && !wrapped) out.append('\n')
        val from = if (r == startRow) startX else 0
        val to = if (r == endRow) endX else Int.MAX_VALUE
        val seg = text.substring(minOf(from, text.length), minOf(to, text.length)).replace(DWC.toString(), "")
        // Trailing spaces are padding, except before the continuation of a wrapped line.
        out.append(if (r < endRow && kept[r + 1]?.second == true) seg else seg.trimEnd(' ', '\t'))
    }
    return out.toString()
}
