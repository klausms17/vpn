package com.klausms.vpn.service

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.klausms.vpn.core.BuildOptions
import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.RussianApps
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Stores
import com.klausms.vpn.ui.MainActivity
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.Clock
import com.klausms.vpn.util.PhoneSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Starts, stops and resets the tunnel. Owns the TUN interface, the [core]
 * running on it and the [session] that says which tunnel runs; nothing else
 * changes them. A new start brings the new interface up before the old one
 * goes, and a failed one follows [StartFailurePolicy].
 *
 * Threading:
 * - Starts and stops run through [submit]: strictly one after another on
 *   one worker thread, under one lock held across their hops to the main
 *   thread (a single-thread dispatcher alone would let the next job start
 *   while the previous one waits for the main thread). [start] and [stop]
 *   may only be called inside a [submit] block. The lock is not reentrant,
 *   so nothing inside such a block may wait for another one; [submit] only
 *   launches.
 * - [resetInPlace] may be called from any thread; the reset runs under the
 *   same lock.
 * - [session] may be read from any thread.
 * - [stopNow] is for onDestroy, on the main thread.
 * - The Go core is called here only on the worker, except by [stopNow].
 */
internal class TunnelEngine(
    private val service: VpnService,
    private val scope: CoroutineScope,
    private val epoch: Epoch,
    private val clock: Clock,
    private val core: CoreHandle,
    private val publisher: StatusPublisher,
    private val runtime: RuntimeStore,
    private val profiles: ProfilesAccess,
    private val coreLog: File,
    underlying: () -> Network?,
    private val latestStartId: () -> Int,
    private val stopIfLatest: (Int) -> Unit,
    private val listener: Listener,
) {
    /** What the service does about the tunnel. Called on the worker, under the start lock. */
    interface Listener {
        /**
         * A start brought [session] up for [req]; [restarting]: it replaced a
         * tunnel that showed [before]. Part of the start: whatever this
         * throws fails the start.
         */
        suspend fun onUp(session: TunnelSession, req: StartRequest, before: VpnStatus, restarting: Boolean)

        /** An in-place reset restarted the core. */
        fun onReset()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val serial = Mutex()
    private val tunBuilder = TunBuilder(service, runtime, underlying)
    private val resets = ResetScheduler(epoch, scope, worker)

    @Volatile
    private var tun: ParcelFileDescriptor? = null

    /**
     * The tunnel that runs, null while no core does. Set when a start
     * succeeds and renewed by an in-place reset, both on the worker under
     * the lock; cleared there or by [stopNow].
     */
    @Volatile
    var session: TunnelSession? = null
        private set

    // Counts starts; a retry planned for an older one is dropped. Under the lock only.
    private var startGeneration = 0

    /** Runs [block] on the worker once every earlier start, stop and reset has finished. */
    fun submit(block: suspend () -> Unit): Job = scope.launch(worker) { serial.withLock { block() } }

    /** Starts the tunnel for [req], replacing the one that runs. Only inside [submit]. */
    suspend fun start(req: StartRequest) {
        // Also while a failed start waits to be retried with the interface
        // held: that is a tunnel that should run, not a first start.
        val restarting = session != null || tun != null
        val generation = ++startGeneration
        val before = VpnStatusHolder.status.value
        // From here on the new interface has replaced the old one.
        var swapped = false
        // New settings for a running tunnel: it keeps working meanwhile, so
        // it still shows as on (a tap on a "connecting" button would cancel).
        if (restarting && before.state == VpnState.CONNECTED) {
            publisher.setStatus(before.copy(message = "Применяем изменения…"))
        } else {
            publisher.setStatus(VpnStatus(VpnState.CONNECTING, profileName = before.profileName))
        }
        try {
            val saved = profiles.snapshot()
            val profile = req.switch?.let { switch -> saved.profiles.firstOrNull { it.id == switch.winnerId } }
                ?: saved.selected
                ?: throw VpnStartException("Не выбран сервер. Добавьте ключ в приложении.")
            val settings = Stores.settings(service).read()
            if (VpnStatusHolder.status.value.state == VpnState.CONNECTING) publisher.setStatus(VpnStatus(VpnState.CONNECTING, profile.id, profile.name))
            val config = buildConfig(profile)
            // Bring the new interface up before the old one goes away:
            // Android then switches over without a moment of traffic
            // flowing outside the VPN.
            val (newTun, lockdownConflict) = tunBuilder.establish(profile, settings, req.userRequested)
            swapped = true
            swapIn(newTun, config, restarting)
            val up = TunnelSession(profile, config, core, connectedAt = clock.elapsed(), lockdownConflict)
            session = up
            LiveCore.current = core
            epoch.advance()
            AppLog.i("tunnel up: ${profile.protocol}/${profile.network}/${profile.security}; ${PhoneSettings.vpnSummary(service)}")
            listener.onUp(up, req, before, restarting)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            onStartFailed(e, req, generation, restarting, swapped, before)
        }
    }

    /**
     * Takes the tunnel down for the disconnect command [startId];
     * [message]: why, when it was not the user. Only inside [submit].
     */
    suspend fun stop(userInitiated: Boolean, startId: Int, message: String?) {
        // A start that was still running has just finished; say again
        // that the tunnel is going down.
        if (VpnStatusHolder.status.value.state != VpnState.DISCONNECTING) {
            publisher.setStatus(VpnStatus(VpnState.DISCONNECTING, profileName = VpnStatusHolder.status.value.profileName))
        }
        stopCore()
        // A start that finished just before this set it again.
        if (userInitiated) runtime.setShouldRun(false)
        publisher.setStatus(VpnStatus(VpnState.DISCONNECTED, message = message))
        AppLog.i("tunnel down")
        withContext(Dispatchers.Main) { stopIfLatest(startId) }
    }

    /**
     * Restarts the running core on the same server and settings after
     * [delayMs]: every connection is reset at once. [expectedEpoch]: only
     * if nothing changed since. The TUN interface stays, so no traffic
     * leaves the VPN meanwhile. Returns whether the reset was queued
     * (false: [expectedEpoch] is outdated, and a pending reset is left alone).
     */
    fun resetInPlace(why: String, delayMs: Long = 0, expectedEpoch: Long? = null): Boolean {
        val startId = latestStartId()
        return resets.schedule(delayMs, expectedEpoch) {
            serial.withLock {
                if (expectedEpoch != null && !epoch.isCurrent(expectedEpoch)) return@withLock
                val running = session ?: return@withLock
                val fd = tun ?: return@withLock
                AppLog.i(why)
                try {
                    epoch.advance()
                    core.stop()
                    // A tunnel that only ever resets never goes through
                    // start(): its log is kept small here too.
                    XrayLog.trim(coreLog)
                    core.start(running.config, fd.fd)
                    // The same profile object: restartOnSaved() compares it by identity.
                    session = running.copy(connectedAt = clock.elapsed())
                    epoch.advance()
                    listener.onReset()
                } catch (e: Exception) {
                    AppLog.e("core restart failed", e)
                    // No core runs now: the restart must not count on the old
                    // one. Nothing here may suspend: a newer reset may have
                    // cancelled this job already, and the start must still be queued.
                    session = null
                    LiveCore.current = null
                    epoch.advance()
                    submit { if (runtime.shouldRun()) start(StartRequest(startId, userRequested = false)) }
                }
            }
        }
    }

    /** Stops the core and closes the interface at once: the process may be killed right after onDestroy. */
    fun stopNow() = stopCore()

    // ------------------------------------------------------------------ start

    private fun buildConfig(profile: StoredProfile): String {
        GeoFiles.ensureInstalled(service)
        XrayCore.init(service)
        XrayLog.trim(coreLog)
        return XrayCore.buildConfig(
            BuildOptions(
                outbounds = profile.outbounds,
                mode = "ru_direct",
                ipv6 = false,
                directRules = emptyList(),
                proxyRules = emptyList(),
                blockRules = emptyList(),
                logLevel = "warning",
                logFile = coreLog.absolutePath,
                tun = true,
            ),
        )
    }

    /**
     * Moves the tunnel onto [newTun] with a core running [config]. The old
     * interface closes once the new core has taken over, or failed to.
     */
    private fun swapIn(newTun: ParcelFileDescriptor, config: String, restarting: Boolean) {
        val oldTun = tun
        resets.cancel()
        if (restarting) {
            // Checks of the old core end here, not with the next one's results.
            epoch.advance()
            try {
                core.stop()
            } catch (e: Exception) {
                AppLog.w("core stop before restart", e)
            }
        }
        tun = newTun
        try {
            core.start(config, newTun.fd)
        } catch (e: Exception) {
            throw VpnStartException("Ядро не запустилось: ${e.userMessage()}")
        } finally {
            if (oldTun != null && oldTun !== newTun) closeQuietly(oldTun)
        }
    }

    private suspend fun onStartFailed(
        e: Exception,
        req: StartRequest,
        generation: Int,
        restarting: Boolean,
        swapped: Boolean,
        before: VpnStatus,
    ) {
        val running = session?.profile
        val action = StartFailurePolicy.decide(
            anotherVpn = e is AnotherVpnException,
            restarting = restarting,
            swapped = swapped,
            sessionAlive = running != null,
            userRequested = req.userRequested,
            attempt = req.attempt,
            shouldRun = runtime::shouldRun,
        )
        when (action) {
            FailureAction.StayOff -> {
                AppLog.i("another VPN is active, not restarting")
                stopCore()
                runtime.setShouldRun(false)
                publisher.setStatus(VpnStatus(VpnState.DISCONNECTED))
                withContext(Dispatchers.Main) { stopIfLatest(req.startId) }
            }
            is FailureAction.KeepOld -> {
                val message = logStartFailure(e, req.attempt)
                val old = checkNotNull(running)
                publisher.setStatus(
                    VpnStatus(
                        VpnState.CONNECTED, old.id, old.name,
                        message = "Не удалось применить изменения: $message",
                        connectedSince = before.connectedSince.takeIf { it > 0 } ?: clock.wall(),
                    ),
                )
                publisher.publishConnected()
                if (action.retry) retryLater(generation, req)
            }
            FailureAction.HoldTunAndRetry -> {
                logStartFailure(e, req.attempt)
                haltCore()
                publisher.setStatus(VpnStatus(VpnState.CONNECTING, profileName = before.profileName, message = "Переподключение…"))
                withContext(Dispatchers.Main) { publisher.enterForeground("Переподключение…", null) }
                retryLater(generation, req)
            }
            FailureAction.GiveUp -> {
                val message = logStartFailure(e, req.attempt)
                stopCore()
                runtime.setShouldRun(false)
                publisher.setStatus(VpnStatus(VpnState.ERROR, message = message))
                if (!publisher.isAppVisible()) Notifications.showError(service, message)
                withContext(Dispatchers.Main) { stopIfLatest(req.startId) }
            }
        }
    }

    /** Logs a start that failed on try [attempt] with [e]; returns the message for the user. */
    private fun logStartFailure(e: Exception, attempt: Int): String {
        val message = (e as? VpnStartException)?.message ?: "Ошибка запуска: ${e.userMessage()}"
        AppLog.e("tunnel start failed (attempt ${attempt + 1}): $message", e.takeIf { it !is VpnStartException })
        return message
    }

    /**
     * Tries [req] once more after a pause, for the newest command and as a
     * start nobody asked for just now, unless the tunnel was started or
     * stopped in any other way meanwhile ([generation] is no longer the last
     * start, or the user switched it off).
     */
    private fun retryLater(generation: Int, req: StartRequest) {
        val attempt = req.attempt + 1
        scope.launch(worker) {
            delay(StartFailurePolicy.retryDelayMs(attempt))
            submit {
                if (generation != startGeneration || !runtime.shouldRun()) return@submit
                AppLog.i("starting again (attempt ${attempt + 1})")
                start(req.copy(startId = latestStartId(), userRequested = false, attempt = attempt))
            }
        }
    }

    // ------------------------------------------------------------------- stop

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
        resets.cancel()
        session = null
        LiveCore.current = null
        epoch.advance()
        try {
            core.stop()
        } catch (e: Exception) {
            AppLog.w("core stop", e)
        }
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
}

/**
 * Builds the TUN interface of a start: routes, DNS, the apps kept outside
 * and the network under it. Keeps no state; runs on the engine's worker.
 */
private class TunBuilder(
    private val service: VpnService,
    private val runtime: RuntimeStore,
    private val underlying: () -> Network?,
) {
    /**
     * Brings up the new interface for [profile]. Returns it, and whether
     * "Block connections without VPN" leaves the apps kept outside it
     * without network.
     */
    fun establish(profile: StoredProfile, settings: AppSettings, userRequested: Boolean): Pair<ParcelFileDescriptor, Boolean> {
        // prepare() is not a query: with an earlier consent it takes the VPN
        // over from whichever app runs one. Only do that when asked to.
        if (userRequested && VpnService.prepare(service) != null) {
            runtime.setVpnConsented(false)
            throw VpnStartException("Нет разрешения на VPN. Откройте приложение и подключитесь оттуда.")
        }
        val tunCfg = XrayCore.tunConfig(ipv6 = false)
        val builder = service.Builder()
            .setSession(profile.name.ifBlank { "VPN" })
            .setMtu(tunCfg.mtu)
            .setConfigureIntent(
                PendingIntent.getActivity(
                    service, 0, Intent(service, MainActivity::class.java),
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
        val lockdownConflict = bypassing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && service.isLockdownEnabled
        if (lockdownConflict) AppLog.w("lockdown is on while some apps bypass the VPN: they will have no network")
        underlying()?.let { builder.setUnderlyingNetworks(arrayOf(it)) }
        val pfd = builder.establish()
            ?: if (userRequested) {
                throw VpnStartException("Система не разрешила создать VPN. Возможно, включён другой постоянный VPN.")
            } else {
                throw AnotherVpnException()
            }
        runtime.setVpnConsented(true)
        return pfd to lockdownConflict
    }

    /** Returns whether some other app ends up outside the tunnel. */
    private fun applyPerAppRules(builder: VpnService.Builder, settings: AppSettings): Boolean {
        builder.addDisallowedApplication(service.packageName)
        val excluded = buildSet {
            addAll(settings.excludedApps)
            if (settings.bypassRussianApps) addAll(RussianApps.installed(service.packageManager))
        }
        var bypassing = false
        for (pkg in excluded) {
            if (pkg == service.packageName) continue
            try {
                builder.addDisallowedApplication(pkg)
                bypassing = true
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return bypassing
    }
}
