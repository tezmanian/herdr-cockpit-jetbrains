// The agent's findings file: only well-formed findings inside the repo get through.
package io.github.tezmanian.herdrcockpit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AgentReviewTest {
    private val root = Files.createTempDirectory("hc").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun keepsFindingsInsideTheRepo() {
        val json = """[
            {"path": "src/a.kt", "line": 3, "body": " off by one "},
            {"path": "./src/../b.kt", "line": 1, "body": "ok"},
            {"path": "../outside.kt", "line": 1, "body": "escapes"},
            {"path": "/etc/passwd", "line": 1, "body": "absolute"},
            {"path": "", "line": 1, "body": "no path"},
            {"path": "c.kt", "line": 0, "body": "no line"},
            {"path": "c.kt", "line": "x", "body": "bad line"},
            {"path": "c.kt", "line": 2, "body": "  "},
            {"path": "c.kt", "line": 2},
            "text"
        ]"""
        assertEquals(listOf(Finding("src/a.kt", 3, "off by one"), Finding("b.kt", 1, "ok")), parseReview(json, root))
    }

    @Test
    fun notAnArrayIsNull() {
        assertNull(parseReview("""{"path": "a"}""", root))
        assertNull(parseReview("not json [", root))
        assertEquals(emptyList<Finding>(), parseReview("[]", root))
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals(File(root, ".herdr-cockpit/reviews/core_-feat_x.json"), reviewFile(root, "core ", "feat/x"))
    }

    @Test
    fun rangeIsTheMostCommonBeforeAndAfterCommit() {
        val a = "aaaaaaa1"
        val b = "bbbbbbb2"
        assertEquals(a to b, changeRange(listOf(a to b, null to b, a to null, "cccccc3" to b, a to "not a hash")))
        assertNull(changeRange(listOf(null to b)))
        assertNull(changeRange(listOf(a to a)))
    }

    @Test
    fun defaultPromptsCarryTheDiffTheFilesAndTheRules() {
        val out = File(root, "r.json")
        val p = reviewPrompt("/repo", "feat", "origin/main", out, " focus on SQL ", listOf("a.kt", "it's.kt"))
        assert(p.contains("`git diff origin/main...HEAD`")) { p }
        assert(p.contains("Changed files: 'a.kt' 'it'\\''s.kt'")) { p }
        assert(p.contains("Additional instructions: focus on SQL\n\nOnly read:")) { p }
        assert(p.contains("findings to ${out.path} as a JSON array")) { p }
        // No instructions, no files: those lines go, no blank-line pile-up.
        val bare = reviewPrompt("/repo", "feat", "origin/main", out)
        assert(!bare.contains("Additional instructions") && !bare.contains("Changed files") && !bare.contains("\n\n\n")) { bare }
        val mr = reviewChangesPrompt("/repo", "aaa", "bbb", listOf("x/y.php"), out)
        assert(mr.contains("`git diff aaa bbb -- 'x/y.php'`") && mr.contains("as of bbb")) { mr }
    }

    @Test
    fun templatesFillInOnePass() {
        val vars = mapOf("diff" to "git diff a b", "instructions" to "look at {diff}", "out" to "/o.json", "format" to "[]")
        assertEquals("Run git diff a b. Note: look at {diff}. Write to /o.json. {unknown}",
            fillTemplate("Run {diff}. Note: {instructions}. Write to {out}. {unknown}", vars))
        // Without {out} the agent wouldn't know where to write: the rules are added.
        assert(fillTemplate("Just {diff}", vars).endsWith("or [] if you found nothing. In each body, say what goes wrong and in which situation."))
    }

    @Test
    fun untouchedLegacyPresetsAreUpgraded() {
        val legacy = promptsFromLines(LEGACY_PROMPTS)
        val mine = SavedPrompt("Mine", "review", "my own")
        val edited = legacy[1].copy(text = "Only real bugs, please")
        assertEquals(listOf(DEFAULT_PROMPTS[0], edited, mine, DEFAULT_PROMPTS[4]), upgradePresets(listOf(legacy[0], edited, mine, legacy[4])))
    }
}
