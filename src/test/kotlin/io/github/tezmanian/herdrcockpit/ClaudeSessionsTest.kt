// Claude Code's saved sessions: folder name, titles from the transcript's tail, newest first.
package io.github.tezmanian.herdrcockpit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

class ClaudeSessionsTest {
    private val home = Files.createTempDirectory("hc").toFile()

    @After
    fun cleanup() {
        home.deleteRecursively()
    }

    @Test
    fun folderNameReplacesEveryNonAlphanumeric() {
        assertEquals(File(home, ".claude/projects/-home-dev-a-b--claude-x"), claudeProjectDir(home, "/home/dev/a.b/.claude/x"))
    }

    @Test
    fun sessionsAreNewestFirstWithTheBestTitle() {
        val dir = claudeProjectDir(home, "/src/app").apply { mkdirs() }
        fun session(id: String, modified: Long, vararg lines: String) =
            File(dir, "$id.jsonl").apply { writeText(lines.joinToString("\n", postfix = "\n")); setLastModified(modified) }
        val a = "11111111-1111-1111-1111-111111111111"
        val b = "22222222-2222-2222-2222-222222222222"
        val c = "33333333-3333-3333-3333-333333333333"
        session(a, 1_000_000, """{"type":"last-prompt","lastPrompt":"fix it\nplease"}""", """{"type":"ai-title","aiTitle":"Old"}""", """{"type":"ai-title","aiTitle":"Find bar"}""")
        session(b, 3_000_000, """{"type":"ai-title","aiTitle":"Auto"}""", """{"type":"custom-title","customTitle":"mine"}""", """{"type":"user","message":{"content":"\"ai-title\""}}""")
        session(c, 2_000_000, """{"type":"last-prompt","lastPrompt":"only a prompt\nsecond line"}""")
        File(dir, "notes.jsonl").writeText("""{"type":"ai-title","aiTitle":"not a session"}""")
        assertEquals(
            listOf(ClaudeSession(b, "mine", 3_000_000), ClaudeSession(c, "only a prompt", 2_000_000), ClaudeSession(a, "Find bar", 1_000_000)),
            claudeSessions(dir),
        )
        assertEquals(emptyList<ClaudeSession>(), claudeSessions(File(home, "missing")))
    }

    @Test
    fun whenTodayYesterdayOrDate() {
        val today = LocalDate.of(2026, 10, 22)
        fun at(d: LocalDate, h: Int) = d.atTime(h, 12).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("09:12", sessionWhen(at(today, 9), today, ZoneOffset.UTC))
        assertEquals("yesterday", sessionWhen(at(today.minusDays(1), 9), today, ZoneOffset.UTC))
        assertEquals("Oct 20", sessionWhen(at(today.minusDays(2), 9), today, ZoneOffset.UTC))
    }
}
