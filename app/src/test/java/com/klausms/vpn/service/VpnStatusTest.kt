package com.klausms.vpn.service

import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnStatusTest {
    private fun server(id: String) = StoredProfile(id = id, name = id, outbounds = JsonArray(emptyList()))

    private val profiles = ProfilesState(profiles = listOf(server("a"), server("b")), selectedId = "a")

    @Test
    fun theRunningServerIsShownElseTheSelectedOne() {
        assertEquals("b", VpnStatus(VpnState.CONNECTED, "b", "b").shownServer(profiles)?.id)
        assertEquals("b", VpnStatus(VpnState.DISCONNECTING, "b", "b").shownServer(profiles)?.id)
        // Off, or starting before it knows which server.
        assertEquals("a", VpnStatus(VpnState.DISCONNECTED).shownServer(profiles)?.id)
        assertEquals("a", VpnStatus(VpnState.CONNECTING, profileName = "b").shownServer(profiles)?.id)
    }

    @Test
    fun aRunningServerARefreshRemovedIsNotReplacedByTheSelection() {
        val status = VpnStatus(VpnState.CONNECTED, "gone", "Германия")
        assertNull(status.shownServer(profiles))
        assertEquals("Германия", status.profileName)
    }
}
