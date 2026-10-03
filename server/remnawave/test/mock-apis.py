#!/usr/bin/env python3
"""Mock Telegram Bot API and GitHub REST API for run-local.sh.

  mock-apis.py --port 18090 --tg-token T --gh-token G --repo klausms17/vpn \
      --tag TAG --bad-tag TAG2 --temp-tag TAG3 --apk KirovVPN-1.0.99.apk \
      --win-tag TAG4 --win-bad-tag TAG5 --exe KirovVPN-Setup-1.0.42.exe \
      --page main=page.html [--page BRANCH=FILE ...]

Telegram (/bot<token>/<method>, GET or POST, JSON or form): getMe,
getUpdates (serves the messages queued with POST /_mock/tg/say
{"chat_id": 1, "first_name": "…", "text": "…"}, long polling up to 3 s; a
negative offset keeps only the last updates, as in Telegram) and
sendMessage (kept; GET /_mock/tg/sent lists them).

GitHub: GET /repos/<repo> answers for that one repository, a private one,
only with "Bearer G" (others, and it without the token, are 404, as GitHub
says for a repository one cannot see; a wrong token is 401), and for
--public-repo also without a token;
/repos/<repo>/releases/tags/<tag> lists the APK and SHA256SUMS.txt, and
<win-tag> the Windows installer, its SHA256SUMS.txt and the version.json
manifest CI makes (in <win-bad-tag> one that names another checksum);
/repos/<repo>/releases/assets/<id> with
"Accept: application/octet-stream" redirects to http://127.0.0.2:<port>/dl/,
which, like GitHub's file storage, refuses requests that still carry the
Authorization header. The release <bad-tag> has a SHA256SUMS.txt that does
not match its APK; <temp-tag> is named like a CI build signed with the
temporary key. /repos/<repo>/contents/server/remnawave/klaus-page.html?ref=B
is the --page file given for branch B, raw for
"Accept: application/vnd.github.raw+json" and, like GitHub, JSON with the
file in base64 for any other.
"""

import argparse
import base64
import hashlib
import http.server
import json
import os
import threading
import time
import urllib.parse

LOCK = threading.Lock()
UPDATES = []
SENT = []
NEXT_ID = [1]
PAGE_PATH = "server/remnawave/klaus-page.html"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18090)
    ap.add_argument("--tg-token", required=True)
    ap.add_argument("--gh-token", required=True)
    ap.add_argument("--repo", default="klausms17/vpn")
    ap.add_argument("--public-repo", default="klausms17/open")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--bad-tag", required=True)
    ap.add_argument("--temp-tag", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--win-tag", required=True)
    ap.add_argument("--win-bad-tag", required=True)
    ap.add_argument("--exe", required=True)
    ap.add_argument("--page", action="append", default=[], help="BRANCH=FILE: the page in that branch")
    args = ap.parse_args()

    pages = {}
    for spec in args.page:
        branch, _, path = spec.partition("=")
        with open(path, "rb") as f:
            pages[branch] = f.read()

    with open(args.apk, "rb") as f:
        apk = f.read()
    name = os.path.basename(args.apk)
    good_sum = hashlib.sha256(apk).hexdigest()
    bad_sum = hashlib.sha256(apk + b"tampered").hexdigest()
    # asset id -> (name, bytes)
    assets = {
        1: (name, apk),
        2: ("SHA256SUMS.txt", ("%s  %s\n" % (good_sum, name)).encode()),
        3: (name, apk),
        4: ("SHA256SUMS.txt", ("%s  %s\n" % (bad_sum, name)).encode()),
    }
    with open(args.exe, "rb") as f:
        exe = f.read()
    exe_name = os.path.basename(args.exe)
    exe_version = exe_name[len("KirovVPN-Setup-"):-len(".exe")]

    def manifest(sha):
        return (json.dumps({"versionCode": int(exe_version.split(".")[-1]), "versionName": exe_version,
                            "file": exe_name, "size": len(exe), "sha256": sha, "minBuild": 17763}) + "\n").encode()

    exe_sum = hashlib.sha256(exe).hexdigest()
    assets.update({
        5: (exe_name, exe),
        6: ("SHA256SUMS.txt", ("%s *%s\n" % (exe_sum, exe_name)).encode()),
        7: ("version.json", manifest(exe_sum)),
        8: ("version.json", manifest(hashlib.sha256(exe + b"tampered").hexdigest())),
    })
    releases = {args.tag: [1, 2], args.bad_tag: [3, 4], args.temp_tag: [1, 2],
                args.win_tag: [5, 6, 7], args.win_bad_tag: [5, 6, 8]}
    version = name[len("KirovVPN-"):-len(".apk")]
    titles = {args.tag: "Kirov VPN %s (%s)" % (version, args.tag), args.bad_tag: "Kirov VPN %s (main)" % version,
              args.temp_tag: "Kirov VPN %s (main) — временная подпись" % version,
              args.win_tag: "Kirov VPN для Windows %s (stable)" % exe_version,
              args.win_bad_tag: "Kirov VPN для Windows %s (main)" % exe_version}

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, fmt, *a):
            print("mock: " + fmt % a, flush=True)

        def reply(self, status, body, ctype="application/json", headers=None):
            data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(data)))
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(data)

        def params(self):
            url = urllib.parse.urlsplit(self.path)
            out = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
            n = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(n) if n else b""
            if raw:
                if "json" in (self.headers.get("Content-Type") or ""):
                    out.update(json.loads(raw))
                else:
                    out.update({k: v[0] for k, v in urllib.parse.parse_qs(raw.decode()).items()})
            return url.path, out

        def do_GET(self):
            self.route()

        def do_POST(self):
            self.route()

        def route(self):
            path, p = self.params()
            if path == "/_mock/tg/say":
                with LOCK:
                    uid = NEXT_ID[0]
                    NEXT_ID[0] += 1
                    chat = {"id": int(p["chat_id"]), "type": "private", "first_name": p.get("first_name", "Owner")}
                    UPDATES.append({"update_id": uid, "message": {
                        "message_id": uid, "date": int(time.time()), "chat": chat,
                        "from": {"id": chat["id"], "is_bot": False, "first_name": chat["first_name"]},
                        "text": p.get("text", "hi")}})
                return self.reply(200, {"ok": True})
            if path == "/_mock/tg/sent":
                with LOCK:
                    return self.reply(200, list(SENT))
            if path.startswith("/bot"):
                return self.telegram(path, p)
            if path.startswith("/repos/") or path.startswith("/dl/"):
                return self.github(path, p)
            self.reply(404, {"message": "Not Found"})

        def telegram(self, path, p):
            token, _, method = path[len("/bot"):].partition("/")
            if token != args.tg_token:
                return self.reply(401, {"ok": False, "error_code": 401, "description": "Unauthorized"})
            if method == "getMe":
                return self.reply(200, {"ok": True, "result": {
                    "id": 777, "is_bot": True, "first_name": "Kirov VPN alerts", "username": "klaus_e2e_bot"}})
            if method == "getUpdates":
                offset = int(p.get("offset") or 0)
                deadline = time.time() + min(float(p.get("timeout") or 0), 3)
                while True:
                    with LOCK:
                        # Confirmed updates are gone for good, as in Telegram;
                        # offset -N forgets all but the last N.
                        if offset < 0:
                            UPDATES[:] = UPDATES[offset:]
                        else:
                            UPDATES[:] = [u for u in UPDATES if u["update_id"] >= offset]
                        if UPDATES or time.time() >= deadline:
                            return self.reply(200, {"ok": True, "result": list(UPDATES)})
                    time.sleep(0.2)
            if method == "sendMessage":
                with LOCK:
                    SENT.append({"chat_id": str(p.get("chat_id")), "text": p.get("text", ""),
                                 "parse_mode": p.get("parse_mode")})
                return self.reply(200, {"ok": True, "result": {"message_id": len(SENT)}})
            self.reply(404, {"ok": False, "error_code": 404, "description": "Not Found"})

        def github(self, path, p):
            if path.startswith("/dl/"):
                if self.headers.get("Authorization"):
                    return self.reply(400, b"Only one auth mechanism allowed", "text/plain")
                aid = int(path[len("/dl/"):])
                return self.reply(200, assets[aid][1], "application/octet-stream")
            auth = self.headers.get("Authorization")
            if auth is not None and auth != "Bearer " + args.gh_token:
                return self.reply(401, {"message": "Bad credentials"})
            repo = next((r for r in (args.repo, args.public_repo)
                         if path == "/repos/" + r or path.startswith("/repos/%s/" % r)), None)
            if repo is None or (repo == args.repo and auth is None):
                return self.reply(404, {"message": "Not Found"})
            base = "http://127.0.0.1:%d/repos/%s" % (args.port, repo)
            if path == "/repos/" + repo:
                return self.reply(200, {"full_name": repo, "private": repo == args.repo})
            if path.startswith("/repos/%s/contents/" % repo):
                page = pages.get(p.get("ref", ""))
                if path != "/repos/%s/contents/%s" % (repo, PAGE_PATH) or page is None:
                    return self.reply(404, {"message": "Not Found"})
                if self.headers.get("Accept") != "application/vnd.github.raw+json":
                    return self.reply(200, {"type": "file", "path": PAGE_PATH, "encoding": "base64",
                                            "content": base64.b64encode(page).decode()})
                return self.reply(200, page, "text/plain; charset=utf-8")
            prefix = "/repos/%s/releases/" % repo
            if not path.startswith(prefix):
                return self.reply(404, {"message": "Not Found"})
            rest = path[len(prefix):]
            if rest.startswith("tags/"):
                tag = urllib.parse.unquote(rest[len("tags/"):])
                if tag not in releases:
                    return self.reply(404, {"message": "Not Found"})
                return self.reply(200, {"tag_name": tag, "name": titles[tag], "prerelease": True, "assets": [
                    {"id": i, "name": assets[i][0], "size": len(assets[i][1]),
                     "url": "%s/releases/assets/%d" % (base, i),
                     "browser_download_url": "https://github.com/%s/releases/download/%s/%s" % (args.repo, tag, assets[i][0])}
                    for i in releases[tag]]})
            if rest.startswith("assets/"):
                aid = int(rest[len("assets/"):])
                if aid not in assets:
                    return self.reply(404, {"message": "Not Found"})
                if self.headers.get("Accept") != "application/octet-stream":
                    return self.reply(200, {"id": aid, "name": assets[aid][0]})
                return self.reply(302, b"", "text/plain",
                                  {"Location": "http://127.0.0.2:%d/dl/%d?X-Amz-Signature=e2e" % (args.port, aid)})
            self.reply(404, {"message": "Not Found"})

    server = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    server.daemon_threads = True
    print("mock Telegram and GitHub APIs on :%d" % args.port, flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
