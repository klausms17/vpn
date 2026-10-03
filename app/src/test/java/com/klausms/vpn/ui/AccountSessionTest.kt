package com.klausms.vpn.ui

import com.klausms.vpn.data.AccountApi
import com.klausms.vpn.data.AccountState
import com.klausms.vpn.data.AccountStatus
import com.klausms.vpn.data.AccountTransport
import com.klausms.vpn.data.HttpAnswer
import com.klausms.vpn.data.JsonFileStore
import com.klausms.vpn.data.ProfilesState
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.data.Subscription
import com.klausms.vpn.service.FakeProfiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AccountSessionTest {
    private val dir: File = Files.createTempDirectory("account").toFile()
    private var clock = 1_800_000_000_000L
    private val minute = 60_000L

    /** The accounts service: answers by the last part of the path, as the real one would. */
    private class Service : AccountTransport {
        val answers = HashMap<String, Pair<Int, String>>()
        val calls = mutableListOf<String>()
        var unreachable = false

        /** Holds every call until it is opened. */
        var gate: CountDownLatch? = null

        override fun send(method: String, url: String, token: String?, body: String?): HttpAnswer {
            val name = url.substringAfterLast('/')
            synchronized(calls) { calls += listOfNotNull(name, token).joinToString(" ") }
            gate?.await(10, TimeUnit.SECONDS)
            if (unreachable) throw IllegalStateException("sub.example: i/o timeout")
            val (status, text) = answers[name] ?: (404 to """{"error":"Нет такого запроса.","code":"not_found"}""")
            return HttpAnswer(status, text.toByteArray())
        }
    }

    private val service = Service()
    private val profiles = FakeProfiles()

    /** Adds a subscription with one server, as the updater would. */
    private inner class Servers : AccountServers {
        val added = mutableListOf<String>()
        val refreshed = mutableListOf<String>()
        var failure: Exception? = null

        override suspend fun add(url: String) {
            failure?.let { throw it }
            added += url
            val id = "sub${added.size}"
            profiles.state = profiles.state.let { st ->
                st.copy(
                    subscriptions = st.subscriptions + Subscription(id, "Kirov VPN", url),
                    profiles = st.profiles + server("$id-server", id),
                    selectedId = st.selectedId ?: "$id-server",
                )
            }
        }

        override suspend fun refresh(id: String) {
            refreshed += id
        }
    }

    private val servers = Servers()
    private val store = JsonFileStore(File(dir, "account.json"), AccountState.serializer()) { AccountState() }

    private fun session(withService: Boolean = true) = AccountSession(
        if (withService) AccountApi("https://sub.example", "Android Pixel 9", service) else null,
        store, profiles, servers, now = { clock }, io = Dispatchers.Unconfined,
    )

    private fun server(id: String, sub: String?) = StoredProfile(id = id, name = id, outbounds = JsonArray(emptyList()), subscriptionId = sub)

    private fun account(status: String, link: String? = LINK) =
        """{"email":"ivan@mail.ru","status":"$status"${link?.let { ""","subscriptionUrl":"$it"""" }.orEmpty()}}"""

    private fun signedIn(status: String, link: String? = LINK) = 200 to """{"token":"tok","account":${account(status, link)}}"""

    private fun me(status: String, link: String? = LINK) = 200 to """{"account":${account(status, link)}}"""

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aSignUpWaitsForTheEmailThenSignsIn() = runTest {
        val s = session()
        service.answers["register"] = 202 to OK
        s.register(" Ivan@Mail.RU ", "correct horse")
        // The screen for an unconfirmed address says where the letter went: no note.
        assertEquals(AccountView(available = true, email = "ivan@mail.ru", status = AccountStatus.UNCONFIRMED), s.view.value)

        service.answers["login"] = 403 to """{"error":"Сначала подтвердите почту.","code":"unconfirmed"}"""
        s.login("ivan@mail.ru", "correct horse")
        assertEquals(AccountStatus.UNCONFIRMED, s.view.value.status)
        assertEquals("Сначала подтвердите почту.", s.view.value.note)
        assertTrue(s.view.value.noteIsError)

        service.answers["resend"] = 202 to OK
        s.resend()
        assertEquals("Письмо отправлено ещё раз на ivan@mail.ru. Если его нет, загляните в папку «Спам».", s.view.value.note)

        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")
        assertEquals(AccountView(true, "ivan@mail.ru", AccountStatus.ACTIVE, note = "Вы вошли. Серверы аккаунта добавлены."), s.view.value)
        assertEquals(listOf(LINK), servers.added)
        assertEquals(listOf(Subscription("sub1", "Kirov VPN", LINK, account = true)), profiles.state.subscriptions)
        assertEquals(AccountState("ivan@mail.ru", "tok", AccountStatus.ACTIVE, "sub1", clock), store.read())
        assertEquals(listOf("register", "login", "resend", "login"), service.calls)
    }

    @Test
    fun accessGrantedLaterBringsTheServers() = runTest {
        val s = session()
        service.answers["login"] = signedIn("pending", link = null)
        s.login("ivan@mail.ru", "correct horse")
        assertEquals("Вы вошли.", s.view.value.note)
        assertEquals(AccountStatus.PENDING, s.view.value.status)

        clock += 4 * minute
        s.checkIfDue()
        assertEquals("not yet: every 5 minutes", listOf("login"), service.calls)

        clock += minute
        service.answers["me"] = me("active")
        s.checkIfDue()
        assertEquals(listOf("login", "me tok"), service.calls)
        assertEquals(AccountStatus.ACTIVE, s.view.value.status)
        assertEquals(listOf(LINK), servers.added)
        assertEquals("sub1", store.read().subscriptionId)

        clock += 30 * minute
        s.checkIfDue()
        assertEquals("then hourly", 2, service.calls.size)
    }

    @Test
    fun serversThatDidNotDownloadAreTriedAgainSoon() = runTest {
        val s = session()
        servers.failure = IllegalStateException("HTTP 404")
        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")
        assertEquals(
            "Вы вошли, но серверы аккаунта пока не загрузились: HTTP 404. Приложение попробует ещё раз само.",
            s.view.value.note,
        )
        assertFalse(s.view.value.noteIsError)
        assertNull(store.read().subscriptionId)

        servers.failure = null
        service.answers["me"] = me("active")
        clock += 5 * minute
        s.checkIfDue()
        assertEquals(listOf(LINK), servers.added)
        assertEquals("sub1", store.read().subscriptionId)
    }

    @Test
    fun theSameLinkAddedByHandBecomesTheAccounts() = runTest {
        profiles.state = ProfilesState(listOf(server("a", "mine")), listOf(Subscription("mine", "Друзья", LINK)), selectedId = "a")
        val s = session()
        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")
        assertEquals(emptyList<String>(), servers.added)
        assertEquals(listOf(Subscription("mine", "Друзья", LINK, account = true)), profiles.state.subscriptions)
        assertEquals("mine", store.read().subscriptionId)
    }

    @Test
    fun aNewLinkFromTheOwnerKeepsTheSubscription() = runTest {
        val s = session()
        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")

        service.answers["me"] = me("active", link = "https://sub.example/new")
        s.check()
        assertEquals(listOf(Subscription("sub1", "Kirov VPN", "https://sub.example/new", account = true)), profiles.state.subscriptions)
        assertEquals(listOf("sub1"), servers.refreshed)
        assertNull(s.view.value.note)
    }

    @Test
    fun aSessionEndedElsewhereSignsThisPhoneOut() = runTest {
        profiles.state = ProfilesState(listOf(server("own", null)), selectedId = "own")
        val s = session()
        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")
        assertEquals(2, profiles.state.profiles.size)

        service.answers["me"] = 401 to """{"error":"Вы вышли из аккаунта. Войдите снова.","code":"signed_out"}"""
        s.check()
        assertEquals(AccountView(available = true, note = "Вы вышли из аккаунта. Войдите снова."), s.view.value)
        assertEquals("the own key stays", listOf("own"), profiles.state.profiles.map { it.id })
        assertEquals(emptyList<Subscription>(), profiles.state.subscriptions)
        assertEquals(AccountState(), store.read())
    }

    @Test
    fun signingOutForgetsTheAccountEvenWhenTheServiceIsNotReached() = runTest {
        val s = session()
        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")

        service.unreachable = true
        s.logout()
        assertEquals(AccountView(available = true, note = "Вы вышли из аккаунта. Его серверы убраны с телефона."), s.view.value)
        assertEquals(ProfilesState(), profiles.state)
        assertEquals(AccountState(), store.read())
        assertEquals(listOf("login", "logout tok"), service.calls)
    }

    @Test
    fun anotherAddressAfterASignUpNeedsNoService() = runTest {
        val s = session()
        service.answers["register"] = 202 to OK
        s.register("ivan@mail.ru", "correct horse")
        s.logout()
        assertEquals(AccountView(available = true), s.view.value)
        assertEquals(listOf("register"), service.calls)
    }

    @Test
    fun deletingAsksForThePassword() = runTest {
        val s = session()
        service.answers["login"] = signedIn("active")
        s.login("ivan@mail.ru", "correct horse")

        s.delete("")
        assertEquals("Введите пароль", s.view.value.note)
        service.answers["delete"] = 401 to """{"error":"Неверный пароль.","code":"bad_login"}"""
        s.delete("wrong")
        assertEquals("Неверный пароль.", s.view.value.note)
        assertEquals(AccountStatus.ACTIVE, s.view.value.status)

        service.answers["delete"] = 200 to OK
        s.delete("correct horse")
        assertEquals(AccountView(available = true, note = "Аккаунт удалён, его серверы убраны с телефона."), s.view.value)
        assertEquals(ProfilesState(), profiles.state)
    }

    @Test
    fun whatWasTypedIsChecked() = runTest {
        val s = session()
        for ((email, password, note) in listOf(
            Triple(" ", "x", "Введите почту"),
            Triple("ivan@mail.ru", "", "Введите пароль"),
            Triple("a".repeat(255), "x", "Слишком длинная почта или пароль"),
            Triple("ivan@mail.ru", "x".repeat(1025), "Слишком длинная почта или пароль"),
        )) {
            s.register(email, password)
            assertEquals(note, s.view.value.note)
            assertTrue(s.view.value.noteIsError)
        }
        assertEquals(emptyList<String>(), service.calls)
    }

    @Test
    fun oneRequestAtATime() = runBlocking {
        val s = AccountSession(AccountApi("https://sub.example", "Android", service), store, profiles, servers, now = { clock })
        service.answers["login"] = signedIn("pending", link = null)
        service.gate = CountDownLatch(1)
        val first = launch(Dispatchers.Default) { s.login("ivan@mail.ru", "correct horse") }
        while (synchronized(service.calls) { service.calls.isEmpty() }) Thread.sleep(5)
        assertTrue(s.view.value.busy)
        s.register("masha@mail.ru", "correct horse")
        assertEquals("Подождите: предыдущий запрос ещё выполняется", s.view.value.note)
        service.gate!!.countDown()
        first.join()
        assertEquals(listOf("login"), service.calls)
        assertFalse(s.view.value.busy)
        assertEquals(AccountStatus.PENDING, s.view.value.status)
    }

    @Test
    fun aBuildWithoutTheServiceHasNoAccount() = runTest {
        val s = session(withService = false)
        s.login("ivan@mail.ru", "correct horse")
        s.checkIfDue()
        assertEquals(AccountView(), s.view.value)
        assertEquals(emptyList<String>(), service.calls)
    }

    private companion object {
        const val LINK = "https://sub.example/s/abc"
        const val OK = """{"ok":true}"""
    }
}
