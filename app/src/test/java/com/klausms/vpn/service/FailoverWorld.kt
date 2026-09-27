package com.klausms.vpn.service

import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The failover logic wired as the service wires it, over fakes and on the
 * virtual time of [scope]. Tests set the fakes, then drive [search].
 * [profiles] starts with [SERVER_A] (selected and running), [SERVER_B] of
 * the same subscription and the own key [SERVER_C]; the subscription was
 * downloaded just now, so it is not downloaded again.
 */
internal class FailoverWorld(scope: TestScope) {
    val io = StandardTestDispatcher(scope.testScheduler)
    val clock = VirtualClock(scope.testScheduler)
    val epoch = Epoch()
    val net = FakeNetworkInfo()
    val runtime = FakeRuntime()
    val profiles = FakeProfiles(ProfilesState(listOf(SERVER_A, SERVER_B, SERVER_C), listOf(SUBSCRIPTION), selectedId = SERVER_A.id))
    val notices = FakeNotices()
    val direct = FakeDirect()
    val core = FakeCore()
    val source = FakeSubscriptionSource()
    val reports = FakeReports()
    val memory = FailureMemory(clock)
    val refresher = SubscriptionRefresher(scope, io, clock, source, session = { null }, profilesChanged = {})
    val search = FailoverSearch(clock, epoch, net, runtime, profiles, notices, direct, WhitelistLookup(scope, io, direct), memory, refresher, reports)

    /** [servers] answer a probe, each in [ms]. */
    fun answering(vararg servers: StoredProfile, ms: Long = 100) {
        for (s in servers) core.probeDelays[s.outbounds] = ms
    }

    /** Makes the subscription due for a download again. */
    fun subscriptionDue() {
        val state = profiles.state
        profiles.state = state.copy(subscriptions = state.subscriptions.map { it.copy(lastAttemptAt = 0) })
    }

    companion object {
        val SUBSCRIPTION = Subscription(id = "s1", name = "Друзья", url = "https://panel.example.com/sub", lastAttemptAt = VirtualClock.WALL_MS)
        val SERVER_A = server("a")
        val SERVER_B = server("b")
        val SERVER_C = server("c", subscriptionId = null)

        /** A server with an address and outbounds of its own. */
        fun server(id: String, subscriptionId: String? = SUBSCRIPTION.id) = StoredProfile(
            id = id,
            name = "Сервер $id",
            address = "$id.example.com",
            port = 443,
            outbounds = buildJsonArray { add(buildJsonObject { put("tag", "proxy-$id") }) },
            subscriptionId = subscriptionId,
        )
    }
}
