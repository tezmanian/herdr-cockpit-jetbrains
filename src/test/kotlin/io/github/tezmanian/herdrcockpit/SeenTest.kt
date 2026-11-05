// SeenTracker: when the plugin raises an agent's shared `herdr.seen` marker, and Mark unread.
package io.github.tezmanian.herdrcockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenTest {
    private val writes = mutableListOf<Pair<String, Long>>()
    private var looking = setOf("t1")
    private val tracker = SeenTracker({ it in looking }) { id, n -> writes += id to n }

    private fun agent(turn: Long, seen: Long?, id: String = "t1") =
        Pane(id, "tb", "sp", id, null, true, "claude", null, false, null, null, null, emptyMap(), null, null, null, turn = turn, seen = seen)

    @Test
    fun writesOncePerTurnWhileLooking() {
        tracker.observe(listOf(agent(1, 0)))
        tracker.observe(listOf(agent(1, 0))) // the label write hasn't shown up in the snapshot yet
        assertEquals(listOf("t1" to 1L), writes)
        tracker.observe(listOf(agent(1, 1)))
        tracker.observe(listOf(agent(2, 1)))
        assertEquals(listOf("t1" to 1L, "t1" to 2L), writes)
    }

    @Test
    fun writesWhenTheLabelIsMissingButNeverLowersIt() {
        tracker.observe(listOf(agent(3, null)))
        assertEquals(listOf("t1" to 3L), writes)
        writes.clear()
        tracker.observe(listOf(agent(2, 5)))
        assertEquals(emptyList<Pair<String, Long>>(), writes)
    }

    @Test
    fun writesNothingWhenNotLooking() {
        looking = emptySet()
        tracker.observe(listOf(agent(1, 0), agent(4, 2, "t2")))
        assertEquals(emptyList<Pair<String, Long>>(), writes)
        val shell = agent(1, 0).copy(isAgent = false)
        looking = setOf("t1")
        tracker.observe(listOf(shell))
        assertEquals("shells have no status", emptyList<Pair<String, Long>>(), writes)
    }

    @Test
    fun markUnreadHoldsForTheRestOfTheTurn() {
        tracker.observe(listOf(agent(2, 1)))
        assertTrue(tracker.canMarkUnread(agent(2, 2)))
        tracker.markUnread(agent(2, 2))
        tracker.observe(listOf(agent(2, 1)))
        assertEquals(listOf("t1" to 2L, "t1" to 1L), writes)
        tracker.observe(listOf(agent(3, 1)))
        assertEquals("a new turn is seen again", "t1" to 3L, writes.last())
    }

    @Test
    fun aFailingWriteDoesNotStopTheOthersAndIsNotRetriedThisTurn() {
        looking = setOf("t1", "t2")
        val errors = mutableListOf<Exception>()
        val t = SeenTracker({ it in looking }, { errors += it }) { id, n -> if (id == "t1") throw HerdrError("busy", "no") else writes += id to n }
        t.observe(listOf(agent(1, 0), agent(1, 0, "t2")))
        t.observe(listOf(agent(1, 0), agent(1, 0, "t2")))
        assertEquals(listOf("t2" to 1L), writes)
        assertEquals(1, errors.size)
    }

    @Test
    fun aReusedAddressIsWrittenAgain() {
        tracker.observe(listOf(agent(1, 0)))
        tracker.observe(emptyList()) // the core restarted: t1 is gone
        tracker.observe(listOf(agent(1, 0)))
        assertEquals(listOf("t1" to 1L, "t1" to 1L), writes)
    }

    @Test
    fun markUnreadIsNotOfferedAtTurnZero() {
        assertFalse(tracker.canMarkUnread(agent(0, null)))
        tracker.markUnread(agent(0, null))
        assertEquals(emptyList<Pair<String, Long>>(), writes)
    }
}
