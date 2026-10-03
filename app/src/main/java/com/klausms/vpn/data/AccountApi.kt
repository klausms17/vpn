package com.klausms.vpn.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Where an account stands (docs/accounts/PLAN.md). The service never
 * reports [UNCONFIRMED]: the app keeps it between a sign-up and a sign-in.
 */
@Serializable
enum class AccountStatus {
    @SerialName("signed_out") SIGNED_OUT,
    @SerialName("unconfirmed") UNCONFIRMED,
    @SerialName("pending") PENDING,
    @SerialName("active") ACTIVE,
    @SerialName("rejected") REJECTED,
}

/** What the service says about the account signed in; [subscriptionUrl] once access is granted. */
data class Account(val email: String, val status: AccountStatus, val subscriptionUrl: String? = null)

/** An answer of the service, whatever its status. */
class HttpAnswer(val status: Int, val body: ByteArray)

/**
 * Sends one request to the accounts service and returns its answer.
 * Blocking; throws when the service was not reached. [token] is the
 * session, sent as a bearer token; [body] is JSON, or null.
 */
fun interface AccountTransport {
    fun send(method: String, url: String, token: String?, body: String?): HttpAnswer
}

/** The service refused; the message is its text for the user. */
class AccountRefusal(val code: String, message: String, val retryAfterSeconds: Int = 0) : Exception(message) {
    /** The session is gone (signed out elsewhere, a new password, the account deleted). */
    val signedOut: Boolean get() = code == "signed_out"

    /** A sign-in waits for the email to be confirmed. */
    val unconfirmed: Boolean get() = code == "unconfirmed"
}

/** The service was not reached, or its answer made no sense. */
class AccountUnavailable(message: String) : Exception(message)

/**
 * The accounts service's API (server/remnawave/klaus-accounts.py), spoken
 * as libxray/client/account speaks it for Windows. Blocking: call on IO.
 * [base] is the service, such as https://sub.example; [device] names this
 * phone in the account's sessions.
 */
class AccountApi(base: String, private val device: String, private val transport: AccountTransport) {
    private val root = base.trimEnd('/') + "/account/v1/"

    /** Signs an address up; the service mails it a link. The answer is the same whether the address was free. */
    fun register(email: String, password: String) {
        call("POST", "register", body("email" to email, "password" to password))
    }

    /** Mails the confirmation link again. */
    fun resend(email: String) {
        call("POST", "resend", body("email" to email))
    }

    /** Mails a link to set a new password. */
    fun forgot(email: String) {
        call("POST", "forgot", body("email" to email))
    }

    /** Signs this phone in: the session token and the account. */
    fun login(email: String, password: String): Pair<String, Account> {
        val answer = call("POST", "login", body("email" to email, "password" to password, "device" to device))
        val token = answer?.text("token")
        val account = answer?.account()
        if (token.isNullOrEmpty() || account == null) throw AccountUnavailable(NONSENSE)
        return token to account
    }

    /** The account session [token] belongs to. */
    fun me(token: String): Account = call("GET", "me", token = token)?.account() ?: throw AccountUnavailable(NONSENSE)

    fun logout(token: String) {
        call("POST", "logout", token = token)
    }

    /** Deletes the account; the password is asked again. */
    fun delete(token: String, password: String) {
        call("POST", "delete", body("password" to password), token)
    }

    /** The answer's JSON object (null when there is none); throws the service's refusal. */
    private fun call(method: String, name: String, body: String? = null, token: String? = null): JsonObject? {
        val answer = try {
            transport.send(method, root + name, token, body)
        } catch (_: Exception) {
            throw AccountUnavailable(UNREACHABLE)
        }
        val json = try {
            AppJson.parseToJsonElement(answer.body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }
        if (answer.status in 200..299) return json
        val text = json?.text("error")
        if (json == null || text.isNullOrEmpty()) {
            // Not the service's own answer: a proxy's error page, a server error.
            throw AccountRefusal("", "Сервер аккаунтов не смог ответить (ошибка ${answer.status}). Попробуйте позже.")
        }
        val retryAfter = (json["retryAfter"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it in 1..86_400 } ?: 0
        throw AccountRefusal(clip(json.text("code").orEmpty(), 40), clip(text, MAX_MESSAGE), retryAfter)
    }

    private fun JsonObject.account(): Account? {
        val a = this["account"] as? JsonObject ?: return null
        val email = a.text("email").orEmpty()
        val url = httpsUrl(a.text("subscriptionUrl"))
        val status = when (a.text("status")) {
            "pending" -> AccountStatus.PENDING
            "rejected" -> AccountStatus.REJECTED
            "active" -> AccountStatus.ACTIVE.takeIf { url != null }
            else -> null
        }
        return if (email.isEmpty() || status == null) null else Account(email, status, url.takeIf { status == AccountStatus.ACTIVE })
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun body(vararg fields: Pair<String, String>): String = buildJsonObject {
        for ((key, value) in fields) put(key, value)
    }.toString()

    companion object {
        /** The service's text shown to the user is at most this long. */
        const val MAX_MESSAGE = 300

        const val UNREACHABLE = "Сервер аккаунтов не отвечает. Проверьте интернет и попробуйте ещё раз."
        const val NONSENSE = "Сервер аккаунтов ответил непонятно. Попробуйте позже."

        /** [text] without control characters, at most [max] characters. */
        internal fun clip(text: String, max: Int): String {
            val kept = text.codePoints().filter { it >= 0x20 && it != 0x7f && it != 0xfffd }.limit(max.toLong()).toArray()
            return String(kept, 0, kept.size)
        }
    }
}
