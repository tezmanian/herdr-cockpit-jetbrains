// HerdrClient against a fake Herdr server on a Unix socket: requests, errors, timeouts and subscriptions.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ClientTest {
    private val dir = Files.createTempDirectory("hc")
    private val socket = dir.resolve("h.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket)) }

    /** Answers each connection with `answer(request)`; null = say nothing. */
    private fun serve(answer: (JsonObject, SocketChannel) -> String?) {
        Thread {
            while (server.isOpen) {
                val ch = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    val line = Channels.newInputStream(ch).bufferedReader().readLine() ?: return@Thread
                    val req = JsonParser.parseString(line).asJsonObject
                    answer(req, ch)?.let { ch.write(ByteBuffer.wrap((it + "\n").toByteArray())) }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun cleanup() {
        server.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun runCommandGivesUpOnAHungProcess() {
        val env = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"))
        val t0 = System.nanoTime()
        assertEquals(null, runCommand(listOf("sleep", "10"), env, 1))
        assertTrue("killed after about 1s", System.nanoTime() - t0 < 5_000_000_000L)
        assertEquals(0 to "hi\n", runCommand(listOf("echo", "hi"), env, 5))
    }

    @Test
    fun requestReturnsResultAndMapsErrors() {
        serve { req, _ ->
            val id = req.get("id").asString
            when (req.get("method").asString) {
                "session.snapshot" -> """{"id":"$id","result":{"type":"session_snapshot","snapshot":{"workspaces":[],"tabs":[],"panes":[],"agents":[],"version":"0.9.3","protocol":22}}}"""
                else -> """{"id":"$id","error":{"code":"pane_not_found","message":"no such pane"}}"""
            }
        }
        val client = HerdrClient { socket.toString() }
        val model = normalize(client.request("session.snapshot"))
        assertEquals(listOf("0.9.3", 22), listOf(model.version, model.protocol))
        try {
            client.request("pane.read")
            fail("expected an error")
        } catch (e: HerdrError) {
            assertEquals("pane_not_found", e.code)
        }
    }

    @Test
    fun requestTimesOut() {
        serve { _, _ -> null }
        try {
            HerdrClient { socket.toString() }.request("session.snapshot", timeoutMs = 300)
            fail("expected a timeout")
        } catch (e: HerdrError) {
            assertEquals("timeout", e.code)
        }
    }

    @Test
    fun subscriptionDeliversEventsAndEnds() {
        val subscribed = LinkedBlockingQueue<JsonObject>()
        serve { req, ch ->
            subscribed.put(req)
            val id = req.get("id").asString
            ch.write(ByteBuffer.wrap("""{"id":"$id","result":{"type":"subscription_started"}}""".toByteArray() + '\n'.code.toByte()))
            ch.write(ByteBuffer.wrap("""{"event":"tab.created","data":{}}""".toByteArray() + '\n'.code.toByte()))
            ch.close()
            null
        }
        val events = LinkedBlockingQueue<JsonObject>()
        val ended = CountDownLatch(1)
        HerdrClient { socket.toString() }.subscribe(subscriptionsFor(listOf("w1:p1")), { events.put(it) }) { ended.countDown() }
        val req = subscribed.poll(2, TimeUnit.SECONDS)!!
        val subs = req.getAsJsonObject("params").getAsJsonArray("subscriptions").map { it.asJsonObject }
        assertTrue(subs.any { it.get("type").asString == "pane.agent_status_changed" && it.get("pane_id").asString == "w1:p1" })
        assertTrue("lifecycle events take no pane id", subs.filter { it.get("type").asString != "pane.agent_status_changed" }.none { it.has("pane_id") })
        assertEquals("tab.created", events.poll(2, TimeUnit.SECONDS)!!.get("event").asString)
        assertTrue(ended.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun socketPathResolution() {
        assertEquals("/x/h.sock", resolveSocketPath(null, mapOf("HERDR_SOCKET_PATH" to "/x/h.sock")))
        assertEquals("/c/herdr/sessions/demo/herdr.sock", resolveSocketPath(null, mapOf("XDG_CONFIG_HOME" to "/c", "HERDR_SESSION" to "demo")))
        assertEquals("/c/herdr/herdr.sock", resolveSocketPath(null, mapOf("XDG_CONFIG_HOME" to "/c")))
        assertEquals("/o.sock", resolveSocketPath("/o.sock", mapOf("HERDR_SOCKET_PATH" to "/x/h.sock")))
        assertEquals("demo", sessionOfSocket("/c/herdr/sessions/demo/herdr.sock"))
    }
}
