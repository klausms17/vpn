package com.klausms.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockReportTest {
    @Test
    fun shortUuidIsTheLastPathSegment() {
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com/aB3_x-9"))
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com/aB3_x-9/"))
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com/api/sub/aB3_x-9"))
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com:8443/aB3_x-9?format=raw"))
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com/aB3_x-9/?x=1#name"))
        assertEquals("aB3_x-9", BlockReport.shortUuid("  https://sub.example.com//aB3_x-9//  "))
        // Remnawave's other formats: the id is before the suffix.
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com/aB3_x-9/json"))
        assertEquals("aB3_x-9", BlockReport.shortUuid("https://sub.example.com/aB3_x-9/v2ray-json/"))
        assertEquals("json", BlockReport.shortUuid("https://sub.example.com/json"))
        // A UUID-style id.
        assertEquals("0b7e1f7c-5a9d-4d6e-9d3f-1f2e3d4c5b6a", BlockReport.shortUuid("https://sub.example.com/0b7e1f7c-5a9d-4d6e-9d3f-1f2e3d4c5b6a"))
    }

    @Test
    fun noShortUuidWithoutAPath() {
        assertNull(BlockReport.shortUuid("https://sub.example.com"))
        assertNull(BlockReport.shortUuid("https://sub.example.com/"))
        assertNull(BlockReport.shortUuid("https://sub.example.com?id=abc/def"))
        assertNull(BlockReport.shortUuid("https://sub.example.com#abc/def"))
        assertNull(BlockReport.shortUuid(""))
        assertNull(BlockReport.shortUuid("not a url"))
        // Nothing that would need guessing: odd characters mean no report.
        assertNull(BlockReport.shortUuid("https://sub.example.com/a%20b"))
        assertNull(BlockReport.shortUuid("https://sub.example.com/абв"))
        assertNull(BlockReport.shortUuid("https://sub.example.com/" + "a".repeat(129)))
    }

    @Test
    fun everyValueIsPercentEncoded() {
        val url = BlockReport.url(
            base = "https://sub.example.com/klaus/report",
            shortUuid = "aB3_x-9",
            host = "2001:db8::1",
            port = 443,
            protocol = "vless",
            network = BlockReport.MOBILE,
            operator = "МТС RUS & Co=1",
            version = "1.0.27+dev",
        )
        assertEquals(
            "https://sub.example.com/klaus/report?s=aB3_x-9&h=2001%3Adb8%3A%3A1&p=443&k=vless&n=mobile" +
                "&o=%D0%9C%D0%A2%D0%A1%20RUS%20%26%20Co%3D1&v=1.0.27%2Bdev",
            url,
        )
    }

    @Test
    fun queryAndFragmentOfTheBase() {
        fun url(base: String) = BlockReport.url(base, "id", "h.example.com", 8443, "trojan", BlockReport.WIFI, "", "1.0")
        val q = "s=id&h=h.example.com&p=8443&k=trojan&n=wifi&o=&v=1.0"
        assertEquals("https://p.example.com/r?t=1&$q", url("https://p.example.com/r?t=1"))
        assertEquals("https://p.example.com/r?$q", url("https://p.example.com/r?"))
        assertEquals("https://p.example.com/r?$q", url("https://p.example.com/r#top"))
    }

    @Test
    fun encodeKeepsOnlyUnreservedCharacters() {
        assertEquals("aZ09-_.~", BlockReport.encode("aZ09-_.~"))
        assertEquals("%20%2B%2F%3F%23%26%3D%25", BlockReport.encode(" +/?#&=%"))
        assertEquals("%D1%91", BlockReport.encode("ё"))
        assertEquals("%F0%9F%87%B3%F0%9F%87%B1", BlockReport.encode("🇳🇱"))
    }

    @Test
    fun operatorIsPrintableLatinOrCyrillic() {
        assertEquals("MTS RUS", BlockReport.operator("MTS RUS"))
        assertEquals("Билайн", BlockReport.operator(" Билайн\n"))
        assertEquals("Mega Fon", BlockReport.operator("Mega\u0000Fon"))
        // Control characters and other scripts become spaces, folded.
        assertEquals("Tele2 RU", BlockReport.operator("Tele2\t\u0007RU"))
        assertEquals("Yota", BlockReport.operator("Yota 📶"))
        assertEquals("", BlockReport.operator(null))
        assertEquals("", BlockReport.operator("\u200b\u202e"))
        val long = BlockReport.operator("Оператор ".repeat(10))
        assertTrue(long, long.length <= 40)
        assertEquals(long.trim(), long)
    }

    @Test
    fun networkTypes() {
        assertEquals("mobile", BlockReport.networkKind(cellular = true, wifi = false, ethernet = false))
        assertEquals("wifi", BlockReport.networkKind(cellular = false, wifi = true, ethernet = false))
        assertEquals("wifi", BlockReport.networkKind(cellular = false, wifi = false, ethernet = true))
        assertEquals("other", BlockReport.networkKind(cellular = false, wifi = false, ethernet = false))
    }

    @Test
    fun oneReportPerServerPerHalfHour() {
        val t = ReportThrottle(BlockReport.THROTTLE_MS)
        val now = 5_000_000L
        val key = BlockReport.serverKey("NL.example.com ", 443)
        assertEquals("nl.example.com:443", key)
        assertTrue(t.claim(key, now))
        assertFalse(t.claim(key, now + 60_000))
        assertFalse(t.claim(BlockReport.serverKey("nl.example.com", 443), now + 29 * 60_000L))
        // Another server, or the same host on another port, is its own.
        assertTrue(t.claim(BlockReport.serverKey("de.example.com", 443), now + 60_000))
        assertTrue(t.claim(BlockReport.serverKey("nl.example.com", 8443), now + 60_000))
        assertTrue(t.claim(key, now + BlockReport.THROTTLE_MS))
    }
}
