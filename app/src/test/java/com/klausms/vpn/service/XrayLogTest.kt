package com.klausms.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class XrayLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun lines(from: Int, to: Int) = (from..to).joinToString("") { "line %04d\n".format(it) }

    @Test
    fun aSmallLogStaysAsItIs() {
        val log = File(tmp.root, "xray.log").apply { writeText(lines(1, 10)) }
        assertFalse(XrayLog.trim(log, maxBytes = 1000, keepBytes = 100))
        assertEquals(lines(1, 10), log.readText())
        assertFalse(File(tmp.root, "xray.log.1").exists())
        // No log yet: nothing to do, nothing thrown.
        assertFalse(XrayLog.trim(File(tmp.root, "none.log")))
    }

    @Test
    fun aBigLogKeepsItsEndInTheOldFileAndStartsEmpty() {
        // 200 lines of 10 bytes.
        val log = File(tmp.root, "xray.log").apply { writeText(lines(1, 200)) }
        File(tmp.root, "xray.log.1").writeText("older\n")
        assertTrue(XrayLog.trim(log, maxBytes = 1000, keepBytes = 105))
        assertEquals(0L, log.length())
        // The last 105 bytes start mid-line: only whole lines are kept.
        assertEquals(lines(191, 200), File(tmp.root, "xray.log.1").readText())
        assertFalse(File(tmp.root, "xray.log.1.tmp").exists())
    }

    @Test
    fun theCoreKeepsWritingAtTheStartAfterACut() {
        val log = File(tmp.root, "xray.log").apply { writeText(lines(1, 200)) }
        // The core holds the file open for appending, as Xray does.
        FileOutputStream(log, true).use { core ->
            assertTrue(XrayLog.trim(log, maxBytes = 1000, keepBytes = 100))
            core.write("after\n".toByteArray())
        }
        assertEquals("after\n", log.readText())
    }

    @Test
    fun anOversizedOldFileIsCutToo() {
        val log = File(tmp.root, "xray.log").apply { writeText("small\n") }
        val old = File(tmp.root, "xray.log.1").apply { writeText(lines(1, 300)) }
        assertFalse(XrayLog.trim(log, maxBytes = 1000, keepBytes = 100))
        assertEquals(lines(291, 300), old.readText())
        assertEquals("small\n", log.readText())
    }
}
