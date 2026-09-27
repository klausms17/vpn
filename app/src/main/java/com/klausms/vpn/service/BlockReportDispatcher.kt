package com.klausms.vpn.service

import android.content.Context
import android.net.Network
import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.DirectNet
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Hands block reports to [BlockReporter] in the background: finds the
 * failed server's subscription and, after a switch, tells the mobile
 * whitelist from a block. Owns the one [BlockReporter], and with it the
 * memory of what was reported lately.
 *
 * Thread-safe. [report] returns at once: the switch and the search never
 * wait for a report, and nothing a report does can fail the tunnel. The
 * report runs in [scope] on [io].
 */
internal class BlockReportDispatcher(
    context: Context,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val netInfo: SystemNetworkInfo,
    private val underlying: () -> Network?,
    private val mobileWhitelist: WhitelistLookup,
    private val direct: DirectNet,
    private val core: () -> CoreHandle?,
) {
    private val reporter = BlockReporter(context)

    /**
     * Tells the owner's panel that [failed] stopped answering here, if its
     * subscription in [state] asked for that. [allDown]: no other server
     * answered either. [winner]: the server the tunnel switched to.
     * [whitelist]: the mobile whitelist is already known to explain the
     * failure.
     */
    fun report(
        failed: StoredProfile,
        state: ProfilesState,
        allDown: Boolean = false,
        winner: StoredProfile? = null,
        whitelist: Boolean = false,
    ) {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return
        if (sub.reportUrl == null) return
        val network = underlying()
        scope.launch(io) {
            try {
                val listed = whitelist || winner != null && looksLikeWhitelist(failed, winner)
                reporter.report(failed, sub, network, allDown, listed, core)
            } catch (ex: Exception) {
                if (ex is CancellationException) throw ex
                AppLog.w("block report failed", ex)
            }
        }
    }

    /**
     * Whether the switch from [failed] to [winner] looks like the mobile
     * operator letting through only its whitelist, not a block of [failed]:
     * on mobile data, [winner] is on the whitelist and [failed] is not, and
     * outside the tunnel a foreign site does not open while a Russian one
     * does (a block of [failed] alone would leave the foreign site open).
     */
    private suspend fun looksLikeWhitelist(failed: StoredProfile, winner: StoredProfile): Boolean {
        if (netInfo.state()?.cellular != true) return false
        val winnerHost = Failover.host(winner)
        val failedHost = Failover.host(failed)
        val listed = mobileWhitelist.listed(listOf(winnerHost, failedHost).distinct())
        return winnerHost in listed && failedHost !in listed &&
            !direct.opens(XrayCore.TEST_URL) && direct.opens(DirectNet.DIRECT_URL)
    }
}
