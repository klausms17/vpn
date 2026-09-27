package com.klausms.vpn.service

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class EpochTest {
    @Test
    fun aNewGenerationOutdatesTheOldOne() {
        val epoch = Epoch()
        val e = epoch.current
        assertTrue(epoch.isCurrent(e))
        epoch.advance()
        assertFalse(epoch.isCurrent(e))
        assertTrue(epoch.isCurrent(epoch.current))
    }

    @Test
    fun oneCheckAtATimeForTheCurrentGeneration() {
        val epoch = Epoch()
        epoch.advance()
        val first = Job()
        var launchedFor = -1L
        assertTrue(epoch.startCheck { e -> launchedFor = e; first })
        assertEquals(epoch.current, launchedFor)
        // While it runs, another one is not even launched.
        assertFalse(epoch.startCheck { error("launched while a check runs") })
        first.complete()
        assertTrue(epoch.startCheck { Job() })
    }

    @Test
    fun aNewGenerationCancelsTheRunningCheck() {
        val epoch = Epoch()
        val check = Job()
        epoch.startCheck { check }
        epoch.advance()
        assertTrue(check.isCancelled)
        // The next check may start at once.
        assertTrue(epoch.startCheck { Job() })
    }

    @Test
    fun nothingAdvancesWhileLocked() {
        val epoch = Epoch()
        val e = epoch.current
        val advancing = CountDownLatch(1)
        lateinit var other: Thread
        val result = epoch.locked {
            other = thread {
                advancing.countDown()
                epoch.advance()
            }
            assertTrue(advancing.await(5, TimeUnit.SECONDS))
            // The other thread now waits for the lock: what was checked here stays true.
            other.join(200)
            assertTrue(other.isAlive)
            epoch.isCurrent(e)
        }
        assertTrue(result)
        other.join(5_000)
        assertFalse(epoch.isCurrent(e))
    }
}
