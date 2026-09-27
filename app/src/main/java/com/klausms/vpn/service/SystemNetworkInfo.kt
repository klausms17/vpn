package com.klausms.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

/**
 * The phone's default network, as ConnectivityManager reports it at the
 * moment of each call. This app is excluded from its own tunnel, so that
 * is the network under the tunnel. Keeps no state, and a query that fails
 * counts as no network. Thread-safe.
 */
internal class SystemNetworkInfo(context: Context) {
    private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    /** The default network, or null when there is none. */
    fun active(): NetId? = try {
        cm?.activeNetwork?.netId()
    } catch (_: Exception) {
        null
    }

    /** What the default network offers, or null when there is none. */
    fun state(): NetState? {
        val caps = try {
            cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        } catch (_: Exception) {
            null
        } ?: return null
        return NetState(
            hasNetwork = Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED),
            captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
    }
}

internal fun Network.netId(): NetId = NetId(networkHandle)
