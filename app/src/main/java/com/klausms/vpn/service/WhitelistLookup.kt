package com.klausms.vpn.service

import com.klausms.vpn.core.DirectNet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells which hosts are on the Russian mobile whitelist, asking outside the
 * tunnel ([DirectNet.whitelistStatus]). Owns the answers, kept while the
 * process lives: few hosts, and the answer rarely changes. A lookup that
 * failed is not remembered, so the next search asks again.
 *
 * Thread-safe. [listed] waits at most [WAIT_MS]. The lookups run in
 * [scope] on [io], [PARALLEL] at a time, not in the caller's coroutine: a
 * DNS lookup cannot be interrupted, and the caller must not wait for a
 * slow one. One that answers late is still remembered.
 */
internal class WhitelistLookup(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val direct: DirectNet,
) {
    // Host -> on the whitelist.
    private val known = ConcurrentHashMap<String, Boolean>()

    /** Those of [hosts] on the whitelist; lookups that take too long count as not. */
    suspend fun listed(hosts: List<String>): Set<String> {
        val found = ConcurrentHashMap.newKeySet<String>()
        hosts.filterTo(found) { known[it] == true }
        val limit = Semaphore(PARALLEL)
        val lookups = hosts.filterNot { known.containsKey(it) }.map { host ->
            scope.launch(io) {
                try {
                    limit.withPermit {
                        val status = direct.whitelistStatus(host)
                        known[host] = status == 1
                        if (status == 1) found.add(host)
                    }
                } catch (ex: Exception) {
                    if (ex is CancellationException) throw ex
                }
            }
        }
        try {
            withTimeoutOrNull(WAIT_MS) { lookups.joinAll() }
        } finally {
            lookups.forEach { it.cancel() }
        }
        return found.toSet()
    }

    companion object {
        /** A search looks up at most this many hosts. */
        const val MAX_HOSTS = 32

        private const val PARALLEL = 4
        private const val WAIT_MS = 3_000L
    }
}
