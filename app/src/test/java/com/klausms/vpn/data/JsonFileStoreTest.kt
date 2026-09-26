package com.klausms.vpn.data

import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class JsonFileStoreTest {
    @Serializable
    data class Counter(val value: Int = 0, val log: List<String> = emptyList())

    private val dir: File = Files.createTempDirectory("store").toFile()
    private val file = File(dir, "counter.json")

    // A new store per call, as Stores does: the lock must not depend on the instance.
    private fun store() = JsonFileStore(file, Counter.serializer()) { Counter() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun updatesFromTwoThreadsAreNeverLost() {
        val rounds = 200
        val start = CountDownLatch(1)
        val workers = listOf("a", "b").map { name ->
            thread {
                start.await()
                repeat(rounds) { i -> store().update { it.copy(value = it.value + 1, log = (it.log + "$name$i").takeLast(5)) } }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(2 * rounds, store().read().value)
        // Every writer used a temp file of its own, and none is left behind.
        assertEquals(listOf("counter.json", "counter.json.lock"), dir.list()!!.sorted())
    }

    @Test
    fun unchangedValueIsNotWritten() {
        store().update { it.copy(value = 1) }
        val before = file.lastModified()
        file.setLastModified(before - 10_000)
        val result = store().update { it }
        assertEquals(1, result.value)
        assertEquals(before - 10_000, file.lastModified())
    }

    @Test
    fun brokenFileIsNeverOverwritten() {
        file.writeText("{not json")
        try {
            store().update { it.copy(value = 5) }
            fail("update must refuse to replace a file it cannot read")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("counter.json"))
        }
        assertEquals("{not json", file.readText())
        // The lenient read still gives the app something to show.
        assertEquals(Counter(), store().read())
    }

    @Test
    fun missingFileStartsFromTheDefault() {
        assertEquals(Counter(value = 1), store().update { it.copy(value = it.value + 1) })
        assertEquals(Counter(value = 1), store().readStrict())
    }
}
