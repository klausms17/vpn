package com.klausms.vpn.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResetSchedulerTest {
    private val epoch = Epoch()
    private val ran = mutableListOf<String>()

    private fun TestScope.scheduler() = ResetScheduler(epoch, this, StandardTestDispatcher(testScheduler))

    @Test
    fun anOutdatedCheckNeitherCancelsNorReplacesThePendingReset() = runTest {
        val resets = scheduler()
        // A check began, then the network changed and queued its reset.
        val checked = epoch.current
        epoch.advance()
        assertTrue(resets.schedule(1_500, expectedEpoch = null) { ran += "network" })
        // The check's blocking test ends only now, and asks for its own reset.
        assertFalse(resets.schedule(0, expectedEpoch = checked) { ran += "stuck core" })
        advanceUntilIdle()
        assertEquals(listOf("network"), ran)
    }

    @Test
    fun aCheckOfTheCurrentGenerationReplacesThePendingReset() = runTest {
        val resets = scheduler()
        assertTrue(resets.schedule(1_500, expectedEpoch = null) { ran += "network" })
        assertTrue(resets.schedule(0, expectedEpoch = epoch.current) { ran += "stuck core" })
        advanceUntilIdle()
        assertEquals(listOf("stuck core"), ran)
    }

    @Test
    fun theResetWaitsForItsDelay() = runTest {
        val resets = scheduler()
        resets.schedule(1_500, expectedEpoch = null) { ran += "network" }
        advanceTimeBy(1_499)
        runCurrent()
        assertEquals(emptyList<String>(), ran)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("network"), ran)
    }

    @Test
    fun cancelDropsThePendingReset() = runTest {
        val resets = scheduler()
        resets.schedule(1_500, expectedEpoch = null) { ran += "network" }
        resets.cancel()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), ran)
        // The next one runs as usual.
        assertTrue(resets.schedule(0, expectedEpoch = null) { ran += "again" })
        advanceUntilIdle()
        assertEquals(listOf("again"), ran)
    }
}
