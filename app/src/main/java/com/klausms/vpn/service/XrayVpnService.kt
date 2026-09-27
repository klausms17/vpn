package com.klausms.vpn.service

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.DirectNet
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.XrayCoreHandle
import com.klausms.vpn.core.XrayDirectNet
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.DiskProfiles
import com.klausms.vpn.data.Downloader
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.data.SubscriptionUpdater
import com.klausms.vpn.util.AndroidClock
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The tunnel. Runs in the ":vpn" process.
 *
 * - Xray reads the TUN fd directly (gVisor stack inside the core): no
 *   tun2socks, no local SOCKS/HTTP port that other apps could find.
 * - This app is always excluded from the tunnel, so the core's own
 *   connections to the server can never loop back into it.
 * - All start/stop work is serialized on one thread (see [TunnelEngine]).
 * - Nothing runs periodically while the screen is off, so an idle tunnel
 *   costs no extra battery.
 * - Whether traffic really gets through is checked on events (connect,
 *   network change, Android losing or regaining internet on the network,
 *   unlock, app opened) and every few minutes while the screen is on; a
 *   server that stopped answering is replaced by one that answers (see
 *   [Failover]).
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.klausms.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.klausms.vpn.DISCONNECT"
        const val ACTION_RECONNECT = "com.klausms.vpn.RECONNECT"
        const val ACTION_BIND = "com.klausms.vpn.BIND"

        /** Start a tunnel that should run (after an app update): nobody asked just now, so never ask for consent. */
        const val ACTION_RESUME = "com.klausms.vpn.RESUME"

        /** Who asked to disconnect, for the log. */
        const val EXTRA_SOURCE = "source"

        /** On CONNECT and RECONNECT: the user has just chosen the server by hand. */
        const val EXTRA_PICKED = "picked"

        private const val NETWORK_SETTLE_MS = 1_500L
        private const val MIN_UPTIME_FOR_RESET_MS = 3_000L

        /** Unlocking the phone checks at most this often. */
        private const val UNLOCK_CHECK_MS = 10 * 60_000L

        /** Opening the app checks at most this often. */
        private const val APP_CHECK_MS = 2 * 60_000L

        /** While the screen is on, a check runs when none ran for this long. */
        private const val SCREEN_CHECK_MS = 5 * 60_000L

        /** Android's view of the network changing checks at most this often. */
        private const val LINK_CHECK_MS = 60_000L

        /** After a search found nothing, the next one on that network waits this long. */
        private const val FRUITLESS_RETRY_MS = 2 * 60_000L

        /** A server that answers again after a failed check gets its core restarted at most this often. */
        private const val CORE_RESTART_GAP_MS = 10 * 60_000L

        /** The "switched to another server" notice goes after a check this long after the switch. */
        private const val SWITCH_NOTICE_MS = 10 * 60_000L

        private const val REFRESH_TIMEOUT_MS = 10_000

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

        /** Hosts looked up in the mobile whitelist per search, and how long to wait for them. */
        private const val WHITELIST_HOSTS = 32
        private const val WHITELIST_WAIT_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val clock: Clock = AndroidClock

    private lateinit var runtime: RuntimeStore

    /** The core's log. */
    private lateinit var coreLog: File

    private lateinit var direct: DirectNet

    private lateinit var profiles: ProfilesAccess

    private lateinit var netInfo: SystemNetworkInfo

    private lateinit var publisher: StatusPublisher

    private lateinit var watcher: NetworkWatcher

    private lateinit var engine: TunnelEngine

    private lateinit var memory: FailureMemory

    // The newest command's startId. A job only stops the service if no newer
    // command arrived meanwhile, so a quick "off, on" never loses the "on".
    @Volatile
    private var lastStartId = 0

    /** The newest reconnect; older ones still queued are skipped. */
    @Volatile
    private var lastReconnectId = 0

    // ------------------------------------------------------ failover state

    // The tunnel's generation, and the one traffic check running.
    private val epoch = Epoch()

    // elapsedRealtime of the last check started, and of the last one that got through.
    @Volatile
    private var lastVerifyAt = 0L

    @Volatile
    private var lastVerifiedOkAt = 0L

    // Per network: when a download check there last showed no stall
    // (elapsedRealtime). Per network, so a phone going back and forth between
    // Wi-Fi and mobile data does not download on every change: mobile data
    // keeps its network across Wi-Fi gaps. Guarded by itself.
    private val bulkFine = HashMap<NetId?, Long>()

    // elapsedRealtime of the last check started because Android's view of the network changed.
    @Volatile
    private var lastLinkCheckAt = 0L

    // elapsedRealtime of the last core restart for a server that answered again.
    @Volatile
    private var lastCoreRestartAt = 0L

    // Per network: when a search there last found nothing, and the notice it
    // showed. Another network may reach servers this one could not, and the
    // same network back (Wi-Fi flapping) remembers. Guarded by itself.
    private val fruitless = HashMap<NetId?, Pair<Long, String>>()

    // One probe at a time: a probe's temporary core holds megabytes until it
    // ends, also when its result is no longer wanted.
    private val probeLock = Mutex()

    // Host -> on the Russian mobile whitelist. Kept while the process lives:
    // few hosts, and the answer rarely changes.
    private val whitelistCache = ConcurrentHashMap<String, Boolean>()

    // A reconnect asked for with EXTRA_PICKED; merged reconnects keep it.
    private val pickPending = AtomicBoolean()

    @Volatile
    private var refreshJob: Deferred<Refreshed>? = null

    // A subscription whose direct download failed during a search: it is
    // downloaded through the tunnel once a server works again.
    @Volatile
    private var owedRefresh: String? = null

    private val blockReporter by lazy { BlockReporter(this) }

    // What the watcher hears, on the main thread.
    private val networkEvents = object : NetworkWatcher.Listener {
        override fun onNetworkChanged(net: NetId?, previous: NetId?) = onUnderlyingNetworkChanged(net, previous)

        override fun onReachability(lost: Boolean) = onReachabilityChanged(lost)

        override fun onPrivateDns(host: String?) = publisher.onPrivateDnsChanged(host)

        // Unlocking is when the phone is about to be used: a cheap moment to
        // find out that the server stopped answering while it was locked.
        // While the screen stays on, a server blocked mid-session is found
        // by the checks every few minutes.
        override fun onUnlock() {
            if (clock.elapsed() - lastVerifyAt >= UNLOCK_CHECK_MS) scheduleVerify(Reason.UNLOCK)
        }

        override fun onScreenTick() {
            if (clock.elapsed() - lastVerifyAt >= SCREEN_CHECK_MS) scheduleVerify(Reason.SCREEN)
        }
    }

    // What the engine reports, on its worker.
    private val engineEvents = object : TunnelEngine.Listener {
        override suspend fun onUp(session: TunnelSession, req: StartRequest, before: VpnStatus, restarting: Boolean) {
            val profile = session.profile
            memory.onStarted(profile.id, automatic = req.switch != null, picked = req.picked)
            runtime.setShouldRun(true)
            withContext(Dispatchers.Main) { watcher.start() }
            // Only a server whose core came up becomes the selection.
            val switch = req.switch
            if (switch != null) {
                if (saveSwitch(switch.failedId, profile.id, switch.expectedSelection)) trackAway(switch.failedId, profile.id)
            } else {
                forgetAwayUnless(profile.id, req.picked)
            }
            publisher.connected(profile, switch?.notice, before, restarting)
            publisher.publishConnected()
            scheduleVerify(Reason.START)
        }

        override fun onReset() = scheduleVerify(Reason.NETWORK)
    }

    private val binder = object : IVpnController.Stub() {
        override fun registerCallback(callback: IVpnCallback?) {
            callback ?: return
            publisher.register(callback)
            // The app came on screen: show the truth about the connection.
            if (clock.elapsed() - lastVerifyAt >= APP_CHECK_MS) scheduleVerify(Reason.APP)
            // Should run, but nothing started it: after an update on phones
            // that hold the update broadcast back (MIUI). With the app on
            // screen it may start now; a start already on its way makes
            // this one do nothing.
            if (engine.session == null && VpnStatusHolder.status.value.state == VpnState.DISCONNECTED) {
                VpnCommands.resume(this@XrayVpnService)
            }
        }

        override fun unregisterCallback(callback: IVpnCallback?) {
            callback ?: return
            publisher.unregister(callback)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = PrefsRuntimeStore(this)
        coreLog = XrayLog.file(this)
        direct = XrayDirectNet(this)
        profiles = DiskProfiles(this)
        netInfo = SystemNetworkInfo(this)
        publisher = StatusPublisher(this, scope, epoch, clock) { engine.session?.lockdownConflict == true }
        watcher = NetworkWatcher(
            this, scope, SCREEN_CHECK_MS,
            setUnderlying = { network -> setUnderlyingNetworks(network?.let { arrayOf(it) }) },
            listener = networkEvents,
        )
        engine = TunnelEngine(
            this, scope, epoch, clock, XrayCoreHandle(), publisher, runtime, profiles, coreLog,
            underlying = { watcher.network },
            latestStartId = { lastStartId },
            stopIfLatest = ::stopIfLatest,
            listener = engineEvents,
        )
        memory = FailureMemory(clock)
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND) binder else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                val source = intent.getStringExtra(EXTRA_SOURCE)?.takeIf { it.length <= 20 } ?: "unknown"
                AppLog.i("disconnect asked by $source")
                disconnect(userInitiated = true, startId)
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                // A running tunnel keeps working while new settings apply.
                publisher.enterForegroundUnlessUp("Переподключение…")
                lastReconnectId = startId
                if (intent.getBooleanExtra(EXTRA_PICKED, false)) pickPending.set(true)
                AppLog.i("reconnect asked (#$startId)")
                engine.submit {
                    when {
                        // A newer reconnect follows and brings the newest settings.
                        startId != lastReconnectId -> Unit
                        // A late "apply new settings" must not switch on a
                        // VPN the user has turned off meanwhile.
                        engine.session == null && !runtime.shouldRun() -> {
                            pickPending.set(false)
                            withContext(Dispatchers.Main) { stopIfLatest(startId) }
                        }
                        // Read and cleared in one step: a pick sent meanwhile is never lost.
                        else -> engine.start(StartRequest(startId, userRequested = true, picked = pickPending.getAndSet(false)))
                    }
                }
            }
            // ACTION_CONNECT, ACTION_RESUME, the system's Always-on VPN
            // (SERVICE_INTERFACE) and a restart after the process was killed
            // (null intent).
            else -> {
                val restart = intent == null
                val resume = intent?.action == ACTION_RESUME
                val alwaysOn = intent?.action == VpnService.SERVICE_INTERFACE
                // stopSelf(startId), never stopSelf(): a start the system has
                // just accepted (the widget's) must not be dropped with it.
                if (restart && !runtime.shouldRun()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (restart && !RuntimeState.allowAutoRestart(this)) {
                    AppLog.e("tunnel keeps crashing, giving up automatic restarts")
                    Notifications.showError(this, "VPN несколько раз аварийно остановился и больше не перезапускается автоматически. Откройте приложение.")
                    runtime.setShouldRun(false)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                when {
                    restart -> AppLog.i("restarted by the system after the process ended")
                    resume -> AppLog.i("resuming the tunnel")
                    alwaysOn -> {
                        AppLog.i("started by Always-on VPN")
                        // The system starts it for the user: failed starts are
                        // tried again, as for a tunnel that should run.
                        runtime.setShouldRun(true)
                    }
                }
                // Also sent to a tunnel that is up (the app connects whatever
                // its cached status says): it stays up and says so.
                publisher.enterForegroundUnlessUp("Подключение…")
                // Only the app, the tile and the widget ask for a start just
                // now. Restarts, resumes and Always-on bring back a tunnel
                // that should run: they never call prepare() (it would take
                // the VPN over from another app) and are tried again on failure.
                val requested = intent?.action == ACTION_CONNECT
                val picked = intent?.getBooleanExtra(EXTRA_PICKED, false) == true
                engine.submit {
                    when {
                        engine.session != null -> {
                            publisher.publishConnected()
                            // "Connect" while connected: maybe it does not work.
                            if (clock.elapsed() - lastVerifiedOkAt >= APP_CHECK_MS) scheduleVerify(Reason.APP)
                        }
                        // Turned off after the resume was sent.
                        resume && !runtime.shouldRun() -> withContext(Dispatchers.Main) { stopIfLatest(startId) }
                        else -> engine.start(StartRequest(startId, requested, picked = picked))
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission in settings.
        AppLog.i("VPN permission revoked by the system (another VPN app or the system settings)")
        disconnect(userInitiated = true, lastStartId, message = "VPN отключила система или другое VPN-приложение")
    }

    override fun onDestroy() {
        watcher.stop()
        // Before the scope goes: the status still reaches the app.
        publisher.markDisconnected()
        scope.cancel()
        // Synchronous: the process may be killed right after this.
        engine.stopNow()
        publisher.kill()
        super.onDestroy()
    }

    /**
     * Leaves the foreground and stops, unless a newer command is waiting.
     * stopSelf(startId) lets the system make the final check atomically: a
     * start it already accepted but not yet delivered keeps the service.
     */
    private fun stopIfLatest(startId: Int) {
        if (startId != lastStartId) return
        leaveForeground()
        stopSelf(startId)
    }

    // ------------------------------------------------------------------ stop

    /** [message]: why, when it was not the user (shown under "Отключено"). */
    private fun disconnect(userInitiated: Boolean, startId: Int, message: String? = null) {
        if (userInitiated) runtime.setShouldRun(false)
        publisher.setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
        engine.submit { engine.stop(userInitiated, startId, message) }
    }

    private fun leaveForeground() {
        watcher.stop()
        publisher.clearBase()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // --------------------------------------------------------- network change

    /**
     * Android no longer sees internet on the same network ([lost]: often the
     * first sign of a blocked server or of a mobile whitelist switched on),
     * or it came back (a Wi-Fi login, the end of a pause): check again, at
     * most once a minute. Coming back only matters when the last check failed.
     */
    private fun onReachabilityChanged(lost: Boolean) {
        if (!lost && lastVerifiedOkAt >= lastVerifyAt) return
        val now = clock.elapsed()
        if (now - lastLinkCheckAt < LINK_CHECK_MS) return
        lastLinkCheckAt = now
        scheduleVerify(Reason.LINK, NETWORK_SETTLE_MS)
    }

    private fun onUnderlyingNetworkChanged(network: NetId?, previous: NetId?) {
        // Whatever a check in progress measured belongs to the old network.
        epoch.advance()
        val e = epoch.current
        if (network == null) {
            // Nothing to search with: do not claim to be searching.
            scope.launch { publisher.show(publisher.base, e, replacing = setOf(Failover.NOTICE_SEARCHING)) }
            return
        }
        // Another network: why the server was switched no longer applies.
        if (network != previous) publisher.clearSwitch(e, minAgeMs = 0)
        // No reset for the first network since the tunnel came up (Always-on
        // at boot), the same one back after a gap or right after connecting:
        // still worth a check.
        val running = engine.session
        val reset = previous != null && network != previous && running != null &&
            clock.elapsed() - running.connectedAt >= MIN_UPTIME_FOR_RESET_MS
        if (!reset) {
            scheduleVerify(Reason.NETWORK, NETWORK_SETTLE_MS)
            return
        }
        // Connections opened over the old network are dead but would hang
        // until timeouts. Restarting the core resets them at once, so apps
        // (messengers, video) reconnect immediately over the new network.
        engine.resetInPlace("network changed, resetting connections", NETWORK_SETTLE_MS)
    }

    // --------------------------------------------------------------- failover

    /**
     * Checks in the background that traffic gets through the tunnel, and
     * looks for another server if not. Never inside [TunnelEngine.submit]:
     * it takes seconds, and "off" must not wait for it. One at a time;
     * [delayMs] lets a new network settle.
     */
    private fun scheduleVerify(reason: Reason, delayMs: Long = 0) {
        if (engine.session == null) return
        epoch.startCheck { e ->
            scope.launch(Dispatchers.IO) {
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

    private suspend fun verify(e: Long, reason: Reason) {
        // The log grows for as long as the tunnel runs; checks come often enough to keep it small.
        XrayLog.trim(coreLog)
        val current = engine.session ?: return
        val c = current.core
        val running = current.profile
        if (!epoch.isCurrent(e) || VpnStatusHolder.status.value.state != VpnState.CONNECTED) return
        lastVerifyAt = clock.elapsed()
        val ok = TrafficCheck.passes(c, TrafficCheck.VERIFY_TIMEOUT_MS, TrafficCheck.CONFIRM_TIMEOUT_MS)
        if (!epoch.isCurrent(e)) return
        if (!ok) {
            AppLog.w("no traffic through the server (${reason.name.lowercase()})")
            runFailover(e, c, running, stalled = false)
            return
        }
        // It answers, but some operators freeze a foreign server's
        // connections after the first ~16 KB: pages and video then hang
        // while short answers still get through.
        val network = netInfo.active()
        if (bulkDue(reason, network) && stalls(c, network)) {
            if (!epoch.isCurrent(e)) return
            AppLog.w("downloads through the server stall (${reason.name.lowercase()})")
            runFailover(e, c, running, stalled = true)
            return
        }
        if (!epoch.isCurrent(e)) return
        lastVerifiedOkAt = clock.elapsed()
        publisher.clearFailure(e)
        publisher.clearSwitch(e, minAgeMs = SWITCH_NOTICE_MS)
        owedRefresh?.let { id ->
            owedRefresh = null
            refreshThroughTunnel(id, c, running)
        }
        // Moments when connections start over anyway.
        if (reason == Reason.NETWORK || reason == Reason.UNLOCK) returnHomeIfItAnswers(e, c, running)
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
     * Whether downloads through [c] stop partway: twice in a row, a 64 KB
     * file stops coming after it began. Any other failure (the site refuses
     * or cannot be reached from the server) proves nothing and counts as
     * fine. No app name is sent. Blocking.
     */
    private fun stalls(c: CoreHandle, network: NetId?): Boolean {
        repeat(2) {
            val stalled = try {
                c.fetchThroughTunnel(BULK_URL, "", BULK_HEADERS, BULK_TIMEOUT_MS)
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

    /**
     * [failed] passes no traffic ([stalled]: it answers, but downloads
     * freeze): probe other servers, all in one temporary core, and switch
     * to the fastest that answers. The tunnel stays as it is meanwhile, and
     * for good when nothing answers: nothing ever leaks outside the VPN.
     */
    private suspend fun runFailover(e: Long, c: CoreHandle, failed: StoredProfile, stalled: Boolean) {
        val net = netInfo.state()
        if (net?.hasNetwork != true) {
            AppLog.i("no network, not looking for another server")
            return
        }
        // Hotel or metro Wi-Fi before its login page: no server can answer yet.
        if (net.captive) {
            AppLog.i("the Wi-Fi asks to sign in, not looking for another server")
            publisher.show(Failover.NOTICE_SIGN_IN, e)
            return
        }
        if (memory.keptByUser(failed.id)) {
            publisher.show(Failover.NOTICE_PICK_ANOTHER, e)
            return
        }
        val network = netInfo.active()
        // Nothing answered on this network moments ago: say so again, search later.
        val last = fruitlessNotice(network)
        if (last != null) {
            publisher.show(last, e)
            return
        }
        if (!runtime.allowFailover(take = false)) {
            AppLog.w("automatic server switches used up for now")
            publisher.show(Failover.NOTICE_PICK_ANOTHER, e)
            return
        }
        val exclude = memory.exclude()
        val state = profiles.snapshot()
        val first = pick(state, failed, exclude, tried = emptyList())
        // The panel may have moved the servers meanwhile (new addresses or keys).
        val sub = refreshableSubscription(state, failed)
        if (first.isNotEmpty() || sub != null) {
            // Probing servers and downloading the list is what costs.
            if (!runtime.allowSearch()) {
                AppLog.w("automatic searches used up for now")
                publisher.show(Failover.NOTICE_PICK_ANOTHER, e)
                return
            }
            publisher.show(Failover.NOTICE_SEARCHING, e)
            AppLog.i("trying ${first.size} other servers")
        }
        val refresh = sub?.let { startFailoverRefresh(it) }
        // The failed server goes first, as a control: when it answers here
        // too, the phone was offline for a moment (a lift, a tunnel) and the
        // server is fine. Not for a stall, which a short answer never shows.
        val control = !stalled
        val delays = probe(c, (if (control) listOf(failed) else emptyList()) + first)
        if (!epoch.isCurrent(e)) return
        // It answered: whatever happens next, it is not blocked from here.
        val reachable = control && delays.first() >= 0
        if (reachable && recoveredInPlace(e, c)) return
        val tier = { p: StoredProfile -> Failover.tier(p, failed) }
        var probed = first.size
        var winner = Failover.fastest(first, if (control) delays.drop(1) else delays, tier)
        var refreshed: Refreshed? = null
        if (winner == null && refresh != null) {
            refreshed = refresh.await()
            if (refreshed.applied == true) {
                // Only what the refresh brought: new servers, or new settings of known ones.
                val second = pick(profiles.snapshot(), failed, exclude, tried = first.map { it.outbounds })
                if (second.isNotEmpty()) {
                    AppLog.i("subscription refreshed, trying ${second.size} more servers")
                    probed += second.size
                    winner = Failover.fastest(second, probe(c, second), tier)
                }
            }
            if (!epoch.isCurrent(e)) return
        }
        if (winner == null) {
            AppLog.w("no server answers")
            nothingAnswers(e, failed, state, probed, network, report = !reachable)
            // The refresh changed or removed the running server: run what is
            // saved now, so the app and the widget show what really runs.
            if (refreshed?.runningChanged == true) restartOnSaved(failed)
            return
        }
        val (to, ms) = winner
        AppLog.i("switching to a server that answered in $ms ms")
        engine.submit { switchTo(e, failed, to.id, report = !reachable) }
    }

    /**
     * The failed server answered in a new connection. If it now answers
     * through the running core too, the connection was lost for a moment:
     * stay. If not, the running core is stuck: restart it on the same
     * server, at most every 10 minutes. False: treat it as a real failure.
     * Google first, as the probe that answered, then Cloudflare.
     */
    private suspend fun recoveredInPlace(e: Long, c: CoreHandle): Boolean {
        if (TrafficCheck.passes(c, TrafficCheck.CONFIRM_TIMEOUT_MS, TrafficCheck.CONFIRM_TIMEOUT_MS)) {
            if (!epoch.isCurrent(e)) return true
            AppLog.i("the server answers again: the connection was lost for a moment")
            lastVerifiedOkAt = clock.elapsed()
            publisher.clearFailure(e)
            return true
        }
        val now = clock.elapsed()
        if (now - lastCoreRestartAt < CORE_RESTART_GAP_MS) return false
        // Not queued: the tunnel changed meanwhile, and the gap stays for a real stuck core.
        if (engine.resetInPlace("the server answers, but not through the running core: restarting it", expectedEpoch = e)) {
            lastCoreRestartAt = now
        }
        return true
    }

    /**
     * No other server answers either (or there is none). A Russian site
     * opened directly, outside the tunnel, tells a block from a phone
     * without internet, and on mobile data a foreign site that does not
     * open directly either tells the operator's whitelist from a block.
     * Only a phone that is online reports [failed] to the owner (marked as
     * the whitelist when it is), and only with [report] (false: [failed]
     * itself answered a new connection).
     */
    private suspend fun nothingAnswers(e: Long, failed: StoredProfile, state: ProfilesState, probed: Int, network: NetId?, report: Boolean) {
        val online = direct.opens(DirectNet.DIRECT_URL)
        val whitelist = online && onMobileData() && !direct.opens(XrayCore.TEST_URL)
        if (!epoch.isCurrent(e)) return
        val notice = Failover.nothingAnswersNotice(online, whitelist, probed)
        if (online) {
            AppLog.w(
                if (whitelist) {
                    "no server answers, and mobile data seems limited to the operator's whitelist"
                } else {
                    "the phone is online, but no server answers from this network"
                },
            )
            if (report) reportBlocked(failed, state, allDown = true, whitelist = whitelist)
        }
        markFruitless(network, notice)
        publisher.show(notice, e)
    }

    /** The notice of a search that found nothing on [network] in the last minutes, or null. */
    private fun fruitlessNotice(network: NetId?): String? = synchronized(fruitless) {
        val now = clock.elapsed()
        fruitless.entries.removeIf { now - it.value.first >= FRUITLESS_RETRY_MS || it.value.first > now }
        fruitless[network]?.second
    }

    private fun markFruitless(network: NetId?, notice: String) {
        synchronized(fruitless) { fruitless[network] = clock.elapsed() to notice }
    }

    /**
     * Runs the saved selection again, unless [running] no longer runs
     * (another start came first) or the tunnel was turned off. Not tied to
     * the check's epoch: a network change during the download resets
     * connections but leaves the removed server running.
     */
    private fun restartOnSaved(running: StoredProfile) {
        engine.submit {
            if (engine.session?.profile !== running || !runtime.shouldRun()) return@submit
            AppLog.i("the running server changed in the subscription, restarting")
            engine.start(StartRequest(lastStartId, userRequested = false))
        }
    }

    /**
     * Inside [TunnelEngine.submit]: moves the tunnel from [failed] to
     * [winnerId], unless something changed since the probe began: another
     * core or network (epoch), the VPN turned off, another server chosen.
     * [returning]: back to the user's server, which is no failure of [failed].
     * [report]: tell the owner's panel that [failed] does not answer here.
     */
    private suspend fun switchTo(e: Long, failed: StoredProfile, winnerId: String, returning: Boolean = false, report: Boolean = true) {
        try {
            if (!epoch.isCurrent(e) || engine.session == null || !runtime.shouldRun() ||
                VpnStatusHolder.status.value.state != VpnState.CONNECTED
            ) {
                AppLog.i("server switch dropped: the tunnel changed meanwhile")
                return
            }
            val saved = profiles.snapshot()
            val winner = saved.profiles.firstOrNull { it.id == winnerId }
            if (winner == null || !Failover.selectionFollowsFailed(saved, failed.id)) {
                // The user's choice wins; their reconnect follows.
                AppLog.i("server switch dropped: another server was chosen")
                publisher.clearFailure(e)
                return
            }
            if (!runtime.allowFailover()) {
                if (!returning) publisher.show(Failover.NOTICE_PICK_ANOTHER, e)
                return
            }
            val notice = if (returning || winner.id == failed.id) null else Failover.switchedNotice(winner.name, failed.name)
            engine.start(
                StartRequest(
                    lastStartId,
                    userRequested = false,
                    switch = Switch(winner.id, failed.id, expectedSelection = saved.selectedId, notice = notice),
                ),
            )
            // Only a switch that happened marks the failed server: one dropped
            // on the way (a reconnect came first) proves nothing about it.
            if (!returning && winner.id != failed.id && engine.session?.profile?.id == winner.id) {
                memory.markFailed(failed.id)
                // Up on another server: the phone is online, so the failed
                // one does not answer from this network.
                if (report) reportBlocked(failed, saved, winner = winner)
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.w("server switch failed", ex)
        }
    }

    /**
     * Tells the owner's panel that [failed] stopped answering here, if its
     * subscription asked for that (see [BlockReporter]). In the background:
     * the switch never waits for it, and nothing it does can fail the tunnel.
     * [winner]: the server the tunnel switched to. [whitelist]: the mobile
     * whitelist is already known to explain the failure.
     */
    private fun reportBlocked(
        failed: StoredProfile,
        state: ProfilesState,
        allDown: Boolean = false,
        winner: StoredProfile? = null,
        whitelist: Boolean = false,
    ) {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return
        if (sub.reportUrl == null) return
        val network = watcher.network
        scope.launch(Dispatchers.IO) {
            try {
                val listed = whitelist || winner != null && looksLikeWhitelist(failed, winner)
                blockReporter.report(failed, sub, network, allDown, listed) { engine.session?.core }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                AppLog.w("block report failed", ex)
            }
        }
    }

    /**
     * Whether the switch from [failed] to [winner] looks like the mobile
     * operator letting through only its whitelist, not a block of [failed]:
     * on mobile data, [winner] is on the whitelist and [failed] is not, and
     * outside the tunnel a foreign site does not open while a Russian one
     * does (a block of [failed] alone would leave the foreign site open).
     */
    private suspend fun looksLikeWhitelist(failed: StoredProfile, winner: StoredProfile): Boolean {
        if (!onMobileData()) return false
        val winnerHost = Failover.host(winner)
        val failedHost = Failover.host(failed)
        val listed = whitelisted(listOf(winnerHost, failedHost).distinct())
        return winnerHost in listed && failedHost !in listed &&
            !direct.opens(XrayCore.TEST_URL) && direct.opens(DirectNet.DIRECT_URL)
    }

    /**
     * The server the tunnel switched to becomes the selection, unless the
     * user chose another meanwhile. Returns whether it did.
     */
    private suspend fun saveSwitch(failedId: String, winnerId: String, expected: String?): Boolean {
        try {
            val saved = profiles.updateProfiles { s -> Failover.selectInstead(s, failedId, winnerId, expected) }
            if (saved.selectedId == winnerId) {
                publisher.profilesChanged()
                return true
            }
            AppLog.i("another server was chosen meanwhile, selection kept")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The tunnel runs anyway; only the next start picks the old server.
            AppLog.w("could not save the new selection", e)
        }
        return false
    }

    // ------------------------------------------------ back to the user's server

    /** An automatic switch from [failedId] to [winnerId] happened: remember the user's server. */
    private fun trackAway(failedId: String, winnerId: String) {
        val away = runtime.away()
        val now = clock.elapsed()
        if (away != null && winnerId == away.home) runtime.setReturned(Failover.Returned(away.home, now, away.backoff))
        runtime.setAway(Failover.afterSwitch(away, runtime.returned(), failedId, winnerId, now))
    }

    /**
     * A start that was no automatic switch: the user's server is no longer
     * worth going back to once they picked one, or the tunnel runs another
     * server than the one switched to (a delete or refresh moved it).
     */
    private fun forgetAwayUnless(runningId: String, picked: Boolean) {
        val away = runtime.away() ?: return
        if (picked || runningId != away.to) runtime.setAway(null)
    }

    /**
     * After an automatic switch the tunnel stays on the other server only
     * while needed: once the user's own server answers again (at the
     * earliest 30 minutes later, then less and less often if it keeps
     * failing), go back to it.
     */
    private suspend fun returnHomeIfItAnswers(e: Long, c: CoreHandle, running: StoredProfile) {
        val away = runtime.away() ?: return
        val now = clock.elapsed()
        if (away.to != running.id || !Failover.returnDue(away, now) || memory.failedRecently(away.home)) return
        val saved = profiles.snapshot()
        val home = saved.profiles.firstOrNull { it.id == away.home }
        if (home == null) {
            runtime.setAway(null)
            return
        }
        // The user chose another server meanwhile; their start follows.
        if (saved.selectedId != running.id || !runtime.allowFailover(take = false)) return
        val answered = probe(c, listOf(home)).first() >= 0
        if (!epoch.isCurrent(e)) return
        if (!answered) {
            runtime.setAway(Failover.returnFailed(away, now))
            return
        }
        AppLog.i("the chosen server answers again, going back to it")
        engine.submit { switchTo(e, running, home.id, returning = true) }
    }

    // ------------------------------------------------------------ candidates

    /**
     * Candidates for [failed]. Servers on the Russian mobile whitelist go
     * first on mobile data, and get a few slots elsewhere (a hotspot or a 4G
     * router may be under the whitelist too) when not every server is probed.
     */
    private suspend fun pick(state: ProfilesState, failed: StoredProfile, exclude: Set<String>, tried: List<JsonArray>): List<StoredProfile> {
        val pool = Failover.pickCandidates(state, failed, exclude, tried, limit = Int.MAX_VALUE)
        val mobile = onMobileData()
        // Every one of them is probed anyway.
        if (!mobile && pool.size <= Failover.MAX_CANDIDATES) return pool
        val preferred = whitelisted(pool.map { Failover.host(it) }.distinct().take(WHITELIST_HOSTS))
        val reserve = if (mobile) Failover.MAX_CANDIDATES else Failover.WHITELIST_RESERVED
        return Failover.pickCandidates(state, failed, exclude, tried, preferred, reserve = reserve)
    }

    /** Those of [hosts] on the Russian mobile whitelist; lookups that take too long count as not. */
    private suspend fun whitelisted(hosts: List<String>): Set<String> {
        val found = ConcurrentHashMap.newKeySet<String>()
        hosts.filterTo(found) { whitelistCache[it] == true }
        val limit = Semaphore(4)
        // In the service scope: a DNS lookup cannot be interrupted, and the
        // search must not wait for a slow one.
        val lookups = hosts.filterNot { whitelistCache.containsKey(it) }.map { host ->
            scope.launch(Dispatchers.IO) {
                try {
                    limit.withPermit {
                        val status = direct.whitelistStatus(host)
                        whitelistCache[host] = status == 1
                        if (status == 1) found.add(host)
                    }
                } catch (ex: Exception) {
                    // Not remembered: the next search asks again.
                    if (ex is CancellationException) throw ex
                }
            }
        }
        try {
            withTimeoutOrNull(WHITELIST_WAIT_MS) { lookups.joinAll() }
        } finally {
            lookups.forEach { it.cancel() }
        }
        return found.toSet()
    }

    /** The delay through each of [servers] in ms, or -1, in the same order. Never throws. */
    private suspend fun probe(c: CoreHandle, servers: List<StoredProfile>): List<Long> {
        if (servers.isEmpty()) return emptyList()
        return probeLock.withLock {
            try {
                c.probe(servers.map { it.outbounds }, timeoutMs = Failover.PROBE_TIMEOUT_MS, parallel = Failover.PARALLEL)
            } catch (ex: Exception) {
                AppLog.w("probe failed", ex)
                List(servers.size) { -1L }
            }
        }
    }

    // ---------------------------------------------------------- subscriptions

    /** [failed]'s subscription, when it may be downloaded again now: at most every 10 minutes and one at a time. */
    private fun refreshableSubscription(state: ProfilesState, failed: StoredProfile): Subscription? {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return null
        if (refreshJob?.isActive == true || !Failover.refreshDue(sub, clock.wall())) return null
        return sub
    }

    /**
     * Downloads [sub] again, directly: the running server is what fails. On
     * its own, so that a switch meanwhile does not cut it short.
     */
    private fun startFailoverRefresh(sub: Subscription): Deferred<Refreshed> {
        val directly = Downloader { url, headers -> XrayCore.fetch(url, null, headers, REFRESH_TIMEOUT_MS) }
        return scope.async(Dispatchers.IO) {
            val result = refreshSubscription(sub.id, directly)
            // Blocked outside the tunnel: once a server works, through it.
            if (result.applied == null) owedRefresh = sub.id
            result
        }.also { refreshJob = it }
    }

    /** After a check of [running] passed: downloads subscription [subId] through the tunnel, and restarts if that changed the running server. */
    private fun refreshThroughTunnel(subId: String, c: CoreHandle, running: StoredProfile) {
        if (refreshJob?.isActive == true) return
        val tunnel = c.downloader()
        refreshJob = scope.async(Dispatchers.IO) {
            refreshSubscription(subId, tunnel).also { if (it.runningChanged) restartOnSaved(running) }
        }
    }

    /**
     * Downloads subscription [subId] and saves the result; the old servers
     * stay when the panel sends none. The app, if open, reloads. Never throws.
     */
    private suspend fun refreshSubscription(subId: String, downloader: Downloader): Refreshed = try {
        val outcome = SubscriptionUpdater(this, profiles).refresh(subId, downloader, runningId = engine.session?.profile?.id)
        Refreshed(applied = outcome?.applied ?: false, runningChanged = outcome?.runningChanged == true)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w("subscription refresh failed: ${e.userMessage()}")
        Refreshed(applied = null, runningChanged = false)
    } finally {
        publisher.profilesChanged()
    }

    // ---------------------------------------------------------------- network

    private fun onMobileData(): Boolean = netInfo.state()?.cellular == true
}
