package com.klausms.vpn.service

import java.io.File
import java.io.RandomAccessFile

/**
 * Keeps the core's log (xray.log) small. The core writes it for as long as
 * the tunnel runs, which can be weeks, and while a server is down it logs
 * every failed DNS lookup. The Logs screen then has to load it.
 */
internal object XrayLog {
    /** Above this size the log is cut. */
    const val MAX_BYTES = 512 * 1024L

    /** How much of its end moves to "xray.log.1" when it is cut. */
    const val KEEP_BYTES = 256 * 1024

    /**
     * When [file] is over [maxBytes], moves its last [keepBytes] (from a
     * line start) to "<name>.1" and empties it in place. Safe while the core
     * writes: it opens the file for appending, so its next line simply
     * starts the empty file. A "<name>.1" over [maxBytes] (left by older
     * versions) is cut to its end as well. Never throws; returns whether
     * [file] was cut. One call at a time: the check and a core restart may
     * both call it, and they would share the temporary file.
     */
    @Synchronized
    fun trim(file: File, maxBytes: Long = MAX_BYTES, keepBytes: Int = KEEP_BYTES): Boolean = try {
        val old = File(file.parentFile, file.name + ".1")
        if (old.length() > maxBytes) old.writeBytes(tail(old, keepBytes))
        if (file.length() <= maxBytes) {
            false
        } else {
            val end = tail(file, keepBytes)
            val tmp = File(file.parentFile, file.name + ".1.tmp")
            tmp.writeBytes(end)
            if (!tmp.renameTo(old)) {
                old.delete()
                tmp.renameTo(old)
            }
            RandomAccessFile(file, "rw").use { it.setLength(0) }
            true
        }
    } catch (_: Exception) {
        // A log that cannot be cut must never stop the tunnel.
        false
    }

    /** At most the last [keepBytes] of [file], from the start of a line (no half line). */
    private fun tail(file: File, keepBytes: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        // One byte more, to see whether the kept part begins a line.
        val start = (length - keepBytes - 1).coerceAtLeast(0)
        val buf = ByteArray((length - start).toInt())
        raf.seek(start)
        raf.readFully(buf)
        val from = if (start > 0) buf.indexOf('\n'.code.toByte()) + 1 else 0
        buf.copyOfRange(from, buf.size)
    }
}
