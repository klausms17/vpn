package com.klausms.vpn.service

import com.klausms.vpn.util.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the servers that stopped answering lately, which searches skip
 * for [Failover.RECENTLY_FAILED_MS], and the server the user chose again
 * right after it had been switched away from: their choice wins, and it is
 * not switched away from automatically. Shared by the search for another
 * server and the switch to it.
 *
 * Thread-safe: starts write it on the engine's worker, checks read it on IO.
 */
internal class FailureMemory(private val clock: Clock) {
    // Server id -> elapsed time when it stopped answering.
    private val recentlyFailed = ConcurrentHashMap<String, Long>()

    @Volatile
    private var manualPick: String? = null

    fun failedRecently(id: String): Boolean =
        recentlyFailed[id]?.let { clock.elapsed() - it < Failover.RECENTLY_FAILED_MS } == true

    /** Server [id] stopped answering just now. */
    fun markFailed(id: String) {
        recentlyFailed[id] = clock.elapsed()
    }

    /** The servers that stopped answering lately, which a search skips. */
    fun exclude(): Set<String> {
        val now = clock.elapsed()
        recentlyFailed.entries.removeIf { now - it.value >= Failover.RECENTLY_FAILED_MS }
        return recentlyFailed.keys.toSet()
    }

    /** Whether the user chose [id] again after it had stopped answering: it is not switched away from. */
    fun keptByUser(id: String): Boolean = id == manualPick && failedRecently(id)

    /**
     * A start brought server [id] up: [automatic], a switch away from a
     * failed server; [picked], chosen by hand just now.
     */
    fun onStarted(id: String, automatic: Boolean, picked: Boolean) {
        manualPick = when {
            automatic -> null
            // Picked by hand again after it had stopped answering: the user knows.
            picked && failedRecently(id) -> id
            // The same server with new settings: the choice still stands.
            manualPick == id -> manualPick
            else -> null
        }
    }
}
