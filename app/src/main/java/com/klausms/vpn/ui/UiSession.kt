package com.klausms.vpn.ui

import android.app.Application
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.data.AppRepository
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.PhoneSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Captions of the long operations now running; [text] is the newest. One
 * operation ending must not hide the pill while another still runs.
 */
internal class BusyTexts {
    private val running = ArrayList<Op>()
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    inner class Op internal constructor(caption: String) {
        /** Progress ("Загрузка geoip.dat…"); may be set from any thread. */
        var caption: String = caption
            set(value) = synchronized(running) {
                field = value
                publish()
            }

        fun end() = synchronized(running) {
            running.remove(this)
            publish()
        }
    }

    fun start(caption: String): Op = synchronized(running) { Op(caption).also { running += it; publish() } }

    private fun publish() {
        _text.value = running.lastOrNull()?.caption
    }
}

/** Work that must not run twice at once; [running] is shown on screen. */
internal class OneAtATime {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** False when a run is already going. */
    fun tryStart(): Boolean = _running.compareAndSet(expect = false, update = true)

    fun end() {
        _running.value = false
    }
}

/**
 * What the UI shows about work that outlives one screen: the toasts, the
 * busy pill, the geo update, the core and geo versions and the whitelist
 * marks. One per UI process (see [com.klausms.vpn.App.ui]), so an import or
 * a geo update finished in [scope] after Back cleared the ViewModel still
 * reaches the next screen. Holds the Application only, never an Activity.
 *
 * Threading: [start] on the main thread only; everything else from any
 * thread ([refreshGeoVersion] blocks, so on IO).
 */
class UiSession(private val app: Application, private val repo: AppRepository, private val scope: CoroutineScope) {
    /** Text of a running long operation, or null. */
    internal val busy = BusyTexts()

    private val messageChannel = Channel<String>(Channel.BUFFERED)

    /**
     * Toasts. One collector at a time: the activity on screen. A toast sent
     * while no activity collects waits in the buffer for the next one.
     */
    val messages: Flow<String> = messageChannel.receiveAsFlow()

    fun message(text: String) {
        messageChannel.trySend(text)
    }

    /**
     * «Обновить списки», for the whole app process rather than one screen: a run
     * whose screen was closed keeps downloading until it ends (the download
     * cannot be interrupted), and a reopened screen must not start a second run
     * over the same files meanwhile.
     */
    internal val geoUpdate = OneAtATime()

    private val _geoVersion = MutableStateFlow(0L)
    val geoVersion: StateFlow<Long> = _geoVersion.asStateFlow()

    private val _coreVersion = MutableStateFlow("")
    val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()

    /** Server address -> 1 (on the Russian mobile whitelist), 0, -1 (partly). */
    private val _whitelist = MutableStateFlow<Map<String, Int>>(emptyMap())
    val whitelist: StateFlow<Map<String, Int>> = _whitelist.asStateFlow()

    @Volatile
    private var started = false

    /** The running start; main thread only. */
    private var starting: Job? = null

    /** Sets up the core once per process; runs again only after a failed attempt. */
    fun start() {
        if (started || starting?.isActive == true) return
        starting = scope.launch(Dispatchers.IO) {
            try {
                // For «Сообщить о проблеме»: what the phone allows in the background.
                AppLog.i("phone: ${PhoneSettings.summary(app)}")
                GeoFiles.ensureInstalled(app)
                XrayCore.init(app)
                _geoVersion.value = GeoFiles.installedVersion(app)
                _coreVersion.value = XrayCore.version()
                started = true
                checkWhitelist(repo.profiles.value.profiles)
            } catch (e: Exception) {
                AppLog.e("startup", e)
            }
        }
    }

    /** Reads the installed geo files' version again. Blocking; call on IO. */
    fun refreshGeoVersion() {
        _geoVersion.value = GeoFiles.installedVersion(app)
    }

    /** Hosts whose lookup is running, so a second call does not repeat it. */
    private val lookingUp = ConcurrentHashMap.newKeySet<String>()

    /**
     * Looks up the whitelist marks of the servers in [list] not looked up
     * yet. Before the core is set up it does nothing: [start] then checks
     * every saved server.
     */
    fun checkWhitelist(list: List<StoredProfile>) {
        if (!started) return
        val hosts = list.map { it.address }.distinct().filter { it !in _whitelist.value && lookingUp.add(it) }
        if (hosts.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (host in hosts) {
                try {
                    val r = XrayCore.whitelistStatus(app, host)
                    _whitelist.update { it + (host to r) }
                } catch (_: Exception) {
                    // Left unmarked; the next call tries again.
                } finally {
                    lookingUp.remove(host)
                }
            }
        }
    }
}
