package com.klausms.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Follows the device's real default network (Wi-Fi / mobile). This app is
 * excluded from its own tunnel, so its default network is never the VPN.
 * The callback is passive: it never keeps a radio awake.
 *
 * [onChanged]: another network (or none). [onReachability]: the same
 * network lost Android's "internet works" mark (lost = true), or it came
 * back: the mark regained, a Wi-Fi login done, a pause of mobile data
 * ended. [onPrivateDns]: the host of a strict «Частный DNS», or null.
 */
class UnderlyingNetworkMonitor(
    context: Context,
    private val onChanged: (network: Network?) -> Unit,
    private val onReachability: (lost: Boolean) -> Unit = {},
    private val onPrivateDns: (host: String?) -> Unit = {},
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    // Written on the main thread; read through network from any thread.
    @Volatile
    private var current: Network? = null

    // What the current network last reported; null until its first report.
    private var validated: Boolean? = null
    private var captive = false
    private var suspended = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network != current) {
                current = network
                validated = null
                onChanged(network)
            }
        }

        override fun onLost(network: Network) {
            if (network == current) {
                current = null
                validated = null
                onChanged(null)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (network != current) return
            val nowValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val nowCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val nowSuspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            val before = validated
            val wasCaptive = captive
            val wasSuspended = suspended
            validated = nowValidated
            captive = nowCaptive
            suspended = nowSuspended
            // The first report of a network is no change. Signal strength and
            // the like change all the time and are not looked at.
            if (before == null) return
            when {
                before && !nowValidated -> onReachability(true)
                (!before && nowValidated) || (wasCaptive && !nowCaptive) || (wasSuspended && !nowSuspended) -> onReachability(false)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            // Android 9 still kept VPN traffic out of Private DNS.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            if (network != current) return
            onPrivateDns(linkProperties.privateDnsServerName)
        }
    }

    fun start() {
        if (registered) return
        current = cm.activeNetwork
        validated = null
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
