package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.util.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Downloads a subscription again and saves what it brings. */
internal interface SubscriptionSource {
    /**
     * Downloads subscription [subId] directly, or through the running core
     * [through], and saves the result; the old servers stay when the panel
     * sends none. [runningId]: the server the tunnel runs, to tell whether
     * the refresh changed it. Never throws, except to cancel: a download
     * that failed is `Refreshed(applied = null)`.
     */
    suspend fun refresh(subId: String, through: CoreHandle?, runningId: String?): Refreshed
}

/**
 * Downloads a failing server's subscription again during a search: the
 * panel may have moved the servers (new addresses or keys). Owns the one
 * refresh that may run at a time, and the refresh owed: a subscription
 * whose direct download failed is downloaded through the tunnel once a
 * server works again.
 *
 * Thread-safe; called from checks on IO. Refreshes run in [scope] on [io],
 * on their own, so a switch meanwhile does not cut them short. After each
 * one the app, if open, reloads the servers ([profilesChanged]).
 */
internal class SubscriptionRefresher(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: Clock,
    private val source: SubscriptionSource,
    private val session: () -> TunnelSession?,
    private val profilesChanged: () -> Unit,
) {
    @Volatile
    private var job: Deferred<Refreshed>? = null

    @Volatile
    private var owed: String? = null

    /** [failed]'s subscription, when it may be downloaded again now: at most every 10 minutes and one at a time. */
    fun refreshable(state: ProfilesState, failed: StoredProfile): Subscription? {
        val sub = state.subscriptions.firstOrNull { it.id == failed.subscriptionId } ?: return null
        if (job?.isActive == true || !Failover.refreshDue(sub, clock.wall())) return null
        return sub
    }

    /** Downloads [sub] again, directly: the running server is what fails. */
    fun startDirect(sub: Subscription): Deferred<Refreshed> =
        scope.async(io) {
            val result = refresh(sub.id, through = null)
            // Blocked outside the tunnel: once a server works, through it.
            if (result.applied == null) owed = sub.id
            result
        }.also { job = it }

    /**
     * A check through [core] passed: downloads the owed subscription, if
     * any, through the tunnel; [onRunningChanged] when that changed the
     * running server. Dropped while another refresh runs.
     */
    fun payOwed(core: CoreHandle, onRunningChanged: () -> Unit) {
        val subId = owed ?: return
        owed = null
        if (job?.isActive == true) return
        job = scope.async(io) {
            refresh(subId, through = core).also { if (it.runningChanged) onRunningChanged() }
        }
    }

    private suspend fun refresh(subId: String, through: CoreHandle?): Refreshed = try {
        source.refresh(subId, through, runningId = session()?.profile?.id)
    } finally {
        profilesChanged()
    }
}
