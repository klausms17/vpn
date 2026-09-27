package com.klausms.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoFilesTest {
    @Test
    fun stampKeepsVersionAndCategories() {
        val stamp = GeoFiles.Stamp(1_790_000_000, "geoip=ru,private;geosite=category-ru")
        assertEquals(stamp, GeoFiles.Stamp.parse(stamp.format()))
    }

    @Test
    fun oldStampsHaveNoCategories() {
        // Written before the categories were recorded, and the APK's copy.
        assertEquals(GeoFiles.Stamp(1_790_000_000, null), GeoFiles.Stamp.parse("1790000000\n"))
        assertEquals(GeoFiles.Stamp(1_790_000_000, null), GeoFiles.Stamp.parse(GeoFiles.Stamp(1_790_000_000, null).format()))
    }

    @Test
    fun brokenStampCountsAsNone() {
        assertEquals(GeoFiles.Stamp(0, null), GeoFiles.Stamp.parse(""))
        assertEquals(0L, GeoFiles.Stamp.parse("garbage\ngeoip=ru").version)
    }
}
