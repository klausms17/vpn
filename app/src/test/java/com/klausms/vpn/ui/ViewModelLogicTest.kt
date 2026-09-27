package com.klausms.vpn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelLogicTest {
    @Test
    fun aFailedUpdateCheckIsRetriedAfterAFewMinutes() {
        val t = 1_800_000_000_000L
        assertTrue(updateRetryDue(0L, t))
        assertEquals(false, updateRetryDue(t, t + 60_000))
        assertTrue(updateRetryDue(t, t + UPDATE_RETRY_MS))
        // A clock set back does not block checks for long.
        assertTrue(updateRetryDue(t, t - 1))
    }

    @Test
    fun aSecondGeoUpdateWaitsForTheFirstToEnd() {
        val geo = OneAtATime()
        assertTrue(geo.tryStart())
        assertTrue(geo.running.value)
        // «Обновить списки» again, maybe from a reopened screen.
        assertFalse(geo.tryStart())
        geo.end()
        assertFalse(geo.running.value)
        assertTrue(geo.tryStart())
    }

    @Test
    fun busyPillShowsTheNewestRunningOperation() {
        val busy = BusyTexts()
        assertNull(busy.text.value)
        val geo = busy.start("Обновление баз…")
        val add = busy.start("Добавление…")
        assertEquals("Добавление…", busy.text.value)
        // The import ends first: the pill goes back to the running update.
        add.end()
        assertEquals("Обновление баз…", busy.text.value)
        geo.caption = "Загрузка geoip.dat…"
        assertEquals("Загрузка geoip.dat…", busy.text.value)
        geo.end()
        assertNull(busy.text.value)
        // Ending twice is harmless.
        geo.end()
        assertNull(busy.text.value)
    }

    @Test
    fun progressOfAnOlderOperationDoesNotCoverANewerOne() {
        val busy = BusyTexts()
        val geo = busy.start("Обновление баз…")
        busy.start("Проверка соединения…")
        geo.caption = "Проверка geosite.dat…"
        assertEquals("Проверка соединения…", busy.text.value)
    }
}
