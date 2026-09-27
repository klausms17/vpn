package com.klausms.vpn.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the running tunnel hears about the phone: the network under it
 * (through [UnderlyingNetworkMonitor]) and the screen, with a tick every
 * [screenTickMs] while the screen is on. It keeps the tunnel attributed to
 * the network it rides on ([setUnderlying]) and passes every event on to
 * [listener], which decides what to do about it.
 *
 * Owns the network monitor, the last network seen and the screen receiver
 * with its ticks. Threading: [start], [stop] and every [Listener] call run
 * on the main thread, and so must [scope]; [network] may be read from any
 * thread.
 */
internal class NetworkWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val screenTickMs: Long,
    private val setUnderlying: (Network?) -> Unit,
    private val listener: Listener,
) {
    /** The events, all on the main thread. */
    interface Listener {
        /** The default network is now [net] (null: none), after [previous] (null: none since [start]). */
        fun onNetworkChanged(net: NetId?, previous: NetId?)

        /** The same network lost Android's "internet works" mark ([lost]), or it came back. */
        fun onReachability(lost: Boolean)

        /** The host of a strict «Частный DNS», or null. */
        fun onPrivateDns(host: String?)

        fun onUnlock()

        /** Every [screenTickMs] while the screen is on. */
        fun onScreenTick()
    }

    @Volatile
    private var monitor: UnderlyingNetworkMonitor? = null

    // The last network seen since start(), also through a moment without one.
    @Volatile
    private var lastNetwork: Network? = null

    private var screenRegistered = false
    private var screenJob: Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> listener.onUnlock()
                Intent.ACTION_SCREEN_ON -> startScreenTicks()
                Intent.ACTION_SCREEN_OFF -> stopScreenTicks()
            }
        }
    }

    /** The network under the tunnel, else the last one seen; null while not watching. */
    val network: Network? get() = monitor?.network ?: lastNetwork

    /** Starts watching, unless it already does. */
    fun start() {
        if (monitor != null) return
        val started = UnderlyingNetworkMonitor(
            context,
            onChanged = { network -> onChanged(network) },
            onReachability = { lost -> listener.onReachability(lost) },
            onPrivateDns = { host -> listener.onPrivateDns(host) },
        )
        started.start()
        lastNetwork = started.network
        monitor = started
        registerScreen()
    }

    /** Stops watching and forgets the network. */
    fun stop() {
        monitor?.stop()
        monitor = null
        lastNetwork = null
        unregisterScreen()
    }

    private fun onChanged(network: Network?) {
        // Lets Android attribute the tunnel to Wi-Fi/mobile (metered state,
        // "no internet" detection) correctly.
        setUnderlying(network)
        val previous = lastNetwork
        if (network != null) lastNetwork = network
        listener.onNetworkChanged(network?.netId(), previous?.netId())
    }

    private fun registerScreen() {
        if (screenRegistered) return
        try {
            // Exported, unlike a receiver for our own broadcasts: SystemUI,
            // not the system server, sends USER_PRESENT, and a not-exported
            // receiver never gets it. Only the system may send these at all
            // (protected broadcasts), and they only trigger throttled checks.
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            screenRegistered = true
        } catch (e: Exception) {
            AppLog.w("screen receiver", e)
        }
        if (context.getSystemService(PowerManager::class.java)?.isInteractive == true) startScreenTicks()
    }

    private fun unregisterScreen() {
        stopScreenTicks()
        if (!screenRegistered) return
        screenRegistered = false
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * While the screen is on, a server blocked mid-session must not go
     * unnoticed until the next unlock: the listener hears a tick every few
     * minutes. On the main thread; the radio is in use anyway while the
     * phone is.
     */
    private fun startScreenTicks() {
        if (screenJob?.isActive == true) return
        screenJob = scope.launch {
            while (true) {
                delay(screenTickMs)
                listener.onScreenTick()
            }
        }
    }

    private fun stopScreenTicks() {
        screenJob?.cancel()
        screenJob = null
    }
}
