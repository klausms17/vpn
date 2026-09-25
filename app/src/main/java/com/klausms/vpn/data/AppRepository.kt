package com.klausms.vpn.data

import android.content.Context
import com.klausms.vpn.widget.VpnWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Profiles and settings for the UI process, persisted atomically. */
class AppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val profileStore = Stores.profiles(context)
    private val settingsStore = Stores.settings(context)
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow(profileStore.read())
    val profiles: StateFlow<ProfilesState> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun updateProfiles(transform: (ProfilesState) -> ProfilesState): ProfilesState = mutex.withLock {
        val next = transform(_profiles.value)
        if (next != _profiles.value) {
            withContext(Dispatchers.IO) {
                profileStore.write(next)
                // The widget shows the selected server's name.
                VpnWidget.requestRefresh(appContext)
            }
            _profiles.value = next
        }
        next
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings): AppSettings = mutex.withLock {
        val next = transform(_settings.value)
        if (next != _settings.value) {
            withContext(Dispatchers.IO) { settingsStore.write(next) }
            _settings.value = next
        }
        next
    }
}
