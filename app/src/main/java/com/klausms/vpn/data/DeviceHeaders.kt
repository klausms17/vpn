package com.klausms.vpn.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Headers that tell a subscription panel which phone is asking, for
 * Remnawave's device limit. Sent with subscription requests only, never
 * with other downloads. Works in both processes.
 */
object DeviceHeaders {
    // The panel counts devices by this id. Changing the recipe would make
    // every phone a new device: never change it.
    private const val HWID_SALT = "klausvpn-hwid-v1|"

    // Returned by a bug in some old Android builds for every phone.
    private const val SHARED_ANDROID_ID = "9774d56d682e549c"

    private const val HWID_FILE = "hwid"
    private const val MODEL_MAX = 64
    private const val HEX = "0123456789abcdef"

    @Volatile
    private var cached: String? = null

    /** The headers as a JSON object, as Libxray.fetchWithHeaders takes them. Blocking the first time. */
    fun json(context: Context): String {
        cached?.let { return it }
        val headers = buildJsonObject {
            put("X-Hwid", hwid(context))
            put("X-Device-Os", "Android")
            put("X-Ver-Os", printable(Build.VERSION.RELEASE.orEmpty(), 32))
            put("X-Device-Model", model(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty()))
        }.toString()
        cached = headers
        return headers
    }

    /**
     * 32 hex characters derived from ANDROID_ID (per phone, user and app
     * signing key; survives reinstalls). The raw id never leaves the phone.
     */
    @SuppressLint("HardwareIds")
    private fun hwid(context: Context): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        return if (androidId.isNullOrBlank() || androidId == SHARED_ANDROID_ID) fallbackId(context) else hashId(androidId)
    }

    internal fun hashId(androidId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((HWID_SALT + androidId).toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.substring(0, 32)
    }

    /** A random id kept on this phone only (never in backups), for phones without ANDROID_ID. */
    private fun fallbackId(context: Context): String {
        val file = File(context.noBackupFilesDir, HWID_FILE)
        // Both processes may get here first: the file lock lets one create it.
        return FileLocks.withLock(file) {
            val saved = if (file.exists()) file.readText().trim() else ""
            saved.ifEmpty {
                val id = UUID.randomUUID().toString()
                file.writeText(id)
                id
            }
        }
    }

    /** "<manufacturer> <model>" without repeating the brand, printable ASCII only. */
    internal fun model(manufacturer: String, model: String): String {
        val brand = manufacturer.trim()
        val name = model.trim()
        val full = if (brand.isEmpty() || name.lowercase(Locale.ROOT).startsWith(brand.lowercase(Locale.ROOT))) name else "$brand $name"
        return printable(full, MODEL_MAX)
    }

    /** Printable ASCII, single spaces, at most [max] characters: header values must not carry control characters. */
    internal fun printable(text: String, max: Int): String =
        text.filter { it in ' '..'~' }.split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(max).trim()
}
