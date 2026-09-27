package com.klausms.vpn.ui

import com.klausms.vpn.core.ParsedProfile
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelLogicTest {
    private fun server(id: String) = StoredProfile(id = id, name = id, outbounds = JsonArray(emptyList()))

    private fun parsed(link: String?, tag: String = link ?: "") =
        ParsedProfile(name = tag, link = link, outbounds = JsonArray(listOf(JsonPrimitive(tag))))

    @Test
    fun selectingAServerThatIsGoneKeepsTheSelection() {
        val state = ProfilesState(profiles = listOf(server("a"), server("b")), selectedId = "a")
        assertEquals("b", state.withSelected("b").selectedId)
        // The VPN process replaced the list: the tapped row's id no longer exists.
        assertSame(state, state.withSelected("old"))
        assertSame(state, state.withSelected("a"))
    }

    @Test
    fun addingKeepsAWorkingSelectionAndRepairsABrokenOne() {
        val added = listOf(server("n1"), server("n2"))
        val working = ProfilesState(profiles = listOf(server("a")), selectedId = "a")
        assertEquals("a", working.withAdded(added).selectedId)
        assertEquals(3, working.withAdded(added).profiles.size)

        val dangling = ProfilesState(profiles = listOf(server("a")), selectedId = "gone")
        assertEquals("n1", dangling.withAdded(added).selectedId)

        val empty = ProfilesState()
        assertEquals("n1", empty.withAdded(added).selectedId)
        assertSame(working, working.withAdded(emptyList()))
    }

    @Test
    fun aRepeatedKeyIsAddedOnce() {
        val ready = listOf(parsed("vless://a"), parsed("vless://b"), parsed("vless://a"), parsed("vless://saved"))
        val fresh = freshKeys(ready, saved = setOf("vless://saved"))
        assertEquals(listOf("vless://a", "vless://b"), fresh.map { it.link })
    }

    @Test
    fun keysWithoutALinkAreTheSameWhenTheirOutboundsAre() {
        // A pasted JSON body has no share links.
        val ready = listOf(parsed(null, "x"), parsed(null, "x"), parsed(null, "y"))
        assertEquals(2, freshKeys(ready, emptySet()).size)
    }

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
