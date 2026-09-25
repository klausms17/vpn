package com.klausms.vpn.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * A JSON document in a file. Writes go to a temp file that is fsynced and
 * renamed over the original, so a crash or power loss mid-write can never
 * corrupt the user's keys, and the VPN process always reads a complete file.
 */
class JsonFileStore<T>(private val file: File, private val serializer: KSerializer<T>, private val default: () -> T) {

    @Synchronized
    fun read(): T {
        if (!file.exists()) return default()
        return try {
            AppJson.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            // Keep the broken file for diagnostics instead of silently losing it.
            file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            default()
        }
    }

    @Synchronized
    fun write(value: T) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(AppJson.encodeToString(serializer, value).toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("Не удалось сохранить ${file.name}")
        }
    }
}
