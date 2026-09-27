package com.klausms.vpn.service

import com.klausms.vpn.service.StartFailurePolicy.MAX_START_RETRIES
import org.junit.Assert.assertEquals
import org.junit.Test

class StartFailurePolicyTest {
    private fun decide(
        anotherVpn: Boolean = false,
        restarting: Boolean = false,
        swapped: Boolean = false,
        sessionAlive: Boolean = false,
        userRequested: Boolean = false,
        attempt: Int = 0,
        shouldRun: () -> Boolean = { true },
    ) = StartFailurePolicy.decide(anotherVpn, restarting, swapped, sessionAlive, userRequested, attempt, shouldRun)

    private val notAsked: () -> Boolean = { throw AssertionError("shouldRun read where it cannot matter") }

    @Test
    fun newSettingsThatFailBeforeTheSwapKeepTheOldTunnelAndNeverGiveUp() {
        for (userRequested in listOf(true, false)) {
            for (attempt in 0 until MAX_START_RETRIES) {
                assertEquals(
                    FailureAction.KeepOld(retry = true),
                    decide(restarting = true, sessionAlive = true, userRequested = userRequested, attempt = attempt, shouldRun = notAsked),
                )
            }
            assertEquals(
                FailureAction.KeepOld(retry = false),
                decide(restarting = true, sessionAlive = true, userRequested = userRequested, attempt = MAX_START_RETRIES, shouldRun = notAsked),
            )
        }
    }

    @Test
    fun afterTheSwapARestartHoldsTheInterfaceAndRetriesTwice() {
        for (attempt in 0 until MAX_START_RETRIES) {
            assertEquals(
                FailureAction.HoldTunAndRetry,
                decide(restarting = true, swapped = true, sessionAlive = true, userRequested = true, attempt = attempt),
            )
        }
        assertEquals(
            FailureAction.GiveUp,
            decide(restarting = true, swapped = true, sessionAlive = true, userRequested = true, attempt = MAX_START_RETRIES, shouldRun = notAsked),
        )
    }

    @Test
    fun aHaltedCoreIsNoOldTunnelToKeep() {
        // An earlier failure stopped the core but held the interface: a restart, retried.
        assertEquals(FailureAction.HoldTunAndRetry, decide(restarting = true, sessionAlive = false, userRequested = true))
        assertEquals(
            FailureAction.GiveUp,
            decide(restarting = true, sessionAlive = false, attempt = MAX_START_RETRIES, shouldRun = notAsked),
        )
    }

    @Test
    fun aFreshStartTheUserAskedForGivesUpAtOnce() {
        assertEquals(FailureAction.GiveUp, decide(userRequested = true, shouldRun = notAsked))
    }

    @Test
    fun aFreshStartNobodyAskedForIsTriedTwiceMore() {
        assertEquals(FailureAction.HoldTunAndRetry, decide(userRequested = false, attempt = 0))
        assertEquals(FailureAction.HoldTunAndRetry, decide(userRequested = false, attempt = 1))
        assertEquals(FailureAction.GiveUp, decide(userRequested = false, attempt = 2, shouldRun = notAsked))
    }

    @Test
    fun aTunnelTurnedOffMeanwhileIsNotRetried() {
        assertEquals(FailureAction.GiveUp, decide(restarting = true, swapped = true, shouldRun = { false }))
        assertEquals(FailureAction.GiveUp, decide(userRequested = false, shouldRun = { false }))
    }

    @Test
    fun anotherVpnAlwaysMeansStayingOff() {
        for (restarting in listOf(true, false)) {
            for (swapped in listOf(true, false)) {
                for (userRequested in listOf(true, false)) {
                    for (attempt in 0..MAX_START_RETRIES) {
                        assertEquals(
                            FailureAction.StayOff,
                            decide(
                                anotherVpn = true, restarting = restarting, swapped = swapped, sessionAlive = restarting,
                                userRequested = userRequested, attempt = attempt, shouldRun = notAsked,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun theFirstRetryComesQuicklyTheSecondLater() {
        assertEquals(1_500L, StartFailurePolicy.retryDelayMs(1))
        assertEquals(5_000L, StartFailurePolicy.retryDelayMs(2))
    }
}
