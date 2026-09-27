package com.klausms.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.data.httpsUrl
import com.klausms.vpn.util.AppLog
import java.util.Locale

/**
 * Tells the owner's panel that a server stopped answering from this
 * phone's network, so the owner learns about a block before friends
 * write. Only once the phone is known to be online: an automatic switch
 * to another server worked, or no server answers while a Russian site
 * opens directly (then marked as "all down"). The panel alerts the owner
 * once several people report the same server.
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
     * the phone's own network; [tunnel]: the running core, for the second try;
     * [allDown]: no other server answered either; [whitelist]: the mobile
     * operator seems to let through only its whitelist, not a block.
     */
    fun report(
        failed: StoredProfile,
        subscription: Subscription,
        network: Network?,
        allDown: Boolean = false,
        whitelist: Boolean = false,
        tunnel: () -> CoreHandle?,
    ) {
        val base = httpsUrl(subscription.reportUrl) ?: return
        val id = BlockReport.shortUuid(subscription.url) ?: return
        if (failed.address.isBlank()) return
        val key = BlockReport.serverKey(failed.address, failed.port)
        if (!throttle.claim(key, SystemClock.elapsedRealtime())) return
        val caps = capabilities(network)
        val kind = BlockReport.networkKind(
            cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
            wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
        )
        val url = BlockReport.url(
            base = base,
            shortUuid = id,
            host = failed.address.trim(),
            port = failed.port,
            protocol = failed.protocol,
            network = kind,
            operator = if (kind == BlockReport.MOBILE) BlockReport.operator(operatorName(caps)) else "",
            version = BuildConfig.VERSION_NAME,
            allDown = allDown,
            whitelist = whitelist,
        )
        // Not delivered (or the panel could not take it now): the next
        // switch away from this server may report it again.
        if (!send(url, tunnel)) throttle.release(key)
    }

    /** True when the panel got the report, or refused it for good (4xx). */
    private fun send(url: String, tunnel: () -> CoreHandle?): Boolean {
        try {
            XrayCore.fetch(url, via = null, timeoutMs = BlockReport.TIMEOUT_MS)
            AppLog.i("block report sent")
            return true
        } catch (e: Exception) {
            // The panel answered: through the tunnel it would say the same.
            val status = BlockReport.httpStatus(e.message)
            if (status != null) {
                AppLog.w("block report refused: ${e.userMessage()}")
                return status in 400..499
            }
            AppLog.w("block report failed directly: ${e.userMessage()}")
        }
        val core = tunnel() ?: return false
        return try {
            core.fetchThroughTunnel(url, XrayCore.USER_AGENT, headers = "", timeoutMs = BlockReport.TIMEOUT_MS)
            AppLog.i("block report sent through the tunnel")
            true
        } catch (e: Exception) {
            AppLog.w("block report failed: ${e.userMessage()}")
            BlockReport.httpStatus(e.message) in 400..499
        }
    }

    private fun capabilities(network: Network?): NetworkCapabilities? = try {
        network?.let { appContext.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * The name of the mobile network the data goes through ("MTS RUS",
     * "Билайн"); needs no permission. On a dual-SIM phone that is the data
     * SIM, not the one for calls (what a plain TelephonyManager tells).
     */
    private fun operatorName(caps: NetworkCapabilities?): String? = try {
        val specified = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (caps?.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId
        } else {
            null
        }
        val id = specified?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: SubscriptionManager.getDefaultDataSubscriptionId()
        if (id == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            null
        } else {
            appContext.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(id)?.networkOperatorName
        }
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

    /** The status of an "HTTP 503 Service Unavailable" error from the core, or null for other errors. */
    fun httpStatus(message: String?): Int? {
        val m = Regex("""^HTTP (\d{3})\b""").find(message ?: return null) ?: return null
        return m.groupValues[1].toInt()
    }

    /** One report per server, whatever its protocol: the panel counts by address and port. */
    fun serverKey(host: String, port: Int): String = "${host.trim().lowercase(Locale.ROOT)}:$port"

    /**
     * The report request: [base] with every value percent-encoded as UTF-8.
     * [allDown] adds "a=1": no server of the phone answered, not only this one.
     * [whitelist] adds "w=1": the mobile whitelist, not a block of this server.
     */
    fun url(
        base: String,
        shortUuid: String,
        host: String,
        port: Int,
        protocol: String,
        network: String,
        operator: String,
        version: String,
        allDown: Boolean = false,
        whitelist: Boolean = false,
    ): String {
        val values = listOfNotNull(
            "s" to shortUuid,
            "h" to host,
            "p" to port.toString(),
            "k" to protocol,
            "n" to network,
            "o" to operator,
            "v" to version,
            ("a" to "1").takeIf { allDown },
            ("w" to "1").takeIf { whitelist },
        )
        val query = values.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
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

    /** Forgets a claim whose report did not get through, so the next one may try. */
    @Synchronized
    fun release(key: String) {
        sent.remove(key)
    }
}
