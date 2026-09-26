package com.klausms.vpn.ui

import android.app.Application
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.klausms.vpn.App
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.core.ParsedProfile
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.AppUpdate
import com.klausms.vpn.data.Downloader
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.SubscriptionUpdater
import com.klausms.vpn.data.pinWhereNeeded
import com.klausms.vpn.data.toStored
import com.klausms.vpn.service.VpnCommands
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface PingResult {
    data object Testing : PingResult
    data class Ok(val ms: Long) : PingResult
    data class Failed(val reason: String) : PingResult
}


class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).repository
    val vpn = VpnClient(app)

    val profiles: StateFlow<ProfilesState> = repo.profiles
    val settings: StateFlow<AppSettings> = repo.settings
    val status = vpn.status
    val traffic = vpn.traffic

    private val _pings = MutableStateFlow<Map<String, PingResult>>(emptyMap())
    val pings: StateFlow<Map<String, PingResult>> = _pings.asStateFlow()

    /** Server address -> 1 (on the Russian mobile whitelist), 0, -1 (partly). */
    private val _whitelist = MutableStateFlow<Map<String, Int>>(emptyMap())
    val whitelist: StateFlow<Map<String, Int>> = _whitelist.asStateFlow()

    /** Text of a running long operation, or null. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _geoVersion = MutableStateFlow(0L)
    val geoVersion: StateFlow<Long> = _geoVersion.asStateFlow()

    private val _coreVersion = MutableStateFlow("")
    val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** A newer build of the app on the owner's panel, until installed or put off. */
    private val _update = MutableStateFlow<AppUpdate?>(null)
    val update: StateFlow<AppUpdate?> = _update.asStateFlow()

    private var reconnectJob: Job? = null
    private var staleJob: Job? = null

    // Small UI state; declared before init, whose coroutine reads it.
    private val uiPrefs by lazy { app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }

    private val updater = SubscriptionUpdater(app, repo)

    /**
     * Direct first (panels are usually reachable); then through the
     * selected server in case the panel is blocked.
     */
    private val downloader = Downloader { url, headers ->
        try {
            XrayCore.fetch(url, null, headers)
        } catch (direct: Exception) {
            val via = profiles.value.selected?.outbounds ?: throw direct
            AppLog.w("subscription direct download failed, retrying via proxy", direct)
            XrayCore.fetch(url, via, headers)
        }
    }

    /**
     * Saving can fail (storage full). Report it instead of crashing; the
     * in-memory state only changes after a successful write.
     */
    private val saveErrors = CoroutineExceptionHandler { _, e ->
        AppLog.e("save failed", e)
        message("Не удалось сохранить: ${e.userMessage()}")
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                GeoFiles.ensureInstalled(app)
                XrayCore.init(app)
                _geoVersion.value = GeoFiles.installedVersion(app)
                _coreVersion.value = XrayCore.version()
                checkWhitelist(profiles.value.profiles)
            } catch (e: Exception) {
                AppLog.e("startup", e)
            }
        }
        // What the last update check found, until the next one.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _update.compareAndSet(null, offer(AppUpdate.parse(uiPrefs.getString(KEY_UPDATE, null))))
            } catch (e: Exception) {
                AppLog.w("saved update", e)
            }
        }
        // The VPN process changed the servers (failover, subscription refresh).
        viewModelScope.launch { vpn.profilesChanged.collect { repo.reload() } }
    }

    /**
     * The app came on screen (also back from Recents): picks up what the VPN
     * process saved meanwhile, then refreshes old subscriptions and looks
     * for a newer app build.
     */
    fun onAppVisible() = viewModelScope.launch {
        repo.reload()
        if (staleJob?.isActive == true) return@launch
        staleJob = viewModelScope.launch {
            refreshStaleSubscriptions()
            // After the refresh: it may have brought the panel's app address.
            checkForUpdate()
        }
    }

    /**
     * Subscriptions are refreshed when the app is opened and the last fresh
     * list is older than an hour: never in the background, so no battery is
     * spent on it.
     */
    private suspend fun refreshStaleSubscriptions() {
        val now = System.currentTimeMillis()
        for (sub in profiles.value.subscriptions) {
            if (SubscriptionUpdater.isStale(sub, now)) refreshSubscription(sub.id, quiet = true).join()
        }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }

    // ------------------------------------------------------------- update

    /**
     * Asks the owner's panel for its latest app build (version.json of the
     * first subscription that names one), at most every 12 hours; like a
     * subscription, directly and then through the selected server. Quiet:
     * a failure or a malformed answer changes nothing.
     */
    private suspend fun checkForUpdate() {
        val url = profiles.value.subscriptions.firstNotNullOfOrNull { it.appUrl } ?: return
        val via = profiles.value.selected?.outbounds
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                if (!AppUpdate.checkDue(uiPrefs.getLong(KEY_UPDATE_CHECKED, 0L), now)) return@withContext
                uiPrefs.edit { putLong(KEY_UPDATE_CHECKED, now) }
                val result = try {
                    XrayCore.fetch(url, null, timeoutMs = UPDATE_TIMEOUT_MS)
                } catch (direct: Exception) {
                    // "HTTP 404" and the like came from the panel itself: nothing to get around.
                    if (via == null || direct.message?.startsWith("HTTP ") == true) throw direct
                    AppLog.w("update check direct download failed, retrying via proxy", direct)
                    XrayCore.fetch(url, via, timeoutMs = UPDATE_TIMEOUT_MS)
                }
                val latest = AppUpdate.parse(result.body?.toString(Charsets.UTF_8))
                if (latest == null) {
                    AppLog.w("update check: not a version.json")
                    return@withContext
                }
                uiPrefs.edit { putString(KEY_UPDATE, latest.toJson()) }
                _update.value = offer(latest)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.w("update check failed: ${e.userMessage()}")
            }
        }
    }

    /** [latest] when it is newer than this build and was not put off. */
    private fun offer(latest: AppUpdate?): AppUpdate? =
        AppUpdate.offer(latest, BuildConfig.VERSION_CODE, uiPrefs.getInt(KEY_UPDATE_DISMISSED, 0))

    /** «Позже»: the card stays hidden until an even newer build appears. */
    fun dismissUpdate() {
        val shown = _update.value ?: return
        _update.value = null
        uiPrefs.edit { putInt(KEY_UPDATE_DISMISSED, shown.versionCode) }
    }

    fun updateNotOpened() = message("Не удалось открыть ссылку: на телефоне нет браузера")

    private val isTunnelUp: Boolean
        get() = status.value.state == VpnState.CONNECTED || status.value.state == VpnState.CONNECTING

    // ---------------------------------------------------------------- VPN

    fun startVpn() {
        if (profiles.value.selected == null) {
            message("Сначала добавьте ключ")
            return
        }
        VpnCommands.connect(getApplication<Application>())
    }

    fun stopVpn() = VpnCommands.disconnect(getApplication<Application>(), "app")

    fun startVpnDenied() = message("Без разрешения на VPN подключиться нельзя. Если включён другой VPN-клиент как «постоянный», отключите его.")

    fun notificationPermissionAsked(): Boolean = uiPrefs.getBoolean("notif_asked", false)

    fun markNotificationPermissionAsked() = uiPrefs.edit { putBoolean("notif_asked", true) }

    /** Re-applies server/settings to a running tunnel (debounced). */
    private fun reconnectIfRunning(delayMs: Long = 0) {
        if (!isTunnelUp) return
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            VpnCommands.reconnect(getApplication<Application>())
        }
    }

    fun testConnection() = viewModelScope.launch {
        _busy.value = "Проверка соединения…"
        val ms = vpn.testConnection()
        _busy.value = null
        message(if (ms >= 0) "Соединение работает: $ms мс" else "Сервер не отвечает")
    }

    // ------------------------------------------------------------ profiles

    fun select(id: String) = viewModelScope.launch(saveErrors) {
        if (profiles.value.selectedId == id) return@launch
        repo.updateProfiles { it.copy(selectedId = id) }
        reconnectIfRunning()
    }

    fun rename(id: String, name: String) = viewModelScope.launch(saveErrors) {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return@launch
        repo.updateProfiles { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    fun delete(id: String) = viewModelScope.launch(saveErrors) {
        val wasSelected = profiles.value.selectedId == id
        val next = repo.updateProfiles { s ->
            val rest = s.profiles.filterNot { it.id == id }
            s.copy(profiles = rest, selectedId = if (s.selectedId == id) rest.firstOrNull()?.id else s.selectedId)
        }
        _pings.update { it - id }
        if (wasSelected) {
            if (next.selected == null) stopVpn() else reconnectIfRunning()
        }
    }

    /** Adds share links, a subscription URL, or a pasted subscription body. */
    fun import(text: String) = viewModelScope.launch(saveErrors) {
        val input = text.trim()
        if (input.isEmpty()) return@launch
        _busy.value = "Добавление…"
        try {
            val subscription = ImportText.subscriptionUrl(input)
            if (subscription != null) addSubscription(subscription) else addLinks(input, ImportText.links(input))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            message(e.userMessage())
        } finally {
            _busy.value = null
        }
    }

    private suspend fun addLinks(input: String, lines: List<String>) {
        val parsed = mutableListOf<ParsedProfile>()
        val errors = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            if (lines.isEmpty()) {
                // Maybe a pasted subscription body (base64 or JSON).
                val res = XrayCore.parseSubscription(input.toByteArray())
                parsed += res.profiles
                errors += res.errors
            } else {
                for (line in lines) {
                    try {
                        parsed += XrayCore.parseLink(line)
                    } catch (e: Exception) {
                        errors += e.userMessage()
                    }
                }
            }
        }
        val ready = pinWhereNeeded(parsed, errors)
        val existing = profiles.value.profiles.mapNotNull { it.link }.toSet()
        val fresh = ready.filter { it.link == null || it.link !in existing }
        if (fresh.isNotEmpty()) {
            val stored = fresh.map { it.toStored(null) }
            repo.updateProfiles { s -> s.copy(profiles = s.profiles + stored, selectedId = s.selectedId ?: stored.first().id) }
            checkWhitelist(stored)
        }
        message(
            when {
                fresh.isNotEmpty() && errors.isEmpty() -> "Добавлено серверов: ${fresh.size}"
                fresh.isNotEmpty() -> "Добавлено: ${fresh.size}, пропущено: ${errors.size} (${errors.first()})"
                ready.isNotEmpty() -> "Эти ключи уже добавлены"
                errors.isNotEmpty() -> errors.first()
                else -> "Не найдено ни одного ключа"
            },
        )
    }

    private suspend fun addSubscription(url: String) {
        if (profiles.value.subscriptions.any { it.url == url }) {
            message("Эта подписка уже добавлена")
            return
        }
        val outcome = updater.add(url, downloader)
        checkWhitelist(outcome.servers)
        val name = outcome.subscription.name
        message(
            if (outcome.applied) {
                "Подписка «$name»: серверов ${outcome.servers.size}"
            } else {
                "Подписка «$name» добавлена без серверов: ${outcome.subscription.notice ?: "сервер подписки их не прислал"}"
            },
        )
    }

    /**
     * [quiet]: an automatic refresh, without messages. The old servers stay
     * when the panel refuses or fails; the reason is saved on the subscription.
     */
    fun refreshSubscription(id: String, quiet: Boolean = false) = viewModelScope.launch(saveErrors) {
        if (profiles.value.subscriptions.none { it.id == id }) return@launch
        if (!quiet) _busy.value = "Обновление подписки…"
        try {
            // A manual refresh also renews pinned certificates.
            val outcome = updater.refresh(id, downloader, runningId = status.value.profileId, repin = !quiet) ?: return@launch
            checkWhitelist(outcome.servers)
            if (outcome.runningChanged) reconnectIfRunning()
            if (!quiet) {
                message(
                    if (outcome.applied) {
                        "Подписка обновлена: серверов ${outcome.servers.size}"
                    } else {
                        outcome.subscription.notice ?: "Сервер подписки не прислал серверов, оставлены прежние"
                    },
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!quiet) message("Не удалось обновить подписку: ${e.userMessage()}")
        } finally {
            if (!quiet) _busy.value = null
        }
    }

    fun deleteSubscription(id: String) = viewModelScope.launch(saveErrors) {
        val selectedWasInside = profiles.value.selected?.subscriptionId == id
        val next = repo.updateProfiles { s ->
            val rest = s.profiles.filterNot { it.subscriptionId == id }
            s.copy(
                profiles = rest,
                subscriptions = s.subscriptions.filterNot { it.id == id },
                selectedId = if (rest.any { it.id == s.selectedId }) s.selectedId else rest.firstOrNull()?.id,
            )
        }
        if (selectedWasInside) {
            if (next.selected == null) stopVpn() else reconnectIfRunning()
        }
    }

    // ------------------------------------------------------------- testing

    fun ping(ids: List<String>) = viewModelScope.launch {
        val targets = profiles.value.profiles.filter { it.id in ids }
        _pings.update { m -> m + targets.associate { it.id to PingResult.Testing } }
        val limit = Semaphore(4)
        targets.map { p ->
            launch {
                limit.withPermit {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            PingResult.Ok(XrayCore.measureDelay(p.outbounds))
                        } catch (e: Exception) {
                            PingResult.Failed(e.userMessage())
                        }
                    }
                    _pings.update { it + (p.id to result) }
                }
            }
        }
    }

    fun pingAll() = ping(profiles.value.profiles.map { it.id })

    private fun checkWhitelist(list: List<StoredProfile>) {
        val app = getApplication<Application>()
        val hosts = list.map { it.address }.distinct().filter { it !in _whitelist.value }
        if (hosts.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (host in hosts) {
                val r = try {
                    XrayCore.whitelistStatus(app, host)
                } catch (_: Exception) {
                    continue
                }
                _whitelist.update { it + (host to r) }
            }
        }
    }

    // ------------------------------------------------------------ settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch(saveErrors) {
        val before = settings.value
        val after = repo.updateSettings(transform)
        if (after != before) reconnectIfRunning(delayMs = 800)
    }

    /** The app lists changed while their screen is open; applied when it closes. */
    private var appListsChanged = false

    /**
     * Picking apps one by one is saved at once but reaches the tunnel only
     * when the list is closed ([applyAppLists]): one reconnect, not one per
     * tap.
     */
    fun updateAppLists(transform: (AppSettings) -> AppSettings) = viewModelScope.launch(saveErrors) {
        val before = settings.value
        if (repo.updateSettings(transform) != before) appListsChanged = true
    }

    fun applyAppLists() = viewModelScope.launch(saveErrors) {
        repo.awaitSaves()
        if (appListsChanged) {
            appListsChanged = false
            reconnectIfRunning()
        }
    }

    fun updateGeo() = viewModelScope.launch {
        val app = getApplication<Application>()
        _busy.value = "Обновление баз…"
        try {
            val via = profiles.value.selected?.outbounds
            withContext(Dispatchers.IO) { GeoFiles.update(app, via) { _busy.value = it } }
            _geoVersion.value = GeoFiles.installedVersion(app)
            message("Базы обновлены")
            reconnectIfRunning()
        } catch (e: Exception) {
            AppLog.e("geo update failed", e)
            message("Не удалось обновить базы: ${e.userMessage()}")
        } finally {
            _busy.value = null
        }
    }

    override fun onCleared() {
        vpn.unbind()
    }

    private companion object {
        const val KEY_UPDATE = "update_latest"
        const val KEY_UPDATE_CHECKED = "update_checked_at"
        const val KEY_UPDATE_DISMISSED = "update_dismissed"
        const val UPDATE_TIMEOUT_MS = 15_000
    }
}
