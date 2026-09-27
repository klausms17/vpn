package com.klausms.vpn.service

/** What the tunnel does after a start failed (see [StartFailurePolicy.decide]). */
internal sealed interface FailureAction {
    /** Another app's VPN is in place: stay off, without an error. */
    data object StayOff : FailureAction

    /**
     * The new interface never replaced the running tunnel, which keeps
     * carrying the traffic; [retry]: the start is tried once more later.
     */
    data class KeepOld(val retry: Boolean) : FailureAction

    /**
     * Stop the core but keep the new interface, so apps wait instead of
     * going around the VPN, and start again a little later.
     */
    data object HoldTunAndRetry : FailureAction

    /** Stop the tunnel and show the error. */
    data object GiveUp : FailureAction
}

/**
 * Decides what a failed start of the tunnel does next. Pure and stateless;
 * any thread.
 */
internal object StartFailurePolicy {
    /** A failed start of a tunnel that should run is tried this many more times. */
    const val MAX_START_RETRIES = 2

    /**
     * [anotherVpn]: an automatic start found another app's VPN in place.
     * [restarting]: a tunnel ran, or a failed start waits for its retry
     * with the interface held. [swapped]: the new interface had already
     * replaced the old one. [sessionAlive]: the old core still runs.
     * [userRequested]: the user asked for this start just now. [attempt]: 0
     * for the first try. [shouldRun]: whether the tunnel should run, read
     * only when the answer depends on it.
     */
    fun decide(
        anotherVpn: Boolean,
        restarting: Boolean,
        swapped: Boolean,
        sessionAlive: Boolean,
        userRequested: Boolean,
        attempt: Int,
        shouldRun: () -> Boolean,
    ): FailureAction = when {
        // The user switched to another VPN app while this one was down.
        anotherVpn -> FailureAction.StayOff
        // New settings or another server for a tunnel that works: until the
        // new interface replaced it, the old tunnel still carries the traffic.
        restarting && !swapped && sessionAlive -> FailureAction.KeepOld(retry = attempt < MAX_START_RETRIES)
        // It was running, or should be running (a restart nobody asked
        // for): a second try usually works.
        (restarting || !userRequested) && attempt < MAX_START_RETRIES && shouldRun() -> FailureAction.HoldTunAndRetry
        else -> FailureAction.GiveUp
    }

    /** The pause before retry number [attempt] (1 or 2). */
    fun retryDelayMs(attempt: Int): Long = if (attempt == 1) 1_500L else 5_000L
}
