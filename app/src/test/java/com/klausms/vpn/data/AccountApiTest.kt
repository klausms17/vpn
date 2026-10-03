package com.klausms.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccountApiTest {
    private class Call(val method: String, val url: String, val token: String?, val body: String?)

    /** Answers each call by the last part of its path; 404 for any other. */
    private class FakeService(val answers: Map<String, Pair<Int, String>>) : AccountTransport {
        val calls = mutableListOf<Call>()
        var unreachable = false

        override fun send(method: String, url: String, token: String?, body: String?): HttpAnswer {
            calls += Call(method, url, token, body)
            if (unreachable) throw IllegalStateException("sub.example: dial tcp: i/o timeout")
            val (status, text) = answers[url.substringAfterLast('/')] ?: (404 to """{"error":"Нет такого запроса.","code":"not_found"}""")
            return HttpAnswer(status, text.toByteArray())
        }
    }

    private fun api(service: FakeService) = AccountApi("https://sub.example/", "Android Pixel 9", service)

    private inline fun <reified E : Exception> refused(block: () -> Unit): E {
        try {
            block()
        } catch (e: Exception) {
            if (e is E) return e
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
        fail("no ${E::class.java.simpleName}")
        throw AssertionError()
    }

    @Test
    fun requestsAsTheServiceTakesThem() {
        val service = FakeService(
            mapOf(
                "register" to (202 to """{"ok":true}"""),
                "login" to (200 to """{"token":"tok","account":{"email":"ivan@mail.ru","status":"pending"}}"""),
                "me" to (200 to """{"account":{"email":"ivan@mail.ru","status":"active","subscriptionUrl":"https://sub.example/abc"}}"""),
                "logout" to (200 to """{"ok":true}"""),
                "delete" to (200 to """{"ok":true}"""),
            ),
        )
        val api = api(service)
        api.register("ivan@mail.ru", "correct horse")
        assertEquals("tok" to Account("ivan@mail.ru", AccountStatus.PENDING), api.login("ivan@mail.ru", "correct horse"))
        assertEquals(Account("ivan@mail.ru", AccountStatus.ACTIVE, "https://sub.example/abc"), api.me("tok"))
        api.logout("tok")
        api.delete("tok", "correct horse")

        val seen = service.calls.map { listOf(it.method, it.url, it.token, it.body) }
        assertEquals(
            listOf(
                listOf("POST", "https://sub.example/account/v1/register", null, """{"email":"ivan@mail.ru","password":"correct horse"}"""),
                listOf(
                    "POST", "https://sub.example/account/v1/login", null,
                    """{"email":"ivan@mail.ru","password":"correct horse","device":"Android Pixel 9"}""",
                ),
                listOf("GET", "https://sub.example/account/v1/me", "tok", null),
                listOf("POST", "https://sub.example/account/v1/logout", "tok", null),
                listOf("POST", "https://sub.example/account/v1/delete", "tok", """{"password":"correct horse"}"""),
            ),
            seen,
        )
    }

    @Test
    fun refusalsCarryTheServiceText() {
        val service = FakeService(
            mapOf(
                "register" to (429 to """{"error":"Слишком часто. Следующее письмо можно отправить через 2 минуты.","code":"too_often","retryAfter":120}"""),
                "login" to (403 to """{"error":"Сначала подтвердите почту: письмо отправлено на ivan@mail.ru.","code":"unconfirmed"}"""),
                "me" to (401 to """{"error":"Вы вышли из аккаунта. Войдите снова.","code":"signed_out"}"""),
                "forgot" to (502 to "<html><title>502 Bad Gateway</title></html>"),
                "resend" to (400 to """{"error":"Плохо\nи\u0007 длинно ${"я".repeat(400)}","code":"x"}"""),
            ),
        )
        val api = api(service)
        val often = refused<AccountRefusal> { api.register("ivan@mail.ru", "correct horse") }
        assertEquals("too_often", often.code)
        assertEquals(120, often.retryAfterSeconds)
        assertTrue(often.message!!.startsWith("Слишком часто"))
        val unconfirmed = refused<AccountRefusal> { api.login("ivan@mail.ru", "x") }
        assertTrue(unconfirmed.unconfirmed && !unconfirmed.signedOut)
        assertTrue(refused<AccountRefusal> { api.me("tok") }.signedOut)
        assertEquals(
            "Сервер аккаунтов не смог ответить (ошибка 502). Попробуйте позже.",
            refused<AccountRefusal> { api.forgot("ivan@mail.ru") }.message,
        )
        val odd = refused<AccountRefusal> { api.resend("ivan@mail.ru") }.message!!
        assertFalse(odd.any { it < ' ' })
        assertEquals(AccountApi.MAX_MESSAGE, odd.length)
    }

    @Test
    fun answersThatMakeNoSenseAreRefused() {
        for (body in listOf(
            "not json",
            """{"token":"","account":{"email":"ivan@mail.ru","status":"pending"}}""",
            """{"token":"tok","account":{"email":"ivan@mail.ru","status":"maybe"}}""",
            """{"token":"tok","account":{"email":"ivan@mail.ru","status":"active"}}""",
            """{"token":"tok","account":{"email":"ivan@mail.ru","status":"active","subscriptionUrl":"http://plain"}}""",
            """{"token":"tok","account":{"email":"","status":"pending"}}""",
        )) {
            val api = api(FakeService(mapOf("login" to (200 to body))))
            assertEquals(body, AccountApi.NONSENSE, refused<AccountUnavailable> { api.login("ivan@mail.ru", "x") }.message)
        }
    }

    @Test
    fun aServiceNotReachedSaysSoWithoutItsAddress() {
        val service = FakeService(emptyMap()).apply { unreachable = true }
        val e = refused<AccountUnavailable> { api(service).register("ivan@mail.ru", "x") }
        assertEquals(AccountApi.UNREACHABLE, e.message)
    }

    @Test
    fun theStateIsDueOftenWhileWaiting() {
        val min = 60_000L
        var s = AccountState(email = "ivan@mail.ru", token = "tok", status = AccountStatus.PENDING, checkedAt = 1000 * min)
        assertFalse("pending: every 5 minutes", s.due(1004 * min))
        assertTrue(s.due(1005 * min))
        s = s.copy(status = AccountStatus.ACTIVE)
        assertTrue("active without its servers here: every 5 minutes", s.due(1005 * min))
        s = s.copy(subscriptionId = "sub1")
        assertFalse("active: every hour", s.due(1059 * min))
        assertTrue(s.due(1060 * min))
        assertTrue("a clock set back makes it due", s.due(999 * min))
        assertFalse("signed out is never due", AccountState(status = AccountStatus.PENDING).due(Long.MAX_VALUE / 2))
        s = s.with(Account("ivan@mail.ru", AccountStatus.REJECTED), 2000 * min)
        assertEquals(AccountState("ivan@mail.ru", "tok", AccountStatus.REJECTED, "sub1", 2000 * min), s)
    }

    @Test
    fun anUnknownStatusInTheFileKeepsTheSession() {
        // A later version's state: the session stays, and the next check sets the status.
        val s = AppJson.decodeFromString(AccountState.serializer(), """{"email":"a@b.c","token":"t","status":"vip","future":1}""")
        assertEquals(AccountState(email = "a@b.c", token = "t"), s)
        assertTrue(s.due(AccountState.CHECK_EVERY_MS))
    }
}
