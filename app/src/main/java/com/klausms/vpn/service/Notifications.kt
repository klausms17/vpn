package com.klausms.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.klausms.vpn.R
import com.klausms.vpn.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "vpn_status"
    const val CHANNEL_ALERTS = "vpn_alerts"
    const val ID_STATUS = 1
    const val ID_ALERT = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Low importance: no sound, no heads-up, no badge. The status
        // notification is only there because Android requires it.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Состояние VPN", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Ошибки VPN", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(false)
            },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun status(context: Context, title: String, text: String?, withDisconnect: Boolean): Notification {
        val builder = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (!text.isNullOrEmpty()) builder.setContentText(text)
        if (withDisconnect) {
            val stop = PendingIntent.getService(
                context, 1,
                Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_DISCONNECT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(Notification.Action.Builder(null, "Отключить", stop).build())
        }
        return builder.build()
    }

    /** Shown when the VPN stops on its own (not by the user). */
    fun showError(context: Context, text: String) {
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VPN отключён")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ID_ALERT, n)
        } catch (_: SecurityException) {
            // Notification permission denied: nothing else to do.
        }
    }

    fun clearError(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_ALERT)
    }
}
