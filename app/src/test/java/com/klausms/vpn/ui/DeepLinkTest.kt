package com.klausms.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    private val sub = "https://sub.example.com/sub/Ab_c-12"

    @Test
    fun addTakesTheRawLink() {
        assertEquals(sub, DeepLink.payload("klausvpn://add/$sub"))
        assertEquals("$sub?token=1&x=2", DeepLink.payload("klausvpn://add/$sub?token=1&x=2"))
        assertEquals(sub, DeepLink.payload("KlausVPN://ADD/$sub"))
    }

    @Test
    fun importIsTheSame() {
        assertEquals(sub, DeepLink.payload("klausvpn://import/$sub"))
        assertEquals(sub, DeepLink.payload("klausvpn://import?url=$sub"))
    }

    @Test
    fun encodedLinkIsDecodedOnce() {
        assertEquals(sub, DeepLink.payload("klausvpn://add/https%3A%2F%2Fsub.example.com%2Fsub%2FAb_c-12"))
        assertEquals(sub, DeepLink.payload("klausvpn://add/https%3a%2f%2fsub.example.com%2fsub%2fAb_c-12"))
        // "%2541" is "%41" after one decoding, not "A".
        assertEquals("https://x.example.com/%41", DeepLink.payload("klausvpn://add/https%3A%2F%2Fx.example.com%2F%2541"))
        assertEquals("https://x.example.com/тест", DeepLink.payload("klausvpn://add/https%3A%2F%2Fx.example.com%2F%D1%82%D0%B5%D1%81%D1%82"))
    }

    @Test
    fun subscriptionNameAfterHashIsDropped() {
        assertEquals(sub, DeepLink.payload("klausvpn://add/$sub#Ivan"))
        assertEquals(sub, DeepLink.payload("klausvpn://add/https%3A%2F%2Fsub.example.com%2Fsub%2FAb_c-12%23Ivan"))
        assertEquals(sub, DeepLink.payload("klausvpn://install-config?url=$sub#Ivan"))
    }

    @Test
    fun keyKeepsItsName() {
        val key = "vless://uuid@host.example.com:443?security=reality&sni=a.com#%F0%9F%87%B3%F0%9F%87%B1%20NL"
        assertEquals(key, DeepLink.payload("klausvpn://add/$key"))
    }

    @Test
    fun installConfigTakesUrlToNameOrEnd() {
        assertEquals(sub, DeepLink.payload("klausvpn://install-config?url=$sub"))
        assertEquals(sub, DeepLink.payload("klausvpn://install-config?url=$sub&name=Ivan"))
        // v2rayNG order: name first.
        assertEquals(sub, DeepLink.payload("klausvpn://install-config?name=Ivan&url=$sub"))
        // An unencoded link with its own query.
        assertEquals("$sub?a=1&b=2", DeepLink.payload("klausvpn://install-config?url=$sub?a=1&b=2&name=Ivan"))
        assertEquals(sub, DeepLink.payload("klausvpn://install-config?url=https%3A%2F%2Fsub.example.com%2Fsub%2FAb_c-12&name=Ivan"))
    }

    @Test
    fun foldedSlashIsRepaired() {
        assertEquals(sub, DeepLink.payload("klausvpn://add/https:/sub.example.com/sub/Ab_c-12"))
    }

    @Test
    fun somethingElseIsIgnored() {
        assertNull(DeepLink.payload(null))
        assertNull(DeepLink.payload(""))
        assertNull(DeepLink.payload("klausvpn://add/"))
        assertNull(DeepLink.payload("klausvpn://add"))
        assertNull(DeepLink.payload("klausvpn://install-config?name=Ivan"))
        assertNull(DeepLink.payload("klausvpn://settings/x"))
        assertNull(DeepLink.payload("https://example.com/add/x"))
        assertNull(DeepLink.payload("klausvpn://add/" + "a".repeat(70_000)))
    }

    @Test
    fun hostOfASubscriptionLink() {
        assertEquals("sub.example.com", DeepLink.urlHost(sub))
        assertEquals("sub.example.com", DeepLink.urlHost("https://user:pw@sub.example.com:8443/x?y#z"))
        assertEquals("[2001:db8::1]", DeepLink.urlHost("http://[2001:db8::1]:8080/sub"))
        assertEquals("evil.example", DeepLink.urlHost("https://evil.example/sub x"))
        assertNull(DeepLink.urlHost("vless://uuid@host.example.com:443"))
    }

    @Test
    fun onlyLinksAndKeysReachTheDialog() {
        assertNull(DeepLink.payload("klausvpn://add/hello"))
        assertNull(DeepLink.payload("klausvpn://add/https%3A%2F%2Fx.example%0A%0Dy"))
    }

    /** The dialog names the host that import() will really fetch, however the link is dressed up. */
    @Test
    fun dialogHostMatchesWhatIsImported() {
        for (text in listOf(".https://evil.example/sub", "https://evil.example/sub x", "Моя подписка: https://evil.example/sub")) {
            val url = ImportText.subscriptionUrl(text)
            assertEquals(text, "evil.example", url?.let(DeepLink::urlHost))
        }
        assertNull(ImportText.subscriptionUrl("$sub\n$sub"))
        assertNull(ImportText.subscriptionUrl("vless://uuid@host.example.com:443"))
        assertNull(ImportText.subscriptionUrl("[{\"outbounds\":[]}]"))
    }

    @Test
    fun percentDecodeLeavesBrokenEscapes() {
        assertEquals("a%zz%4", DeepLink.percentDecode("a%zz%4"))
        assertEquals("a+b c", DeepLink.percentDecode("a+b%20c"))
    }
}
