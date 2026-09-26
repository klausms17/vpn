package com.klausms.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.telephony.TelephonyManager
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.data.httpsUrl
import com.klausms.vpn.util.AppLog
import libxray.Controller
import java.util.Locale

/**
 * Tells the owner's panel that a server stopped answering from this
 * phone's network, so the owner learns about a block before friends
 * write. Only after an automatic switch to another server worked: that
 * proves the phone itself is online. The panel alerts the owner once
 * several people report the same server.
 *
 * One GET to the subscription's "klaus-report-url" with the server's
 * address, port and protocol, the network type (on mobile data also the
 * operator's name), the app version and the subscription's short id (so
 * the panel accepts only real subscriptions and counts people, not
 * reports). Nothing else: no device id, sites or traffic. Direct first,
 * through the tunnel if the panel is blocked too; failures are only
 * logged. Blocking: call it off the main thread.
 */
internal class BlockReporter(context: Context) {
    private val appContext = context.applicationContext
    private val throttle = ReportThrottle(BlockReport.THROTTLE_MS)

    /**
     * Reports [failed], a server of [subscription], unless the panel did not
     * ask for reports or it was reported in the last 30 minutes. [network]:
     * the phone's own network; [tunnel]: the running core, for the second try.
     */
    fun report(failed: StoredProfile, subscription: Subscription, network: Network?, tunnel: () -> Controller?) {
        val base = httpsUrl(subscription.reportUrl) ?: return
        val id = BlockReport.shortUuid(subscription.url) ?: return
        if (failed.address.isBlank()) return
        if (!throttle.claim(BlockReport.serverKey(failed.address, failed.port), SystemClock.elapsedRealtime())) return
        val kind = networkKind(network)
        val url = BlockReport.url(
            base = base,
            shortUuid = id,
            host = failed.address.trim(),
            port = failed.port,
            protocol = failed.protocol,
            network = kind,
            operator = if (kind == BlockReport.MOBILE) BlockReport.operator(operatorName()) else "",
            version = BuildConfig.VERSION_NAME,
        )
        send(url, tunnel)
    }

    private fun send(url: String, tunnel: () -> Controller?) {
        try {
            XrayCore.fetch(url, via = null, timeoutMs = BlockReport.TIMEOUT_MS)
            AppLog.i("block report sent")
            return
        } catch (e: Exception) {
            // The panel answered (e.g. 403): through the tunnel it would say the same.
            if (e.message?.startsWith("HTTP ") == true) {
                AppLog.w("block report refused: ${e.userMessage()}")
                return
            }
            AppLog.w("block report failed directly: ${e.userMessage()}")
        }
        val c = tunnel() ?: return
        try {
            XrayCore.fetchThroughTunnel(c, url, headers = "", timeoutMs = BlockReport.TIMEOUT_MS)
            AppLog.i("block report sent through the tunnel")
        } catch (e: Exception) {
            AppLog.w("block report failed: ${e.userMessage()}")
        }
    }

    private fun networkKind(network: Network?): String {
        val caps = try {
            network?.let { appContext.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(it) }
        } catch (_: Exception) {
            null
        }
        return BlockReport.networkKind(
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        )
    }

    /** The mobile network's name ("MTS RUS", "Билайн"); needs no permission. */
    private fun operatorName(): String? = try {
        appContext.getSystemService(TelephonyManager::class.java)?.networkOperatorName
    } catch (_: Exception) {
        null
    }
}

/** The pure part of a block report: what goes into the request. */
internal object BlockReport {
    /** A server is reported at most this often from one phone. */
    const val THROTTLE_MS = 30 * 60_000L
    const val TIMEOUT_MS = 10_000

    const val MOBILE = "mobile"
    const val WIFI = "wifi"
    const val OTHER = "other"

    private const val OPERATOR_MAX = 40
    private const val ID_MAX = 128
    private const val HEX = "0123456789ABCDEF"

    // Remnawave serves other formats at /<shortUuid>/<type>.
    private val CLIENT_TYPES = setOf("json", "v2ray-json", "mihomo", "singbox", "clash", "stash")

    /**
     * The friend's personal id in a Remnawave subscription URL: the last
     * path segment (before "?" and "#", trailing "/" ignored), or the one
     * before a format suffix such as "/json". Null when there is none.
     */
    fun shortUuid(subscriptionUrl: String): String? {
        val rest = subscriptionUrl.trim().substringAfter("://", "")
        val path = rest.substringBefore('#').substringBefore('?').substringAfter('/', "")
        val segments = path.split('/').filter { it.isNotEmpty() }
        var id = segments.lastOrNull() ?: return null
        if (id.lowercase(Locale.ROOT) in CLIENT_TYPES && segments.size >= 2) id = segments[segments.size - 2]
        return id.takeIf { it.length <= ID_MAX && it.all(::isUnreserved) }
    }

    /** The network type the panel counts: cellular is mobile, Wi-Fi and Ethernet are wifi. */
    fun networkKind(cellular: Boolean, wifi: Boolean, ethernet: Boolean): String = when {
        cellular -> MOBILE
        wifi || ethernet -> WIFI
        else -> OTHER
    }

    /** The operator's name as sent: printable Latin or Cyrillic, single spaces, at most 40 characters. */
    fun operator(name: String?): String =
        name.orEmpty()
            .map { if (it in ' '..'~' || it in '\u0400'..'\u04FF') it else ' ' }
            .joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            .take(OPERATOR_MAX).trim()

    /** One report per server, whatever its protocol: the panel counts by address and port. */
    fun serverKey(host: String, port: Int): String = "${host.trim().lowercase(Locale.ROOT)}:$port"

    /** The report request: [base] with every value percent-encoded as UTF-8. */
    fun url(
        base: String,
        shortUuid: String,
        host: String,
        port: Int,
        protocol: String,
        network: String,
        operator: String,
        version: String,
    ): String {
        val query = listOf(
            "s" to shortUuid,
            "h" to host,
            "p" to port.toString(),
            "k" to protocol,
            "n" to network,
            "o" to operator,
            "v" to version,
        ).joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        val target = base.substringBefore('#')
        val separator = when {
            '?' !in target -> "?"
            target.endsWith("?") || target.endsWith("&") -> ""
            else -> "&"
        }
        return target + separator + query
    }

    /** Everything but RFC 3986 unreserved characters as %XX of its UTF-8 bytes (no "+" for spaces). */
    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c < 0x80 && isUnreserved(c.toChar())) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0f])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.~"
}

/**
 * Lets one report per key through in [windowMs] (elapsed realtime). In
 * memory: a restarted VPN process may report again, which is rare enough.
 */
internal class ReportThrottle(private val windowMs: Long) {
    private val sent = HashMap<String, Long>()

    @Synchronized
    fun claim(key: String, now: Long): Boolean {
        sent.values.removeAll { now - it >= windowMs || it > now }
        if (key in sent) return false
        sent[key] = now
        return true
    }
}
