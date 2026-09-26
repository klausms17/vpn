package com.klausms.vpn.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Commands to the VPN process. VpnService.prepare() must have succeeded. */
object VpnCommands {
    private fun intent(context: Context, action: String) =
        Intent(context, XrayVpnService::class.java).setAction(action)

    fun connect(context: Context) =
        ContextCompat.startForegroundService(context, intent(context, XrayVpnService.ACTION_CONNECT))

    /** Applies a new server or settings to a running tunnel. */
    fun reconnect(context: Context) =
        ContextCompat.startForegroundService(context, intent(context, XrayVpnService.ACTION_RECONNECT))

    /** [source]: who asked ("app", "tile", "widget"), for the log. */
    fun disconnect(context: Context, source: String) {
        context.startService(intent(context, XrayVpnService.ACTION_DISCONNECT).putExtra(XrayVpnService.EXTRA_SOURCE, source))
    }
}
