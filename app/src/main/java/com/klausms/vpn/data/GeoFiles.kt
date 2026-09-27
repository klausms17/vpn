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
    fun installedVersion(context: Context): Long = readStamp(activeDir(context)).version

    private fun bundledVersion(context: Context): Long =
        context.assets.open("geo/$VERSION").bufferedReader().use { Stamp.parse(it.readText()).version }

    /**
     * What version.txt holds: when the databases were made (epoch seconds)
     * and, on a second line, the categories they were trimmed to. Files
     * installed before that line existed have none.
     */
    internal data class Stamp(val version: Long, val codes: String?) {
        fun format(): String = if (codes == null) "$version" else "$version\n$codes"

        companion object {
            fun parse(text: String): Stamp {
                val lines = text.lines()
                return Stamp(lines.first().trim().toLongOrNull() ?: 0, lines.getOrNull(1)?.trim()?.ifEmpty { null })
            }
        }
    }

    /** The categories this version of the app loads from the databases. */
    private fun wantedCodes() = "geoip=${XrayCore.geoipCodes};geosite=${XrayCore.geositeCodes}"

    /** Makes sure usable databases are installed; cheap when they are. */
    @Synchronized
    fun ensureInstalled(context: Context) {
        val dir = activeDir(context).apply { mkdirs() }
        val bundled = bundledVersion(context)
        val complete = File(dir, GEOIP).length() > 0 && File(dir, GEOSITE).length() > 0
        val installed = readStamp(dir)
        if (complete && installed.version >= bundled && hasWantedCodes(dir, installed)) return
        for (name in listOf(GEOIP, GEOSITE)) {
            // Per-process temp name: the UI and VPN processes may race here.
            val tmp = File(dir, "$name.${Process.myPid()}.tmp")
            context.assets.open("geo/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
                throw IllegalStateException("Не удалось установить базы маршрутизации")
            }
        }
        writeStamp(dir, Stamp(bundled, wantedCodes()))
        AppLog.i("geo databases installed from APK (version $bundled)")
    }

    /**
     * Whether the installed databases hold every category this version
     * loads. Databases updated in the app are newer than the APK's copy and
     * stay, but a category added in an app update would be missing from
     * them, and the core would then fail on every start. The stamp answers
     * quickly; without a matching one (written by an older version) the
     * files themselves are checked, once.
     */
    private fun hasWantedCodes(dir: File, stamp: Stamp): Boolean {
        val wanted = wantedCodes()
        if (stamp.codes == wanted) return true
        try {
            XrayCore.checkGeoFile(File(dir, GEOIP).absolutePath, XrayCore.geoipCodes)
            XrayCore.checkGeoFile(File(dir, GEOSITE).absolutePath, XrayCore.geositeCodes)
        } catch (e: Exception) {
            AppLog.w("installed geo databases lack categories, reinstalling from APK", e)
            return false
        }
        try {
            writeStamp(dir, stamp.copy(codes = wanted))
        } catch (e: Exception) {
            // Only costs another check on the next start.
            AppLog.w("geo version not saved", e)
        }
        return true
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
            // Never older than the APK's copy, even with the phone's clock set
            // back: ensureInstalled would otherwise swap these fresh files for it.
            val version = maxOf(System.currentTimeMillis() / 1000, bundledVersion(context))
            writeStamp(dir, Stamp(version, wantedCodes()))
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

    private fun readStamp(dir: File): Stamp =
        File(dir, VERSION).takeIf { it.exists() }?.readText()?.let(Stamp::parse) ?: Stamp(0, null)

    private fun writeStamp(dir: File, stamp: Stamp) {
        val tmp = File(dir, "$VERSION.${Process.myPid()}.tmp")
        tmp.writeText(stamp.format())
        tmp.renameTo(File(dir, VERSION))
    }
}
