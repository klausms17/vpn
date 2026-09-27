package com.klausms.vpn.service

/** A start failed; the message is for the user. */
internal class VpnStartException(message: String) : Exception(message)

/** An automatic restart found another app's VPN in place. */
internal class AnotherVpnException : Exception("another VPN is active")

/**
 * What made the service check that traffic gets through: a start, another
 * network, Android's view of the same network (LINK), unlocking, the app
 * opened, the screen on for a while.
 */
internal enum class Reason { START, NETWORK, LINK, UNLOCK, APP, SCREEN }

/** What a subscription refresh in the VPN process did: [applied] is null when the download failed. */
internal class Refreshed(val applied: Boolean?, val runningChanged: Boolean)
