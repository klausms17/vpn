package com.klausms.vpn.ui

import com.klausms.vpn.core.userMessage
import com.klausms.vpn.data.AccountApi
import com.klausms.vpn.data.AccountRefusal
import com.klausms.vpn.data.AccountState
import com.klausms.vpn.data.AccountStatus
import com.klausms.vpn.data.AccountUnavailable
import com.klausms.vpn.data.JsonFileStore
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.data.withoutSubscription
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.Locale

/** What the account screen shows. */
data class AccountView(
    /** False in a build without an accounts service: no account anywhere. */
    val available: Boolean = false,
    val email: String = "",
    val status: AccountStatus = AccountStatus.SIGNED_OUT,
    /** A request to the service runs. */
    val busy: Boolean = false,
    /** What the last request did, or why it failed ([noteIsError]). */
    val note: String? = null,
    val noteIsError: Boolean = false,
)

/** How the account's link becomes servers: a subscription, added and refreshed like the others. */
interface AccountServers {
    /** Adds [url] as a subscription; throws when it cannot be used. */
    suspend fun add(url: String)

    /** Downloads subscription [id] again. */
    suspend fun refresh(id: String)
}

/**
 * This phone's account (docs/accounts/PLAN.md), for the UI process. The
 * session stays in [store]; once the owner grants access, the account's
 * link becomes a subscription marked [Subscription.account], which signing
 * out removes. One request to the service at a time: another one is
 * refused with a note rather than queued.
 *
 * Threading: any thread; the requests run on [io]. Only this class writes
 * [store], so its state is kept in memory once read.
 *
 * @param api the service; null in a build without one.
 */
class AccountSession(
    private val api: AccountApi?,
    private val store: JsonFileStore<AccountState>,
    private val profiles: ProfilesAccess,
    private val servers: AccountServers,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Volatile
    private var state = store.read()
    private val work = Mutex()

    private val _view = MutableStateFlow(AccountView(available = api != null, email = state.email, status = state.status))
    val view: StateFlow<AccountView> = _view.asStateFlow()

    /** Signs an address up. The screen for an unconfirmed address then says where the letter went. */
    suspend fun register(email: String, password: String) = request { api ->
        val address = typed(email, password)
        if (state.signedIn) throw Refused(SIGNED_IN)
        api.register(address, password)
        save(AccountState(email = address, status = AccountStatus.UNCONFIRMED))
        AppLog.i("account signed up, waiting for the email to be confirmed")
        null
    }

    /** Mails the confirmation link to the address signed up with again. */
    suspend fun resend() = request { api ->
        val address = state.email.ifEmpty { throw Refused(ENTER_EMAIL) }
        api.resend(address)
        "Письмо отправлено ещё раз на $address. Если его нет, загляните в папку «Спам»."
    }

    /** Mails a link to set a new password. */
    suspend fun forgot(email: String) = request { api ->
        val address = typed(email, password = null)
        api.forgot(address)
        "Мы отправили письмо со ссылкой для нового пароля на $address. Если его нет, загляните в папку «Спам»."
    }

    /** Signs this phone in; with access granted, the account's servers come at once. */
    suspend fun login(email: String, password: String) = request { api ->
        val address = typed(email, password)
        if (state.signedIn) throw Refused(SIGNED_IN)
        val (token, account) = try {
            api.login(address, password)
        } catch (e: AccountRefusal) {
            if (e.unconfirmed) save(AccountState(email = address, status = AccountStatus.UNCONFIRMED))
            throw e
        }
        val signedIn = save(AccountState(token = token).with(account, now()))
        AppLog.i("signed in to the account, ${account.status.name.lowercase(Locale.ROOT)}")
        val link = account.subscriptionUrl ?: return@request "Вы вошли."
        try {
            attach(signedIn, link)
            "Вы вошли. Серверы аккаунта добавлены."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Вы вошли, но серверы аккаунта пока не загрузились: ${e.userMessage()}. Приложение попробует ещё раз само."
        }
    }

    /** Signs this phone out, telling the service if it can; the account's servers go. */
    suspend fun logout() = request { api ->
        val was = state
        if (was.signedIn) {
            try {
                api.logout(was.token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                AppLog.w("the accounts service did not hear of the sign-out")
            }
        }
        forget("signed out of the account")
        if (was.signedIn) "Вы вышли из аккаунта. Его серверы убраны с телефона." else null
    }

    /** Deletes the account, its password asked again; its servers go. */
    suspend fun delete(password: String) = request { api ->
        if (password.isEmpty()) throw Refused(ENTER_PASSWORD)
        if (password.length > MAX_PASSWORD) throw Refused(TOO_LONG)
        if (!state.signedIn) throw Refused(NOT_SIGNED_IN)
        api.delete(state.token, password)
        forget("account deleted")
        "Аккаунт удалён, его серверы убраны с телефона."
    }

    /** «Проверить сейчас»: asks the service about the account now. */
    suspend fun check() = request { api ->
        if (!state.signedIn) throw Refused(NOT_SIGNED_IN)
        refresh(api)
    }

    /** Asks the service about the account when it is time ([AccountState.due]), quietly. */
    suspend fun checkIfDue() {
        val api = api ?: return
        if (!state.due(now()) || !work.tryLock()) return
        try {
            withContext(io) { refresh(api) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("account not checked: ${reason(e)}")
        } finally {
            work.unlock()
        }
    }

    /**
     * Runs one request to the service: refused while another runs. What it
     * returns, or why it failed, becomes the note.
     */
    private suspend fun request(block: suspend (AccountApi) -> String?) {
        val api = api ?: return
        if (!work.tryLock()) {
            _view.update { it.copy(note = BUSY, noteIsError = true) }
            return
        }
        _view.update { it.copy(busy = true, note = null, noteIsError = false) }
        var note: String? = null
        var failed = false
        try {
            note = withContext(io) { block(api) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            note = when (e) {
                is AccountRefusal, is AccountUnavailable, is Refused -> e.message
                else -> {
                    // Only the type: a download error names the panel's host.
                    AppLog.w("account request failed: ${e.javaClass.simpleName}")
                    e.userMessage()
                }
            }
        } finally {
            work.unlock()
            _view.update { it.copy(busy = false, note = note, noteIsError = failed) }
        }
    }

    /**
     * Asks the service about the account and follows it: the servers come
     * once access is granted, and a session that is gone signs this phone
     * out. Returns the note for «Проверить сейчас».
     */
    private suspend fun refresh(api: AccountApi): String? {
        val account = try {
            api.me(state.token)
        } catch (e: AccountRefusal) {
            if (!e.signedOut) throw e
            forget("signed out by the accounts service")
            return e.message
        }
        val checked = save(state.with(account, now()))
        account.subscriptionUrl?.let { attach(checked, it) }
        return null
    }

    /** Makes [link] the account's subscription and keeps its id, so that a missing one is tried again soon. */
    private suspend fun attach(s: AccountState, link: String) {
        val id = attachedId(link)
        if (id != s.subscriptionId) save(s.copy(subscriptionId = id))
    }

    /** The account's subscription for [link]: the one it is already, the same link added by hand, or a new one. */
    private suspend fun attachedId(link: String): String {
        val subs = profiles.snapshot().subscriptions
        val mine = subs.firstOrNull { it.account }
        if (mine != null) {
            if (mine.url == link) return mine.id
            // The owner gave the account a new link: the servers follow it.
            profiles.updateProfiles { st -> st.marked({ it.id == mine.id }, link) }
            AppLog.i("the account's subscription got a new link")
            servers.refresh(mine.id)
            return mine.id
        }
        if (subs.none { it.url == link }) servers.add(link)
        val saved = profiles.updateProfiles { st -> st.marked({ it.url == link }, link) }
        // Deleted while it was downloading: tried again at the next check.
        return saved.subscriptions.firstOrNull { it.url == link }?.id ?: throw IllegalStateException("Подписку аккаунта удалили")
    }

    /** Signs this phone out: the account's servers and the state go. */
    private suspend fun forget(why: String) {
        profiles.updateProfiles { st -> st.subscriptions.filter { it.account }.fold(st) { s, sub -> s.withoutSubscription(sub.id) } }
        save(AccountState())
        AppLog.i(why)
    }

    private suspend fun save(s: AccountState): AccountState {
        val saved = withContext(io) { store.update { s } }
        state = saved
        _view.update { v ->
            // Another state starts without the note of the one before.
            val same = v.status == saved.status && v.email == saved.email
            v.copy(email = saved.email, status = saved.status, note = v.note.takeIf { same }, noteIsError = same && v.noteIsError)
        }
        return saved
    }

    /** The address as the service keys it, once what was typed is checked; [password] null when none is asked. */
    private fun typed(email: String, password: String?): String {
        val address = email.trim()
        when {
            address.isEmpty() -> throw Refused(ENTER_EMAIL)
            address.length > MAX_EMAIL || (password?.length ?: 0) > MAX_PASSWORD -> throw Refused(TOO_LONG)
            password?.isEmpty() == true -> throw Refused(ENTER_PASSWORD)
        }
        return address.lowercase(Locale.ROOT)
    }

    private fun reason(e: Exception): String = when (e) {
        is AccountRefusal -> "refused (${e.code})"
        is AccountUnavailable -> "no answer"
        else -> e.javaClass.simpleName
    }

    /** What was typed, or the moment, does not allow the request. */
    private class Refused(message: String) : Exception(message)

    private companion object {
        // An address of RFC 5321; more than the service takes as a password, so that it says what is wrong.
        const val MAX_EMAIL = 254
        const val MAX_PASSWORD = 1024

        const val BUSY = "Подождите: предыдущий запрос ещё выполняется"
        const val SIGNED_IN = "Вы уже вошли в аккаунт. Чтобы войти в другой, сначала выйдите"
        const val NOT_SIGNED_IN = "Вы не вошли в аккаунт"
        const val ENTER_EMAIL = "Введите почту"
        const val ENTER_PASSWORD = "Введите пароль"
        const val TOO_LONG = "Слишком длинная почта или пароль"
    }
}

/** The subscriptions [which] picks, marked as the account's, with [link]. */
private fun ProfilesState.marked(which: (Subscription) -> Boolean, link: String): ProfilesState =
    copy(subscriptions = subscriptions.map { if (which(it)) it.copy(account = true, url = link) else it })
