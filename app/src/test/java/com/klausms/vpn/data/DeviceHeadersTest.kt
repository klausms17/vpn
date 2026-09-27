package com.klausms.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHeadersTest {
    @Test
    fun hwidRecipeNeverChanges() {
        // SHA-256("klausvpn-hwid-v1|" + ANDROID_ID), first 32 hex digits.
        // A different value here means every phone becomes a new device in the panel.
        assertEquals("1012707cdd34d59dbc64b03534a44dc9", DeviceHeaders.hashId("0123456789abcdef"))
    }

    @Test
    fun hwidFitsThePanelsRule() {
        // Remnawave ignores ids outside /^[a-zA-Z0-9=-]{10,64}$/.
        val rule = Regex("^[a-zA-Z0-9=-]{10,64}$")
        for (id in listOf("0123456789abcdef", "ffffffffffffffff", "1")) {
            val hwid = DeviceHeaders.hashId(id)
            assertEquals(32, hwid.length)
            assertTrue(hwid, rule.matches(hwid))
            assertEquals(hwid.lowercase(), hwid)
        }
    }

    @Test
    fun modelIsPrintableAsciiWithoutRepeatedBrand() {
        assertEquals("Google Pixel 8", DeviceHeaders.model("Google", "Pixel 8"))
        assertEquals("Xiaomi 13T Pro", DeviceHeaders.model("Xiaomi", "Xiaomi 13T Pro"))
        assertEquals("samsung SM-S911B", DeviceHeaders.model("samsung", "SM-S911B"))
        assertEquals("OnePlus", DeviceHeaders.model("OnePlus", ""))
        // Control characters and non-ASCII are dropped, spaces folded.
        assertEquals("HONOR X8 Lite", DeviceHeaders.model("HONOR", "X8\u0000  Лайт Lite\n"))
        assertEquals(64, DeviceHeaders.model("Brand", "M".repeat(100)).length)
    }

    @Test
    fun printableTrimsAndCuts() {
        assertEquals("14", DeviceHeaders.printable(" 14 ", 32))
        assertEquals("abc", DeviceHeaders.printable("abc def", 4))
        assertEquals("", DeviceHeaders.printable("\t\u0007", 32))
    }
}
