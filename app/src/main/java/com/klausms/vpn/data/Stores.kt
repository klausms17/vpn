package com.klausms.vpn.data

import android.content.Context
import java.io.File

/** File locations shared by the UI and VPN processes. */
object Stores {
    private fun dataDir(context: Context) = File(context.filesDir, "data")

    fun profiles(context: Context) =
        JsonFileStore(File(dataDir(context), "profiles.json"), ProfilesState.serializer()) { ProfilesState() }

    fun settings(context: Context) =
        JsonFileStore(File(dataDir(context), "settings.json"), AppSettings.serializer()) { AppSettings() }

    /** The UI process's only: see [com.klausms.vpn.ui.AccountSession]. */
    fun account(context: Context) =
        JsonFileStore(File(dataDir(context), "account.json"), AccountState.serializer()) { AccountState() }
}
