package com.klausms.vpn.core

import android.content.Context
import com.klausms.vpn.BuildConfig
import com.klausms.vpn.data.AppJson
import com.klausms.vpn.data.GeoFiles
import com.klausms.vpn.util.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import libxray.Controller
import libxray.Libxray
import java.io.File

/** A server as parsed by the core (see libxray/share.go Profile). */
@Serializable
data class ParsedProfile(
    val name: String = "",
    val protocol: String = "",
    val address: String = "",
    val port: Int = 0,
    val network: String = "",
    val security: String = "",
    val link: String? = null,
    val outbounds: JsonArray,
    val needsCertPin: Boolean = false,
    val certPinSni: String? = null,
    val certPinQuic: Boolean = false,
)

@Serializable
data class SubscriptionParseResult(
    val profiles: List<ParsedProfile> = emptyList(),
    val errors: List<String> = emptyList(),
    /** Messages the panel sent as fake servers (expired, device limit, ...); never in [profiles]. */
    val notices: List<String> = emptyList(),
)

@Serializable
data class TunConfig(
    val mtu: Int,
    val addresses: List<String>,
    val routes: List<String>,
    val dnsServers: List<String>,
)

/** Input of the core's config builder (see libxray/config.go BuildOptions). */
@Serializable
data class BuildOptions(
    val outbounds: JsonArray,
    val mode: String,
    val ipv6: Boolean,
    val directRules: List<String>,
    val proxyRules: List<String>,
    val blockRules: List<String>,
    val logLevel: String,
    val logFile: String,
    val tun: Boolean,
)

/**
 * Kotlin facade over the Go core. All calls are blocking; call them from a
 * background dispatcher. Errors arrive as exceptions whose message is meant
 * for the user.
 */
object XrayCore {
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** A second opinion: a server that only fails to reach Google is not dead. */
    const val TEST_URL_ALT = "https://cp.cloudflare.com/generate_204"

    /**
     * Panels choose the response format by it. Never add the phone model or
     * browser words: "Mozilla", "Edge" etc. would get the web page instead.
     * It keeps the app's former name on purpose: our panel recognises the
     * app by "KlausVPN/" (klaus-panel's response rule).
     */
    val USER_AGENT = "KlausVPN/${BuildConfig.VERSION_NAME} (Android)"

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                Libxray.initEnv(GeoFiles.activeDir(context).absolutePath)
                // A crash of the core then leaves its report on the Logs screen.
                try {
                    Libxray.setCrashLog(File(AppLog.logDir(context), "go-crash.log").absolutePath)
                } catch (e: Exception) {
                    AppLog.w("crash log", e)
                }
                initialized = true
            }
        }
    }

    fun version(): String = Libxray.version()

    fun parseLink(link: String): ParsedProfile = AppJson.decodeFromString(Libxray.parseLink(link))

    fun parseSubscription(body: ByteArray): SubscriptionParseResult =
        AppJson.decodeFromString(Libxray.parseSubscription(body))

    /** Pins the server certificate for links that asked to skip verification. */
    fun pinCertificate(profile: ParsedProfile): ParsedProfile {
        val hash = Libxray.fetchCertSha256(profile.address, profile.port, profile.certPinSni ?: "", profile.certPinQuic, 8000)
        return AppJson.decodeFromString(Libxray.pinCertificate(AppJson.encodeToString(ParsedProfile.serializer(), profile), hash))
    }

    fun buildConfig(options: BuildOptions): String =
        Libxray.buildConfig(AppJson.encodeToString(BuildOptions.serializer(), options))

    fun proxyOnlyConfig(outbounds: JsonArray): String = Libxray.buildProxyOnlyConfig(outbounds.toString())

    fun tunConfig(ipv6: Boolean): TunConfig = AppJson.decodeFromString(Libxray.tunSettings(ipv6))

    /** Real latency through the server (TLS/REALITY handshake + HTTP). */
    fun measureDelay(outbounds: JsonArray, timeoutMs: Int = 10_000): Long =
        Libxray.measureOutboundDelay(proxyOnlyConfig(outbounds), TEST_URL, timeoutMs)

    /**
     * Downloads a subscription [url], optionally through [via] (a profile's
     * outbounds, in a temporary core). [headers]: extra request headers as a
     * JSON object (see DeviceHeaders), "" for none.
     */
    fun fetch(url: String, via: JsonArray?, headers: String = "", timeoutMs: Int = 25_000): libxray.FetchResult =
        Libxray.fetchWithHeaders(url, USER_AGENT, headers, timeoutMs, via?.let { proxyOnlyConfig(it) } ?: "")

    /**
     * Downloads [url] through the running tunnel's server, without a
     * temporary core: the one way to go through a server in the VPN process.
     */
    fun fetchThroughTunnel(controller: Controller, url: String, headers: String, timeoutMs: Int = 15_000): libxray.FetchResult =
        controller.fetchThroughTunnel(url, USER_AGENT, headers, timeoutMs)

    /**
     * Real latency through each candidate (a profile's outbounds), all in one
     * temporary core that leaves the running tunnel's log alone. Returns ms
     * or -1 per candidate, in the same order.
     */
    fun probe(
        controller: Controller,
        candidates: List<JsonArray>,
        url: String = TEST_URL,
        timeoutMs: Int = 5_000,
        parallel: Int = 4,
    ): List<Long> {
        if (candidates.isEmpty()) return emptyList()
        val results = AppJson.decodeFromString<List<Long>>(controller.probeOutbounds(JsonArray(candidates).toString(), url, timeoutMs, parallel))
        return List(candidates.size) { results.getOrElse(it) { -1L } }
    }

    fun downloadFile(url: String, dst: String, via: JsonArray?) =
        Libxray.downloadFile(url, dst, USER_AGENT, 600_000, via?.let { proxyOnlyConfig(it) } ?: "")

    fun trimGeoFile(src: String, dst: String, codes: String) = Libxray.trimGeoFile(src, dst, codes)

    fun checkGeoFile(path: String, codes: String) = Libxray.checkGeoFile(path, codes)

    /** 1 = every address of [host] is on the Russian mobile whitelist, 0 = none, -1 = some. */
    fun whitelistStatus(context: Context, host: String): Int =
        Libxray.hostInGeoIP(GeoFiles.file(context, GeoFiles.GEOIP).absolutePath, "ru-whitelist", host, 5_000)

    val geositeCodes: String get() = Libxray.GeositeCodes
    val geoipCodes: String get() = Libxray.GeoipCodes
}

/** Go errors reach Kotlin as plain exceptions; this keeps the text readable. */
fun Throwable.userMessage(): String {
    val m = message?.trim().orEmpty()
    return m.ifEmpty { javaClass.simpleName }
}
