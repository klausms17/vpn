package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SubscriptionRefresherTest {
    /** Answers [result]; while [gate] is set, each refresh waits for it. */
    private class FakeSource(var result: Refreshed) : SubscriptionSource {
        class Call(val subId: String, val through: CoreHandle?, val runningId: String?)

        val calls = mutableListOf<Call>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun refresh(subId: String, through: CoreHandle?, runningId: String?): Refreshed {
            calls += Call(subId, through, runningId)
            gate?.await()
            return result
        }
    }

    private val clock = FakeClock(wallMs = 1_000_000_000)
    private val sub = Subscription(id = "sub", name = "Друзья", url = "https://panel.example/sub/abc")
    private val failed = StoredProfile(id = "s1", name = "Сервер", outbounds = JsonArray(emptyList()), subscriptionId = "sub")
    private val state = ProfilesState(profiles = listOf(failed), subscriptions = listOf(sub), selectedId = "s1")
    private val core = FakeCore()
    private val running = TunnelSession(failed, config = "{}", core = core, connectedAt = 0, lockdownConflict = false)
    private val source = FakeSource(Refreshed(applied = true, runningChanged = false))
    private var reloads = 0

    private fun TestScope.refresher() = SubscriptionRefresher(
        this, StandardTestDispatcher(testScheduler), clock, source,
        session = { running },
        profilesChanged = { reloads++ },
    )

    @Test
    fun aSubscriptionIsDownloadedAgainAtMostEveryTenMinutes() = runTest {
        val refresher = refresher()
        val tried = { at: Long -> state.copy(subscriptions = listOf(sub.copy(lastAttemptAt = at))) }
        assertNull(refresher.refreshable(tried(clock.wallMs - Failover.REFRESH_MS + 1), failed))
        assertEquals(sub.id, refresher.refreshable(tried(clock.wallMs - Failover.REFRESH_MS), failed)?.id)
        // A clock set back counts as due.
        assertEquals(sub.id, refresher.refreshable(tried(clock.wallMs + 1), failed)?.id)
        assertNull(refresher.refreshable(state, failed.copy(subscriptionId = null)))
    }

    @Test
    fun oneRefreshAtATime() = runTest {
        val refresher = refresher()
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        refresher.startDirect(sub)
        runCurrent()
        assertNull(refresher.refreshable(state, failed))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(sub.id, refresher.refreshable(state, failed)?.id)
    }

    @Test
    fun aDirectDownloadNamesTheRunningServerAndReloadsTheApp() = runTest {
        val refresher = refresher()
        val result = refresher.startDirect(sub).await()
        assertEquals(true, result.applied)
        val call = source.calls.single()
        assertEquals("sub", call.subId)
        assertNull(call.through)
        assertEquals("s1", call.runningId)
        assertEquals(1, reloads)
    }

    @Test
    fun aFailedDirectDownloadIsOwedAndPaidOnceThroughTheTunnel() = runTest {
        val refresher = refresher()
        source.result = Refreshed(applied = null, runningChanged = false)
        refresher.startDirect(sub).await()

        source.result = Refreshed(applied = true, runningChanged = false)
        var restarts = 0
        refresher.payOwed(core) { restarts++ }
        advanceUntilIdle()
        refresher.payOwed(core) { restarts++ }
        advanceUntilIdle()

        assertEquals(2, source.calls.size)
        assertSame(core, source.calls[1].through)
        assertEquals(0, restarts)
        assertEquals(2, reloads)
    }

    @Test
    fun aDownloadThatGotAnAnswerOwesNothing() = runTest {
        val refresher = refresher()
        // The panel answered without servers: the old ones stay, nothing to download again.
        source.result = Refreshed(applied = false, runningChanged = false)
        refresher.startDirect(sub).await()
        refresher.payOwed(core) { error("nothing was owed") }
        advanceUntilIdle()
        assertEquals(1, source.calls.size)
    }

    @Test
    fun anOwedRefreshThatChangesTheRunningServerRestartsIt() = runTest {
        val refresher = refresher()
        source.result = Refreshed(applied = null, runningChanged = false)
        refresher.startDirect(sub).await()
        source.result = Refreshed(applied = true, runningChanged = true)
        var restarts = 0
        refresher.payOwed(core) { restarts++ }
        advanceUntilIdle()
        assertEquals(1, restarts)
    }
}
