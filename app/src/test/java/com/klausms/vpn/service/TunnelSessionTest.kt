package com.klausms.vpn.service

import com.klausms.vpn.data.StoredProfile
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertFalse
import org.junit.Test

class TunnelSessionTest {
    private val secret = "b831381d-6324-4d53-ad4f-8cda48b30811"
    private val profile = StoredProfile(
        id = "p1", name = "Сервер", address = "server.example.com", link = "vless://$secret@server.example.com:443",
        outbounds = JsonArray(emptyList()),
    )
    private val session = TunnelSession(
        profile, config = """{"outbounds":[{"id":"$secret"}]}""", core = FakeCore(), connectedAt = 1_000, lockdownConflict = false,
    )

    @Test
    fun itsTextNeverCarriesTheKeysOrTheServer() {
        val text = session.toString()
        assertFalse(text.contains(secret))
        assertFalse(text.contains("server.example.com"))
    }
}
