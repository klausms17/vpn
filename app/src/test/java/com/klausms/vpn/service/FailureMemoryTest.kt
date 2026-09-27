package com.klausms.vpn.service

import com.klausms.vpn.service.Failover.RECENTLY_FAILED_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureMemoryTest {
    private val clock = FakeClock(elapsedMs = 1_000_000)
    private val memory = FailureMemory(clock)

    @Test
    fun aFailedServerIsSkippedForAWhile() {
        memory.markFailed("a")
        clock.elapsedMs += RECENTLY_FAILED_MS - 1
        assertTrue(memory.failedRecently("a"))
        assertEquals(setOf("a"), memory.exclude())
        clock.elapsedMs += 1
        assertFalse(memory.failedRecently("a"))
        assertEquals(emptySet<String>(), memory.exclude())
        assertFalse(memory.failedRecently("never failed"))
    }

    @Test
    fun aFailedServerPickedByHandAgainIsKept() {
        memory.markFailed("a")
        memory.onStarted("a", automatic = false, picked = true)
        assertTrue(memory.keptByUser("a"))
        // New settings for the same server: the choice still stands.
        memory.onStarted("a", automatic = false, picked = false)
        assertTrue(memory.keptByUser("a"))
        // Only while the failure is recent.
        clock.elapsedMs += RECENTLY_FAILED_MS
        assertFalse(memory.keptByUser("a"))
    }

    @Test
    fun aPickOfAServerThatNeverFailedKeepsNothing() {
        memory.onStarted("a", automatic = false, picked = true)
        memory.markFailed("a")
        assertFalse(memory.keptByUser("a"))
    }

    @Test
    fun anotherServerOrAnAutomaticSwitchEndsTheChoice() {
        memory.markFailed("a")
        memory.onStarted("a", automatic = false, picked = true)
        memory.onStarted("b", automatic = false, picked = false)
        assertFalse(memory.keptByUser("a"))

        memory.onStarted("a", automatic = false, picked = true)
        memory.onStarted("a", automatic = true, picked = false)
        assertFalse(memory.keptByUser("a"))
    }
}
