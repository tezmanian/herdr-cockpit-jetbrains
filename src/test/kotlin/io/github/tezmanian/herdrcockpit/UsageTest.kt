// Usage.kt against fixture home directories (shapes of Claude Code, Codex and Kiro files).
package io.github.tezmanian.herdrcockpit

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset

class UsageTest {
    private val home = Files.createTempDirectory("hc-home").toFile()
    private val now = Instant.parse("2026-10-14T12:30:00Z").toEpochMilli()
    private val sec = now / 1000

    @After
    fun cleanup() { home.deleteRecursively() }

    private fun write(path: String, text: String) = File(home, path).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun nothingInstalledMeansNoEntries() {
        assertEquals(0, readUsage(home.path, now, setOf("claude", "codex", "kiro"), null, 0).size())
    }

    @Test
    fun claudeLimitsAndTokensInTheReportedWindow() {
        write(".herdr-cockpit/claude-usage.json", """{"rate_limits":{"five_hour":{"used_percentage":42.5,"resets_at":${sec + 3600}},"seven_day":{"used_percentage":18,"resets_at":${sec - 10}}}}""")
        val msg = { ts: String, id: String, inTok: Int -> """{"timestamp":"$ts","requestId":"r$id","message":{"id":"m$id","usage":{"input_tokens":$inTok,"output_tokens":5,"cache_creation_input_tokens":1,"cache_read_input_tokens":100}}}""" }
        write(".claude/projects/p/s.jsonl", listOf(
            msg("2026-10-14T09:00:00Z", "0", 1000), // the window resets 13:30, so it started 08:30
            msg("2026-10-14T08:00:00Z", "1", 2000), // before the window
            msg("2026-10-14T12:00:00Z", "2", 10),
            msg("2026-10-14T12:00:00Z", "2", 10), // streamed twice
            "not json",
        ).joinToString("\n"))
        val u = claudeUsage(home.path, now)!!
        val w = u.getAsJsonArray("windows")
        assertEquals("5h", w[0].asJsonObject.get("label").asString)
        assertEquals(42.5, w[0].asJsonObject.get("usedPercent").asDouble, 0.0)
        assertTrue("7d window is past its reset", w[1].asJsonObject.get("reset").asBoolean)
        assertEquals(0.0, w[1].asJsonObject.get("usedPercent").asDouble, 0.0)
        assertFalse(u.has("canSetUpLimits"))
        val b = u.getAsJsonObject("block")
        assertTrue(b.get("exact").asBoolean)
        assertEquals((1000 + 6) + (10 + 6), b.get("tokens").asLong)
        assertEquals(200, b.get("cacheRead").asLong)
    }

    @Test
    fun claudeWithoutCaptureOffersSetupAndEstimatesTheBlock() {
        write(".claude/projects/p/s.jsonl", """{"timestamp":"2026-10-14T10:20:00Z","message":{"usage":{"input_tokens":7}}}""")
        val u = claudeUsage(home.path, now)!!
        assertTrue(u.get("canSetUpLimits").asBoolean)
        val b = u.getAsJsonObject("block")
        assertFalse(b.get("exact").asBoolean)
        assertEquals(Instant.parse("2026-10-14T10:00:00Z").toEpochMilli(), b.get("startsAt").asLong)
        assertEquals(7, b.get("tokens").asLong)
    }

    @Test
    fun claudeTranscriptsAreReadIncrementally() {
        val line = { id: String, n: Int -> """{"timestamp":"2026-10-14T10:20:00Z","requestId":"r$id","message":{"id":"m$id","usage":{"input_tokens":$n}}}""" }
        val f = write(".claude/projects/p/s.jsonl", line("1", 1) + "\n" + line("2", 2)) // the last line still being written
        fun tokens() = claudeUsage(home.path, now)!!.getAsJsonObject("block").get("tokens").asLong
        assertEquals(3, tokens())
        f.appendText("\n" + line("2", 2) + "\n" + line("3", 4) + "\n") // line 2 complete, streamed again, then 3
        assertEquals("appended lines counted, duplicates once", 7, tokens())
        assertEquals("nothing new: the same", 7, tokens())
        f.writeText(line("9", 50) + "\n") // rewritten shorter
        assertEquals("a shrunk file is read again", 50, tokens())
    }

    @Test
    fun blocksStartAtTheHourAndExpire() {
        val h = 3_600_000L
        val e = listOf(TokenEntry(10 * h + 5, 1, 0), TokenEntry(14 * h, 2, 0), TokenEntry(16 * h, 4, 0))
        assertEquals(Block(16 * h, 21 * h, 4, 0, false), currentBlock(e, 16 * h))
        assertNull(currentBlock(e, 21 * h))
    }

    @Test
    fun codexFromSessionLogsAndLive() {
        write(".codex/sessions/2026/10/14/rollout-a.jsonl", """
            {"timestamp":"2026-10-14T11:00:00Z","payload":{"rate_limits":{"plan_type":"team","primary":{"used_percent":12,"window_minutes":300,"resets_at":${sec + 60}},"secondary":{"used_percent":3,"window_minutes":10080}}}}
            {"timestamp":"2026-10-14T11:01:00Z","payload":{"type":"other"}}
        """.trimIndent())
        val u = codexUsage(home.path, now)!!
        assertEquals("team", u.get("plan").asString)
        assertEquals(listOf("5h", "7d"), u.getAsJsonArray("windows").map { it.asJsonObject.get("label").asString })
        assertEquals(Instant.parse("2026-10-14T11:00:00Z").toEpochMilli(), u.get("asOf").asLong)

        val live = codexRateLimits(JsonParser.parseString("""{"planType":"plus","primary":{"usedPercent":50,"windowDurationMins":300,"resetsAt":${sec + 60}}}""").asJsonObject, now)!!
        assertTrue(live.get("live").asBoolean)
        assertEquals((sec + 60) * 1000, live.getAsJsonArray("windows")[0].asJsonObject.get("resetsAt").asLong)
        // Live numbers win over the logs.
        assertEquals("plus", readUsage(home.path, now, setOf("codex"), live, 0)[0].asJsonObject.get("plan").asString)
    }

    @Test
    fun kiroCreditsThisMonthAndToday() {
        val turn = { ts: String, v: Double -> """{"end_timestamp":"$ts","metering_usage":[{"value":$v,"unit":"credit"},{"value":99,"unit":"other"}],"tool_args":"secret"}""" }
        write(".kiro/sessions/cli/a.json", """{"session_state":{"conversation_metadata":{"user_turn_metadatas":[${turn("2026-10-14T08:00:00Z", 1.5)},${turn("2026-10-02T08:00:00Z", 2.0)},${turn("2026-09-30T08:00:00Z", 50.0)}]}}}""")
        val u = kiroUsage(home.path, now, 0, ZoneOffset.UTC)!!
        assertEquals(3.5, u.getAsJsonObject("credits").get("month").asDouble, 1e-9)
        assertEquals(1.5, u.getAsJsonObject("credits").get("today").asDouble, 1e-9)
        assertEquals(0, u.getAsJsonArray("windows").size())

        val limited = kiroUsage(home.path, now, 10, ZoneOffset.UTC)!!
        val w = limited.getAsJsonArray("windows")[0].asJsonObject
        assertEquals(35.0, w.get("usedPercent").asDouble, 1e-9)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), w.get("resetsAt").asLong)
    }

    @Test
    fun onlyRunningAgentsAreReadAndShown() {
        File(home, ".claude").mkdirs()
        File(home, ".kiro/sessions/cli").mkdirs()
        assertEquals(listOf("kiro"), readUsage(home.path, now, setOf("kiro"), null, 0).map { it.asJsonObject.get("id").asString })
    }
}
