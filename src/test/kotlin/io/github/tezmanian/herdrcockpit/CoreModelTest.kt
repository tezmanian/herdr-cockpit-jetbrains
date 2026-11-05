// coreModel() on a layout and terminal list captured from an isolated herdr-app 0.1.13 / herdr-core 0.1.18 sandbox
// (src/test/resources/core): order, derived names, splits, status and seen, pins, filtering, an unreadable layout.
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreModelTest {
    private val home = "/home/dev"

    private fun json(name: String) = JsonParser.parseString(javaClass.getResourceAsStream("/core/$name")!!.readBytes().toString(Charsets.UTF_8)).asJsonObject
    private fun layout() = json("layout.json")
    private fun terminals(): JsonArray = json("terminals.json").getAsJsonArray("terminals")
    private fun model(doc: JsonObject = layout(), ts: JsonArray = terminals(), gitTop: (String) -> String? = { null }) = coreModel(doc, ts, home, gitTop, "0.1.18")

    private fun terminal(ts: JsonArray, address: String) = ts.map { it.asJsonObject }.first { it.get("address").asString == address }

    @Test
    fun followsTheLayoutAndNamesLikeTheApp() {
        val m = model()
        assertEquals(listOf("cockpit", "tmp"), m.spaces.map { it.label })
        val cockpit = m.spaces[0]
        assertEquals(listOf("main", "2"), cockpit.tabs.map { it.label })
        assertEquals("the split tab in tree order", listOf("t2", "t3"), cockpit.tabs[1].panes.map { it.id })
        assertEquals("Tab 2", paneName(cockpit.tabs[1], cockpit.tabs[1].panes[0]))
        assertEquals(listOf("1"), m.spaces[1].tabs.map { it.label })
        assertEquals("/var/tmp", m.spaces[1].cwd)
        assertEquals("2 (experimental) · core 0.1.18", m.version)
        assertNull(m.protocol)
        assertNull(m.focusedSpaceId)
        assertTrue(m.raw.has("doc") && m.raw.has("terminals"))
    }

    @Test
    fun unnamedSpacesTakeTheGitTopLevelThenTheFolder() {
        assertEquals("repo", model(gitTop = { if (it == "/var/tmp") "/work/repo" else null }).spaces[1].label)
        assertEquals("~", folderName(home, home))
        assertEquals("~", folderName("", home))
        assertEquals("tmp", folderName("/var/tmp/", home))
    }

    @Test
    fun agentsAreReportingTerminalsWithSeenStatus() {
        val t1 = model().find("t1")!!.second
        assertTrue(t1.isAgent)
        assertEquals("turn 1 > herdr.seen 0", AgentStatus.done, t1.status)
        assertEquals("bash", t1.agentKind)
        assertEquals("/tmp", t1.cwd)
        assertFalse(t1.focused)
        val shell = model().find("t2")!!.second
        assertFalse(shell.isAgent)
        assertNull(shell.status)

        val ts = terminals()
        terminal(ts, "t1").getAsJsonObject("labels").addProperty("herdr.seen", "1")
        assertEquals(AgentStatus.idle, model(ts = ts).find("t1")!!.second.status)
        terminal(ts, "t1").getAsJsonObject("labels").remove("herdr.seen")
        assertEquals("no seen label: the reported word", AgentStatus.idle, model(ts = ts).find("t1")!!.second.status)

        terminal(ts, "t1").addProperty("turn", "x")
        assertEquals("an odd turn counts as 0", AgentStatus.idle, model(ts = ts).find("t1")!!.second.status)

        assertEquals(AgentStatus.working, coreStatus("working", 3, 0))
        assertEquals(AgentStatus.blocked, coreStatus("error", 3, 3))
        assertEquals(AgentStatus.blocked, coreStatus("blocked", 3, null))
        assertEquals(AgentStatus.done, coreStatus("done", 0, null))
    }

    @Test
    fun pinnedAgentsSortFirstInTheView() {
        val ts = terminals()
        listOf("t2", "t3").forEach { a -> terminal(ts, a).addProperty("reported", true) }
        terminal(ts, "t3").getAsJsonObject("labels").addProperty("herdr.pinned", "1")
        val m = model(ts = ts)
        assertTrue(m.find("t3")!!.second.pinned)
        assertFalse(m.find("t2")!!.second.pinned)
        val inputs = ViewInputs(m, true, { null }, { null }, { false }, { false }, { null }, JsonObject())
        val agents = buildViewState(inputs).getAsJsonArray("spaces")[0].asJsonObject.getAsJsonArray("agents").map { it.asJsonObject.get("id").asString }
        assertEquals(listOf("t3", "t1", "t2"), agents)
    }

    @Test
    fun dropsFinishedRemoteUnplacedAndMissingTerminals() {
        val ts = terminals()
        terminal(ts, "t4").addProperty("state", "finished")
        ts.add(JsonObject().apply { addProperty("address", "a/t1"); addProperty("state", "live") })
        ts.add(JsonObject().apply { addProperty("address", "t9"); addProperty("state", "live") })
        val doc = layout()
        val tab = doc.getAsJsonObject("value").getAsJsonArray("spaces")[0].asJsonObject.getAsJsonArray("tabs")[0].asJsonObject
        tab.getAsJsonObject("root").getAsJsonArray("children").apply {
            add(JsonParser.parseString("""{"id":"p-gone","type":"block","content":{"terminal":"t42"}}"""))
            add(JsonParser.parseString("""{"id":"p-mod","type":"block","content":{"mod":"notes"}}"""))
            add(JsonParser.parseString("""{"id":"p-remote","type":"block","content":{"terminal":"a/t1"}}"""))
        }
        val m = model(doc, ts)
        assertEquals(listOf("t1", "t2", "t3"), m.paneIds())
        assertEquals("a tab without panes is still shown", 1, m.spaces[1].tabs.size)
        assertTrue(m.spaces[1].tabs[0].panes.isEmpty())
    }

    @Test
    fun anEmptyLayoutIsAnEmptyModel() {
        val m = model(json("layout-empty.json"))
        assertTrue(m.spaces.isEmpty())
        assertEquals("2 (experimental) · core 0.1.18", m.version)
    }

    @Test
    fun aLayoutOfAnotherShapeIsUnreadable() {
        for (bad in listOf("""{"value":{"spaces":{}},"version":1}""", """{"value":[],"version":1}""",
                """{"value":{"spaces":[{"name":"x","tabs":[]}]},"version":1}""", """{"value":{"spaces":[{"id":"s","tabs":[{"name":"t"}]}]},"version":1}""")) {
            try {
                model(JsonParser.parseString(bad).asJsonObject)
                fail("expected unreadable: $bad")
            } catch (e: LayoutUnreadable) {
                assertEquals("unreadable", e.code)
            }
        }
    }
}
