// CoreClient against a fake herdr-core on a Unix socket: requests, core error codes, hello, the event stream
// (event, heartbeat, missed), its end and a stream that goes quiet. Plus where the core socket is looked for.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoreClientTest {
    private val dir = Files.createTempDirectory("hc")
    private val socket = dir.resolve("c.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket)) }

    /** Answers each connection's first line with `answer(request, channel)`; null = say nothing (more). */
    private fun serve(answer: (JsonObject, SocketChannel) -> String?) {
        Thread {
            while (server.isOpen) {
                val ch = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    val line = Channels.newInputStream(ch).bufferedReader().readLine() ?: return@Thread
                    answer(JsonParser.parseString(line).asJsonObject, ch)?.let { send(ch, it) }
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun send(ch: SocketChannel, line: String) = ch.write(ByteBuffer.wrap((line + "\n").toByteArray()))

    @After
    fun cleanup() {
        server.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun requestsWithStringIdsAndMapsCoreErrors() {
        serve { req, _ ->
            val id = req.get("id").asString
            when (req.get("method").asString) {
                "terminal.list" -> """{"id":"$id","jsonrpc":"2.0","result":{"terminals":[]}}"""
                else -> """{"error":{"code":-32000,"data":{"hint":"see `hc ls --all`","kind":"not_found"},"message":"no terminal \"t99\""},"id":"$id","jsonrpc":"2.0"}"""
            }
        }
        val core = CoreClient(socket.toString())
        assertTrue(core.request("terminal.list").getAsJsonArray("terminals").isEmpty)
        try {
            core.request("terminal.get", JsonObject().apply { addProperty("terminal", "t99") })
            fail("expected an error")
        } catch (e: HerdrError) {
            assertEquals("not_found", e.code)
            assertTrue(e.message!!.contains("no terminal"))
        }
    }

    @Test
    fun helloAcceptsGenerationOneWithEvents() {
        val generation = AtomicInteger(1)
        serve { req, _ ->
            """{"id":"${req.get("id").asString}","result":{"generation":${generation.get()},"features":["attach","events"],"version":"0.1.18"}}"""
        }
        val core = CoreClient(socket.toString())
        assertEquals("0.1.18", core.hello()?.version)
        generation.set(2)
        assertNull("another protocol generation", core.hello())
        assertNull("nothing listens", CoreClient(dir.resolve("none.sock").toString()).hello())
    }

    @Test
    fun subscriptionReportsEventsAndMissedAndEnds() {
        val subParams = AtomicReference<JsonObject>()
        serve { req, ch ->
            subParams.set(req.getAsJsonObject("params"))
            send(ch, """{"id":"${req.get("id").asString}","jsonrpc":"2.0","result":{"channel":null,"seq":40,"terminals":[]}}""")
            send(ch, """{"jsonrpc":"2.0","method":"event","params":{"data":{"name":"herdr-app","version":6},"seq":41,"type":"doc"}}""")
            send(ch, """{"jsonrpc":"2.0","method":"heartbeat","params":{"seq":41}}""")
            send(ch, """{"jsonrpc":"2.0","method":"missed","params":{"from":42}}""")
            ch.close()
            null
        }
        val changes = LinkedBlockingQueue<Int>()
        val ended = CountDownLatch(1)
        val error = AtomicReference<Exception?>()
        CoreClient(socket.toString()).subscribe({ changes.put(1) }) { error.set(it); ended.countDown() }
        assertTrue("stream end calls onEnd", ended.await(3, TimeUnit.SECONDS))
        assertNull(error.get())
        assertEquals("event and missed re-read; the ack and heartbeat don't", 2, changes.size)
        assertEquals("unfiltered", 0, subParams.get().size())
    }

    @Test
    fun aQuietStreamCountsAsLost() {
        serve { req, ch ->
            send(ch, """{"id":"${req.get("id").asString}","result":{}}""")
            null // then nothing, not even a heartbeat
        }
        val ended = CountDownLatch(1)
        val error = AtomicReference<Exception?>()
        val t0 = System.nanoTime()
        CoreClient({ socket.toString() }, idleMs = 300).subscribe({}) { error.set(it); ended.countDown() }
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        assertEquals("timeout", (error.get() as? HerdrError)?.code)
        assertTrue("not before the idle time", System.nanoTime() - t0 >= 300_000_000L)
    }

    @Test
    fun disposeEndsTheStreamWithoutOnEnd() {
        val connected = CountDownLatch(1)
        serve { req, ch ->
            send(ch, """{"id":"${req.get("id").asString}","result":{}}""")
            connected.countDown()
            null
        }
        val ended = CountDownLatch(1)
        val sub = CoreClient({ socket.toString() }, idleMs = 300).subscribe({}) { ended.countDown() }
        assertTrue(connected.await(3, TimeUnit.SECONDS))
        sub.dispose()
        assertTrue("no onEnd after dispose, even past the idle time", !ended.await(800, TimeUnit.MILLISECONDS))
    }

    @Test
    fun resolvesTheCoreSocketLikeHerdrCore() {
        val home = System.getProperty("user.home")
        assertEquals("/x/c.sock", resolveCoreSocket("/x/c.sock", mapOf("HC_SOCKET" to "/y.sock")))
        assertEquals("$home/c.sock", resolveCoreSocket("~/c.sock", emptyMap()))
        assertEquals("/y.sock", resolveCoreSocket("", mapOf("HC_SOCKET" to "/y.sock", "XDG_RUNTIME_DIR" to "/run/u")))
        assertEquals("/run/u/herdr-core/s2.sock", resolveCoreSocket(null, mapOf("XDG_RUNTIME_DIR" to "/run/u", "HC_SESSION" to "s2")))
        assertEquals("/run/u/herdr-core/default.sock", resolveCoreSocket(null, mapOf("XDG_RUNTIME_DIR" to "/run/u")))
        assertEquals("/st/herdr-core/default/default.sock", resolveCoreSocket(null, mapOf("XDG_STATE_HOME" to "/st"), uid = null))
        assertEquals("$home/.local/state/herdr-core/a/a.sock", resolveCoreSocket(null, mapOf("HC_SESSION" to "a"), uid = 4_000_000_000L))
        assertNotNull(coreErrorOf(JsonParser.parseString("""{"error":{"code":-32601,"message":"no such method"}}""").asJsonObject)?.code?.takeIf { it == "-32601" })
    }
}
