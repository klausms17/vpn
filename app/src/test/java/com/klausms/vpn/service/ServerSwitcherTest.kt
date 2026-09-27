package com.klausms.vpn.service

import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_A
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_B
import com.klausms.vpn.service.FailoverWorld.Companion.SERVER_C
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSwitcherTest {
    private fun report(failed: String, winner: String) = FakeReports.Report(failed, allDown = false, winnerId = winner, whitelist = false)

    /** A world whose tunnel has just been switched from A to B. */
    private suspend fun TestScope.switchedToB(): FailoverWorld {
        val w = FailoverWorld(this)
        w.switcher.switchTo(w.epoch.current, SERVER_A, SERVER_B.id)
        return w
    }

    @Test
    fun aSwitchMovesTheTunnelAndTheSelectionAndRemembersTheWayBack() = runTest {
        val w = switchedToB()
        val notice = Failover.switchedNotice(SERVER_B.name, SERVER_A.name)
        assertEquals(
            listOf(StartRequest(1, userRequested = false, switch = Switch(SERVER_B.id, SERVER_A.id, expectedSelection = SERVER_A.id, notice, report = true))),
            w.tunnel.starts,
        )
        assertEquals(SERVER_B.id, w.tunnel.session?.profile?.id)
        assertEquals(SERVER_B.id, w.profiles.state.selectedId)
        assertEquals(1, w.reloads)
        assertEquals(1, w.runtime.switchesTaken)
        val away = w.runtime.awayState
        assertEquals(SERVER_A.id, away?.home)
        assertEquals(SERVER_B.id, away?.to)
        assertTrue(w.memory.failedRecently(SERVER_A.id))
        assertEquals(listOf(report(SERVER_A.id, SERVER_B.id)), w.reports.sent)
    }

    @Test
    fun aServerThatAnsweredANewConnectionIsMarkedButNotReported() = runTest {
        val w = FailoverWorld(this)
        w.switcher.switchTo(w.epoch.current, SERVER_A, SERVER_B.id, report = false)
        assertTrue(w.memory.failedRecently(SERVER_A.id))
        assertEquals(emptyList<FakeReports.Report>(), w.reports.sent)
    }

    private class Dropped(val name: String, val notice: String? = null, val clearsFailure: Boolean = false, val setUp: FailoverWorld.() -> Unit)

    @Test
    fun aSwitchIsDroppedWhenAnythingChangedSinceTheProbe() = runTest {
        val cases = listOf(
            Dropped("another generation") { epoch.advance() },
            Dropped("no tunnel") { tunnel.session = null },
            Dropped("turned off") { runtime.run = false },
            Dropped("not shown as connected") { status = VpnStatus(VpnState.CONNECTING) },
            Dropped("another server chosen", clearsFailure = true) {
                profiles.state = profiles.state.copy(selectedId = SERVER_C.id)
            },
            Dropped("the winner deleted", clearsFailure = true) {
                profiles.state = profiles.state.copy(profiles = listOf(SERVER_A, SERVER_C))
            },
            Dropped("switches used up", notice = Failover.NOTICE_PICK_ANOTHER) { runtime.switchBudget = false },
        )
        for (case in cases) {
            val w = FailoverWorld(this)
            val e = w.epoch.current
            case.setUp(w)
            w.switcher.switchTo(e, SERVER_A, SERVER_B.id)
            assertEquals(case.name, emptyList<StartRequest>(), w.tunnel.starts)
            assertEquals(case.name, listOfNotNull(case.notice), w.notices.shown)
            assertEquals(case.name, if (case.clearsFailure) 1 else 0, w.notices.failuresCleared)
            assertFalse(case.name, w.memory.failedRecently(SERVER_A.id))
            assertEquals(case.name, emptyList<FakeReports.Report>(), w.reports.sent)
        }
    }

    @Test
    fun theSameServerWithNewSettingsIsNoSwitchAway() = runTest {
        val w = FailoverWorld(this)
        w.switcher.switchTo(w.epoch.current, SERVER_A, SERVER_A.id)
        assertEquals(SERVER_A.id, w.tunnel.starts.single().switch?.winnerId)
        assertNull(w.tunnel.starts.single().switch?.notice)
        assertFalse(w.memory.failedRecently(SERVER_A.id))
        assertEquals(emptyList<FakeReports.Report>(), w.reports.sent)
        assertNull(w.runtime.awayState)
    }

    @Test
    fun aSwitchThatDidNotBringTheWinnerUpMarksNothing() = runTest {
        val w = FailoverWorld(this)
        w.tunnel.startsFail = true
        w.switcher.switchTo(w.epoch.current, SERVER_A, SERVER_B.id)
        assertEquals(1, w.tunnel.starts.size)
        assertFalse(w.memory.failedRecently(SERVER_A.id))
        assertEquals(emptyList<FakeReports.Report>(), w.reports.sent)
        assertEquals(SERVER_A.id, w.profiles.state.selectedId)
    }

    @Test
    fun aSwitchThatCameUpOnItsRetryMarksAndReportsTheFailedServer() = runTest {
        val w = FailoverWorld(this)
        w.tunnel.startsFail = true
        w.switcher.switchTo(w.epoch.current, SERVER_A, SERVER_B.id)
        assertFalse(w.memory.failedRecently(SERVER_A.id))
        // The engine tries the same start again a moment later (TunnelEngine.retryLater).
        w.tunnel.startsFail = false
        w.tunnel.start(w.tunnel.starts.single().copy(attempt = 1))

        assertEquals(SERVER_B.id, w.tunnel.session?.profile?.id)
        assertEquals(SERVER_B.id, w.profiles.state.selectedId)
        assertTrue(w.memory.failedRecently(SERVER_A.id))
        assertEquals(listOf(report(SERVER_A.id, SERVER_B.id)), w.reports.sent)
    }

    // --------------------------------------------------- back to the user's server

    @Test
    fun theUsersServerIsGoneBackToOnceItAnswers() = runTest {
        val w = switchedToB()
        advanceTimeBy(Failover.RECENTLY_FAILED_MS)
        w.answering(SERVER_A)
        w.switcher.returnHomeIfItAnswers(w.epoch.current, w.core, SERVER_B)
        advanceUntilIdle()

        assertEquals(Switch(SERVER_A.id, SERVER_B.id, expectedSelection = SERVER_B.id, notice = null), w.tunnel.starts.last().switch)
        assertEquals(SERVER_A.id, w.tunnel.session?.profile?.id)
        assertEquals(SERVER_A.id, w.profiles.state.selectedId)
        assertNull(w.runtime.awayState)
        assertEquals(SERVER_A.id, w.runtime.returnedState?.id)
        // Going back is no failure of the server it leaves.
        assertFalse(w.memory.failedRecently(SERVER_B.id))
        assertEquals(listOf(report(SERVER_A.id, SERVER_B.id)), w.reports.sent)
    }

    @Test
    fun aUsersServerThatStillFailsIsTriedLessOften() = runTest {
        val w = switchedToB()
        val before = w.runtime.awayState!!
        advanceTimeBy(Failover.RECENTLY_FAILED_MS)
        w.switcher.returnHomeIfItAnswers(w.epoch.current, w.core, SERVER_B)
        advanceUntilIdle()

        assertEquals(1, w.tunnel.starts.size)
        assertEquals(Failover.returnFailed(before, w.clock.elapsed()), w.runtime.awayState)
    }

    @Test
    fun aWayBackWithoutSwitchesLeftSaysNothing() = runTest {
        val w = switchedToB()
        w.runtime.switchBudget = false
        w.switcher.switchTo(w.epoch.current, SERVER_B, SERVER_A.id, returning = true)
        assertEquals(1, w.tunnel.starts.size)
        assertEquals(emptyList<String?>(), w.notices.shown)
    }

    private class NoWayBack(val name: String, val running: StoredProfile = SERVER_B, val setUp: FailoverWorld.() -> Unit = {})

    @Test
    fun theWayBackIsNotTriedWhileItCannotBeTaken() = runTest {
        val cases = listOf(
            NoWayBack("not due yet") { runtime.awayState = runtime.awayState?.copy(retryAt = clock.elapsed() + 1) },
            NoWayBack("the user's server failed again lately") { memory.markFailed(SERVER_A.id) },
            NoWayBack("the tunnel runs another server", running = SERVER_C),
            NoWayBack("another server chosen") { profiles.state = profiles.state.copy(selectedId = SERVER_C.id) },
            NoWayBack("switches used up") { runtime.switchBudget = false },
        )
        for (case in cases) {
            val w = switchedToB()
            advanceTimeBy(Failover.RECENTLY_FAILED_MS)
            w.answering(SERVER_A)
            case.setUp(w)
            val away = w.runtime.awayState
            w.switcher.returnHomeIfItAnswers(w.epoch.current, w.core, case.running)
            advanceUntilIdle()
            assertEquals(case.name, 0, w.core.probed.size)
            assertEquals(case.name, 1, w.tunnel.starts.size)
            assertSame(case.name, away, w.runtime.awayState)
        }
    }

    @Test
    fun aDeletedUsersServerIsNoLongerGoneBackTo() = runTest {
        val w = switchedToB()
        advanceTimeBy(Failover.RECENTLY_FAILED_MS)
        w.profiles.state = w.profiles.state.copy(profiles = listOf(SERVER_B, SERVER_C))
        w.switcher.returnHomeIfItAnswers(w.epoch.current, w.core, SERVER_B)
        assertNull(w.runtime.awayState)
        assertEquals(0, w.core.probed.size)
    }

    @Test
    fun theWayBackSaysNothingAfterANetworkChangeDuringItsProbe() = runTest {
        val w = switchedToB()
        w.answering(SERVER_A)
        advanceTimeBy(Failover.RECENTLY_FAILED_MS)
        val away = w.runtime.awayState
        for (answers in listOf(true, false)) {
            if (!answers) w.core.probeDelays.clear()
            val e = w.epoch.current
            w.core.onProbe = { w.epoch.advance() }
            w.switcher.returnHomeIfItAnswers(e, w.core, SERVER_B)
            advanceUntilIdle()
        }
        assertEquals(2, w.core.probed.size)
        assertEquals(1, w.tunnel.starts.size)
        assertSame(away, w.runtime.awayState)
    }

    // --------------------------------------------------------- other starts

    @Test
    fun theSavedSelectionRunsAgainOnlyWhileTheSameServerRuns() = runTest {
        val w = FailoverWorld(this)
        val running = w.tunnel.session!!.profile
        w.switcher.restartOnSaved(running)
        advanceUntilIdle()
        assertEquals(listOf(StartRequest(1, userRequested = false)), w.tunnel.starts)

        // Another start came first: its server object is another one, even with the same id.
        w.tunnel.runOn(SERVER_A.copy())
        w.switcher.restartOnSaved(running)
        advanceUntilIdle()
        assertEquals(1, w.tunnel.starts.size)

        w.runtime.run = false
        w.switcher.restartOnSaved(w.tunnel.session!!.profile)
        advanceUntilIdle()
        assertEquals(1, w.tunnel.starts.size)
    }

    @Test
    fun aStartThatWasNoSwitchEndsTheWayBackWhenThePickChanged() = runTest {
        val w = switchedToB()
        val away = w.runtime.awayState
        // New settings for the server switched to: the way back stays.
        w.switcher.afterStart(SERVER_B.id, StartRequest(2, userRequested = true))
        assertSame(away, w.runtime.awayState)
        // Picked by hand: the user decided.
        w.switcher.afterStart(SERVER_B.id, StartRequest(3, userRequested = true, picked = true))
        assertNull(w.runtime.awayState)

        w.runtime.awayState = away
        // Another server runs (a refresh or a delete moved the selection).
        w.switcher.afterStart(SERVER_C.id, StartRequest(4, userRequested = false))
        assertNull(w.runtime.awayState)
    }
}
