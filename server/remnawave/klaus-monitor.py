#!/usr/bin/env python3
"""klaus-monitor: block reports from friends' Klaus VPN apps -> Telegram.

Runs on the panel host next to Remnawave (install-panel.sh starts it in the
official python:3-alpine image with this file mounted read-only; standard
library only), behind Caddy at https://<SUB_DOMAIN>/klaus/:

  GET /klaus/report?s=&h=&p=&k=&n=&o=&v=&w=&a=
      sent by the app after its auto-failover switched AWAY from a server
      that stopped answering (so the phone itself has internet): s is the
      friend's subscription id, h/p/k the failed server, n the network type
      (wifi|mobile|other), o the mobile operator, v the app version. w=1:
      the app moved from a server outside the mobile operator's whitelist
      to one inside it (the "whitelist" regime, not a block). a=1: no
      server answered at all while a Russian site opened directly; it
      counts like any other report.
  GET /klaus/health

When REPORT_THRESHOLD different subscriptions report the same server within
REPORT_WINDOW_MIN minutes, one Russian alert goes to Telegram, then that
server is quiet for REPORT_COOLDOWN_MIN minutes. w=1 reports do not count
towards that alert: when only they reach the threshold, a separate note
says the server is not blocked (never "disable it"), with its own quiet
time.

Privacy: the client's IP is never logged or stored (requests are not logged
at all). Subscription ids stay only in memory (the panel's answer about an id
for 10 minutes, who reported which server for the report window) and are
never logged or sent anywhere but to the panel's own API.

The panel token can only look up one subscription by its id, never list
them: this service faces the internet, and a list would hold every friend's
keys.

Settings (environment, written to klaus-monitor.env by klaus-panel):
PANEL_URL, PANEL_TOKEN, TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID,
TELEGRAM_API_BASE, REPORT_THRESHOLD, REPORT_WINDOW_MIN, REPORT_COOLDOWN_MIN,
RATE_LIMIT_PER_HOUR, LISTEN_PORT.
"""

import collections
import http.client
import http.server
import json
import os
import re
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


PANEL_URL = (os.environ.get("PANEL_URL") or "http://remnawave:3000").rstrip("/")
PANEL_TOKEN = os.environ.get("PANEL_TOKEN", "")
TG_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
TG_CHAT = os.environ.get("TELEGRAM_CHAT_ID", "")
TG_API = (os.environ.get("TELEGRAM_API_BASE") or "https://api.telegram.org").rstrip("/")
THRESHOLD = env_int("REPORT_THRESHOLD", 2, 1, 100)
WINDOW = env_int("REPORT_WINDOW_MIN", 20, 1, 24 * 60) * 60
COOLDOWN = env_int("REPORT_COOLDOWN_MIN", 180, 1, 7 * 24 * 60) * 60
RATE_LIMIT = env_int("RATE_LIMIT_PER_HOUR", 30, 1, 10000)
PORT = env_int("LISTEN_PORT", 8080, 1, 65535)

KNOWN_TTL = 10 * 60  # how long a panel answer about a subscription is kept
MAX_KNOWN = 5000  # answers kept, the oldest go first
# An id not known yet costs one lookup (the panel finds a user by its short
# id). There is no budget per minute that made-up ids could use up and so
# lock friends out: anyone can make the panel do the same lookup by opening
# https://SUB_DOMAIN/<id> anyway. Only the lookups in flight are capped.
LOOKUPS_AT_ONCE = 16
LOOKUP_WAIT = 5  # seconds a report waits for a free lookup, then 503
HOSTS_TTL = 60  # the panel's server list
MAX_TRACKED = 20000  # entries per table, whatever happens

SHORT_UUID_RE = re.compile(r"^[A-Za-z0-9_-]{6,64}$")
HOST_RE = re.compile(r"^[A-Za-z0-9.:\[\]_-]{1,253}$")
PORT_RE = re.compile(r"^[0-9]{1,5}$")
PROTO_RE = re.compile(r"^[A-Za-z0-9_-]{0,20}$")
VERSION_RE = re.compile(r"^[A-Za-z0-9._+-]{0,40}$")


def log(msg):
    print(time.strftime("%Y-%m-%d %H:%M:%S ") + msg, flush=True)


class PanelError(Exception):
    pass


def panel_get(path):
    """GET a panel API path -> parsed JSON; None on 404."""
    req = urllib.request.Request(PANEL_URL + path, headers={
        "Authorization": "Bearer " + PANEL_TOKEN,
        # The panel only answers requests that come through its HTTPS proxy.
        "X-Forwarded-For": "127.0.0.1",
        "X-Forwarded-Proto": "https",
        "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise PanelError("panel API: HTTP %d" % e.code) from None
    except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError) as e:
        raise PanelError("panel API: %s" % type(e).__name__) from None


def panel_response(path):
    """The "response" object of a panel API answer; PanelError on anything else."""
    data = panel_get(path)
    response = data.get("response") if isinstance(data, dict) else None
    if response is None:
        raise PanelError("panel API: unexpected answer")
    return response


def telegram_send(text):
    # "chat" or "chat:topic", as the panel takes TELEGRAM_NOTIFY_NODES.
    chat, _, thread = TG_CHAT.partition(":")
    msg = {"chat_id": chat, "text": text, "link_preview_options": {"is_disabled": True}}
    if thread.isdigit():
        msg["message_thread_id"] = int(thread)
    body = json.dumps(msg).encode()
    req = urllib.request.Request("%s/bot%s/sendMessage" % (TG_API, TG_TOKEN), data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.load(resp).get("ok") is True
    except urllib.error.HTTPError as e:
        # Never the URL: it holds the bot token.
        log("telegram: HTTP %d" % e.code)
    except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError, AttributeError) as e:
        log("telegram: %s" % type(e).__name__)
    return False


def clean_operator(value):
    value = "".join(c for c in value if c.isprintable())
    return " ".join(value.split())[:40]


def network_label(kind, operator):
    if kind == "wifi":
        return "Wi-Fi"
    if kind == "mobile":
        return operator or "мобильный интернет"
    return "другая сеть"


def people(n):
    return "%d %s" % (n, "человека" if n % 10 == 1 and n % 100 != 11 else "человек")


def duration(seconds):
    minutes = seconds // 60
    if minutes % 60 == 0:
        return "%d ч" % (minutes // 60)
    return "%d мин" % minutes


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.known = collections.OrderedDict()  # shortUuid -> (active subscription, expires), oldest first
        self.lookups = threading.BoundedSemaphore(LOOKUPS_AT_ONCE)
        self.rate = {}  # shortUuid -> deque of report times, last hour
        self.reports = {}  # (host, port) -> {shortUuid: (time, network label, whitelist)}
        # (host, port) -> quiet until, for the blocking alert;
        # (host, port, "whitelist") for the whitelist note.
        self.cooldown = {}
        self.hosts = (0.0, [])  # (fetched at, panel hosts)
        self.purged = time.monotonic()

    def remember(self, s, ok, expires):
        self.known.pop(s, None)
        self.known[s] = (ok, expires)
        while len(self.known) > MAX_KNOWN:
            self.known.popitem(last=False)

    def purge(self, now):
        if now - self.purged < 60 and all(len(t) < MAX_TRACKED for t in (self.rate, self.reports)):
            return
        self.purged = now
        for s in [s for s, v in self.known.items() if v[1] <= now]:
            del self.known[s]
        for s in list(self.rate):
            q = self.rate[s]
            while q and q[0] <= now - 3600:
                q.popleft()
            if not q:
                del self.rate[s]
        for key in list(self.reports):
            fresh = {s: r for s, r in self.reports[key].items() if r[0] > now - WINDOW}
            if fresh:
                self.reports[key] = fresh
            else:
                del self.reports[key]
        self.cooldown = {k: t for k, t in self.cooldown.items() if t > now}
        # Last resort: forget, never grow.
        if len(self.rate) >= MAX_TRACKED:
            self.rate.clear()
        if len(self.reports) >= MAX_TRACKED:
            self.reports.clear()


STATE = State()


def subscription_ok(s):
    """True/False, or None when the panel cannot be asked right now."""
    now = time.monotonic()
    with STATE.lock:
        cached = STATE.known.get(s)
        if cached and cached[1] > now:
            return cached[0]
    if not STATE.lookups.acquire(timeout=LOOKUP_WAIT):
        return None
    try:
        user = panel_get("/api/users/by-short-uuid/" + urllib.parse.quote(s, safe=""))
    except PanelError as e:
        log(str(e))
        return None
    finally:
        STATE.lookups.release()
    # A disabled or expired friend cannot use any server anyway: his
    # failures say nothing about blocking.
    response = user.get("response") if isinstance(user, dict) else None
    ok = isinstance(response, dict) and response.get("status") == "ACTIVE"
    with STATE.lock:
        STATE.remember(s, ok, time.monotonic() + KNOWN_TTL)
    return ok


def panel_hosts():
    now = time.monotonic()
    with STATE.lock:
        fetched, hosts = STATE.hosts
        if now - fetched < HOSTS_TTL:
            return hosts
    hosts = panel_response("/api/hosts")
    if not isinstance(hosts, list):
        raise PanelError("panel API: unexpected host list")
    hosts = [h for h in hosts if isinstance(h, dict)]
    with STATE.lock:
        STATE.hosts = (time.monotonic(), hosts)
    return hosts


def norm_host(h):
    return h.strip("[]").lower()


def find_host(h, p):
    """The panel's enabled host for this address and port, or None."""
    for host in panel_hosts():
        if host.get("isDisabled"):
            continue
        if norm_host(str(host.get("address", ""))) == h and host.get("port") == p:
            return host
    return None


def node_state(host):
    """-> (names of the host's servers, "online" | "offline" | "unknown")."""
    try:
        listed = panel_response("/api/nodes")
        if not isinstance(listed, list):
            raise PanelError("panel API: unexpected node list")
        nodes = {n.get("uuid"): n for n in listed if isinstance(n, dict)}
        linked = [nodes[u] for u in host.get("nodes") or [] if isinstance(u, str) and u in nodes]
        online = [n for n in linked if n.get("isConnected") and not n.get("isDisabled")]
        names = [n.get("name") for n in (online or linked) if n.get("name")]
        if online:
            return names, "online"
        if linked:
            return names, "offline"
        return names, "unknown"
    except PanelError as e:
        log(str(e))
        return [], "unknown"


def network_mix(labels):
    mix = collections.Counter(labels)
    return ", ".join("%s ×%d" % (label, count) for label, count in
                     sorted(mix.items(), key=lambda item: (-item[1], item[0])))


def alert_text(host, h, p, reports):
    """The blocking alert. reports: [(network label, whitelist)]."""
    node_names, state = node_state(host)
    name = (host.get("remark") or "").strip() or (node_names[0] if node_names else "%s:%d" % (h, p))
    labels = [label + (" (белые списки)" if whitelist else "") for label, whitelist in reports]
    lines = [
        "Klaus VPN: сервер «%s» не отвечает у %s за последние %s." % (name, people(len(labels)), duration(WINDOW)),
        "Сети: %s." % network_mix(labels),
    ]
    disable = "klaus-panel disable-node %s" % node_names[0] if node_names else "klaus-panel disable-node …"
    if state == "online":
        lines.append("Панель видит сервер на связи — похоже на блокировку: добавьте новый сервер "
                     "(klaus-panel add-node …) и отключите этот (%s)." % disable)
    elif state == "offline":
        lines.append("Панель тоже не видит сервер: сервер недоступен и с панели. Проверьте VPS у хостера "
                     "(включён ли, оплачен ли); если он не вернётся, добавьте новый (klaus-panel add-node …) "
                     "и отключите этот (%s)." % disable)
    else:
        lines.append("Состояние сервера на панели узнать не удалось: посмотрите klaus-panel list-nodes.")
    lines.append("Следующее сообщение об этом сервере — не раньше чем через %s." % duration(COOLDOWN))
    return name, "\n".join(lines)


def whitelist_text(host, h, p, reports):
    """The note for w=1 reports: under the mobile whitelist every foreign
    server fails the same way, so a new one would not help. It never
    suggests disable-node. The app cannot always tell the whitelist from a
    block of this one server on mobile networks only, so the note says
    "most likely" and that reports from other networks still raise the
    usual alert."""
    node_names, state = node_state(host)
    name = (host.get("remark") or "").strip() or (node_names[0] if node_names else "%s:%d" % (h, p))
    lines = [
        "Klaus VPN: мобильный интернет в режиме белых списков у %s за последние %s: сервер «%s» у них "
        "не открывается, приложение перешло на сервер из белого списка." % (people(len(reports)), duration(WINDOW), name),
        "Сети: %s." % network_mix([label for label, _ in reports]),
        "Скорее всего, оператор открывает только сайты из белого списка: тогда не работает ни один "
        "зарубежный сервер, и отключать или менять этот не нужно. Если на сервер пожалуются и из других "
        "сетей, например по Wi-Fi, придёт обычное сообщение о блокировке.",
    ]
    if state == "offline":
        lines.append("Но панель тоже не видит сервер: проверьте VPS у хостера (включён ли, оплачен ли).")
    elif state == "unknown":
        lines.append("Состояние сервера на панели узнать не удалось: посмотрите klaus-panel list-nodes.")
    lines.append("Следующее такое сообщение об этом сервере — не раньше чем через %s." % duration(COOLDOWN))
    return name, "\n".join(lines)


def send_alert(quiet_key, host, reports, whitelist):
    kind = "whitelist note" if whitelist else "alert"
    try:
        make = whitelist_text if whitelist else alert_text
        name, text = make(host, quiet_key[0], quiet_key[1], reports)
        if not (TG_TOKEN and TG_CHAT):
            log("%s for «%s» (%d people), Telegram is not set up: klaus-panel telegram-setup"
                % (kind, name, len(reports)))
            return
        if telegram_send(text):
            log("%s sent: «%s», %d people" % (kind, name, len(reports)))
            return
    except Exception as e:  # whatever it was, the alert must not stay muted
        log("%s failed: %s" % (kind, type(e).__name__))
    # Not delivered: the next report may try again.
    with STATE.lock:
        STATE.cooldown.pop(quiet_key, None)


def handle_report(query):
    """-> (HTTP status, JSON body)."""
    def one(name):
        values = query.get(name) or [""]
        return values[0].strip()

    s, h, p, k, n, o, v, w = (one(x) for x in "shpknovw")
    if not (SHORT_UUID_RE.match(s) and HOST_RE.match(h) and PORT_RE.match(p) and 0 < int(p) < 65536
            and PROTO_RE.match(k) and VERSION_RE.match(v)):
        return 400, {"ok": False}
    # The app knows best whether the whitelist explains the failure (a 4G
    # router's Wi-Fi has one too), so the flag is taken as it is.
    whitelist = w == "1"
    known = subscription_ok(s)
    if known is None:
        return 503, {"ok": False}
    if not known:
        return 403, {"ok": False}
    now = time.monotonic()
    with STATE.lock:
        STATE.purge(now)
        times = STATE.rate.setdefault(s, collections.deque())
        while times and times[0] <= now - 3600:
            times.popleft()
        if len(times) >= RATE_LIMIT:
            return 429, {"ok": False}
        times.append(now)
    key = (norm_host(h), int(p))
    try:
        host = find_host(*key)
    except PanelError as e:
        log(str(e))
        return 503, {"ok": False}
    if host is None:
        # Not (or no longer) a server of the panel: nothing to tell.
        return 200, {"ok": True}
    kind = n if n in ("wifi", "mobile") else "other"
    label = network_label(kind, clean_operator(o) if kind == "mobile" else "")
    note_key = key + ("whitelist",)
    with STATE.lock:
        reports = STATE.reports.setdefault(key, {})
        reports[s] = (now, label, whitelist)
        fresh = [r for r in reports.values() if r[0] > now - WINDOW]
        listed = [r for r in fresh if r[2]]
        if STATE.cooldown.get(key, 0) > now:
            return 200, {"ok": True}
        # A whitelist report says nothing about blocking: the alert needs
        # enough of the others. It lists everybody, marked.
        if len(fresh) - len(listed) >= THRESHOLD:
            quiet_key, chosen, note = key, fresh, False
        elif len(listed) >= THRESHOLD and STATE.cooldown.get(note_key, 0) <= now:
            quiet_key, chosen, note = note_key, listed, True
        else:
            return 200, {"ok": True}
        STATE.cooldown[quiet_key] = now + COOLDOWN
        chosen = [(r[1], r[2]) for r in chosen]
    threading.Thread(target=send_alert, args=(quiet_key, host, chosen, note), daemon=True).start()
    return 200, {"ok": True}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "klaus-monitor"
    sys_version = ""
    timeout = 15

    def log_message(self, *args):
        # Request lines would carry the client's IP and subscription id.
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path, _, query = self.path.partition("?")
        if path == "/klaus/health":
            # Docker asks every 30 s: old reports go even when none come.
            with STATE.lock:
                STATE.purge(time.monotonic())
            self.reply(200, {"ok": True})
        elif path == "/klaus/report":
            if len(query) > 2048:
                self.reply(400, {"ok": False})
                return
            try:
                status, body = handle_report(urllib.parse.parse_qs(query))
            except Exception as e:
                # An answer all the same: the app then tries again later.
                log("report failed: %s" % type(e).__name__)
                status, body = 500, {"ok": False}
            self.reply(status, body)
        else:
            self.reply(404, {"ok": False})


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # The default prints the client's address with the traceback.
        log("request failed: %s" % sys.exc_info()[0].__name__)


def main():
    if not PANEL_TOKEN:
        log("PANEL_TOKEN is not set: every report will be refused")
    log("klaus-monitor on :%d; Telegram %s; alert after %d people in %d min, then quiet for %d min; %d reports/h each"
        % (PORT, "on" if TG_TOKEN and TG_CHAT else "off", THRESHOLD, WINDOW // 60, COOLDOWN // 60, RATE_LIMIT))
    Server(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
