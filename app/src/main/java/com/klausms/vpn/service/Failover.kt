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

    /** A server that stopped answering is not switched back to for this long. */
    const val RECENTLY_FAILED_MS = 30 * 60_000L

    /** At most this many automatic switches per window, so a bad day never becomes a ping-pong. */
    const val MAX_SWITCHES = 3
    const val SWITCH_WINDOW_MS = 30 * 60_000L

    /** The VPN process downloads a subscription again at most this often. */
    const val REFRESH_MS = 10 * 60_000L

    const val NOTICE_SEARCHING = "Сервер не отвечает, ищем другой…"
    const val NOTICE_NONE_ANSWER = "Серверы не отвечают — проверьте интернет"
    const val NOTICE_NO_OTHER = "Сервер не отвечает — проверьте интернет"
    const val NOTICE_PICK_ANOTHER = "Сервер не отвечает — выберите другой сервер"

    /** Notices that say the server does not answer; a check that gets through clears them. */
    val FAILURE_NOTICES = setOf(NOTICE_SEARCHING, NOTICE_NONE_ANSWER, NOTICE_NO_OTHER, NOTICE_PICK_ANOTHER)

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
     * Hosts in [preferred] go first (on mobile data: the Russian whitelist).
     * Skipped: ids in [exclude] (failed recently), outbounds equal to the
     * failed server's or to anything in [tried] (probed in an earlier
     * round), duplicates and placeholders. At most [limit].
     */
    fun pickCandidates(
        state: ProfilesState,
        failed: StoredProfile,
        exclude: Set<String> = emptySet(),
        tried: Collection<JsonArray> = emptyList(),
        preferred: Set<String> = emptySet(),
        limit: Int = MAX_CANDIDATES,
    ): List<StoredProfile> {
        val subOrder = state.subscriptions.withIndex().associate { (i, s) -> s.id to i }
        val failedHost = host(failed)
        fun group(p: StoredProfile): Int = when {
            p.subscriptionId == failed.subscriptionId -> if (host(p) == failedHost) 1 else 0
            p.subscriptionId == null -> 2
            // Servers of a subscription that is gone come last.
            else -> 3 + (subOrder[p.subscriptionId] ?: subOrder.size)
        }
        val ordered = state.profiles
            .filter { it.id !in exclude && !isPlaceholder(it) }
            .sortedBy { group(it) } // stable: list order within a group
            .let { list -> if (preferred.isEmpty()) list else list.sortedBy { if (host(it) in preferred) 0 else 1 } }
        val seen = HashSet<JsonArray>(tried).apply { add(failed.outbounds) }
        val picked = ArrayList<StoredProfile>()
        for (p in ordered) {
            if (picked.size >= limit) break
            if (seen.add(p.outbounds)) picked += p
        }
        return picked
    }

    /** The fastest candidate that answered and its delay; null when none did. */
    fun fastest(candidates: List<StoredProfile>, delays: List<Long>): Pair<StoredProfile, Long>? =
        candidates.indices
            .filter { delays.getOrElse(it) { -1L } >= 0 }
            .minByOrNull { delays[it] }
            ?.let { candidates[it] to delays[it] }

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
    fun countSwitch(saved: String, now: Long): String? {
        val recent = recentSwitches(saved, now)
        if (recent.size >= MAX_SWITCHES) return null
        return (recent + now).joinToString(",")
    }

    // ------------------------------------------------------------ helpers

    fun host(p: StoredProfile): String = p.address.trim().lowercase(Locale.ROOT)

    private fun isPlaceholder(p: StoredProfile): Boolean =
        p.outbounds.isEmpty() || (p.address == "0.0.0.0" && p.port <= 1) || ZERO_UUID in p.outbounds.toString()
}
