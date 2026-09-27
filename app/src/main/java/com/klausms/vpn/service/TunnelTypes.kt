package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.StoredProfile

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

/**
 * A network as Android numbers it (Network.networkHandle), without the
 * Network object: two are equal exactly when their Networks are. Lets
 * logic kept per network run on the JVM.
 */
@JvmInline
internal value class NetId(val handle: Long)

/**
 * What the phone's default network offers right now. [hasNetwork]: it is
 * not paused for a moment (mobile data in a lift or a tunnel: the same one
 * comes back). [captive]: a Wi-Fi that asks to sign in first.
 * [cellular]: mobile data.
 */
internal data class NetState(val hasNetwork: Boolean, val captive: Boolean, val cellular: Boolean)

/**
 * The tunnel that runs: [core] runs [config] for [profile], connected (or
 * last reset in place) at [connectedAt], elapsed time. [lockdownConflict]:
 * "Block connections without VPN" leaves the apps kept outside this tunnel
 * without network. Immutable; [TunnelEngine] holds the current one, and
 * none while no core runs.
 */
internal data class TunnelSession(
    val profile: StoredProfile,
    val config: String,
    val core: CoreHandle,
    val connectedAt: Long,
    val lockdownConflict: Boolean,
) {
    // The config carries the server's keys: never into a log or a crash report.
    override fun toString(): String = "TunnelSession(connectedAt=$connectedAt, lockdownConflict=$lockdownConflict)"
}

/**
 * One start of the tunnel. [startId]: the command it answers; a start
 * that fails for good stops the service unless a newer command arrived.
 * [userRequested]: the user asked just now (the app, the tile, the widget,
 * a reconnect); only such a start may take the VPN over from another app,
 * and a first start the user asked for is not retried (see
 * [StartFailurePolicy]). [picked]: the user has just chosen the server by
 * hand. [switch]: an automatic move to another server instead of the
 * selected one. [attempt]: 0 for the first try, then the retry's number.
 */
internal data class StartRequest(
    val startId: Int,
    val userRequested: Boolean,
    val picked: Boolean = false,
    val switch: Switch? = null,
    val attempt: Int = 0,
)

/**
 * An automatic move of the tunnel away from [failedId] to [winnerId],
 * which then becomes the selection unless the user chose another than
 * [expectedSelection] meanwhile. [notice] is shown once connected.
 */
internal data class Switch(val winnerId: String, val failedId: String, val expectedSelection: String?, val notice: String?)
