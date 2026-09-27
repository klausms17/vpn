package com.klausms.vpn.service

import android.app.ActivityManager
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
import android.os.PowerManager
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
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.data.SubscriptionUpdater
import com.klausms.vpn.ui.MainActivity
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.PhoneSettings
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

        /** While the screen is on, a check runs when none ran for this long. */
        private const val SCREEN_CHECK_MS = 5 * 60_000L

        /** Android's view of the network changing checks at most this often. */
        private const val LINK_CHECK_MS = 60_000L

        /** After a search found nothing, the next one on that network waits this long (unless asked for). */
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
         * Run on connect and a manual check, and otherwise at most this
         * often per network (a new network gets one at its first check).
         */
        private const val BULK_URL = "https://speed.cloudflare.com/__down?bytes=65536"
        private const val BULK_HEADERS = """{"Accept-Encoding":"identity"}"""
        private const val BULK_TIMEOUT_MS = 12_000
        private const val BULK_CHECK_MS = 30 * 60_000L

        /**
         * A Russian site, opened outside the tunnel: tells "servers blocked"
         * from "no internet", and the mobile whitelist from a block. A small
         * file, not the page: this can run every few minutes while nothing
         * answers.
         */
        private const val DIRECT_URL = "https://ya.ru/robots.txt"
        private const val DIRECT_TIMEOUT_MS = 5_000

        /** Hosts looked up in the mobile whitelist per search, and how long to wait for them. */
        private const val WHITELIST_HOSTS = 32
        private const val WHITELIST_WAIT_MS = 3_000L

        /** The running core, for the widget's ping (same process). */
        @Volatile
        var liveController: Controller? = null
            private set

        /**
         * Delay through the running core [c] in ms: Google first, then
         * Cloudflare, since some servers cannot reach Google but carry
         * everything else. Blocking; throws when neither answers.
         */
        fun measureThrough(c: Controller, timeoutMs: Int): Long {
            val ms = try {
                c.measureDelay(XrayCore.TEST_URL, timeoutMs)
            } catch (_: Exception) {
                -1L
            }
            return if (ms >= 0) ms else c.measureDelay(XrayCore.TEST_URL_ALT, CONFIRM_TIMEOUT_MS)
        }
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

    /** The newest reconnect; older ones still queued are skipped. */
    @Volatile
    private var lastReconnectId = 0

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

    // Per network: when a download check there last showed no stall
    // (elapsedRealtime). Per network, so a phone going back and forth between
    // Wi-Fi and mobile data does not download on every change: mobile data
    // keeps its network across Wi-Fi gaps. Guarded by itself.
    private val bulkFine = HashMap<Network?, Long>()

    // elapsedRealtime of the last check started because Android's view of the network changed.
    @Volatile
    private var lastLinkCheckAt = 0L

    // elapsedRealtime of the last core restart for a server that answered again.
    @Volatile
    private var lastCoreRestartAt = 0L

    // Per network: when a search there last found nothing, and the notice it
    // showed. Another network may reach servers this one could not, and the
    // same network back (Wi-Fi flapping) remembers. Guarded by itself.
    private val fruitless = HashMap<Network?, Pair<Long, String>>()

    @Volatile
    private var verifyJob: Job? = null
    private val verifyLock = Any()

    // One probe at a time: a probe's temporary core holds megabytes until it
    // ends, also when its result is no longer wanted.
    private val probeLock = Mutex()

    // Server id -> elapsedRealtime it stopped answering; skipped as a
    // candidate for [Failover.RECENTLY_FAILED_MS].
    private val recentlyFailed = ConcurrentHashMap<String, Long>()

    // Host -> on the Russian mobile whitelist. Kept while the process lives:
    // few hosts, and the answer rarely changes.
    private val whitelistCache = ConcurrentHashMap<String, Boolean>()

    // A server the user chose again right after it had been switched away
    // from: their choice wins, it is not switched away from automatically.
    @Volatile
    private var manualPick: String? = null

    // A reconnect asked for with EXTRA_PICKED; merged reconnects keep it.
    @Volatile
    private var pickPending = false

    // The "switched to another server" notice on screen, and since when (elapsedRealtime).
    @Volatile
    private var switchNotice: String? = null

    @Volatile
    private var switchNoticeAt = 0L

    // A hint shown whenever no other notice is (strict Private DNS).
    @Volatile
    private var baseNotice: String? = null

    @Volatile
    private var refreshJob: Deferred<Refreshed>? = null

    // A subscription whose direct download failed during a search: it is
    // downloaded through the tunnel once a server works again.
    @Volatile
    private var owedRefresh: String? = null

    private val blockReporter by lazy { BlockReporter(this) }

    private var screenRegistered = false

    // Checks every few minutes while the screen is on; none while it is off.
    private var screenJob: Job? = null

    // Unlocking is when the phone is about to be used: a cheap moment to
    // find out that the server stopped answering while it was locked. While
    // the screen stays on, a server blocked mid-session is found by the
    // checks every few minutes.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT ->
                    if (SystemClock.elapsedRealtime() - lastVerifyAt >= UNLOCK_CHECK_MS) scheduleVerify(Reason.UNLOCK)
                Intent.ACTION_SCREEN_ON -> startScreenChecks()
                Intent.ACTION_SCREEN_OFF -> stopScreenChecks()
            }
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
            // Should run, but nothing started it: after an update on phones
            // that hold the update broadcast back (MIUI). With the app on
            // screen it may start now; a start already on its way makes
            // this one do nothing.
            if (config == null && VpnStatusHolder.status.value.state == VpnState.DISCONNECTED) {
                VpnCommands.resume(this@XrayVpnService)
            }
        }

        override fun unregisterCallback(callback: IVpnCallback?) {
            callback ?: return
            callbacks.unregister(callback)
        }

        override fun testConnection(): Long {
            val c = controller ?: return -1
            // The same two sites as the check, so the answer never contradicts it.
            val ms = try {
                measureThrough(c, 10_000)
            } catch (e: Exception) {
                AppLog.w("connection test failed", e)
                -1L
            }
            if (ms < 0) {
                // The user saw it fail: look for a server that answers.
                scheduleVerify(Reason.USER)
            } else if (config != null && bulkDue(Reason.APP, activeNetwork())) {
                // A quick answer says nothing about downloads that stall
                // (and must not clear a notice about them): the full check,
                // with its download, decides.
                scheduleVerify(Reason.APP)
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
                // A running tunnel keeps working while new settings apply.
                enterForegroundUnlessUp("Переподключение…")
                lastReconnectId = startId
                if (intent.getBooleanExtra(EXTRA_PICKED, false)) pickPending = true
                AppLog.i("reconnect asked (#$startId)")
                enqueue {
                    when {
                        // A newer reconnect follows and brings the newest settings.
                        startId != lastReconnectId -> Unit
                        // A late "apply new settings" must not switch on a
                        // VPN the user has turned off meanwhile.
                        config == null && !RuntimeState.shouldRun(this) -> {
                            pickPending = false
                            withContext(Dispatchers.Main) { stopIfLatest(startId) }
                        }
                        else -> {
                            val picked = pickPending
                            pickPending = false
                            startTunnel(startId, userRequested = true, picked = picked)
                        }
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
                if (restart && !RuntimeState.shouldRun(this)) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                if (restart && !RuntimeState.allowAutoRestart(this)) {
                    AppLog.e("tunnel keeps crashing, giving up automatic restarts")
                    Notifications.showError(this, "VPN несколько раз аварийно остановился и больше не перезапускается автоматически. Откройте приложение.")
                    RuntimeState.setShouldRun(this, false)
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
                        RuntimeState.setShouldRun(this, true)
                    }
                }
                // Also sent to a tunnel that is up (the app connects whatever
                // its cached status says): it stays up and says so.
                enterForegroundUnlessUp("Подключение…")
                // Only the app, the tile and the widget ask for a start just
                // now. Restarts, resumes and Always-on bring back a tunnel
                // that should run: they never call prepare() (it would take
                // the VPN over from another app) and are tried again on failure.
                val requested = intent?.action == ACTION_CONNECT
                val picked = intent?.getBooleanExtra(EXTRA_PICKED, false) == true
                enqueue {
                    when {
                        config != null -> {
                            publishConnected()
                            // "Connect" while connected: maybe it does not work.
                            if (SystemClock.elapsedRealtime() - lastVerifiedOkAt >= APP_CHECK_MS) scheduleVerify(Reason.APP)
                        }
                        // Turned off after the resume was sent.
                        resume && !RuntimeState.shouldRun(this) -> withContext(Dispatchers.Main) { stopIfLatest(startId) }
                        else -> startTunnel(startId, requested, picked = picked)
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
        networkMonitor?.stop()
        networkMonitor = null
        unregisterScreen()
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
     * once connected. [picked]: the user has just chosen this server.
     */
    private suspend fun startTunnel(
        startId: Int,
        userRequested: Boolean,
        profileOverride: String? = null,
        failedId: String? = null,
        expectedSelection: String? = null,
        notice: String? = null,
        attempt: Int = 0,
        picked: Boolean = false,
    ) {
        // Also while a failed start waits to be retried with the interface
        // held: that is a tunnel that should run, not a first start.
        val restarting = config != null || tun != null
        val generation = ++startGeneration
        val before = VpnStatusHolder.status.value
        // From here on the new interface has replaced the old one.
        var swapped = false
        // New settings for a running tunnel: it keeps working meanwhile, so
        // it still shows as on (a tap on a "connecting" button would cancel).
        if (restarting && before.state == VpnState.CONNECTED) {
            setStatus(before.copy(message = "Применяем изменения…"))
        } else {
            setStatus(VpnStatus(VpnState.CONNECTING, profileName = before.profileName))
        }
        try {
            val profiles = Stores.profiles(this).read()
            val profile = profileOverride?.let { id -> profiles.profiles.firstOrNull { it.id == id } }
                ?: profiles.selected
                ?: throw VpnStartException("Не выбран сервер. Добавьте ключ в приложении.")
            val settings = Stores.settings(this).read()
            if (VpnStatusHolder.status.value.state == VpnState.CONNECTING) setStatus(VpnStatus(VpnState.CONNECTING, profile.id, profile.name))

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
            manualPick = when {
                // An automatic switch.
                profileOverride != null -> null
                // Picked by hand again after it had stopped answering: the user knows.
                picked && failedRecently(profile.id) -> profile.id
                // The same server with new settings: the choice still stands.
                manualPick == profile.id -> manualPick
                else -> null
            }
            resetOnNetworkChange = settings.resetOnNetworkChange
            connectedAtElapsed = SystemClock.elapsedRealtime()
            RuntimeState.setShouldRun(this, true)
            withContext(Dispatchers.Main) { startNetworkMonitor() }
            // Only a server whose core came up becomes the selection.
            if (profileOverride != null && failedId != null) {
                if (saveSwitch(failedId, profile.id, expectedSelection)) trackAway(failedId, profile.id)
            } else {
                forgetAwayUnless(profile.id, picked)
            }
            // The session timer goes on when only the settings changed.
            val since = before.connectedSince.takeIf { restarting && it > 0 && before.profileId == profile.id }
                ?: System.currentTimeMillis()
            switchNotice = notice
            switchNoticeAt = SystemClock.elapsedRealtime()
            setStatus(VpnStatus(VpnState.CONNECTED, profile.id, profile.name, message = notice ?: baseNotice, connectedSince = since))
            Notifications.clearError(this)
            AppLog.i("tunnel up: ${profile.protocol}/${profile.network}/${profile.security}, mode ${settings.mode.core}; ${PhoneSettings.vpnSummary(this)}")
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
                    startTunnel(lastStartId, userRequested = false, profileOverride, failedId, expectedSelection, notice, attempt = next, picked = picked)
                }
            }
            // New settings or another server for a tunnel that works: until
            // the new interface replaced it, the old tunnel still carries the
            // traffic. It keeps running; one more try a little later.
            if (restarting && !swapped && config != null && liveController != null) {
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
                withContext(Dispatchers.Main) { enterForeground("Переподключение…", null) }
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
        val f = File(File(filesDir, "logs").apply { mkdirs() }, "xray.log")
        XrayLog.trim(f)
        return f
    }

    // ------------------------------------------------------------------ stop

    /** [message]: why, when it was not the user (shown under "Отключено"). */
    private fun disconnect(userInitiated: Boolean, startId: Int, message: String? = null) {
        if (userInitiated) RuntimeState.setShouldRun(this, false)
        setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
        enqueue {
            // A start that was still running has just finished; say again
            // that the tunnel is going down.
            if (VpnStatusHolder.status.value.state != VpnState.DISCONNECTING) {
                setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
            }
            stopCore()
            // A start that finished just before this set it again.
            if (userInitiated) RuntimeState.setShouldRun(this, false)
            setStatus(VpnStatus(VpnState.DISCONNECTED, message = message))
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
        baseNotice = null
        unregisterScreen()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // --------------------------------------------------------- network change

    private fun startNetworkMonitor() {
        if (networkMonitor != null) return
        val monitor = UnderlyingNetworkMonitor(
            this,
            onChanged = { network -> onUnderlyingNetworkChanged(network) },
            onReachability = { lost -> onReachabilityChanged(lost) },
            onPrivateDns = { host -> onPrivateDnsChanged(host) },
        )
        monitor.start()
        lastNetwork = monitor.network
        networkMonitor = monitor
        registerScreen()
    }

    private fun registerScreen() {
        if (screenRegistered) return
        try {
            // Exported, unlike a receiver for our own broadcasts: SystemUI,
            // not the system server, sends USER_PRESENT, and a not-exported
            // receiver never gets it. Only the system may send these at all
            // (protected broadcasts), and they only trigger throttled checks.
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            screenRegistered = true
        } catch (e: Exception) {
            AppLog.w("screen receiver", e)
        }
        if (getSystemService(PowerManager::class.java)?.isInteractive == true) startScreenChecks()
    }

    private fun unregisterScreen() {
        stopScreenChecks()
        if (!screenRegistered) return
        screenRegistered = false
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * While the screen is on, a server blocked mid-session must not go
     * unnoticed until the next unlock: check when none ran for a while. On
     * the main thread; the radio is in use anyway while the phone is.
     */
    private fun startScreenChecks() {
        if (screenJob?.isActive == true) return
        screenJob = scope.launch {
            while (true) {
                delay(SCREEN_CHECK_MS)
                if (SystemClock.elapsedRealtime() - lastVerifyAt >= SCREEN_CHECK_MS) scheduleVerify(Reason.SCREEN)
            }
        }
    }

    private fun stopScreenChecks() {
        screenJob?.cancel()
        screenJob = null
    }

    /**
     * Android no longer sees internet on the same network ([lost]: often the
     * first sign of a blocked server or of a mobile whitelist switched on),
     * or it came back (a Wi-Fi login, the end of a pause): check again, at
     * most once a minute. Coming back only matters when the last check failed.
     */
    private fun onReachabilityChanged(lost: Boolean) {
        if (!lost && lastVerifiedOkAt >= lastVerifyAt) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLinkCheckAt < LINK_CHECK_MS) return
        lastLinkCheckAt = now
        scheduleVerify(Reason.LINK, NETWORK_SETTLE_MS)
    }

    /**
     * A strict «Частный DNS» (a host name) also takes over the DNS of VPN
     * apps on Android 10+: the phone's lookups then go around the core's
     * DNS rules, and fail with the server. Only the user can change it, so
     * say so. The host may name a personal account: never logged.
     */
    private fun onPrivateDnsChanged(host: String?) {
        val hint = if (host.isNullOrBlank()) null else Failover.NOTICE_PRIVATE_DNS
        val old = baseNotice
        if (hint == old) return
        if (hint != null) AppLog.w("strict private DNS is on: lookups bypass the tunnel's DNS rules")
        baseNotice = hint
        val e = epoch.get()
        scope.launch { setNotice(hint, e, replacing = setOf(null, old)) }
    }

    private fun onUnderlyingNetworkChanged(network: Network?) {
        // Lets Android attribute the tunnel to Wi-Fi/mobile (metered state,
        // "no internet" detection) correctly.
        setUnderlyingNetworks(network?.let { arrayOf(it) })
        val previous = lastNetwork
        if (network != null) lastNetwork = network
        // Whatever a check in progress measured belongs to the old network.
        newEpoch()
        val e = epoch.get()
        if (network == null) {
            // Nothing to search with: do not claim to be searching.
            scope.launch { setNotice(baseNotice, e, replacing = setOf(Failover.NOTICE_SEARCHING)) }
            return
        }
        // Another network: why the server was switched no longer applies.
        if (network != previous) clearSwitchNotice(e, minAgeMs = 0)
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
        restartCore("network changed, resetting connections", NETWORK_SETTLE_MS)
    }

    /**
     * Restarts the running core on the same server and settings: every
     * connection is reset at once. [expectedEpoch]: only if nothing changed
     * since. The TUN interface stays, so no traffic leaves the VPN meanwhile.
     */
    private fun restartCore(why: String, delayMs: Long = 0, expectedEpoch: Long? = null) {
        resetJob?.cancel()
        val startId = lastStartId
        resetJob = scope.launch(worker) {
            if (delayMs > 0) delay(delayMs)
            serial.withLock {
                if (expectedEpoch != null && epoch.get() != expectedEpoch) return@withLock
                val cfg = config ?: return@withLock
                val fd = tun ?: return@withLock
                val c = controller ?: return@withLock
                AppLog.i(why)
                try {
                    newEpoch()
                    c.stop()
                    // A tunnel that only ever resets never goes through
                    // startTunnel: its log is kept small here too.
                    XrayLog.trim(File(File(filesDir, "logs"), "xray.log"))
                    c.start(cfg, fd.fd)
                    connectedAtElapsed = SystemClock.elapsedRealtime()
                    newEpoch()
                    scheduleVerify(Reason.NETWORK)
                } catch (e: Exception) {
                    AppLog.e("core restart failed", e)
                    // No core runs now: the restart must not count on the old one.
                    liveController = null
                    runningProfile = null
                    config = null
                    newEpoch()
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
        // The log grows for as long as the tunnel runs; checks come often enough to keep it small.
        XrayLog.trim(File(File(filesDir, "logs"), "xray.log"))
        val c = liveController ?: return
        val running = runningProfile ?: return
        if (epoch.get() != e || VpnStatusHolder.status.value.state != VpnState.CONNECTED) return
        lastVerifyAt = SystemClock.elapsedRealtime()
        // One site failing is not enough: some servers cannot reach Google
        // but carry everything else. A failed manual test was the first try.
        val ok = (reason != Reason.USER && answers(c, XrayCore.TEST_URL, VERIFY_TIMEOUT_MS)) ||
            answers(c, XrayCore.TEST_URL_ALT, CONFIRM_TIMEOUT_MS)
        if (epoch.get() != e) return
        if (!ok) {
            AppLog.w("no traffic through the server (${reason.name.lowercase()})")
            runFailover(e, c, running, reason, stalled = false)
            return
        }
        // It answers, but some operators freeze a foreign server's
        // connections after the first ~16 KB: pages and video then hang
        // while short answers still get through.
        val network = activeNetwork()
        if (bulkDue(reason, network) && stalls(c, network)) {
            if (epoch.get() != e) return
            AppLog.w("downloads through the server stall (${reason.name.lowercase()})")
            runFailover(e, c, running, reason, stalled = true)
            return
        }
        if (epoch.get() != e) return
        lastVerifiedOkAt = SystemClock.elapsedRealtime()
        clearFailureNotice(e)
        clearSwitchNotice(e, minAgeMs = SWITCH_NOTICE_MS)
        owedRefresh?.let { id ->
            owedRefresh = null
            refreshThroughTunnel(id, c, running)
        }
        // Moments when connections start over anyway.
        if (reason == Reason.NETWORK || reason == Reason.UNLOCK) returnHomeIfItAnswers(e, c, running)
    }

    /** Whether [url] answers through the running tunnel. Blocking. */
    private fun answers(c: Controller, url: String, timeoutMs: Int): Boolean = try {
        c.measureDelay(url, timeoutMs) >= 0
    } catch (_: Exception) {
        false
    }

    /**
     * Whether the download check is due: on connect and when the user asks,
     * otherwise when none showed downloads working on [network] in the last
     * half hour (a new network, or a stall seen before).
     */
    private fun bulkDue(reason: Reason, network: Network?): Boolean {
        if (reason == Reason.START || reason == Reason.USER) return true
        val now = SystemClock.elapsedRealtime()
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
    private fun stalls(c: Controller, network: Network?): Boolean {
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
                synchronized(bulkFine) { bulkFine[network] = SystemClock.elapsedRealtime() }
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
    private suspend fun runFailover(e: Long, c: Controller, failed: StoredProfile, reason: Reason, stalled: Boolean) {
        val caps = underlying()
        if (!hasNetwork(caps)) {
            AppLog.i("no network, not looking for another server")
            return
        }
        // Hotel or metro Wi-Fi before its login page: no server can answer yet.
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true) {
            AppLog.i("the Wi-Fi asks to sign in, not looking for another server")
            setNotice(Failover.NOTICE_SIGN_IN, e)
            return
        }
        if (failed.id == manualPick && failedRecently(failed.id)) {
            setNotice(Failover.NOTICE_PICK_ANOTHER, e)
            return
        }
        val network = activeNetwork()
        if (reason != Reason.USER) {
            // Nothing answered on this network moments ago: say so again, search later.
            val last = fruitlessNotice(network)
            if (last != null) {
                setNotice(last, e)
                return
            }
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
        val first = pick(state, failed, exclude, tried = emptyList())
        // The panel may have moved the servers meanwhile (new addresses or keys).
        val sub = refreshableSubscription(state, failed)
        if (first.isNotEmpty() || sub != null) {
            // Probing servers and downloading the list is what costs.
            if (reason != Reason.USER && !RuntimeState.allowSearch(this)) {
                AppLog.w("automatic searches used up for now")
                setNotice(Failover.NOTICE_PICK_ANOTHER, e)
                return
            }
            setNotice(Failover.NOTICE_SEARCHING, e)
            AppLog.i("trying ${first.size} other servers")
        }
        val refresh = sub?.let { startFailoverRefresh(it) }
        // The failed server goes first, as a control: when it answers here
        // too, the phone was offline for a moment (a lift, a tunnel) and the
        // server is fine. Not for a stall, which a short answer never shows.
        val control = !stalled
        val delays = probe(c, (if (control) listOf(failed) else emptyList()) + first)
        if (epoch.get() != e) return
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
                val second = pick(Stores.profiles(this).read(), failed, exclude, tried = first.map { it.outbounds })
                if (second.isNotEmpty()) {
                    AppLog.i("subscription refreshed, trying ${second.size} more servers")
                    probed += second.size
                    winner = Failover.fastest(second, probe(c, second), tier)
                }
            }
            if (epoch.get() != e) return
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
        enqueue { switchTo(e, failed, to.id, report = !reachable) }
    }

    /**
     * The failed server answered in a new connection. If it now answers
     * through the running core too, the connection was lost for a moment:
     * stay. If not, the running core is stuck: restart it on the same
     * server, at most every 10 minutes. False: treat it as a real failure.
     * Google first, as the probe that answered, then Cloudflare.
     */
    private suspend fun recoveredInPlace(e: Long, c: Controller): Boolean {
        if (answers(c, XrayCore.TEST_URL, CONFIRM_TIMEOUT_MS) || answers(c, XrayCore.TEST_URL_ALT, CONFIRM_TIMEOUT_MS)) {
            if (epoch.get() != e) return true
            AppLog.i("the server answers again: the connection was lost for a moment")
            lastVerifiedOkAt = SystemClock.elapsedRealtime()
            clearFailureNotice(e)
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastCoreRestartAt < CORE_RESTART_GAP_MS) return false
        lastCoreRestartAt = now
        restartCore("the server answers, but not through the running core: restarting it", expectedEpoch = e)
        return true
    }

    /**
     * No other server answers either (or there is none). A Russian site
     * opened directly, outside the tunnel, tells a block from a phone
     * without internet: only a block is reported to the owner, and only
     * with [report] (false: [failed] itself answered a new connection).
     */
    private suspend fun nothingAnswers(e: Long, failed: StoredProfile, state: ProfilesState, probed: Int, network: Network?, report: Boolean) {
        val online = opensDirectly(DIRECT_URL)
        if (epoch.get() != e) return
        val notice = when {
            online && probed == 0 -> Failover.NOTICE_BLOCKED
            online -> Failover.NOTICE_ALL_BLOCKED
            probed == 0 -> Failover.NOTICE_NO_OTHER
            else -> Failover.NOTICE_NONE_ANSWER
        }
        if (online) {
            AppLog.w("the phone is online, but no server answers from this network")
            if (report) reportBlocked(failed, state, allDown = true)
        }
        markFruitless(network, notice)
        setNotice(notice, e)
    }

    /** Whether [url] answers outside the tunnel (this app's own traffic never enters it). No app name is sent. Blocking. */
    private fun opensDirectly(url: String): Boolean = try {
        Libxray.fetchWithHeaders(url, "", "", DIRECT_TIMEOUT_MS, "")
        true
    } catch (ex: Exception) {
        // Any answer at all (a captcha, a refusal) proves the site can be reached.
        BlockReport.httpStatus(ex.message) != null
    }

    /** The notice of a search that found nothing on [network] in the last minutes, or null. */
    private fun fruitlessNotice(network: Network?): String? = synchronized(fruitless) {
        val now = SystemClock.elapsedRealtime()
        fruitless.entries.removeIf { now - it.value.first >= FRUITLESS_RETRY_MS || it.value.first > now }
        fruitless[network]?.second
    }

    private fun markFruitless(network: Network?, notice: String) {
        synchronized(fruitless) { fruitless[network] = SystemClock.elapsedRealtime() to notice }
    }

    /**
     * Runs the saved selection again, unless [running] no longer runs
     * (another start came first) or the tunnel was turned off. Not tied to
     * the check's epoch: a network change during the download resets
     * connections but leaves the removed server running.
     */
    private fun restartOnSaved(running: StoredProfile) {
        enqueue {
            if (config == null || !RuntimeState.shouldRun(this) || runningProfile !== running) return@enqueue
            AppLog.i("the running server changed in the subscription, restarting")
            startTunnel(lastStartId, userRequested = false)
        }
    }

    /**
     * On [worker], under [serial]: moves the tunnel from [failed] to
     * [winnerId], unless something changed since the probe began: another
     * core or network (epoch), the VPN turned off, another server chosen.
     * [returning]: back to the user's server, which is no failure of [failed].
     * [report]: tell the owner's panel that [failed] does not answer here.
     */
    private suspend fun switchTo(e: Long, failed: StoredProfile, winnerId: String, returning: Boolean = false, report: Boolean = true) {
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
                if (!returning) setNotice(Failover.NOTICE_PICK_ANOTHER, e)
                return
            }
            val notice = if (returning || winner.id == failed.id) null else Failover.switchedNotice(winner.name, failed.name)
            startTunnel(
                lastStartId,
                userRequested = false,
                profileOverride = winner.id,
                failedId = failed.id,
                expectedSelection = saved.selectedId,
                notice = notice,
            )
            // Only a switch that happened marks the failed server: one dropped
            // on the way (a reconnect came first) proves nothing about it.
            if (!returning && winner.id != failed.id && runningProfile?.id == winner.id) {
                recentlyFailed[failed.id] = SystemClock.elapsedRealtime()
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
     * [winner]: the server the tunnel switched to.
     */
    private fun reportBlocked(failed: StoredProfile, state: ProfilesState, allDown: Boolean = false, winner: StoredProfile? = null) {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return
        if (sub.reportUrl == null) return
        val network = networkMonitor?.network ?: lastNetwork
        scope.launch(Dispatchers.IO) {
            try {
                val whitelist = winner != null && looksLikeWhitelist(failed, winner)
                blockReporter.report(failed, sub, network, allDown, whitelist) { liveController }
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
            !opensDirectly(XrayCore.TEST_URL) && opensDirectly(DIRECT_URL)
    }

    /**
     * The server the tunnel switched to becomes the selection, unless the
     * user chose another meanwhile. Returns whether it did.
     */
    private fun saveSwitch(failedId: String, winnerId: String, expected: String?): Boolean {
        try {
            val saved = Stores.profiles(this).update { s -> Failover.selectInstead(s, failedId, winnerId, expected) }
            if (saved.selectedId == winnerId) {
                notifyProfilesChanged()
                return true
            }
            AppLog.i("another server was chosen meanwhile, selection kept")
        } catch (e: Exception) {
            // The tunnel runs anyway; only the next start picks the old server.
            AppLog.w("could not save the new selection", e)
        }
        return false
    }

    // ------------------------------------------------ back to the user's server

    /** An automatic switch from [failedId] to [winnerId] happened: remember the user's server. */
    private fun trackAway(failedId: String, winnerId: String) {
        val away = RuntimeState.away(this)
        val now = SystemClock.elapsedRealtime()
        if (away != null && winnerId == away.home) RuntimeState.setReturned(this, Failover.Returned(away.home, now, away.backoff))
        RuntimeState.setAway(this, Failover.afterSwitch(away, RuntimeState.returned(this), failedId, winnerId, now))
    }

    /**
     * A start that was no automatic switch: the user's server is no longer
     * worth going back to once they picked one, or the tunnel runs another
     * server than the one switched to (a delete or refresh moved it).
     */
    private fun forgetAwayUnless(runningId: String, picked: Boolean) {
        val away = RuntimeState.away(this) ?: return
        if (picked || runningId != away.to) RuntimeState.setAway(this, null)
    }

    /**
     * After an automatic switch the tunnel stays on the other server only
     * while needed: once the user's own server answers again (at the
     * earliest 30 minutes later, then less and less often if it keeps
     * failing), go back to it.
     */
    private suspend fun returnHomeIfItAnswers(e: Long, c: Controller, running: StoredProfile) {
        val away = RuntimeState.away(this) ?: return
        val now = SystemClock.elapsedRealtime()
        if (away.to != running.id || !Failover.returnDue(away, now) || failedRecently(away.home)) return
        val saved = Stores.profiles(this).read()
        val home = saved.profiles.firstOrNull { it.id == away.home }
        if (home == null) {
            RuntimeState.setAway(this, null)
            return
        }
        // The user chose another server meanwhile; their start follows.
        if (saved.selectedId != running.id || !RuntimeState.allowFailover(this, take = false)) return
        val answered = probe(c, listOf(home)).first() >= 0
        if (epoch.get() != e) return
        if (!answered) {
            RuntimeState.setAway(this, Failover.returnFailed(away, now))
            return
        }
        AppLog.i("the chosen server answers again, going back to it")
        enqueue { switchTo(e, running, home.id, returning = true) }
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
                        val status = XrayCore.whitelistStatus(this@XrayVpnService, host)
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
    private suspend fun probe(c: Controller, servers: List<StoredProfile>): List<Long> {
        if (servers.isEmpty()) return emptyList()
        return probeLock.withLock {
            try {
                XrayCore.probe(c, servers.map { it.outbounds }, timeoutMs = Failover.PROBE_TIMEOUT_MS, parallel = Failover.PARALLEL)
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
        if (refreshJob?.isActive == true || !Failover.refreshDue(sub, System.currentTimeMillis())) return null
        return sub
    }

    /**
     * Downloads [sub] again, directly: the running server is what fails. On
     * its own, so that a switch meanwhile does not cut it short.
     */
    private fun startFailoverRefresh(sub: Subscription): Deferred<Refreshed> {
        val direct = Downloader { url, headers -> XrayCore.fetch(url, null, headers, REFRESH_TIMEOUT_MS) }
        return scope.async(Dispatchers.IO) {
            val result = refreshSubscription(sub.id, direct)
            // Blocked outside the tunnel: once a server works, through it.
            if (result.applied == null) owedRefresh = sub.id
            result
        }.also { refreshJob = it }
    }

    /** After a check of [running] passed: downloads subscription [subId] through the tunnel, and restarts if that changed the running server. */
    private fun refreshThroughTunnel(subId: String, c: Controller, running: StoredProfile) {
        if (refreshJob?.isActive == true) return
        val tunnel = Downloader { url, headers -> XrayCore.fetchThroughTunnel(c, url, headers) }
        refreshJob = scope.async(Dispatchers.IO) {
            refreshSubscription(subId, tunnel).also { if (it.runningChanged) restartOnSaved(running) }
        }
    }

    /**
     * Downloads subscription [subId] and saves the result; the old servers
     * stay when the panel sends none. The app, if open, reloads. Never throws.
     */
    private suspend fun refreshSubscription(subId: String, downloader: Downloader): Refreshed = try {
        val outcome = SubscriptionUpdater(this, DiskProfiles(this)).refresh(subId, downloader, runningId = runningProfileId)
        Refreshed(applied = outcome?.applied ?: false, runningChanged = outcome?.runningChanged == true)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w("subscription refresh failed: ${e.userMessage()}")
        Refreshed(applied = null, runningChanged = false)
    } finally {
        notifyProfilesChanged()
    }

    // ---------------------------------------------------------------- network

    private fun activeNetwork(): Network? = try {
        getSystemService(ConnectivityManager::class.java)?.activeNetwork
    } catch (_: Exception) {
        null
    }

    /** The network under the tunnel (this app's own traffic never enters it). */
    private fun underlying(): NetworkCapabilities? = try {
        val cm = getSystemService(ConnectivityManager::class.java)
        cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    } catch (_: Exception) {
        null
    }

    /** A network, and not paused for a moment (mobile data in a lift or a tunnel: the same one comes back). */
    private fun hasNetwork(caps: NetworkCapabilities?): Boolean = caps != null &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED))

    private fun onMobileData(): Boolean = underlying()?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    /**
     * Shows [notice] (null clears it) while connected, unless the tunnel
     * changed since [e]; with [replacing], only in place of one of those
     * (null in it: also when no notice is shown). On the main thread with a
     * compare-and-set, so it never overwrites a newer status.
     */
    private suspend fun setNotice(notice: String?, e: Long, replacing: Set<String?>? = null) {
        try {
            withContext(Dispatchers.Main) {
                if (epoch.get() != e) return@withContext
                val s = VpnStatusHolder.status.value
                val current = s.message
                if (s.state != VpnState.CONNECTED || current == notice) return@withContext
                if (replacing != null && current !in replacing) return@withContext
                val next = s.copy(message = notice)
                if (!VpnStatusHolder.compareAndSet(s, next)) return@withContext
                publishStatus()
                publishConnected()
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.w("notice update failed", ex)
        }
    }

    /** Traffic gets through (again): "not answering" is no longer true. A switch notice stays. */
    private suspend fun clearFailureNotice(e: Long) = setNotice(baseNotice, e, replacing = Failover.FAILURE_NOTICES)

    /**
     * Takes the "switched to another server" notice away once it is
     * [minAgeMs] old; otherwise it would stay for as long as the tunnel runs.
     */
    private fun clearSwitchNotice(e: Long, minAgeMs: Long) {
        val notice = switchNotice ?: return
        // Already replaced by another notice: it never comes back.
        if (VpnStatusHolder.status.value.message != notice) {
            switchNotice = null
            return
        }
        if (SystemClock.elapsedRealtime() - switchNoticeAt < minAgeMs) return
        scope.launch { setNotice(baseNotice, e, replacing = setOf(notice)) }
    }

    // ------------------------------------------------------ status & traffic

    private fun enterForeground(title: String, text: String?) {
        val n = Notifications.status(this, title, text, withDisconnect = true, networkSettings = text == Failover.NOTICE_PRIVATE_DNS)
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

    /** Foreground at once, as a start command requires: "connected" while the tunnel is up, else [title]. */
    private fun enterForegroundUnlessUp(title: String) {
        val s = VpnStatusHolder.status.value
        if (s.state == VpnState.CONNECTED) enterForeground("Подключено", connectedText(s)) else enterForeground(title, null)
    }

    private suspend fun publishConnected() {
        val s = VpnStatusHolder.status.value
        if (s.state != VpnState.CONNECTED) return
        withContext(Dispatchers.Main) { enterForeground("Подключено", connectedText(s)) }
    }

    private fun connectedText(s: VpnStatus): String? = when {
        lockdownConflict ->
            "Включено «Блокировать соединения без VPN»: приложения без VPN (банки, Госуслуги) останутся без интернета"
        // A notice such as "switched to another server".
        !s.message.isNullOrBlank() -> s.message
        else -> s.profileName
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
        publishStatus()
    }

    /** Shows the status in the widget and the app, if open. */
    private fun publishStatus() {
        VpnWidget.update(this)
        scope.launch {
            // The newest status, read when it is sent: a send posted from the
            // worker must never arrive after a newer one sent on the main thread.
            val status = VpnStatusHolder.status.value
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

/**
 * What made the service check that traffic gets through: a start, another
 * network, Android's view of the same network (LINK), unlocking, the app
 * opened, the screen on for a while, the user's "Проверить".
 */
private enum class Reason { START, NETWORK, LINK, UNLOCK, APP, SCREEN, USER }

/** What a subscription refresh in the VPN process did: [applied] is null when the download failed. */
private class Refreshed(val applied: Boolean?, val runningChanged: Boolean)

/** An automatic restart found another app's VPN in place. */
private class AnotherVpnException : Exception("another VPN is active")

/**
 * Small state private to the VPN process: whether the tunnel should be up
 * (for restarts after the process was killed), a crash-loop guard, the
 * budgets of automatic server switches and searches, and the user's server
 * while an automatic switch keeps the tunnel on another one.
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

    /** Whether a search for another server nobody asked for is allowed now (see [Failover.MAX_SEARCHES]); counts one. */
    fun allowSearch(context: Context): Boolean {
        val p = prefs(context)
        val next = Failover.countSearch(p.getString("searches", "") ?: "", SystemClock.elapsedRealtime()) ?: return false
        p.edit { putString("searches", next) }
        return true
    }

    /**
     * Whether the system may restart the tunnel after the process ended:
     * at most 3 times in 5 minutes after crashes (see [RestartGuard]).
     * Android 11+ tells why the process ended (App logs it); on older ones
     * every end counts. By time since boot, so a clock change cannot fool it.
     */
    fun allowAutoRestart(context: Context): Boolean {
        val reason = lastExitReason(context)
        if (reason != null && !RestartGuard.isCrash(reason)) return true
        val p = prefs(context)
        val next = RestartGuard.countRestart(p.getString("restarts", "") ?: "", SystemClock.elapsedRealtime()) ?: return false
        p.edit { putString("restarts", next) }
        return true
    }

    /** Why the last VPN process before this one ended (ApplicationExitInfo.REASON_*), or null when unknown. */
    private fun lastExitReason(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                ?.firstOrNull { it.processName.endsWith(":vpn") }
                ?.reason
        } catch (_: Exception) {
            null
        }
    }

    fun away(context: Context): Failover.Away? {
        val p = prefs(context)
        val home = p.getString("away_home", null) ?: return null
        val to = p.getString("away_to", null) ?: return null
        return Failover.Away(home, to, p.getLong("away_retry", 0), p.getInt("away_backoff", 0))
    }

    fun setAway(context: Context, away: Failover.Away?) {
        prefs(context).edit {
            if (away == null) {
                remove("away_home")
                remove("away_to")
                remove("away_retry")
                remove("away_backoff")
            } else {
                putString("away_home", away.home)
                putString("away_to", away.to)
                putLong("away_retry", away.retryAt)
                putInt("away_backoff", away.backoff)
            }
        }
    }

    fun returned(context: Context): Failover.Returned? {
        val p = prefs(context)
        val id = p.getString("returned_id", null) ?: return null
        return Failover.Returned(id, p.getLong("returned_at", 0), p.getInt("returned_backoff", 0))
    }

    fun setReturned(context: Context, returned: Failover.Returned) {
        prefs(context).edit {
            putString("returned_id", returned.id)
            putLong("returned_at", returned.at)
            putInt("returned_backoff", returned.backoff)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
