package com.klausms.vpn.core

import com.klausms.vpn.data.AccountTransport
import com.klausms.vpn.data.HttpAnswer
import com.klausms.vpn.util.AppLog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Requests to the accounts service, sent as subscriptions are downloaded:
 * directly, then through the [selected] server (a temporary core), but
 * only when the service was not reached: a request it answered, even with
 * a refusal, is never sent twice.
 */
class CoreAccountTransport(private val selected: () -> JsonArray?) : AccountTransport {
    override fun send(method: String, url: String, token: String?, body: String?): HttpAnswer {
        val headers = token?.let { buildJsonObject { put("Authorization", "Bearer $it") }.toString() }.orEmpty()
        val bytes = body?.toByteArray()
        val reply = try {
            XrayCore.request(method, url, headers, bytes, via = null)
        } catch (direct: Exception) {
            val via = selected() ?: throw direct
            // Without the error: it names the service's host.
            AppLog.w("accounts service not reached directly, trying through the selected server")
            XrayCore.request(method, url, headers, bytes, via)
        }
        return HttpAnswer(reply.status, reply.body ?: ByteArray(0))
    }
}
