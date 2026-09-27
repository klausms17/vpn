package com.klausms.vpn.service

import com.klausms.vpn.core.DirectNet
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.service.Failover.NOTICE_ALL_BLOCKED
import com.klausms.vpn.service.Failover.NOTICE_BLOCKED
import com.klausms.vpn.service.Failover.NOTICE_NONE_ANSWER
import com.klausms.vpn.service.Failover.NOTICE_NO_OTHER
import com.klausms.vpn.service.Failover.NOTICE_PICK_ANOTHER
import com.klausms.vpn.service.Failover.NOTICE_SEARCHING
import com.klausms.vpn.service.Failover.NOTICE_SIGN_IN
import com.klausms.vpn.service.Failover.NOTICE_WHITELIST
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_A
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_B
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_C
import com.klausms.vpn.service.FailoverWorld.Companion.server
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FailoverSearchTest {
    private val russian = DirectNet.DIRECT_URL
    private val foreign = XrayCore.TEST_URL

    /** Whether the search asked if the failed server recovered in place, and what that says. */
    private var recoveryAsked = 0
    private var recovers = false

    private fun TestScope.world() = FailoverWorld(this)

    private suspend fun FailoverWorld.searchFromA(stalled: Boolean = false): SearchOutcome =
        search.search(epoch.current, core, SERVER_A, stalled) {
            recoveryAsked++
            recovers
        }

    private fun FailoverWorld.probedIds(round: Int): List<String> {
        val all = profiles.state.profiles
        return core.probed[round].map { outbounds -> all.single { it.outbounds == outbounds }.id }
    }

    // --------------------------------------------------------------- gates

    private class Gate(val name: String, val notice: String?, val setUp: FailoverWorld.() -> Unit)

    @Test
    fun eachGateStopsTheSearchBeforeAnyProbe() = runTest {
        val gates = listOf(
            Gate("no network at all", notice = null) { net.netState = null },
            Gate("the network paused", notice = null) { net.netState = NetState(hasNetwork = false, captive = false, cellular = true) },
            Gate("a Wi-Fi login page", NOTICE_SIGN_IN) { net.netState = NetState(hasNetwork = true, captive = true, cellular = false) },
            Gate("picked by hand after it failed", NOTICE_PICK_ANOTHER) {
                memory.markFailed(SERVER_A.id)
                memory.onStarted(SERVER_A.id, automatic = false, picked = true)
            },
            Gate("switches used up", NOTICE_PICK_ANOTHER) { runtime.switchBudget = false },
            Gate("searches used up", NOTICE_PICK_ANOTHER) { runtime.searchBudget = false },
        )
        for (gate in gates) {
            val w = world()
            w.answering(SERVER_B)
            gate.setUp(w)
            assertEquals(gate.name, SearchOutcome.Done, w.searchFromA())
            assertEquals(gate.name, listOfNotNull(gate.notice), w.notices.shown)
            assertEquals(gate.name, 0, w.core.probed.size)
            assertEquals(gate.name, 0, w.runtime.switchesTaken)
        }
    }

    @Test
    fun aSearchThatFoundNothingWaitsTwoMinutesOnThatNetwork() = runTest {
        val w = world()
        w.direct.opening += russian
        w.searchFromA()
        assertEquals(1, w.core.probed.size)

        // Moments later on the same network: the same notice, no probe.
        w.notices.calls.clear()
        assertEquals(SearchOutcome.Done, w.searchFromA())
        assertEquals(listOf(NOTICE_ALL_BLOCKED), w.notices.shown)
        assertEquals(1, w.core.probed.size)

        // Another network may reach what this one could not.
        w.net.network = NetId(2)
        w.searchFromA()
        assertEquals(2, w.core.probed.size)

        w.net.network = NetId(1)
        advanceTimeBy(2 * 60_000L)
        w.searchFromA()
        assertEquals(3, w.core.probed.size)
    }

    // ------------------------------------------------------------- probing

    @Test
    fun theFailedServerIsProbedFirstAsAControl() = runTest {
        val w = world()
        w.answering(SERVER_A, SERVER_B)
        recovers = true
        assertEquals(SearchOutcome.Done, w.searchFromA())
        assertEquals(1, recoveryAsked)
        assertEquals(listOf("a", "b", "c"), w.probedIds(0))
    }

    @Test
    fun aControlThatAnsweredButDidNotRecoverStillSwitchesWithoutAReport() = runTest {
        val w = world()
        w.answering(SERVER_A, SERVER_B)
        recovers = false
        // It answered a new connection: it is not blocked from here.
        assertEquals(SearchOutcome.SwitchTo(SERVER_B.id, report = false), w.searchFromA())
        assertEquals(1, recoveryAsked)
    }

    @Test
    fun aStallProbesNoControl() = runTest {
        val w = world()
        w.answering(SERVER_A, SERVER_B)
        assertEquals(SearchOutcome.SwitchTo(SERVER_B.id, report = true), w.searchFromA(stalled = true))
        assertEquals(0, recoveryAsked)
        assertEquals(listOf("b", "c"), w.probedIds(0))
    }

    @Test
    fun theFirstRoundsWinnerIsTheFastestOfTheClosestGroup() = runTest {
        val w = world()
        // The own key is faster, but not twice as fast: the subscription's server wins.
        w.answering(SERVER_B, ms = 180)
        w.answering(SERVER_C, ms = 100)
        assertEquals(SearchOutcome.SwitchTo(SERVER_B.id, report = true), w.searchFromA())
        assertEquals(listOf(NOTICE_SEARCHING), w.notices.shown)
        assertEquals(1, w.runtime.searchesTaken)
        // Only the switch itself counts one.
        assertEquals(0, w.runtime.switchesTaken)
        assertEquals(0, w.source.refreshed.size)
    }

    @Test
    fun aRefreshThatBringsNewServersGetsASecondRound() = runTest {
        val w = world()
        w.subscriptionDue()
        val d = server("d")
        w.answering(d)
        w.source.answer = {
            w.profiles.state = w.profiles.state.let { it.copy(profiles = it.profiles + d) }
            Refreshed(applied = true, runningChanged = false)
        }
        assertEquals(SearchOutcome.SwitchTo(d.id, report = true), w.searchFromA())
        assertEquals(listOf("s1" to null), w.source.refreshed)
        // Only what the refresh brought.
        assertEquals(listOf("d"), w.probedIds(1))
    }

    @Test
    fun aRefreshThatChangedTheRunningServerRestartsOnTheSavedOne() = runTest {
        val w = world()
        w.subscriptionDue()
        w.source.answer = { Refreshed(applied = true, runningChanged = true) }
        assertEquals(SearchOutcome.RestartOnSaved, w.searchFromA())
    }

    // -------------------------------------------------------- nothing answers

    private class NoAnswer(val online: Boolean, val others: Boolean, val notice: String)

    @Test
    fun whenNothingAnswersTheNoticeSaysWhyAndAnOnlinePhoneReports() = runTest {
        val cases = listOf(
            NoAnswer(online = true, others = true, NOTICE_ALL_BLOCKED),
            NoAnswer(online = true, others = false, NOTICE_BLOCKED),
            NoAnswer(online = false, others = true, NOTICE_NONE_ANSWER),
            NoAnswer(online = false, others = false, NOTICE_NO_OTHER),
        )
        for (case in cases) {
            val name = "online ${case.online}, others ${case.others}"
            val w = world()
            if (case.online) w.direct.opening += russian
            if (!case.others) w.profiles.state = w.profiles.state.copy(profiles = listOf(SERVER_A))
            assertEquals(name, SearchOutcome.Done, w.searchFromA())
            // Searching is claimed, and counted, only when there is something to try.
            assertEquals(name, listOfNotNull(NOTICE_SEARCHING.takeIf { case.others }, case.notice), w.notices.shown)
            assertEquals(name, if (case.others) 1 else 0, w.runtime.searchesTaken)
            val report = FakeReports.Report(SERVER_A.id, allDown = true, winnerId = null, whitelist = false)
            assertEquals(name, listOfNotNull(report.takeIf { case.online }), w.reports.sent)
            // Off mobile data, only the Russian site is asked.
            assertEquals(name, listOf(russian), w.direct.asked)
        }
    }

    @Test
    fun onMobileDataOnlyTheWhitelistOpeningIsToldFromABlock() = runTest {
        val w = world()
        w.net.netState = NetState(hasNetwork = true, captive = false, cellular = true)
        w.direct.opening += russian
        assertEquals(SearchOutcome.Done, w.searchFromA())
        assertEquals(listOf(NOTICE_SEARCHING, NOTICE_WHITELIST), w.notices.shown)
        assertEquals(listOf(FakeReports.Report(SERVER_A.id, allDown = true, winnerId = null, whitelist = true)), w.reports.sent)
        assertEquals(listOf(russian, foreign), w.direct.asked)
    }

    @Test
    fun aFailedServerThatAnsweredANewConnectionIsNotReported() = runTest {
        val w = world()
        w.answering(SERVER_A)
        w.direct.opening += russian
        assertEquals(SearchOutcome.Done, w.searchFromA())
        assertEquals(listOf(NOTICE_SEARCHING, NOTICE_ALL_BLOCKED), w.notices.shown)
        assertEquals(emptyList<FakeReports.Report>(), w.reports.sent)
    }

    // ----------------------------------------------------------- stale epoch

    private class Stale(val name: String, val setUp: FailoverWorld.() -> Unit)

    @Test
    fun aSearchThatOutlivesItsGenerationEndsWithoutAResult() = runTest {
        val cases = listOf(
            Stale("during the first probe") {
                answering(SERVER_A, SERVER_B)
                core.onProbe = { epoch.advance() }
            },
            Stale("during the refresh") {
                subscriptionDue()
                source.answer = {
                    epoch.advance()
                    Refreshed(applied = true, runningChanged = true)
                }
            },
            Stale("during the second probe") {
                subscriptionDue()
                val d = server("d")
                answering(d)
                source.answer = {
                    profiles.state = profiles.state.let { it.copy(profiles = it.profiles + d) }
                    Refreshed(applied = true, runningChanged = false)
                }
                core.onProbe = { if (core.probed.size == 2) epoch.advance() }
            },
            Stale("while asking outside the tunnel") {
                direct.opening += russian
                direct.onOpen = { epoch.advance() }
            },
        )
        for (case in cases) {
            val w = world()
            case.setUp(w)
            recoveryAsked = 0
            assertEquals(case.name, SearchOutcome.Done, w.searchFromA())
            assertEquals(case.name, 0, recoveryAsked)
            assertEquals(case.name, listOf(NOTICE_SEARCHING), w.notices.shown)
            assertEquals(case.name, emptyList<FakeReports.Report>(), w.reports.sent)
            // Nothing was learnt about this network: the next search probes again.
            val probes = w.core.probed.size
            w.core.onProbe = {}
            w.direct.onOpen = {}
            w.searchFromA()
            assertTrue(case.name, w.core.probed.size > probes)
        }
    }

    @Test
    fun aRefreshThatChangedTheRunningServerRestartsItEvenAfterANetworkChange() = runTest {
        val w = world()
        w.subscriptionDue()
        w.source.answer = { Refreshed(applied = true, runningChanged = true) }
        w.direct.onOpen = { w.epoch.advance() }
        assertEquals(SearchOutcome.RestartOnSaved, w.searchFromA())
        assertEquals(listOf(NOTICE_SEARCHING), w.notices.shown)
    }

    @Test
    fun aProbeThatFailsCountsAsNoAnswer() = runTest {
        val w = world()
        w.core.onProbe = { throw IllegalStateException("core is not running") }
        assertEquals(listOf(-1L, -1L), w.search.probe(w.core, listOf(SERVER_B, SERVER_C)))
        assertEquals(emptyList<Long>(), w.search.probe(w.core, emptyList()))
    }
}
