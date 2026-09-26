package com.klausms.vpn.service

import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FailoverTest {
    private val minute = 60_000L

    private fun outbounds(address: String, port: Int, uuid: String) = buildJsonArray {
        add(
            buildJsonObject {
                put("protocol", "vless")
                put("tag", "proxy")
                putJsonObject("settings") {
                    put("address", address)
                    put("port", port)
                    put("id", uuid)
                }
            },
        )
    }

    private fun server(id: String, address: String, sub: String? = null, port: Int = 443, uuid: String = "u-$id") =
        StoredProfile(id = id, name = "Сервер $id", address = address, port = port, outbounds = outbounds(address, port, uuid), subscriptionId = sub)

    private fun sub(id: String) = Subscription(id = id, name = id, url = "https://$id.example.com/sub")

    private fun ids(list: List<StoredProfile>) = list.map { it.id }

    // ---------------------------------------------------------- candidates

    @Test
    fun sameSubscriptionFirstOtherAddressesBeforeItsOwn() {
        val failed = server("a", "nl.example.com", "s1")
        val state = ProfilesState(
            profiles = listOf(
                server("own", "own.example.com"),
                server("x", "de.example.com", "s2"),
                failed,
                // Same address, another port or protocol: an address block takes it down too.
                server("a2", "NL.example.com", "s1", port = 8443),
                server("b", "fi.example.com", "s1"),
            ),
            subscriptions = listOf(sub("s1"), sub("s2")),
            selectedId = "a",
        )
        assertEquals(listOf("b", "a2", "own", "x"), ids(Failover.pickCandidates(state, failed)))
    }

    @Test
    fun ownKeyTriesOtherOwnKeysThenSubscriptionsInListOrder() {
        val failed = server("o1", "one.example.com")
        val state = ProfilesState(
            profiles = listOf(
                server("s1a", "a.example.com", "s1"),
                failed,
                server("gone", "g.example.com", "deleted-sub"),
                server("s2a", "b.example.com", "s2"),
                server("o2", "two.example.com"),
            ),
            // s2 is listed before s1: its servers come first.
            subscriptions = listOf(sub("s2"), sub("s1")),
        )
        assertEquals(listOf("o2", "s2a", "s1a", "gone"), ids(Failover.pickCandidates(state, failed)))
    }

    @Test
    fun skipsRecentlyFailedIdenticalTriedDuplicatesAndPlaceholders() {
        val failed = server("a", "nl.example.com", "s1", uuid = "same")
        val tried = server("t", "t.example.com", "s1")
        val state = ProfilesState(
            profiles = listOf(
                failed,
                // Another entry with exactly the failed server's settings.
                failed.copy(id = "copy", name = "Копия"),
                server("recent", "r.example.com", "s1"),
                tried,
                server("b", "b.example.com", "s1", uuid = "dup"),
                server("b-again", "b.example.com", "s2", uuid = "dup"),
                server("expired", "0.0.0.0", "s1", port = 1),
                server("zero", "z.example.com", "s1", uuid = "00000000-0000-0000-0000-000000000000"),
                StoredProfile(id = "empty", name = "Пусто", address = "e.example.com", outbounds = buildJsonArray { }, subscriptionId = "s1"),
                server("c", "c.example.com", "s2"),
            ),
            subscriptions = listOf(sub("s1"), sub("s2")),
        )
        val picked = Failover.pickCandidates(state, failed, exclude = setOf("recent"), tried = listOf(tried.outbounds))
        assertEquals(listOf("b", "c"), ids(picked))
    }

    @Test
    fun atMostEight() {
        val failed = server("f", "f.example.com", "s1")
        val others = (1..20).map { server("s$it", "h$it.example.com", "s1") }
        val state = ProfilesState(listOf(failed) + others, listOf(sub("s1")))
        val picked = Failover.pickCandidates(state, failed)
        assertEquals(Failover.MAX_CANDIDATES, picked.size)
        assertEquals((1..8).map { "s$it" }, ids(picked))
        assertEquals(20, Failover.pickCandidates(state, failed, limit = Int.MAX_VALUE).size)
    }

    @Test
    fun whitelistedHostsGoFirstOnMobileData() {
        val failed = server("f", "f.example.com", "s1")
        val state = ProfilesState(
            listOf(failed, server("a", "a.example.com", "s1"), server("b", "b.example.com", "s1"), server("relay", "Relay.example.ru")),
            listOf(sub("s1")),
        )
        assertEquals(listOf("relay", "a", "b"), ids(Failover.pickCandidates(state, failed, preferred = setOf("relay.example.ru"))))
        assertEquals(listOf("a", "b", "relay"), ids(Failover.pickCandidates(state, failed)))
    }

    @Test
    fun aRefreshedFailedServerIsTriedAgainButNothingProbedTwice() {
        val failed = server("a", "nl.example.com", "s1", uuid = "old")
        val b = server("b", "b.example.com", "s1")
        // The panel handed out new keys for the same server, and a new one.
        val after = ProfilesState(
            listOf(server("a", "nl.example.com", "s1", uuid = "new"), b, server("c", "c.example.com", "s1")),
            listOf(sub("s1")),
            selectedId = "a",
        )
        assertEquals(listOf("c", "a"), ids(Failover.pickCandidates(after, failed, tried = listOf(b.outbounds))))
    }

    // -------------------------------------------------------------- winner

    @Test
    fun fastestAnsweringServerWins() {
        val list = listOf(server("a", "a"), server("b", "b"), server("c", "c"))
        val (best, ms) = Failover.fastest(list, listOf(-1L, 420L, 180L))!!
        assertSame(list[2], best)
        assertEquals(180L, ms)
        assertNull(Failover.fastest(list, listOf(-1L, -1L, -1L)))
        // A short answer from the core counts as failures for the rest.
        assertEquals("a", Failover.fastest(list, listOf(90L))!!.first.id)
        assertNull(Failover.fastest(emptyList(), emptyList()))
    }

    @Test
    fun theUsersChoiceWinsOverTheSwitch() {
        val a = server("a", "a.example.com", "s1")
        val b = server("b", "b.example.com", "s1")
        val c = server("c", "c.example.com", "s1")
        val state = ProfilesState(listOf(a, b, c), listOf(sub("s1")), selectedId = "a")
        assertEquals("b", Failover.selectInstead(state, failedId = "a", winnerId = "b").selectedId)
        // Picked "c" while the probe ran.
        val picked = state.copy(selectedId = "c")
        assertSame(picked, Failover.selectInstead(picked, failedId = "a", winnerId = "b"))
        assertFalse(Failover.selectionFollowsFailed(picked, "a"))
        // The winner was deleted meanwhile.
        val noWinner = state.copy(profiles = listOf(a, c))
        assertSame(noWinner, Failover.selectInstead(noWinner, failedId = "a", winnerId = "b"))
        // A refresh dropped the failed server and moved the selection to its first one
        // before the switch was decided: the switch saw "c" and replaces it.
        val refreshed = state.copy(profiles = listOf(b, c), selectedId = "c")
        assertTrue(Failover.selectionFollowsFailed(refreshed, "a"))
        assertEquals("b", Failover.selectInstead(refreshed, failedId = "a", winnerId = "b", expected = "c").selectedId)
        // The user tapped "d" while the winner was starting: their pick stays,
        // even though the failed server is gone.
        val d = server("d", "d.example.com", "s1")
        val tapped = refreshed.copy(profiles = listOf(b, c, d), selectedId = "d")
        assertSame(tapped, Failover.selectInstead(tapped, failedId = "a", winnerId = "b", expected = "c"))
    }

    // -------------------------------------------------------------- budget

    @Test
    fun atMostThreeSwitchesPerHalfHour() {
        var saved = ""
        var now = 10 * minute
        repeat(3) {
            saved = Failover.countSwitch(saved, now) ?: error("switch ${it + 1} refused")
            now += minute
        }
        assertEquals(3, Failover.recentSwitches(saved, now).size)
        assertNull(Failover.countSwitch(saved, now))
        // The first switch leaves the window 30 minutes after it happened.
        assertNull(Failover.countSwitch(saved, 10 * minute + Failover.SWITCH_WINDOW_MS - 1))
        val later = 10 * minute + Failover.SWITCH_WINDOW_MS
        val next = Failover.countSwitch(saved, later)!!
        assertEquals(listOf(11 * minute, 12 * minute, later), Failover.recentSwitches(next, later))
    }

    @Test
    fun budgetSurvivesJunkAndStartsOverAfterAReboot() {
        assertEquals(emptyList<Long>(), Failover.recentSwitches("", 5 * minute))
        assertEquals(listOf(4 * minute, 7L), Failover.recentSwitches("x,,${4 * minute}, 7", 5 * minute))
        // Saved before a reboot: time since boot is smaller now.
        val beforeReboot = listOf(50 * minute, 51 * minute, 52 * minute).joinToString(",")
        assertEquals(emptyList<Long>(), Failover.recentSwitches(beforeReboot, 2 * minute))
        assertEquals("${2 * minute}", Failover.countSwitch(beforeReboot, 2 * minute))
    }

    @Test
    fun subscriptionRefreshAtMostEveryTenMinutes() {
        val now = 1_800_000_000_000L
        val s = sub("s1")
        assertTrue(Failover.refreshDue(s.copy(lastAttemptAt = 0), now))
        assertFalse(Failover.refreshDue(s.copy(lastAttemptAt = now - 9 * minute), now))
        assertTrue(Failover.refreshDue(s.copy(lastAttemptAt = now - 10 * minute), now))
        // A clock set back counts as due.
        assertTrue(Failover.refreshDue(s.copy(lastAttemptAt = now + minute), now))
    }

    @Test
    fun noticesThatAFailedCheckClears() {
        assertTrue(Failover.NOTICE_SEARCHING in Failover.FAILURE_NOTICES)
        assertTrue(Failover.NOTICE_NONE_ANSWER in Failover.FAILURE_NOTICES)
        val switched = Failover.switchedNotice("Финляндия", "Нидерланды")
        assertEquals("Переключились на «Финляндия»: «Нидерланды» не отвечал", switched)
        assertFalse(switched in Failover.FAILURE_NOTICES)
    }
}
