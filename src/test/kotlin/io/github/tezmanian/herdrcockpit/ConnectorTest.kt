// HerdrTtyConnector: the pane stream starts at the tab's real size, never at the default one.
// Herdr 2's attach command takes local terminal addresses only, and its output loses mode 2026 (SyncOutputFilter).
package io.github.tezmanian.herdrcockpit

import com.jediterm.core.util.TermSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ConnectorTest {
    @Test
    fun readingBeforeTheFirstResizeDoesNotStartTheStream() {
        val ended = CopyOnWriteArrayList<String>()
        // A missing binary: starting the stream fails at once and reports it through onEnded.
        val tty = HerdrTtyConnector({ _, _, _ -> listOf("/nonexistent/herdr") }, "w1:p1", emptyMap(), "test") { ended += it }
        val reader = Thread { tty.read(CharArray(16), 0, 16) }.apply { isDaemon = true; start() }
        Thread.sleep(500)
        assertEquals(emptyList<String>(), ended)
        tty.resize(TermSize(100, 30))
        assertTrue(ended.toString(), ended.single().startsWith("couldn't stream this pane"))
        reader.interrupt()
    }

    @Test
    fun herdr2AttachesOnlyLocalTerminalAddresses() {
        val b = Herdr2Backend({ "/opt/hc" }, { null }) { null }
        try {
            assertEquals(listOf("/opt/hc", "attach", "t7"), b.attachCommand("t7", observe = false, cols = 80, rows = 24))
            assertEquals(listOf("/opt/hc", "attach", "build_2-x"), b.attachCommand("build_2-x", observe = true, cols = 80, rows = 24))
            for (bad in listOf("-x", "a/t7", "", "t" + "1".repeat(32), "T7", "t 7", "--help")) {
                try {
                    b.attachCommand(bad, false, 80, 24)
                    fail("accepted $bad")
                } catch (e: HerdrError) {
                    assertEquals("bad_address", e.code)
                }
            }
        } finally {
            b.dispose()
        }
    }

    @Test
    fun syncOutputModeIsDroppedEvenWhenCutBetweenChunks() {
        val f = SyncOutputFilter()
        assertEquals("a\u001b[?2004lb", f.feed("a\u001b[?2026l\u001b[?2004l\u001b[?2026hb"))
        assertEquals("x", f.feed("x\u001b[?20"))
        assertEquals("y", f.feed("26ly"))
        assertEquals("a cut-off other sequence waits, then passes", "", f.feed("\u001b"))
        assertEquals("\u001b[7mz", f.feed("[7mz"))
        assertEquals("\u001b[?2025l", f.feed("\u001b[?2025l"))
        assertEquals("", f.feed(""))
    }
}
