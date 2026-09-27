package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Checks that traffic gets through the running tunnel, and hands a server
 * that passes none to [failover]. Nothing runs on a timer while the screen
 * is off: checks follow events (a start, another network, Android's view
 * of the same network, unlocking, the app shown or asked to connect while
 * up, the screen staying on), each kind at most so often. A check that
 * passes also takes away the failure notice and, once old, the switch
 * notice, downloads an owed subscription and may take the tunnel back to
 * the user's server.
 *
 * Owns when checks last started and passed, the download checks per
 * network and the last in-place restart of a stuck core; the one check
 * that may run at a time belongs to [epoch]. Threading: the event calls
 * may come from any thread (the network and screen events on the main
 * thread, [onAppShown] on a binder thread, [schedule] and
 * [onConnectWhileUp] on the engine's worker) and only launch. The check
 * runs in [scope] on [io], never on the worker or the main thread: it
 * takes seconds, and "off" must not wait for it.
 */
internal class HealthMonitor(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val epoch: Epoch,
    private val tunnel: TunnelControl,
    private val status: () -> VpnStatus,
    private val netInfo: NetworkInfo,
    private val notices: NoticeSink,
    private val failover: FailoverSearch,
    private val switcher: ServerSwitcher,
    private val refresher: SubscriptionRefresher,
    private val coreLog: File,
) {
    // Elapsed time of the last check started, and of the last one that got through.
    @Volatile
    private var lastVerifyAt = 0L

    @Volatile
    private var lastVerifiedOkAt = 0L

    // Elapsed time of the last check started because Android's view of the network changed.
    @Volatile
    private var lastLinkCheckAt = 0L

    // Elapsed time of the last core restart for a server that answered again.
    @Volatile
    private var lastCoreRestartAt = 0L

    // Per network: when a download check there last showed no stall
    // (elapsed time). Per network, so a phone going back and forth between
    // Wi-Fi and mobile data does not download on every change: mobile data
    // keeps its network across Wi-Fi gaps. Guarded by itself.
    private val bulkFine = HashMap<NetId?, Long>()

    /**
     * Checks in the background that traffic gets through the tunnel, and
     * looks for another server if not; nothing while no tunnel runs. One
     * check at a time; [delayMs] lets a new network settle.
     */
    fun schedule(reason: Reason, delayMs: Long = 0) {
        if (tunnel.session == null) return
        epoch.startCheck { e ->
            scope.launch(io) {
                try {
                    if (delayMs > 0) delay(delayMs)
                    verify(e, reason)
                } catch (ex: Exception) {
                    // Anything uncaught here would take the tunnel process down.
                    if (ex is CancellationException) throw ex
                    AppLog.w("connection check failed", ex)
                }
            }
        }
    }

    /**
     * Unlocking is when the phone is about to be used: a cheap moment to
     * find out that the server stopped answering while it was locked.
     * While the screen stays on, a server blocked mid-session is found by
     * [onScreenTick].
     */
    fun onUnlock() {
        if (clock.elapsed() - lastVerifyAt >= UNLOCK_CHECK_MS) schedule(Reason.UNLOCK)
    }

    /** The screen has been on for [SCREEN_CHECK_MS] more. */
    fun onScreenTick() {
        if (clock.elapsed() - lastVerifyAt >= SCREEN_CHECK_MS) schedule(Reason.SCREEN)
    }

    /** The app came on screen: show the truth about the connection. */
    fun onAppShown() {
        if (clock.elapsed() - lastVerifyAt >= APP_CHECK_MS) schedule(Reason.APP)
    }

    /** "Connect" while connected: maybe it does not work. */
    fun onConnectWhileUp() {
        if (clock.elapsed() - lastVerifiedOkAt >= APP_CHECK_MS) schedule(Reason.APP)
    }

    /**
     * Android no longer sees internet on the same network ([lost]: often the
     * first sign of a blocked server or of a mobile whitelist switched on),
     * or it came back (a Wi-Fi login, the end of a pause): check again, at
     * most once a minute. Coming back only matters when the last check failed.
     */
    fun onReachability(lost: Boolean) {
        if (!lost && lastVerifiedOkAt >= lastVerifyAt) return
        val now = clock.elapsed()
        if (now - lastLinkCheckAt < LINK_CHECK_MS) return
        lastLinkCheckAt = now
        schedule(Reason.LINK, NETWORK_SETTLE_MS)
    }

    /** The network under the tunnel is now [net] (null: none), after [previous]. On the main thread. */
    fun onNetworkChanged(net: NetId?, previous: NetId?) {
        // Whatever a check in progress measured belongs to the old network.
        epoch.advance()
        val e = epoch.current
        if (net == null) {
            // Nothing to search with: do not claim to be searching.
            scope.launch { notices.show(notices.base, e, replacing = setOf(Failover.NOTICE_SEARCHING)) }
            return
        }
        // Another network: why the server was switched no longer applies.
        if (net != previous) notices.clearSwitch(e, minAgeMs = 0)
        if (resetsConnections(net, previous, tunnel.session, clock.elapsed())) {
            // Connections opened over the old network are dead but would hang
            // until timeouts. Restarting the core resets them at once, so apps
            // (messengers, video) reconnect immediately over the new network.
            tunnel.resetInPlace("network changed, resetting connections", NETWORK_SETTLE_MS)
        } else {
            schedule(Reason.NETWORK, NETWORK_SETTLE_MS)
        }
    }

    private suspend fun verify(e: Long, reason: Reason) {
        // The log grows for as long as the tunnel runs; checks come often enough to keep it small.
        XrayLog.trim(coreLog)
        val current = tunnel.session ?: return
        val core = current.core
        val running = current.profile
        if (!epoch.isCurrent(e) || status().state != VpnState.CONNECTED) return
        lastVerifyAt = clock.elapsed()
        val ok = TrafficCheck.passes(core, TrafficCheck.VERIFY_TIMEOUT_MS, TrafficCheck.CONFIRM_TIMEOUT_MS)
        if (!epoch.isCurrent(e)) return
        if (!ok) {
            AppLog.w("no traffic through the server (${reason.name.lowercase()})")
            search(e, core, running, stalled = false)
            return
        }
        // It answers, but some operators freeze a foreign server's
        // connections after the first ~16 KB: pages and video then hang
        // while short answers still get through.
        val network = netInfo.active()
        if (bulkDue(reason, network) && stalls(core, network)) {
            if (!epoch.isCurrent(e)) return
            AppLog.w("downloads through the server stall (${reason.name.lowercase()})")
            search(e, core, running, stalled = true)
            return
        }
        if (!epoch.isCurrent(e)) return
        lastVerifiedOkAt = clock.elapsed()
        notices.clearFailure(e)
        notices.clearSwitch(e, minAgeMs = SWITCH_NOTICE_MS)
        refresher.payOwed(core) { switcher.restartOnSaved(running) }
        // Moments when connections start over anyway.
        if (reason == Reason.NETWORK || reason == Reason.UNLOCK) switcher.returnHomeIfItAnswers(e, core, running)
    }

    /** Looks for a server to replace [failed] and does what the search came to. */
    private suspend fun search(e: Long, core: CoreHandle, failed: StoredProfile, stalled: Boolean) {
        when (val outcome = failover.search(e, core, failed, stalled) { recoveredInPlace(e, core) }) {
            SearchOutcome.Done -> Unit
            is SearchOutcome.SwitchTo -> tunnel.submit { switcher.switchTo(e, failed, outcome.winnerId, report = outcome.report) }
            SearchOutcome.RestartOnSaved -> switcher.restartOnSaved(failed)
        }
    }

    /**
     * The failed server answered in a new connection. If it now answers
     * through the running [core] too, the connection was lost for a moment:
     * stay. If not, the running core is stuck: restart it on the same
     * server, at most every 10 minutes. False: treat it as a real failure.
     * Google first, as the probe that answered, then Cloudflare.
     */
    private suspend fun recoveredInPlace(e: Long, core: CoreHandle): Boolean {
        if (TrafficCheck.passes(core, TrafficCheck.CONFIRM_TIMEOUT_MS, TrafficCheck.CONFIRM_TIMEOUT_MS)) {
            if (!epoch.isCurrent(e)) return true
            AppLog.i("the server answers again: the connection was lost for a moment")
            lastVerifiedOkAt = clock.elapsed()
            notices.clearFailure(e)
            return true
        }
        val now = clock.elapsed()
        if (now - lastCoreRestartAt < CORE_RESTART_GAP_MS) return false
        // Not queued: the tunnel changed meanwhile, and the gap stays for a real stuck core.
        if (tunnel.resetInPlace("the server answers, but not through the running core: restarting it", expectedEpoch = e)) {
            lastCoreRestartAt = now
        }
        return true
    }

    /**
     * Whether the download check is due: on connect, otherwise when none
     * showed downloads working on [network] in the last half hour (a new
     * network, or a stall seen before).
     */
    private fun bulkDue(reason: Reason, network: NetId?): Boolean {
        if (reason == Reason.START) return true
        val now = clock.elapsed()
        return synchronized(bulkFine) {
            bulkFine.entries.removeIf { now - it.value >= BULK_CHECK_MS || it.value > now }
            network !in bulkFine
        }
    }

    /**
     * Whether downloads through [core] stop partway: twice in a row, a 64
     * KB file stops coming after it began. Any other failure (the site
     * refuses or cannot be reached from the server) proves nothing and
     * counts as fine. No app name is sent. Blocking.
     */
    private fun stalls(core: CoreHandle, network: NetId?): Boolean {
        repeat(2) {
            val stalled = try {
                core.fetchThroughTunnel(BULK_URL, "", BULK_HEADERS, BULK_TIMEOUT_MS)
                false
            } catch (ex: Exception) {
                Failover.isStall(ex.message)
            }
            if (!stalled) {
                // Also after a refusal: trying again at every check would
                // only cost time and data.
                synchronized(bulkFine) { bulkFine[network] = clock.elapsed() }
                return false
            }
        }
        synchronized(bulkFine) { bulkFine.remove(network) }
        return true
    }

    companion object {
        /** While the screen is on, a check runs when none ran for this long; the watcher ticks this often. */
        const val SCREEN_CHECK_MS = 5 * 60_000L

        private const val NETWORK_SETTLE_MS = 1_500L
        private const val MIN_UPTIME_FOR_RESET_MS = 3_000L

        /** Unlocking the phone checks at most this often. */
        private const val UNLOCK_CHECK_MS = 10 * 60_000L

        /** Opening the app checks at most this often. */
        private const val APP_CHECK_MS = 2 * 60_000L

        /** Android's view of the network changing checks at most this often. */
        private const val LINK_CHECK_MS = 60_000L

        /** A server that answers again after a failed check gets its core restarted at most this often. */
        private const val CORE_RESTART_GAP_MS = 10 * 60_000L

        /** The "switched to another server" notice goes after a check this long after the switch. */
        private const val SWITCH_NOTICE_MS = 10 * 60_000L

        /**
         * The download check: some operators freeze connections to foreign
         * servers after the first ~16 KB, which a 204 answer never reaches.
         * 64 KB, not compressed; a stall counts, a refusal does not.
         * Run on connect, and otherwise at most this often per network (a
         * new network gets one at its first check).
         */
        private const val BULK_URL = "https://speed.cloudflare.com/__down?bytes=65536"
        private const val BULK_HEADERS = """{"Accept-Encoding":"identity"}"""
        private const val BULK_TIMEOUT_MS = 12_000
        private const val BULK_CHECK_MS = 30 * 60_000L

        /**
         * Whether moving from [previous] to [net] resets the connections of
         * the running [session] at [now], rather than only checking them.
         * Not for the first network since the tunnel came up (Always-on at
         * boot), the same one back after a gap, or right after connecting;
         * nothing to reset while no tunnel runs.
         */
        fun resetsConnections(net: NetId, previous: NetId?, session: TunnelSession?, now: Long): Boolean =
            previous != null && net != previous && session != null && now - session.connectedAt >= MIN_UPTIME_FOR_RESET_MS
    }
}
