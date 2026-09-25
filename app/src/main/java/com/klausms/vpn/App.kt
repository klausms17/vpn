package com.klausms.vpn

import android.app.Application
import android.os.Build
import com.klausms.vpn.data.AppRepository
import com.klausms.vpn.util.AppLog
import java.io.File

class App : Application() {
    /** Only used by the UI process; the VPN process reads files directly. */
    val repository: AppRepository by lazy { AppRepository(this) }

    override fun onCreate() {
        super.onCreate()
        val isVpnProcess = currentProcessName().endsWith(":vpn")
        AppLog.init(this, if (isVpnProcess) "vpn" else "ui")
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
