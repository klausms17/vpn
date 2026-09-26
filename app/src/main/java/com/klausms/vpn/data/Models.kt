package com.klausms.vpn.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/** One server. [outbounds] is the Xray outbound list produced by the core. */
@Serializable
data class StoredProfile(
    val id: String,
    val name: String,
    val protocol: String = "",
    val address: String = "",
    val port: Int = 0,
    val network: String = "",
    val security: String = "",
    /** The original share link, kept so it can be copied again. */
    val link: String? = null,
    val outbounds: JsonArray,
    val subscriptionId: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    /** When its servers were last replaced by a fresh list. */
    val updatedAt: Long = 0,
    /** Raw "subscription-userinfo" header (traffic / expiry). */
    val userInfo: String? = null,
    /** Why the last attempt failed; null once one succeeds. */
    val lastError: String? = null,
    /** Last attempt, successful or not. */
    val lastAttemptAt: Long = 0,
    /**
     * What the panel said instead of (or besides) servers: device limit,
     * subscription expired, ... The servers from before are kept then.
     */
    val notice: String? = null,
    /** The owner's message ("announce" header). */
    val announce: String? = null,
    /** Where to ask the owner for help ("support-url" header). */
    val supportUrl: String? = null,
    /** Where to say that a server stopped answering ("klaus-report-url" header, https only). */
    val reportUrl: String? = null,
    /** The owner's latest app build, version.json ("klaus-app-url" header, https only). */
    val appUrl: String? = null,
)

@Serializable
data class ProfilesState(
    val profiles: List<StoredProfile> = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val selectedId: String? = null,
) {
    val selected: StoredProfile? get() = profiles.firstOrNull { it.id == selectedId }
}

@Serializable
enum class RoutingMode(val core: String, val title: String, val description: String) {
    @SerialName("ru_direct")
    RU_DIRECT("ru_direct", "Россия напрямую", "Российские сайты и сервисы — без VPN, остальное — через VPN"),

    @SerialName("blocked_only")
    BLOCKED_ONLY("blocked_only", "Только заблокированное", "Через VPN идут только сайты, заблокированные в России"),

    @SerialName("global")
    GLOBAL("global", "Всё через VPN", "Весь трафик идёт через VPN (кроме локальной сети)"),
}

@Serializable
enum class AppMode {
    /** Every app uses the VPN except the excluded ones. */
    @SerialName("all_except")
    ALL_EXCEPT,

    /** Only the selected apps use the VPN. */
    @SerialName("only_selected")
    ONLY_SELECTED,
}

@Serializable
data class AppSettings(
    val mode: RoutingMode = RoutingMode.RU_DIRECT,
    val ipv6: Boolean = false,
    val appMode: AppMode = AppMode.ALL_EXCEPT,
    /** Russian banks, Gosuslugi, marketplaces etc. bypass the VPN entirely. */
    val bypassRussianApps: Boolean = true,
    val excludedApps: Set<String> = emptySet(),
    val includedApps: Set<String> = emptySet(),
    val directRules: List<String> = emptyList(),
    val proxyRules: List<String> = emptyList(),
    val blockRules: List<String> = emptyList(),
    /** Restart the core when Wi-Fi/mobile switches so apps reconnect at once. */
    val resetOnNetworkChange: Boolean = true,
    val verboseLog: Boolean = false,
)
