// Ported from the VS Code extension's src/test/viewTest.ts: shell/agent summaries and the sidebar view
// state, from real pane output and a real (scrubbed) herdr 0.9.1 snapshot.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewTest {
    private val zsh = ProcInfo(1, listOf(Proc(1, "zsh", listOf("-zsh"), null)))
    private val P = "dev@laptop api-service %"
    private val V = "(api-service) dev@laptop api-service %"

    private fun fixture(): Model {
        val text = javaClass.getResourceAsStream("/snapshot-herdr-0.9.1.json")!!.readBytes().toString(Charsets.UTF_8)
        return normalize(JsonParser.parseString(text).asJsonObject.getAsJsonObject("result"))
    }

    @Test
    fun describesProcesses() {
        assertEquals("python app.py", describeProcess(Proc(1, "Python", listOf("/Library/Frameworks/Python.framework/Versions/3.12/Resources/Python.app/Contents/MacOS/Python", "app.py"), null)))
        assertEquals("vi config.yaml", describeProcess(Proc(1, "vim", listOf("vi", "/Users/dev/code/api-service/config.yaml"), null)))
        assertEquals("npm run dev", describeProcess(Proc(1, "node", listOf("/opt/homebrew/bin/npm", "run", "dev"), null)))
        assertEquals("python3 -m http.server", describeProcess(Proc(1, "python3", listOf("python3", "-m", "http.server", "8000"), null)))
        assertTrue(isShellProcess("zsh (kiro-cli-term)") && isShellProcess("zsh (kiro-cli-t") && isShellProcess("-zsh") && !isShellProcess("python"))
    }

    @Test
    fun summarizesShells() {
        var s = summarizeShell(zsh, listOf("$P AUTOSSH_GATETIME=0 autossh -M 0 -f -N -i /Users/dev/.ssh/tunnel.pem \\", "Connection to localhost port 5433 [tcp/*] succeeded!", "tunnel up", P).joinToString("\n"))
        assertEquals(PaneActivity("prompt", "autossh -M 0 -f -N -i tunnel.pem", null, listOf("Connection to localhost port 5433 [tcp/*] succeeded!", "tunnel up"), false), s)
        s = summarizeShell(zsh, listOf("$P ssh -i /k/key.pem host", "ssh: connect to host 192.0.2.10 port 22: Connection refused", "$P ssh -i /k/key.pem host").joinToString("\n"))
        assertEquals("ssh -i key.pem host", s.command)
        assertNull("recalled history equal to the last command is not typed", s.typed)
        assertTrue(s.failed)
        s = summarizeShell(zsh, listOf("$P ls", "a b", "$P git push").joinToString("\n"))
        assertEquals("git push", s.typed)
        s = summarizeShell(zsh, listOf("Restored session: Fri Oct  2", "$P cd ~/Desktop", P, "").joinToString("\n"))
        assertEquals(listOf("cd ~/Desktop", emptyList<String>()), listOf(s.command, s.output))
        s = summarizeShell(zsh, listOf("$P autossh -M 0 -f -N jumphost", "tunnel up", "agent/.venv/bin/activate", "$V  source /Users/dev/code/api-service/", "$V  source /Users/dev/code/api-service/", V).joinToString("\n"))
        assertEquals(listOf("autossh -M 0 -f -N jumphost", null, listOf("tunnel up")), listOf(s.command, s.typed, s.output))
        s = summarizeShell(zsh, listOf("stopped job 7", "$P  source /Users/dev/code/api-service/.venv/bin/acti(api-service").joinToString("\n"))
        assertEquals(listOf(null, listOf("stopped job 7")), listOf(s.typed, s.output))
        s = summarizeShell(zsh, listOf("$V npm run dev", "ready on :3000", V).joinToString("\n"))
        assertEquals(listOf("npm run dev", listOf("ready on :3000")), listOf(s.command, s.output))
        s = summarizeShell(ProcInfo(7, listOf(Proc(7, "vim", listOf("vi", "a.yaml"), null))), "      - \"mysql * -e *DROP*\"")
        assertEquals(listOf("running", "vi a.yaml", emptyList<String>()), listOf(s.state, s.command, s.output))
    }

    @Test
    fun summarizesAgents() {
        val claudeDone = listOf("  Sources:", "  - API reference overview", "", "✻ Crunched for 1m 59s · done Saturday 5:02 PM", "   ", "────────", "❯", "────────", "  ⏵⏵ auto mode on (shift+tab to cycle) · ← 1 agent", "").joinToString("\n")
        assertEquals(listOf("Sources:", "- API reference overview", "✻ Crunched for 1m 59s · done Saturday 5:02 PM"), summarizeAgent(claudeDone, AgentStatus.idle))
        val survey = listOf("Done, tests pass.", "", "● How is Claude doing this session? (optional)", "  1: Bad    2: Fine   3: Good   0: Dismiss", "────", "❯", "────").joinToString("\n")
        assertEquals(listOf("Done, tests pass."), summarizeAgent(survey, AgentStatus.idle))
        val blocked = listOf("╭──────────╮", "│ Bash command │", "│ Do you want to proceed? │", "│ ❯ 1. Yes │", "│   2. No │", "╰──────────╯").joinToString("\n")
        assertEquals(listOf("Bash command", "Do you want to proceed?", "❯ 1. Yes", "2. No"), summarizeAgent(blocked, AgentStatus.blocked))
        assertEquals("You are making the app consistent. Next, review it. new task? /clear", headline(listOf("x", "※ recap: You are making the app", "consistent. Next, review it.", "new task? /clear")))
        assertEquals("One note: your API token expires soon. If you ever hit an error, re-auth.", headline(listOf("One note: your API token expires soon. If", "you ever hit an error, re-auth.")))
        assertEquals("Otherwise run it in a separate terminal window.", headline(listOf("Otherwise run it in a separate terminal window.", "✻ Churned for 5s · done 12:18 PM")))
        assertEquals("✻ Baked for 35s · done 10:30 AM", headline(listOf("✻ Baked for 35s · done 10:30 AM")))
    }

    private fun view(model: Model, showShells: Boolean, acts: Map<String, PaneActivity> = emptyMap()) = buildViewState(
        ViewInputs(model, showShells, { null }, { null }, { it.id == "w6" }, { false }, { acts[it] }, JsonObject()),
    )

    @Test
    fun viewStateFromRealSnapshot() {
        val model = fixture()
        val acts = mapOf(
            "w5:p3" to PaneActivity("running", "python app.py", null, listOf("GET / 200"), false),
            "w2:pA" to PaneActivity("prompt", "cd ~", null, emptyList(), false),
            "w4:p1" to PaneActivity(output = listOf("To be clear about how I operate", "What would you like to do next?")),
        )
        val vs = view(model, true, acts)
        fun sp(id: String) = vs.getAsJsonArray("spaces").map { it.asJsonObject }.first { it.get("id").asString == id }
        fun names(a: com.google.gson.JsonArray) = a.map { it.asJsonObject.get("name").asString }
        assertEquals(5, vs.getAsJsonObject("totals").get("agents").asInt)
        assertEquals(10, vs.getAsJsonObject("totals").get("shells").asInt)
        assertEquals(listOf("DB Tunnel", "Git Review", "reviewr", "Cloud Login", "Tab 5", "Tab 6"), names(sp("w2").getAsJsonArray("shells")))
        val shells = sp("w2").getAsJsonArray("shells").map { it.asJsonObject }
        assertEquals("Git Review", shells.first { it.get("id").asString == "w2:p8" }.get("tab").asString)
        assertEquals("Tab 3", sp("w5").getAsJsonArray("shells").map { it.asJsonObject }.first { it.get("id").asString == "w5:p3" }.get("name").asString)
        val kiro = sp("w4").getAsJsonArray("agents")[0].asJsonObject
        assertEquals(listOf("Kiro", null, "What would you like to do next?"), listOf(kiro.get("name").asString, kiro.get("detail")?.asString, kiro.get("headline").asString))
        assertEquals("Add pagination to the orders API", sp("w2").getAsJsonArray("agents")[0].asJsonObject.get("detail").asString)
        assertTrue(sp("w6").get("mounted").asBoolean)
        assertEquals(0, sp("w8").getAsJsonArray("agents").size())
        assertEquals(0, vs.getAsJsonArray("attention").size())
        assertTrue(view(model, false).getAsJsonArray("spaces").all { it.asJsonObject.getAsJsonArray("shells").isEmpty })
    }

    @Test
    fun attachOrderAgentsByUrgencyThenShells() {
        val ka = fixture().space("w2")!!
        assertEquals(listOf("w2:p1", "w2:p5", "w2:p7", "w2:p8", "w2:p9", "w2:pA", "w2:pC"), attachOrder(ka, true).map { it.id })
        assertEquals(listOf("w2:p1"), attachOrder(ka, false).map { it.id })
    }

    @Test
    fun projectSpacePrefersExactDirectory() {
        fun sp(id: String, cwd: String?) = Space(id, id, cwd, false, emptyMap(), false)
        val spaces = listOf(sp("home", "/home/u"), sp("sub", "/x/a/sub"), sp("a", "/x/a"), sp("ab", "/x/ab"))
        assertEquals("a", projectSpace(spaces, "/x/a")?.id)
        assertEquals("sub", projectSpace(spaces, "/x/a/sub")?.id)
        assertEquals("sub", projectSpace(spaces - spaces[2], "/x/a")?.id)
        assertNull(projectSpace(spaces.take(1), "/x/a"))
    }

    @Test
    fun editorTitleIsSpaceThenPane() {
        val w5 = fixture().space("w5")!!
        val p3 = w5.panes.first { it.id == "w5:p3" }
        assertEquals("${w5.label} · Tab 3", editorTitle(w5, w5.tabs.first { it.id == p3.tabId }, p3))
    }

    @Test
    fun droppedFilesAreTypedAsPaths() {
        assertEquals("/x/a.php '/x/my file.txt' '/x/it'\\''s' ", dropText(listOf(java.io.File("/x/a.php"), java.io.File("/x/my file.txt"), java.io.File("/x/it's"))))
        assertEquals("@/x/a.php @\"/x/my file.txt\" ", dropText(listOf(java.io.File("/x/a.php"), java.io.File("/x/my file.txt")), claude = true))
    }

    @Test
    fun spaceOrderBusyThenRecentThenHerdr() {
        fun sp(id: String, vararg st: AgentStatus) = Space(id, id, null, false, emptyMap(), false).also { space ->
            space.tabs += Tab("$id:t", id, "1", st.mapIndexed { n, x -> fixture().spaces.flatMap { it.agents }.first().copy(id = "$id:p$n", status = x) }.toMutableList())
        }
        val at = mapOf("a" to 5L, "b" to 9L, "c" to 1L, "d" to 3L)
        val ordered = orderByActivity(listOf(sp("a", AgentStatus.idle), sp("b"), sp("c", AgentStatus.working), sp("d", AgentStatus.blocked, AgentStatus.idle), sp("e", AgentStatus.done), sp("f")), { at[it.id] })
        assertEquals(listOf("d", "c", "b", "a", "e", "f"), ordered.map { it.id })
    }

    @Test
    fun scrollKeys() {
        val (shift, ctrl, meta) = listOf(java.awt.event.InputEvent.SHIFT_DOWN_MASK, java.awt.event.InputEvent.CTRL_DOWN_MASK, java.awt.event.InputEvent.META_DOWN_MASK)
        val k = java.awt.event.KeyEvent::class.java
        fun key(n: String) = k.getField("VK_$n").getInt(null)
        assertEquals(true to 31, scrollKey(key("PAGE_UP"), shift, 32))
        assertEquals(false to 31, scrollKey(key("PAGE_DOWN"), shift, 32))
        assertEquals(true to 65535, scrollKey(key("HOME"), ctrl, 32))
        assertEquals(false to 65535, scrollKey(key("END"), meta, 32))
        assertNull("plain PageUp goes to the program", scrollKey(key("PAGE_UP"), 0, 32))
        assertNull(scrollKey(key("HOME"), 0, 32))
    }

    @Test
    fun fileReferencesInTerminalLines() {
        fun refs(s: String) = findFileRefs(s).map { listOf(s.substring(it.start, it.start + it.length), it.path, it.line, it.col) }
        assertEquals(
            listOf(listOf("src/termFind.ts:42:7", "src/termFind.ts", 42, 7), listOf("./a/b.py(12, 3)", "./a/b.py", 12, 3), listOf("README.md", "README.md", null, null)),
            refs("Edited src/termFind.ts:42:7 and ./a/b.py(12, 3), see README.md."),
        )
        assertEquals("versions, URLs, abbreviations are not files", emptyList<Any>(), refs("v1.2.3 and https://github.com/a/b.ts e.g. foo"))
        assertEquals(listOf(listOf("/abs/x.ts#L9", "/abs/x.ts", 9, null), listOf("~/notes/todo", "~/notes/todo", null, null)), refs("at /abs/x.ts#L9 ~/notes/todo"))
        val exists = setOf("/p/pane/src/a.kt", "/p/space/b.kt", "/h/notes/todo")
        val ok: (java.io.File) -> Boolean = { it.path in exists }
        assertEquals("/p/pane/src/a.kt", resolveRef(FileRef(0, 0, "src/a.kt"), listOf("/p/pane", "/p/space"), "/h", ok)?.path)
        assertEquals("/p/space/b.kt", resolveRef(FileRef(0, 0, "./b.kt"), listOf("/p/pane", "/p/space"), "/h", ok)?.path)
        assertEquals("/h/notes/todo", resolveRef(FileRef(0, 0, "~/notes/todo"), emptyList(), "/h", ok)?.path)
        assertNull(resolveRef(FileRef(0, 0, "missing.kt"), listOf("/p/pane"), "/h", ok))
    }

    @Test
    fun refusesBroadMounts() {
        listOf("/", "/home/u", "/x", "/x/a").forEach { assertTrue(it, tooBroadToMount(it, "/home/u", "/x/a")) }
        listOf("/x/ab", "/x/a/sub", "/home/u/code").forEach { assertFalse(it, tooBroadToMount(it, "/home/u", "/x/a")) }
    }

    @Test
    fun parsesGitStatus() {
        val g = parseStatus("## main...origin/main [ahead 1, behind 2]\nM  a.txt\n M b.txt\n?? c.txt\nUU d.txt\n")
        assertEquals(listOf("main", "origin/main", 1, 2, 1, 1, 1, 1, 4), listOf(g.branch, g.upstream, g.ahead, g.behind, g.staged, g.modified, g.untracked, g.conflicts, g.changes))
        assertEquals("detached HEAD", parseStatus("## HEAD (no branch)\n").branch)
        assertEquals("main", parseStatus("## No commits yet on main\n").branch)
    }

    @Test
    fun parsesChangesForReview() {
        val c = parseChanges(" M a b.txt\u0000R  new.txt\u0000old.txt\u0000?? dir/c.txt\u0000")
        assertEquals(listOf(GitChange("a b.txt", " M"), GitChange("new.txt", "R ", "old.txt"), GitChange("dir/c.txt", "??")), c)
    }

    @Test
    fun rawRecordsOfSpaceAndPane() {
        val m = fixture()
        val a = m.space("w2")!!.agents.first()
        assertEquals("w2", m.rawOf("w2", null)!!.get("workspace_id").asString)
        val raw = m.rawOf("w2", a.id)!!
        assertEquals(a.id, raw.getAsJsonObject("pane").get("pane_id").asString)
        assertEquals(a.id, raw.getAsJsonObject("agent").get("pane_id").asString)
    }

    @Test
    fun statusBarText() {
        assertEquals("⬢ Herdr: disconnected", statusText(false, 3, 0))
        assertEquals("⬢ Herdr", statusText(true, 0, 0))
        assertEquals("⬢ 1 blocked · 2 done", statusText(true, 1, 2))
        assertEquals("⬢ 2 done", statusText(true, 0, 2))
    }
}
