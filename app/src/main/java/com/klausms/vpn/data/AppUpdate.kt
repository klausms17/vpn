package com.klausms.vpn.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A build of the app the owner published on the panel host, as described
 * by its version.json: {"versionCode": 27, "versionName": "1.0.27",
 * "apk": "https://…/KirovVPN-1.0.27.apk", "sha256": "…"}. The APK is
 * opened in the browser; Android's installer checks the signature itself,
 * so the hash is not needed here.
 */
data class AppUpdate(val versionCode: Int, val versionName: String, val apkUrl: String) {

    /** The same fields as version.json, to keep the last answer between launches. */
    fun toJson(): String = buildJsonObject {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("apk", apkUrl)
    }.toString()

    companion object {
        /** The app asks the panel at most this often. */
        const val CHECK_MS = 12 * 60 * 60_000L

        // A version.json is a few lines; anything bigger is not one.
        private const val MAX_LENGTH = 16 * 1024
        private const val NAME_MAX = 32

        /** The build [text] describes, or null when it is not a usable version.json. */
        fun parse(text: String?): AppUpdate? {
            if (text.isNullOrBlank() || text.length > MAX_LENGTH) return null
            val obj = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return null
            val code = obj.primitive("versionCode")?.content?.toIntOrNull() ?: return null
            if (code <= 0) return null
            val apk = httpsUrl(obj.primitive("apk")?.takeIf { it.isString }?.content) ?: return null
            // Only shown to the user: printable, short; the code stands in for a missing name.
            val name = obj.primitive("versionName")?.takeIf { it.isString }?.content
                ?.filter { it in ' '..'~' }?.trim()?.take(NAME_MAX)?.trim()
                ?.ifEmpty { null }
                ?: code.toString()
            return AppUpdate(code, name, apk)
        }

        /** [latest] when it is newer than the [installed] build and than the one put off with «Позже». */
        fun offer(latest: AppUpdate?, installed: Int, dismissed: Int): AppUpdate? =
            latest?.takeIf { it.versionCode > installed && it.versionCode > dismissed }

        /** Whether it is time to ask again (wall clock; a clock set back counts as due). */
        fun checkDue(lastCheckAt: Long, now: Long): Boolean =
            now - lastCheckAt >= CHECK_MS || lastCheckAt > now

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    }
}
