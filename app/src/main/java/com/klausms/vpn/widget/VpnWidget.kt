package com.klausms.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.klausms.vpn.R
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.data.PingGrade
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Stores
import com.klausms.vpn.data.pingGrade
import com.klausms.vpn.service.LiveCore
import com.klausms.vpn.service.RuntimeState
import com.klausms.vpn.service.TrafficCheck
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatusHolder
import com.klausms.vpn.service.XrayVpnService
import com.klausms.vpn.ui.MainActivity
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Home screen widget: status, one-tap on/off and a ping check.
 *
 * Plain RemoteViews: the VPN service pushes a new picture when its state
 * changes, and the system asks for a redraw about every 30 minutes
 * (updatePeriodMillis), in case the VPN process was killed without redrawing.
 * The connection timer is a Chronometer that the launcher ticks by itself,
 * only while it is on screen.
 *
 * Everything here runs in the ":vpn" process, which owns the VPN state.
 */
object VpnWidget {
    private const val PREFS = "widget"
    private const val KEY_PING_PROFILE = "ping_profile"
    private const val KEY_PING_MS = "ping_ms"

    private const val PING_TIMEOUT_MS = 8_000

    /** A second tap this soon after a result is taken as a double tap. */
    private const val DOUBLE_TAP_MS = 1_500L

    // The look of the Happ widget the owner asked for: a deep green for
    // "connected", a bright one for the power sign, yellow for a slow ping.
    private const val GREEN = 0xFF00B800.toInt()
    private const val NEON = 0xFF19FF3C.toInt()
    private const val YELLOW = 0xFFFFD600.toInt()
    private const val AMBER = 0xFFFF9F0A.toInt()
    private const val RED = 0xFFFF453A.toInt()
    private const val GREY = 0xFF8E8E93.toInt()
    private const val BAR_OFF = 0xFFC0C0C0.toInt()
    private const val TEXT = 0xFFFFFFFF.toInt()
    private const val TEXT_SECONDARY = 0x99EBEBF5.toInt()
    private const val MAP_ON = 0xFF3CDC64.toInt()
    private const val MAP_OFF = 0xFFFFFFFF.toInt()

    private const val RC_OPEN = 100
    private const val RC_CONNECT_UI = 101
    private const val RC_CONNECT = 102
    private const val RC_DISCONNECT = 103
    private const val RC_PING = 104
    private const val RC_REFRESH = 105

    // Pictures are built one at a time and always from the current state, so
    // the last one pushed to the launcher is never an outdated one.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val renderer = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Redraws all widgets. Cheap when none is placed. */
    fun update(context: Context, pending: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext
        scope.launch(renderer) {
            try {
                render(app)
            } catch (e: Exception) {
                AppLog.w("widget update failed", e)
            } finally {
                pending?.finish()
            }
        }
    }

    /** From the app UI process: asks the VPN process to redraw. */
    fun requestRefresh(context: Context) {
        try {
            if (widgetIds(context).isEmpty()) return
            context.sendBroadcast(refreshIntent(context))
        } catch (e: Exception) {
            AppLog.w("widget refresh request failed", e)
        }
    }

    /** Measures the ping of the connected (or selected) server. */
    fun ping(context: Context, pending: BroadcastReceiver.PendingResult?) {
        val app = context.applicationContext
        scope.launch {
            var released = false
            val release = {
                if (!released) {
                    released = true
                    pending?.finish()
                }
            }
            try {
                measure(app, release)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w("widget ping failed", e)
            } finally {
                try {
                    withContext(renderer) { render(app) }
                } catch (e: Exception) {
                    AppLog.w("widget update failed", e)
                }
                release()
            }
        }
    }

    internal fun clearState(context: Context) {
        prefs(context).edit { clear() }
    }

    // ----------------------------------------------------------------- ping

    // In memory only, touched on [renderer]: a test cut short by process
    // death must not leave the widget showing "…".
    @Volatile
    private var testingProfile: String? = null

    @Volatile
    private var lastResultAt = 0L

    private suspend fun measure(context: Context, release: () -> Unit) {
        val status = VpnStatusHolder.status.value
        // No test while the tunnel is going up or down.
        if (status.state == VpnState.CONNECTING || status.state == VpnState.DISCONNECTING) return
        // While a tunnel core runs, always measure through it (the path the
        // apps' traffic takes). A second, temporary core in this process
        // would take over Xray's process-wide logger from the tunnel.
        val live = LiveCore.current
        val profile = status.shownServer(Stores.profiles(context).read()) ?: return
        val prefs = prefs(context)

        val started = withContext(renderer) {
            val recent = SystemClock.elapsedRealtime() - lastResultAt < DOUBLE_TAP_MS &&
                prefs.getString(KEY_PING_PROFILE, null) == profile.id
            if (testingProfile == profile.id || recent) return@withContext false
            testingProfile = profile.id
            prefs.edit(commit = true) {
                putString(KEY_PING_PROFILE, profile.id)
                remove(KEY_PING_MS)
            }
            render(context)
            true
        }
        if (!started) return
        // The running VPN service keeps this process alive: let the next tap
        // (e.g. the power button) through instead of queueing it behind the test.
        if (live != null) release()

        val ms = try {
            // Through the tunnel: Cloudflare when Google does not answer, as
            // the service's own check, so a working server never shows "no answer".
            live?.let { TrafficCheck.delay(it, PING_TIMEOUT_MS) }
                ?: XrayCore.measureDelay(profile.outbounds, PING_TIMEOUT_MS)
        } catch (e: Exception) {
            AppLog.i("widget ping: ${e.message}")
            -1L
        }

        withContext(renderer) {
            if (testingProfile == profile.id) testingProfile = null
            // Another server may have been picked or switched to meanwhile: a
            // result goes only to the server shown, and one measured through
            // the running core only while that server still runs.
            val stillRunning = live == null || VpnStatusHolder.status.value.profileId == profile.id
            if (stillRunning && prefs.getString(KEY_PING_PROFILE, null) == profile.id) {
                prefs.edit(commit = true) { putLong(KEY_PING_MS, ms) }
                lastResultAt = SystemClock.elapsedRealtime()
            }
        }
    }

    // --------------------------------------------------------------- render

    private sealed interface Ping {
        data object Unknown : Ping
        data object Testing : Ping
        data object Failed : Ping
        data class Ok(val ms: Long) : Ping
    }

    private class Model(
        val state: VpnState,
        /** Wall clock millis when the tunnel came up. */
        val connectedSince: Long,
        val profile: StoredProfile?,
        val ping: Ping,
        /** False until a tunnel has come up once (the user allowed the VPN). */
        val vpnAllowed: Boolean,
        /** Shown while connected, e.g. that the server was switched. */
        val notice: String? = null,
        /** The status's server name, for a running server that a refresh removed from the list. */
        runningName: String? = null,
    ) {
        val serverName: String? = profile?.let(::nameOf) ?: runningName
    }

    /**
     * Screenshot tests: every size of the widget for one state, built exactly
     * as for the home screen. [pingMs]: null not measured, -1 no answer,
     * -2 being measured. [notice]: the connected state's message.
     */
    @VisibleForTesting
    internal fun previews(
        context: Context,
        state: VpnState,
        connectedSince: Long,
        profile: StoredProfile?,
        pingMs: Long?,
        notice: String? = null,
    ): Map<String, RemoteViews> {
        val ping = when {
            pingMs == null -> Ping.Unknown
            pingMs == -1L -> Ping.Failed
            pingMs < 0 -> Ping.Testing
            else -> Ping.Ok(pingMs)
        }
        val m = Model(state, connectedSince, profile, ping, vpnAllowed = true, notice = notice)
        return Variant.entries.associate { it.name to build(context, m, it) }
    }

    private fun loadModel(context: Context): Model {
        val status = VpnStatusHolder.status.value
        val profile = status.shownServer(Stores.profiles(context).read())
        val prefs = prefs(context)
        val ping = when {
            profile == null -> Ping.Unknown
            testingProfile == profile.id -> Ping.Testing
            prefs.getString(KEY_PING_PROFILE, null) != profile.id -> Ping.Unknown
            !prefs.contains(KEY_PING_MS) -> Ping.Unknown
            else -> prefs.getLong(KEY_PING_MS, -1L).let { if (it < 0) Ping.Failed else Ping.Ok(it) }
        }
        val notice = status.message?.takeIf { status.state == VpnState.CONNECTED && it.isNotBlank() }
        // Not VpnService.prepare(): it would take the VPN over from another app.
        return Model(status.state, status.connectedSince, profile, ping, RuntimeState.vpnConsented(context), notice, status.profileName)
    }

    private fun render(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = widgetIds(context)
        if (ids.isEmpty()) return
        val model = loadModel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.updateAppWidget(ids, responsive(context, model))
        } else {
            for (id in ids) manager.updateAppWidget(id, sized(context, model, manager.getAppWidgetOptions(id)))
        }
    }

    private fun widgetIds(context: Context): IntArray {
        val manager = AppWidgetManager.getInstance(context) ?: return IntArray(0)
        return manager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java)) ?: IntArray(0)
    }

    /** Layouts by the smallest size (dp) each one needs. */
    private enum class Variant(
        @param:LayoutRes val layout: Int,
        val minWidth: Float,
        val minHeight: Float,
        /** Small layouts show the timer in place of the status text. */
        val timerInStatus: Boolean,
        val showPing: Boolean,
    ) {
        MINI(R.layout.widget_vpn_row, 110f, 40f, timerInStatus = true, showPing = false),
        ROW(R.layout.widget_vpn_row, 230f, 40f, timerInStatus = false, showPing = true),
        FULL(R.layout.widget_vpn, 260f, 124f, timerInStatus = false, showPing = true),
        ;

        /**
         * FULL grows with the system font size: 16 dp of padding, the 46 dp
         * strip, and the text column (about 61 dp at scale 1) or the 64 dp
         * power button, whichever is taller.
         */
        fun minHeight(scale: Float): Float =
            if (this == FULL) 62f + maxOf(61f * scale, 64f) else minHeight
    }

    /**
     * Android 12+: the launcher itself picks the layout that fits as the
     * widget is resized. Each size is the smallest one its layout needs.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun responsive(context: Context, m: Model): RemoteViews {
        val scale = fontScale(context)
        return RemoteViews(Variant.entries.associate { SizeF(it.minWidth, it.minHeight(scale)) to build(context, m, it) })
    }

    /** Older launchers: one layout for portrait and one for landscape. */
    private fun sized(context: Context, m: Model, options: Bundle?): RemoteViews {
        val minW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val maxW = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) ?: 0
        val minH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
        val maxH = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (minW <= 0 || minH <= 0) return build(context, m, Variant.FULL)
        val scale = fontScale(context)
        val portrait = variantFor(minW.toFloat(), maxH.coerceAtLeast(minH).toFloat(), scale)
        val landscape = variantFor(maxW.coerceAtLeast(minW).toFloat(), minH.toFloat(), scale)
        if (portrait == landscape) return build(context, m, portrait)
        return RemoteViews(build(context, m, landscape), build(context, m, portrait))
    }

    /** The largest layout that fits, as Android 12+ picks it. */
    private fun variantFor(width: Float, height: Float, scale: Float): Variant =
        Variant.entries
            .filter { it.minWidth <= width + 1 && it.minHeight(scale) <= height + 1 }
            .maxByOrNull { it.minWidth * it.minHeight(scale) }
            ?: Variant.MINI

    /** The launcher uses the same system font scale as this process. */
    private fun fontScale(context: Context): Float =
        context.resources.configuration.fontScale.coerceIn(0.85f, 2f)

    private fun build(context: Context, m: Model, variant: Variant): RemoteViews {
        val v = RemoteViews(context.packageName, variant.layout)
        val connected = m.state == VpnState.CONNECTED
        val busy = m.state == VpnState.CONNECTING || m.state == VpnState.DISCONNECTING
        val notice = m.notice?.takeIf { connected }
        // The row has no protocol line: its notice takes the status text's
        // place next to the timer.
        val noticeInStatus = notice != null && variant == Variant.ROW

        // Status
        val (statusText, statusColor) = when (m.state) {
            VpnState.CONNECTED -> "Подключен" to GREEN
            VpnState.CONNECTING -> "Подключение…" to AMBER
            VpnState.DISCONNECTING -> "Отключение…" to AMBER
            VpnState.ERROR -> "Ошибка" to RED
            VpnState.DISCONNECTED -> "Отключен" to GREY
        }
        v.setInt(R.id.status_dot, "setColorFilter", statusColor)
        v.setTextViewText(R.id.status_text, if (noticeInStatus) notice else statusText)
        v.setTextColor(
            R.id.status_text,
            when {
                noticeInStatus -> AMBER
                m.state == VpnState.DISCONNECTED -> TEXT_SECONDARY
                else -> statusColor
            },
        )
        v.setInt(R.id.panel, "setBackgroundResource", if (connected) R.drawable.widget_panel_on else R.drawable.widget_panel)
        // The map: cropped where the launcher clips it to the rounded panel
        // (Android 12+), stretched with its own rounded corners before.
        val clipped = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        v.setViewVisibility(R.id.map, if (clipped) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.map_fit, if (clipped) View.GONE else View.VISIBLE)
        for (id in intArrayOf(R.id.map, R.id.map_fit)) {
            v.setInt(id, "setColorFilter", if (connected) MAP_ON else MAP_OFF)
            v.setInt(id, "setImageAlpha", if (connected) 255 else 0x66)
        }

        // Connection timer: counted by the launcher, not by this app. Every
        // visibility is set explicitly: a launcher may re-apply this picture
        // onto views inflated for another size.
        val timing = connected && m.connectedSince > 0
        if (timing) {
            val sinceMs = (System.currentTimeMillis() - m.connectedSince).coerceAtLeast(0)
            v.setChronometer(R.id.timer, SystemClock.elapsedRealtime() - sinceMs, null, true)
        } else {
            v.setChronometer(R.id.timer, SystemClock.elapsedRealtime(), null, false)
        }
        v.setViewVisibility(R.id.timer, if (timing) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.timer_idle, if (timing) View.GONE else View.VISIBLE)
        v.setViewVisibility(R.id.status_text, if (timing && variant.timerInStatus) View.GONE else View.VISIBLE)

        // Server
        val profile = m.profile
        val name = m.serverName
        v.setTextViewText(R.id.server_name, name ?: "Нет сервера")
        v.setTextViewText(
            R.id.protocol,
            when {
                name == null -> "Добавьте ключ в приложении"
                notice != null -> notice
                else -> profile?.let(::protocolLine).orEmpty()
            },
        )
        v.setTextColor(R.id.protocol, if (notice != null && name != null) AMBER else TEXT_SECONDARY)

        // Power button
        v.setImageViewResource(
            R.id.power_bg,
            when {
                connected -> R.drawable.widget_power_on
                busy -> R.drawable.widget_power_busy
                else -> R.drawable.widget_power_off
            },
        )
        v.setInt(R.id.power_icon, "setColorFilter", if (connected) NEON else if (busy) AMBER else TEXT)
        v.setViewVisibility(R.id.power_glow, if (connected) View.VISIBLE else View.GONE)
        val on = connected || m.state == VpnState.CONNECTING
        v.setContentDescription(
            R.id.power,
            context.getString(if (on) R.string.widget_power_off else R.string.widget_power_on),
        )
        v.setOnClickPendingIntent(R.id.power, powerIntent(context, m))

        // Ping
        val showPing = variant.showPing && profile != null
        v.setViewVisibility(R.id.ping_button, if (showPing) View.VISIBLE else View.GONE)
        renderPing(v, m.ping, compact = variant != Variant.FULL)
        val action = context.getString(R.string.widget_ping_action)
        val spoken = when (val ping = m.ping) {
            is Ping.Ok -> "${ping.ms} мс"
            Ping.Failed -> "нет связи"
            Ping.Testing -> "проверка"
            Ping.Unknown -> null
        }
        // The compact pill carries the result inside it; say it too.
        v.setContentDescription(
            R.id.ping_button,
            if (variant == Variant.FULL || spoken == null) action else "$action, $spoken",
        )
        if (showPing) v.setOnClickPendingIntent(R.id.ping_button, broadcast(context, VpnWidgetActionReceiver.ACTION_PING, RC_PING))

        v.setOnClickPendingIntent(android.R.id.background, openAppIntent(context))
        return v
    }

    private fun renderPing(v: RemoteViews, ping: Ping, compact: Boolean) {
        val (bars, color) = when (ping) {
            is Ping.Ok -> when (pingGrade(ping.ms)) {
                PingGrade.GREAT -> 4 to GREEN
                PingGrade.GOOD -> 3 to GREEN
                PingGrade.FAIR -> 3 to YELLOW
                PingGrade.POOR -> 1 to RED
            }
            Ping.Failed -> 1 to RED
            Ping.Testing, Ping.Unknown -> 0 to GREY
        }
        val barIds = intArrayOf(R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4)
        for ((i, id) in barIds.withIndex()) {
            // Both are always set: RemoteViews may be re-applied onto old views.
            val on = i < bars
            v.setInt(id, "setColorFilter", if (on) color else BAR_OFF)
            v.setInt(id, "setImageAlpha", if (on || bars > 0) 255 else 0x80)
        }

        val text = when (ping) {
            is Ping.Ok -> String.format(Locale.ROOT, "%d мс", ping.ms)
            Ping.Failed -> if (compact) "—" else "нет связи"
            Ping.Testing -> "…"
            Ping.Unknown -> if (compact) "Пинг" else "—"
        }
        v.setTextViewText(R.id.ping_value, text)
        v.setTextColor(
            R.id.ping_value,
            when {
                ping == Ping.Failed -> RED
                compact -> TEXT
                else -> TEXT_SECONDARY
            },
        )
    }

    private fun nameOf(p: StoredProfile): String = p.name.ifBlank { p.address }

    /** "VLESS | TCP | Reality" */
    private fun protocolLine(p: StoredProfile): String {
        val parts = mutableListOf<String>()
        parts += when (p.protocol) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "shadowsocks" -> "Shadowsocks"
            "hysteria2", "hysteria" -> "Hysteria2"
            "wireguard" -> "WireGuard"
            else -> p.protocol.uppercase(Locale.ROOT)
        }
        when (p.network) {
            "", "hysteria" -> Unit
            "raw", "tcp" -> parts += "TCP"
            "ws" -> parts += "WS"
            "grpc" -> parts += "gRPC"
            "httpupgrade" -> parts += "HTTPUpgrade"
            "kcp", "mkcp" -> parts += "mKCP"
            else -> parts += p.network.uppercase(Locale.ROOT)
        }
        when (p.security) {
            "reality" -> parts += "Reality"
            "tls" -> parts += "TLS"
        }
        return parts.filter { it.isNotBlank() }.joinToString(" | ")
    }

    // -------------------------------------------------------------- intents

    private fun powerIntent(context: Context, m: Model): PendingIntent = when {
        // Via the receiver: it always turns the VPN off, also when the VPN
        // process died since this picture was drawn (then it only logs that).
        m.state == VpnState.CONNECTED || m.state == VpnState.CONNECTING ->
            broadcast(context, VpnWidgetActionReceiver.ACTION_DISCONNECT, RC_DISCONNECT)
        // Already going down; a tap only redraws.
        m.state == VpnState.DISCONNECTING -> broadcast(context, VpnWidgetActionReceiver.ACTION_REFRESH, RC_REFRESH)
        // No server yet, or the one-time VPN permission dialog is needed:
        // both need the app on screen.
        m.profile == null || !m.vpnAllowed ->
            PendingIntent.getActivity(
                context, RC_CONNECT_UI,
                Intent().setClassName(context, MainActivity.CONNECT_ALIAS)
                    .setAction(MainActivity.ACTION_CONNECT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        else ->
            PendingIntent.getForegroundService(
                context, RC_CONNECT,
                Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_CONNECT),
                PendingIntent.FLAG_IMMUTABLE,
            )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, RC_OPEN,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun refreshIntent(context: Context) =
        Intent(context, VpnWidgetActionReceiver::class.java).setAction(VpnWidgetActionReceiver.ACTION_REFRESH)

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, VpnWidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
