package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile

/** A [NetworkInfo] that tests set: [network] is the default network, [netState] what it offers. */
internal class FakeNetworkInfo(
    var network: NetId? = NetId(1),
    var netState: NetState? = NetState(hasNetwork = true, captive = false, cellular = false),
) : NetworkInfo {
    override fun active(): NetId? = network

    override fun state(): NetState? = netState
}

/**
 * A [RuntimeStore] in memory. [switchBudget] and [searchBudget]: whether
 * an automatic switch and a search are still allowed; [switchesTaken] and
 * [searchesTaken] count the ones used.
 */
internal class FakeRuntime : RuntimeStore {
    var run = true
    var switchBudget = true
    var searchBudget = true
    var switchesTaken = 0
    var searchesTaken = 0
    var awayState: Failover.Away? = null
    var returnedState: Failover.Returned? = null

    override fun shouldRun(): Boolean = run

    override fun setShouldRun(value: Boolean) {
        run = value
    }

    override fun setVpnConsented(value: Boolean) = Unit

    override fun allowFailover(take: Boolean): Boolean {
        if (!switchBudget) return false
        if (take) switchesTaken++
        return true
    }

    override fun allowSearch(): Boolean {
        if (!searchBudget) return false
        searchesTaken++
        return true
    }

    override fun away(): Failover.Away? = awayState

    override fun setAway(away: Failover.Away?) {
        awayState = away
    }

    override fun returned(): Failover.Returned? = returnedState

    override fun setReturned(returned: Failover.Returned) {
        returnedState = returned
    }
}

/** A [ProfilesAccess] in memory: [state] is what is saved. */
internal class FakeProfiles(var state: ProfilesState = ProfilesState()) : ProfilesAccess {
    override fun snapshot(): ProfilesState = state

    override suspend fun updateProfiles(transform: (ProfilesState) -> ProfilesState): ProfilesState =
        transform(state).also { state = it }
}

/** A [NoticeSink] that records every notice put up, in order, and what was cleared. */
internal class FakeNotices : NoticeSink {
    data class Shown(val notice: String?, val replacing: Set<String?>?)

    override var base: String? = null
    val calls = mutableListOf<Shown>()
    var failuresCleared = 0

    /** The minAgeMs of every [clearSwitch], in order. */
    val switchClears = mutableListOf<Long>()

    /** The notices put up, in order. */
    val shown: List<String?> get() = calls.map { it.notice }

    override suspend fun show(notice: String?, e: Long, replacing: Set<String?>?) {
        calls += Shown(notice, replacing)
    }

    override suspend fun clearFailure(e: Long) {
        failuresCleared++
    }

    override fun clearSwitch(e: Long, minAgeMs: Long) {
        switchClears += minAgeMs
    }
}

/** A [BlockReports] that records every report. */
internal class FakeReports : BlockReports {
    data class Report(val failedId: String, val allDown: Boolean, val winnerId: String?, val whitelist: Boolean)

    val sent = mutableListOf<Report>()

    override fun report(failed: StoredProfile, state: ProfilesState, allDown: Boolean, winner: StoredProfile?, whitelist: Boolean) {
        sent += Report(failed.id, allDown, winner?.id, whitelist)
    }
}

/** A [SubscriptionSource] whose refresh does and answers [answer]; by default the download fails. */
internal class FakeSubscriptionSource : SubscriptionSource {
    var answer: () -> Refreshed = { Refreshed(applied = null, runningChanged = false) }

    /** (subId, through) of every refresh, in order. */
    val refreshed = mutableListOf<Pair<String, CoreHandle?>>()

    override suspend fun refresh(subId: String, through: CoreHandle?, runningId: String?): Refreshed {
        refreshed += subId to through
        return answer()
    }
}
