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
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.SubscriptionUpdater
import com.klausms.vpn.data.pinWhereNeeded
import com.klausms.vpn.data.renamed
import com.klausms.vpn.data.withNewKeys
import com.klausms.vpn.data.withSelected
import com.klausms.vpn.data.withoutProfile
import com.klausms.vpn.data.withoutSubscription
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.PhoneSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/**
 * Whether an update check may run now, [lastAttempt] being the start of the
 * last one that found nothing (0: none). A failed check is retried soon, not
 * after the full 12 hours; a clock set back counts as a retry.
 */
internal fun updateRetryDue(lastAttempt: Long, now: Long): Boolean =
    now - lastAttempt >= UPDATE_RETRY_MS || lastAttempt > now

/** A failed update check waits this long (the next app opening after it). */
internal const val UPDATE_RETRY_MS = 5 * 60_000L

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as App).repository
    private val ui = (app as App).ui
    val vpn = VpnClient(app)

    val profiles: StateFlow<ProfilesState> = repo.profiles
    val settings: StateFlow<AppSettings> = repo.settings
    val status = vpn.status

    private val _pings = MutableStateFlow<Map<String, PingResult>>(emptyMap())
    val pings: StateFlow<Map<String, PingResult>> = _pings.asStateFlow()

    /** Server address -> 1 (on the Russian mobile whitelist), 0, -1 (partly). */
    val whitelist: StateFlow<Map<String, Int>> = ui.whitelist

    /** Text of a running long operation, or null. */
    val busy: StateFlow<String?> = ui.busy.text

    /** «Обновить списки» is running; a second run would delete this one's files. */
    val geoUpdating: StateFlow<Boolean> = ui.geoUpdate.running

    private val _backgroundTip = MutableStateFlow(false)

    /**
     * Once, after the first connection, on a phone that may stop the VPN in
     * the background: offer to allow it before it happens.
     */
    val backgroundTip: StateFlow<Boolean> = _backgroundTip.asStateFlow()

    val geoVersion: StateFlow<Long> = ui.geoVersion

    val coreVersion: StateFlow<String> = ui.coreVersion

    val messages: Flow<String> = ui.messages

    /** A newer build of the app on the owner's panel, until installed or put off. */
    private val _update = MutableStateFlow<AppUpdate?>(null)
    val update: StateFlow<AppUpdate?> = _update.asStateFlow()

    private var staleJob: Job? = null

    /** Survives the activity finishing: see [App.appScope]. */
    private val appScope = (app as App).appScope

    /**
     * Owned by this ViewModel, not the app: isUp reads this ViewModel's
     * VpnClient, the only source of the tunnel status.
     */
    private val tunnel = TunnelController(
        appScope,
        VpnTunnelCommands(app),
        isUp = { isTunnelUp },
        isFresh = { vpn.fresh.value },
        awaitSaves = repo::awaitSaves,
    )

    // Small UI state; declared before init, whose coroutine reads it.
    private val uiPrefs by lazy { app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE) }

    private val updater = SubscriptionUpdater(app, repo)

    private val downloader = (app as App).downloader

    private val accountSession = (app as App).account

    /** This phone's account, for the account screen. */
    val accountView: StateFlow<AccountView> = accountSession.view

    /**
     * Saving can fail (storage full). Report it instead of crashing; the
     * in-memory state only changes after a successful write.
     */
    private val saveErrors = CoroutineExceptionHandler { _, e ->
        AppLog.e("save failed", e)
        message("Не удалось сохранить: ${e.userMessage()}")
    }

    init {
        ui.start()
        // What the last update check found, until the next one.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _update.compareAndSet(null, offer(AppUpdate.parse(uiPrefs.getString(KEY_UPDATE, null))))
            } catch (e: Exception) {
                AppLog.w("saved update", e)
            }
        }
        // The VPN process changed the servers (failover, subscription refresh).
        viewModelScope.launch {
            vpn.profilesChanged.collect {
                repo.reload()
                ui.checkWhitelist(profiles.value.profiles)
            }
        }
        viewModelScope.launch {
            status.first { it.state == VpnState.CONNECTED }
            val show = withContext(Dispatchers.IO) {
                if (uiPrefs.getBoolean(KEY_BACKGROUND_TIP, false)) return@withContext false
                // Nothing to set up on this phone: never asked.
                PhoneSettings.needsSetup(app).also { if (!it) uiPrefs.edit { putBoolean(KEY_BACKGROUND_TIP, true) } }
            }
            if (show) _backgroundTip.value = true
        }
    }

    /** The background tip was answered (or put off): it is not shown again. */
    fun backgroundTipDone() {
        _backgroundTip.value = false
        uiPrefs.edit { putBoolean(KEY_BACKGROUND_TIP, true) }
    }

    /**
     * The app is on screen since [onAppVisible], until [onAppHidden]. Main
     * thread only. A rotation recreates the activity but not this ViewModel:
     * the new activity's start is not a new visit.
     */
    private var onScreen = false

    /**
     * The app came on screen (also back from Recents): picks up what the VPN
     * process saved meanwhile, then refreshes old subscriptions and looks
     * for a newer app build.
     */
    fun onAppVisible() {
        if (onScreen) return
        onScreen = true
        viewModelScope.launch {
            repo.reload()
            // Servers the VPN process added by itself (a subscription refresh).
            ui.checkWhitelist(profiles.value.profiles)
            if (staleJob?.isActive == true) return@launch
            staleJob = viewModelScope.launch {
                // First: access granted meanwhile brings the account's servers.
                account { checkIfDue() }.join()
                refreshStaleSubscriptions()
                // After the refresh: it may have brought the panel's app address.
                checkForUpdate()
            }
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

    private fun message(text: String) = ui.message(text)

    // ------------------------------------------------------------- update

    /**
     * Asks the owner's panel for its latest app build (version.json of the
     * first subscription that names one), at most every 12 hours; like a
     * subscription, directly and then through the selected server. Quiet:
     * a failure or a malformed answer changes nothing, and the next opening
     * of the app a few minutes later tries again (the phone may have been
     * in the metro or behind a Wi-Fi login page).
     */
    private suspend fun checkForUpdate() {
        val url = profiles.value.subscriptions.firstNotNullOfOrNull { it.appUrl } ?: return
        val via = profiles.value.selected?.outbounds
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                if (!AppUpdate.checkDue(uiPrefs.getLong(KEY_UPDATE_CHECKED, 0L), now)) return@withContext
                if (!updateRetryDue(uiPrefs.getLong(KEY_UPDATE_ATTEMPT, 0L), now)) return@withContext
                // Saved before the fetch: a check cut short (app closed) also waits.
                uiPrefs.edit { putLong(KEY_UPDATE_ATTEMPT, now) }
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
                uiPrefs.edit {
                    putString(KEY_UPDATE, latest.toJson())
                    putLong(KEY_UPDATE_CHECKED, now)
                    remove(KEY_UPDATE_ATTEMPT)
                }
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
        val selected = profiles.value.selected
        if (selected == null) {
            message("Сначала добавьте ключ")
            return
        }
        tunnel.connect(selected.id)
    }

    fun stopVpn() = tunnel.disconnect()

    fun startVpnDenied() = message("Без разрешения на VPN подключиться нельзя. Если включён другой VPN-клиент как «постоянный», отключите его.")

    fun notificationPermissionAsked(): Boolean = uiPrefs.getBoolean("notif_asked", false)

    fun markNotificationPermissionAsked() = uiPrefs.edit { putBoolean("notif_asked", true) }

    /** The app left the screen; see [TunnelController.flushPending]. */
    fun onAppHidden() {
        onScreen = false
        tunnel.flushPending()
    }

    // ------------------------------------------------------------ profiles

    /**
     * The user picked server [id]; the service is told so (their choice then
     * wins over automatic switches). A running tunnel moves to it, also when
     * it is already the selection but the tunnel runs another (a refresh
     * removed the running one). In the app scope, like the other changes
     * that reach the tunnel ([TunnelController.reconnectIfRunning]).
     */
    fun select(id: String) = appScope.launch(saveErrors) {
        val up = isTunnelUp
        if (profiles.value.selectedId == id && (!up || status.value.profileId == id)) return@launch
        // Gone meanwhile: the VPN process replaced the list.
        if (repo.updateProfiles { s -> s.withSelected(id) }.selectedId != id) return@launch
        tunnel.picked(id, up)
    }

    fun rename(id: String, name: String) = viewModelScope.launch(saveErrors) {
        val clean = name.trim().take(80)
        if (clean.isEmpty()) return@launch
        repo.updateProfiles { s -> s.renamed(id, clean) }
    }

    fun delete(id: String) = appScope.launch(saveErrors) {
        val wasSelected = profiles.value.selectedId == id
        val next = repo.updateProfiles { s -> s.withoutProfile(id) }
        _pings.update { it - id }
        if (wasSelected) {
            if (next.selected == null) stopVpn() else tunnel.reconnectIfRunning()
        }
    }

    /**
     * Adds share links, a subscription URL, or a pasted subscription body. In
     * the app scope: a slow subscription download is not dropped when the
     * user leaves the app meanwhile.
     */
    fun import(text: String) = appScope.launch(saveErrors) {
        val input = text.trim()
        if (input.isEmpty()) return@launch
        val op = ui.busy.start("Добавление…")
        try {
            val subscription = ImportText.subscriptionUrl(input)
            if (subscription != null) addSubscription(subscription) else addLinks(input, ImportText.links(input))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            message(e.userMessage())
        } finally {
            op.end()
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
        var added = emptyList<StoredProfile>()
        if (ready.isNotEmpty()) {
            // Against the saved list, inside the save: another import may have just added these keys.
            repo.updateProfiles { s -> s.withNewKeys(ready).also { added = it.second }.first }
            ui.checkWhitelist(added)
        }
        message(
            when {
                added.isNotEmpty() && errors.isEmpty() -> "Добавлено серверов: ${added.size}"
                added.isNotEmpty() -> "Добавлено: ${added.size}, пропущено: ${errors.size} (${errors.first()})"
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
        ui.checkWhitelist(outcome.servers)
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
        val op = if (quiet) null else ui.busy.start("Обновление подписки…")
        try {
            // A manual refresh also renews pinned certificates.
            val outcome = updater.refresh(id, downloader, runningId = status.value.profileId, repin = !quiet) ?: return@launch
            ui.checkWhitelist(outcome.servers)
            if (outcome.runningChanged) tunnel.reconnectIfRunning()
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
            op?.end()
        }
    }

    /** Subscription [id] removed with its servers; the account's goes only with the account, so this signs out. */
    fun deleteSubscription(id: String) = appScope.launch(saveErrors) {
        if (profiles.value.subscriptions.any { it.id == id && it.account }) {
            account { logout() }
            return@launch
        }
        val selectedWasInside = profiles.value.selected?.subscriptionId == id
        val next = repo.updateProfiles { s -> s.withoutSubscription(id) }
        if (selectedWasInside) {
            if (next.selected == null) stopVpn() else tunnel.reconnectIfRunning()
        }
    }

    // ------------------------------------------------------------- account

    /**
     * Runs [work] on the account in the app scope: it outlives the screen.
     * The account's servers may come or go with it: a tunnel on a server
     * that is gone moves to the selection, or stops when there is none.
     */
    fun account(work: suspend AccountSession.() -> Unit) = appScope.launch(saveErrors) {
        val running = status.value.profileId?.takeIf { isTunnelUp }
        accountSession.work()
        val now = profiles.value
        ui.checkWhitelist(now.profiles)
        if (running != null && now.profiles.none { it.id == running }) {
            if (now.selected == null) stopVpn() else tunnel.reconnectIfRunning()
        }
    }

    // ------------------------------------------------------------- testing

    fun ping(ids: List<String>) = viewModelScope.launch {
        // A server already being measured is not measured again at the same
        // time («Проверить все» tapped twice): each check starts a core.
        val running = _pings.value
        val targets = profiles.value.profiles.filter { it.id in ids && running[it.id] !is PingResult.Testing }
        if (targets.isEmpty()) return@launch
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

    // ------------------------------------------------------------ settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) = appScope.launch(saveErrors) {
        val before = settings.value
        val after = repo.updateSettings(transform)
        if (after != before) tunnel.reconnectIfRunning(delayMs = 800)
    }

    /**
     * Picking apps one by one is saved at once but reaches the tunnel only
     * when the list is closed ([applyAppLists]): one reconnect, not one per
     * tap.
     */
    fun updateAppLists(transform: (AppSettings) -> AppSettings) = appScope.launch(saveErrors) {
        // Set inside the save, so applyAppLists (waiting for the saves) sees it.
        repo.updateSettings { s -> transform(s).also { if (it != s) tunnel.markAppListsChanged() } }
    }

    fun applyAppLists() = appScope.launch(saveErrors) { tunnel.applyAppLists() }

    /** One run at a time in the app ([UiSession.geoUpdate]): runs share their download folder and files. */
    fun updateGeo() {
        if (ui.geoUpdate.running.value) return
        // In the app scope: the result is applied even when the screen closed during the download.
        appScope.launch {
            if (!ui.geoUpdate.tryStart()) return@launch
            val app = getApplication<Application>()
            val op = ui.busy.start("Обновление баз…")
            try {
                val via = profiles.value.selected?.outbounds
                withContext(Dispatchers.IO) {
                    GeoFiles.update(app, via) { op.caption = it }
                    ui.refreshGeoVersion()
                }
                message("Базы обновлены")
                tunnel.reconnectIfRunning()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.e("geo update failed", e)
                message("Не удалось обновить базы: ${e.userMessage()}")
            } finally {
                op.end()
                ui.geoUpdate.end()
            }
        }
    }

    override fun onCleared() {
        vpn.unbind()
    }

    private companion object {
        const val KEY_UPDATE = "update_latest"
        const val KEY_UPDATE_CHECKED = "update_checked_at"
        const val KEY_UPDATE_ATTEMPT = "update_attempt_at"
        const val KEY_BACKGROUND_TIP = "background_tip_shown"
        const val KEY_UPDATE_DISMISSED = "update_dismissed"
        const val UPDATE_TIMEOUT_MS = 15_000
    }
}
