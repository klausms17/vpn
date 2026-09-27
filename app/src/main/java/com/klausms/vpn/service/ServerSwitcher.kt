package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.Clock
import kotlinx.coroutines.CancellationException

/**
 * Moves the tunnel to another server, and keeps what that means: the
 * server switched to becomes the selection, a switch that happened marks
 * the failed server and reports it, and the user's own server is gone
 * back to once it answers again.
 *
 * Owns no state: the way back lives in [runtime], the failed servers in
 * [memory]. Threading: [switchTo] runs only inside [TunnelControl.submit]
 * and [afterStart] on the engine's worker as part of a start;
 * [restartOnSaved] may be called from any thread; [returnHomeIfItAnswers]
 * probes, so it runs on IO.
 */
internal class ServerSwitcher(
    private val tunnel: TunnelControl,
    private val profiles: ProfilesAccess,
    private val runtime: RuntimeStore,
    private val memory: FailureMemory,
    private val failover: FailoverSearch,
    private val reports: BlockReports,
    private val notices: NoticeSink,
    private val clock: Clock,
    private val epoch: Epoch,
    private val status: () -> VpnStatus,
    private val profilesChanged: () -> Unit,
) {
    /**
     * Moves the tunnel from [failed] to [winnerId], unless something
     * changed since the probe began: another core or network (epoch [e]),
     * the VPN turned off, another server chosen. [returning]: back to the
     * user's server, which is no failure of [failed]. [report]: tell the
     * owner's panel that [failed] does not answer here.
     */
    suspend fun switchTo(e: Long, failed: StoredProfile, winnerId: String, returning: Boolean = false, report: Boolean = true) {
        try {
            if (!epoch.isCurrent(e) || tunnel.session == null || !runtime.shouldRun() ||
                status().state != VpnState.CONNECTED
            ) {
                AppLog.i("server switch dropped: the tunnel changed meanwhile")
                return
            }
            val saved = profiles.snapshot()
            val winner = saved.profiles.firstOrNull { it.id == winnerId }
            if (winner == null || !Failover.selectionFollowsFailed(saved, failed.id)) {
                // The user's choice wins; their reconnect follows.
                AppLog.i("server switch dropped: another server was chosen")
                notices.clearFailure(e)
                return
            }
            if (!runtime.allowFailover()) {
                if (!returning) notices.show(Failover.NOTICE_PICK_ANOTHER, e)
                return
            }
            val notice = if (returning || winner.id == failed.id) null else Failover.switchedNotice(winner.name, failed.name)
            tunnel.start(
                StartRequest(
                    tunnel.latestStartId(),
                    userRequested = false,
                    switch = Switch(winner.id, failed.id, expectedSelection = saved.selectedId, notice = notice),
                ),
            )
            // Only a switch that happened marks the failed server: one dropped
            // on the way (a reconnect came first) proves nothing about it.
            if (!returning && winner.id != failed.id && tunnel.session?.profile?.id == winner.id) {
                memory.markFailed(failed.id)
                // Up on another server: the phone is online, so the failed
                // one does not answer from this network.
                if (report) reports.report(failed, saved, winner = winner)
            }
        } catch (ex: Exception) {
            if (ex is CancellationException) throw ex
            AppLog.w("server switch failed", ex)
        }
    }

    /**
     * Part of the start [req] that brought [runningId] up. Only a server
     * whose core came up becomes the selection: after an automatic switch
     * it does, and the user's server is remembered; any other start may
     * end the way back to it.
     */
    suspend fun afterStart(runningId: String, req: StartRequest) {
        val switch = req.switch
        if (switch != null) {
            if (saveSwitch(switch.failedId, runningId, switch.expectedSelection)) trackAway(switch.failedId, runningId)
        } else {
            forgetAwayUnless(runningId, req.picked)
        }
    }

    /**
     * Runs the saved selection again, unless [running] no longer runs
     * (another start came first) or the tunnel was turned off. Not tied to
     * the check's epoch: a network change during the download resets
     * connections but leaves the removed server running.
     */
    fun restartOnSaved(running: StoredProfile) {
        tunnel.submit {
            if (tunnel.session?.profile !== running || !runtime.shouldRun()) return@submit
            AppLog.i("the running server changed in the subscription, restarting")
            tunnel.start(StartRequest(tunnel.latestStartId(), userRequested = false))
        }
    }

    /**
     * After an automatic switch the tunnel stays on the other server only
     * while needed: once the user's own server answers again (at the
     * earliest 30 minutes later, then less and less often if it keeps
     * failing), go back to it. [running] runs on [core]; [e]: the check's epoch.
     */
    suspend fun returnHomeIfItAnswers(e: Long, core: CoreHandle, running: StoredProfile) {
        val away = runtime.away() ?: return
        val now = clock.elapsed()
        if (away.to != running.id || !Failover.returnDue(away, now) || memory.failedRecently(away.home)) return
        val saved = profiles.snapshot()
        val home = saved.profiles.firstOrNull { it.id == away.home }
        if (home == null) {
            runtime.setAway(null)
            return
        }
        // The user chose another server meanwhile; their start follows.
        if (saved.selectedId != running.id || !runtime.allowFailover(take = false)) return
        val answered = failover.probe(core, listOf(home)).first() >= 0
        if (!epoch.isCurrent(e)) return
        if (!answered) {
            runtime.setAway(Failover.returnFailed(away, now))
            return
        }
        AppLog.i("the chosen server answers again, going back to it")
        tunnel.submit { switchTo(e, running, home.id, returning = true) }
    }

    /**
     * The server the tunnel switched to becomes the selection, unless the
     * user chose another meanwhile. Returns whether it did.
     */
    private suspend fun saveSwitch(failedId: String, winnerId: String, expected: String?): Boolean {
        try {
            val saved = profiles.updateProfiles { s -> Failover.selectInstead(s, failedId, winnerId, expected) }
            if (saved.selectedId == winnerId) {
                profilesChanged()
                return true
            }
            AppLog.i("another server was chosen meanwhile, selection kept")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // The tunnel runs anyway; only the next start picks the old server.
            AppLog.w("could not save the new selection", e)
        }
        return false
    }

    /** An automatic switch from [failedId] to [winnerId] happened: remember the user's server. */
    private fun trackAway(failedId: String, winnerId: String) {
        val away = runtime.away()
        val now = clock.elapsed()
        if (away != null && winnerId == away.home) runtime.setReturned(Failover.Returned(away.home, now, away.backoff))
        runtime.setAway(Failover.afterSwitch(away, runtime.returned(), failedId, winnerId, now))
    }

    /**
     * A start that was no automatic switch: the user's server is no longer
     * worth going back to once they picked one, or the tunnel runs another
     * server than the one switched to (a delete or refresh moved it).
     */
    private fun forgetAwayUnless(runningId: String, picked: Boolean) {
        val away = runtime.away() ?: return
        if (picked || runningId != away.to) runtime.setAway(null)
    }
}
