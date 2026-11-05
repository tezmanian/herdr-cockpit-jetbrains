// Against a real, throwaway herdr-core. Skipped unless HC_LIVE_SOCKET is set to its socket, e.g. a core without a
// Herdr app window in isolated folders (quickstart.md §2):
//   HC_SESSION=hcjb2t hc server start
//   HC_LIVE_SOCKET=$XDG_RUNTIME_DIR/herdr-core/hcjb2t.sock ./gradlew test --tests '*CoreLiveTest*'
// Never point it at your own core: it rewrites the "herdr-app" layout document (and puts it back afterwards).
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CoreLiveTest {
    private val socket = System.getenv("HC_LIVE_SOCKET")?.takeIf { it.isNotBlank() }

    private fun params(vararg kv: Pair<String, Any>) = JsonObject().apply {
        for ((k, v) in kv) when (v) {
            is String -> addProperty(k, v)
            is Number -> addProperty(k, v)
            is com.google.gson.JsonElement -> add(k, v)
        }
    }

    @Test
    fun snapshotPlacesATerminalOfTheLayoutAndEventsFire() {
        assumeTrue("HC_LIVE_SOCKET not set", socket != null)
        assertNotEquals("refusing the default core socket: point HC_LIVE_SOCKET at a throwaway core", resolveCoreSocket(null), socket)
        val core = CoreClient(socket!!)
        assertNotNull("no herdr-core at $socket", core.hello())

        val run = core.request("terminal.run", params("argv" to JsonArray().apply { add("sh") }, "cwd" to "/tmp", "name" to "cockpit-live",
            "labels" to JsonObject().apply { addProperty("cockpit.test", "1") }))
        val address = run.get("address").asString
        val backend = Herdr2Backend({ findTool("hc") }, { socket }) { null }
        val before = core.request("doc.get", params("name" to "herdr-app"))
        try {
            val changed = CountDownLatch(1)
            val sub = backend.subscribe(null, { changed.countDown() }) {}
            Thread.sleep(300) // let the subscription start
            val layout = JsonParser.parseString("""{"spaces":[{"id":"sp-live","name":"","folder":"/tmp","tabs":[{"id":"tb-live","name":"",
                "root":{"id":"r","type":"row","children":[{"id":"b","type":"block","content":{"terminal":"$address"}}]}}]}]}""")
            core.request("doc.set", params("name" to "herdr-app", "value" to layout, "version" to before.get("version").asLong))
            assertTrue("doc.set fires an event", changed.await(3, TimeUnit.SECONDS))
            sub.dispose()

            val m = backend.snapshot()
            val space = m.space("sp-live")!!
            assertEquals("tmp", space.label)
            assertEquals(listOf(address), space.panes.map { it.id })
            assertEquals("cockpit-live", space.panes[0].label)
            assertTrue(m.version!!.startsWith("2 (experimental) · core "))

            // Shared markers: pin, seen, unpin. A gone terminal is no error.
            backend.actions.setPinned(address, true)
            backend.actions.markSeen(address, 7)
            val marked = backend.snapshot().find(address)!!.second
            assertTrue(marked.pinned)
            assertEquals(7L, marked.seen)
            backend.actions.setPinned(address, false)
            assertTrue(!backend.snapshot().find(address)!!.second.pinned)
            backend.actions.markSeen("t" + "9".repeat(9), 1)
        } finally {
            // Put the layout back, then end and remove the terminal.
            runCatching {
                val now = core.request("doc.get", params("name" to "herdr-app")).get("version").asLong
                core.request("doc.set", params("name" to "herdr-app", "value" to (before.get("value") ?: JsonNull.INSTANCE), "version" to now))
            }
            runCatching { core.request("terminal.kill", params("terminal" to address)) }
            for (i in 0 until 20) {
                if (runCatching { core.request("terminal.get", params("terminal" to address)).get("state")?.asString }.getOrNull() != "live") break
                Thread.sleep(100)
            }
            runCatching { core.request("terminal.rm", params("terminal" to address)) }
            backend.dispose()
            core.dispose()
        }
    }
}
