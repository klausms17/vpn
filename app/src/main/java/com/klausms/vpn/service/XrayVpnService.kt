package com.klausms.vpn.service

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.klausms.vpn.core.XrayCoreHandle
import com.klausms.vpn.core.XrayDirectNet
import com.klausms.vpn.data.DiskProfiles
import com.klausms.vpn.util.AndroidClock
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
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
 *   unlock, app opened) and every few minutes while the screen is on (see
 *   [HealthMonitor]); a server that stopped answering is replaced by one
 *   that answers (see [FailoverSearch] and [ServerSwitcher]).
 *
 * This class is the Android side and the wiring. It builds the
 * collaborators in [onCreate], turns start commands into starts and stops
 * of the [TunnelEngine], and passes on what the network watcher, the
 * engine and the app report. It owns the one coroutine scope they all
 * share and which command is the newest. Threading: the lifecycle calls
 * run on the main thread, the binder's on binder threads, and the blocks
 * it submits on the engine's worker.
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var runtime: RuntimeStore

    private lateinit var publisher: StatusPublisher

    private lateinit var watcher: NetworkWatcher

    private lateinit var engine: TunnelEngine

    private lateinit var memory: FailureMemory

    private lateinit var switcher: ServerSwitcher

    private lateinit var health: HealthMonitor

    // The newest command's startId. A job only stops the service if no newer
    // command arrived meanwhile, so a quick "off, on" never loses the "on".
    @Volatile
    private var lastStartId = 0

    /** The newest reconnect; older ones still queued are skipped. */
    @Volatile
    private var lastReconnectId = 0

    // A reconnect asked for with EXTRA_PICKED; merged reconnects keep it.
    private val pickPending = AtomicBoolean()

    // What the watcher hears, on the main thread.
    private val networkEvents = object : NetworkWatcher.Listener {
        override fun onNetworkChanged(net: NetId?, previous: NetId?) = health.onNetworkChanged(net, previous)

        override fun onReachability(lost: Boolean) = health.onReachability(lost)

        override fun onPrivateDns(host: String?) = publisher.onPrivateDnsChanged(host)

        override fun onUnlock() = health.onUnlock()

        override fun onScreenTick() = health.onScreenTick()
    }

    // What the engine reports, on its worker.
    private val engineEvents = object : TunnelEngine.Listener {
        override suspend fun onUp(session: TunnelSession, req: StartRequest, before: VpnStatus, restarting: Boolean) {
            val profile = session.profile
            memory.onStarted(profile.id, automatic = req.switch != null, picked = req.picked)
            runtime.setShouldRun(true)
            withContext(Dispatchers.Main) { watcher.start() }
            switcher.afterStart(profile.id, req)
            publisher.connected(profile, req.switch?.notice, before, restarting)
            publisher.publishConnected()
            health.schedule(Reason.START)
        }

        override fun onReset() = health.schedule(Reason.NETWORK)
    }

    private val binder = object : IVpnController.Stub() {
        override fun registerCallback(callback: IVpnCallback?) {
            callback ?: return
            publisher.register(callback)
            health.onAppShown()
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
        val clock = AndroidClock
        val epoch = Epoch()
        runtime = PrefsRuntimeStore(this)
        val profiles = DiskProfiles(this)
        val coreLog = XrayLog.file(this)
        val direct = XrayDirectNet(this)
        val netInfo = SystemNetworkInfo(this)
        publisher = StatusPublisher(this, scope, epoch, clock) { engine.session?.lockdownConflict == true }
        watcher = NetworkWatcher(
            this, scope, HealthMonitor.SCREEN_CHECK_MS,
            setUnderlying = { network -> setUnderlyingNetworks(network?.let { arrayOf(it) }) },
            listener = networkEvents,
        )
        engine = TunnelEngine(
            this, scope, epoch, clock, XrayCoreHandle(), publisher, runtime, profiles, coreLog,
            underlying = { watcher.network },
            lastStartId = { lastStartId },
            stopIfLatest = ::stopIfLatest,
            listener = engineEvents,
        )
        memory = FailureMemory(clock)
        val mobileWhitelist = WhitelistLookup(scope, Dispatchers.IO, direct)
        val refresher = SubscriptionRefresher(
            scope, Dispatchers.IO, clock, UpdaterSubscriptionSource(this, profiles),
            session = { engine.session },
            profilesChanged = publisher::profilesChanged,
        )
        val reports = BlockReportDispatcher(
            this, scope, Dispatchers.IO, netInfo,
            underlying = { watcher.network },
            mobileWhitelist = mobileWhitelist,
            direct = direct,
            core = { engine.session?.core },
        )
        val failover = FailoverSearch(clock, epoch, netInfo, runtime, profiles, publisher, direct, mobileWhitelist, memory, refresher, reports)
        switcher = ServerSwitcher(
            engine, profiles, runtime, memory, failover, reports, publisher, clock, epoch,
            status = { VpnStatusHolder.status.value },
            profilesChanged = publisher::profilesChanged,
        )
        health = HealthMonitor(
            scope, Dispatchers.IO, clock, epoch, engine,
            status = { VpnStatusHolder.status.value },
            netInfo = netInfo,
            notices = publisher,
            failover = failover,
            switcher = switcher,
            refresher = refresher,
            coreLog = coreLog,
        )
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
                            health.onConnectWhileUp()
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
}
