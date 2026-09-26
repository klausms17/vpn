package com.klausms.vpn.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.klausms.vpn.core.BuildOptions
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.AppMode
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.DiskProfiles
import com.klausms.vpn.data.Downloader
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.RussianApps
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Stores
import com.klausms.vpn.data.SubscriptionUpdater
import com.klausms.vpn.ui.MainActivity
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.widget.VpnWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import libxray.Controller
import libxray.Libxray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The tunnel. Runs in the ":vpn" process.
 *
 * - Xray reads the TUN fd directly (gVisor stack inside the core): no
 *   tun2socks, no local SOCKS/HTTP port that other apps could find.
 * - This app is always excluded from the tunnel, so the core's own
 *   connections to the server can never loop back into it.
 * - All start/stop work is serialized on one thread.
 * - Nothing runs periodically unless the app UI is open (traffic counters),
 *   so an idle tunnel costs no extra battery.
 * - Whether traffic really gets through is checked on events only (connect,
 *   network change, unlock, app opened); a server that stopped answering is
 *   replaced by one that answers (see [Failover]).
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.klausms.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.klausms.vpn.DISCONNECT"
        const val ACTION_RECONNECT = "com.klausms.vpn.RECONNECT"
        const val ACTION_BIND = "com.klausms.vpn.BIND"

        /** Who asked to disconnect, for the log. */
        const val EXTRA_SOURCE = "source"

        private const val NETWORK_SETTLE_MS = 1_500L

        /** A failed start of a tunnel that should run is tried this many more times. */
        private const val MAX_START_RETRIES = 2
        private const val MIN_UPTIME_FOR_RESET_MS = 3_000L

        // The traffic check: a second site confirms before a server counts as dead.
        private const val VERIFY_TIMEOUT_MS = 6_000
        private const val CONFIRM_TIMEOUT_MS = 4_000

        /** Unlocking the phone checks at most this often. */
        private const val UNLOCK_CHECK_MS = 10 * 60_000L

        /** Opening the app checks at most this often. */
        private const val APP_CHECK_MS = 2 * 60_000L

        /** After a search found nothing, the next one waits this long (unless asked for). */
        private const val FRUITLESS_RETRY_MS = 2 * 60_000L

        private const val REFRESH_TIMEOUT_MS = 10_000

        /** Hosts looked up in the mobile whitelist per search, and how long to wait for them. */
        private const val WHITELIST_HOSTS = 12
        private const val WHITELIST_WAIT_MS = 3_000L

        /** The running core, for the widget's ping (same process). */
        @Volatile
        var liveController: Controller? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    // Start/stop jobs run strictly one after another, including their hops
    // to the main thread (a single-thread dispatcher alone would let the next
    // job start while the previous one waits for the main thread).
    private val serial = Mutex()

    // The newest command's startId. A job only stops the service if no newer
    // command arrived meanwhile, so a quick "off, on" never loses the "on".
    @Volatile
    private var lastStartId = 0

    // Written only on [worker]; read elsewhere.
    @Volatile
    private var controller: Controller? = null

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var config: String? = null

    @Volatile
    private var connectedAtElapsed = 0L

    @Volatile
    private var resetOnNetworkChange = true

    @Volatile
    private var lockdownConflict = false

    private var networkMonitor: UnderlyingNetworkMonitor? = null
    private var lastNetwork: Network? = null

    @Volatile
    private var resetJob: Job? = null

    /** Counts starts; a retry planned for an older one is dropped. Used in the serial queue only. */
    private var startGeneration = 0

    // ------------------------------------------------------ failover state

    // Changes whenever the running core changes, stops or loses its network:
    // a check or probe that started before is outdated and its result
    // discarded.
    private val epoch = AtomicLong()

    @Volatile
    private var runningProfile: StoredProfile? = null

    private val runningProfileId: String? get() = runningProfile?.id

    // elapsedRealtime of the last check started, and of the last one that got through.
    @Volatile
    private var lastVerifyAt = 0L

    @Volatile
    private var lastVerifiedOkAt = 0L

    // When a search last probed servers and none answered.
    @Volatile
    private var lastFruitlessAt = 0L

    @Volatile
    private var verifyJob: Job? = null
    private val verifyLock = Any()

    // One probe at a time: a probe's temporary core holds megabytes until it
    // ends, also when its result is no longer wanted.
    private val probeLock = Mutex()

    // Server id -> elapsedRealtime it stopped answering; skipped as a
    // candidate for [Failover.RECENTLY_FAILED_MS].
    private val recentlyFailed = ConcurrentHashMap<String, Long>()

    // A server the user chose again right after it had been switched away
    // from: their choice wins, it is not switched away from automatically.
    @Volatile
    private var manualPick: String? = null

    @Volatile
    private var refreshJob: Deferred<Boolean>? = null

    // A subscription whose direct download failed during a search: it is
    // downloaded through the tunnel once a server works again.
    @Volatile
    private var owedRefresh: String? = null

    private val blockReporter by lazy { BlockReporter(this) }

    private var unlockRegistered = false

    // Unlocking is when the phone is about to be used: a cheap moment to
    // find out that the server stopped answering while it was locked.
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            if (SystemClock.elapsedRealtime() - lastVerifyAt < UNLOCK_CHECK_MS) return
            scheduleVerify(Reason.UNLOCK)
        }
    }

    private val callbacks = RemoteCallbackList<IVpnCallback>()

    private val binder = object : IVpnController.Stub() {
        override fun registerCallback(callback: IVpnCallback?) {
            callback ?: return
            callbacks.register(callback)
            scope.launch { sendStatus(callback, VpnStatusHolder.status.value) }
            // The app came on screen: show the truth about the connection.
            if (SystemClock.elapsedRealtime() - lastVerifyAt >= APP_CHECK_MS) scheduleVerify(Reason.APP)
        }

        override fun unregisterCallback(callback: IVpnCallback?) {
            callback ?: return
            callbacks.unregister(callback)
        }

        override fun testConnection(): Long {
            val c = controller ?: return -1
            val ms = try {
                c.measureDelay(XrayCore.TEST_URL, 10_000)
            } catch (e: Exception) {
                AppLog.w("connection test failed", e)
                -1L
            }
            if (ms < 0) {
                // The user saw it fail: look for a server that answers.
                scheduleVerify(Reason.USER)
            } else if (config != null) {
                val now = SystemClock.elapsedRealtime()
                lastVerifyAt = now
                lastVerifiedOkAt = now
                val e = epoch.get()
                scope.launch { clearFailureNotice(e) }
            }
            return ms
        }
    }

    override fun onCreate() {
        super.onCreate()
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
                enterForeground("Переподключение…", null)
                enqueue {
                    // A late "apply new settings" must not switch on a VPN
                    // the user has turned off meanwhile.
                    if (config == null && !RuntimeState.shouldRun(this)) {
                        withContext(Dispatchers.Main) { stopIfLatest(startId) }
                    } else {
                        startTunnel(startId, userRequested = true)
                    }
                }
            }
            // ACTION_CONNECT, the system's Always-on VPN (SERVICE_INTERFACE)
            // and a restart after the process was killed (null intent).
            else -> {
                if (intent == null && !RuntimeState.shouldRun(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (intent == null && !RuntimeState.allowAutoRestart(this)) {
                    AppLog.e("tunnel keeps crashing, giving up automatic restarts")
                    Notifications.showError(this, "VPN несколько раз аварийно остановился и больше не перезапускается автоматически. Откройте приложение.")
                    RuntimeState.setShouldRun(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                enterForeground("Подключение…", null)
                // A null intent is our own restart after the process died;
                // everything else (the app, tile, widget, Always-on) is a
                // start someone asked for.
                val requested = intent != null
                enqueue {
                    if (config == null) {
                        startTunnel(startId, requested)
                    } else {
                        publishConnected()
                        // "Connect" while connected: maybe it does not work.
                        if (SystemClock.elapsedRealtime() - lastVerifiedOkAt >= APP_CHECK_MS) scheduleVerify(Reason.APP)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission in settings.
        AppLog.i("VPN permission revoked by the system (another VPN app or the system settings)")
        disconnect(userInitiated = true, lastStartId)
    }

    override fun onDestroy() {
        networkMonitor?.stop()
        networkMonitor = null
        unregisterUnlock()
        // Never leave the widget, tile or app showing a tunnel that is gone.
        val state = VpnStatusHolder.status.value.state
        if (state != VpnState.DISCONNECTED && state != VpnState.ERROR) setStatus(VpnStatus(VpnState.DISCONNECTED))
        scope.cancel()
        // Synchronous: the process may be killed right after this.
        stopCore()
        callbacks.kill()
        super.onDestroy()
    }

    private fun enqueue(block: suspend () -> Unit) = scope.launch(worker) { serial.withLock { block() } }

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

    // ------------------------------------------------------------------ start

    /**
     * [profileOverride]: run this server instead of the selected one (an
     * automatic switch away from [failedId], which then becomes the
     * selection unless the user chose another meanwhile). [notice] is shown
     * once connected.
     */
    private suspend fun startTunnel(
        startId: Int,
        userRequested: Boolean,
        profileOverride: String? = null,
        failedId: String? = null,
        expectedSelection: String? = null,
        notice: String? = null,
        attempt: Int = 0,
    ) {
        val restarting = config != null
        val generation = ++startGeneration
        val before = VpnStatusHolder.status.value
        // From here on the new interface has replaced the old one.
        var swapped = false
        setStatus(VpnStatus(VpnState.CONNECTING, profileName = before.profileName))
        try {
            val profiles = Stores.profiles(this).read()
            val profile = profileOverride?.let { id -> profiles.profiles.firstOrNull { it.id == id } }
                ?: profiles.selected
                ?: throw VpnStartException("Не выбран сервер. Добавьте ключ в приложении.")
            val settings = Stores.settings(this).read()
            setStatus(VpnStatus(VpnState.CONNECTING, profile.id, profile.name))

            GeoFiles.ensureInstalled(this)
            XrayCore.init(this)
            val logFile = prepareLogFile()
            val newConfig = XrayCore.buildConfig(
                BuildOptions(
                    outbounds = profile.outbounds,
                    mode = settings.mode.core,
                    ipv6 = settings.ipv6,
                    directRules = settings.directRules,
                    proxyRules = settings.proxyRules,
                    blockRules = settings.blockRules,
                    logLevel = if (settings.verboseLog) "info" else "warning",
                    logFile = logFile.absolutePath,
                    tun = true,
                ),
            )

            // Bring the new interface up before the old one goes away:
            // Android then switches over without a moment of traffic
            // flowing outside the VPN.
            val newTun = establishTun(profile, settings, userRequested)
            swapped = true
            val oldTun = tun
            resetJob?.cancel()
            if (restarting) {
                // Checks of the old core end here, not with the next one's results.
                newEpoch()
                try {
                    controller?.stop()
                } catch (e: Exception) {
                    AppLog.w("core stop before restart", e)
                }
            }
            tun = newTun
            val c = controller ?: Libxray.newController().also { controller = it }
            try {
                c.start(newConfig, newTun.fd)
            } catch (e: Exception) {
                throw VpnStartException("Ядро не запустилось: ${e.userMessage()}")
            } finally {
                if (oldTun != null && oldTun !== newTun) closeQuietly(oldTun)
            }
            config = newConfig
            liveController = c
            runningProfile = profile
            newEpoch()
            // Picked by hand again after it had stopped answering: the user knows.
            manualPick = profile.id.takeIf { userRequested && profileOverride == null && failedRecently(profile.id) }
            resetOnNetworkChange = settings.resetOnNetworkChange
            connectedAtElapsed = SystemClock.elapsedRealtime()
            RuntimeState.setShouldRun(this, true)
            withContext(Dispatchers.Main) { startNetworkMonitor() }
            // Only a server whose core came up becomes the selection.
            if (profileOverride != null && failedId != null) saveSwitch(failedId, profile.id, expectedSelection)
            setStatus(VpnStatus(VpnState.CONNECTED, profile.id, profile.name, message = notice, connectedSince = System.currentTimeMillis()))
            Notifications.clearError(this)
            AppLog.i("tunnel up: ${profile.protocol}/${profile.network}/${profile.security}, mode ${settings.mode.core}")
            publishConnected()
            scheduleVerify(Reason.START)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is AnotherVpnException) {
                // The user switched to another VPN app while we were down:
                // leave it alone and stay off, without an error.
                AppLog.i("another VPN is active, not restarting")
                stopCore()
                RuntimeState.setShouldRun(this, false)
                setStatus(VpnStatus(VpnState.DISCONNECTED))
                withContext(Dispatchers.Main) { stopIfLatest(startId) }
                return
            }
            val message = (e as? VpnStartException)?.message ?: "Ошибка запуска: ${e.userMessage()}"
            AppLog.e("tunnel start failed (attempt ${attempt + 1}): $message", e.takeIf { it !is VpnStartException })
            val again: (Int) -> Unit = { next ->
                retryStart(generation, next) {
                    startTunnel(lastStartId, userRequested = false, profileOverride, failedId, expectedSelection, notice, attempt = next)
                }
            }
            // New settings or another server for a tunnel that works: until
            // the new interface replaced it, the old tunnel still carries the
            // traffic. It keeps running; one more try a little later.
            if (restarting && !swapped && config != null) {
                val running = runningProfile
                setStatus(
                    VpnStatus(
                        VpnState.CONNECTED, running?.id, running?.name,
                        message = "Не удалось применить изменения: $message",
                        connectedSince = before.connectedSince.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                )
                publishConnected()
                if (attempt < MAX_START_RETRIES) again(attempt + 1)
                return
            }
            // It was running, or should be running (a restart nobody asked
            // for): a second try usually works. The new interface stays up
            // meanwhile, so apps wait instead of going around the VPN.
            if ((restarting || !userRequested) && attempt < MAX_START_RETRIES && RuntimeState.shouldRun(this)) {
                haltCore()
                setStatus(VpnStatus(VpnState.CONNECTING, profileName = before.profileName, message = "Переподключение…"))
                again(attempt + 1)
                return
            }
            stopCore()
            RuntimeState.setShouldRun(this, false)
            setStatus(VpnStatus(VpnState.ERROR, message = message))
            if (!isAppVisible()) Notifications.showError(this, message)
            withContext(Dispatchers.Main) { stopIfLatest(startId) }
        }
    }

    /**
     * Runs [start] again after a pause, unless the tunnel was started or
     * stopped in any other way meanwhile ([generation] is no longer the last
     * start, or the user switched it off).
     */
    private fun retryStart(generation: Int, attempt: Int, start: suspend () -> Unit) {
        scope.launch(worker) {
            delay(if (attempt == 1) 1_500L else 5_000L)
            enqueue {
                if (generation != startGeneration || !RuntimeState.shouldRun(this@XrayVpnService)) return@enqueue
                AppLog.i("starting again (attempt ${attempt + 1})")
                start()
            }
        }
    }

    private fun establishTun(profile: StoredProfile, settings: AppSettings, userRequested: Boolean): ParcelFileDescriptor {
        // prepare() is not a query: with an earlier consent it takes the VPN
        // over from whichever app runs one. Only do that when asked to.
        if (userRequested && prepare(this) != null) {
            RuntimeState.setVpnConsented(this, false)
            throw VpnStartException("Нет разрешения на VPN. Откройте приложение и подключитесь оттуда.")
        }
        val tunCfg = XrayCore.tunConfig(settings.ipv6)
        val builder = Builder()
            .setSession(profile.name.ifBlank { "VPN" })
            .setMtu(tunCfg.mtu)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        for (address in tunCfg.addresses) {
            val (ip, prefix) = address.split('/')
            builder.addAddress(ip, prefix.toInt())
        }
        for (route in tunCfg.routes) {
            val (ip, prefix) = route.split('/')
            builder.addRoute(ip, prefix.toInt())
        }
        for (dns in tunCfg.dnsServers) builder.addDnsServer(dns)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // "Not metered" here means: inherit meteredness from Wi-Fi/mobile.
            builder.setMetered(false)
        }
        val bypassing = applyPerAppRules(builder, settings)
        // "Block connections without VPN" cuts off every app kept outside
        // the tunnel (banks, Gosuslugi): say so instead of failing silently.
        lockdownConflict = bypassing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled
        if (lockdownConflict) AppLog.w("lockdown is on while some apps bypass the VPN: they will have no network")
        (networkMonitor?.network ?: lastNetwork)?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        val pfd = builder.establish()
            ?: if (userRequested) {
                throw VpnStartException("Система не разрешила создать VPN. Возможно, включён другой постоянный VPN.")
            } else {
                throw AnotherVpnException()
            }
        RuntimeState.setVpnConsented(this, true)
        return pfd
    }

    /** Returns whether some other app ends up outside the tunnel. */
    private fun applyPerAppRules(builder: Builder, settings: AppSettings): Boolean {
        if (settings.appMode == AppMode.ONLY_SELECTED) {
            var added = 0
            for (pkg in settings.includedApps) {
                if (pkg == packageName) continue
                try {
                    builder.addAllowedApplication(pkg)
                    added++
                } catch (_: PackageManager.NameNotFoundException) {
                    // Uninstalled since it was chosen.
                }
            }
            if (added > 0) return true
            // No selected app is installed: fall back to "all apps", otherwise
            // Android would route everything, including this app, into the
            // tunnel and create a loop.
            AppLog.w("no selected apps installed, using all apps")
        }
        builder.addDisallowedApplication(packageName)
        val excluded = buildSet {
            addAll(settings.excludedApps)
            if (settings.bypassRussianApps) addAll(RussianApps.installed(packageManager))
        }
        var bypassing = false
        for (pkg in excluded) {
            if (pkg == packageName) continue
            try {
                builder.addDisallowedApplication(pkg)
                bypassing = true
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return bypassing
    }

    private fun prepareLogFile(): File {
        val dir = File(filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "xray.log")
        if (f.length() > 256 * 1024) {
            File(dir, "xray.log.1").delete()
            f.renameTo(File(dir, "xray.log.1"))
        }
        return f
    }

    // ------------------------------------------------------------------ stop

    private fun disconnect(userInitiated: Boolean, startId: Int) {
        if (userInitiated) RuntimeState.setShouldRun(this, false)
        setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
        enqueue {
            // A start that was still running has just finished; say again
            // that the tunnel is going down.
            if (VpnStatusHolder.status.value.state != VpnState.DISCONNECTING) {
                setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
            }
            stopCore()
            setStatus(VpnStatus(VpnState.DISCONNECTED))
            AppLog.i("tunnel down")
            withContext(Dispatchers.Main) { stopIfLatest(startId) }
        }
    }

    /** Stops the core first, then closes the TUN fd it was reading. */
    private fun stopCore() {
        haltCore()
        closeTun()
    }

    /**
     * Stops the core but keeps the TUN interface: until a new start (which
     * closes it), apps' traffic waits instead of leaving the VPN.
     */
    private fun haltCore() {
        resetJob?.cancel()
        liveController = null
        runningProfile = null
        newEpoch()
        try {
            controller?.stop()
        } catch (e: Exception) {
            AppLog.w("core stop", e)
        }
        config = null
    }

    private fun closeTun() {
        closeQuietly(tun)
        tun = null
    }

    private fun closeQuietly(pfd: ParcelFileDescriptor?) {
        try {
            pfd?.close()
        } catch (e: Exception) {
            AppLog.w("tun close", e)
        }
    }

    private fun leaveForeground() {
        networkMonitor?.stop()
        networkMonitor = null
        lastNetwork = null
        unregisterUnlock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // --------------------------------------------------------- network change

    private fun startNetworkMonitor() {
        if (networkMonitor != null) return
        val monitor = UnderlyingNetworkMonitor(this) { network -> onUnderlyingNetworkChanged(network) }
        monitor.start()
        lastNetwork = monitor.network
        networkMonitor = monitor
        registerUnlock()
    }

    private fun registerUnlock() {
        if (unlockRegistered) return
        try {
            // Exported, unlike a receiver for our own broadcasts: SystemUI,
            // not the system server, sends USER_PRESENT, and a not-exported
            // receiver never gets it. Only the system may send it at all
            // (a protected broadcast), and it only triggers a throttled check.
            ContextCompat.registerReceiver(this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED)
            unlockRegistered = true
        } catch (e: Exception) {
            AppLog.w("unlock receiver", e)
        }
    }

    private fun unregisterUnlock() {
        if (!unlockRegistered) return
        unlockRegistered = false
        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun onUnderlyingNetworkChanged(network: Network?) {
        // Lets Android attribute the tunnel to Wi-Fi/mobile (metered state,
        // "no internet" detection) correctly.
        setUnderlyingNetworks(network?.let { arrayOf(it) })
        val previous = lastNetwork
        if (network != null) lastNetwork = network
        // Whatever a check in progress measured belongs to the old network.
        newEpoch()
        if (network == null) {
            // Nothing to search with: do not claim to be searching.
            val e = epoch.get()
            scope.launch { setNotice(null, e, replacing = setOf(Failover.NOTICE_SEARCHING)) }
            return
        }
        // Another network may reach servers the last one could not.
        if (network != previous) lastFruitlessAt = 0
        // No reset for the first network since the tunnel came up (Always-on
        // at boot), the same one back after a gap, right after connecting or
        // with resets turned off: still worth a check.
        val reset = previous != null && network != previous && resetOnNetworkChange &&
            SystemClock.elapsedRealtime() - connectedAtElapsed >= MIN_UPTIME_FOR_RESET_MS
        if (!reset) {
            scheduleVerify(Reason.NETWORK, NETWORK_SETTLE_MS)
            return
        }
        // Connections opened over the old network are dead but would hang
        // until timeouts. Restarting the core resets them at once, so apps
        // (messengers, video) reconnect immediately over the new network.
        resetJob?.cancel()
        val startId = lastStartId
        resetJob = scope.launch(worker) {
            delay(NETWORK_SETTLE_MS)
            serial.withLock {
                val cfg = config ?: return@withLock
                val fd = tun ?: return@withLock
                val c = controller ?: return@withLock
                AppLog.i("network changed, resetting connections")
                try {
                    newEpoch()
                    c.stop()
                    c.start(cfg, fd.fd)
                    connectedAtElapsed = SystemClock.elapsedRealtime()
                    newEpoch()
                    scheduleVerify(Reason.NETWORK)
                } catch (e: Exception) {
                    AppLog.e("core restart after network change failed", e)
                    enqueue { if (RuntimeState.shouldRun(this@XrayVpnService)) startTunnel(startId, userRequested = false) }
                }
            }
        }
    }

    // --------------------------------------------------------------- failover

    /** A new core, network or a stop: checks and probes started before are discarded. */
    private fun newEpoch() {
        synchronized(verifyLock) {
            epoch.incrementAndGet()
            verifyJob?.cancel()
        }
    }

    private fun failedRecently(id: String): Boolean =
        recentlyFailed[id]?.let { SystemClock.elapsedRealtime() - it < Failover.RECENTLY_FAILED_MS } == true

    /**
     * Checks in the background that traffic gets through the tunnel, and
     * looks for another server if not. Never on [worker] and never under
     * [serial]: it takes seconds, and "off" must not wait for it. One at a
     * time; [delayMs] lets a new network settle.
     */
    private fun scheduleVerify(reason: Reason, delayMs: Long = 0) {
        if (config == null) return
        synchronized(verifyLock) {
            if (verifyJob?.isActive == true) return
            val e = epoch.get()
            verifyJob = scope.launch(Dispatchers.IO) {
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
        val c = liveController ?: return
        val running = runningProfile ?: return
        if (epoch.get() != e || VpnStatusHolder.status.value.state != VpnState.CONNECTED) return
        lastVerifyAt = SystemClock.elapsedRealtime()
        // One site failing is not enough: some servers cannot reach Google
        // but carry everything else. A failed manual test was the first try.
        val ok = (reason != Reason.USER && answers(c, XrayCore.TEST_URL, VERIFY_TIMEOUT_MS)) ||
            answers(c, XrayCore.TEST_URL_ALT, CONFIRM_TIMEOUT_MS)
        if (epoch.get() != e) return
        if (ok) {
            lastVerifiedOkAt = SystemClock.elapsedRealtime()
            clearFailureNotice(e)
            owedRefresh?.let { id ->
                owedRefresh = null
                refreshThroughTunnel(id, c)
            }
            return
        }
        AppLog.w("no traffic through the server (${reason.name.lowercase()})")
        runFailover(e, c, running, reason)
    }

    /** Whether [url] answers through the running tunnel. Blocking. */
    private fun answers(c: Controller, url: String, timeoutMs: Int): Boolean = try {
        c.measureDelay(url, timeoutMs) >= 0
    } catch (_: Exception) {
        false
    }

    /**
     * [failed] passes no traffic: probe other servers, all in one temporary
     * core, and switch to the fastest that answers. The tunnel stays as it
     * is meanwhile, and for good when nothing answers: nothing ever leaks
     * outside the VPN, and the user is told to check the internet.
     */
    private suspend fun runFailover(e: Long, c: Controller, failed: StoredProfile, reason: Reason) {
        if (!hasNetwork()) {
            AppLog.i("no network, not looking for another server")
            return
        }
        if (failed.id == manualPick && failedRecently(failed.id)) {
            setNotice(Failover.NOTICE_PICK_ANOTHER, e)
            return
        }
        if (reason != Reason.USER && SystemClock.elapsedRealtime() - lastFruitlessAt < FRUITLESS_RETRY_MS) {
            // Nothing answered moments ago: say so again, search later.
            setNotice(Failover.NOTICE_NONE_ANSWER, e)
            return
        }
        if (!RuntimeState.allowFailover(this, take = false)) {
            AppLog.w("automatic server switches used up for now")
            setNotice(Failover.NOTICE_PICK_ANOTHER, e)
            return
        }
        val now = SystemClock.elapsedRealtime()
        recentlyFailed.entries.removeIf { now - it.value >= Failover.RECENTLY_FAILED_MS }
        val exclude = recentlyFailed.keys.toSet()
        val state = Stores.profiles(this).read()
        // The panel may have moved the servers meanwhile (new addresses or keys).
        val refresh = startFailoverRefresh(state, failed)
        val first = pick(state, failed, exclude, tried = emptyList())
        if (first.isEmpty() && refresh == null) {
            AppLog.w("no other server to try")
            setNotice(Failover.NOTICE_NO_OTHER, e)
            return
        }
        setNotice(Failover.NOTICE_SEARCHING, e)
        AppLog.i("trying ${first.size} other servers")
        var probed = first.size
        var winner = probeBest(c, first)
        if (epoch.get() != e) return
        if (winner == null && refresh != null && refresh.await()) {
            // Only what the refresh brought: new servers, or new settings of known ones.
            val second = pick(Stores.profiles(this).read(), failed, exclude, tried = first.map { it.outbounds })
            if (second.isNotEmpty()) {
                AppLog.i("subscription refreshed, trying ${second.size} more servers")
                probed += second.size
                winner = probeBest(c, second)
            }
            if (epoch.get() != e) return
        }
        if (winner == null) {
            AppLog.w("no server answers")
            if (probed == 0) {
                setNotice(Failover.NOTICE_NO_OTHER, e)
            } else {
                lastFruitlessAt = SystemClock.elapsedRealtime()
                setNotice(Failover.NOTICE_NONE_ANSWER, e)
            }
            return
        }
        val (to, ms) = winner
        AppLog.i("switching to a server that answered in $ms ms")
        // The same server with new settings from the subscription is no failure.
        if (to.id != failed.id) recentlyFailed[failed.id] = SystemClock.elapsedRealtime()
        enqueue { switchTo(e, failed, to.id) }
    }

    /**
     * On [worker], under [serial]: moves the tunnel from [failed] to
     * [winnerId], unless something changed since the probe began: another
     * core or network (epoch), the VPN turned off, another server chosen.
     */
    private suspend fun switchTo(e: Long, failed: StoredProfile, winnerId: String) {
        try {
            if (epoch.get() != e || config == null || !RuntimeState.shouldRun(this) ||
                VpnStatusHolder.status.value.state != VpnState.CONNECTED
            ) {
                AppLog.i("server switch dropped: the tunnel changed meanwhile")
                return
            }
            val saved = Stores.profiles(this).read()
            val winner = saved.profiles.firstOrNull { it.id == winnerId }
            if (winner == null || !Failover.selectionFollowsFailed(saved, failed.id)) {
                // The user's choice wins; their reconnect follows.
                AppLog.i("server switch dropped: another server was chosen")
                clearFailureNotice(e)
                return
            }
            if (!RuntimeState.allowFailover(this)) {
                setNotice(Failover.NOTICE_PICK_ANOTHER, e)
                return
            }
            val notice = if (winner.id == failed.id) null else Failover.switchedNotice(winner.name, failed.name)
            startTunnel(
                lastStartId,
                userRequested = false,
                profileOverride = winner.id,
                failedId = failed.id,
                expectedSelection = saved.selectedId,
                notice = notice,
            )
            // Up on another server: the phone is online, so the failed one
            // does not answer from this network.
            if (winner.id != failed.id && runningProfile?.id == winner.id) reportBlocked(failed, saved)
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.w("server switch failed", ex)
        }
    }

    /**
     * Tells the owner's panel that [failed] stopped answering here, if its
     * subscription asked for that (see [BlockReporter]). In the background:
     * the switch never waits for it, and nothing it does can fail the tunnel.
     */
    private fun reportBlocked(failed: StoredProfile, state: ProfilesState) {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return
        if (sub.reportUrl == null) return
        val network = networkMonitor?.network ?: lastNetwork
        scope.launch(Dispatchers.IO) {
            try {
                blockReporter.report(failed, sub, network) { liveController }
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                AppLog.w("block report failed", ex)
            }
        }
    }

    /** The server the tunnel switched to becomes the selection, unless the user chose another meanwhile. */
    private fun saveSwitch(failedId: String, winnerId: String, expected: String?) {
        try {
            val saved = Stores.profiles(this).update { s -> Failover.selectInstead(s, failedId, winnerId, expected) }
            if (saved.selectedId == winnerId) {
                notifyProfilesChanged()
            } else {
                AppLog.i("another server was chosen meanwhile, selection kept")
            }
        } catch (e: Exception) {
            // The tunnel runs anyway; only the next start picks the old server.
            AppLog.w("could not save the new selection", e)
        }
    }

    /** Candidates for [failed]; on mobile data, servers on the Russian whitelist first. */
    private suspend fun pick(state: ProfilesState, failed: StoredProfile, exclude: Set<String>, tried: List<JsonArray>): List<StoredProfile> {
        if (!onMobileData()) return Failover.pickCandidates(state, failed, exclude, tried)
        // Under the mobile whitelist only servers on it get through: make
        // sure they are among those probed.
        val pool = Failover.pickCandidates(state, failed, exclude, tried, limit = Int.MAX_VALUE)
        val preferred = whitelisted(pool.map { Failover.host(it) }.distinct().take(WHITELIST_HOSTS))
        return Failover.pickCandidates(state, failed, exclude, tried, preferred)
    }

    /** Those of [hosts] on the Russian mobile whitelist; lookups that take too long count as not. */
    private suspend fun whitelisted(hosts: List<String>): Set<String> {
        val found = ConcurrentHashMap.newKeySet<String>()
        val limit = Semaphore(4)
        // In the service scope: a DNS lookup cannot be interrupted, and the
        // search must not wait for a slow one.
        val lookups = hosts.map { host ->
            scope.launch(Dispatchers.IO) {
                try {
                    limit.withPermit {
                        if (XrayCore.whitelistStatus(this@XrayVpnService, host) == 1) found.add(host)
                    }
                } catch (ex: Exception) {
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

    /** The fastest of [candidates] and its delay, or null when none answers. */
    private suspend fun probeBest(c: Controller, candidates: List<StoredProfile>): Pair<StoredProfile, Long>? {
        if (candidates.isEmpty()) return null
        val delays = probeLock.withLock {
            try {
                XrayCore.probe(c, candidates.map { it.outbounds }, timeoutMs = Failover.PROBE_TIMEOUT_MS, parallel = Failover.PARALLEL)
            } catch (ex: Exception) {
                AppLog.w("probe failed", ex)
                emptyList()
            }
        }
        return Failover.fastest(candidates, delays)
    }

    /**
     * Downloads [failed]'s subscription again, directly: the running server
     * is what fails. At most every 10 minutes and one at a time, on its own
     * so that a switch meanwhile does not cut it short. Its result: whether
     * it brought servers.
     */
    private fun startFailoverRefresh(state: ProfilesState, failed: StoredProfile): Deferred<Boolean>? {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return null
        if (refreshJob?.isActive == true || !Failover.refreshDue(sub, System.currentTimeMillis())) return null
        val direct = Downloader { url, headers -> XrayCore.fetch(url, null, headers, REFRESH_TIMEOUT_MS) }
        return scope.async(Dispatchers.IO) {
            val applied = refreshSubscription(sub.id, direct)
            // Blocked outside the tunnel: once a server works, through it.
            if (applied == null) owedRefresh = sub.id
            applied == true
        }.also { refreshJob = it }
    }

    private fun refreshThroughTunnel(subId: String, c: Controller) {
        if (refreshJob?.isActive == true) return
        val tunnel = Downloader { url, headers -> XrayCore.fetchThroughTunnel(c, url, headers) }
        refreshJob = scope.async(Dispatchers.IO) { refreshSubscription(subId, tunnel) == true }
    }

    /**
     * Downloads subscription [subId] and saves the result; the old servers
     * stay when the panel sends none. True: new servers, false: none, null:
     * the download failed. The app, if open, reloads. Never throws.
     */
    private suspend fun refreshSubscription(subId: String, downloader: Downloader): Boolean? = try {
        SubscriptionUpdater(this, DiskProfiles(this)).refresh(subId, downloader, runningId = runningProfileId)?.applied ?: false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w("subscription refresh failed: ${e.userMessage()}")
        null
    } finally {
        notifyProfilesChanged()
    }

    /** The network under the tunnel (this app's own traffic never enters it). */
    private fun underlying(): NetworkCapabilities? = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    } catch (_: Exception) {
        null
    }

    private fun hasNetwork(): Boolean = underlying() != null

    private fun onMobileData(): Boolean = underlying()?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    /**
     * Shows [notice] (null clears it) while connected, unless the tunnel
     * changed since [e]; with [replacing], only in place of one of those.
     * On the main thread with a compare-and-set, so it never overwrites a
     * newer status.
     */
    private suspend fun setNotice(notice: String?, e: Long, replacing: Set<String>? = null) {
        try {
            withContext(Dispatchers.Main) {
                if (epoch.get() != e) return@withContext
                val s = VpnStatusHolder.status.value
                val current = s.message
                if (s.state != VpnState.CONNECTED || current == notice) return@withContext
                if (replacing != null && (current == null || current !in replacing)) return@withContext
                val next = s.copy(message = notice)
                if (!VpnStatusHolder.compareAndSet(s, next)) return@withContext
                publishStatus(next)
                publishConnected()
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.w("notice update failed", ex)
        }
    }

    /** Traffic gets through (again): "not answering" is no longer true. A switch notice stays. */
    private suspend fun clearFailureNotice(e: Long) = setNotice(null, e, replacing = Failover.FAILURE_NOTICES)

    // ------------------------------------------------------ status & traffic

    private fun enterForeground(title: String, text: String?) {
        val n = Notifications.status(this, title, text, withDisconnect = true)
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_STATUS, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            // Can only happen for a system restart in the background; the
            // tunnel still works, it just has no notification.
            AppLog.w("startForeground refused", e)
        }
    }

    private suspend fun publishConnected() {
        val s = VpnStatusHolder.status.value
        if (s.state != VpnState.CONNECTED) return
        val text = when {
            lockdownConflict ->
                "Включено «Блокировать соединения без VPN»: приложения без VPN (банки, Госуслуги) останутся без интернета"
            // A notice such as "switched to another server".
            !s.message.isNullOrBlank() -> s.message
            else -> s.profileName
        }
        withContext(Dispatchers.Main) { enterForeground("Подключено", text) }
    }

    /** The saved servers or the selection changed here: the app (if open) and the widget reload them. */
    private fun notifyProfilesChanged() {
        VpnWidget.update(this)
        scope.launch {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    try {
                        callbacks.getBroadcastItem(i).onProfilesChanged()
                    } catch (_: Exception) {
                        // Dead callbacks are removed by RemoteCallbackList.
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun setStatus(status: VpnStatus) {
        VpnStatusHolder.set(status)
        publishStatus(status)
    }

    /** Shows [status] in the widget and the app, if open. */
    private fun publishStatus(status: VpnStatus) {
        VpnWidget.update(this)
        scope.launch {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) sendStatus(callbacks.getBroadcastItem(i), status)
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun sendStatus(cb: IVpnCallback, s: VpnStatus) {
        try {
            cb.onStatus(s.state.code, s.profileId, s.profileName, s.message, s.connectedSince)
        } catch (_: Exception) {
            // Dead callbacks are removed by RemoteCallbackList.
        }
    }

    private fun isAppVisible(): Boolean = callbacks.registeredCallbackCount > 0
}

private class VpnStartException(message: String) : Exception(message)

/** What made the service check that traffic gets through. */
private enum class Reason { START, NETWORK, UNLOCK, APP, USER }

/** An automatic restart found another app's VPN in place. */
private class AnotherVpnException : Exception("another VPN is active")

/**
 * Small state private to the VPN process: whether the tunnel should be up
 * (for restarts after the process was killed), a crash-loop guard and the
 * budget of automatic server switches.
 */
internal object RuntimeState {
    private const val PREFS = "vpn_runtime"

    fun shouldRun(context: Context) = prefs(context).getBoolean("should_run", false)

    fun setShouldRun(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean("should_run", value) }
    }

    /**
     * Whether the user has allowed this VPN (a tunnel came up once). Lets the
     * widget connect directly without calling VpnService.prepare(), which is
     * not a query: with an earlier consent it takes the VPN over from
     * whichever app is running one.
     */
    fun vpnConsented(context: Context) = prefs(context).getBoolean("vpn_consented", false)

    fun setVpnConsented(context: Context, value: Boolean) {
        if (vpnConsented(context) != value) prefs(context).edit { putBoolean("vpn_consented", value) }
    }

    /**
     * Whether an automatic switch to another server is allowed now (see
     * [Failover.MAX_SWITCHES]); [take] counts one. Kept on disk so a
     * restarted process cannot start over; by time since boot, so a clock
     * change cannot either.
     */
    fun allowFailover(context: Context, take: Boolean = true): Boolean {
        val p = prefs(context)
        val next = Failover.countSwitch(p.getString("failovers", "") ?: "", SystemClock.elapsedRealtime()) ?: return false
        if (take) p.edit { putString("failovers", next) }
        return true
    }

    /** Allows at most 3 automatic restarts within 5 minutes. */
    fun allowAutoRestart(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val recent = (p.getString("restarts", "") ?: "").split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < 5 * 60_000 }
        if (recent.size >= 3) return false
        p.edit { putString("restarts", (recent + now).joinToString(",")) }
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
