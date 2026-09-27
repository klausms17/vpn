package com.klausms.vpn.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.klausms.vpn.util.AppLog

/**
 * Receives the launcher's widget events (placed, resized, restored after a
 * reboot). Runs in the ":vpn" process; every event just redraws.
 */
class VpnWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            super.onReceive(context, intent)
        } catch (e: RuntimeException) {
            // A malformed broadcast must never take the tunnel process down.
            AppLog.w("bad widget broadcast", e)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        VpnWidget.update(context, goAsync())
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        VpnWidget.update(context, goAsync())
    }

    override fun onDisabled(context: Context) {
        // The last widget was removed.
        VpnWidget.clearState(context)
    }
}
