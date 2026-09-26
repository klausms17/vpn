package com.klausms.vpn.data

import com.klausms.vpn.core.ParsedProfile
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import java.util.Base64
import java.util.UUID

/**
 * Links asking to skip TLS verification get their certificate pinned, four
 * servers at a time. [pinned] can supply outbounds pinned earlier for the
 * same link, so an unchanged server is not contacted again. When a
 * certificate cannot be fetched, [fallback] can keep the server as it was
 * saved (it may just be unreachable right now); otherwise the server is
 * dropped with a line in [errors].
 */
suspend fun pinWhereNeeded(
    list: List<ParsedProfile>,
    errors: MutableList<String>,
    pinned: (ParsedProfile) -> JsonArray? = { null },
    fallback: (ParsedProfile) -> JsonArray? = { null },
): List<ParsedProfile> = coroutineScope {
    val limit = Semaphore(4)
    val results = list.map { p -> async(Dispatchers.IO) { pinOne(p, limit, pinned, fallback) } }.awaitAll()
    results.mapNotNull { (profile, error) ->
        error?.let { errors += it }
        profile
    }
}

/** The profile ready to store, or null and why not. */
private suspend fun pinOne(
    p: ParsedProfile,
    limit: Semaphore,
    pinned: (ParsedProfile) -> JsonArray?,
    fallback: (ParsedProfile) -> JsonArray?,
): Pair<ParsedProfile?, String?> {
    if (!p.needsCertPin) return p to null
    pinned(p)?.let { return p.copy(outbounds = it, needsCertPin = false) to null }
    return limit.withPermit {
        try {
            XrayCore.pinCertificate(p) to null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val saved = fallback(p)
            if (saved != null) {
                AppLog.w("certificate of a saved server not refreshed, kept as it was", e)
                p.copy(outbounds = saved, needsCertPin = false) to null
            } else {
                null to "${p.name}: не удалось получить сертификат сервера (${e.userMessage()})"
            }
        }
    }
}

fun ParsedProfile.toStored(
    subscriptionId: String?,
    id: String = UUID.randomUUID().toString(),
    createdAt: Long = System.currentTimeMillis(),
) = StoredProfile(
    id = id,
    name = name.ifBlank { address },
    protocol = protocol,
    address = address,
    port = port,
    network = network,
    security = security,
    link = link,
    outbounds = outbounds,
    subscriptionId = subscriptionId,
    createdAt = createdAt,
)

/** A panel's "profile-title": plain, or "base64:…" when the core left it encoded. */
fun decodeTitle(raw: String?): String? {
    val t = raw?.trim().orEmpty()
    if (t.isEmpty()) return null
    if (t.startsWith("base64:")) {
        return try {
            String(Base64.getMimeDecoder().decode(t.removePrefix("base64:")), Charsets.UTF_8).trim().ifEmpty { null }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    return t
}
