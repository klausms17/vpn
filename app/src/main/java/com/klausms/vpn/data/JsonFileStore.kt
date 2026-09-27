package com.klausms.vpn.data

import com.klausms.vpn.util.AppLog
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
class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val log: (String) -> Unit = { AppLog.w(it) },
    private val default: () -> T,
) {

    /** The saved value; the default when there is none or it cannot be read. */
    fun read(): T {
        if (!file.exists()) return default()
        return try {
            AppJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // The file stays as it is until update() sets it aside. Logged
            // once per process, as the widget reads on every redraw, and only
            // the error's type: its message may quote the file, which holds keys.
            if (unreadableLogged.add(file.absolutePath)) log("${file.name} cannot be read (${e.javaClass.simpleName}), using the default")
            default()
        }
    }

    /**
     * The saved value; the default only when there is no file. Throws when
     * it cannot be read, so nothing is ever written over the user's keys
     * because of a failed read: [CorruptFileException] when it was read but
     * is not a valid document.
     */
    fun readStrict(): T {
        if (!file.exists()) return default()
        val text = file.readText()
        return try {
            AppJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            // The whole file was read (an I/O error is thrown above, as it is),
            // so whatever the decoder throws, the content is what is unusable.
            throw CorruptFileException("Не удалось прочитать ${file.name}: файл повреждён", e)
        }
    }

    /**
     * Reads the saved value, applies [transform] and saves the result if it
     * changed, while no other thread or process can change the file. Keep
     * [transform] quick: no network, no waiting.
     *
     * A file that was read but cannot be decoded is moved aside and the
     * change starts from the default. Refusing would block every save for
     * good with no way out in the app, and [read] already shows the default.
     * A file that cannot be read at all (an I/O error) is left alone.
     */
    fun update(transform: (T) -> T): T = FileLocks.withLock(file) {
        val current = try {
            readStrict()
        } catch (e: CorruptFileException) {
            setAside(e)
            default()
        }
        val next = transform(current)
        if (next != current) save(next)
        next
    }

    /**
     * Renames the undecodable file to "<name>.corrupt-<time>", untouched, so
     * the user's keys in it are not lost. Only the first [KEEP_CORRUPT]
     * copies are kept, the likeliest to hold them; later broken files are
     * deleted, so a bug that keeps writing them cannot fill the storage.
     */
    private fun setAside(e: CorruptFileException) {
        val dir = file.parentFile
        val kept = dir?.list()?.count { it.startsWith("${file.name}.corrupt-") } ?: 0
        val copy = File(dir, "${file.name}.corrupt-${System.currentTimeMillis()}")
        val keep = kept < KEEP_CORRUPT
        val gone = if (keep) file.renameTo(copy) else file.delete()
        // Still there: refuse, as before, rather than write over it.
        if (!gone) throw e
        val where = if (keep) "moved to ${copy.name}" else "deleted"
        // Only the error's type: its message quotes the file, which holds keys.
        log("${file.name} cannot be decoded (${e.cause?.javaClass?.simpleName}), $where; starting over")
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

/** The file was read, but its content is not a valid document. */
class CorruptFileException(message: String, cause: Throwable) : IllegalStateException(message, cause)

private const val KEEP_CORRUPT = 3

/** Files whose failed [JsonFileStore.read] this process has logged. */
private val unreadableLogged = ConcurrentHashMap.newKeySet<String>()

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
