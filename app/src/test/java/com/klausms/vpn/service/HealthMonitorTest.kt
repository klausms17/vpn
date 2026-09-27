package com.klausms.vpn.service

import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_A
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_B
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HealthMonitorTest {
    private val minute = 60_000L
    private val settle = 1_500L
    private val networkReset = "network changed, resetting connections"
    private val stuckCoreReset = "the server answers, but not through the running core: restarting it"
    private val stall = "context deadline exceeded (Client.Timeout or context cancellation while reading body)"

    /** A world whose running server passes traffic. */
    private fun TestScope.world() = FailoverWorld(this).apply { core.delays[XrayCore.TEST_URL] = 50 }

    /** How many checks got as far as testing the traffic. */
    private fun FailoverWorld.checks(): Int =
        core.measured.count { it == XrayCore.TEST_URL to TrafficCheck.VERIFY_TIMEOUT_MS }

    /** The running server stops passing traffic. */
    private fun FailoverWorld.trafficStops() {
        core.delays.clear()
    }

    // ------------------------------------------------------------ throttles

    private class Throttle(val name: String, val windowMs: Long, val event: HealthMonitor.() -> Unit)

    @Test
    fun eachEventChecksAtMostSoOften() = runTest {
        val throttles = listOf(
            Throttle("unlock", 10 * minute) { onUnlock() },
            Throttle("screen on", HealthMonitor.SCREEN_CHECK_MS) { onScreenTick() },
            Throttle("app shown", 2 * minute) { onAppShown() },
            Throttle("connect while up", 2 * minute) { onConnectWhileUp() },
        )
        for (throttle in throttles) {
            val w = world()
            throttle.event(w.health)
            runCurrent()
            assertEquals(throttle.name, 1, w.checks())
            advanceTimeBy(throttle.windowMs - 1)
            throttle.event(w.health)
            runCurrent()
            assertEquals(throttle.name, 1, w.checks())
            advanceTimeBy(1)
            throttle.event(w.health)
            runCurrent()
            assertEquals(throttle.name, 2, w.checks())
        }
    }

    @Test
    fun afterAFailedCheckConnectChecksAgainButTheAppComingOnScreenDoesNot() = runTest {
        val w = world()
        w.trafficStops()
        w.health.schedule(Reason.START)
        runCurrent()
        advanceTimeBy(2 * minute - 1)
        w.health.onAppShown()
        runCurrent()
        assertEquals(1, w.checks())
        w.health.onConnectWhileUp()
        runCurrent()
        assertEquals(2, w.checks())
    }

    @Test
    fun androidLosingTheInternetChecksOnceAMinuteAfterTheNetworkSettles() = runTest {
        val w = world()
        w.health.onReachability(lost = true)
        advanceTimeBy(settle - 1)
        runCurrent()
        assertEquals(0, w.checks())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, w.checks())

        w.health.onReachability(lost = true)
        advanceTimeBy(minute - 1)
        runCurrent()
        assertEquals(1, w.checks())
        w.health.onReachability(lost = true)
        advanceTimeBy(settle)
        runCurrent()
        assertEquals(2, w.checks())

        // Exactly a minute after the last one counts.
        advanceTimeBy(minute - settle - 1)
        w.health.onReachability(lost = true)
        advanceTimeBy(1)
        w.health.onReachability(lost = true)
        advanceTimeBy(settle)
        runCurrent()
        assertEquals(3, w.checks())
    }

    @Test
    fun theInternetComingBackMattersOnlyAfterAFailedCheck() = runTest {
        val w = world()
        w.health.schedule(Reason.START)
        runCurrent()
        advanceTimeBy(minute)
        w.health.onReachability(lost = false)
        advanceUntilIdle()
        assertEquals(1, w.checks())

        w.trafficStops()
        w.health.schedule(Reason.APP)
        runCurrent()
        advanceTimeBy(minute)
        w.health.onReachability(lost = false)
        advanceTimeBy(settle)
        runCurrent()
        assertEquals(3, w.checks())
    }

    @Test
    fun nothingIsCheckedWhileNoTunnelRuns() = runTest {
        val w = world()
        w.tunnel.session = null
        w.health.onUnlock()
        w.health.onNetworkChanged(NetId(1), previous = null)
        advanceUntilIdle()
        assertEquals(0, w.checks())

        // Nor does a check wait for a tunnel: the one a start asks for runs at once.
        w.health.onNetworkChanged(NetId(1), previous = null)
        w.tunnel.runOn(SERVER_A)
        w.health.schedule(Reason.START)
        runCurrent()
        assertEquals(1, w.checks())
    }

    @Test
    fun nothingIsCheckedWhileTheTunnelIsNotShownAsConnected() = runTest {
        val w = world()
        w.status = VpnStatus(VpnState.CONNECTING)
        w.health.onAppShown()
        runCurrent()
        assertEquals(0, w.checks())

        // Nor did that count as a check.
        w.status = VpnStatus(VpnState.CONNECTED, SERVER_A.id, SERVER_A.name)
        w.health.onAppShown()
        runCurrent()
        assertEquals(1, w.checks())
    }

    // ------------------------------------------------------ what a check does

    @Test
    fun aPassingCheckTakesTheFailureNoticeAwayAndAgesTheSwitchNotice() = runTest {
        val w = world()
        w.health.schedule(Reason.APP)
        runCurrent()
        assertEquals(1, w.notices.failuresCleared)
        assertEquals(listOf(10 * minute), w.notices.switchClears)
        assertEquals(emptyList<List<*>>(), w.core.probed)
    }

    @Test
    fun theWayBackIsTriedOnlyWhenConnectionsStartOverAnyway() = runTest {
        val w = world()
        w.profiles.state = w.profiles.state.copy(selectedId = SERVER_B.id)
        w.tunnel.runOn(SERVER_B)
        val away = Failover.Away(home = SERVER_A.id, to = SERVER_B.id, retryAt = 0, backoff = 0)
        w.runtime.awayState = away
        for (reason in listOf(Reason.START, Reason.APP, Reason.SCREEN, Reason.LINK)) {
            w.health.schedule(reason)
            runCurrent()
        }
        assertEquals(emptyList<List<*>>(), w.core.probed)
        for (reason in listOf(Reason.NETWORK, Reason.UNLOCK)) {
            // Each try that fails puts the next one off.
            w.runtime.awayState = away
            w.health.schedule(reason)
            runCurrent()
        }
        assertEquals(listOf(listOf(SERVER_A.outbounds), listOf(SERVER_A.outbounds)), w.core.probed)
    }

    @Test
    fun aSubscriptionThatCouldNotBeDownloadedDirectlyIsDownloadedThroughTheTunnelOnceItWorks() = runTest {
        val w = world()
        w.subscriptionDue()
        w.trafficStops()
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(listOf("s1" to null), w.source.refreshed)

        // The server works again, and the download moved the running server.
        w.core.delays[XrayCore.TEST_URL] = 50
        w.source.answer = { Refreshed(applied = true, runningChanged = true) }
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(listOf("s1" to null, "s1" to w.core), w.source.refreshed)
        assertEquals(listOf(StartRequest(1, userRequested = false)), w.tunnel.starts)
    }

    @Test
    fun aStallingDownloadLeadsToASearchWithoutAControl() = runTest {
        val w = world()
        w.core.fetchError = stall
        w.answering(SERVER_A, SERVER_B)
        w.health.schedule(Reason.START)
        advanceUntilIdle()
        // Twice in a row, to be sure.
        assertEquals(2, w.core.fetched.size)
        assertFalse(SERVER_A.outbounds in w.core.probed.single())
        assertEquals(SERVER_B.id, w.tunnel.starts.single().switch?.winnerId)
    }

    @Test
    fun aStallIsLookedForAgainAtTheNextCheck() = runTest {
        val w = world()
        w.health.schedule(Reason.START)
        runCurrent()
        w.core.fetchError = stall
        // Nowhere to go: the tunnel stays on the server whose downloads stall.
        w.runtime.switchBudget = false
        w.health.schedule(Reason.START)
        runCurrent()
        assertEquals(3, w.core.fetched.size)

        // The download that worked earlier on this network no longer counts.
        w.health.schedule(Reason.APP)
        runCurrent()
        assertEquals(5, w.core.fetched.size)
    }

    @Test
    fun aDownloadThatWorksIsNotRepeatedOnThatNetworkForHalfAnHour() = runTest {
        val w = world()
        // A refusal proves nothing, and counts as working.
        w.core.fetchError = "connection refused"
        w.health.schedule(Reason.START)
        runCurrent()
        assertEquals(1, w.core.fetched.size)

        w.health.schedule(Reason.APP)
        runCurrent()
        assertEquals(1, w.core.fetched.size)
        // A connect always downloads.
        w.health.schedule(Reason.START)
        runCurrent()
        assertEquals(2, w.core.fetched.size)

        w.net.network = NetId(2)
        w.health.schedule(Reason.APP)
        runCurrent()
        assertEquals(3, w.core.fetched.size)

        w.net.network = NetId(1)
        advanceTimeBy(30 * minute)
        w.health.schedule(Reason.APP)
        runCurrent()
        assertEquals(4, w.core.fetched.size)
        assertEquals(emptyList<List<*>>(), w.core.probed)
    }

    // --------------------------------------------- the failed server answers

    @Test
    fun aServerThatAnswersAgainWasOnlyCutOffForAMoment() = runTest {
        val w = world()
        w.trafficStops()
        w.answering(SERVER_A, SERVER_B)
        // By the time the control answered, the connection is back.
        w.core.onProbe = { w.core.delays[XrayCore.TEST_URL] = 50 }
        w.health.schedule(Reason.APP)
        advanceUntilIdle()

        assertEquals(1, w.notices.failuresCleared)
        assertEquals(emptyList<String>(), w.tunnel.resetsDone)
        assertEquals(emptyList<StartRequest>(), w.tunnel.starts)
        // It counts as a check that passed.
        w.health.onConnectWhileUp()
        runCurrent()
        assertEquals(1, w.checks())
    }

    @Test
    fun aStuckCoreIsRestartedInPlaceAtMostEveryTenMinutes() = runTest {
        val w = world()
        w.trafficStops()
        w.answering(SERVER_A, SERVER_B)
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(listOf(stuckCoreReset), w.tunnel.resetsDone)
        assertEquals(emptyList<StartRequest>(), w.tunnel.starts)

        // Within the gap the failure is taken for real: the tunnel moves on.
        advanceTimeBy(10 * minute - 1)
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(1, w.tunnel.resetsDone.size)
        assertEquals(SERVER_B.id, w.tunnel.session?.profile?.id)

        // Once the gap passed, the core of the server switched to is restarted in its turn.
        advanceTimeBy(1)
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(listOf(stuckCoreReset, stuckCoreReset), w.tunnel.resetsDone)
    }

    @Test
    fun anOutdatedCheckNeitherCancelsANetworkResetNorSpendsTheGap() = runTest {
        val w = world()
        advanceTimeBy(3_000)
        w.trafficStops()
        w.answering(SERVER_A)
        // The phone moves to mobile data while the stuck-core test runs
        // through the core (the only test of Google that waits 4 s).
        w.core.onMeasure = {
            if (w.core.measured.last() == XrayCore.TEST_URL to TrafficCheck.CONFIRM_TIMEOUT_MS) {
                w.core.onMeasure = {}
                w.health.onNetworkChanged(NetId(2), previous = NetId(1))
            }
        }
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(listOf(networkReset), w.tunnel.resetsDone)

        // The gap is still there for a real stuck core.
        w.health.schedule(Reason.APP)
        advanceUntilIdle()
        assertEquals(listOf(networkReset, stuckCoreReset), w.tunnel.resetsDone)
    }

    private class Outdated(val name: String, val searches: Boolean = false, val setUp: FailoverWorld.() -> Unit)

    @Test
    fun aCheckThatOutlivesItsGenerationChangesNothing() = runTest {
        val cases = listOf(
            Outdated("during the traffic test") {
                core.onMeasure = { epoch.advance() }
            },
            Outdated("during a download that works") {
                core.onFetch = { epoch.advance() }
            },
            Outdated("during a download that stalls") {
                core.fetchError = stall
                answering(SERVER_B)
                core.onFetch = { if (core.fetched.size == 2) epoch.advance() }
            },
            Outdated("while the failed server is tested again", searches = true) {
                trafficStops()
                answering(SERVER_A, SERVER_B)
                // The control answered, and so does the core by now, but the tunnel changed.
                core.onProbe = { core.delays[XrayCore.TEST_URL] = 50 }
                core.onMeasure = { if (core.measured.last() == XrayCore.TEST_URL to TrafficCheck.CONFIRM_TIMEOUT_MS) epoch.advance() }
            },
        )
        for (case in cases) {
            val w = world()
            case.setUp(w)
            w.health.schedule(Reason.START)
            advanceUntilIdle()
            assertEquals(case.name, listOfNotNull(Failover.NOTICE_SEARCHING.takeIf { case.searches }), w.notices.shown)
            assertEquals(case.name, if (case.searches) 1 else 0, w.core.probed.size)
            assertEquals(case.name, 0, w.notices.failuresCleared)
            assertEquals(case.name, emptyList<Long>(), w.notices.switchClears)
            assertEquals(case.name, emptyList<StartRequest>(), w.tunnel.starts)
            assertEquals(case.name, emptyList<String>(), w.tunnel.resetsDone)

            // Nor did it count as a check that passed.
            w.core.onMeasure = {}
            w.core.onFetch = {}
            w.health.onConnectWhileUp()
            runCurrent()
            assertEquals(case.name, 2, w.checks())
        }
    }

    // --------------------------------------------------------- network change

    private class Move(val name: String, val net: NetId, val previous: NetId?, val session: TunnelSession?, val now: Long, val resets: Boolean)

    @Test
    fun aNetworkChangeResetsConnectionsOnlyWhenTheTunnelMovedToAnotherNetwork() {
        val wifi = NetId(1)
        val mobile = NetId(2)
        val session = TunnelSession(SERVER_A, config = "{}", core = FakeCore(), connectedAt = 10_000, lockdownConflict = false)
        val up = 13_000L
        val moves = listOf(
            Move("to another network", mobile, wifi, session, up, resets = true),
            Move("the first network since the tunnel came up", mobile, null, session, up, resets = false),
            Move("the same network back after a gap", wifi, wifi, session, up, resets = false),
            Move("right after connecting", mobile, wifi, session, up - 1, resets = false),
            Move("no tunnel runs", mobile, wifi, null, up, resets = false),
        )
        for (move in moves) {
            assertEquals(move.name, move.resets, HealthMonitor.resetsConnections(move.net, move.previous, move.session, move.now))
        }
    }

    @Test
    fun anotherNetworkResetsTheCoreAfterItSettles() = runTest {
        val w = world()
        advanceTimeBy(3_000)
        w.health.onNetworkChanged(NetId(2), previous = NetId(1))
        // Why the server was switched no longer applies.
        assertEquals(listOf(0L), w.notices.switchClears)
        advanceTimeBy(settle - 1)
        runCurrent()
        assertEquals(emptyList<String>(), w.tunnel.resetsDone)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(networkReset), w.tunnel.resetsDone)
        assertEquals(0, w.checks())
    }

    @Test
    fun theFirstNetworkAndTheSameOneBackAreOnlyChecked() = runTest {
        val w = world()
        advanceTimeBy(3_000)
        w.health.onNetworkChanged(NetId(1), previous = null)
        advanceTimeBy(settle)
        runCurrent()
        assertEquals(1, w.checks())

        // The same network back after a gap: why the server was switched still applies.
        w.notices.switchClears.clear()
        w.health.onNetworkChanged(NetId(1), previous = NetId(1))
        assertEquals(emptyList<Long>(), w.notices.switchClears)
        advanceTimeBy(settle)
        runCurrent()
        assertEquals(2, w.checks())
        assertEquals(emptyList<String>(), w.tunnel.resetsDone)
    }

    @Test
    fun withoutANetworkTheSearchingNoticeGoesAndACheckInProgressIsDropped() = runTest {
        val w = world()
        w.notices.base = Failover.NOTICE_PRIVATE_DNS
        w.trafficStops()
        w.answering(SERVER_B)
        // The network goes while the check tests the traffic.
        w.core.onMeasure = {
            w.core.onMeasure = {}
            w.health.onNetworkChanged(null, previous = NetId(1))
        }
        w.health.schedule(Reason.APP)
        advanceUntilIdle()

        assertEquals(listOf(FakeNotices.Shown(Failover.NOTICE_PRIVATE_DNS, setOf(Failover.NOTICE_SEARCHING))), w.notices.calls)
        assertEquals(emptyList<List<*>>(), w.core.probed)
        assertTrue(w.tunnel.starts.isEmpty())
    }
}
