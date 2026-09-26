package com.klausms.vpn

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import com.klausms.vpn.data.AppRepository
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.widget.VpnWidget
import java.io.File

class App : Application() {
    /** Only used by the UI process; the VPN process reads files directly. */
    val repository: AppRepository by lazy { AppRepository(this) }

    override fun onCreate() {
        super.onCreate()
        val isVpnProcess = currentProcessName().endsWith(":vpn")
        AppLog.init(this, if (isVpnProcess) "vpn" else "ui")
        // A fresh VPN process means any widget picture from before (e.g. a
        // tunnel that was killed) may be stale.
        if (isVpnProcess) {
            AppLog.i("vpn process started")
            logLastExit()
            VpnWidget.update(this)
        }
    }

    /**
     * Why the previous VPN process ended, if it did not end normally
     * (crash, native crash, killed for memory): a tunnel that went off by
     * itself then leaves a trace in the log the user can send.
     */
    private fun logLastExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val last = am.getHistoricalProcessExitReasons(packageName, 0, 10)
                .firstOrNull { it.processName.endsWith(":vpn") } ?: return
            val normal = last.reason == ApplicationExitInfo.REASON_EXIT_SELF ||
                last.reason == ApplicationExitInfo.REASON_USER_REQUESTED ||
                last.reason == ApplicationExitInfo.REASON_USER_STOPPED ||
                last.reason == ApplicationExitInfo.REASON_PACKAGE_UPDATED
            if (!normal) {
                val ago = (System.currentTimeMillis() - last.timestamp) / 1000
                AppLog.w("previous vpn process ended ${ago}s ago: reason ${last.reason}, status ${last.status}, ${last.description ?: "no description"}")
            }
        } catch (e: Exception) {
            AppLog.w("exit reasons", e)
        }
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
            } catch (_: Exception) {
                packageName
            }
        }
}
