package com.klausms.vpn.data

import android.content.Context
import com.klausms.vpn.core.ParsedProfile
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.userMessage
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.Locale
import java.util.UUID

/** How a subscription is downloaded: directly, through a server, through the tunnel. */
fun interface Downloader {
    /** Blocking. [headers]: the device headers as a JSON object. */
    fun fetch(url: String, headers: String): libxray.FetchResult
}

/**
 * [text] when it is a plain https URL with a host, else null: addresses
 * from panel headers and files are used only then (never http, no spaces,
 * control characters or "user@" before the host).
 */
fun httpsUrl(text: String?, max: Int = 1000): String? {
    val t = text?.trim() ?: return null
    if (t.length > max || !t.startsWith("https://", ignoreCase = true)) return null
    if (t.any { it.isWhitespace() || it.isISOControl() }) return null
    val authority = t.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
    val host = if (authority.startsWith("[")) authority.substringBefore(']').drop(1) else authority.substringBefore(':')
    return t.takeIf { host.isNotEmpty() && '@' !in authority }
}

/**
 * Adds and refreshes subscriptions, from the UI or the VPN process. The
 * download happens first; the result is merged into what is on disk at
 * that moment, under the file lock, so the two processes never undo each
 * other's changes or give the same server two different ids.
 *
 * The servers the user has are only replaced by a real new list: when the
 * panel refuses (device limit), sends only messages (expired, ...) or fails
 * (HTTP 502, no network), they stay, and the message is shown instead.
 */
class SubscriptionUpdater(context: Context, private val profiles: ProfilesAccess) {
    private val appContext = context.applicationContext

    /** What a refresh or an add did. */
    data class Outcome(
        /** The subscription as saved. */
        val subscription: Subscription,
        /** Its servers now. */
        val servers: List<StoredProfile>,
        /** False when the panel sent no servers and the old ones were kept. */
        val applied: Boolean,
        /** Entries that could not be imported. */
        val errors: List<String>,
        /** The running server was changed or removed: the tunnel should restart. */
        val runningChanged: Boolean,
    )

    /** A downloaded subscription, ready to be merged. */
    data class Fetched(
        /** Ready to store (certificates pinned); empty means: keep the old servers. */
        val profiles: List<ParsedProfile>,
        val notice: String? = null,
        val errors: List<String> = emptyList(),
        val title: String? = null,
        val userInfo: String? = null,
        val supportUrl: String? = null,
        val announce: String? = null,
        val reportUrl: String? = null,
        val appUrl: String? = null,
    )

    /** Downloads [url] and adds it as a new subscription. Throws when it cannot be used. */
    suspend fun add(url: String, downloader: Downloader): Outcome {
        if (profiles.snapshot().subscriptions.any { it.url == url }) throw IllegalStateException(ALREADY_ADDED)
        val fetched = download(url, downloader, reuse = Pinned.NONE, known = Pinned.NONE)
        val now = System.currentTimeMillis()
        val sub = Subscription(id = UUID.randomUUID().toString(), name = fetched.title ?: hostOf(url) ?: "Подписка", url = url)
        var before = ProfilesState()
        val after = profiles.updateProfiles { s ->
            if (s.subscriptions.any { it.url == url }) throw IllegalStateException(ALREADY_ADDED)
            before = s
            merge(s.copy(subscriptions = s.subscriptions + sub), sub.id, fetched, now)
        }
        return outcome(before, after, sub.id, fetched, runningId = null)
            ?: throw IllegalStateException("Не удалось сохранить подписку")
    }

    /**
     * Downloads subscription [id] again and merges it. [runningId]: the
     * server the tunnel runs (default: the selected one), for
     * [Outcome.runningChanged]. [repin]: fetch pinned certificates again
     * instead of keeping those of unchanged links (a manual refresh).
     * [repinId]: only this server's certificate is fetched again, e.g. the
     * one that just failed: its operator may have issued a new one, and the
     * old pin would keep it broken.
     * Returns null when the subscription is gone; throws, after saving the
     * error on the subscription, when the download or parsing fails.
     */
    suspend fun refresh(
        id: String,
        downloader: Downloader,
        runningId: String? = null,
        repin: Boolean = false,
        repinId: String? = null,
    ): Outcome? {
        val snapshot = profiles.snapshot()
        val sub = snapshot.subscriptions.firstOrNull { it.id == id } ?: return null
        // Certificates pinned before: reused for unchanged servers, and kept
        // when a server cannot be reached right now (it is not deleted).
        val servers = snapshot.profiles.filter { it.subscriptionId == id }
        val known = Pinned.of(servers)
        val stale = servers.firstOrNull { it.id == repinId }?.link
        val reuse = when {
            repin -> Pinned.NONE
            stale != null -> known.without(stale)
            else -> known
        }
        val fetched = try {
            download(sub.url, downloader, reuse, known)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            recordFailure(id, e.userMessage())
            throw e
        }
        val now = System.currentTimeMillis()
        var before = ProfilesState()
        val after = profiles.updateProfiles { s ->
            before = s
            merge(s, id, fetched, now)
        }
        return outcome(before, after, id, fetched, runningId ?: before.selectedId)
    }

    private suspend fun recordFailure(id: String, error: String) {
        try {
            profiles.updateProfiles { s -> markFailed(s, id, error, System.currentTimeMillis()) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.e("save failed", e)
        }
    }

    private suspend fun download(url: String, downloader: Downloader, reuse: Pinned, known: Pinned): Fetched {
        val (result, parsed) = withContext(Dispatchers.IO) {
            val r = downloader.fetch(url, DeviceHeaders.json(appContext))
            // Refused for the device limit: the body holds only placeholders, or nothing.
            val refused = r.hwidMaxDevices || r.hwidLimit || r.hwidNotSupported
            r to if (refused) null else XrayCore.parseSubscription(r.body ?: ByteArray(0))
        }
        val base = Fetched(
            profiles = emptyList(),
            title = decodeTitle(result.profileTitle),
            userInfo = result.userInfo.clean(),
            supportUrl = result.supportUrl.clean()?.take(500),
            announce = result.announce.clean()?.take(500),
            reportUrl = httpsUrl(result.reportUrl),
            appUrl = httpsUrl(result.appUrl),
        )
        if (parsed == null) {
            AppLog.w("subscription refused by the panel's device limit")
            return base.copy(notice = if (result.hwidNotSupported) HWID_NOT_SUPPORTED else HWID_LIMIT)
        }
        val errors = parsed.errors.toMutableList()
        val ready = pinWhereNeeded(parsed.profiles, errors, pinned = reuse::sameLink, fallback = known::sameServer)
        // Servers came, none usable: an error, not an empty list.
        if (ready.isEmpty() && parsed.profiles.isNotEmpty()) throw IllegalStateException(errors.firstOrNull() ?: "в подписке нет подходящих серверов")
        val notice = parsed.notices.joinToString("\n").ifBlank { null }
        return base.copy(profiles = ready, notice = notice, errors = errors)
    }

    private fun outcome(before: ProfilesState, after: ProfilesState, id: String, fetched: Fetched, runningId: String?): Outcome? {
        val sub = after.subscriptions.firstOrNull { it.id == id } ?: return null
        return Outcome(
            subscription = sub,
            servers = after.profiles.filter { it.subscriptionId == id },
            applied = fetched.profiles.isNotEmpty(),
            errors = fetched.errors,
            runningChanged = runningChanged(before, after, id, runningId),
        )
    }

    companion object {
        /** A subscription older than this is refreshed when the app is opened. */
        const val STALE_MS = 60 * 60_000L

        /** Automatic attempts at most this often, whatever the result. */
        const val RETRY_MS = 2 * 60_000L

        const val HWID_LIMIT = "Достигнут лимит устройств для этой подписки"
        const val HWID_NOT_SUPPORTED = "Сервер подписки не принял это устройство. Сообщите владельцу подписки."
        private const val ALREADY_ADDED = "Эта подписка уже добавлена"

        private val TRANSPORTS = listOf("wsSettings", "httpupgradeSettings", "xhttpSettings", "splithttpSettings", "grpcSettings")

        /** Worth refreshing now: no fresh list for an hour and no attempt in the last minutes. */
        fun isStale(sub: Subscription, now: Long): Boolean {
            // A clock set back counts as old.
            val old = now - sub.updatedAt >= STALE_MS || sub.updatedAt > now
            val rested = now - sub.lastAttemptAt >= RETRY_MS || sub.lastAttemptAt > now
            return old && rested
        }

        /**
         * [fetched] applied to subscription [subId] in [state]. Servers that
         * are still there keep their ids, so the selection, ping results and
         * the running tunnel's identity survive. Without servers in
         * [fetched] only the subscription's details change.
         */
        fun merge(
            state: ProfilesState,
            subId: String,
            fetched: Fetched,
            now: Long,
            newId: () -> String = { UUID.randomUUID().toString() },
        ): ProfilesState {
            // Deleted while it was downloading: do not bring it back.
            val sub = state.subscriptions.firstOrNull { it.id == subId } ?: return state
            val applied = fetched.profiles.isNotEmpty()
            val updated = sub.copy(
                updatedAt = if (applied) now else sub.updatedAt,
                lastAttemptAt = now,
                lastError = null,
                notice = fetched.notice,
                announce = fetched.announce,
                supportUrl = fetched.supportUrl,
                userInfo = if (applied) fetched.userInfo else fetched.userInfo ?: sub.userInfo,
                // Like the traffic info: an answer without servers keeps what it does not bring.
                reportUrl = if (applied) fetched.reportUrl else fetched.reportUrl ?: sub.reportUrl,
                appUrl = if (applied) fetched.appUrl else fetched.appUrl ?: sub.appUrl,
            )
            val subscriptions = state.subscriptions.map { if (it.id == subId) updated else it }
            if (!applied) return state.copy(subscriptions = subscriptions)

            val old = state.profiles.filter { it.subscriptionId == subId }
            val matches = matchExisting(old, fetched.profiles)
            val stored = fetched.profiles.mapIndexed { i, p ->
                val match = matches[i]
                p.toStored(subId, id = match?.id ?: newId(), createdAt = match?.createdAt ?: now)
            }
            val others = state.profiles.filterNot { it.subscriptionId == subId }
            val all = others + stored
            val selectedId = if (all.any { it.id == state.selectedId }) {
                state.selectedId
            } else {
                // The selected server left the subscription, or none was selected.
                (stored.firstOrNull() ?: others.firstOrNull())?.id
            }
            return state.copy(profiles = all, subscriptions = subscriptions, selectedId = selectedId)
        }

        /** The error of a failed attempt; the servers stay as they are. */
        fun markFailed(state: ProfilesState, subId: String, error: String, now: Long): ProfilesState =
            state.copy(subscriptions = state.subscriptions.map { if (it.id == subId) it.copy(lastError = error, lastAttemptAt = now) else it })

        /** Whether [runningId], a server of [subId], has other outbounds in [after] or is gone. */
        fun runningChanged(before: ProfilesState, after: ProfilesState, subId: String, runningId: String?): Boolean {
            val old = before.profiles.firstOrNull { it.id == runningId && it.subscriptionId == subId } ?: return false
            val now = after.profiles.firstOrNull { it.id == old.id }
            return now == null || now.outbounds != old.outbounds
        }

        /**
         * For each fresh entry the existing server it replaces, or null. Keys
         * from strongest to weakest: the same link; the same endpoint
         * (protocol, address, port, transport, TLS name, path); the same
         * protocol, address and port. Each key is tried for every entry
         * before the next one, so a weak match never takes a server that a
         * stronger key gives to another entry. Remarks are not a key: panels
         * put days left and traffic into them.
         */
        internal fun matchExisting(old: List<StoredProfile>, fresh: List<ParsedProfile>): List<StoredProfile?> {
            val oldKeys = old.map { keys(it.link, it.protocol, it.address, it.port, it.network, it.security, it.outbounds) }
            val freshKeys = fresh.map { keys(it.link, it.protocol, it.address, it.port, it.network, it.security, it.outbounds) }
            val taken = BooleanArray(old.size)
            val result = arrayOfNulls<StoredProfile>(fresh.size)
            for (level in 0 until 3) {
                for (i in fresh.indices) {
                    if (result[i] != null) continue
                    val key = freshKeys[i][level] ?: continue
                    val j = old.indices.firstOrNull { !taken[it] && oldKeys[it][level] == key } ?: continue
                    taken[j] = true
                    result[i] = old[j]
                }
            }
            return result.toList()
        }

        private fun keys(
            link: String?,
            protocol: String,
            address: String,
            port: Int,
            network: String,
            security: String,
            outbounds: JsonArray,
        ): List<String?> {
            val host = address.lowercase(Locale.ROOT)
            return listOf(
                link?.takeIf { it.isNotBlank() },
                listOf(protocol, host, port, network, security, endpointDetails(outbounds)).joinToString("|"),
                listOf(protocol, host, port).joinToString("|"),
            )
        }

        /** TLS/REALITY server name, transport path (or gRPC service) and host header of the main outbound. */
        private fun endpointDetails(outbounds: JsonArray): String {
            val stream = (outbounds.firstOrNull() as? JsonObject)?.get("streamSettings") as? JsonObject ?: return ""
            val tls = stream["realitySettings"] as? JsonObject ?: stream["tlsSettings"] as? JsonObject
            val transport = TRANSPORTS.firstNotNullOfOrNull { stream[it] as? JsonObject }
            return listOf(
                tls?.text("serverName"),
                transport?.let { it.text("path") ?: it.text("serviceName") },
                transport?.text("host"),
            ).joinToString("|") { it.orEmpty() }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /** Outbounds of links whose certificate was pinned before. */

        private fun hostOf(url: String): String? = try {
            URI(url).host?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }

        private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Outbounds with a certificate pinned earlier, by link (without the
 * "#name": panels change names, e.g. "12 days left") and by endpoint.
 */
class Pinned private constructor(
    private val byLink: Map<String, JsonArray>,
    private val byEndpoint: Map<String, JsonArray>,
) {
    /** Same link: the same server settings, safe to reuse without contacting it. */
    fun sameLink(p: ParsedProfile): JsonArray? = p.link?.let { byLink[linkKey(it)] }

    /** The same server, maybe with other settings: only when it cannot be pinned now. */
    fun sameServer(p: ParsedProfile): JsonArray? = sameLink(p) ?: byEndpoint[endpointKey(p.protocol, p.address, p.port)]

    /**
     * These pins, but [sameLink] gives none for [link] (whatever its name),
     * so its certificate is fetched again; [sameServer] still has it.
     */
    fun without(link: String) = Pinned(byLink - linkKey(link), byEndpoint)

    companion object {
        val NONE = Pinned(emptyMap(), emptyMap())

        fun of(servers: List<StoredProfile>) = Pinned(
            servers.mapNotNull { p -> p.link?.let { linkKey(it) to p.outbounds } }.toMap(),
            servers.associate { p -> endpointKey(p.protocol, p.address, p.port) to p.outbounds },
        )

        private fun linkKey(link: String) = link.substringBefore('#')
        private fun endpointKey(protocol: String, address: String, port: Int) = "$protocol|${address.lowercase()}|$port"
    }
}
