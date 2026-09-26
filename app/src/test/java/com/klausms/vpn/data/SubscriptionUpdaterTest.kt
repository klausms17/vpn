package com.klausms.vpn.data

import com.klausms.vpn.core.ParsedProfile
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUpdaterTest {
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60_000L

    private fun outbounds(address: String, sni: String = "", path: String = "", id: String = "u1") = buildJsonArray {
        add(
            buildJsonObject {
                put("protocol", "vless")
                putJsonObject("settings") { put("address", address); put("id", id) }
                putJsonObject("streamSettings") {
                    put("network", if (path.isEmpty()) "raw" else "ws")
                    put("security", "tls")
                    putJsonObject("tlsSettings") { put("serverName", sni) }
                    if (path.isNotEmpty()) putJsonObject("wsSettings") { put("path", path) }
                }
            },
        )
    }

    private fun parsed(name: String, address: String, port: Int = 443, sni: String = "", path: String = "", id: String = "u1") =
        ParsedProfile(
            name = name, protocol = "vless", address = address, port = port,
            network = if (path.isEmpty()) "raw" else "ws", security = "tls",
            link = "vless://$id@$address:$port?sni=$sni&path=$path#$name",
            outbounds = outbounds(address, sni, path, id),
        )

    private fun stored(id: String, p: ParsedProfile, sub: String? = "s1") = p.toStored(sub, id = id, createdAt = 1)

    private val sub = Subscription(id = "s1", name = "Друзья", url = "https://sub.example.com/abc", updatedAt = now - 2 * hour)
    private val own = stored("own", parsed("Мой", "own.example.com"), sub = null)

    private fun ids(): () -> String {
        var n = 0
        return { "new${++n}" }
    }

    private fun fetched(vararg profiles: ParsedProfile, notice: String? = null) =
        SubscriptionUpdater.Fetched(profiles.toList(), notice = notice, userInfo = "upload=0; download=1; total=2; expire=0")

    // ------------------------------------------------------------- merge

    @Test
    fun refreshKeepsIdsAndSelection() {
        val nl = parsed("NL", "nl.example.com")
        val de = parsed("DE", "de.example.com")
        val state = ProfilesState(listOf(own, stored("a", nl), stored("b", de)), listOf(sub), selectedId = "b")
        // Remarks change between refreshes (days left), and with them the
        // links: the servers are still recognised.
        fun renamed(p: ParsedProfile, name: String) = p.copy(name = name, link = p.link!!.substringBefore('#') + "#" + name)
        val next = SubscriptionUpdater.merge(state, "s1", fetched(renamed(nl, "NL 29 дней"), renamed(de, "DE 29 дней")), now, ids())
        assertEquals(listOf("own", "a", "b"), next.profiles.map { it.id })
        assertEquals("b", next.selectedId)
        assertEquals("DE 29 дней", next.selected?.name)
        val s = next.subscriptions.single()
        assertEquals(now, s.updatedAt)
        assertEquals(now, s.lastAttemptAt)
        assertEquals("upload=0; download=1; total=2; expire=0", s.userInfo)
    }

    @Test
    fun newServersGetNewIdsAndGoneOnesLeave() {
        val nl = parsed("NL", "nl.example.com")
        val state = ProfilesState(listOf(own, stored("a", nl), stored("b", parsed("DE", "de.example.com"))), listOf(sub), selectedId = "own")
        val next = SubscriptionUpdater.merge(state, "s1", fetched(nl, parsed("FI", "fi.example.com")), now, ids())
        assertEquals(listOf("own", "a", "new1"), next.profiles.map { it.id })
        assertEquals("own", next.selectedId)
    }

    @Test
    fun selectionMovesWhenTheSelectedServerIsGone() {
        val state = ProfilesState(listOf(own, stored("a", parsed("NL", "nl.example.com"))), listOf(sub), selectedId = "a")
        val next = SubscriptionUpdater.merge(state, "s1", fetched(parsed("FI", "fi.example.com")), now, ids())
        assertEquals("new1", next.selectedId)
    }

    @Test
    fun firstServerIsSelectedWhenNothingWas() {
        val state = ProfilesState(emptyList(), listOf(sub), selectedId = null)
        val next = SubscriptionUpdater.merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids())
        assertEquals("new1", next.selectedId)
    }

    @Test
    fun noticesOnlyKeepTheServers() {
        val a = stored("a", parsed("NL", "nl.example.com"))
        val state = ProfilesState(listOf(own, a), listOf(sub.copy(userInfo = "old")), selectedId = "a")
        val next = SubscriptionUpdater.merge(state, "s1", fetched(notice = "Subscription expired").copy(userInfo = null), now, ids())
        assertEquals(state.profiles, next.profiles)
        assertEquals("a", next.selectedId)
        val s = next.subscriptions.single()
        assertEquals("Subscription expired", s.notice)
        // Only a real list counts as fresh; the attempt is still recorded.
        assertEquals(sub.updatedAt, s.updatedAt)
        assertEquals(now, s.lastAttemptAt)
        assertEquals("old", s.userInfo)
    }

    @Test
    fun deviceLimitKeepsTheServersAndShowsTheAnnounce() {
        val state = ProfilesState(listOf(stored("a", parsed("NL", "nl.example.com"))), listOf(sub.copy(lastError = "HTTP 502 Bad Gateway")), selectedId = "a")
        val refused = SubscriptionUpdater.Fetched(emptyList(), notice = SubscriptionUpdater.HWID_LIMIT, announce = "Напишите мне")
        val next = SubscriptionUpdater.merge(state, "s1", refused, now, ids())
        assertEquals(state.profiles, next.profiles)
        val s = next.subscriptions.single()
        assertEquals(SubscriptionUpdater.HWID_LIMIT, s.notice)
        assertEquals("Напишите мне", s.announce)
        assertNull(s.lastError)
    }

    @Test
    fun panelAddressesAreKeptUntilAListSaysOtherwise() {
        val report = "https://sub.example.com/klaus/report"
        val app = "https://sub.example.com/app/version.json"
        val state = ProfilesState(listOf(stored("a", parsed("NL", "nl.example.com"))), listOf(sub), selectedId = "a")
        val first = SubscriptionUpdater.merge(state, "s1", fetched(parsed("NL", "nl.example.com")).copy(reportUrl = report, appUrl = app), now, ids())
        assertEquals(report, first.subscriptions.single().reportUrl)
        assertEquals(app, first.subscriptions.single().appUrl)
        // Refused or only messages, without the headers: the addresses stay.
        val refused = SubscriptionUpdater.merge(first, "s1", SubscriptionUpdater.Fetched(emptyList(), notice = SubscriptionUpdater.HWID_LIMIT), now, ids())
        assertEquals(report, refused.subscriptions.single().reportUrl)
        assertEquals(app, refused.subscriptions.single().appUrl)
        // A real list without them: the owner took them away.
        val removed = SubscriptionUpdater.merge(refused, "s1", fetched(parsed("NL", "nl.example.com")), now, ids())
        assertNull(removed.subscriptions.single().reportUrl)
        assertNull(removed.subscriptions.single().appUrl)
    }

    @Test
    fun onlyHttpsAddressesFromThePanel() {
        assertEquals("https://sub.example.com/klaus/report", httpsUrl(" https://sub.example.com/klaus/report "))
        assertEquals("https://[2001:db8::1]:8443/r?x=1", httpsUrl("https://[2001:db8::1]:8443/r?x=1"))
        assertEquals("https://впн.example/app/version.json", httpsUrl("https://впн.example/app/version.json"))
        for (bad in listOf(
            null, "", "http://sub.example.com/r", "sub.example.com/r", "https://", "https:///r", "https://:443/r",
            "https://user:pw@sub.example.com/r", "https://sub.example.com/a b", "https://sub.example.com/\u0000",
            "javascript:alert(1)", "https://sub.example.com/" + "a".repeat(1000),
        )) {
            assertNull(bad, httpsUrl(bad))
        }
    }

    @Test
    fun aRealListClearsTheNotice() {
        val state = ProfilesState(emptyList(), listOf(sub.copy(notice = SubscriptionUpdater.HWID_LIMIT)), selectedId = null)
        val next = SubscriptionUpdater.merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids())
        assertNull(next.subscriptions.single().notice)
    }

    @Test
    fun deletedWhileDownloadingIsNotBroughtBack() {
        val state = ProfilesState(listOf(own), emptyList(), selectedId = "own")
        assertEquals(state, SubscriptionUpdater.merge(state, "s1", fetched(parsed("NL", "nl.example.com")), now, ids()))
    }

    @Test
    fun otherSubscriptionsAreUntouched() {
        val other = Subscription(id = "s2", name = "Другая", url = "https://other.example.com/x")
        val b = stored("b", parsed("US", "us.example.com"), sub = "s2")
        val state = ProfilesState(listOf(own, stored("a", parsed("NL", "nl.example.com")), b), listOf(sub, other), selectedId = "b")
        val next = SubscriptionUpdater.merge(state, "s1", fetched(parsed("FI", "fi.example.com")), now, ids())
        assertTrue(b in next.profiles)
        assertEquals(other, next.subscriptions[1])
        assertEquals("b", next.selectedId)
    }

    // --------------------------------------------------------- matching

    @Test
    fun sameAddressAndPortDifferentSniKeepTheirOwnIds() {
        // Two hosts on one node and port, told apart only by SNI and path.
        val one = parsed("A", "node.example.com", sni = "one.example.com", path = "/one")
        val two = parsed("B", "node.example.com", sni = "two.example.com", path = "/two")
        val old = listOf(stored("x", one), stored("y", two))
        // Both renamed and listed the other way round.
        val matches = SubscriptionUpdater.matchExisting(old, listOf(two.copy(name = "B2", link = null), one.copy(name = "A2", link = null)))
        assertEquals(listOf("y", "x"), matches.map { it?.id })
    }

    @Test
    fun linkMatchWinsOverWeakerKeys() {
        val a = parsed("A", "node.example.com", sni = "a.example.com")
        val b = parsed("B", "node.example.com", sni = "b.example.com")
        val old = listOf(stored("x", a), stored("y", b))
        // The first fresh entry would take "x" by address and port alone,
        // but "x" belongs to the second one by its link.
        val fresh = listOf(parsed("C", "node.example.com", sni = "c.example.com"), a)
        val matches = SubscriptionUpdater.matchExisting(old, fresh)
        assertEquals(listOf("y", "x"), matches.map { it?.id })
    }

    @Test
    fun changedCredentialStillMatchesByEndpoint() {
        val before = parsed("NL", "nl.example.com", sni = "nl.example.com", id = "old-uuid")
        val after = parsed("NL", "nl.example.com", sni = "nl.example.com", id = "new-uuid")
        val matches = SubscriptionUpdater.matchExisting(listOf(stored("x", before)), listOf(after))
        assertEquals("x", matches.single()?.id)
    }

    @Test
    fun addressPortAndProtocolIsTheLastResort() {
        val before = parsed("NL", "NL.example.com", sni = "old.example.com")
        val after = parsed("NL", "nl.example.com", sni = "random-1234.example.com")
        val other = parsed("NL", "nl.example.com", port = 8443)
        val matches = SubscriptionUpdater.matchExisting(listOf(stored("x", before)), listOf(other, after))
        assertEquals(listOf(null, "x"), matches.map { it?.id })
    }

    // --------------------------------------------------- running server

    @Test
    fun runningChangedOnlyWhenItsOutboundsChangeOrItIsGone() {
        val nl = parsed("NL", "nl.example.com", sni = "a.example.com")
        val before = ProfilesState(listOf(own, stored("a", nl)), listOf(sub), selectedId = "a")
        val same = SubscriptionUpdater.merge(before, "s1", fetched(nl), now, ids())
        assertFalse(SubscriptionUpdater.runningChanged(before, same, "s1", "a"))

        val changed = SubscriptionUpdater.merge(before, "s1", fetched(nl.copy(outbounds = outbounds("nl.example.com", "b.example.com"))), now, ids())
        assertEquals("a", changed.selectedId)
        assertTrue(SubscriptionUpdater.runningChanged(before, changed, "s1", "a"))

        val gone = SubscriptionUpdater.merge(before, "s1", fetched(parsed("FI", "fi.example.com")), now, ids())
        assertTrue(SubscriptionUpdater.runningChanged(before, gone, "s1", "a"))

        // An own key is not this subscription's business.
        assertFalse(SubscriptionUpdater.runningChanged(before, gone, "s1", "own"))
        assertFalse(SubscriptionUpdater.runningChanged(before, gone, "s1", null))
    }

    @Test
    fun failureKeepsServersAndRecordsTheError() {
        val state = ProfilesState(listOf(stored("a", parsed("NL", "nl.example.com"))), listOf(sub), selectedId = "a")
        val next = SubscriptionUpdater.markFailed(state, "s1", "HTTP 502 Bad Gateway", now)
        assertEquals(state.profiles, next.profiles)
        assertEquals("HTTP 502 Bad Gateway", next.subscriptions.single().lastError)
        assertEquals(now, next.subscriptions.single().lastAttemptAt)
        assertEquals(sub.updatedAt, next.subscriptions.single().updatedAt)
    }

    // ------------------------------------------------------------ stale

    @Test
    fun staleAfterAnHour() {
        assertFalse(SubscriptionUpdater.isStale(sub.copy(updatedAt = now - 59 * 60_000L), now))
        assertTrue(SubscriptionUpdater.isStale(sub.copy(updatedAt = now - hour), now))
        assertTrue(SubscriptionUpdater.isStale(sub.copy(updatedAt = 0), now))
        // The clock was set back.
        assertTrue(SubscriptionUpdater.isStale(sub.copy(updatedAt = now + hour), now))
    }

    @Test
    fun notRetriedRightAfterAnAttempt() {
        val failed = sub.copy(updatedAt = now - 5 * hour, lastAttemptAt = now - 60_000L)
        assertFalse(SubscriptionUpdater.isStale(failed, now))
        assertTrue(SubscriptionUpdater.isStale(failed.copy(lastAttemptAt = now - SubscriptionUpdater.RETRY_MS), now))
    }

    @Test
    fun storedProfileTakesTheParsedOne() {
        val p = parsed("NL", "nl.example.com", sni = "x")
        val s = p.toStored("s1", id = "a", createdAt = 5)
        assertEquals(p.outbounds, s.outbounds)
        assertEquals(p.link, s.link)
        assertEquals("s1", s.subscriptionId)
        // A server without a name is called by its address.
        assertEquals("nl.example.com", p.copy(name = "").toStored(null).name)
    }
}
