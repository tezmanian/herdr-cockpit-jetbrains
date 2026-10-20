// Find in a pane's history (TermFind.kt) and editing the typed line (TermEdit.kt). Ported from upstream's findTest.ts.
package io.github.tezmanian.herdrcockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class FindTest {
    private fun q(s: String) = compileQuery(s)!!
    private fun Pattern.caseless() = flags() and Pattern.CASE_INSENSITIVE != 0

    @Test
    fun widthsAndWrapping() {
        assertEquals(listOf(1, 2, 2, 0, 1, 1), listOf("a", "日", "😀", "́", "●", "⏵").map { charWidth(it.codePointAt(0)) })
        val lay = Layout("abcdefgh日本", 5) // abcde | fgh日 | 本
        assertEquals(3, lay.rows)
        assertEquals(listOf(RowCol(1, 0), RowCol(1, 3), RowCol(2, 0)), listOf(lay.at(5), lay.at(8), lay.at(9)))
        assertEquals("an empty line is a row", 1, Layout("", 80).rows)
        assertEquals("exactly the width: one row", 1, Layout("x".repeat(80), 80).rows)
    }

    @Test
    fun smartCaseLiteralOrRegex() {
        assertNull(compileQuery(""))
        assertTrue("lower case: case-insensitive", q("error").caseless())
        assertFalse("a capital: case-sensitive", q("Error").caseless())
        assertTrue("literal by default", q("a.b(").matcher("xa.b(").find())
        assertFalse(q("a.b(").matcher("axb(").find())
        assertTrue("regex escapes are not capitals", q("/\\S+ed$/").caseless())
        assertFalse(q("/Fail.*/").caseless())
        assertTrue(q("/fail/i").caseless())
        assertTrue("a path is literal, not /usr/ with flags \"bin\"", q("/usr/bin").matcher("ls /usr/bin").find())
        assertFalse(q("/usr/bin").matcher("usr").find())
        assertTrue("a bad regex is an error", runCatching { compileQuery("/(/") }.exceptionOrNull() is PatternSyntaxException)
    }

    @Test
    fun matchesAcrossWrappedRows() {
        val text = PaneText(listOf("first error here", "x".repeat(8) + "ERROR", "", "no", "error error"), 10)
        assertEquals("wrapped lines take two rows", listOf(0, 2, 4, 5, 6), text.starts)
        val ms = findMatches(text, q("error"))
        assertEquals(
            listOf(
                0 to listOf(Span(0, 6, 10), Span(1, 0, 1)), // "error" across the wrap of line 0
                2 to listOf(Span(2, 8, 10), Span(3, 0, 3)),
                6 to listOf(Span(6, 0, 5)),
                6 to listOf(Span(6, 6, 10), Span(7, 0, 1)),
            ),
            ms.map { it.row to it.spans },
        )
        assertEquals("case-sensitive with a capital", 0, findMatches(text, q("Error")).size)
        assertEquals("no empty matches, no endless loop", 1, findMatches(PaneText(listOf("aaa"), 80), q("/a*/")).size)
        assertEquals("the ones on screen from row 1: the one wrapping into it", 0, firstEndingFrom(ms, 1))
        assertEquals(1, firstEndingFrom(ms, 2))
        assertEquals("two on row 6", 2, firstEndingFrom(ms, 4))
        assertEquals("the second one wraps to row 7", 3, firstEndingFrom(ms, 7))
        assertEquals("none below", 4, firstEndingFrom(ms, 8))
        assertEquals("the newest match at or above the view bottom", 1, startAt(ms, 5))
        assertEquals(3, startAt(ms, 100))
        // The history has max + rows rows; the top row is max - offset.
        assertEquals("the row lands in the middle", 3012 - (3000 - 19), offsetFor(3000, 3012, 40))
        assertEquals("clamped at the top", 3012, offsetFor(5, 3012, 40))
        assertEquals("clamped at the bottom", 0, offsetFor(3050, 3012, 40))
    }

    private val left = "\u001b[D"
    private val right = "\u001b[C"

    @Test
    fun deletingTheSelectionOnTheTypedLine() {
        val row = "\$ echo hello world" // cursor after "world": column 18
        assertEquals("to the end of \"hello\", then 5 Backspaces", left.repeat(6) + "\u007f".repeat(5), deleteKeys(row, 18, 7, 11))
        assertEquals("past the end of the input is padding", "\u007f".repeat(5), deleteKeys(row, 18, 13, 25))
        assertEquals("right of the cursor: move there first", right.repeat(3) + "\u007f".repeat(2), deleteKeys(row, 2, 3, 4))
        assertNull("only padding selected", deleteKeys(row, 18, 20, 25))
        val rprompt = "\$ echo hello world" + " ".repeat(20) + "~/proj 12:01"
        assertEquals("from \"hello\" to the end of the row: not the right prompt", "\u007f".repeat(11), deleteKeys(rprompt, 18, 7, rprompt.length - 1))
        assertEquals("selected to the end of the row: only the input", "\u007f".repeat(5), deleteKeys(rprompt, 18, 13, rprompt.length - 1))
        assertNull("Alt+click on the right prompt: already at the end", moveKeys(rprompt, 18, rprompt.length - 3))
        assertEquals("half of a wide character: all of it", left + "\u007f", deleteKeys("a日${DWC}b", 4, 2, 2))
    }

    @Test
    fun altClickMovesTheCursor() {
        val row = "\$ echo hello world"
        assertEquals("on \"hello\": 11 left", left.repeat(11), moveKeys(row, 18, 7))
        assertNull("past the end of the input: already there", moveKeys(row, 18, 25))
        assertEquals("right of the cursor", right.repeat(5), moveKeys(row, 2, 7))
        assertEquals("a wide character is one key", left.repeat(2), moveKeys("a日${DWC}b", 4, 1))
    }

    @Test
    fun scrollShiftFindsHowFarTheViewMoved() {
        val before = listOf("a", "b", "c", "d", "e")
        assertEquals(1, scrollShift(before, listOf("z", "a", "b", "c", "d"), up = true, lines = 1))
        assertEquals(2, scrollShift(before, listOf("c", "d", "e", "f", "g"), up = false, lines = 3))
        assertEquals("a spinner isn't the scroll's frame", 0, scrollShift(before, listOf("a", "b", "c", "d", "⠋"), up = true, lines = 1))
        // less: a fixed footer, the text above moves by its own step
        assertEquals(2, scrollShift(listOf("3", "4", "5", "6", ":"), listOf("1", "2", "3", "4", ":"), up = true, lines = 1))
    }

    @Test
    fun keptRowsGiveTheSelectedText() {
        val kept = mapOf(-2 to ("first line   " to false), -1 to ("wrapped par" to false), 0 to ("t two" to true), 1 to ("last" to false))
        assertEquals("trailing padding trimmed, wrapped rows joined", "line\nwrapped part two\nla", keptText(kept, -2, 6, 1, 2))
        assertEquals("no DWC placeholders", "日b", keptText(mapOf(0 to ("a日${DWC}b" to false)), 0, 1, 0, 4))
    }
}
