// Pane claims (Claims.kt): the files VS Code and JetBrains windows share to say who took a pane over.
package io.github.tezmanian.herdrcockpit

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ClaimsTest {
    private val home = Files.createTempDirectory("hc-home").toFile()
    private val alive = mutableSetOf(1L, 2L, 3L)
    private val claims = Claims(home.path) { it in alive }
    private val sock = "/s/herdr.sock"
    private val win = Any()
    private val other = Any()

    @After
    fun cleanup() { home.deleteRecursively() }

    @Test
    fun fileNameMatchesUpstream() {
        // sha256("/s/herdr.sock\nw1:p1") hex, first 24 characters: the same file the VS Code extension uses.
        val hex = java.security.MessageDigest.getInstance("SHA-256").digest("$sock\nw1:p1".toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(java.io.File(home, ".herdr-cockpit/claims/${hex.take(24)}.json"), claims.file(sock, "w1:p1"))
    }

    @Test
    fun whoHasThePane() {
        val me = PaneClaim("PhpStorm", "jetbrains", 1, 100)
        claims.write(sock, "w1:p1", PaneClaim("Visual Studio Code", "vscode", 2, 200), other)
        assertEquals("Visual Studio Code", claims.holder(sock, "w1:p1", me, win))
        assertNull("a claim from before we took it is stale", claims.holder(sock, "w1:p1", me, win, since = 300))
        claims.write(sock, "w1:p1", PaneClaim("PhpStorm", "jetbrains", 3, 200), other)
        assertEquals("another PhpStorm window", claims.holder(sock, "w1:p1", me, win))
        alive -= 3
        assertNull("the claiming window is gone", claims.holder(sock, "w1:p1", me, win))
    }

    @Test
    fun windowsOfOneIdeShareThePid() {
        val me = PaneClaim("PhpStorm", "jetbrains", 1, 100)
        claims.write(sock, "w1:p1", me, other)
        assertEquals("another PhpStorm window", claims.holder(sock, "w1:p1", me, win))
        assertNull("our own claim", claims.holder(sock, "w1:p1", me, other))
        claims.remove(sock, "w1:p1", 1, win)
        assertTrue("another window's claim stays", claims.file(sock, "w1:p1").exists())
        claims.remove(sock, "w1:p1", 1, other)
        assertFalse(claims.file(sock, "w1:p1").exists())
    }

    @Test
    fun pruneDropsClaimsOfGoneWindows() {
        claims.write(sock, "w1:p1", PaneClaim("Kiro", "kiro", 2, 1), other)
        claims.write(sock, "w1:p2", PaneClaim("Cursor", "cursor", 9, 1), other)
        claims.prune()
        assertTrue(claims.file(sock, "w1:p1").exists())
        assertFalse(claims.file(sock, "w1:p2").exists())
    }
}
