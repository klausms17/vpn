package com.klausms.vpn.ui

import android.app.Application
import androidx.core.content.edit
import androidx.core.net.toUri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.klausms.vpn.App
import com.klausms.vpn.core.ParsedProfile
import com.klausms.vpn.core.SubscriptionParseResult
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.AppSettings
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.service.VpnCommands
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.util.AppLog
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
import java.util.UUID

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

    private var reconnectJob: Job? = null

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
        refreshStaleSubscriptions()
    }

    /**
     * Subscriptions are refreshed when the app is opened and they are older
     * than a day: never in the background, so no battery is spent on it.
     */
    private fun refreshStaleSubscriptions() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        for (sub in profiles.value.subscriptions) {
            if (now - sub.updatedAt > 24 * 60 * 60 * 1000L) refreshSubscription(sub.id, quiet = true).join()
        }
    }

    private fun message(text: String) {
        _messages.trySend(text)
    }

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

    fun stopVpn() = VpnCommands.disconnect(getApplication<Application>())

    fun startVpnDenied() = message("Без разрешения на VPN подключиться нельзя. Если включён другой VPN-клиент как «постоянный», отключите его.")

    private val uiPrefs by lazy { app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }

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

    fun select(id: String) = viewModelScope.launch {
        if (profiles.value.selectedId == id) return@launch
        repo.updateProfiles { it.copy(selectedId = id) }
        reconnectIfRunning()
    }

    fun rename(id: String, name: String) = viewModelScope.launch {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return@launch
        repo.updateProfiles { s -> s.copy(profiles = s.profiles.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    fun delete(id: String) = viewModelScope.launch {
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
    fun import(text: String) = viewModelScope.launch {
        val input = text.trim()
        if (input.isEmpty()) return@launch
        _busy.value = "Добавление…"
        try {
            val single = input.lines().filter { it.isNotBlank() }
            if (single.size == 1 && (input.startsWith("https://", true) || input.startsWith("http://", true))) {
                addSubscription(input)
            } else {
                addLinks(input)
            }
        } catch (e: Exception) {
            message(e.userMessage())
        } finally {
            _busy.value = null
        }
    }

    private suspend fun addLinks(input: String) {
        val lines = input.lines().map { it.trim() }.filter { it.contains("://") }
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

    /** Links asking to skip TLS verification get their certificate pinned. */
    private suspend fun pinWhereNeeded(list: List<ParsedProfile>, errors: MutableList<String>): List<ParsedProfile> =
        withContext(Dispatchers.IO) {
            list.mapNotNull { p ->
                if (!p.needsCertPin) return@mapNotNull p
                try {
                    XrayCore.pinCertificate(p)
                } catch (e: Exception) {
                    errors += "${p.name}: не удалось получить сертификат сервера (${e.userMessage()})"
                    null
                }
            }
        }

    private fun ParsedProfile.toStored(subscriptionId: String?) = StoredProfile(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { address },
        protocol = protocol,
        address = address,
        port = port,
        network = network,
        security = security,
        link = link,
        outbounds = outbounds,
        subscriptionId = subscriptionId,
        createdAt = System.currentTimeMillis(),
    )

    private suspend fun downloadSubscription(url: String): Pair<SubscriptionParseResult, libxray.FetchResult> =
        withContext(Dispatchers.IO) {
            // Direct first (panels are usually reachable); then through the
            // selected server in case the panel is blocked.
            val result = try {
                XrayCore.fetch(url, null)
            } catch (direct: Exception) {
                val via = profiles.value.selected?.outbounds ?: throw direct
                AppLog.w("subscription direct download failed, retrying via proxy", direct)
                XrayCore.fetch(url, via)
            }
            XrayCore.parseSubscription(result.body) to result
        }

    private suspend fun addSubscription(url: String) {
        if (profiles.value.subscriptions.any { it.url == url }) {
            message("Эта подписка уже добавлена")
            return
        }
        val (parsed, fetched) = downloadSubscription(url)
        val errors = parsed.errors.toMutableList()
        val ready = pinWhereNeeded(parsed.profiles, errors)
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = decodeTitle(fetched.profileTitle) ?: url.toUri().host ?: "Подписка",
            url = url,
            updatedAt = System.currentTimeMillis(),
            userInfo = fetched.userInfo.ifBlank { null },
        )
        val stored = ready.map { it.toStored(sub.id) }
        repo.updateProfiles { s ->
            s.copy(
                profiles = s.profiles + stored,
                subscriptions = s.subscriptions + sub,
                selectedId = s.selectedId ?: stored.firstOrNull()?.id,
            )
        }
        checkWhitelist(stored)
        message("Подписка «${sub.name}»: серверов ${stored.size}")
    }

    fun refreshSubscription(id: String, quiet: Boolean = false) = viewModelScope.launch {
        val sub = profiles.value.subscriptions.firstOrNull { it.id == id } ?: return@launch
        if (!quiet) _busy.value = "Обновление подписки…"
        try {
            val (parsed, fetched) = downloadSubscription(sub.url)
            val errors = parsed.errors.toMutableList()
            val ready = pinWhereNeeded(parsed.profiles, errors)
            val oldSelected = profiles.value.selected
            val stored = ready.map { it.toStored(sub.id) }
            val next = repo.updateProfiles { s ->
                val keepSelection = oldSelected?.takeIf { it.subscriptionId == sub.id }?.let { old ->
                    stored.firstOrNull { it.name == old.name && it.address == old.address && it.port == old.port }
                        ?: stored.firstOrNull { it.address == old.address && it.port == old.port }
                }
                val others = s.profiles.filterNot { it.subscriptionId == sub.id }
                val selectedId = when {
                    oldSelected?.subscriptionId != sub.id -> s.selectedId
                    keepSelection != null -> keepSelection.id
                    else -> stored.firstOrNull()?.id ?: others.firstOrNull()?.id
                }
                s.copy(
                    profiles = others + stored,
                    subscriptions = s.subscriptions.map {
                        if (it.id == sub.id) it.copy(updatedAt = System.currentTimeMillis(), userInfo = fetched.userInfo.ifBlank { null }, lastError = null) else it
                    },
                    selectedId = selectedId,
                )
            }
            checkWhitelist(stored)
            if (oldSelected != null && oldSelected.subscriptionId == sub.id && next.selected?.outbounds != oldSelected.outbounds) reconnectIfRunning()
            if (!quiet) message("Подписка обновлена: серверов ${stored.size}")
        } catch (e: Exception) {
            repo.updateProfiles { s -> s.copy(subscriptions = s.subscriptions.map { if (it.id == sub.id) it.copy(lastError = e.userMessage()) else it }) }
            if (!quiet) message("Не удалось обновить подписку: ${e.userMessage()}")
        } finally {
            if (!quiet) _busy.value = null
        }
    }

    fun deleteSubscription(id: String) = viewModelScope.launch {
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

    private fun decodeTitle(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.startsWith("base64:")) {
            return try {
                String(Base64.decode(t.removePrefix("base64:"), Base64.DEFAULT)).trim().ifEmpty { null }
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return t
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

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        val before = settings.value
        val after = repo.updateSettings(transform)
        if (after != before) reconnectIfRunning(delayMs = 800)
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
}
