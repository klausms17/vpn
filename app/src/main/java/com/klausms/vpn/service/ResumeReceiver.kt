package com.klausms.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.klausms.vpn.util.AppLog

/**
 * Brings the tunnel back after the app was updated. Installing an update
 * stops the app, service included, and Android restarts it by itself only
 * when the system's Always-on VPN is set. The system sends this broadcast
 * to this app only, and it may start the VPN from the background.
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!RuntimeState.shouldRun(context)) return
        AppLog.i("app updated while the VPN was on, starting it again")
        VpnCommands.resume(context)
    }
}
