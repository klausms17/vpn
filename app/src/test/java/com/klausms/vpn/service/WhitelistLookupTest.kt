package com.klausms.vpn.service

import com.klausms.vpn.core.DirectNet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class WhitelistLookupTest {
    /** Answers [statuses] per host; a host missing from it fails. [before] runs first, on the lookup's thread. */
    private class FakeDirect(private val statuses: Map<String, Int>, private val before: () -> Unit = {}) : DirectNet {
        val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun opens(url: String): Boolean = throw UnsupportedOperationException("not faked")

        override fun whitelistStatus(host: String): Int {
            asked += host
            before()
            return statuses[host] ?: throw Exception("lookup failed")
        }
    }

    @Test
    fun onlyHostsWhoseEveryAddressIsListedCount() = runTest {
        val direct = FakeDirect(mapOf("all" to 1, "none" to 0, "some" to -1))
        val lookup = WhitelistLookup(this, StandardTestDispatcher(testScheduler), direct)
        assertEquals(setOf("all"), lookup.listed(listOf("all", "none", "some", "failing")))
    }

    @Test
    fun answersAreRememberedButFailuresAreNot() = runTest {
        val direct = FakeDirect(mapOf("all" to 1, "none" to 0))
        val lookup = WhitelistLookup(this, StandardTestDispatcher(testScheduler), direct)
        lookup.listed(listOf("all", "none", "failing"))
        assertEquals(setOf("all"), lookup.listed(listOf("all", "none", "failing")))
        assertEquals(listOf("all", "none", "failing", "failing"), direct.asked)
    }

    @Test
    fun aSlowLookupCountsAsNotListedAndItsLateAnswerIsRemembered() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val direct = FakeDirect(mapOf("slow" to 1)) {
            entered.countDown()
            release.await()
        }
        // Real threads: the lookup blocks, as a DNS lookup does.
        val lookups = CoroutineScope(Job())
        val lookup = WhitelistLookup(lookups, Dispatchers.IO, direct)

        val first = async { lookup.listed(listOf("slow")) }
        runCurrent()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // The wait runs out (on virtual time) while the lookup still blocks.
        assertEquals(emptySet<String>(), first.await())

        release.countDown()
        lookups.coroutineContext[Job]!!.children.toList().joinAll()
        assertEquals(setOf("slow"), lookup.listed(listOf("slow")))
        assertEquals(listOf("slow"), direct.asked)
    }
}
