package com.klausms.vpn.service

import android.content.Context
import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.Downloader
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.SubscriptionUpdater
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException

/**
 * [SubscriptionSource] over [SubscriptionUpdater], for the VPN process:
 * downloads directly, or through the running core's server. Keeps no
 * state of its own; the download blocks, so call it on IO.
 */
internal class UpdaterSubscriptionSource(context: Context, profiles: ProfilesAccess) : SubscriptionSource {
    private val updater = SubscriptionUpdater(context, profiles)

    override suspend fun refresh(subId: String, through: CoreHandle?, runningId: String?): Refreshed = try {
        val downloader = through?.downloader() ?: Downloader { url, headers -> XrayCore.fetch(url, null, headers, DIRECT_TIMEOUT_MS) }
        val outcome = updater.refresh(subId, downloader, runningId = runningId)
        Refreshed(applied = outcome?.applied ?: false, runningChanged = outcome?.runningChanged == true)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w("subscription refresh failed: ${e.userMessage()}")
        Refreshed(applied = null, runningChanged = false)
    }

    private companion object {
        const val DIRECT_TIMEOUT_MS = 10_000
    }
}
