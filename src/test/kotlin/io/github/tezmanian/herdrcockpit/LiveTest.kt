// Against a real, throwaway Herdr session. Skipped unless HERDR_LIVE_SOCKET is set, e.g.
//   herdr --session hcjb server &
//   HERDR_LIVE_SOCKET=~/.config/herdr/sessions/hcjb/herdr.sock ./gradlew test
// Never point it at your own session: it types into the first pane.
package io.github.tezmanian.herdrcockpit

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveTest {
    private val socket = System.getenv("HERDR_LIVE_SOCKET")?.let { resolveSocketPath(it) }

    @Test
    fun snapshotAndPaneStream() {
        assumeTrue("HERDR_LIVE_SOCKET not set", socket != null)
        val client = HerdrClient { socket!! }
        val model = normalize(client.request("session.snapshot"))
        val pane = model.spaces.flatMap { it.panes }.first { !it.isAgent }
        val view = buildViewState(ViewInputs(model, true, { null }, { null }, { false }, { false }, { null }, com.google.gson.JsonObject()))
        assertTrue(view.get("connected").asBoolean)

        val ended = mutableListOf<String>()
        val tty = HerdrTtyConnector(findTool("herdr"), pane.id, herdrEnv(socket), "test") { ended += it }
        tty.start()
        tty.resize(com.jediterm.core.util.TermSize(100, 30))
        val seen = StringBuilder()
        val reader = Thread {
            val buf = CharArray(4096)
            while (true) {
                val n = runCatching { tty.read(buf, 0, buf.size) }.getOrDefault(-1)
                if (n < 0) break
                synchronized(seen) { seen.append(buf, 0, n) }
            }
        }.apply { isDaemon = true; start() }
        Thread.sleep(800)
        tty.write("echo hc-ok-\$((6*7))\r")
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline && synchronized(seen) { "hc-ok-42" !in seen }) Thread.sleep(100)
        assertTrue("typed command ran and its output was streamed back", synchronized(seen) { "hc-ok-42" in seen })
        tty.close()
        reader.join(2000)
        assertTrue("closing only detaches: no 'ended' note", ended.isEmpty())
        // The pane is still there in Herdr.
        assertTrue(normalize(client.request("session.snapshot")).find(pane.id) != null)
    }
}
