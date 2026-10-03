#!/usr/bin/env python3
"""klaus-accounts: accounts for the Kirov VPN apps (docs/accounts/PLAN.md).

A friend registers in any app with an email and a password and confirms
the email from a letter. Once the owner grants access, the account gets a
Remnawave user, and every app signed in to the account adds that user's
subscription link like any other. Links and keys keep working without an
account.

Runs on the panel host next to Remnawave (install-panel.sh starts it in the
official python:3-alpine image with this file mounted read-only and the
database in /data; standard library only), behind Caddy at
https://<SUB_DOMAIN>/account/:

  POST /account/v1/register  {email, password}
  POST /account/v1/resend    {email}
  POST /account/v1/login     {email, password, device} -> {token, account}
  GET  /account/v1/me        Authorization: Bearer     -> {account}
  POST /account/v1/logout    Bearer
  POST /account/v1/forgot    {email}
  POST /account/v1/delete    Bearer, {password}
  /account/confirm, /account/reset, /account/decide: the pages opened from
      the letters and from the owner's Telegram message (GET shows, POST
      acts)
  GET  /account/health

The owner's commands run in the container (klaus-panel calls them through
docker exec): list, approve, reject, delete, link, owner-of, mail-test,
backup.

Privacy: requests are not logged, Caddy hands 127.0.0.1 instead of the
client's IP, and the log never holds an address, a token or a link. Kept:
the email, a scrypt hash of the password, the account's state and its panel
user; sessions and one-time tokens only as SHA-256 hashes of them.

The panel token can create and delete users only: this service faces the
internet.

Settings (environment, klaus-accounts.env written by klaus-panel):
PUBLIC_URL, PANEL_URL, PANEL_TOKEN, PANEL_SQUAD, TELEGRAM_BOT_TOKEN,
TELEGRAM_CHAT_ID, TELEGRAM_API_BASE, SMTP_HOST, SMTP_PORT, SMTP_TLS,
SMTP_USER, SMTP_PASSWORD, SMTP_FROM, MAIL_DAILY_LIMIT, MAIL_GAP, DATA_DIR,
LISTEN_PORT.
"""

import base64
import binascii
import collections
import contextlib
import email.message
import email.utils
import hashlib
import hmac
import html
import http.client
import http.server
import json
import os
import queue
import re
import secrets
import smtplib
import sqlite3
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


def env_int(name, default, low, high):
    try:
        value = int(os.environ.get(name, "") or default)
    except ValueError:
        value = default
    return min(max(value, low), high)


PUBLIC_URL = (os.environ.get("PUBLIC_URL") or "").rstrip("/")
PANEL_URL = (os.environ.get("PANEL_URL") or "http://remnawave:3000").rstrip("/")
PANEL_TOKEN = os.environ.get("PANEL_TOKEN", "")
PANEL_SQUAD = os.environ.get("PANEL_SQUAD", "")
TG_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
TG_CHAT = os.environ.get("TELEGRAM_CHAT_ID", "")
TG_API = (os.environ.get("TELEGRAM_API_BASE") or "https://api.telegram.org").rstrip("/")
SMTP_HOST = os.environ.get("SMTP_HOST", "")
SMTP_TLS = os.environ.get("SMTP_TLS", "") or "starttls"  # starttls | ssl | none (tests only)
SMTP_PORT = env_int("SMTP_PORT", 465 if SMTP_TLS == "ssl" else 587, 1, 65535)
SMTP_USER = os.environ.get("SMTP_USER", "")
SMTP_PASSWORD = os.environ.get("SMTP_PASSWORD", "")
SMTP_FROM = os.environ.get("SMTP_FROM", "") or SMTP_USER
MAIL_DAILY_LIMIT = env_int("MAIL_DAILY_LIMIT", 300, 1, 100000)
DATA_DIR = os.environ.get("DATA_DIR") or "/data"
PORT = env_int("LISTEN_PORT", 8081, 1, 65535)

# How long things last, in seconds.
CONFIRM_TTL = 2 * 86400  # a confirmation link
RESET_TTL = 3600  # a password reset link
DECIDE_TTL = 14 * 86400  # the owner's buttons
UNCONFIRMED_TTL = 2 * 86400  # an account nobody confirmed, after its last registration
REJECTED_TTL = 30 * 86400  # a refused account, after the decision
SESSION_IDLE = 180 * 86400  # a signed-in device that never comes back
SESSION_TOUCH = 86400  # how precisely a session's last use is kept

# Limits. No IP is used for them: Caddy hands 127.0.0.1, and friends behind
# the VPN share the nodes' addresses anyway.
MAIL_GAP = env_int("MAIL_GAP", 120, 0, 3600)  # seconds between letters to one address
MAIL_PER_DAY = 5  # letters to one address
FREE_FAILURES = 5  # wrong passwords for one address before it has to wait
LOCK_STEPS = (60, 120, 240, 480, 900)  # the waits after that
REQUESTS_AT_ONCE = 32
HASHES_AT_ONCE = 2
HASH_WAIT = 20  # seconds a request waits for a hashing slot, then 503
MAX_JSON = 8192
MAX_FORM = 4096
MAX_DRAIN = 65536  # Caddy lets no bigger body through
MAX_TRACKED = 20000  # addresses kept in memory per table, whatever happens

TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
LABEL_RE = re.compile(r"^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
LOCAL_CHARS = set("abcdefghijklmnopqrstuvwxyz0123456789.!#$%&'*+/=?^_`{|}~-")
USER_ID_RE = re.compile(r"^[A-Za-z0-9-]{1,64}$")
USERNAME_RE = re.compile(r"^[A-Za-z0-9_-]{3,36}$")

# Remnawave needs an end date; klaus-panel add-user uses the same.
NEVER = "2099-12-31T00:00:00.000Z"


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S ") + msg, flush=True)


class Busy(Exception):
    """Too much at once; the request gets 503 and may be repeated."""


class PanelError(Exception):
    pass


# ---------------------------------------------------------------- input

def normalize_email(value):
    """The address as stored and compared, lower case, or None when it is no
    plain address. Only ASCII: it goes into mail headers, and no CR, LF or
    space may get there."""
    if not isinstance(value, str):
        return None
    s = value.strip()
    if not s.isascii() or len(s) > 254 or s.count("@") != 1:
        return None
    local, domain = s.lower().split("@")
    if (not local or len(local) > 64 or local[0] == "." or local[-1] == "." or ".." in local
            or not set(local) <= LOCAL_CHARS):
        return None
    labels = domain.split(".")
    if len(labels) < 2 or not all(LABEL_RE.match(label) for label in labels):
        return None
    tld = labels[-1]
    if len(tld) < 2 or not (tld.isalpha() or tld.startswith("xn--")):
        return None
    return local + "@" + domain


# The passwords guessed first; Russian keyboards add their own ("йцукенгш"
# is "qwertyui").
COMMON_PASSWORDS = {
    "12345678", "123456789", "1234567890", "11111111", "00000000", "87654321", "123123123",
    "12341234", "11223344", "password", "password1", "qwertyui", "qwerty123", "qwertyuiop",
    "1q2w3e4r", "1qaz2wsx", "iloveyou", "abcd1234", "йцукенгш", "пароль123",
}


def password_problem(password, email):
    """What is wrong with a new password, in Russian, or "" for a good one."""
    if not isinstance(password, str) or not password:
        return "Введите пароль."
    if not password.isprintable():
        return "В пароле есть недопустимые символы."
    if len(password) < 8:
        return "Пароль должен быть не короче 8 символов."
    if len(password) > 128:
        return "Пароль должен быть не длиннее 128 символов."
    lower = password.lower()
    if lower in COMMON_PASSWORDS or lower == email or lower == email.split("@")[0]:
        return "Этот пароль слишком легко угадать. Придумайте другой."
    return ""


def clean_device(value):
    if not isinstance(value, str):
        return ""
    value = "".join(c for c in value if c.isprintable())
    return " ".join(value.split())[:64]


# ---------------------------------------------------------------- secrets

# scrypt with N = 2^15, r = 8, p = 3: 32 MiB and about a third of a second
# per hash, as strong as OWASP's other settings. Kept next to each hash, so
# they can be raised later.
SCRYPT = (15, 8, 3)
MAX_SCRYPT_MEMORY = 128 * 1024 * 1024
HASHING = threading.BoundedSemaphore(HASHES_AT_ONCE)


def b64(data):
    return base64.b64encode(data).decode().rstrip("=")


def unb64(text):
    return base64.b64decode(text + "=" * (-len(text) % 4), validate=True)


def scrypt(password, salt, log_n, r, p, length):
    if not HASHING.acquire(timeout=HASH_WAIT):
        raise Busy()
    try:
        return hashlib.scrypt(password.encode(), salt=salt, n=1 << log_n, r=r, p=p,
                              maxmem=2 * MAX_SCRYPT_MEMORY, dklen=length)
    finally:
        HASHING.release()


def hash_password(password):
    salt = secrets.token_bytes(16)
    key = scrypt(password, salt, *SCRYPT, 32)
    return "scrypt$%d$%d$%d$%s$%s" % (*SCRYPT, b64(salt), b64(key))


def check_password(stored, password):
    """-> (whether the password matches, whether the hash should be made
    again with today's parameters)."""
    try:
        name, log_n, r, p, salt, key = stored.split("$")
        log_n, r, p = int(log_n), int(r), int(p)
        salt, key = unb64(salt), unb64(key)
    except (ValueError, binascii.Error):
        return False, False
    # A damaged row must not make one check take gigabytes or minutes.
    if (name != "scrypt" or not (10 <= log_n <= 20 and 1 <= r <= 32 and 1 <= p <= 16)
            or 128 * r * (1 << log_n) > MAX_SCRYPT_MEMORY or len(salt) < 8 or not 16 <= len(key) <= 64):
        return False, False
    got = scrypt(password, salt, log_n, r, p, len(key))
    return hmac.compare_digest(got, key), (log_n, r, p) != SCRYPT


_dummy = []


def waste_time(password):
    """Spends the time of a password check, so that a login for an unknown
    address takes as long as one for a known address."""
    if not _dummy:
        _dummy.append(hash_password("not a password"))
    check_password(_dummy[0], password)


def new_token():
    return secrets.token_urlsafe(32)


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


# ---------------------------------------------------------------- database

SCHEMA = """
CREATE TABLE IF NOT EXISTS accounts (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password TEXT NOT NULL,
    status TEXT NOT NULL,
    created INTEGER NOT NULL,
    confirmed INTEGER,
    decided INTEGER,
    panel_user TEXT,
    panel_name TEXT,
    sub_url TEXT,
    panel_owned INTEGER NOT NULL DEFAULT 0,
    notice INTEGER
);
CREATE TABLE IF NOT EXISTS sessions (
    hash TEXT PRIMARY KEY,
    account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device TEXT NOT NULL,
    created INTEGER NOT NULL,
    used INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_account ON sessions(account);
CREATE TABLE IF NOT EXISTS tokens (
    hash TEXT PRIMARY KEY,
    account TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    password TEXT,
    expires INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS tokens_account ON tokens(account);
CREATE TABLE IF NOT EXISTS counters (
    name TEXT NOT NULL,
    day TEXT NOT NULL,
    value INTEGER NOT NULL,
    PRIMARY KEY (name, day)
);
"""


class Db:
    """One SQLite connection shared by the threads, one statement at a time.
    The owner's commands run in other processes on the same file."""

    def __init__(self, path):
        self.lock = threading.RLock()
        self.conn = sqlite3.connect(path, timeout=10, isolation_level=None, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        for pragma in ("journal_mode=WAL", "synchronous=FULL", "foreign_keys=ON", "temp_store=MEMORY"):
            self.conn.execute("PRAGMA " + pragma)
        self.conn.executescript(SCHEMA)

    @contextlib.contextmanager
    def tx(self):
        with self.lock:
            self.conn.execute("BEGIN IMMEDIATE")
            try:
                yield self.conn
            except BaseException:
                self.conn.execute("ROLLBACK")
                raise
            self.conn.execute("COMMIT")

    def one(self, sql, args=()):
        with self.lock:
            return self.conn.execute(sql, args).fetchone()

    def all(self, sql, args=()):
        with self.lock:
            return self.conn.execute(sql, args).fetchall()


def day(now):
    return time.strftime("%Y-%m-%d", time.gmtime(now))


# ---------------------------------------------------------------- limits

class Limits:
    """Letters per address and wrong passwords per address, in memory."""

    def __init__(self):
        self.lock = threading.Lock()
        self.mail = {}  # address -> deque of times a letter was asked for, last day
        self.failures = {}  # address -> [wrong passwords in a row, may try again at, last failure]

    def mail_wait(self, address, now):
        """Seconds until a letter to address may go; 0 means it may, and
        counts it."""
        with self.lock:
            times = self.mail.setdefault(address, collections.deque())
            while times and times[0] <= now - 86400:
                times.popleft()
            if times and now - times[-1] < MAIL_GAP:
                return int(MAIL_GAP - (now - times[-1])) + 1
            if len(times) >= MAIL_PER_DAY:
                return int(times[0] + 86400 - now) + 1
            times.append(now)
            return 0

    def login_wait(self, address, now):
        with self.lock:
            entry = self.failures.get(address)
            return int(entry[1] - now) + 1 if entry and entry[1] > now else 0

    def login_failed(self, address, now):
        with self.lock:
            entry = self.failures.setdefault(address, [0, 0, 0])
            entry[0] += 1
            entry[2] = now
            if entry[0] >= FREE_FAILURES:
                entry[1] = now + LOCK_STEPS[min(entry[0] - FREE_FAILURES, len(LOCK_STEPS) - 1)]

    def login_ok(self, address):
        with self.lock:
            self.failures.pop(address, None)

    def purge(self, now):
        with self.lock:
            for address in [a for a, t in self.mail.items() if not t or t[-1] <= now - 86400]:
                del self.mail[address]
            for address in [a for a, e in self.failures.items() if e[2] <= now - 86400]:
                del self.failures[address]
            for table in (self.mail, self.failures):
                while len(table) > MAX_TRACKED:
                    del table[next(iter(table))]


# ---------------------------------------------------------------- mail

LETTER_STYLE = ("font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;font-size:16px;"
                "line-height:1.5;color:#0E1726")
BUTTON_STYLE = ("display:inline-block;padding:12px 22px;border-radius:12px;background:#0A74FF;"
                "color:#ffffff;text-decoration:none;font-weight:600")


def letter(subject, paragraphs, link="", button=""):
    """A letter as (subject, plain text, HTML). paragraphs are plain text;
    the link, when there is one, goes after the first."""
    text = list(paragraphs)
    parts = ["<p>%s</p>" % html.escape(p) for p in paragraphs]
    if link:
        text.insert(1, link)
        parts.insert(1, '<p><a href="%s" style="%s">%s</a></p><p style="font-size:13px;color:#556173">'
                        "Если кнопка не открывается, скопируйте ссылку: %s</p>"
                     % (html.escape(link), BUTTON_STYLE, html.escape(button), html.escape(link)))
    body = '<div style="%s">%s</div>' % (LETTER_STYLE, "".join(parts))
    return subject, "\n\n".join(text) + "\n", body


def letter_confirm(link):
    return letter("Подтвердите почту для Kirov VPN", [
        "Здравствуйте! Чтобы закончить регистрацию в Kirov VPN, откройте ссылку и нажмите «Подтвердить почту».",
        "Ссылка действует 2 дня. Если вы не регистрировались, просто удалите это письмо.",
    ], link, "Подтвердить почту")


def letter_exists():
    return letter("Вход в Kirov VPN", [
        "Здравствуйте! С этой почтой пытались зарегистрироваться в Kirov VPN, но аккаунт с ней уже есть.",
        "Если это были вы, просто войдите в приложении. Забыли пароль — нажмите «Забыли пароль?» "
        "на экране входа. Если это были не вы, ничего делать не нужно.",
    ])


def letter_reset(link):
    return letter("Новый пароль для Kirov VPN", [
        "Здравствуйте! Чтобы задать новый пароль для Kirov VPN, откройте ссылку.",
        "Ссылка действует 1 час. Если вы не просили сменить пароль, ничего не делайте: "
        "старый пароль продолжит работать.",
    ], link, "Задать новый пароль")


def letter_approved():
    return letter("Доступ к Kirov VPN открыт", [
        "Здравствуйте! Вам открыли доступ к Kirov VPN.",
        "Откройте приложение на любом устройстве и войдите с этой почтой: серверы появятся сами.",
    ])


def smtp_deliver(to, mail):
    subject, text, body = mail
    msg = email.message.EmailMessage()
    msg["From"] = SMTP_FROM
    msg["To"] = to
    msg["Subject"] = subject
    msg["Date"] = email.utils.formatdate(localtime=False)
    msg["Message-ID"] = email.utils.make_msgid(domain=SMTP_FROM.rpartition("@")[2].strip("> ") or None)
    msg.set_content(text)
    msg.add_alternative(body, subtype="html")
    context = ssl.create_default_context()
    if SMTP_TLS == "ssl":
        client = smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=30, context=context)
    else:
        client = smtplib.SMTP(SMTP_HOST, SMTP_PORT, timeout=30)
    with client:
        if SMTP_TLS == "starttls":
            client.starttls(context=context)
        if SMTP_USER:
            client.login(SMTP_USER, SMTP_PASSWORD)
        client.send_message(msg)


class Mailer:
    """Letters go out from a queue, one at a time, so that a slow mail
    server never holds an app's request. Each day's letters are counted in
    the database, and MAIL_DAILY_LIMIT stops them until the next day."""

    RETRIES = (10, 60)

    def __init__(self, db, owner, deliver=smtp_deliver, now=time.time, later=None):
        self.db, self.owner, self.deliver, self.now = db, owner, deliver, now
        self.later = later or (lambda fn, *args: fn(*args))
        self.queue = queue.Queue(maxsize=500)
        self.configured = bool(SMTP_HOST and SMTP_FROM and PUBLIC_URL)

    def start(self):
        threading.Thread(target=self.run, daemon=True).start()

    def room(self):
        row = self.db.one("SELECT value FROM counters WHERE name = 'mail' AND day = ?", (day(self.now()),))
        return (row["value"] if row else 0) < MAIL_DAILY_LIMIT

    def send(self, to, mail):
        """Queues a letter; False when it cannot go today."""
        if not self.configured:
            return False
        today = day(self.now())
        with self.db.tx() as c:
            row = c.execute("SELECT value FROM counters WHERE name = 'mail' AND day = ?", (today,)).fetchone()
            sent = row["value"] if row else 0
            if sent >= MAIL_DAILY_LIMIT:
                return False
            c.execute("INSERT INTO counters (name, day, value) VALUES ('mail', ?, 1) "
                      "ON CONFLICT (name, day) DO UPDATE SET value = value + 1", (today,))
            told = sent + 1 >= MAIL_DAILY_LIMIT and c.execute(
                "INSERT OR IGNORE INTO counters (name, day, value) VALUES ('mail-limit-told', ?, 1)",
                (today,)).rowcount == 1
        if told:
            self.later(self.owner.tell, "Kirov VPN: письма на сегодня закончились, отправлено %d. Возможно, кто-то "
                            "массово регистрируется. Новые письма пойдут завтра (по Гринвичу); лимит "
                            "меняет klaus-panel mail-setup." % MAIL_DAILY_LIMIT)
        try:
            self.queue.put_nowait((to, mail))
        except queue.Full:
            log("mail queue is full, a letter was dropped")
            return False
        return True

    def run(self):
        while True:
            to, mail = self.queue.get()
            for wait in self.RETRIES + (None,):
                try:
                    self.deliver(to, mail)
                    break
                except Exception as e:  # never the address or the text
                    log("mail failed: %s" % type(e).__name__)
                    if wait is None:
                        log("mail dropped after %d tries" % (len(self.RETRIES) + 1))
                    else:
                        time.sleep(wait)


# ---------------------------------------------------------------- Telegram

class Telegram:
    """The owner's chat, send-only: nothing here reads the bot's updates, so
    telegram-setup and Remnawave's notices on the same bot are undisturbed."""

    def __init__(self, token=TG_TOKEN, chat=TG_CHAT, api=TG_API):
        self.token, self.api = token, api
        self.chat, _, thread = chat.partition(":")
        self.thread = int(thread) if thread.isdigit() else None

    @property
    def configured(self):
        return bool(self.token and self.chat)

    def call(self, method, payload):
        if not self.configured:
            return None
        payload = dict(payload, chat_id=self.chat)
        req = urllib.request.Request("%s/bot%s/%s" % (self.api, self.token, method),
                                     data=json.dumps(payload).encode(),
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                answer = json.load(resp)
            return answer.get("result") if answer.get("ok") is True else None
        except urllib.error.HTTPError as e:
            # Never the URL: it holds the bot token.
            log("telegram %s: HTTP %d" % (method, e.code))
        except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError, AttributeError) as e:
            log("telegram %s: %s" % (method, type(e).__name__))
        return None

    def send(self, text, buttons=None):
        msg = {"text": text, "link_preview_options": {"is_disabled": True}}
        if self.thread is not None:
            msg["message_thread_id"] = self.thread
        if buttons:
            msg["reply_markup"] = {"inline_keyboard": [[{"text": t, "url": u} for t, u in buttons]]}
        result = self.call("sendMessage", msg)
        return result.get("message_id") if isinstance(result, dict) else None

    def ask(self, address, approve_url, reject_url):
        return self.send("Kirov VPN: новая регистрация\n%s\n\nВыдать доступ к VPN?" % address,
                         [("Выдать доступ", approve_url), ("Отклонить", reject_url)])

    def settle(self, message_id, text):
        # Without reply_markup the buttons go.
        if message_id:
            self.call("editMessageText", {"message_id": message_id, "text": text})

    def tell(self, text):
        self.send(text)


# ---------------------------------------------------------------- Remnawave

class Panel:
    def __init__(self, url=PANEL_URL, token=PANEL_TOKEN, squad=PANEL_SQUAD):
        self.url, self.token, self.squad = url, token, squad

    def request(self, method, path, body=None):
        """-> (HTTP status, parsed JSON or None); PanelError when the panel
        does not answer."""
        req = urllib.request.Request(self.url + path, method=method, headers={
            "Authorization": "Bearer " + self.token,
            # The panel only answers requests that come through its HTTPS proxy.
            "X-Forwarded-For": "127.0.0.1",
            "X-Forwarded-Proto": "https",
            "Accept": "application/json",
            "Content-Type": "application/json",
        }, data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                return resp.status, json.load(resp)
        except urllib.error.HTTPError as e:
            return e.code, None
        except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError) as e:
            raise PanelError("panel API: %s" % type(e).__name__) from None

    def create(self, address):
        """Creates the user for an account as klaus-panel add-user does, so
        that tidy-users leaves it alone. -> (id, username, subscription link)."""
        if not (self.token and self.squad):
            raise PanelError("panel API: no token or squad")
        base = re.sub(r"[^a-z0-9_-]", "_", address.split("@")[0])[:24].strip("_-") or "friend"
        if len(base) < 3:
            base = "friend_" + base
        status = 0
        for _ in range(3):
            name = "%s-%s" % (base, secrets.token_hex(2))
            status, data = self.request("POST", "/api/users", {
                "username": name, "expireAt": NEVER, "trafficLimitBytes": 0,
                "trafficLimitStrategy": "NO_RESET", "activeInternalSquads": [self.squad],
                "description": "klaus-accounts",
            })
            user = data.get("response") if isinstance(data, dict) else None
            if 200 <= status < 300 and isinstance(user, dict):
                user_id, url = str(user.get("id") or user.get("uuid") or ""), user.get("subscriptionUrl")
                if not USER_ID_RE.match(user_id) or not isinstance(url, str) or not url.startswith("https://"):
                    raise PanelError("panel API: unexpected answer")
                return user_id, name, url
            if status not in (400, 409):
                break
            # Most likely the name is taken: another suffix.
        raise PanelError("panel API: HTTP %d" % status)

    def delete(self, user_id):
        status, _ = self.request("DELETE", "/api/users/" + urllib.parse.quote(user_id, safe=""))
        if not (200 <= status < 300 or status == 404):
            raise PanelError("panel API: HTTP %d" % status)


# ---------------------------------------------------------------- accounts

STATUS_RU = {
    "unconfirmed": "почта не подтверждена",
    "pending": "ждёт решения",
    "active": "доступ есть",
    "rejected": "отклонён",
}


def error(status, code, text, **extra):
    return status, dict(error=text, code=code, **extra)


def minutes(seconds):
    m = max(1, (int(seconds) + 59) // 60)
    if m % 10 == 1 and m % 100 != 11:
        word = "минуту"
    elif m % 10 in (2, 3, 4) and m % 100 not in (12, 13, 14):
        word = "минуты"
    else:
        word = "минут"
    return "%d %s" % (m, word)


BAD_EMAIL = error(400, "bad_email", "Проверьте адрес почты: например, ivan@mail.ru.")
BAD_LOGIN = error(401, "bad_login", "Неверная почта или пароль.")
SIGNED_OUT = error(401, "signed_out", "Вы вышли из аккаунта. Войдите снова.")
NOT_FOUND = error(404, "not_found", "Нет такого запроса.")


class Accounts:
    """The flows of docs/accounts/PLAN.md. Every method that answers an app
    returns (HTTP status, JSON body)."""

    def __init__(self, db, mailer, owner, panel, public_url=PUBLIC_URL, now=time.time, later=None):
        self.db, self.mailer, self.owner, self.panel = db, mailer, owner, panel
        self.public_url, self.now = public_url, now
        # Telegram can be slow: requests do not wait for it. The owner's
        # commands run it before they end.
        self.later = later or (lambda fn, *args: fn(*args))
        self.limits = Limits()
        self.deciding = threading.Lock()  # one owner's decision at a time

    def link(self, page, token, **extra):
        return "%s/account/%s?%s" % (self.public_url, page, urllib.parse.urlencode(dict(t=token, **extra)))

    @staticmethod
    def issue(c, account_id, kind, ttl, now, password=None):
        token = new_token()
        c.execute("INSERT INTO tokens (hash, account, kind, password, expires) VALUES (?, ?, ?, ?, ?)",
                  (token_hash(token), account_id, kind, password, int(now + ttl)))
        return token

    def mail_check(self, address):
        """None when a letter to address may go now, else the answer."""
        if not self.mailer.configured:
            return error(503, "mail_off", "Письма пока не отправляются: владелец ещё не настроил почту. "
                                          "Попробуйте позже.")
        if not self.mailer.room():
            return error(503, "mail_off", "Сегодня письма больше не отправляются. Попробуйте завтра.")
        wait = self.limits.mail_wait(address, self.now())
        if wait:
            return error(429, "too_often", "Слишком часто. Следующее письмо можно отправить через %s."
                         % minutes(wait), retryAfter=wait)
        return None

    @staticmethod
    def view(row):
        account = {"email": row["email"], "status": row["status"]}
        if row["status"] == "active" and row["sub_url"]:
            account["subscriptionUrl"] = row["sub_url"]
        return account

    # -- apps

    def register(self, address, password):
        address = normalize_email(address)
        if not address:
            return BAD_EMAIL
        problem = password_problem(password, address)
        if problem:
            return error(400, "weak_password", problem)
        refused = self.mail_check(address)
        if refused:
            return refused
        # Hashed for a taken address too: the same time either way.
        hashed = hash_password(password)
        now = self.now()
        with self.db.tx() as c:
            row = c.execute("SELECT id, status FROM accounts WHERE email = ?", (address,)).fetchone()
            if row is None:
                account_id = secrets.token_hex(8)
                c.execute("INSERT INTO accounts (id, email, password, status, created) VALUES (?, ?, ?, 'unconfirmed', ?)",
                          (account_id, address, hashed, int(now)))
                mail = letter_confirm(self.link("confirm", self.issue(c, account_id, "confirm", CONFIRM_TTL, now, hashed)))
            elif row["status"] == "unconfirmed":
                # The latest registration's password, but each link confirms
                # the password of its own registration: a stranger's later
                # attempt cannot take the account when its owner clicks.
                c.execute("UPDATE accounts SET password = ?, created = ? WHERE id = ?", (hashed, int(now), row["id"]))
                mail = letter_confirm(self.link("confirm", self.issue(c, row["id"], "confirm", CONFIRM_TTL, now, hashed)))
            else:
                mail = letter_exists()
        if not self.mailer.send(address, mail):
            return error(503, "mail_off", "Не получилось отправить письмо. Попробуйте позже.")
        return 202, {"ok": True}

    def resend(self, address):
        address = normalize_email(address)
        if not address:
            return BAD_EMAIL
        refused = self.mail_check(address)
        if refused:
            return refused
        now = self.now()
        mail = None
        with self.db.tx() as c:
            row = c.execute("SELECT id, status, password FROM accounts WHERE email = ?", (address,)).fetchone()
            if row is not None and row["status"] == "unconfirmed":
                mail = letter_confirm(self.link("confirm", self.issue(
                    c, row["id"], "confirm", CONFIRM_TTL, now, row["password"])))
            elif row is not None:
                mail = letter_exists()
        if mail:
            self.mailer.send(address, mail)
        return 202, {"ok": True}

    def login(self, address, password, device):
        address = normalize_email(address)
        if not address:
            return BAD_EMAIL
        if not isinstance(password, str) or not password or len(password) > 1024:
            return BAD_LOGIN
        now = self.now()
        wait = self.limits.login_wait(address, now)
        if wait:
            return error(429, "locked", "Слишком много неверных паролей. Попробуйте через %s или "
                                        "восстановите пароль." % minutes(wait), retryAfter=wait)
        row = self.db.one("SELECT * FROM accounts WHERE email = ?", (address,))
        if row is None:
            waste_time(password)
            self.limits.login_failed(address, now)
            return BAD_LOGIN
        ok, rehash = check_password(row["password"], password)
        if not ok:
            self.limits.login_failed(address, now)
            return BAD_LOGIN
        self.limits.login_ok(address)
        if row["status"] == "unconfirmed":
            return error(403, "unconfirmed", "Сначала подтвердите почту: письмо отправлено на %s." % address)
        token = new_token()
        rehashed = hash_password(password) if rehash else None
        with self.db.tx() as c:
            c.execute("INSERT INTO sessions (hash, account, device, created, used) VALUES (?, ?, ?, ?, ?)",
                      (token_hash(token), row["id"], clean_device(device), int(now), int(now)))
            if rehashed:
                c.execute("UPDATE accounts SET password = ? WHERE id = ? AND password = ?",
                          (rehashed, row["id"], row["password"]))
        return 200, {"token": token, "account": self.view(row)}

    def session(self, token):
        """The account row of a session token, or None."""
        if not token or not TOKEN_RE.match(token):
            return None
        return self.db.one("SELECT a.*, s.hash AS session, s.used AS session_used FROM sessions s "
                           "JOIN accounts a ON a.id = s.account WHERE s.hash = ?", (token_hash(token),))

    def me(self, token):
        row = self.session(token)
        if row is None:
            return SIGNED_OUT
        now = self.now()
        if now - row["session_used"] >= SESSION_TOUCH:
            with self.db.tx() as c:
                c.execute("UPDATE sessions SET used = ? WHERE hash = ?", (int(now), row["session"]))
        return 200, {"account": self.view(row)}

    def logout(self, token):
        if token and TOKEN_RE.match(token):
            with self.db.tx() as c:
                c.execute("DELETE FROM sessions WHERE hash = ?", (token_hash(token),))
        return 200, {"ok": True}

    def forgot(self, address):
        address = normalize_email(address)
        if not address:
            return BAD_EMAIL
        refused = self.mail_check(address)
        if refused:
            return refused
        now = self.now()
        mail = None
        with self.db.tx() as c:
            row = c.execute("SELECT id FROM accounts WHERE email = ?", (address,)).fetchone()
            if row is not None:
                mail = letter_reset(self.link("reset", self.issue(c, row["id"], "reset", RESET_TTL, now)))
        if mail:
            self.mailer.send(address, mail)
        return 202, {"ok": True}

    def delete(self, token, password):
        row = self.session(token)
        if row is None:
            return SIGNED_OUT
        now = self.now()
        wait = self.limits.login_wait(row["email"], now)
        if wait:
            return error(429, "locked", "Слишком много неверных паролей. Попробуйте через %s." % minutes(wait),
                         retryAfter=wait)
        if not isinstance(password, str) or len(password) > 1024 or not check_password(row["password"], password)[0]:
            self.limits.login_failed(row["email"], now)
            return error(401, "bad_login", "Неверный пароль.")
        try:
            self.remove(row)
        except PanelError as e:
            log("account deletion failed: %s" % e)
            return error(503, "panel", "Не получилось удалить аккаунт. Попробуйте позже.")
        self.later(self.owner.tell, "Kirov VPN: аккаунт удалён по просьбе пользователя\n%s" % row["email"])
        log("account deleted by its user")
        return 200, {"ok": True}

    def remove(self, row):
        """Deletes an account, and the panel user made for it."""
        if row["panel_user"] and row["panel_owned"]:
            self.panel.delete(row["panel_user"])
        with self.db.tx() as c:
            c.execute("DELETE FROM accounts WHERE id = ?", (row["id"],))
        if row["status"] == "pending":
            self.later(self.owner.settle, row["notice"], "Kirov VPN: регистрация отменена\n%s" % row["email"])

    # -- pages

    def token_row(self, token, kind):
        """The account row of a valid one-time token, or None."""
        if not token or not TOKEN_RE.match(token):
            return None
        return self.db.one("SELECT a.*, t.password AS token_password FROM tokens t JOIN accounts a ON a.id = t.account "
                           "WHERE t.hash = ? AND t.kind = ? AND t.expires > ?",
                           (token_hash(token), kind, int(self.now())))

    def confirm(self, token):
        """-> "confirmed", "done" (it was confirmed before) or "expired"."""
        row = self.token_row(token, "confirm")
        if row is None:
            return "expired"
        now = self.now()
        with self.db.tx() as c:
            # The same token may have been used meanwhile.
            used = c.execute("DELETE FROM tokens WHERE hash = ?", (token_hash(token),)).rowcount == 0
            if used:
                return "expired"
            if row["status"] != "unconfirmed":
                return "done"
            c.execute("UPDATE accounts SET password = ?, status = 'pending', confirmed = ? WHERE id = ?",
                      (row["token_password"], int(now), row["id"]))
            c.execute("DELETE FROM tokens WHERE account = ? AND kind = 'confirm'", (row["id"],))
            decide = self.issue(c, row["id"], "decide", DECIDE_TTL, now)
        self.later(self.ask_owner, row["id"], row["email"], decide)
        log("account confirmed")
        return "confirmed"

    def ask_owner(self, account_id, address, decide):
        if not self.owner.configured:
            log("an account waits for the owner (Telegram is not set up): klaus-panel accounts")
            return
        message_id = self.owner.ask(address, self.link("decide", decide, a="approve"),
                                    self.link("decide", decide, a="reject"))
        if message_id:
            with self.db.tx() as c:
                c.execute("UPDATE accounts SET notice = ? WHERE id = ?", (message_id, account_id))

    def reset(self, token, password, again):
        """-> ("done" | "expired" | "error", the Russian error for the form)."""
        row = self.token_row(token, "reset")
        if row is None:
            return "expired", ""
        if password != again:
            return "error", "Пароли не совпадают."
        problem = password_problem(password, row["email"])
        if problem:
            return "error", problem
        hashed = hash_password(password)
        now = self.now()
        with self.db.tx() as c:
            if c.execute("DELETE FROM tokens WHERE hash = ?", (token_hash(token),)).rowcount == 0:
                return "expired", ""
            # The letter reached the mailbox: that confirms it as well.
            confirms = row["status"] == "unconfirmed"
            c.execute("UPDATE accounts SET password = ?, status = CASE status WHEN 'unconfirmed' THEN 'pending' "
                      "ELSE status END, confirmed = COALESCE(confirmed, ?) WHERE id = ?", (hashed, int(now), row["id"]))
            c.execute("DELETE FROM sessions WHERE account = ?", (row["id"],))
            c.execute("DELETE FROM tokens WHERE account = ? AND kind IN ('reset', 'confirm')", (row["id"],))
            decide = self.issue(c, row["id"], "decide", DECIDE_TTL, now) if confirms else None
        self.limits.login_ok(row["email"])
        if decide:
            self.later(self.ask_owner, row["id"], row["email"], decide)
        log("password reset")
        return "done", ""

    # -- the owner

    def decide_row(self, token):
        return self.token_row(token, "decide")

    def approve(self, row):
        """Gives an account access. -> (ok, Russian text for the owner)."""
        with self.deciding:
            row = self.db.one("SELECT * FROM accounts WHERE id = ?", (row["id"],))
            if row is None:
                return False, "Аккаунта больше нет."
            if row["status"] == "active":
                return True, "Доступ уже выдан: %s." % row["email"]
            if row["status"] == "unconfirmed":
                return False, "Почта %s ещё не подтверждена." % row["email"]
            try:
                user_id, name, url = self.panel.create(row["email"])
            except PanelError as e:
                log("approval failed: %s" % e)
                return False, "Не получилось создать пользователя в панели. Попробуйте ещё раз через минуту."
            with self.db.tx() as c:
                c.execute("UPDATE accounts SET status = 'active', decided = ?, panel_user = ?, panel_name = ?, "
                          "sub_url = ?, panel_owned = 1 WHERE id = ?", (int(self.now()), user_id, name, url, row["id"]))
                c.execute("DELETE FROM tokens WHERE account = ? AND kind = 'decide'", (row["id"],))
        self.mailer.send(row["email"], letter_approved())
        self.later(self.owner.settle, row["notice"], "Kirov VPN: доступ выдан ✅\n%s" % row["email"])
        log("account approved")
        return True, "Доступ выдан: %s (пользователь %s)." % (row["email"], name)

    def reject(self, row):
        with self.deciding:
            row = self.db.one("SELECT * FROM accounts WHERE id = ?", (row["id"],))
            if row is None:
                return False, "Аккаунта больше нет."
            if row["status"] == "rejected":
                return True, "Уже отклонено: %s." % row["email"]
            if row["status"] != "pending":
                return False, "%s: %s, отклонять нечего." % (row["email"], STATUS_RU[row["status"]])
            with self.db.tx() as c:
                c.execute("UPDATE accounts SET status = 'rejected', decided = ? WHERE id = ?", (int(self.now()), row["id"]))
                c.execute("DELETE FROM tokens WHERE account = ? AND kind = 'decide'", (row["id"],))
        self.later(self.owner.settle, row["notice"], "Kirov VPN: отклонено ❌\n%s" % row["email"])
        log("account rejected")
        return True, "Отклонено: %s." % row["email"]

    def link_user(self, row, user_id, name, url):
        """Gives an account an existing friend's panel user."""
        with self.deciding:
            row = self.db.one("SELECT * FROM accounts WHERE id = ?", (row["id"],))
            if row is None or row["status"] not in ("pending", "rejected"):
                return False, "Привязать можно только аккаунт, который ждёт решения или был отклонён."
            with self.db.tx() as c:
                c.execute("UPDATE accounts SET status = 'active', decided = ?, panel_user = ?, panel_name = ?, "
                          "sub_url = ?, panel_owned = 0 WHERE id = ?", (int(self.now()), user_id, name, url, row["id"]))
                c.execute("DELETE FROM tokens WHERE account = ? AND kind = 'decide'", (row["id"],))
        self.mailer.send(row["email"], letter_approved())
        self.later(self.owner.settle, row["notice"], "Kirov VPN: доступ выдан ✅ (пользователь %s)\n%s"
                   % (name, row["email"]))
        log("account linked to a panel user")
        return True, "%s привязан к пользователю %s." % (row["email"], name)

    # -- upkeep

    def purge(self):
        now = int(self.now())
        with self.db.tx() as c:
            c.execute("DELETE FROM accounts WHERE status = 'unconfirmed' AND created < ?", (now - UNCONFIRMED_TTL,))
            c.execute("DELETE FROM accounts WHERE status = 'rejected' AND decided < ?", (now - REJECTED_TTL,))
            c.execute("DELETE FROM sessions WHERE used < ?", (now - SESSION_IDLE,))
            c.execute("DELETE FROM tokens WHERE expires <= ?", (now,))
            c.execute("DELETE FROM counters WHERE day < ?", (day(now - 7 * 86400),))
        self.limits.purge(now)


# ---------------------------------------------------------------- pages

PAGE_CSS = """
:root{--ice:#EEF3F9;--ink:#0E1726;--slate:#556173;--blue:#0A74FF;--blue-deep:#0659D6;--red:#D7263D;
--font:-apple-system,BlinkMacSystemFont,"SF Pro Text",system-ui,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;
color-scheme:light}
*{box-sizing:border-box}
html,body{margin:0;background:var(--ice)}
body{font-family:var(--font);color:var(--ink);-webkit-font-smoothing:antialiased;min-height:100vh;
background:radial-gradient(62vmax 50vmax at 6% 0%,rgba(140,203,255,.95),rgba(140,203,255,0) 70%),
radial-gradient(56vmax 46vmax at 100% 34%,rgba(125,226,205,.85),rgba(125,226,205,0) 70%),var(--ice);
display:flex;align-items:flex-start;justify-content:center;padding:48px 16px}
main{width:100%;max-width:420px;background:rgba(255,255,255,.72);border:1px solid rgba(255,255,255,.85);
border-radius:24px;padding:28px 24px;box-shadow:0 18px 40px -14px rgba(22,44,96,.24),0 2px 6px rgba(22,44,96,.06)}
.brand{font-weight:700;font-size:15px;color:var(--slate);margin-bottom:18px}
h1{font-size:24px;line-height:1.2;margin:0 0 12px;letter-spacing:-.01em}
p{font-size:16px;line-height:1.5;margin:0 0 14px;color:var(--slate)}
.who{color:var(--ink);font-weight:600;word-break:break-all}
.err{color:var(--red);font-weight:600}
label{display:block;font-size:14px;font-weight:600;margin:14px 0 6px}
input{width:100%;font:inherit;font-size:16px;padding:12px 14px;border-radius:12px;border:1px solid rgba(14,23,38,.16);
background:#fff;color:var(--ink)}
button{margin-top:18px;width:100%;font:inherit;font-size:16px;font-weight:600;padding:14px;border:0;border-radius:14px;
background:var(--blue);color:#fff;cursor:pointer}
button:hover{background:var(--blue-deep)}
button.danger{background:var(--red)}
a{color:var(--blue)}
.alt{margin-top:16px;font-size:14px;text-align:center}
"""
PAGE_CSP = ("default-src 'none'; style-src 'sha256-%s'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"
            % base64.b64encode(hashlib.sha256(PAGE_CSS.encode()).digest()).decode())


def esc(value):
    return html.escape(str(value), quote=True)


def page(title, *paragraphs, form=""):
    """An HTML page; paragraphs are HTML already escaped by the caller."""
    body = "".join("<p>%s</p>" % p for p in paragraphs)
    return ('<!doctype html><html lang="ru"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            '<meta name="robots" content="noindex, nofollow"><meta name="referrer" content="no-referrer">'
            "<title>%s · Kirov VPN</title><style>%s</style></head><body><main>"
            '<div class="brand">Kirov VPN</div><h1>%s</h1>%s%s</main></body></html>'
            % (esc(title), PAGE_CSS, esc(title), body, form))


def hidden(name, value):
    return '<input type="hidden" name="%s" value="%s">' % (esc(name), esc(value))


EXPIRED_PAGE = page("Ссылка устарела", "Эта ссылка уже использована или больше не действует.",
                    "Если почта уже подтверждена, просто войдите в приложении. Если нет, нажмите в приложении "
                    "«Отправить письмо ещё раз» или «Забыли пароль?».")


def confirm_page(row, token):
    return page("Подтверждение почты", 'Подтвердите почту <span class="who">%s</span>, чтобы закончить '
                "регистрацию в Kirov VPN." % esc(row["email"]),
                form='<form method="post" action="/account/confirm">%s<button>Подтвердить почту</button></form>'
                % hidden("t", token))


CONFIRMED_PAGE = page("Почта подтверждена", "Осталось дождаться, когда вам откроют доступ.",
                      "Мы пришлём письмо, а серверы появятся в приложении сами.")
CONFIRMED_BEFORE_PAGE = page("Почта уже подтверждена", "Можно закрыть эту страницу и войти в приложении.")


def reset_page(row, token, problem=""):
    note = '<p class="err">%s</p>' % esc(problem) if problem else ""
    return page("Новый пароль", 'Новый пароль для <span class="who">%s</span>. Не короче 8 символов.' % esc(row["email"]),
                form='%s<form method="post" action="/account/reset">%s'
                '<label for="p1">Новый пароль</label><input id="p1" name="password" type="password" '
                'autocomplete="new-password" minlength="8" maxlength="128" required>'
                '<label for="p2">Ещё раз</label><input id="p2" name="again" type="password" '
                'autocomplete="new-password" minlength="8" maxlength="128" required>'
                "<button>Сохранить пароль</button></form>" % (note, hidden("t", token)))


RESET_DONE_PAGE = page("Пароль изменён", "Войдите в приложении с новым паролем.",
                       "На остальных устройствах тоже нужно будет войти заново.")


def decide_page(row, token, action):
    approve = action != "reject"
    other = "reject" if approve else "approve"
    return page("Новая регистрация", '<span class="who">%s</span>' % esc(row["email"]),
                "Выдать доступ к VPN?" if approve else "Отклонить регистрацию?",
                form='<form method="post" action="/account/decide">%s%s<button%s>%s</button></form>'
                '<p class="alt"><a href="/account/decide?%s">%s</a></p>'
                % (hidden("t", token), hidden("a", action), "" if approve else ' class="danger"',
                   "Выдать доступ" if approve else "Отклонить",
                   esc(urllib.parse.urlencode({"t": token, "a": other})),
                   "Нет, отклонить" if approve else "Нет, выдать доступ"))


# ---------------------------------------------------------------- HTTP

class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "klaus-accounts"
    sys_version = ""
    timeout = 15

    def log_message(self, *args):
        # Request lines carry tokens, and nothing about friends is logged.
        pass

    def send(self, status, content_type, data, extra=()):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        for name, value in extra:
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def reply(self, status, body):
        self.send(status, "application/json; charset=utf-8", json.dumps(body, ensure_ascii=False).encode())

    def show(self, html_text, status=200):
        self.send(status, "text/html; charset=utf-8", html_text.encode(), (
            ("Content-Security-Policy", PAGE_CSP), ("X-Frame-Options", "DENY"), ("X-Robots-Tag", "noindex")))

    def body(self, limit):
        """The request body, or None when it is missing, too big or chunked."""
        if self.headers.get("Transfer-Encoding"):
            return None
        try:
            length = int(self.headers.get("Content-Length") or "0")
        except ValueError:
            return None
        if length < 0:
            return None
        if length > limit:
            # Read before the answer: a socket closed with unread data
            # resets the connection, and Caddy would show 502 instead.
            self.rfile.read(min(length, MAX_DRAIN))
            return None
        return self.rfile.read(length)

    def json_body(self):
        raw = self.body(MAX_JSON)
        try:
            data = json.loads(raw) if raw else None
        except (ValueError, UnicodeDecodeError):
            data = None
        return data if isinstance(data, dict) else None

    def form(self):
        raw = self.body(MAX_FORM)
        if raw is None:
            return None
        try:
            fields = urllib.parse.parse_qs(raw.decode(), keep_blank_values=True, max_num_fields=8)
        except (ValueError, UnicodeDecodeError):
            return None
        return {k: v[0] for k, v in fields.items()}

    def bearer(self):
        scheme, _, token = self.headers.get("Authorization", "").partition(" ")
        return token.strip() if scheme.lower() == "bearer" else ""

    def query(self):
        try:
            fields = urllib.parse.parse_qs(self.path.partition("?")[2], max_num_fields=8)
        except ValueError:
            return {}
        return {k: v[0] for k, v in fields.items()}

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def route(self, method):
        app = self.server.app
        path = self.path.partition("?")[0]
        try:
            if path.startswith("/account/v1/"):
                self.api(app, method, path[len("/account/v1/"):])
            elif path in ("/account/confirm", "/account/reset", "/account/decide"):
                self.page(app, method, path[len("/account/"):])
            elif path == "/account/health" and method == "GET":
                self.reply(200, {"ok": True})
            else:
                self.reply(*NOT_FOUND)
        except Busy:
            self.reply(*error(503, "busy", "Сервер занят. Попробуйте через минуту."))
        except Exception as e:
            log("request failed: %s" % type(e).__name__)
            self.reply(*error(500, "internal", "Ошибка на сервере. Попробуйте позже."))

    def api(self, app, method, name):
        if method == "GET" and name == "me":
            self.reply(*app.me(self.bearer()))
            return
        if method != "POST":
            self.reply(*NOT_FOUND)
            return
        if name == "logout":
            self.reply(*app.logout(self.bearer()))
            return
        data = self.json_body()
        if data is None:
            self.reply(*error(400, "bad_request", "Неверный запрос."))
            return
        if name == "register":
            self.reply(*app.register(data.get("email"), data.get("password")))
        elif name == "resend":
            self.reply(*app.resend(data.get("email")))
        elif name == "login":
            self.reply(*app.login(data.get("email"), data.get("password"), data.get("device")))
        elif name == "forgot":
            self.reply(*app.forgot(data.get("email")))
        elif name == "delete":
            self.reply(*app.delete(self.bearer(), data.get("password")))
        else:
            self.reply(*NOT_FOUND)

    def page(self, app, method, name):
        fields = self.query() if method == "GET" else self.form()
        if fields is None:
            self.show(page("Неверный запрос", "Откройте ссылку из письма ещё раз."), 400)
            return
        token = fields.get("t", "")
        if name == "confirm":
            if method == "GET":
                row = app.token_row(token, "confirm")
                self.show(confirm_page(row, token) if row else EXPIRED_PAGE)
            else:
                result = app.confirm(token)
                self.show({"confirmed": CONFIRMED_PAGE, "done": CONFIRMED_BEFORE_PAGE}.get(result, EXPIRED_PAGE))
        elif name == "reset":
            row = app.token_row(token, "reset")
            if row is None:
                self.show(EXPIRED_PAGE)
            elif method == "GET":
                self.show(reset_page(row, token))
            else:
                result, problem = app.reset(token, fields.get("password", ""), fields.get("again", ""))
                if result == "error":
                    self.show(reset_page(row, token, problem))
                else:
                    self.show(RESET_DONE_PAGE if result == "done" else EXPIRED_PAGE)
        else:
            row = app.decide_row(token)
            action = fields.get("a", "")
            if action not in ("approve", "reject"):
                self.show(page("Неверный запрос", "Откройте кнопку в Telegram ещё раз."), 400)
            elif row is None:
                self.show(page("Решение уже принято", "Эта кнопка больше не действует: решение по этой регистрации "
                               "уже принято, или прошло больше двух недель. Посмотреть: klaus-panel accounts."))
            elif method == "GET":
                self.show(decide_page(row, token, action))
            else:
                ok, text = app.reject(row) if action == "reject" else app.approve(row)
                self.show(page("Готово" if ok else "Не получилось", esc(text)))


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, app):
        super().__init__(address, Handler)
        self.app = app
        self.slots = threading.BoundedSemaphore(REQUESTS_AT_ONCE)

    def process_request(self, request, client_address):
        # At most REQUESTS_AT_ONCE requests are worked on; others wait in
        # the listen queue.
        if not self.slots.acquire(timeout=5):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, request, client_address):
        # The default prints the client's address with the traceback.
        log("request failed: %s" % sys.exc_info()[0].__name__)


# ---------------------------------------------------------------- main

def in_background(fn, *args):
    threading.Thread(target=fn, args=args, daemon=True).start()


def open_app(serving=False):
    os.umask(0o077)
    db = Db(os.path.join(DATA_DIR, "accounts.db"))
    owner = Telegram()
    later = in_background if serving else None
    mailer = Mailer(db, owner, later=later)
    if serving:
        mailer.start()
    return Accounts(db, mailer, owner, Panel(), later=later)


def upkeep(app):
    while True:
        try:
            app.purge()
        except Exception as e:
            log("upkeep failed: %s" % type(e).__name__)
        time.sleep(3600)


def serve():
    app = open_app(serving=True)
    threading.Thread(target=upkeep, args=(app,), daemon=True).start()
    if not app.mailer.configured:
        log("mail is not set up (klaus-panel mail-setup): registrations are refused for now")
    if not (PANEL_TOKEN and PANEL_SQUAD):
        log("PANEL_TOKEN or PANEL_SQUAD is not set: approvals will fail")
    log("klaus-accounts on :%d; Telegram %s; mail %s, at most %d letters a day"
        % (PORT, "on" if app.owner.configured else "off", "on" if app.mailer.configured else "off", MAIL_DAILY_LIMIT))
    Server(("0.0.0.0", PORT), app).serve_forever()


def account_by_email(app, address):
    normalized = normalize_email(address)
    row = app.db.one("SELECT * FROM accounts WHERE email = ?", (normalized,)) if normalized else None
    if row is None:
        raise SystemExit("нет аккаунта %s (список: klaus-panel accounts)" % address)
    return row


def cli_list(app):
    rows = app.db.all("SELECT a.*, (SELECT COUNT(*) FROM sessions s WHERE s.account = a.id) AS devices "
                      "FROM accounts a ORDER BY a.created")
    print("\t".join(("ПОЧТА", "СОСТОЯНИЕ", "УСТРОЙСТВ", "ПОЛЬЗОВАТЕЛЬ", "ЗАРЕГИСТРИРОВАН")))
    for r in rows:
        print("\t".join((r["email"], STATUS_RU.get(r["status"], r["status"]), str(r["devices"]),
                         r["panel_name"] or "-", time.strftime("%Y-%m-%d %H:%M", time.gmtime(r["created"])))))
    waiting = sum(1 for r in rows if r["status"] == "pending")
    print("\nВсего: %d, ждут решения: %d" % (len(rows), waiting))


def cli(argv):
    # The owner reads the command's own answer; the service's log is for
    # the running service.
    global log
    log = lambda msg: None  # noqa: E731
    app = open_app()
    command, args = argv[0], argv[1:]
    if command == "list" and not args:
        cli_list(app)
        return
    if command in ("approve", "reject", "delete") and len(args) == 1:
        row = account_by_email(app, args[0])
        if command == "delete":
            try:
                app.remove(row)
            except PanelError as e:
                raise SystemExit("не получилось удалить пользователя в панели (%s); аккаунт оставлен" % e)
            print("Аккаунт %s удалён%s." % (row["email"], ", и пользователь панели %s тоже" % row["panel_name"]
                                            if row["panel_owned"] and row["panel_name"] else ""))
            return
        ok, text = app.approve(row) if command == "approve" else app.reject(row)
        if not ok:
            raise SystemExit(text)
        print(text)
        # The letter goes before the command ends.
        drain(app.mailer)
        return
    if command == "link" and len(args) == 4:
        address, user_id, name, url = args
        if not (USER_ID_RE.match(user_id) and USERNAME_RE.match(name) and url.startswith("https://")):
            raise SystemExit("неверный пользователь панели")
        ok, text = app.link_user(account_by_email(app, address), user_id, name, url)
        if not ok:
            raise SystemExit(text)
        print(text)
        drain(app.mailer)
        return
    if command == "owner-of" and len(args) == 1:
        # The account that signs in with this panel user, if any.
        row = app.db.one("SELECT email FROM accounts WHERE panel_user = ?", (args[0],))
        if row:
            print(row["email"])
        return
    if command == "backup" and len(args) == 1:
        # A consistent copy while the service runs, next to the database.
        target = os.path.join(DATA_DIR, os.path.basename(args[0]))
        copy = sqlite3.connect(target)
        with app.db.lock:
            app.db.conn.backup(copy)
        copy.close()
        print(target)
        return
    if command == "mail-test" and len(args) == 1:
        to = normalize_email(args[0])
        if not to:
            raise SystemExit("неверный адрес")
        if not app.mailer.configured:
            raise SystemExit("почта не настроена: klaus-panel mail-setup")
        try:
            smtp_deliver(to, letter("Kirov VPN: проверка почты", ["Если вы читаете это письмо, почта для "
                                    "аккаунтов Kirov VPN настроена правильно."]))
        except (smtplib.SMTPException, OSError, ssl.SSLError) as e:
            # The owner's own terminal: the server's answer helps him.
            raise SystemExit("письмо не отправлено: %s: %s" % (type(e).__name__, e))
        print("Письмо отправлено на %s." % to)
        return
    raise SystemExit("команды: list | approve ПОЧТА | reject ПОЧТА | delete ПОЧТА | "
                     "link ПОЧТА ID ИМЯ ССЫЛКА | owner-of ID | mail-test ПОЧТА | backup ФАЙЛ")


def drain(mailer):
    while not mailer.queue.empty():
        to, mail = mailer.queue.get()
        try:
            mailer.deliver(to, mail)
        except Exception as e:
            print("письмо не отправлено: %s" % type(e).__name__, file=sys.stderr)


def main():
    if len(sys.argv) < 2 or sys.argv[1] == "serve":
        serve()
    else:
        cli(sys.argv[1:])


if __name__ == "__main__":
    main()
