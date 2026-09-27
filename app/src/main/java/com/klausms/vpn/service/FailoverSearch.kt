package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.DirectNet
import com.klausms.vpn.core.onlyWhitelistOpens
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray

/** What a search for another server came to; the caller carries it out. */
internal sealed interface SearchOutcome {
    /** Nothing more to do: the failed server answers again, or a notice says why the tunnel stays. */
    data object Done : SearchOutcome

    /**
     * Move the tunnel to [winnerId], which answered. [report]: tell the
     * owner's panel that the failed server does not answer from here.
     */
    data class SwitchTo(val winnerId: String, val report: Boolean) : SearchOutcome

    /**
     * Nothing answers, and the subscription refresh changed or removed the
     * running server: run what is saved now, so the app and the widget
     * show what really runs.
     */
    data object RestartOnSaved : SearchOutcome
}

/**
 * Looks for another server when the running one passes no traffic: probes
 * the candidates, all in one temporary core, and names the fastest that
 * answers. The tunnel stays as it is meanwhile, and for good when nothing
 * answers: nothing ever leaks outside the VPN. On the way it shows what it
 * does through [notices] (a single result could not say "searching" and
 * then keep probing), downloads the failed server's subscription again
 * and reports a server that nothing replaces.
 *
 * Owns the one probe that may run at a time and, per network, the last
 * search that found nothing. Thread-safe. [search] and [probe] block while
 * they probe, so they run on IO, never on the engine's worker or the main
 * thread.
 */
internal class FailoverSearch(
    private val clock: Clock,
    private val epoch: Epoch,
    private val netInfo: NetworkInfo,
    private val runtime: RuntimeStore,
    private val profiles: ProfilesAccess,
    private val notices: NoticeSink,
    private val direct: DirectNet,
    private val mobileWhitelist: WhitelistLookup,
    private val memory: FailureMemory,
    private val refresher: SubscriptionRefresher,
    private val reports: BlockReports,
) {
    // Per network: when a search there last found nothing, and the notice it
    // showed. Another network may reach servers this one could not, and the
    // same network back (Wi-Fi flapping) remembers. Guarded by itself.
    private val fruitless = HashMap<NetId?, Pair<Long, String>>()

    // One probe at a time: a probe's temporary core holds megabytes until it
    // ends, also when its result is no longer wanted.
    private val probeLock = Mutex()

    /**
     * [failed], running on [core], passes no traffic ([stalled]: it
     * answers, but downloads freeze). [e]: the tunnel's generation when the
     * check began; a search that outlives it ends without a result.
     * [recoveredInPlace] is asked when [failed] itself answers a new
     * connection; true settles the matter.
     */
    suspend fun search(
        e: Long,
        core: CoreHandle,
        failed: StoredProfile,
        stalled: Boolean,
        recoveredInPlace: suspend () -> Boolean,
    ): SearchOutcome {
        val net = netInfo.state()
        if (net?.hasNetwork != true) {
            AppLog.i("no network, not looking for another server")
            return SearchOutcome.Done
        }
        // Hotel or metro Wi-Fi before its login page: no server can answer yet.
        if (net.captive) {
            AppLog.i("the Wi-Fi asks to sign in, not looking for another server")
            notices.show(Failover.NOTICE_SIGN_IN, e)
            return SearchOutcome.Done
        }
        if (memory.keptByUser(failed.id)) {
            notices.show(Failover.NOTICE_PICK_ANOTHER, e)
            return SearchOutcome.Done
        }
        val network = netInfo.active()
        // Nothing answered on this network moments ago: say so again, search later.
        val last = fruitlessNotice(network)
        if (last != null) {
            notices.show(last, e)
            return SearchOutcome.Done
        }
        if (!runtime.allowFailover(take = false)) {
            AppLog.w("automatic server switches used up for now")
            notices.show(Failover.NOTICE_PICK_ANOTHER, e)
            return SearchOutcome.Done
        }
        val exclude = memory.exclude()
        val state = profiles.snapshot()
        val first = pick(state, failed, exclude, tried = emptyList())
        // The panel may have moved the servers meanwhile (new addresses or keys).
        val sub = refresher.refreshable(state, failed)
        if (first.isNotEmpty() || sub != null) {
            // Probing servers and downloading the list is what costs.
            if (!runtime.allowSearch()) {
                AppLog.w("automatic searches used up for now")
                notices.show(Failover.NOTICE_PICK_ANOTHER, e)
                return SearchOutcome.Done
            }
            notices.show(Failover.NOTICE_SEARCHING, e)
            AppLog.i("trying ${first.size} other servers")
        }
        val refresh = sub?.let { refresher.startDirect(it) }
        // The failed server goes first, as a control: when it answers here
        // too, the phone was offline for a moment (a lift, a tunnel) and the
        // server is fine. Not for a stall, which a short answer never shows.
        val control = !stalled
        val delays = probe(core, (if (control) listOf(failed) else emptyList()) + first)
        if (!epoch.isCurrent(e)) return SearchOutcome.Done
        // It answered: whatever happens next, it is not blocked from here.
        val reachable = control && delays.first() >= 0
        if (reachable && recoveredInPlace()) return SearchOutcome.Done
        val tier = { p: StoredProfile -> Failover.tier(p, failed) }
        var probed = first.size
        var winner = Failover.fastest(first, if (control) delays.drop(1) else delays, tier)
        var refreshed: Refreshed? = null
        if (winner == null && refresh != null) {
            refreshed = refresh.await()
            if (refreshed.applied == true) {
                // Only what the refresh brought: new servers, or new settings of known ones.
                val second = pick(profiles.snapshot(), failed, exclude, tried = first.map { it.outbounds })
                if (second.isNotEmpty()) {
                    AppLog.i("subscription refreshed, trying ${second.size} more servers")
                    probed += second.size
                    winner = Failover.fastest(second, probe(core, second), tier)
                }
            }
            if (!epoch.isCurrent(e)) return SearchOutcome.Done
        }
        if (winner == null) {
            AppLog.w("no server answers")
            nothingAnswers(e, failed, state, probed, network, report = !reachable)
            // Not tied to the check's generation: a network change during the
            // download resets connections but leaves the removed server running.
            return if (refreshed?.runningChanged == true) SearchOutcome.RestartOnSaved else SearchOutcome.Done
        }
        val (to, ms) = winner
        AppLog.i("switching to a server that answered in $ms ms")
        return SearchOutcome.SwitchTo(to.id, report = !reachable)
    }

    /** The delay through each of [servers] in ms, or -1, in the same order. Never throws. */
    suspend fun probe(core: CoreHandle, servers: List<StoredProfile>): List<Long> {
        if (servers.isEmpty()) return emptyList()
        return probeLock.withLock {
            try {
                core.probe(servers.map { it.outbounds }, timeoutMs = Failover.PROBE_TIMEOUT_MS, parallel = Failover.PARALLEL)
            } catch (ex: Exception) {
                AppLog.w("probe failed", ex)
                List(servers.size) { -1L }
            }
        }
    }

    /**
     * No other server answers either (or there is none). A Russian site
     * opened directly, outside the tunnel, tells a block from a phone
     * without internet, and on mobile data a foreign site that does not
     * open directly either tells the operator's whitelist from a block.
     * Only a phone that is online reports [failed] to the owner (marked as
     * the whitelist when it is), and only with [report] (false: [failed]
     * itself answered a new connection).
     */
    private suspend fun nothingAnswers(e: Long, failed: StoredProfile, state: ProfilesState, probed: Int, network: NetId?, report: Boolean) {
        val online = direct.opens(DirectNet.DIRECT_URL)
        val whitelist = online && netInfo.onMobileData() && direct.onlyWhitelistOpens(russianOpens = online)
        if (!epoch.isCurrent(e)) return
        val notice = Failover.nothingAnswersNotice(online, whitelist, probed)
        if (online) {
            AppLog.w(
                if (whitelist) {
                    "no server answers, and mobile data seems limited to the operator's whitelist"
                } else {
                    "the phone is online, but no server answers from this network"
                },
            )
            if (report) reports.report(failed, state, allDown = true, whitelist = whitelist)
        }
        markFruitless(network, notice)
        notices.show(notice, e)
    }

    /** The notice of a search that found nothing on [network] in the last minutes, or null. */
    private fun fruitlessNotice(network: NetId?): String? = synchronized(fruitless) {
        val now = clock.elapsed()
        fruitless.entries.removeIf { now - it.value.first >= FRUITLESS_RETRY_MS || it.value.first > now }
        fruitless[network]?.second
    }

    private fun markFruitless(network: NetId?, notice: String) {
        synchronized(fruitless) { fruitless[network] = clock.elapsed() to notice }
    }

    /**
     * Candidates for [failed]. Servers on the Russian mobile whitelist go
     * first on mobile data, and get a few slots elsewhere (a hotspot or a 4G
     * router may be under the whitelist too) when not every server is probed.
     */
    private suspend fun pick(state: ProfilesState, failed: StoredProfile, exclude: Set<String>, tried: List<JsonArray>): List<StoredProfile> {
        val pool = Failover.pickCandidates(state, failed, exclude, tried, limit = Int.MAX_VALUE)
        val mobile = netInfo.onMobileData()
        // Every one of them is probed anyway.
        if (!mobile && pool.size <= Failover.MAX_CANDIDATES) return pool
        val preferred = mobileWhitelist.listed(pool.map { Failover.host(it) }.distinct().take(WhitelistLookup.MAX_HOSTS))
        val reserve = if (mobile) Failover.MAX_CANDIDATES else Failover.WHITELIST_RESERVED
        return Failover.pickCandidates(state, failed, exclude, tried, preferred, reserve = reserve)
    }

    private companion object {
        /** After a search found nothing, the next one on that network waits this long. */
        const val FRUITLESS_RETRY_MS = 2 * 60_000L
    }
}
