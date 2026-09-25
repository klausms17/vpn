package com.klausms.vpn.data

import android.content.Context
import android.os.Process
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.util.AppLog
import kotlinx.serialization.json.JsonArray
import java.io.File

/**
 * Russian routing databases (runetfreedom/russia-v2ray-rules-dat), trimmed
 * to the categories the app uses. A copy ships inside the APK; newer ones
 * can be downloaded from the settings screen.
 */
object GeoFiles {
    const val GEOIP = "geoip.dat"
    const val GEOSITE = "geosite.dat"
    private const val VERSION = "version.txt"
    private const val SOURCE = "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/"

    fun activeDir(context: Context) = File(context.filesDir, "geo")
    fun file(context: Context, name: String) = File(activeDir(context), name)

    /** Epoch seconds of the installed databases, 0 if none. */
    fun installedVersion(context: Context): Long =
        File(activeDir(context), VERSION).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0

    private fun bundledVersion(context: Context): Long =
        context.assets.open("geo/$VERSION").bufferedReader().use { it.readText().trim().toLongOrNull() ?: 0 }

    /** Makes sure usable databases are installed; cheap when they are. */
    @Synchronized
    fun ensureInstalled(context: Context) {
        val dir = activeDir(context).apply { mkdirs() }
        val bundled = bundledVersion(context)
        val complete = File(dir, GEOIP).length() > 0 && File(dir, GEOSITE).length() > 0
        if (complete && installedVersion(context) >= bundled) return
        for (name in listOf(GEOIP, GEOSITE)) {
            // Per-process temp name: the UI and VPN processes may race here.
            val tmp = File(dir, "$name.${Process.myPid()}.tmp")
            context.assets.open("geo/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
                throw IllegalStateException("Не удалось установить базы маршрутизации")
            }
        }
        writeVersion(dir, bundled)
        AppLog.i("geo databases installed from APK (version $bundled)")
    }

    /**
     * Downloads fresh databases (about 90 MB upstream), trims them to what
     * the app uses (a few MB), validates and atomically swaps them in.
     * [via] lets the download go through the VPN server when GitHub is slow.
     */
    fun update(context: Context, via: JsonArray?, onProgress: (String) -> Unit) {
        val dir = activeDir(context).apply { mkdirs() }
        val work = File(context.cacheDir, "geo-update").apply { deleteRecursively(); mkdirs() }
        try {
            val staged = mutableListOf<Pair<File, File>>()
            for ((name, codes) in listOf(GEOIP to XrayCore.geoipCodes, GEOSITE to XrayCore.geositeCodes)) {
                onProgress("Загрузка $name…")
                val full = File(work, name)
                downloadWithFallback(SOURCE + name, full, via)
                onProgress("Проверка $name…")
                val trimmed = File(dir, "$name.new")
                XrayCore.trimGeoFile(full.absolutePath, trimmed.absolutePath, codes)
                XrayCore.checkGeoFile(trimmed.absolutePath, codes)
                full.delete()
                staged += trimmed to File(dir, name)
            }
            for ((from, to) in staged) {
                if (!from.renameTo(to)) throw IllegalStateException("Не удалось заменить ${to.name}")
            }
            writeVersion(dir, System.currentTimeMillis() / 1000)
            AppLog.i("geo databases updated")
        } finally {
            work.deleteRecursively()
            dir.listFiles { f -> f.name.endsWith(".new") }?.forEach { it.delete() }
        }
    }

    private fun downloadWithFallback(url: String, dst: File, via: JsonArray?) {
        val attempts = if (via != null) listOf(via, null) else listOf(null)
        var last: Exception? = null
        for (route in attempts) {
            try {
                XrayCore.downloadFile(url, dst.absolutePath, route)
                return
            } catch (e: Exception) {
                last = e
                AppLog.w("geo download ${if (route != null) "via proxy" else "direct"} failed", e)
            }
        }
        throw last ?: IllegalStateException("download failed")
    }

    private fun writeVersion(dir: File, version: Long) {
        val tmp = File(dir, "$VERSION.${Process.myPid()}.tmp")
        tmp.writeText(version.toString())
        tmp.renameTo(File(dir, VERSION))
    }
}
