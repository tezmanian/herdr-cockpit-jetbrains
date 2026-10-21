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
}
