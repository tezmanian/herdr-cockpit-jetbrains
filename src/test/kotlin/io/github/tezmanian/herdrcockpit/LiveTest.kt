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

    @Test
    fun createSpaceTabAndWorktree() {
        assumeTrue("HERDR_LIVE_SOCKET not set", socket != null)
        val client = HerdrClient { socket!! }
        val actions = HerdrActions(client)
        val repo = java.nio.file.Files.createTempDirectory("hc-live-repo").toFile()
        fun git(vararg a: String) = ProcessBuilder(listOf("git", "-C", repo.path) + a).redirectErrorStream(true).start().waitFor()
        git("init", "-q", "-b", "main")
        java.io.File(repo, "README.md").writeText("hc\n")
        git("add", ".")
        git("-c", "user.name=hc", "-c", "user.email=hc@example.invalid", "commit", "-qm", "init")
        val label = "hc-live-${System.nanoTime() % 100000}"
        val closeSpace = { id: String -> client.request("workspace.close", com.google.gson.JsonObject().apply { addProperty("workspace_id", id); addProperty("close_group", true) }) }

        val space = actions.createSpace(repo.path, label)
        try {
            val m = normalize(client.request("session.snapshot"))
            assertTrue("new space is listed", m.space(space.workspaceId)?.label == label)
            assertTrue("new space isn't focused", m.focusedSpaceId != space.workspaceId)

            val tab = actions.createTab(space.workspaceId!!, repo.path, "Shell 2")
            actions.renameTab(tab.tabId!!, "renamed")
            val (sp, p) = normalize(client.request("session.snapshot")).find(tab.paneId)!!
            assertTrue(sp.id == space.workspaceId && sp.tabs.first { it.id == p.tabId }.label == "renamed")
            actions.renamePane(tab.paneId, "pane-x")
            actions.renameSpace(space.workspaceId, "$label-r")
            val m2 = normalize(client.request("session.snapshot"))
            assertTrue(m2.find(tab.paneId)!!.second.paneLabel == "pane-x" && m2.space(space.workspaceId)!!.label == "$label-r")
            actions.closeTab(tab.tabId)
            assertTrue("tab closed", normalize(client.request("session.snapshot")).find(tab.paneId) == null)

            val wt = actions.createWorktreeSpace(space.workspaceId, "hc/live", "hc/live")
            try {
                val w = normalize(client.request("session.snapshot")).space(wt.workspaceId)!!
                assertTrue("worktree space: ${w.cwd}", w.linkedWorktree && w.cwd != null && w.cwd != repo.path)
            } finally {
                client.request("worktree.remove", com.google.gson.JsonObject().apply { addProperty("workspace_id", wt.workspaceId); addProperty("force", true) }, 30_000)
            }
        } finally {
            closeSpace(space.workspaceId!!)
            repo.deleteRecursively()
            // Herdr leaves the (now empty) ~/.herdr/worktrees/<repo> folder behind.
            java.io.File(System.getProperty("user.home"), ".herdr/worktrees/${repo.name}").delete()
        }
    }
}
