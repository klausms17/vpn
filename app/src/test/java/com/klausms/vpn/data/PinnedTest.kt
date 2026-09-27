package com.klausms.vpn.data

import com.klausms.vpn.core.ParsedProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinnedTest {
    private val saved = StoredProfile(
        id = "h1",
        name = "🇫🇮 5 дней",
        protocol = "hysteria2",
        address = "H.example.com",
        port = 443,
        link = "hysteria2://pw@h.example.com:443?insecure=1#%F0%9F%87%AB%F0%9F%87%AE%205",
        outbounds = JsonArray(listOf(JsonPrimitive("pinned"))),
        subscriptionId = "s1",
    )
    private val pinned = Pinned.of(listOf(saved))

    private fun parsed(link: String) = ParsedProfile(
        name = "x",
        protocol = "hysteria2",
        address = "h.example.com",
        port = 443,
        link = link,
        outbounds = JsonArray(emptyList()),
        needsCertPin = true,
    )

    @Test
    fun renamedServerReusesItsPin() {
        // The panel changed only the name ("4 days left"): same settings.
        val p = parsed("hysteria2://pw@h.example.com:443?insecure=1#%F0%9F%87%AB%F0%9F%87%AE%204")
        assertEquals(saved.outbounds, pinned.sameLink(p))
        assertEquals(saved.outbounds, pinned.sameServer(p))
    }

    @Test
    fun changedSettingsAreOnlyAFallback() {
        // Another password: not reused, but kept if the server cannot be reached now.
        val p = parsed("hysteria2://other@h.example.com:443?insecure=1#x")
        assertNull(pinned.sameLink(p))
        assertEquals(saved.outbounds, pinned.sameServer(p))
    }

    @Test
    fun otherServersAndNoneGetNothing() {
        assertNull(pinned.sameServer(parsed("hysteria2://pw@other.example.com:443?insecure=1#x").copy(address = "other.example.com")))
        assertNull(Pinned.NONE.sameServer(parsed(saved.link!!)))
    }
}
