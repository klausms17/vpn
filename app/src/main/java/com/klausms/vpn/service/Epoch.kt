package com.klausms.vpn.service

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicLong

/**
 * The tunnel's generation. It changes whenever the running core changes,
 * stops or loses its network: a check or probe that began in an older one
 * is outdated, and its result is discarded. It also owns the one traffic
 * check that may run at a time, which a new generation cancels.
 *
 * Thread-safe. [advance] and whatever runs in [startCheck] or [locked] are
 * atomic with each other, so "a new generation" can never fall between a
 * caller's look at the generation and what it does about it. Nothing run
 * under the lock may suspend or block: only launch or cancel jobs.
 */
internal class Epoch {
    private val lock = Any()
    private val n = AtomicLong()

    // Guarded by lock.
    private var check: Job? = null

    val current: Long get() = n.get()

    fun isCurrent(e: Long): Boolean = n.get() == e

    /** Starts a new generation and cancels the running check. */
    fun advance() {
        synchronized(lock) {
            n.incrementAndGet()
            check?.cancel()
        }
    }

    /**
     * Starts the check that [launch] returns for the current generation,
     * unless one is still active. Returns whether it started.
     */
    fun startCheck(launch: (Long) -> Job): Boolean = synchronized(lock) {
        if (check?.isActive == true) return false
        check = launch(n.get())
        true
    }

    /** Runs [block] atomically with [advance]. */
    fun <T> locked(block: () -> T): T = synchronized(lock, block)
}
