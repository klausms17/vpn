package com.klausms.vpn.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.klausms.vpn.service.RuntimeState
import com.klausms.vpn.service.VpnCommands
import com.klausms.vpn.util.AppLog

/**
 * Taps on the widget's ping and "off" buttons, and redraw requests from the
 * app. Not exported: only this app's own PendingIntents and broadcasts can
 * reach it. Runs in the ":vpn" process next to the tunnel.
 */
class VpnWidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_PING = "com.klausms.vpn.widget.PING"
        const val ACTION_DISCONNECT = "com.klausms.vpn.widget.DISCONNECT"
        const val ACTION_REFRESH = "com.klausms.vpn.widget.REFRESH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PING -> VpnWidget.ping(context, goAsync())
            ACTION_DISCONNECT -> {
                // Only sent from an "on" picture. Honour it even if this
                // process was just restarted and does not know the state
                // yet: a pending automatic restart must not bring the
                // tunnel back after the user turned it off.
                RuntimeState.setShouldRun(context, false)
                try {
                    VpnCommands.disconnect(context, "widget")
                } catch (e: Exception) {
                    AppLog.w("widget could not stop the VPN", e)
                }
                VpnWidget.update(context, goAsync())
            }
            ACTION_REFRESH -> VpnWidget.update(context, goAsync())
        }
    }
}
