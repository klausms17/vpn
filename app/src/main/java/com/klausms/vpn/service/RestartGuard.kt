package com.klausms.vpn.service

import android.app.ApplicationExitInfo

/**
 * When the system restarts the VPN process it ended, whether to go on.
 * Only real crashes count towards giving up: the system also ends the
 * process to free memory, or a phone maker's battery manager kills it, and
 * a restart after those usually works (Android spaces them out itself).
 */
internal object RestartGuard {
    /** Automatic restarts after crashes allowed per window. */
    const val MAX_RESTARTS = 3
    const val WINDOW_MS = 5 * 60_000L

    /** Whether an [ApplicationExitInfo] reason is the app's own fault (it would crash again). */
    fun isCrash(reason: Int): Boolean = reason == ApplicationExitInfo.REASON_CRASH ||
        reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
        reason == ApplicationExitInfo.REASON_ANR ||
        reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE

    /**
     * [saved] ("t1,t2,…", elapsed realtime) with a restart at [now] counted,
     * or null when [MAX_RESTARTS] were in the last [WINDOW_MS]. Times after
     * [now] are from before a reboot and do not count.
     */
    fun countRestart(saved: String, now: Long): String? = Failover.count(saved, now, MAX_RESTARTS, WINDOW_MS)
}
