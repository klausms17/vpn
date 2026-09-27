package com.klausms.vpn.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // advanceTimeBy, runCurrent
class TunnelControllerTest {
    /** Records each command as text, e.g. "reconnect(picked=false)". */
    private class RecordingCommands : TunnelCommands {
        val sent = mutableListOf<String>()
        var failReconnect = false

        override fun connect(picked: Boolean) {
            sent += "connect(picked=$picked)"
        }

        override fun reconnect(picked: Boolean) {
            if (failReconnect) throw IllegalStateException("not allowed to start a service")
            sent += "reconnect(picked=$picked)"
        }

        override fun disconnect(source: String) {
            sent += "disconnect($source)"
        }
    }

    private val commands = RecordingCommands()
    private var up = true

    private fun TestScope.controller() =
        TunnelController(backgroundScope, commands, isUp = { up }, awaitSaves = {})

    @Test
    fun changesInQuickSuccessionReconnectOnceAfterTheLastPause() = runTest {
        val tunnel = controller()
        tunnel.reconnectIfRunning(800)
        advanceTimeBy(500)
        tunnel.reconnectIfRunning(800)
        advanceTimeBy(799)
        assertEquals(emptyList<String>(), commands.sent)
        advanceTimeBy(2)
        assertEquals(listOf("reconnect(picked=false)"), commands.sent)
        advanceTimeBy(10_000)
        assertEquals(1, commands.sent.size)
    }

    @Test
    fun leavingTheAppSendsAPendingChangeAtOnce() = runTest {
        val tunnel = controller()
        tunnel.reconnectIfRunning(800)
        runCurrent()
        tunnel.flushPending()
        assertEquals(listOf("reconnect(picked=false)"), commands.sent)
        advanceTimeBy(10_000)
        assertEquals(1, commands.sent.size)
    }

    @Test
    fun leavingTheAppWithNothingPendingSendsNothing() = runTest {
        val tunnel = controller()
        tunnel.flushPending()
        tunnel.reconnectIfRunning(800)
        advanceTimeBy(10_000)
        tunnel.flushPending()
        assertEquals(listOf("reconnect(picked=false)"), commands.sent)
    }

    @Test
    fun disconnectingCancelsAPendingReconnect() = runTest {
        val tunnel = controller()
        tunnel.reconnectIfRunning(800)
        runCurrent()
        tunnel.disconnect()
        advanceTimeBy(10_000)
        assertEquals(listOf("disconnect(app)"), commands.sent)
    }

    @Test
    fun aServerPickedWhileOffIsReportedByTheNextConnectOnly() = runTest {
        val tunnel = controller()
        tunnel.picked("a", up = false)
        tunnel.connect("a")
        tunnel.connect("a")
        // Picked, then another server became the selection (the VPN process replaced the list).
        tunnel.picked("a", up = false)
        tunnel.connect("b")
        assertEquals(
            listOf("connect(picked=true)", "connect(picked=false)", "connect(picked=false)"),
            commands.sent,
        )
    }

    @Test
    fun aServerPickedWhileUpReconnectsOnceAndTakesAPendingChangeAlong() = runTest {
        val tunnel = controller()
        tunnel.reconnectIfRunning(800)
        runCurrent()
        tunnel.picked("a", up = true)
        advanceTimeBy(10_000)
        assertEquals(listOf("reconnect(picked=true)"), commands.sent)
    }

    @Test
    fun appListChangesAreAppliedOnceWhenTheScreenCloses() = runTest {
        val tunnel = controller()
        tunnel.markAppListsChanged()
        tunnel.applyAppLists()
        tunnel.applyAppLists()
        runCurrent()
        assertEquals(listOf("reconnect(picked=false)"), commands.sent)
    }

    @Test
    fun nothingIsSentToATunnelThatIsOff() = runTest {
        up = false
        val tunnel = controller()
        tunnel.reconnectIfRunning()
        tunnel.reconnectIfRunning(800)
        tunnel.markAppListsChanged()
        tunnel.applyAppLists()
        advanceTimeBy(10_000)
        tunnel.flushPending()
        assertEquals(emptyList<String>(), commands.sent)
    }

    @Test
    fun aReconnectTheSystemRefusesIsLoggedNotThrown() = runTest {
        commands.failReconnect = true
        val tunnel = controller()
        tunnel.picked("a", up = true)
        tunnel.reconnectIfRunning()
        runCurrent()
        assertEquals(emptyList<String>(), commands.sent)
    }
}
