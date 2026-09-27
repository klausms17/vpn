package com.klausms.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    private val json = """
        {"versionCode": 27, "versionName": "1.0.27",
         "apk": "https://sub.example.com/app/KirovVPN-1.0.27.apk", "sha256": "ab12"}
    """.trimIndent()

    @Test
    fun readsThePanelsVersionJson() {
        assertEquals(AppUpdate(27, "1.0.27", "https://sub.example.com/app/KirovVPN-1.0.27.apk"), AppUpdate.parse(json))
    }

    @Test
    fun newerOnlyWhenAboveTheInstalledBuild() {
        val latest = AppUpdate.parse(json)
        assertEquals(latest, AppUpdate.offer(latest, installed = 26, dismissed = 0))
        // The same build, or an older one on the panel: nothing to offer.
        assertNull(AppUpdate.offer(latest, installed = 27, dismissed = 0))
        assertNull(AppUpdate.offer(latest, installed = 30, dismissed = 0))
        assertNull(AppUpdate.offer(null, installed = 1, dismissed = 0))
    }

    @Test
    fun laterHidesItUntilANewerBuild() {
        val latest = AppUpdate.parse(json)!!
        assertNull(AppUpdate.offer(latest, installed = 20, dismissed = 27))
        assertEquals(28, AppUpdate.offer(latest.copy(versionCode = 28), installed = 20, dismissed = 27)?.versionCode)
    }

    @Test
    fun malformedAnswersAreIgnored() {
        for (text in listOf(
            null,
            "",
            "<html>502 Bad Gateway</html>",
            "{\"versionCode\": 27",
            "[1, 2]",
            "\"1.0.27\"",
            """{"versionName": "1.0.27", "apk": "https://sub.example.com/a.apk"}""",
            """{"versionCode": "new", "apk": "https://sub.example.com/a.apk"}""",
            """{"versionCode": 27.5, "apk": "https://sub.example.com/a.apk"}""",
            """{"versionCode": 0, "apk": "https://sub.example.com/a.apk"}""",
            """{"versionCode": -3, "apk": "https://sub.example.com/a.apk"}""",
            """{"versionCode": 27}""",
            """{"versionCode": 27, "apk": 5}""",
            """{"versionCode": 27, "apk": null}""",
            "{\"versionCode\": 27, \"apk\": \"https://sub.example.com/a.apk\", \"pad\": \"${"x".repeat(20_000)}\"}",
        )) {
            assertNull(text?.take(60), AppUpdate.parse(text))
        }
    }

    @Test
    fun onlyHttpsApkLinks() {
        fun apk(url: String) = AppUpdate.parse("""{"versionCode": 27, "versionName": "1.0.27", "apk": "$url"}""")
        assertNull(apk("http://sub.example.com/app/KirovVPN.apk"))
        assertNull(apk("ftp://sub.example.com/app/KirovVPN.apk"))
        assertNull(apk("file:///sdcard/KirovVPN.apk"))
        assertNull(apk("javascript:alert(1)"))
        assertNull(apk("https://"))
        assertNull(apk("https:///KirovVPN.apk"))
        assertNull(apk("https://user@evil.example.com/KirovVPN.apk"))
        assertNull(apk("https://sub.example.com/app/Kirov VPN.apk"))
        assertEquals("HTTPS://sub.example.com:8443/app/KirovVPN.apk", apk("HTTPS://sub.example.com:8443/app/KirovVPN.apk")?.apkUrl)
    }

    @Test
    fun nameIsShortPrintableOrTheCode() {
        assertEquals("27", AppUpdate.parse("""{"versionCode": 27, "apk": "https://s.example.com/a.apk"}""")?.versionName)
        assertEquals("27", AppUpdate.parse("""{"versionCode": 27, "versionName": " \n", "apk": "https://s.example.com/a.apk"}""")?.versionName)
        assertEquals("1.0.27 beta", AppUpdate.parse("""{"versionCode": 27, "versionName": "1.0.27\u0000 beta", "apk": "https://s.example.com/a.apk"}""")?.versionName)
        assertEquals(32, AppUpdate.parse("""{"versionCode": 27, "versionName": "${"9".repeat(100)}", "apk": "https://s.example.com/a.apk"}""")?.versionName?.length)
        // A number as a string is still the version code.
        assertEquals(27, AppUpdate.parse("""{"versionCode": "27", "apk": "https://s.example.com/a.apk"}""")?.versionCode)
    }

    @Test
    fun savedAnswerReadsBack() {
        val latest = AppUpdate(31, "1.0.31", "https://sub.example.com/app/KirovVPN-1.0.31.apk")
        assertEquals(latest, AppUpdate.parse(latest.toJson()))
    }

    @Test
    fun checkedAtMostTwiceADay() {
        val now = 1_800_000_000_000L
        assertTrue(AppUpdate.checkDue(0, now))
        assertFalse(AppUpdate.checkDue(now - 11 * 3_600_000L, now))
        assertTrue(AppUpdate.checkDue(now - 12 * 3_600_000L, now))
        // The clock was set back.
        assertTrue(AppUpdate.checkDue(now + 3_600_000L, now))
    }
}
