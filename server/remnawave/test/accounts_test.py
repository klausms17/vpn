#!/usr/bin/env python3
"""Unit tests of klaus-accounts: the flows of docs/accounts/PLAN.md, the
limits, the pages and the HTTP layer, without Docker, a panel, Telegram or
a mail server.

  python3 server/remnawave/test/accounts_test.py

Each test loads a fresh copy of the service with a temporary database, a
fake clock and fakes for the owner's Telegram, the panel and mail. CI runs
this on every push; run-local.sh runs it first, then the same service for
real.
"""

import base64
import contextlib
import hashlib
import http.client
import importlib.util
import io
import json
import os
import re
import shutil
import tempfile
import threading
import unittest
import urllib.parse

SERVICE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "klaus-accounts.py")
PUBLIC = "https://sub.example"


def load():
    spec = importlib.util.spec_from_file_location("klaus_accounts", SERVICE)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


class Clock:
    def __init__(self):
        self.t = 1_800_000_000.0

    def __call__(self):
        return self.t


class Owner:
    configured = True

    def __init__(self):
        self.asked, self.settled, self.told = [], [], []

    def ask(self, address, approve_url, reject_url):
        self.asked.append((address, approve_url, reject_url))
        return 100 + len(self.asked)

    def settle(self, message_id, text):
        self.settled.append((message_id, text))

    def tell(self, text):
        self.told.append(text)


class Panel:
    def __init__(self, m):
        self.m = m
        self.created, self.deleted = [], []
        self.fail = False

    def create(self, address):
        if self.fail:
            raise self.m.PanelError("panel API: HTTP 500")
        self.created.append(address)
        n = len(self.created)
        return "u%d" % n, "friend%d-abcd" % n, "https://sub.example/short%d" % n

    def delete(self, user_id):
        self.deleted.append(user_id)


class AccountsTest(unittest.TestCase):
    def setUp(self):
        m = load()
        self.logged = []
        m.log = self.logged.append
        # Cheap hashes: one test checks the real parameters.
        m.SCRYPT = (10, 8, 1)
        self.dir = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dir)
        self.clock = Clock()
        self.db = m.Db(os.path.join(self.dir, "accounts.db"))
        self.owner = Owner()
        self.panel = Panel(m)
        self.mailer = m.Mailer(self.db, self.owner, deliver=None, now=self.clock)
        self.mailer.configured = True
        self.app = m.Accounts(self.db, self.mailer, self.owner, self.panel, public_url=PUBLIC, now=self.clock)
        self.m = m

    # -- helpers

    def letters(self):
        """The letters queued since the last call: [(to, subject, text, html)]."""
        out = []
        while not self.mailer.queue.empty():
            to, (subject, text, body) = self.mailer.queue.get_nowait()
            out.append((to, subject, text, body))
        return out

    def one_letter(self, to):
        letters = self.letters()
        self.assertEqual(1, len(letters), letters)
        self.assertEqual(to, letters[0][0])
        return letters[0]

    def token_in(self, mail, page):
        links = re.findall(r"https://\S+", mail[2])
        self.assertEqual(1, len(links), mail[2])
        url = urllib.parse.urlsplit(links[0])
        self.assertEqual("/account/" + page, url.path)
        return urllib.parse.parse_qs(url.query)["t"][0]

    def later(self, minutes):
        self.clock.t += minutes * 60

    def register(self, address="ivan@mail.ru", password="correct horse"):
        status, body = self.app.register(address, password)
        self.assertEqual(202, status, body)
        return self.token_in(self.one_letter(address.strip().lower()), "confirm")

    def active(self, address="ivan@mail.ru", password="correct horse"):
        """Registers, confirms and approves; -> the decision token used."""
        self.assertEqual("confirmed", self.app.confirm(self.register(address, password)))
        asked = self.owner.asked[-1]
        token = urllib.parse.parse_qs(urllib.parse.urlsplit(asked[1]).query)["t"][0]
        ok, text = self.app.approve(self.app.decide_row(token))
        self.assertTrue(ok, text)
        self.assertEqual("Доступ к Kirov VPN открыт", self.one_letter(address)[1])
        self.later(3)  # past the gap between letters to one address
        return token

    def login(self, address="ivan@mail.ru", password="correct horse", device="Pixel 9"):
        return self.app.login(address, password, device)

    # -- the whole way

    def test_register_confirm_approve_and_sign_in(self):
        token = self.register("  Ivan@Mail.RU ")
        row = self.app.token_row(token, "confirm")
        self.assertEqual("ivan@mail.ru", row["email"])
        self.assertIn("ivan@mail.ru", self.m.confirm_page(row, token))
        self.assertEqual(403, self.login()[0])

        self.assertEqual("confirmed", self.app.confirm(token))
        self.assertEqual("expired", self.app.confirm(token))
        address, approve_url, reject_url = self.owner.asked[0]
        self.assertEqual("ivan@mail.ru", address)
        self.assertTrue(approve_url.startswith(PUBLIC + "/account/decide?t=") and approve_url.endswith("&a=approve"))
        self.assertTrue(reject_url.endswith("&a=reject"))

        status, body = self.login()
        self.assertEqual(200, status, body)
        self.assertEqual({"email": "ivan@mail.ru", "status": "pending"}, body["account"])
        session = body["token"]
        self.assertRegex(session, r"^[A-Za-z0-9_-]{43}$")

        decide = urllib.parse.parse_qs(urllib.parse.urlsplit(approve_url).query)["t"][0]
        ok, text = self.app.approve(self.app.decide_row(decide))
        self.assertTrue(ok)
        self.assertEqual("Доступ выдан: ivan@mail.ru (пользователь friend1-abcd).", text)
        self.assertEqual(["ivan@mail.ru"], self.panel.created)
        self.assertEqual((101, "Kirov VPN: доступ выдан ✅\nivan@mail.ru"), self.owner.settled[0])
        self.assertEqual("Доступ к Kirov VPN открыт", self.one_letter("ivan@mail.ru")[1])
        self.assertIsNone(self.app.decide_row(decide), "the owner's buttons work once")

        status, body = self.app.me(session)
        self.assertEqual(200, status)
        self.assertEqual({"email": "ivan@mail.ru", "status": "active",
                          "subscriptionUrl": "https://sub.example/short1"}, body["account"])
        # Another device gets the same link.
        self.assertEqual("https://sub.example/short1", self.login(device="ПК")[1]["account"]["subscriptionUrl"])

        self.assertEqual(200, self.app.logout(session)[0])
        self.assertEqual((401, "signed_out"), (self.app.me(session)[0], self.app.me(session)[1]["code"]))

    def test_nothing_tells_whether_an_address_has_an_account(self):
        self.active()
        # Registering a taken address mails its owner instead.
        status, body = self.app.register("ivan@mail.ru", "another password")
        self.assertEqual((202, {"ok": True}), (status, body))
        self.assertEqual("Вход в Kirov VPN", self.one_letter("ivan@mail.ru")[1])
        self.assertEqual(1, self.db.one("SELECT COUNT(*) AS n FROM accounts")["n"])
        # Unknown addresses: the same answers, and no letter.
        for call in (self.app.forgot, self.app.resend):
            self.assertEqual((202, {"ok": True}), call("nobody@mail.ru"))
            self.later(3)
        self.assertEqual([], self.letters())
        self.assertEqual(self.login("nobody@mail.ru"), self.login(password="wrong password"))

    def test_a_strangers_later_registration_cannot_take_the_account(self):
        mine = self.register(password="my own password")
        self.later(3)
        self.register(password="stranger password")
        self.assertEqual("confirmed", self.app.confirm(mine))
        self.assertEqual(401, self.login(password="stranger password")[0])
        self.assertEqual(200, self.login(password="my own password")[0])

    def test_the_latest_confirmation_link_works_too(self):
        self.register(password="first password")
        self.later(3)
        second = self.register(password="second password")
        self.assertEqual("confirmed", self.app.confirm(second))
        self.assertEqual(200, self.login(password="second password")[0])

    def test_wrong_passwords_make_an_address_wait(self):
        self.active()
        for address in ("ivan@mail.ru", "nobody@mail.ru"):
            for _ in range(5):
                self.assertEqual(401, self.login(address, "wrong password")[0])
            status, body = self.login(address, "wrong password")
            self.assertEqual((429, "locked", 61), (status, body["code"], body["retryAfter"]), body)
            self.assertIn("через 2 минуты", body["error"])
        self.assertEqual(429, self.login()[0], "the right password waits too")
        self.later(1.1)
        self.assertEqual(200, self.login()[0])
        # A success starts the count again.
        for _ in range(5):
            self.assertEqual(401, self.login(password="wrong password")[0])

    def test_letters_to_one_address_are_spaced_and_counted(self):
        self.register()
        status, body = self.app.resend("ivan@mail.ru")
        self.assertEqual((429, "too_often"), (status, body["code"]))
        self.assertEqual(121, body["retryAfter"])
        for _ in range(4):
            self.later(3)
            self.assertEqual(202, self.app.resend("ivan@mail.ru")[0])
        self.later(3)
        status, body = self.app.resend("ivan@mail.ru")
        self.assertEqual(429, status, "five letters a day")
        self.assertEqual(4, len(self.letters()), "after the first")
        self.later(24 * 60)
        self.assertEqual(202, self.app.resend("ivan@mail.ru")[0])

    def test_the_days_letters_run_out(self):
        self.m.MAIL_DAILY_LIMIT = 3
        for i in range(3):
            self.assertEqual(202, self.app.register("user%d@mail.ru" % i, "correct horse")[0])
        status, body = self.app.register("user9@mail.ru", "correct horse")
        self.assertEqual((503, "mail_off"), (status, body["code"]))
        self.assertEqual(1, len(self.owner.told))
        self.assertIn("письма на сегодня закончились", self.owner.told[0])
        self.later(24 * 60)
        self.assertEqual(202, self.app.register("user9@mail.ru", "correct horse")[0])
        self.assertEqual(1, len(self.owner.told), "told once a day")

    def test_without_mail_registration_is_refused(self):
        self.mailer.configured = False
        status, body = self.app.register("ivan@mail.ru", "correct horse")
        self.assertEqual((503, "mail_off"), (status, body["code"]))
        self.assertIsNone(self.db.one("SELECT * FROM accounts"))

    def test_bad_input(self):
        self.assertEqual("bad_email", self.app.register("ivan", "correct horse")[1]["code"])
        status, body = self.app.register("ivan@mail.ru", "1234567")
        self.assertEqual((400, "weak_password"), (status, body["code"]))
        self.assertEqual("Пароль должен быть не короче 8 символов.", body["error"])
        self.assertEqual("weak_password", self.app.register("ivan@mail.ru", "ivan@mail.ru")[1]["code"])
        self.assertEqual("weak_password", self.app.register("ivan@mail.ru", None)[1]["code"])
        self.assertEqual(401, self.login(password=None)[0])
        self.assertEqual(401, self.login(password="x" * 2000)[0])
        self.assertEqual("bad_email", self.app.forgot(["ivan@mail.ru"])[1]["code"])
        self.assertEqual([], self.letters())

    def test_email_and_password_rules(self):
        norm = self.m.normalize_email
        self.assertEqual("ivan.petrov@mail.ru", norm(" Ivan.Petrov@Mail.RU "))
        self.assertEqual("o'neil@example.co", norm("o'neil@example.co"))
        self.assertEqual("user@xn--80ak6aa92e.com", norm("user@xn--80ak6aa92e.com"))
        for bad in ("", "plain", "@mail.ru", "user@", "user@mail", "a@@b.ru", "user@mail.r", ".u@mail.ru",
                    "u.@mail.ru", "u..v@mail.ru", "user@-mail.ru", "user@mail..ru", "us er@mail.ru",
                    "user@mail.ru\r\nBcc: x@y.z", "иван@mail.ru", "user@почта.рф", "Kate@mail.ru",
                    "user@mail.r1", "a" * 65 + "@mail.ru", "a@" + "b" * 250 + ".ru", None, 42):
            self.assertIsNone(norm(bad), bad)
        problem = self.m.password_problem
        for good in ("correct horse", "Kirov-2026!", "пароль из слов"):
            self.assertEqual("", problem(good, "ivan@mail.ru"))
        for bad in ("short", "12345678", "Password", "ЙЦУКЕНГШ", "ivan", "IVAN@mail.ru", "x" * 129, "tab\there"):
            self.assertNotEqual("", problem(bad, "ivan@mail.ru"), bad)

    def test_password_reset(self):
        self.active()
        session = self.login()[1]["token"]
        self.assertEqual((202, {"ok": True}), self.app.forgot("ivan@mail.ru"))
        mail = self.one_letter("ivan@mail.ru")
        self.assertEqual("Новый пароль для Kirov VPN", mail[1])
        token = self.token_in(mail, "reset")
        self.assertEqual(("error", "Пароли не совпадают."), self.app.reset(token, "new password one", "new password two"))
        self.assertEqual("error", self.app.reset(token, "12345678", "12345678")[0])
        self.assertEqual(("done", ""), self.app.reset(token, "new password one", "new password one"))
        self.assertEqual(("expired", ""), self.app.reset(token, "new password one", "new password one"))
        self.assertEqual(401, self.app.me(session)[0], "a reset signs every device out")
        self.assertEqual(401, self.login()[0])
        self.assertEqual(200, self.login(password="new password one")[0])
        # A reset link lasts an hour.
        self.later(3)
        self.app.forgot("ivan@mail.ru")
        token = self.token_in(self.one_letter("ivan@mail.ru"), "reset")
        self.later(61)
        self.assertEqual("expired", self.app.reset(token, "third password", "third password")[0])

    def test_a_reset_confirms_an_unconfirmed_address(self):
        self.register()
        self.later(3)
        self.app.forgot("ivan@mail.ru")
        token = self.token_in(self.one_letter("ivan@mail.ru"), "reset")
        self.assertEqual("done", self.app.reset(token, "chosen anew", "chosen anew")[0])
        self.assertEqual("pending", self.db.one("SELECT status FROM accounts")["status"])
        self.assertEqual("ivan@mail.ru", self.owner.asked[0][0])
        self.assertEqual(200, self.login(password="chosen anew")[0])

    def test_deleting_the_account(self):
        self.active()
        session = self.login()[1]["token"]
        status, body = self.app.delete(session, "wrong password")
        self.assertEqual((401, "bad_login"), (status, body["code"]))
        self.assertEqual((200, {"ok": True}), self.app.delete(session, "correct horse"))
        self.assertEqual(["u1"], self.panel.deleted)
        self.assertIsNone(self.db.one("SELECT * FROM accounts"))
        self.assertIsNone(self.db.one("SELECT * FROM sessions"))
        self.assertIn("удалён по просьбе пользователя\nivan@mail.ru", self.owner.told[0])
        self.assertEqual(401, self.app.me(session)[0])

    def test_a_linked_panel_user_stays_when_the_account_goes(self):
        self.assertEqual("confirmed", self.app.confirm(self.register()))
        row = self.db.one("SELECT * FROM accounts")
        ok, text = self.app.link_user(row, "77", "masha_k", "https://sub.example/masha")
        self.assertTrue(ok, text)
        session = self.login()[1]["token"]
        self.assertEqual("https://sub.example/masha", self.app.me(session)[1]["account"]["subscriptionUrl"])
        self.assertEqual(200, self.app.delete(session, "correct horse")[0])
        self.assertEqual([], self.panel.deleted)

    def test_when_the_panel_fails_the_decision_stays_open(self):
        self.assertEqual("confirmed", self.app.confirm(self.register()))
        token = urllib.parse.parse_qs(urllib.parse.urlsplit(self.owner.asked[0][1]).query)["t"][0]
        self.panel.fail = True
        ok, text = self.app.approve(self.app.decide_row(token))
        self.assertFalse(ok)
        self.assertIn("Попробуйте ещё раз", text)
        self.assertEqual("pending", self.db.one("SELECT status FROM accounts")["status"])
        self.panel.fail = False
        self.assertTrue(self.app.approve(self.app.decide_row(token))[0])

    def test_rejected_and_forgotten_accounts_go(self):
        self.assertEqual("confirmed", self.app.confirm(self.register()))
        token = urllib.parse.parse_qs(urllib.parse.urlsplit(self.owner.asked[0][2]).query)["t"][0]
        self.assertEqual((True, "Отклонено: ivan@mail.ru."), self.app.reject(self.app.decide_row(token)))
        self.assertEqual("rejected", self.login()[1]["account"]["status"])
        self.assertEqual("Kirov VPN: отклонено ❌\nivan@mail.ru", self.owner.settled[0][1])
        self.register("masha@mail.ru")  # never confirmed
        self.later(2 * 24 * 60 + 1)
        self.app.purge()
        self.assertEqual(["ivan@mail.ru"], [r["email"] for r in self.db.all("SELECT email FROM accounts")])
        self.later(30 * 24 * 60)
        self.app.purge()
        self.assertIsNone(self.db.one("SELECT * FROM accounts"))

    def test_idle_sessions_and_old_tokens_go(self):
        self.active()
        session = self.login()[1]["token"]
        self.app.forgot("ivan@mail.ru")
        self.later(179 * 24 * 60)
        self.assertEqual(200, self.app.me(session)[0])  # used: kept
        self.later(179 * 24 * 60)
        self.app.purge()
        self.assertEqual(200, self.app.me(session)[0])
        self.assertIsNone(self.db.one("SELECT * FROM tokens"))
        self.later(181 * 24 * 60)
        self.app.purge()
        self.assertEqual(401, self.app.me(session)[0])

    def test_the_log_holds_no_address_token_or_link(self):
        self.active()
        session = self.login()[1]["token"]
        self.app.forgot("ivan@mail.ru")
        self.app.delete(session, "correct horse")
        text = "\n".join(self.logged)
        self.assertTrue(self.logged)
        for secret in ("ivan", "mail.ru", session, "short1", "sub.example"):
            self.assertNotIn(secret, text)

    def test_the_real_password_hash(self):
        m = load()
        stored = m.hash_password("correct horse")
        self.assertRegex(stored, r"^scrypt\$15\$8\$3\$[A-Za-z0-9+/]{22}\$[A-Za-z0-9+/]{43}$")
        self.assertEqual((True, False), m.check_password(stored, "correct horse"))
        self.assertEqual((False, False), m.check_password(stored, "correct horsE"))
        # Older, weaker hashes still work and are made again.
        self.assertEqual((True, True), m.check_password(self.m.hash_password("x" * 8), "x" * 8))
        for bad in ("", "plain", "scrypt$15$8$3$!!$AAAA", "scrypt$30$8$1$" + "A" * 22 + "$" + "A" * 43,
                    "bcrypt$15$8$3$" + "A" * 22 + "$" + "A" * 43):
            self.assertEqual((False, False), m.check_password(bad, "x"), bad)

    def test_owner_commands(self):
        self.assertEqual("confirmed", self.app.confirm(self.register()))
        m = self.m
        m.DATA_DIR = self.dir
        m.Telegram = lambda: self.owner
        m.Panel = lambda: self.panel

        def cli(*args):
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                m.cli(list(args))
            return out.getvalue()

        listing = cli("list")
        self.assertIn("ivan@mail.ru\tждёт решения\t0\t-\t", listing)
        self.assertIn("Всего: 1, ждут решения: 1", listing)
        self.assertEqual("Доступ выдан: ivan@mail.ru (пользователь friend1-abcd).\n", cli("approve", "Ivan@mail.ru"))
        self.assertEqual("ivan@mail.ru\n", cli("owner-of", "u1"))
        self.assertEqual("", cli("owner-of", "u2"))
        copy = cli("backup", "../backup.db").strip()
        self.assertEqual(os.path.join(self.dir, "backup.db"), copy)
        self.assertEqual("active", m.Db(copy).one("SELECT status FROM accounts")["status"])
        with self.assertRaises(SystemExit):
            cli("approve", "nobody@mail.ru")
        with self.assertRaises(SystemExit):
            cli("link", "ivan@mail.ru", "1", "bad name!", "https://x")
        self.assertIn("удалён, и пользователь панели friend1-abcd тоже", cli("delete", "ivan@mail.ru"))
        self.assertEqual(["u1"], self.panel.deleted)

    # -- pages

    def test_pages_escape_what_they_show(self):
        token = self.register("o'neil@mail.ru")
        page = self.m.confirm_page(self.app.token_row(token, "confirm"), token)
        self.assertIn("o&#x27;neil@mail.ru", page)
        self.assertNotIn("o'neil", page)
        css = re.search(r"<style>(.*)</style>", page, re.S).group(1)
        digest = base64.b64encode(hashlib.sha256(css.encode()).digest()).decode()
        self.assertIn("'sha256-%s'" % digest, self.m.PAGE_CSP)
        self.assertNotIn("<script", page)

    def test_letters(self):
        subject, text, body = self.m.letter_confirm("https://sub.example/account/confirm?t=abc&x=<1>")
        self.assertEqual("Подтвердите почту для Kirov VPN", subject)
        self.assertIn("\nhttps://sub.example/account/confirm?t=abc&x=<1>\n", text)
        self.assertIn('href="https://sub.example/account/confirm?t=abc&amp;x=&lt;1&gt;"', body)
        self.assertNotIn("<1>", body)


class PanelTest(unittest.TestCase):
    def test_users_are_made_like_klaus_panel_add_user(self):
        m = load()
        panel = m.Panel("http://panel", "token", "squad-uuid")
        calls = []
        answers = [(400, None), (201, {"response": {"id": 5, "subscriptionUrl": "https://sub.example/xyz"}})]
        panel.request = lambda method, path, body=None: (calls.append((method, path, body)), answers.pop(0))[1]
        user_id, name, url = panel.create("Ivan.Petrov+vpn@mail.ru".lower())
        self.assertEqual(("5", "https://sub.example/xyz"), (user_id, url))
        self.assertRegex(name, r"^ivan_petrov_vpn-[0-9a-f]{4}$")
        self.assertEqual(2, len(calls), "a taken name is tried again")
        method, path, body = calls[1]
        self.assertEqual(("POST", "/api/users"), (method, path))
        self.assertEqual({"username": name, "expireAt": "2099-12-31T00:00:00.000Z", "trafficLimitBytes": 0,
                          "trafficLimitStrategy": "NO_RESET", "activeInternalSquads": ["squad-uuid"],
                          "description": "klaus-accounts"}, body)

    def test_short_and_odd_names(self):
        m = load()
        names = []
        panel = m.Panel("http://panel", "token", "squad")
        panel.request = lambda method, path, body=None: (names.append(body["username"]),
                                                         (200, {"response": {"id": "a-1", "subscriptionUrl": "https://s/x"}}))[1]
        for address in ("a@mail.ru", "...@x.ru".replace("...", "-_-"), "o'neil@mail.ru"):
            panel.create(address)
        for name in names:
            self.assertRegex(name, r"^[A-Za-z0-9_-]{3,36}$")
        self.assertTrue(names[0].startswith("friend_a-"))

    def test_an_unexpected_answer_is_refused(self):
        m = load()
        panel = m.Panel("http://panel", "token", "squad")
        panel.request = lambda method, path, body=None: (200, {"response": {"id": "1", "subscriptionUrl": "javascript:x"}})
        with self.assertRaises(m.PanelError):
            panel.create("ivan@mail.ru")
        panel.request = lambda method, path, body=None: (500, None)
        with self.assertRaises(m.PanelError):
            panel.create("ivan@mail.ru")
        with self.assertRaises(m.PanelError):
            m.Panel("http://panel", "", "squad").create("ivan@mail.ru")


class TelegramTest(unittest.TestCase):
    def test_the_owner_gets_two_link_buttons(self):
        m = load()
        tg = m.Telegram("123:abc", "-100500:7", "https://tg")
        calls = []
        tg.call = lambda method, payload: (calls.append((method, payload)), {"message_id": 9})[1]
        self.assertEqual(9, tg.ask("ivan@mail.ru", "https://a", "https://r"))
        method, msg = calls[0]
        self.assertEqual("sendMessage", method)
        self.assertEqual(7, msg["message_thread_id"])
        self.assertIn("ivan@mail.ru", msg["text"])
        self.assertEqual([[{"text": "Выдать доступ", "url": "https://a"}, {"text": "Отклонить", "url": "https://r"}]],
                         msg["reply_markup"]["inline_keyboard"])
        tg.settle(9, "done")
        self.assertEqual(("editMessageText", {"message_id": 9, "text": "done"}), calls[1])
        self.assertFalse(m.Telegram("", "1", "x").configured)


class HttpTest(unittest.TestCase):
    """The HTTP layer over a real socket."""

    def setUp(self):
        self.t = AccountsTest("test_letters")
        self.t.setUp()
        self.addCleanup(self.t.doCleanups)
        self.server = self.t.m.Server(("127.0.0.1", 0), self.t.app)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=10)
        self.addCleanup(conn.close)
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        return resp.status, dict(resp.getheaders()), resp.read().decode()

    def api(self, method, name, data=None, token=None):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        status, h, body = self.request(method, "/account/v1/" + name,
                                       json.dumps(data).encode() if data is not None else None, headers)
        self.assertEqual("no-store", h["Cache-Control"])
        self.assertTrue(h["Content-Type"].startswith("application/json"))
        return status, json.loads(body)

    def test_the_api_and_the_pages(self):
        status, body = self.api("POST", "register", {"email": "ivan@mail.ru", "password": "correct horse"})
        self.assertEqual((202, {"ok": True}), (status, body))
        token = self.t.token_in(self.t.one_letter("ivan@mail.ru"), "confirm")

        status, h, page = self.request("GET", "/account/confirm?t=" + token)
        self.assertEqual(200, status)
        self.assertEqual(self.t.m.PAGE_CSP, h["Content-Security-Policy"])
        self.assertEqual("no-referrer", h["Referrer-Policy"])
        self.assertIn('action="/account/confirm"', page)
        status, _, page = self.request("POST", "/account/confirm", urllib.parse.urlencode({"t": token}),
                                       {"Content-Type": "application/x-www-form-urlencoded"})
        self.assertIn("Почта подтверждена", page)

        approve = urllib.parse.urlsplit(self.t.owner.asked[0][1])
        status, _, page = self.request("GET", approve.path + "?" + approve.query)
        self.assertIn("Выдать доступ", page)
        decide = urllib.parse.parse_qs(approve.query)["t"][0]
        status, _, page = self.request("POST", "/account/decide", urllib.parse.urlencode({"t": decide, "a": "maybe"}))
        self.assertEqual(400, status)
        status, _, page = self.request("POST", "/account/decide", urllib.parse.urlencode({"t": decide, "a": "approve"}))
        self.assertIn("Доступ выдан", page)

        status, body = self.api("POST", "login", {"email": "ivan@mail.ru", "password": "correct horse", "device": "ПК"})
        self.assertEqual(200, status, body)
        status, body = self.api("GET", "me", token=body["token"])
        self.assertEqual("active", body["account"]["status"])

    def test_refusals(self):
        self.assertEqual(401, self.api("GET", "me")[0])
        self.assertEqual(401, self.api("GET", "me", token="x" * 43)[0])
        self.assertEqual(404, self.api("GET", "register")[0])
        self.assertEqual(404, self.api("POST", "nothing", {})[0])
        status, body = self.api("POST", "login", ["not", "an", "object"])
        self.assertEqual((400, "bad_request"), (status, body["code"]))
        status, body = self.api("POST", "register", None)
        self.assertEqual((400, "bad_request"), (status, body["code"]))
        status, _, body = self.request("POST", "/account/v1/register", b"{" + b" " * 9000 + b"}",
                                       {"Content-Type": "application/json"})
        self.assertEqual(400, status)
        status, _, page = self.request("GET", "/account/reset?t=" + "x" * 43)
        self.assertIn("Ссылка устарела", page)
        self.assertEqual(404, self.request("GET", "/account/")[0])
        self.assertEqual(200, self.request("GET", "/account/health")[0])


if __name__ == "__main__":
    unittest.main()
