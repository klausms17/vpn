package com.klausms.vpn.service

import com.klausms.vpn.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A [Clock] on a test's virtual time: it moves with [scheduler], from
 * [BOOT_MS] since boot and [WALL_MS] by the phone's clock, so throttles
 * and delays run on the same time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class VirtualClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun elapsed(): Long = BOOT_MS + scheduler.currentTime

    override fun wall(): Long = WALL_MS + scheduler.currentTime

    companion object {
        // Well past boot: every "at most every n minutes" gap has passed at the start.
        const val BOOT_MS = 1_000_000_000L
        const val WALL_MS = 1_750_000_000_000L
    }
}
