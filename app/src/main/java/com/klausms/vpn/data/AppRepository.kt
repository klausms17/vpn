package com.klausms.vpn.data

import android.content.Context
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.widget.VpnWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where profiles are read and saved: the UI's repository, or the file itself in the VPN process. */
interface ProfilesAccess {
    /** The latest known state (may lag behind the file by one change of the other process). */
    fun snapshot(): ProfilesState

    /** Applies [transform] to the state on disk and saves it; see [JsonFileStore.update]. */
    suspend fun updateProfiles(transform: (ProfilesState) -> ProfilesState): ProfilesState
}

/** The profiles file used directly, for the VPN process. */
class DiskProfiles(context: Context) : ProfilesAccess {
    private val store = Stores.profiles(context.applicationContext)

    override fun snapshot(): ProfilesState = store.read()

    override suspend fun updateProfiles(transform: (ProfilesState) -> ProfilesState): ProfilesState =
        withContext(Dispatchers.IO) { store.update(transform) }
}

/**
 * Profiles and settings for the UI process, persisted atomically. Changes
 * are applied to what is on disk, so nothing the VPN process saved is lost.
 */
class AppRepository(context: Context) : ProfilesAccess {
    private val appContext = context.applicationContext
    private val profileStore = Stores.profiles(context)
    private val settingsStore = Stores.settings(context)
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow(profileStore.read())
    val profiles: StateFlow<ProfilesState> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    override fun snapshot(): ProfilesState = _profiles.value

    override suspend fun updateProfiles(transform: (ProfilesState) -> ProfilesState): ProfilesState = mutex.withLock {
        val next = withContext(Dispatchers.IO) {
            val saved = profileStore.update(transform)
            // The widget shows the selected server's name.
            if (saved != _profiles.value) VpnWidget.requestRefresh(appContext)
            saved
        }
        _profiles.value = next
        next
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        val next = withContext(Dispatchers.IO) { settingsStore.update(transform) }
        _settings.value = next
        next
    }

    /** Picks up what the VPN process saved (failover, subscription refresh). */
    suspend fun reload() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                _profiles.value = profileStore.readStrict()
                _settings.value = settingsStore.readStrict()
            } catch (e: Exception) {
                // Keep what is shown; the next change reports the problem.
                AppLog.w("reload failed", e)
            }
        }
    }
}
