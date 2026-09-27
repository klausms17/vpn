package com.klausms.vpn.util

import android.app.ApplicationExitInfo

/** How an ended VPN process is written to the log (ApplicationExitInfo, Android 11+). */
object ProcessExits {
    /**
     * Whether the end is worth a warning. [tunnelWanted]: the VPN was meant
     * to be on when the process ended. Then any end is news, a force-stop
     * too: firmware power managers and cleaners stop apps that way, and
     * Android files it as "user requested". With the VPN off only a crash
     * matters; an idle process being cleared away is routine.
     */
    fun worthLogging(reason: Int, tunnelWanted: Boolean): Boolean =
        tunnelWanted ||
            reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            reason == ApplicationExitInfo.REASON_ANR ||
            reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ||
            reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialization failure"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
        // Force-stop: "Остановить" in the app's settings, a cleaner or a firmware power manager.
        ApplicationExitInfo.REASON_USER_REQUESTED -> "force-stopped (user, cleaner or power manager)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        ApplicationExitInfo.REASON_FREEZER -> "freezer"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "app updated"
        else -> "reason $reason"
    }
}
