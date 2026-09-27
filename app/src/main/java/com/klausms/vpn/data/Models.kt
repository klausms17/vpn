package com.klausms.vpn.data

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
data class AppSettings(
    /** Russian banks, Gosuslugi, marketplaces etc. bypass the VPN entirely. */
    val bypassRussianApps: Boolean = true,
    val excludedApps: Set<String> = emptySet(),
)
