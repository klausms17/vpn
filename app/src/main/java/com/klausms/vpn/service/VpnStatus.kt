package com.klausms.vpn.service

import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
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
    /**
     * Error text for [VpnState.ERROR]; for [VpnState.CONNECTED] a notice to
     * show next to the status (e.g. the server was switched), usually null.
     */
    val message: String? = null,
    /** Wall clock millis when the tunnel came up. */
    val connectedSince: Long = 0,
) {
    /** The tunnel runs, or is starting or stopping. */
    val active: Boolean
        get() = state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.DISCONNECTING

    /**
     * The server to show: the one the tunnel runs (or is starting or
     * stopping), else the selected one. Null while the running one is no
     * longer in [profiles] (a refresh removed it): [profileName] still names it.
     */
    fun shownServer(profiles: ProfilesState): StoredProfile? {
        val running = profileId?.takeIf { active } ?: return profiles.selected
        return profiles.profiles.firstOrNull { it.id == running }
    }
}

/** Status inside the VPN process, observed by the service and the tile. */
object VpnStatusHolder {
    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    internal fun set(status: VpnStatus) {
        _status.value = status
    }

    /** Replaces [expected] with [status] only if nothing else changed it meanwhile. */
    internal fun compareAndSet(expected: VpnStatus, status: VpnStatus): Boolean = _status.compareAndSet(expected, status)
}
