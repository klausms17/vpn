package com.klausms.vpn.service

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestartGuardTest {
    private val minute = 60_000L

    @Test
    fun onlyTheAppsOwnFailuresCountAsCrashes() {
        assertTrue(RestartGuard.isCrash(ApplicationExitInfo.REASON_CRASH))
        assertTrue(RestartGuard.isCrash(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(RestartGuard.isCrash(ApplicationExitInfo.REASON_ANR))
        assertTrue(RestartGuard.isCrash(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
        // Memory, a phone maker's killer, Android 17's memory limiter.
        assertFalse(RestartGuard.isCrash(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertFalse(RestartGuard.isCrash(ApplicationExitInfo.REASON_SIGNALED))
        assertFalse(RestartGuard.isCrash(ApplicationExitInfo.REASON_OTHER))
        assertFalse(RestartGuard.isCrash(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
    }

    @Test
    fun atMostThreeRestartsInFiveMinutes() {
        var saved = ""
        repeat(3) { saved = RestartGuard.countRestart(saved, (10 + it) * minute) ?: error("restart ${it + 1} refused") }
        assertNull(RestartGuard.countRestart(saved, 13 * minute))
        // The first leaves the window five minutes after it.
        assertEquals("${11 * minute},${12 * minute},${15 * minute}", RestartGuard.countRestart(saved, 15 * minute))
    }

    @Test
    fun theClockAndRebootsCannotFoolIt() {
        // Wall clock times saved by older versions, or times from before a
        // reboot, are later than the time since boot: they do not count.
        val old = listOf(1_800_000_000_000L, 1_800_000_060_000L, 1_800_000_120_000L).joinToString(",")
        assertEquals("${2 * minute}", RestartGuard.countRestart(old, 2 * minute))
        assertEquals("${2 * minute}", RestartGuard.countRestart("junk,,", 2 * minute))
    }
}
