package com.klausms.vpn.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.klausms.vpn.util.AppLog

/** Commands to the VPN process. VpnService.prepare() must have succeeded. */
object VpnCommands {
    private fun intent(context: Context, action: String) =
        Intent(context, XrayVpnService::class.java).setAction(action)

    /** [picked]: the user has just chosen the server by hand (their choice then wins over automatic switches). */
    fun connect(context: Context, picked: Boolean = false) =
        ContextCompat.startForegroundService(
            context,
            intent(context, XrayVpnService.ACTION_CONNECT).putExtra(XrayVpnService.EXTRA_PICKED, picked),
        )

    /** Applies a new server or settings to a running tunnel. [picked]: as for [connect]. */
    fun reconnect(context: Context, picked: Boolean = false) =
        ContextCompat.startForegroundService(
            context,
            intent(context, XrayVpnService.ACTION_RECONNECT).putExtra(XrayVpnService.EXTRA_PICKED, picked),
        )

    /** [source]: who asked ("app", "tile", "widget"), for the log. */
    fun disconnect(context: Context, source: String) {
        context.startService(intent(context, XrayVpnService.ACTION_DISCONNECT).putExtra(XrayVpnService.EXTRA_SOURCE, source))
    }

    /**
     * Starts the tunnel again when it should run but nothing runs it (after
     * an app update, which stops it). Only if the user allowed this VPN
     * before: it never asks for consent and never takes the VPN over from
     * another app. Never throws.
     */
    fun resume(context: Context) {
        if (!RuntimeState.shouldRun(context) || !RuntimeState.vpnConsented(context)) return
        try {
            ContextCompat.startForegroundService(context, intent(context, XrayVpnService.ACTION_RESUME))
        } catch (e: Exception) {
            // Started from the background where Android does not allow it.
            AppLog.w("could not resume the tunnel", e)
        }
    }
}
