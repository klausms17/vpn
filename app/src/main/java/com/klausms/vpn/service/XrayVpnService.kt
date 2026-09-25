package com.klausms.vpn.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteCallbackList
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.klausms.vpn.core.BuildOptions
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.AppMode
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.RussianApps
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Stores
import com.klausms.vpn.ui.MainActivity
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libxray.Controller
import libxray.Libxray
import java.io.File

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
 */
class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.klausms.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.klausms.vpn.DISCONNECT"
        const val ACTION_RECONNECT = "com.klausms.vpn.RECONNECT"
        const val ACTION_BIND = "com.klausms.vpn.BIND"

        private const val NETWORK_SETTLE_MS = 1_500L
        private const val MIN_UPTIME_FOR_RESET_MS = 3_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

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

    private var networkMonitor: UnderlyingNetworkMonitor? = null
    private var lastNetwork: Network? = null

    @Volatile
    private var resetJob: Job? = null
    private var trafficJob: Job? = null

    private val callbacks = RemoteCallbackList<IVpnCallback>()
    private var totalUp = 0L
    private var totalDown = 0L

    private val binder = object : IVpnController.Stub() {
        override fun registerCallback(callback: IVpnCallback?) {
            callback ?: return
            callbacks.register(callback)
            scope.launch {
                sendStatus(callback, VpnStatusHolder.status.value)
                updateTrafficTicker()
            }
        }

        override fun unregisterCallback(callback: IVpnCallback?) {
            callback ?: return
            callbacks.unregister(callback)
            scope.launch { updateTrafficTicker() }
        }

        override fun testConnection(): Long {
            val c = controller ?: return -1
            return try {
                c.measureDelay(XrayCore.TEST_URL, 10_000)
            } catch (e: Exception) {
                AppLog.w("connection test failed", e)
                -1
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_BIND) binder else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect(userInitiated = true)
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> {
                enterForeground("Переподключение…", null)
                scope.launch(worker) { startTunnel() }
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
                scope.launch(worker) { if (config == null) startTunnel() else publishConnected() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission in settings.
        AppLog.i("VPN permission revoked by the system")
        disconnect(userInitiated = true)
    }

    override fun onDestroy() {
        networkMonitor?.stop()
        networkMonitor = null
        scope.cancel()
        // Synchronous: the process may be killed right after this.
        stopCore()
        callbacks.kill()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ start

    private suspend fun startTunnel() {
        val restarting = config != null
        setStatus(VpnStatus(VpnState.CONNECTING, profileName = VpnStatusHolder.status.value.profileName))
        try {
            val profiles = Stores.profiles(this).read()
            val profile = profiles.selected ?: throw VpnStartException("Не выбран сервер. Добавьте ключ в приложении.")
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
            val newTun = establishTun(profile, settings)
            val oldTun = tun
            resetJob?.cancel()
            if (restarting) {
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
            resetOnNetworkChange = settings.resetOnNetworkChange
            connectedAtElapsed = SystemClock.elapsedRealtime()
            totalUp = 0
            totalDown = 0
            RuntimeState.setShouldRun(this, true)
            withContext(Dispatchers.Main) { startNetworkMonitor() }
            setStatus(VpnStatus(VpnState.CONNECTED, profile.id, profile.name, connectedSince = System.currentTimeMillis()))
            Notifications.clearError(this)
            AppLog.i("tunnel up: ${profile.protocol}/${profile.network}/${profile.security}, mode ${settings.mode.core}")
            publishConnected()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val message = (e as? VpnStartException)?.message ?: "Ошибка запуска: ${e.userMessage()}"
            AppLog.e("tunnel start failed: $message")
            stopCore()
            RuntimeState.setShouldRun(this, false)
            setStatus(VpnStatus(VpnState.ERROR, message = message))
            if (!isAppVisible()) Notifications.showError(this, message)
            withContext(Dispatchers.Main) { leaveForegroundAndStop() }
        }
    }

    private fun establishTun(profile: StoredProfile, settings: AppSettings): ParcelFileDescriptor {
        if (prepare(this) != null) {
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
        applyPerAppRules(builder, settings)
        (networkMonitor?.network ?: lastNetwork)?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        return builder.establish()
            ?: throw VpnStartException("Система не разрешила создать VPN. Возможно, включён другой постоянный VPN.")
    }

    private fun applyPerAppRules(builder: Builder, settings: AppSettings) {
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
            if (added > 0) return
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
        for (pkg in excluded) {
            if (pkg == packageName) continue
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
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

    private fun disconnect(userInitiated: Boolean) {
        if (userInitiated) RuntimeState.setShouldRun(this, false)
        setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
        scope.launch(worker) {
            stopCore()
            setStatus(VpnStatus(VpnState.DISCONNECTED))
            AppLog.i("tunnel down")
            withContext(Dispatchers.Main) { leaveForegroundAndStop() }
        }
    }

    /** Stops the core first, then closes the TUN fd it was reading. */
    private fun stopCore() {
        resetJob?.cancel()
        try {
            controller?.stop()
        } catch (e: Exception) {
            AppLog.w("core stop", e)
        }
        config = null
        closeTun()
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

    private fun leaveForegroundAndStop() {
        networkMonitor?.stop()
        networkMonitor = null
        lastNetwork = null
        trafficJob?.cancel()
        trafficJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --------------------------------------------------------- network change

    private fun startNetworkMonitor() {
        if (networkMonitor != null) return
        val monitor = UnderlyingNetworkMonitor(this) { network -> onUnderlyingNetworkChanged(network) }
        monitor.start()
        lastNetwork = monitor.network
        networkMonitor = monitor
    }

    private fun onUnderlyingNetworkChanged(network: Network?) {
        // Lets Android attribute the tunnel to Wi-Fi/mobile (metered state,
        // "no internet" detection) correctly.
        setUnderlyingNetworks(network?.let { arrayOf(it) })
        val previous = lastNetwork
        if (network != null) lastNetwork = network
        if (network == null || previous == null || network == previous) return
        if (!resetOnNetworkChange) return
        if (SystemClock.elapsedRealtime() - connectedAtElapsed < MIN_UPTIME_FOR_RESET_MS) return
        // Connections opened over the old network are dead but would hang
        // until timeouts. Restarting the core resets them at once, so apps
        // (messengers, video) reconnect immediately over the new network.
        resetJob?.cancel()
        resetJob = scope.launch(worker) {
            delay(NETWORK_SETTLE_MS)
            val cfg = config ?: return@launch
            val fd = tun ?: return@launch
            val c = controller ?: return@launch
            AppLog.i("network changed, resetting connections")
            try {
                c.stop()
                c.start(cfg, fd.fd)
                connectedAtElapsed = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                AppLog.e("core restart after network change failed", e)
                scope.launch(worker) { startTunnel() }
            }
        }
    }

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
        val settings = Stores.settings(this).read()
        withContext(Dispatchers.Main) { enterForeground("Подключено", "${s.profileName} · ${settings.mode.title}") }
    }

    private fun setStatus(status: VpnStatus) {
        VpnStatusHolder.set(status)
        scope.launch {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) sendStatus(callbacks.getBroadcastItem(i), status)
            } finally {
                callbacks.finishBroadcast()
            }
            updateTrafficTicker()
        }
    }

    private fun sendStatus(cb: IVpnCallback, s: VpnStatus) {
        try {
            cb.onStatus(s.state.code, s.profileId, s.profileName, s.message, s.connectedSince)
        } catch (_: Exception) {
            // Dead callbacks are removed by RemoteCallbackList.
        }
    }

    /** Counts traffic only while the app UI is open and the tunnel is up. */
    private fun updateTrafficTicker() {
        val wanted = callbacks.registeredCallbackCount > 0 && VpnStatusHolder.status.value.state == VpnState.CONNECTED
        if (!wanted) {
            trafficJob?.cancel()
            trafficJob = null
            return
        }
        if (trafficJob?.isActive == true) return
        trafficJob = scope.launch {
            var last = SystemClock.elapsedRealtime()
            controller?.queryTraffic() // reset counters
            while (isActive) {
                delay(1_000)
                val t = withContext(Dispatchers.IO) { controller?.queryTraffic() } ?: continue
                val now = SystemClock.elapsedRealtime()
                val seconds = ((now - last).coerceAtLeast(1)) / 1000.0
                last = now
                val up = t.proxyUp + t.directUp
                val down = t.proxyDown + t.directDown
                totalUp += up
                totalDown += down
                val n = callbacks.beginBroadcast()
                try {
                    for (i in 0 until n) {
                        try {
                            callbacks.getBroadcastItem(i).onTraffic((up / seconds).toLong(), (down / seconds).toLong(), totalUp, totalDown)
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }
    }

    private fun isAppVisible(): Boolean = callbacks.registeredCallbackCount > 0
}

private class VpnStartException(message: String) : Exception(message)

/**
 * Small state private to the VPN process: whether the tunnel should be up
 * (for restarts after the process was killed) and a crash-loop guard.
 */
internal object RuntimeState {
    private const val PREFS = "vpn_runtime"

    fun shouldRun(context: Context) = prefs(context).getBoolean("should_run", false)

    fun setShouldRun(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("should_run", value).apply()
    }

    /** Allows at most 3 automatic restarts within 5 minutes. */
    fun allowAutoRestart(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val recent = (p.getString("restarts", "") ?: "").split(',')
            .mapNotNull { it.toLongOrNull() }
            .filter { now - it < 5 * 60_000 }
        if (recent.size >= 3) return false
        p.edit().putString("restarts", (recent + now).joinToString(",")).apply()
        return true
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
