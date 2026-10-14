// Links from the Herdr plugin: only well-formed ids, absolute paths and the IDE's own session get through.
package io.github.tezmanian.herdrcockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LinksTest {
    private fun parse(action: String, vararg kv: Pair<String, String>, session: String? = null) = parseLink(action, mapOf(*kv)::get, session)

    @Test
    fun acceptsOwnSpacesPanesAndFiles() {
        assertEquals(Link.Open("w2", "w2:p1"), parse("open", "space" to "w2", "pane" to "w2:p1").getOrThrow())
        assertEquals(Link.Review("w2", null), parse("review", "space" to "w2", "pane" to "").getOrThrow())
        assertEquals(Link.OpenFile("/a/b.php", 42, null), parse("file", "path" to "/a/b.php", "line" to "42").getOrThrow())
        assertEquals(Link.Open("w1", null), parse("open", "space" to "w1", "session" to "hcjb", session = "hcjb").getOrThrow())
    }

    @Test
    fun refusesEverythingElse() {
        listOf(
            parse("open"),
            parse("open", "space" to "w1;rm -rf"),
            parse("open", "space" to "w1", "pane" to "x".repeat(65)),
            parse("file", "path" to "relative.php"),
            parse("file", "path" to "/a.php", "line" to "-1"),
            parse("run", "space" to "w1"),
            parse("open", "space" to "w1", "session" to "other"),
            parse("open", "space" to "w1", session = "hcjb"),
        ).forEach { assertTrue(it.toString(), it.isFailure) }
    }

    @Test
    fun pluginScriptParsesLocations() {
        fun loc(s: String) = ProcessBuilder("sh", "herdr-plugin/herdr-cockpit-jetbrains.sh", "parse-location", s)
            .directory(File(".")).start().inputStream.bufferedReader().readText().trimEnd('\n')
        assertEquals("src/a.php\t42\t7", loc("src/a.php:42:7"))
        assertEquals("app.py\t3\t", loc("File \"app.py\", line 3, in main"))
        assertEquals("x.kt\t9\t", loc("x.kt#L9"))
    }
}
