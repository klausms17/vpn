package com.klausms.vpn.data

import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private val logged = mutableListOf<String>()

    // A new store per call, as Stores does: the lock must not depend on the instance.
    private fun store() = JsonFileStore(file, Counter.serializer(), log = { logged += it }) { Counter() }

    private fun corruptCopies() = dir.list()!!.filter { it.startsWith("counter.json.corrupt-") }

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
    fun brokenFileIsSetAsideAndSavingWorksAgain() {
        val broken = "{\"value\": \"vless://secret-key@1.2.3.4\""
        file.writeText(broken)
        // The lenient read gives the app something to show; the strict one refuses.
        assertEquals(Counter(), store().read())
        try {
            store().readStrict()
            fail("readStrict must refuse a file it cannot decode")
        } catch (e: CorruptFileException) {
            assertTrue(e.message!!.contains("counter.json"))
        }
        // Not stuck: the change starts from the default, the broken file is kept untouched.
        assertEquals(Counter(value = 5), store().update { it.copy(value = 5) })
        assertEquals(Counter(value = 5), store().readStrict())
        assertEquals(broken, File(dir, corruptCopies().single()).readText())
        // Logged, but never the content: it holds the user's keys.
        assertFalse(logged.single().contains("secret"))
        assertFalse(logged.single().contains("1.2.3.4"))
    }

    @Test
    fun onlyTheFirstBrokenFilesAreKept() {
        repeat(5) { i ->
            file.writeText("broken $i")
            store().update { it.copy(value = i) }
            Thread.sleep(2) // the copies are named by the millisecond
        }
        assertEquals(3, corruptCopies().size)
        // The first ones are the likeliest to hold the user's own keys.
        assertEquals(setOf("broken 0", "broken 1", "broken 2"), corruptCopies().map { File(dir, it).readText() }.toSet())
        assertEquals(Counter(value = 4), store().read())
    }

    @Test
    fun unreadableFileIsLeftAlone() {
        // Not a decoding problem but an I/O error: refuse and change nothing.
        file.mkdirs()
        try {
            store().update { it.copy(value = 5) }
            fail("update must refuse a file it cannot read")
        } catch (e: Exception) {
            assertFalse(e is CorruptFileException)
        }
        assertTrue(file.isDirectory)
        assertTrue(corruptCopies().isEmpty())
    }

    @Test
    fun missingFileStartsFromTheDefault() {
        assertEquals(Counter(value = 1), store().update { it.copy(value = it.value + 1) })
        assertEquals(Counter(value = 1), store().readStrict())
    }
}
