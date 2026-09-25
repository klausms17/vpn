package com.klausms.vpn.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * Receives the launcher's widget events (placed, resized, restored after a
 * reboot). Runs in the ":vpn" process; every event just redraws.
 */
class VpnWidgetProvider : AppWidgetProvider() {
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
