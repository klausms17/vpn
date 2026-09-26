package com.klausms.vpn.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * A JSON document in a file, shared by the UI and VPN processes. Writes go
 * to a temp file that is fsynced and renamed over the original, so a crash
 * or power loss mid-write can never corrupt the user's keys, and a reader
 * always sees a complete file. Changes go through [update], which holds a
 * lock across both processes, so neither overwrites what the other saved.
 */
class JsonFileStore<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {

    /** The saved value; the default when there is none or it cannot be read. */
    fun read(): T {
        if (!file.exists()) return default()
        return try {
            AppJson.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            // Keep the broken file for diagnostics instead of silently losing it.
            try {
                file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            } catch (_: Exception) {
            }
            default()
        }
    }

    /**
     * The saved value; the default only when there is no file. Throws when
     * it cannot be read, so nothing is ever written over the user's keys
     * because of a failed read.
     */
    fun readStrict(): T {
        if (!file.exists()) return default()
        val text = file.readText()
        return try {
            AppJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            throw IllegalStateException("Не удалось прочитать ${file.name}: файл повреждён", e)
        }
    }

    /**
     * Reads the saved value, applies [transform] and saves the result if it
     * changed, while no other thread or process can change the file. Keep
     * [transform] quick: no network, no waiting.
     */
    fun update(transform: (T) -> T): T = FileLocks.withLock(file) {
        val current = readStrict()
        val next = transform(current)
        if (next != current) save(next)
        next
    }

    private fun save(value: T) {
        file.parentFile?.mkdirs()
        // A temp file of its own for every write: never shared with another
        // writer, whichever process or thread it is in.
        val tmp = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(AppJson.encodeToString(serializer, value).toByteArray())
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IllegalStateException("Не удалось сохранить ${file.name}")
        } finally {
            tmp.delete()
        }
    }
}

/** One writer at a time per file: first among this process's threads, then across processes. */
internal object FileLocks {
    // A file lock is held by the whole process: a second lock() from
    // another thread would throw instead of waiting, hence the monitors.
    private val monitors = ConcurrentHashMap<String, Any>()

    fun <R> withLock(target: File, block: () -> R): R {
        val monitor = monitors.computeIfAbsent(target.absolutePath) { Any() }
        return synchronized(monitor) {
            target.parentFile?.mkdirs()
            RandomAccessFile(File(target.parentFile, target.name + ".lock"), "rw").use { raf ->
                val lock = raf.channel.lock()
                try {
                    block()
                } finally {
                    lock.release()
                }
            }
        }
    }
}
