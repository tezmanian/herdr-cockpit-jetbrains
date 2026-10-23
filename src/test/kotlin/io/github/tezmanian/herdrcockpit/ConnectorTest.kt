// HerdrTtyConnector: the pane stream starts at the tab's real size, never at the default one.
package io.github.tezmanian.herdrcockpit

import com.jediterm.core.util.TermSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ConnectorTest {
    @Test
    fun readingBeforeTheFirstResizeDoesNotStartTheStream() {
        val ended = CopyOnWriteArrayList<String>()
        // A missing binary: starting the stream fails at once and reports it through onEnded.
        val tty = HerdrTtyConnector("/nonexistent/herdr", "w1:p1", emptyMap(), "test") { ended += it }
        val reader = Thread { tty.read(CharArray(16), 0, 16) }.apply { isDaemon = true; start() }
        Thread.sleep(500)
        assertEquals(emptyList<String>(), ended)
        tty.resize(TermSize(100, 30))
        assertTrue(ended.toString(), ended.single().startsWith("couldn't stream this pane"))
        reader.interrupt()
    }
}
