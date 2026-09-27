#!/usr/bin/env python3
"""Prototype (stdlib only) of the iPhone UDID enrollment endpoints that
klaus-monitor.py (or a sibling container) could serve behind Caddy:

  GET  /klaus/ios/enroll?s=<shortUuid>   -> "Profile Service" .mobileconfig
       (unsigned; per-request Challenge bound to the friend's subscription)
  POST /klaus/ios/udid                   <- device-signed CMS plist
       (UDID, PRODUCT, VERSION, CHALLENGE) -> 301 to /klaus/ios/done?t=<ticket>
  GET  /klaus/ios/done?t=<ticket>        -> status page in Safari

Pending devices go to SPOOL (a JSON file, mode 600) that the host side
(klaus-panel on a timer, root) reads to dispatch the GitHub workflow. The UDID
is never logged, never put in a URL and never sent to Telegram.
"""

import http.server
import json
import os
import plistlib
import re
import secrets
import sys
import threading
import time
import urllib.parse
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cms_plist import CMSError, signed_plist  # noqa: E402

PUBLIC = os.environ.get("PUBLIC_BASE", "https://sub.example.com").rstrip("/")
SPOOL = os.environ.get("IOS_SPOOL", "/data/ios-pending.json")
PER_SUB_MAX = int(os.environ.get("IOS_DEVICES_PER_SUB", "2"))
GLOBAL_MAX = int(os.environ.get("IOS_DEVICES_MAX", "95"))  # keep a few of the 100 slots
CHALLENGE_TTL = 15 * 60  # iOS deletes a downloaded profile not installed in 8 min
ENROLLS_PER_SUB_HOUR = 6
MAX_BODY = 64 * 1024

SHORT_UUID_RE = re.compile(r"^[A-Za-z0-9_-]{6,64}$")
UDID_RE = re.compile(r"^(?:[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40})$")
PRODUCT_RE = re.compile(r"^(?:iPhone|iPad|iPod)[0-9]{1,3},[0-9]{1,3}$")
VERSION_RE = re.compile(r"^[0-9A-Za-z]{3,12}$")
TICKET_RE = re.compile(r"^[A-Za-z0-9_-]{20,64}$")

LOCK = threading.Lock()
CHALLENGES = {}  # challenge -> (shortUuid, expires)
TICKETS = {}  # ticket -> device record key (in memory; the spool keeps the record)
ENROLLS = {}  # shortUuid -> [times]


def subscription_ok(s):
    """In klaus-monitor this is the existing panel lookup (ACTIVE only)."""
    return bool(SHORT_UUID_RE.match(s))


def load_spool():
    try:
        with open(SPOOL, "rb") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_spool(data):
    tmp = SPOOL + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f)
    os.replace(tmp, SPOOL)


def profile_service(challenge):
    return plistlib.dumps({
        "PayloadType": "Profile Service",  # literal, do not change
        "PayloadVersion": 1,
        "PayloadIdentifier": "com.klausms.vpn.udid",
        "PayloadUUID": str(uuid.uuid4()).upper(),
        "PayloadOrganization": "Klaus VPN",
        "PayloadDisplayName": "Klaus VPN: регистрация iPhone",
        "PayloadDescription": "Передаёт владельцу Klaus VPN идентификатор (UDID) и модель этого "
                              "iPhone, чтобы он мог собрать для него приложение. Профиль не "
                              "остаётся на телефоне и ничего не меняет в настройках.",
        "PayloadContent": {
            "URL": PUBLIC + "/klaus/ios/udid",
            # Only what is needed: no IMEI, SERIAL, DEVICE_NAME, MAC.
            "DeviceAttributes": ["UDID", "PRODUCT", "VERSION"],
            "Challenge": challenge,
        },
    })


def page(title, text):
    body = ("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
            "<title>%s</title><body style='font:17px -apple-system;margin:24px'><h2>%s</h2><p>%s</p>"
            % (title, title, text))
    return body.encode()


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "klaus-monitor"
    sys_version = ""
    timeout = 15

    def log_message(self, *args):
        pass  # request lines carry subscription ids

    def send(self, status, body=b"", ctype="text/plain; charset=utf-8", headers=()):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path, _, query = self.path.partition("?")
        q = urllib.parse.parse_qs(query)
        if path == "/klaus/ios/enroll":
            s = (q.get("s") or [""])[0]
            if not SHORT_UUID_RE.match(s) or not subscription_ok(s):
                return self.send(403, page("Ссылка не подходит", "Попросите новую ссылку."),
                                 "text/html; charset=utf-8")
            now = time.monotonic()
            with LOCK:
                times = [t for t in ENROLLS.get(s, []) if t > now - 3600]
                if len(times) >= ENROLLS_PER_SUB_HOUR:
                    return self.send(429, b"too many")
                ENROLLS[s] = times + [now]
                for c in [c for c, v in CHALLENGES.items() if v[1] <= now]:
                    del CHALLENGES[c]
                challenge = secrets.token_urlsafe(24)
                CHALLENGES[challenge] = (s, now + CHALLENGE_TTL)
            return self.send(200, profile_service(challenge), "application/x-apple-aspen-config",
                             [("Content-Disposition", 'attachment; filename="KlausVPN-iPhone.mobileconfig"')])
        if path == "/klaus/ios/done":
            t = (q.get("t") or [""])[0]
            with LOCK:
                known = TICKET_RE.match(t) and t in TICKETS
            if not known:
                return self.send(404, page("Не найдено", "Откройте ссылку ещё раз."), "text/html; charset=utf-8")
            return self.send(200, page("iPhone зарегистрирован",
                                       "Когда сборка для него будет готова, здесь появится кнопка "
                                       "«Установить». Обычно это 10–30 минут."), "text/html; charset=utf-8")
        return self.send(404, b"")

    def do_POST(self):
        if self.path != "/klaus/ios/udid":
            return self.send(404, b"")
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if not 0 < length <= MAX_BODY:
            return self.send(400, b"")
        body = self.rfile.read(length)
        try:
            info = signed_plist(body)  # signature NOT verified: untrusted input
        except (CMSError, ValueError, IndexError):
            return self.send(400, b"")
        udid = str(info.get("UDID", ""))
        product = str(info.get("PRODUCT", ""))
        version = str(info.get("VERSION", ""))
        challenge = str(info.get("CHALLENGE", ""))
        if not (UDID_RE.match(udid) and PRODUCT_RE.match(product) and VERSION_RE.match(version)):
            return self.send(400, b"")
        now = time.monotonic()
        with LOCK:
            bound = CHALLENGES.pop(challenge, None)  # single use
            if not bound or bound[1] <= now:
                return self.send(403, b"")
            s = bound[0]
            spool = load_spool()
            key = udid.upper() if "-" in udid else udid.lower()
            rec = spool.get(key)
            if rec is None:
                mine = [r for r in spool.values() if r.get("sub") == s]
                if len(mine) >= PER_SUB_MAX or len(spool) >= GLOBAL_MAX:
                    return self.send(301, b"", headers=[("Location", PUBLIC + "/klaus/ios/limit")])
                rec = {"sub": s, "product": product, "version": version,
                       "added": int(time.time()), "state": "pending"}
                spool[key] = rec
                save_spool(spool)
            ticket = secrets.token_urlsafe(24)
            TICKETS[ticket] = key
        # 301, not 302: with 302 iOS reports the profile as invalid (community reports).
        return self.send(301, b"", headers=[("Location", PUBLIC + "/klaus/ios/done?t=" + ticket)])


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8099
    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
