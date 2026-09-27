package com.klausms.vpn.ui

import com.klausms.vpn.util.AppLog
import com.klausms.vpn.util.PhoneSettings
import com.klausms.vpn.util.PhoneSettings.Oem
import com.klausms.vpn.util.ProcessExits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The pure parts of the phone checks behind Settings → «Надёжность» and the log. */
class PhoneDiagnosticsTest {
    @Test
    fun brandsWithTheirOwnAutostartSwitch() {
        assertEquals(Oem.XIAOMI, PhoneSettings.oem("Xiaomi", "Redmi"))
        assertEquals(Oem.XIAOMI, PhoneSettings.oem("unknown", "POCO"))
        assertEquals(Oem.HUAWEI, PhoneSettings.oem("HUAWEI", "HUAWEI"))
        assertEquals(Oem.HONOR, PhoneSettings.oem("HONOR", "HONOR"))
        assertEquals(Oem.OPPO, PhoneSettings.oem("realme", "realme"))
        assertEquals(Oem.OPPO, PhoneSettings.oem("OnePlus", "OnePlus"))
        assertEquals(Oem.VIVO, PhoneSettings.oem("vivo", "iQOO"))
    }

    @Test
    fun stockLikePhonesNeedNoExtraStep() {
        assertNull(PhoneSettings.oem("samsung", "samsung"))
        assertNull(PhoneSettings.oem("Google", "google"))
        assertNull(PhoneSettings.oem(null, null))
        assertNull(PhoneSettings.oem("", ""))
    }

    @Test
    fun everyBrandHasAScreenToTryAndAHint() {
        for (oem in Oem.entries) {
            assertTrue(oem.name, oem.screens.isNotEmpty())
            assertTrue(oem.name, oem.hint.isNotBlank())
            for ((pkg, cls) in oem.screens) assertTrue("$pkg/$cls", cls.startsWith("com."))
        }
        // Honor phones on MagicOS first, then those still on EMUI.
        assertEquals("com.hihonor.systemmanager", Oem.HONOR.screens.first().first)
    }

    @Test
    fun standbyBucketsHaveNames() {
        assertEquals("active", PhoneSettings.bucketName(10))
        assertEquals("restricted", PhoneSettings.bucketName(45))
        assertEquals("7", PhoneSettings.bucketName(7))
    }

    @Test
    fun aForceStopWhileTheVpnWasOnIsLogged() {
        // ApplicationExitInfo.REASON_USER_REQUESTED: how Android files a
        // force-stop by a cleaner or a firmware power manager.
        val forceStop = 10
        val crash = 4
        val other = 13 // REASON_OTHER, e.g. an idle process cleared away
        // RunningAppProcessInfo importance: running the tunnel (a foreground
        // service), or idle in the cache.
        val tunnel = 125
        val cached = 400
        assertTrue(ProcessExits.worthLogging(forceStop, tunnelWanted = true, importance = tunnel))
        assertTrue(ProcessExits.worthLogging(other, tunnelWanted = true, importance = tunnel))
        assertFalse(ProcessExits.worthLogging(forceStop, tunnelWanted = false, importance = tunnel))
        assertFalse(ProcessExits.worthLogging(other, tunnelWanted = false, importance = cached))
        assertTrue(ProcessExits.worthLogging(crash, tunnelWanted = false, importance = cached))
    }

    @Test
    fun anIdleProcessClearedAwayIsNotNewsEvenWithTheVpnMeantToBeOn() {
        // The tunnel did not come back (e.g. the firmware blocked the restart);
        // the widget's half-hourly redraw starts the process, which then idles
        // in the cache until it is cleared: once every 30 minutes.
        assertFalse(ProcessExits.worthLogging(13, tunnelWanted = true, importance = 400))
        // A crash is logged all the same.
        assertTrue(ProcessExits.worthLogging(5, tunnelWanted = true, importance = 400))
        // Importance unknown (IMPORTANCE_GONE): better one line too many.
        assertTrue(ProcessExits.worthLogging(10, tunnelWanted = true, importance = 1000))
    }

    @Test
    fun exitReasonsHaveNames() {
        assertTrue(ProcessExits.reasonName(10).startsWith("force-stopped"))
        assertEquals("reason 99", ProcessExits.reasonName(99))
    }

    @Test
    fun logTailReadsOnlyTheEndOfABigFile() {
        val f = File.createTempFile("xray", ".log")
        try {
            f.bufferedWriter().use { w -> for (i in 1..20_000) w.write("line $i of a long outage\n") }
            assertTrue(f.length() > 64 * 1024)
            val tail = AppLog.tail(f, maxLines = 300, maxBytes = 64 * 1024)
            assertEquals(300, tail.size)
            assertEquals("line 20000 of a long outage", tail.last())
            assertEquals("line 19701 of a long outage", tail.first())

            // Fewer lines in the window than asked: the cut first line is dropped.
            val small = AppLog.tail(f, maxLines = 10_000, maxBytes = 100)
            assertTrue(small.isNotEmpty())
            assertTrue(small.all { it.startsWith("line ") && it.endsWith(" of a long outage") })
            assertEquals("line 20000 of a long outage", small.last())
        } finally {
            f.delete()
        }
    }

    @Test
    fun logTailOfASmallOrMissingFile() {
        val f = File.createTempFile("app", ".log")
        try {
            f.writeText("one\ntwo\n")
            assertEquals(listOf("one", "two"), AppLog.tail(f, 300))
            f.writeText("")
            assertEquals(emptyList<String>(), AppLog.tail(f, 300))
        } finally {
            f.delete()
        }
        assertEquals(emptyList<String>(), AppLog.tail(File(f.parentFile, "missing-${System.nanoTime()}.log"), 300))
    }
}
