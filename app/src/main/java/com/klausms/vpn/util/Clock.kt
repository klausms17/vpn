package com.klausms.vpn.util

import android.os.SystemClock

/**
 * The time, as logic that runs on timers reads it, so tests can run it on
 * virtual time. Implementations are thread-safe.
 */
interface Clock {
    /** Milliseconds since boot, deep sleep included: for intervals and throttles. */
    fun elapsed(): Long

    /** Milliseconds since 1970 by the phone's clock, which the user may change: for times shown or saved. */
    fun wall(): Long
}

/** The phone's own clocks. */
object AndroidClock : Clock {
    override fun elapsed(): Long = SystemClock.elapsedRealtime()

    override fun wall(): Long = System.currentTimeMillis()
}
