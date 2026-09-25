package com.klausms.vpn.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VpnState(val code: Int) {
    DISCONNECTED(0), CONNECTING(1), CONNECTED(2), DISCONNECTING(3), ERROR(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: DISCONNECTED
    }
}

data class VpnStatus(
    val state: VpnState = VpnState.DISCONNECTED,
    val profileId: String? = null,
    val profileName: String? = null,
    /** Error text for [VpnState.ERROR]. */
    val message: String? = null,
    /** Wall clock millis when the tunnel came up. */
    val connectedSince: Long = 0,
)

data class TrafficStats(
    val upRate: Long = 0,
    val downRate: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
)

/** Status inside the VPN process, observed by the service and the tile. */
object VpnStatusHolder {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    internal fun set(status: VpnStatus) {
        _status.value = status
    }
}
