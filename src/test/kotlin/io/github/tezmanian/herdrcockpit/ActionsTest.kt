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

    private val client = HerdrClient { socket.toString() }
    private val actions = HerdrActions(client) {}
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
    fun renamesAndCloses() {
        serve { _, _ -> "{}" }
        actions.renamePane("w1:p7", null)
        actions.renameAgent("w1:p7", "reviewer")
        actions.closeSpace("w1")
        actions.closeSpace("w1", group = true)
        actions.focusSpace("w1")
        actions.removeWorktree("w2", force = true)
        actions.promptAgent("w3:p1", "go")
        actions.focusAgent("w3:p1")
        val sent = requests.map { it.str("method") to it.getAsJsonObject("params").toString() }
        assertEquals(
            listOf(
                "pane.rename" to """{"pane_id":"w1:p7","label":null}""",
                "agent.rename" to """{"target":"w1:p7","name":"reviewer"}""",
                "workspace.close" to """{"workspace_id":"w1"}""",
                "workspace.close" to """{"workspace_id":"w1","close_group":true}""",
                "workspace.focus" to """{"workspace_id":"w1"}""",
                "worktree.remove" to """{"workspace_id":"w2","force":true}""",
                "agent.prompt" to """{"target":"w3:p1","text":"go"}""",
                "agent.focus" to """{"target":"w3:p1"}""",
            ),
            sent,
        )
    }

    @Test
    fun paneHistoryReadsFromTheTopToTheLastRow() {
        serve { m, p ->
            when {
                m == "pane.get" -> """{"pane":{"scroll":{"offset_from_bottom":0,"max_offset_from_bottom":262,"viewport_rows":40}}}"""
                p.getAsJsonObject("cursor").get("col").asInt > 0 -> "!selection_unavailable"
                else -> """{"text":"1\n2"}"""
            }
        }
        assertEquals("1\n2", actions.paneHistory("w9:p1", 80))
        assertEquals("""{"pane_id":"w9:p1","anchor":{"row":0,"col":0},"cursor":{"row":301,"col":79}}""", params(1).toString())
        assertEquals("falls back to column 0", 0, params(2).getAsJsonObject("cursor").get("col").asInt)
    }

    @Test
    fun findScrollsTheViewAndReadsTheRevision() {
        serve { m, _ ->
            when (m) {
                "pane.copy_motion" -> """{"type":"pane_copy_motion","cursor":{"row":0,"col":26},"content_revision":18}"""
                else -> """{"pane":{"scroll":{"offset_from_bottom":50,"max_offset_from_bottom":290,"viewport_rows":12}}}"""
            }
        }
        assertEquals(PaneScroll(50, 290, 12), actions.scrollPaneTo("w9:p1", 50))
        assertEquals("""{"pane_id":"w9:p1","offset_from_bottom":50}""", params(0).toString())
        assertEquals(18L, actions.paneRevision("w9:p1"))
        assertEquals("""{"pane_id":"w9:p1","cursor":{"row":0,"col":0},"motion":"line_end"}""", params(1).toString())
        assertEquals(PaneScroll(50, 290, 12), actions.paneScroll("w9:p1"))
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
        assertEquals("reviewer-bot", agentSlug("Reviewer Bot!"))
        assertEquals(null, agentSlug("42 !"))
    }

    @Test
    fun favoritesAndBranches() {
        assertEquals(listOf("claude", "codex", "gemini"), favoriteAgents("Claude, codex,, nonsense gemini claude"))
        assertEquals(emptyList<String>(), favoriteAgents(null))
        assertTrue(isValidBranch("feat/new-idea"))
        listOf("", "a b", "a..b", "-x", "x/", "x.", "a@{b", "a:b").forEach { assertFalse(it, isValidBranch(it)) }
    }

    @Test
    fun oldPromptLinesBecomeEntries() {
        val setting = "review: Focus on security\n\n  Review: only bugs \nExplain this\ngeneral: Write tests: unit ones\n" +
            "review: Focus on tests: missing cases, edge cases and the error paths"
        assertEquals(listOf(
            SavedPrompt("Focus on security", "review", "Focus on security"),
            SavedPrompt("only bugs", "review", "only bugs"),
            SavedPrompt("Explain this", "general", "Explain this"),
            SavedPrompt("Write tests: unit ones", "general", "Write tests: unit ones"),
            SavedPrompt("Focus on tests: missing cases, edge…", "review", "Focus on tests: missing cases, edge cases and the error paths"),
        ), promptsFromLines(setting))
        assertEquals(emptyList<SavedPrompt>(), promptsFromLines(null))
    }

    @Test
    fun promptAndWaitSendsTheWaitAndReadsTheStatus() {
        serve { _, _ -> """{"type":"agent_prompted","agent":{"pane_id":"w1:p2","agent_status":"blocked"}}""" }
        val st = actions.promptAndWait("w1:p2", "review", setOf(AgentStatus.idle, AgentStatus.blocked), 60_000)
        assertEquals(AgentStatus.blocked, st)
        assertEquals("agent.prompt", requests[0].str("method"))
        assertEquals("review", params(0).str("text"))
        val wait = params(0).getAsJsonObject("wait")
        assertEquals(60_000L, wait.get("timeout_ms").asLong)
        assertEquals(listOf("idle", "blocked"), wait.getAsJsonArray("until").map { it.asString })
    }

    @Test
    fun waitAgentSendsTargetAndStates() {
        serve { _, _ -> """{"type":"agent_info","agent":{"pane_id":"w1:p2","agent_status":"done"}}""" }
        assertEquals(AgentStatus.done, actions.waitAgent("w1:p2", setOf(AgentStatus.done), 1000))
        assertEquals("agent.wait", requests[0].str("method"))
        assertEquals("w1:p2", params(0).str("target"))
        assertFalse(params(0).has("focus"))
    }

    @Test
    fun sendKeysAndRead() {
        serve { m, _ -> if (m == "agent.read") """{"type":"pane_read","read":{"text":"Allow?\n1. Yes\n2. No"}}""" else """{"type":"ok"}""" }
        actions.sendKeys("w1:p2", listOf("down", "enter"))
        assertEquals("agent.send_keys", requests[0].str("method"))
        assertEquals(listOf("down", "enter"), params(0).getAsJsonArray("keys").map { it.asString })
        assertEquals("Allow?\n1. Yes\n2. No", actions.readAgent("w1:p2"))
        assertEquals("visible", params(1).str("source"))
    }

    @Test
    fun paneKeys() {
        serve { _, _ -> """{"type":"ok"}""" }
        actions.paneKeys("w1:p2", listOf("ctrl+j"))
        assertEquals("pane.send_keys", requests[0].str("method"))
        assertEquals("w1:p2", params(0).str("pane_id"))
        assertEquals(listOf("ctrl+j"), params(0).getAsJsonArray("keys").map { it.asString })
    }

    @Test
    fun blockedAgentRefusesKeysForAnotherOccupant() {
        serve { _, _ -> "!agent_not_running" }
        try {
            actions.sendKeys("w1:p2", listOf("enter"))
            fail("expected agent_not_running")
        } catch (e: HerdrError) {
            assertEquals("agent_not_running", e.code)
        }
    }

    @Test
    fun disposeEndsAWaitInFlight() {
        serve { _, _ -> Thread.sleep(30_000); """{"type":"ok"}""" }
        val started = System.currentTimeMillis()
        Thread { Thread.sleep(300); client.dispose() }.start()
        try {
            actions.waitAgent("w1:p2", setOf(AgentStatus.idle), 60_000)
            fail("expected the wait to fail")
        } catch (_: Exception) {
        }
        assertTrue(System.currentTimeMillis() - started < 10_000)
    }
}
