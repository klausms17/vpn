package com.klausms.vpn.service

import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import kotlinx.serialization.json.JsonArray
import java.util.Locale

/**
 * Rules of the automatic switch to another server when the running one
 * stops passing traffic. Pure logic: XrayVpnService does the checking,
 * probing and switching.
 */
internal object Failover {
    /** Servers probed at most per round, and at the same time. */
    const val MAX_CANDIDATES = 8
    const val PARALLEL = 4
    const val PROBE_TIMEOUT_MS = 5_000

    /** Off mobile data, slots of a round kept for servers on the Russian whitelist (a hotspot or 4G router may be under it). */
    const val WHITELIST_RESERVED = 3

    /** A server of the preferred group wins unless it is more than this many times slower than the fastest. */
    const val PREFERRED_SLOWDOWN = 2

    /** A server that stopped answering is not switched back to for this long. */
    const val RECENTLY_FAILED_MS = 30 * 60_000L

    /** At most this many automatic switches per window, so a bad day never becomes a ping-pong. */
    const val MAX_SWITCHES = 3
    const val SWITCH_WINDOW_MS = 30 * 60_000L

    /** At most this many searches nobody asked for per window, so a phone moving between networks where nothing answers does not search all day. */
    const val MAX_SEARCHES = 6
    const val SEARCH_WINDOW_MS = 60 * 60_000L

    /** The VPN process downloads a subscription again at most this often. */
    const val REFRESH_MS = 10 * 60_000L

    /**
     * Going back to the user's server waits [RECENTLY_FAILED_MS], twice as
     * long after each try or return that did not work, at most this many
     * times (4 hours).
     */
    const val MAX_RETURN_BACKOFF = 3

    const val NOTICE_SEARCHING = "Сервер не отвечает, ищем другой…"
    const val NOTICE_NONE_ANSWER = "Серверы не отвечают — проверьте интернет"
    const val NOTICE_NO_OTHER = "Сервер не отвечает — проверьте интернет"
    const val NOTICE_PICK_ANOTHER = "Сервер не отвечает — выберите другой сервер"
    const val NOTICE_ALL_BLOCKED = "Серверы не открываются из этой сети — похоже на блокировку"
    const val NOTICE_BLOCKED = "Сервер не открывается из этой сети — похоже на блокировку"
    const val NOTICE_SIGN_IN = "Wi-Fi требует входа: войдите в сеть"

    /** Not a failure: shown while the phone's strict «Частный DNS» takes DNS away from the VPN's rules. */
    const val NOTICE_PRIVATE_DNS = "«Частный DNS» мешает VPN: выберите в настройках «Автоматически»"

    /** Notices that say the server does not answer; a check that gets through clears them. */
    val FAILURE_NOTICES = setOf(
        NOTICE_SEARCHING, NOTICE_NONE_ANSWER, NOTICE_NO_OTHER, NOTICE_PICK_ANOTHER,
        NOTICE_ALL_BLOCKED, NOTICE_BLOCKED, NOTICE_SIGN_IN,
    )

    fun switchedNotice(to: String, from: String) = "Переключились на «$to»: «$from» не отвечал"

    // Remnawave's fake entries carrying a message ("subscription expired").
    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

    /**
     * Servers to probe instead of [failed], best bets first:
     * 1. its own subscription (for an own key: the other own keys), other
     *    addresses before its own, since a blocked address takes every
     *    server on it down while a blocked protocol may spare some;
     * 2. own keys;
     * 3. other subscriptions, in the order of the list.
     *
     * Hosts in [preferred] (the Russian whitelist) go first, into at most
     * [reserve] slots: on mobile data all of them; elsewhere a few, in place
     * of the last ones, so they are probed even when many servers come
     * before them. Skipped: ids in [exclude] (failed recently), outbounds
     * equal to the failed server's or to anything in [tried] (probed in an
     * earlier round), duplicates and placeholders. At most [limit].
     */
    fun pickCandidates(
        state: ProfilesState,
        failed: StoredProfile,
        exclude: Set<String> = emptySet(),
        tried: Collection<JsonArray> = emptyList(),
        preferred: Set<String> = emptySet(),
        limit: Int = MAX_CANDIDATES,
        reserve: Int = limit,
    ): List<StoredProfile> {
        val subOrder = state.subscriptions.withIndex().associate { (i, s) -> s.id to i }
        val failedHost = host(failed)
        fun group(p: StoredProfile): Int = when {
            p.subscriptionId == failed.subscriptionId -> if (host(p) == failedHost) 1 else 0
            p.subscriptionId == null -> 2
            // Servers of a subscription that is gone come last.
            else -> 3 + (subOrder[p.subscriptionId] ?: subOrder.size)
        }
        val seen = HashSet<JsonArray>(tried).apply { add(failed.outbounds) }
        val all = state.profiles
            .filter { it.id !in exclude && !isPlaceholder(it) }
            .sortedBy { group(it) } // stable: list order within a group
            .filter { seen.add(it.outbounds) }
        fun isPreferred(p: StoredProfile) = host(p) in preferred
        if (preferred.isEmpty() || reserve <= 0) return all.take(limit)
        if (reserve >= limit) return all.sortedBy { if (isPreferred(it)) 0 else 1 }.take(limit)
        val top = all.take(limit)
        val missing = (reserve - top.count(::isPreferred)).coerceAtLeast(0)
        val extra = all.drop(limit).filter(::isPreferred).take(missing)
        if (extra.isEmpty()) return top
        // Make room at the end, never by dropping a whitelisted one.
        val dropped = top.filterNot(::isPreferred).takeLast(extra.size).toSet()
        return top.filterNot { it in dropped } + extra
    }

    /**
     * How close [p] is to [failed]: its own subscription (for an own key:
     * the other own keys) 0, own keys 1, other subscriptions 2.
     */
    fun tier(p: StoredProfile, failed: StoredProfile): Int = when {
        p.subscriptionId == failed.subscriptionId -> 0
        p.subscriptionId == null -> 1
        else -> 2
    }

    /**
     * The candidate to switch to and its delay; null when none answered.
     * With [tier], the fastest of the closest group that answered wins
     * unless it is more than [PREFERRED_SLOWDOWN] times slower than the
     * fastest overall: first the same subscription, as the owner planned.
     */
    fun fastest(candidates: List<StoredProfile>, delays: List<Long>, tier: ((StoredProfile) -> Int)? = null): Pair<StoredProfile, Long>? {
        val answered = candidates.indices.filter { delays.getOrElse(it) { -1L } >= 0 }
        val best = answered.minByOrNull { delays[it] } ?: return null
        var pick = best
        if (tier != null) {
            val closest = answered.minOf { tier(candidates[it]) }
            val near = answered.filter { tier(candidates[it]) == closest }.minBy { delays[it] }
            if (delays[near] <= delays[best] * PREFERRED_SLOWDOWN) pick = near
        }
        return candidates[pick] to delays[pick]
    }

    /**
     * [state] with [winnerId] selected instead of [failedId]. Unchanged
     * when the user picked another server meanwhile (their choice wins) or
     * the winner is gone. When the failed server itself is gone (a refresh
     * or a delete moved the selection to some first server), the winner
     * takes its place.
     */
    fun selectInstead(state: ProfilesState, failedId: String, winnerId: String, expected: String? = failedId): ProfilesState {
        if (state.profiles.none { it.id == winnerId }) return state
        // A real compare-and-set: only the selection the switch was decided
        // on (or the failed server itself) is replaced, never a later pick.
        if (state.selectedId != failedId && state.selectedId != expected) return state
        return state.copy(selectedId = winnerId)
    }

    /**
     * Whether a start runs server [id] because the user chose it: the app
     * said so ([picked]), or a start they asked for ([requested], no
     * automatic switch) moves the tunnel from [runningId], still in
     * [state], to another server; the app may not say so. Not when the
     * running server was deleted or dropped by a refresh: the selection
     * then moved by itself.
     */
    fun chosenByUser(picked: Boolean, requested: Boolean, runningId: String?, id: String, state: ProfilesState): Boolean =
        picked || (requested && runningId != null && runningId != id && state.profiles.any { it.id == runningId })

    /** False when someone chose a server other than [failedId] since it was picked up as failed. */
    fun selectionFollowsFailed(state: ProfilesState, failedId: String): Boolean =
        state.selectedId == failedId || state.profiles.none { it.id == failedId }

    /** Whether the VPN process may download [sub] again now (wall clock; a clock set back counts as due). */
    fun refreshDue(sub: Subscription, now: Long): Boolean =
        now - sub.lastAttemptAt >= REFRESH_MS || sub.lastAttemptAt > now

    // ------------------------------------------------------------- budget

    /** The switch times from [saved] ("t1,t2,…", elapsed realtime) still inside the window. */
    fun recentSwitches(saved: String, now: Long): List<Long> =
        saved.split(',').mapNotNull { it.trim().toLongOrNull() }
            // Times after [now] are from before a reboot.
            .filter { it <= now && now - it < SWITCH_WINDOW_MS }

    /** [saved] with a switch at [now] counted, or null when the budget is used up. */
    fun countSwitch(saved: String, now: Long): String? = count(saved, now, MAX_SWITCHES, SWITCH_WINDOW_MS)

    /** [saved] with a search at [now] counted, or null when [MAX_SEARCHES] were made in the last hour. */
    fun countSearch(saved: String, now: Long): String? = count(saved, now, MAX_SEARCHES, SEARCH_WINDOW_MS)

    /** "t1,t2,…" (elapsed realtime) with [now] added, or null when [max] of them are within [windowMs]. */
    fun count(saved: String, now: Long, max: Int, windowMs: Long): String? {
        val recent = saved.split(',').mapNotNull { it.trim().toLongOrNull() }
            // Times after [now] are from before a reboot.
            .filter { it <= now && now - it < windowMs }
        if (recent.size >= max) return null
        return (recent + now).joinToString(",")
    }

    // --------------------------------------------------------- the check

    /**
     * Whether a download failed because data stopped coming after it had
     * begun: the answer's headers arrived, then the body timed out (the Go
     * core's wording). Some operators freeze connections to foreign servers
     * that way after the first ~16 KB. Anything else proves nothing.
     */
    fun isStall(error: String?): Boolean = error?.contains("while reading body") == true

    // ------------------------------------------------ back to the user's server

    /**
     * The server the user chose ([home]) while automatic switches keep the
     * tunnel on [to]. Going back is tried from [retryAt] (elapsed realtime),
     * which moves further out by [backoff].
     */
    data class Away(val home: String, val to: String, val retryAt: Long, val backoff: Int)

    /** The last return to the user's server [id] at [at]: when it fails again soon, the next return waits longer. */
    data class Returned(val id: String, val at: Long, val backoff: Int)

    /** How long a return waits: [RECENTLY_FAILED_MS], doubled [backoff] times (at most [MAX_RETURN_BACKOFF]). */
    fun returnDelay(backoff: Int): Long = RECENTLY_FAILED_MS shl backoff.coerceIn(0, MAX_RETURN_BACKOFF)

    /** [away] after an automatic switch from [failedId] to [winnerId] at [now]; null once back on the user's server. */
    fun afterSwitch(away: Away?, returned: Returned?, failedId: String, winnerId: String, now: Long): Away? {
        // The same server with new settings: nothing moved.
        if (winnerId == failedId) return away
        // Moving on from a server switched to: the user's server stays the one to go back to.
        if (away != null && away.to == failedId) return if (winnerId == away.home) null else away.copy(to = winnerId)
        // The user's server failed again soon after the last return: wait longer this time.
        val backoff = if (returned != null && returned.id == failedId && now >= returned.at && now - returned.at < RECENTLY_FAILED_MS) {
            (returned.backoff + 1).coerceAtMost(MAX_RETURN_BACKOFF)
        } else {
            0
        }
        return Away(failedId, winnerId, now + returnDelay(backoff), backoff)
    }

    /** Whether going back may be tried at [now]; a time from before a reboot counts as due. */
    fun returnDue(away: Away, now: Long): Boolean =
        now >= away.retryAt || away.retryAt - now > returnDelay(MAX_RETURN_BACKOFF)

    /** [away] after the user's server did not answer at [now]: the next try waits longer. */
    fun returnFailed(away: Away, now: Long): Away {
        val backoff = (away.backoff + 1).coerceAtMost(MAX_RETURN_BACKOFF)
        return away.copy(retryAt = now + returnDelay(backoff), backoff = backoff)
    }

    // ------------------------------------------------------------ helpers

    fun host(p: StoredProfile): String = p.address.trim().lowercase(Locale.ROOT)

    private fun isPlaceholder(p: StoredProfile): Boolean =
        p.outbounds.isEmpty() || (p.address == "0.0.0.0" && p.port <= 1) || ZERO_UUID in p.outbounds.toString()
}
