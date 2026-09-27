package com.klausms.vpn.data

import com.klausms.vpn.core.ParsedProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesOpsTest {
    private fun server(id: String, sub: String? = null) =
        StoredProfile(id = id, name = id, outbounds = JsonArray(emptyList()), subscriptionId = sub)

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
    fun theSameKeysImportedTwiceAreSavedOnce() {
        // Two imports of one key racing: the second save sees the first one's result.
        val ready = listOf(parsed("vless://a"), parsed("vless://b"))
        val (once, added) = ProfilesState().withNewKeys(ready)
        assertEquals(listOf("vless://a", "vless://b"), added.map { it.link })
        assertEquals(added, once.profiles)
        assertEquals(added.first().id, once.selectedId)

        val (twice, addedAgain) = once.withNewKeys(ready)
        assertTrue(addedAgain.isEmpty())
        assertSame(once, twice)
    }

    @Test
    fun deletingTheSelectedServerSelectsTheFirstRemainingOne() {
        val state = ProfilesState(profiles = listOf(server("a"), server("b"), server("c")), selectedId = "b")
        val next = state.withoutProfile("b")
        assertEquals(listOf("a", "c"), next.profiles.map { it.id })
        assertEquals("a", next.selectedId)

        assertNull(ProfilesState(profiles = listOf(server("a")), selectedId = "a").withoutProfile("a").selectedId)
    }

    @Test
    fun deletingAnotherServerKeepsTheSelection() {
        val state = ProfilesState(profiles = listOf(server("a"), server("b")), selectedId = "b")
        val next = state.withoutProfile("a")
        assertEquals(listOf("b"), next.profiles.map { it.id })
        assertEquals("b", next.selectedId)
    }

    @Test
    fun deletingASubscriptionKeepsASelectionOutsideIt() {
        val sub = Subscription(id = "s", name = "s", url = "https://example.com/s")
        val other = Subscription(id = "t", name = "t", url = "https://example.com/t")
        val state = ProfilesState(
            profiles = listOf(server("own"), server("s1", sub = "s"), server("t1", sub = "t")),
            subscriptions = listOf(sub, other),
            selectedId = "t1",
        )
        val next = state.withoutSubscription("s")
        assertEquals(listOf("own", "t1"), next.profiles.map { it.id })
        assertEquals(listOf("t"), next.subscriptions.map { it.id })
        assertEquals("t1", next.selectedId)

        // The selected server was in it: the first remaining one takes over.
        assertEquals("own", state.copy(selectedId = "s1").withoutSubscription("s").selectedId)
        assertNull(
            ProfilesState(profiles = listOf(server("s1", sub = "s")), subscriptions = listOf(sub), selectedId = "s1")
                .withoutSubscription("s").selectedId,
        )
    }

    @Test
    fun renamingChangesOnlyThatServer() {
        val state = ProfilesState(profiles = listOf(server("a"), server("b")), selectedId = "a")
        assertEquals(listOf("A", "b"), state.renamed("a", "A").profiles.map { it.name })
    }
}
