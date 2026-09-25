package com.klausms.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper

/**
 * Follows the device's real default network (Wi-Fi / mobile). This app is
 * excluded from its own tunnel, so its default network is never the VPN.
 * The callback is passive: it never keeps a radio awake.
 */
class UnderlyingNetworkMonitor(
    context: Context,
    private val onChanged: (network: Network?) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private var current: Network? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network != current) {
                current = network
                onChanged(network)
            }
        }

        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                onChanged(null)
            }
        }
    }

    fun start() {
        if (registered) return
        current = cm.activeNetwork
        cm.registerDefaultNetworkCallback(callback, handler)
        registered = true
    }

    fun stop() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
        }
        registered = false
        current = null
    }

    val network: Network? get() = current ?: cm.activeNetwork
}
