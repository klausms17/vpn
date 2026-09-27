package com.klausms.vpn.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny size-capped file log, one file per process, shown on the Logs screen.
 * Never log keys, links or passwords here.
 */
object AppLog {
    private const val TAG = "KlausVPN"
    private const val MAX_BYTES = 128 * 1024L
    private var file: File? = null
    private val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context, processTag: String) {
        file = File(logDir(context), "app-$processTag.log")
    }

    fun logDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    /**
     * The last [maxLines] lines of [file], read from at most its last
     * [maxBytes]: a log can grow large (the core's, during a long outage),
     * and reading it whole could run the app out of memory.
     */
    fun tail(file: File, maxLines: Int, maxBytes: Int = 64 * 1024): List<String> {
        if (!file.isFile) return emptyList()
        return try {
            RandomAccessFile(file, "r").use { f ->
                val length = f.length()
                val start = (length - maxBytes).coerceAtLeast(0)
                val bytes = ByteArray((length - start).toInt())
                f.seek(start)
                f.readFully(bytes)
                val lines = String(bytes, Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }
                // Started mid-file: the first line is only the end of one.
                (if (start > 0) lines.drop(1) else lines).takeLast(maxLines)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun i(message: String) = write("I", message, null)
    fun w(message: String, e: Throwable? = null) = write("W", message, e)
    fun e(message: String, e: Throwable? = null) = write("E", message, e)

    @Synchronized
    private fun write(level: String, message: String, e: Throwable?) {
        val line = buildString {
            append(time.format(Date())).append(' ').append(level).append(' ').append(message)
            if (e != null) append(": ").append(e.javaClass.simpleName).append(' ').append(e.message)
        }
        when (level) {
            "E" -> Log.e(TAG, line)
            "W" -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, f.name + ".1")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(line + "\n")
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }
}
