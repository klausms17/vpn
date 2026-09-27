package com.klausms.vpn.service

import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.RemoteCallbackList
import androidx.core.app.ServiceCompat
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.Clock
import com.klausms.vpn.widget.VpnWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notices next to "Подключено", as the checks and the search for
 * another server put them up; a fake in tests. The suspending calls may
 * come from any coroutine, the others from any thread. [e] in each call is
 * the tunnel's generation the notice is about (see [Epoch]).
 */
internal interface NoticeSink {
    /** The hint shown whenever no other notice is (strict Private DNS), or null. */
    val base: String?

    /**
     * Shows [notice] (null clears it) while connected, unless the tunnel
     * changed since [e]; with [replacing], only in place of one of those
     * (null in it: also when no notice is shown).
     */
    suspend fun show(notice: String?, e: Long, replacing: Set<String?>? = null)

    /** Traffic gets through (again): "not answering" is no longer true. A switch notice stays. */
    suspend fun clearFailure(e: Long)

    /**
     * Takes the "switched to another server" notice away once it is
     * [minAgeMs] old; otherwise it would stay for as long as the tunnel runs.
     */
    fun clearSwitch(e: Long, minAgeMs: Long)
}

/**
 * Shows what the tunnel does: the status in [VpnStatusHolder], sent to the
 * app (if open) and the widget, the foreground notification, and the
 * notices next to "Подключено". Owns the app's callbacks and the
 * [NoticeBoard]. [lockdownConflict]: whether the running tunnel leaves apps
 * without network under "Block connections without VPN".
 *
 * Threading: [setStatus], [profilesChanged], [connected], [register],
 * [unregister], [isAppVisible], [markDisconnected] and [clearSwitch] may be
 * called from any thread; [show], [clearFailure] and [publishConnected]
 * from any coroutine. Whatever they send goes out on the main thread: the
 * callback broadcasts through [scope], the notification and the notice's
 * compare-and-set through a switch to Main. [enterForeground],
 * [enterForegroundUnlessUp], [onPrivateDnsChanged], [clearBase] and [kill]
 * are for the main thread only.
 */
internal class StatusPublisher(
    private val service: Service,
    private val scope: CoroutineScope,
    private val epoch: Epoch,
    private val clock: Clock,
    private val lockdownConflict: () -> Boolean,
) : NoticeSink {
    private val callbacks = RemoteCallbackList<IVpnCallback>()
    private val notices = NoticeBoard()

    override val base: String? get() = notices.base

    /** The app registered [callback]: it gets the status at once, and every change. */
    fun register(callback: IVpnCallback) {
        callbacks.register(callback)
        scope.launch { sendTo(callback) { it.sendStatus(VpnStatusHolder.status.value) } }
    }

    fun unregister(callback: IVpnCallback) {
        callbacks.unregister(callback)
    }

    fun isAppVisible(): Boolean = callbacks.registeredCallbackCount > 0

    fun setStatus(status: VpnStatus) {
        VpnStatusHolder.set(status)
        publishStatus()
    }

    /**
     * A start brought [profile] up, [restarting] a tunnel that showed
     * [before]: "connected", with [notice] (the "switched to another server"
     * one) or else the base hint.
     */
    fun connected(profile: StoredProfile, notice: String?, before: VpnStatus, restarting: Boolean) {
        // The session timer goes on when only the settings changed.
        val since = before.connectedSince.takeIf { restarting && it > 0 && before.profileId == profile.id }
            ?: clock.wall()
        notices.switched(notice, clock.elapsed())
        setStatus(VpnStatus(VpnState.CONNECTED, profile.id, profile.name, message = notice ?: notices.base, connectedSince = since))
        Notifications.clearError(service)
    }

    /**
     * The tunnel is gone: shown as off unless it already shows as off or
     * failed, so the widget, tile or app never show a tunnel that is gone.
     */
    fun markDisconnected() {
        val state = VpnStatusHolder.status.value.state
        if (state != VpnState.DISCONNECTED && state != VpnState.ERROR) setStatus(VpnStatus(VpnState.DISCONNECTED))
    }

    /** The saved servers or the selection changed here: the app (if open) and the widget reload them. */
    fun profilesChanged() {
        VpnWidget.update(service)
        scope.launch { broadcast { it.onProfilesChanged() } }
    }

    /** The service goes away: no app hears from it again. */
    fun kill() {
        callbacks.kill()
    }

    // ------------------------------------------------------------ notification

    fun enterForeground(title: String, text: String?) {
        val n = Notifications.status(service, title, text, withDisconnect = true, networkSettings = text == Failover.NOTICE_PRIVATE_DNS)
        try {
            ServiceCompat.startForeground(
                service, Notifications.ID_STATUS, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Can only happen for a system restart in the background; the
            // tunnel still works, it just has no notification.
            AppLog.w("startForeground refused", e)
        }
    }

    /** Foreground at once, as a start command requires: "connected" while the tunnel is up, else [title]. */
    fun enterForegroundUnlessUp(title: String) {
        val s = VpnStatusHolder.status.value
        if (s.state == VpnState.CONNECTED) enterForeground("Подключено", connectedText(s)) else enterForeground(title, null)
    }

    suspend fun publishConnected() {
        val s = VpnStatusHolder.status.value
        if (s.state != VpnState.CONNECTED) return
        withContext(Dispatchers.Main) { enterForeground("Подключено", connectedText(s)) }
    }

    private fun connectedText(s: VpnStatus): String? = when {
        lockdownConflict() ->
            "Включено «Блокировать соединения без VPN»: приложения без VPN (банки, Госуслуги) останутся без интернета"
        // A notice such as "switched to another server".
        !s.message.isNullOrBlank() -> s.message
        else -> s.profileName
    }

    // ----------------------------------------------------------------- notices

    // On the main thread with a compare-and-set, so it never overwrites a newer status.
    override suspend fun show(notice: String?, e: Long, replacing: Set<String?>?) {
        try {
            withContext(Dispatchers.Main) {
                if (!epoch.isCurrent(e)) return@withContext
                val s = VpnStatusHolder.status.value
                if (s.state != VpnState.CONNECTED || !NoticeBoard.shouldReplace(s.message, notice, replacing)) return@withContext
                if (!VpnStatusHolder.compareAndSet(s, s.copy(message = notice))) return@withContext
                publishStatus()
                publishConnected()
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.w("notice update failed", ex)
        }
    }

    override suspend fun clearFailure(e: Long) = show(notices.base, e, replacing = Failover.FAILURE_NOTICES)

    override fun clearSwitch(e: Long, minAgeMs: Long) {
        val notice = notices.switchNoticeToClear(VpnStatusHolder.status.value.message, clock.elapsed(), minAgeMs) ?: return
        scope.launch { show(notices.base, e, replacing = setOf(notice)) }
    }

    /**
     * A strict «Частный DNS» (a host name) also takes over the DNS of VPN
     * apps on Android 10+: the phone's lookups then go around the core's
     * DNS rules, and fail with the server. Only the user can change it, so
     * say so. The host may name a personal account: never logged.
     */
    fun onPrivateDnsChanged(host: String?) {
        val hint = if (host.isNullOrBlank()) null else Failover.NOTICE_PRIVATE_DNS
        val old = notices.base
        if (hint == old) return
        if (hint != null) AppLog.w("strict private DNS is on: lookups bypass the tunnel's DNS rules")
        notices.base = hint
        val e = epoch.current
        scope.launch { show(hint, e, replacing = setOf(null, old)) }
    }

    /** The tunnel left the foreground: the hint is looked up again at the next start. */
    fun clearBase() {
        notices.base = null
    }

    // ------------------------------------------------------------------ status

    /** Shows the status in the widget and the app, if open. */
    private fun publishStatus() {
        VpnWidget.update(service)
        scope.launch {
            // The newest status, read when it is sent: a send posted from the
            // worker must never arrive after a newer one sent on the main thread.
            val status = VpnStatusHolder.status.value
            broadcast { it.sendStatus(status) }
        }
    }

    /** Calls [send] for every registered app. On the main thread. */
    private fun broadcast(send: (IVpnCallback) -> Unit) {
        val n = callbacks.beginBroadcast()
        try {
            for (i in 0 until n) sendTo(callbacks.getBroadcastItem(i), send)
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private inline fun sendTo(callback: IVpnCallback, send: (IVpnCallback) -> Unit) {
        try {
            send(callback)
        } catch (_: Exception) {
            // Dead callbacks are removed by RemoteCallbackList.
        }
    }

    private fun IVpnCallback.sendStatus(s: VpnStatus) {
        onStatus(s.state.code, s.profileId, s.profileName, s.message, s.connectedSince)
    }
}
