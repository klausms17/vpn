package com.klausms.vpn

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.klausms.vpn.data.AppRepository
import com.klausms.vpn.service.RuntimeState
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.ProcessExits
import com.klausms.vpn.widget.VpnWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class App : Application() {
    /** Only used by the UI process; the VPN process reads files directly. */
    val repository: AppRepository by lazy { AppRepository(this) }

    /**
     * UI process: work that must finish even if the screen closes meanwhile
     * (Back on Android 8–11 ends the activity and its ViewModel), such as
     * saving an import or applying a changed setting to the tunnel.
     */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    override fun onCreate() {
        super.onCreate()
        val isVpnProcess = currentProcessName().endsWith(":vpn")
        AppLog.init(this, if (isVpnProcess) "vpn" else "ui")
        // A fresh VPN process means any widget picture from before (e.g. a
        // tunnel that was killed) may be stale.
        if (isVpnProcess) {
            // Read before anything in this process changes it: it still says
            // whether the VPN was meant to be on when the last process ended.
            val wanted = RuntimeState.shouldRun(this)
            // With the VPN off, the process also starts just to redraw the
            // widget (about every 30 minutes): a line each time would push
            // the useful ones out of the short log.
            if (wanted) AppLog.i("vpn process started")
            logLastExit(wanted)
            VpnWidget.update(this)
        }
    }

    /**
     * Why the previous VPN process ended: a tunnel that went off by itself
     * then leaves a trace in the log the user can send. [wanted]: the VPN
     * was meant to be on.
     */
    private fun logLastExit(wanted: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(ActivityManager::class.java) ?: return
            val last = am.getHistoricalProcessExitReasons(packageName, 0, 10)
                .firstOrNull { it.processName.endsWith(":vpn") } ?: return
            // Each end once, even if this process is started again before
            // another one ends.
            val prefs = getSharedPreferences("exit_log", Context.MODE_PRIVATE)
            if (prefs.getLong("last_logged", 0L) == last.timestamp) return
            prefs.edit { putLong("last_logged", last.timestamp) }
            if (!ProcessExits.worthLogging(last.reason, wanted, last.importance)) return
            val ago = (System.currentTimeMillis() - last.timestamp) / 1000
            AppLog.w(
                "previous vpn process ended ${ago}s ago" + (if (wanted) " while the VPN was on" else "") +
                    ": ${ProcessExits.reasonName(last.reason)}, importance ${last.importance}, status ${last.status}, " +
                    (last.description ?: "no description"),
            )
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
