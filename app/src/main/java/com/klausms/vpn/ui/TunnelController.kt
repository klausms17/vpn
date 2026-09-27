package com.klausms.vpn.ui

import android.content.Context
import com.klausms.vpn.service.VpnCommands
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The commands the UI sends to the VPN process; see [VpnCommands]. */
internal interface TunnelCommands {
    fun connect(picked: Boolean)
    fun reconnect(picked: Boolean)
    fun disconnect(source: String)
}

/** Sends [TunnelCommands] as [VpnCommands] from [context]. */
internal class VpnTunnelCommands(private val context: Context) : TunnelCommands {
    override fun connect(picked: Boolean) {
        VpnCommands.connect(context, picked)
    }

    override fun reconnect(picked: Boolean) {
        VpnCommands.reconnect(context, picked)
    }

    override fun disconnect(source: String) {
        VpnCommands.disconnect(context, source)
    }
}

/**
 * When the UI tells the tunnel to connect, reconnect or disconnect. Owns
 * the pending reconnect of a changed setting (debounced, cancelled by a
 * disconnect, sent at once when the app leaves the screen), the server
 * picked by hand while the VPN was off, and the app-list change waiting
 * for its screen to close.
 *
 * Threading: main thread only, except [markAppListsChanged], which may be
 * called from any thread. [scope] must run on the main thread.
 *
 * @param isUp whether the tunnel runs or is starting, as last reported.
 * @param awaitSaves returns once the settings saves started before it are done.
 */
internal class TunnelController(
    private val scope: CoroutineScope,
    private val commands: TunnelCommands,
    private val isUp: () -> Boolean,
    private val awaitSaves: suspend () -> Unit,
) {
    private var reconnectJob: Job? = null

    /** A server picked by hand while the VPN was off; the next connect tells the service so. */
    private var pickedWhileOff: String? = null

    /** The app lists changed while their screen is open; applied when it closes. */
    @Volatile
    private var appListsChanged = false

    /** Starts the tunnel on [selectedId], the selected server. */
    fun connect(selectedId: String) {
        val picked = pickedWhileOff == selectedId
        pickedWhileOff = null
        commands.connect(picked)
    }

    fun disconnect() {
        // A settings change waiting to be applied must not switch it back on.
        reconnectJob?.cancel()
        commands.disconnect("app")
    }

    /**
     * The user picked server [id] ([up]: the tunnel was running or starting).
     * A running tunnel moves to it now; otherwise the next [connect] says
     * that it was picked by hand.
     */
    fun picked(id: String, up: Boolean) {
        if (up) {
            // Also brings a settings change still waiting out its pause.
            reconnectJob?.cancel()
            send(picked = true)
        } else {
            pickedWhileOff = id
        }
    }

    /**
     * Re-applies server/settings to a running tunnel (debounced). In the app
     * scope: leaving the app during the pause must not lose the change.
     */
    fun reconnectIfRunning(delayMs: Long = 0) {
        if (!isUp()) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            send(picked = false)
        }
    }

    /**
     * The app left the screen: a change still waiting out its short pause
     * goes to the tunnel now, as the process may be killed any moment (swiped
     * away from Recents).
     */
    fun flushPending() {
        val pending = reconnectJob ?: return
        if (!pending.isActive) return
        pending.cancel()
        send(picked = false)
    }

    /** Called inside the settings save that changed the app lists. */
    fun markAppListsChanged() {
        appListsChanged = true
    }

    /** The app list screen closed: one reconnect for all the changes made on it. */
    suspend fun applyAppLists() {
        awaitSaves()
        if (appListsChanged) {
            appListsChanged = false
            reconnectIfRunning()
        }
    }

    /** Sends a reconnect; [picked]: the user has just chosen the server by hand. */
    private fun send(picked: Boolean) {
        try {
            commands.reconnect(picked)
        } catch (e: Exception) {
            AppLog.w("could not apply the change to the tunnel", e)
        }
    }
}
