// HerdrActions against a fake Herdr server: the requests each create sends and how replies are read.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class ActionsTest {
    private val dir = Files.createTempDirectory("hc")
    private val socket = dir.resolve("h.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socket)) }
    private val requests = CopyOnWriteArrayList<JsonObject>()

    /** Records each request and answers with `result(method, params)` (a JSON object), or an error for "!code". */
    private fun serve(result: (String, JsonObject) -> String) {
        Thread {
            while (server.isOpen) {
                val ch = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    val line = Channels.newInputStream(ch).bufferedReader().readLine() ?: return@Thread
                    val req = JsonParser.parseString(line).asJsonObject
                    requests += req
                    val id = req.get("id").asString
                    val r = result(req.get("method").asString, req.getAsJsonObject("params"))
                    val reply = if (r.startsWith("!")) """{"id":"$id","error":{"code":"${r.substring(1)}","message":"nope"}}""" else """{"id":"$id","result":$r}"""
                    ch.write(ByteBuffer.wrap((reply + "\n").toByteArray()))
                    ch.close()
                }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private val actions = HerdrActions(HerdrClient { socket.toString() }) {}
    private fun params(i: Int) = requests[i].getAsJsonObject("params")
    private fun JsonObject.str(k: String) = get(k)?.asString

    @After
    fun cleanup() {
        server.close()
        dir.toFile().deleteRecursively()
    }

    private val createdReply = """{"type":"tab_created","tab":{"tab_id":"w1:t3","workspace_id":"w1"},"root_pane":{"pane_id":"w1:p7","tab_id":"w1:t3","workspace_id":"w1"}}"""

    @Test
    fun createTabSendsFocusFalse() {
        serve { _, _ -> createdReply }
        val c = actions.createTab("w1", "/src/app", "Claude")
        assertEquals(Created("w1:p7", "w1:t3", "w1"), c)
        assertEquals("tab.create", requests[0].str("method"))
        val p = params(0)
        assertEquals(listOf("w1", "/src/app", "Claude"), listOf(p.str("workspace_id"), p.str("cwd"), p.str("label")))
        assertFalse(p.get("focus").asBoolean)
    }

    @Test
    fun createTabOmitsMissingFields() {
        serve { _, _ -> createdReply }
        actions.createTab("w1", null, null)
        assertFalse(params(0).has("cwd"))
        assertFalse(params(0).has("label"))
    }

    @Test
    fun createSpaceAndWorktree() {
        serve { m, _ ->
            val ws = if (m == "worktree.create") "w9" else "w8"
            """{"workspace":{"workspace_id":"$ws"},"tab":{"tab_id":"$ws:t1"},"root_pane":{"pane_id":"$ws:p1"}}"""
        }
        assertEquals(Created("w8:p1", "w8:t1", "w8"), actions.createSpace("/src/other", "other"))
        assertEquals("workspace.create", requests[0].str("method"))
        assertEquals(listOf("/src/other", "other"), listOf(params(0).str("cwd"), params(0).str("label")))
        assertFalse(params(0).get("focus").asBoolean)

        assertEquals(Created("w9:p1", "w9:t1", "w9"), actions.createWorktreeSpace("w1", "feat/x", "feat/x"))
        assertEquals("worktree.create", requests[1].str("method"))
        assertEquals(listOf("w1", "feat/x"), listOf(params(1).str("workspace_id"), params(1).str("branch")))
        assertFalse("herdr refuses workspace_id together with cwd", params(1).has("cwd"))
    }

    @Test
    fun replyWithoutPaneIsAnError() {
        serve { _, _ -> """{"type":"tab_created"}""" }
        try {
            actions.createTab("w1", null, null)
            fail("expected an error")
        } catch (e: HerdrError) {
            assertEquals("bad_response", e.code)
        }
    }

    @Test
    fun renameTab() {
        serve { _, _ -> "{}" }
        actions.renameTab("w1:t3", "Codex")
        assertEquals("tab.rename", requests[0].str("method"))
        assertEquals(listOf("w1:t3", "Codex"), listOf(params(0).str("tab_id"), params(0).str("label")))
    }

    @Test
    fun startAgentRetriesWhilePaneIsBusy() {
        var calls = 0
        serve { _, _ -> if (++calls < 3) "!agent_pane_busy" else """{"agent":{"pane_id":"w1:p7"}}""" }
        actions.startAgent("w1:p7", "claude", "claude-2")
        assertEquals(3, requests.size)
        val p = params(2)
        assertEquals(listOf("w1:p7", "claude", "claude-2"), listOf(p.str("pane_id"), p.str("kind"), p.str("name")))
        assertTrue(p.get("timeout_ms").asLong > 0)
    }

    @Test
    fun startAgentFailsOnOtherErrors() {
        serve { _, _ -> "!unknown_agent" }
        try {
            actions.startAgent("w1:p7", "nope", "nope")
            fail("expected an error")
        } catch (e: HerdrError) {
            assertEquals("unknown_agent", e.code)
        }
        assertEquals(1, requests.size)
    }

    @Test
    fun agentNames() {
        assertEquals("claude", agentName("claude", emptyList()))
        assertEquals("claude-3", agentName("claude", listOf("claude", "claude-2")))
        assertEquals("agent", agentName("42", emptyList()))
        assertTrue(AGENT_NAME.matches(agentName("Mastra Code", emptyList())))
    }

    @Test
    fun favoritesAndBranches() {
        assertEquals(listOf("claude", "codex", "gemini"), favoriteAgents("Claude, codex,, nonsense gemini claude"))
        assertEquals(emptyList<String>(), favoriteAgents(null))
        assertTrue(isValidBranch("feat/new-idea"))
        listOf("", "a b", "a..b", "-x", "x/", "x.", "a@{b", "a:b").forEach { assertFalse(it, isValidBranch(it)) }
    }
}
