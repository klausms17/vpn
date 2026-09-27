package com.klausms.vpn.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the one pending in-place reset of the running core: a newer reset
 * replaces it, and a reset asked for by a check of an older generation is
 * refused and leaves it alone. A check's blocking test cannot be cancelled,
 * so an outdated one may still ask, for instance right after a network
 * change queued its own reset.
 *
 * Thread-safe. [schedule] and [cancel] run under the [Epoch] lock, so
 * "is the generation still current" and "replace the pending reset" are
 * one step that no new generation can fall between. Nothing under the lock
 * suspends or blocks: the reset is only launched in [scope] on
 * [dispatcher], or cancelled.
 */
internal class ResetScheduler(
    private val epoch: Epoch,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    // Guarded by the epoch's lock.
    private var job: Job? = null

    /**
     * Runs [body] after [delayMs] in place of the pending reset, unless
     * [expectedEpoch] is given and no longer current. Returns whether it
     * was scheduled.
     */
    fun schedule(delayMs: Long, expectedEpoch: Long?, body: suspend () -> Unit): Boolean = epoch.locked {
        if (expectedEpoch != null && !epoch.isCurrent(expectedEpoch)) return@locked false
        job?.cancel()
        job = scope.launch(dispatcher) {
            if (delayMs > 0) delay(delayMs)
            body()
        }
        true
    }

    /** Drops the pending reset, if any. One that already runs its body is not interrupted before it suspends. */
    fun cancel() {
        epoch.locked { job?.cancel() }
    }
}
