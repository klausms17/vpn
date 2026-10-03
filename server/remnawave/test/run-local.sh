#!/usr/bin/env bash
# Local end-to-end test of server/remnawave. Not needed on real servers.
#
# First the monitor's unit tests (monitor_test.py, no Docker). Then brings
# the whole friends' setup up on this one machine, with the real scripts
# and images, and checks that a friend's subscription really works:
#
#   install-panel.sh  panel, database, valkey, subscription page and Caddy
#                     (with its internal CA instead of Let's Encrypt), run
#                     twice to check that a re-run changes nothing, then
#                     with a new SUB_DOMAIN and SUPPORT_URL and back
#   klaus-panel       add-node (panel -> node over the Docker bridge, friends
#                     -> node on 127.0.0.1), add-user, disable/enable,
#                     tidy-users, hwid-limit, backup
#   install-node.sh   remnanode on the host network, run again with only
#                     PANEL_IP (as after a panel move)
#   telegram-setup    against a mock Telegram API (test/mock-apis.py): the
#                     chat is taken from getUpdates, a test message goes out,
#                     the panel's own node messages arrive too
#   publish-apk       against a mock GitHub API with a fake "stable"
#                     release (the default; a panel that saved the old
#                     default, the work branch's test builds, moves to it
#                     once, a branch chosen on purpose stays); an open
#                     repository needs no token, a closed one does
#   publish-windows   the same for a fake "windows-stable" release with
#                     the installer, its checksum and CI's manifest (one
#                     that does not match is refused)
#   update-page       the friends' page from the mock's "main" branch (the
#                     default; PAGE_BRANCH= keeps the page of the folder),
#                     a broken or cut one refused, a missing one quiet for
#                     the timer
#
# and then
#   (a) the app's User-Agent gets a base64 list with a vless REALITY link
#       and the klaus-report-url / klaus-app-url headers,
#   (b) a browser gets Kirov VPN's own page (klaus-page.html): the
#       klausvpn://add/… button, its settings, nothing from other sites,
#   (c) the device from X-Hwid is recorded in the panel without the
#       friend's IP; no history of subscription downloads and no log of the
#       subscription page,
#   (d) the app's own Go core (libxray, via test/e2e) parses the
#       subscription and fetches a page through the node,
#   plus: a disabled friend is refused by the node, a friend made as the
#   panel's web form makes one (no squad, end date tomorrow) gets the
#   servers and no end date from tidy-users while older and hand-set users
#   stay, a disabled node leaves the subscription, the device limit works, a domain change reaches Caddy,
#   the support link follows SUPPORT_URL (never the panel's placeholder),
#   the node keeps its custom port on a re-run, the node's Xray keeps idle
#   connections 30 minutes and no access log, with addresses masked in its
#   error log (the profile's policy and log settings), the APK is published
#   with a verified checksum (a wrong one is refused, a missing release is
#   quiet for the timer) on https://SUB/app/ with version.json and the
#   page's download button (and its Samsung and Huawei tip), the Windows
#   installer on https://SUB/app/windows/ as a download with the same
#   rules and the page's Windows steps, a PC as one more device, the page
#   entry, remark and published build of the app's former name taken
#   over by setup, block reports (unknown friend refused, one
#   friend twice is no alert, two friends in the mobile whitelist regime
#   are one note that never says "disable", two friends are exactly one
#   Russian alert, then quiet; rate limit; no IPs or ids in the logs),
#   and a backup restored into a fresh panel serves the same link, the app
#   (both builds) and the monitor, also after failed attempts (which leave no
#   half-restored database, and a run without RESTORE refuses to build an
#   empty panel over them).
#
# Everything is removed at the end (the images stay); KEEP=1 leaves it
# running. Logs stay in $WORK.
#
# Needs root (it adds 11.11.11.11 to lo for the test page: the profile
# blocks private addresses), Docker with compose, go, python3, jq, curl,
# openssl, iproute2, the free ports 80, 443, 3000, 3001, 3010, 6767, 42222,
# 44443, 44080, 18090 and no real Remnawave on this machine. Missing images
# are pulled from mirror.gcr.io (Docker Hub limits anonymous pulls).
#
#   sudo bash server/remnawave/test/run-local.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RWS="$(cd "$HERE/.." && pwd)"
WORK="${WORK:-$(mktemp -d /tmp/klausvpn-rw-test.XXXXXX)}"
KEEP="${KEEP:-0}"
PANEL_DOMAIN=panel.klaus.test
SUB_DOMAIN=sub.klaus.test
SUB_DOMAIN2=sub2.klaus.test # a domain change on a re-run
SUPPORT=https://t.me/klaus_e2e
NODE_PORT=42222
VPN_PORT=44443
TARGET=127.0.0.1:44080 # the site REALITY imitates: a local TLS 1.3 server
WEB_IP=11.11.11.11
WEB="$WEB_IP:18080"
TOKEN="klaus-e2e-$RANDOM$RANDOM"
HWID=5f2a9c1e7b3d4e6f8a0b1c2d3e4f5a6b
HWID_PC=c0ffee11c0ffee22c0ffee33c0ffee44 # the same friend's PC
UA_APP='KlausVPN/1.0.99 (Android)' # the app's User-Agent keeps its former name on purpose
UA_BROWSER='Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36'
IMAGES="remnawave/backend:3 postgres:18.4 valkey/valkey:9-alpine remnawave/subscription-page:latest caddy:2 remnawave/node:latest python:3-alpine"
MOCK_PORT=18090 # mock Telegram and GitHub APIs
TG_BOT_TOKEN=123456789:AAklaus-e2e-bot-token-0123456789abcdef
TG_CHAT=4242
GH_TEST_TOKEN=github_pat_klaus_e2e_0123456789
RELEASE=stable
RELEASE_OLD=build-claude-compassionate-mayer-6jph8m # the default before "stable"
APK_VERSION=1.0.99
EXE_VERSION=1.0.42
SPOOFED_IP=203.0.113.77 # a client IP that must never reach the monitor's log
# This machine's own tokens must never reach the panel under test.
unset GITHUB_TOKEN GH_TOKEN TELEGRAM_API_BASE PAGE_BRANCH

step() { printf '\n\033[1;36m### %s\033[0m\n' "$*"; }
pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- checks
[ "$(id -u)" -eq 0 ] || fail "run as root"
for c in docker go python3 jq curl openssl ip base64 sha256sum; do command -v "$c" >/dev/null || fail "missing $c"; done
# The monitor's counting first: seconds, no Docker.
python3 "$HERE/monitor_test.py" || fail "klaus-monitor unit tests"
docker info >/dev/null 2>&1 || fail "docker is not running"
if [ -e /opt/remnawave ] || [ -e /opt/remnanode ]; then fail "this machine has a real Remnawave setup; not touching it"; fi
for c in remnawave remnawave-db remnawave-redis remnawave-subscription-page caddy remnanode klaus-monitor; do
  docker inspect "$c" >/dev/null 2>&1 && fail "container $c already exists"
done
for img in $IMAGES; do
  docker image inspect "$img" >/dev/null 2>&1 && continue
  src="mirror.gcr.io/$img"
  [[ "$img" == */* ]] || src="mirror.gcr.io/library/$img"
  echo "pulling $img from $src"
  docker pull -q "$src" >/dev/null || fail "cannot pull $img"
  docker tag "$src" "$img"
done
mkdir -p "$WORK"
echo "work dir: $WORK"

# ---------------------------------------------------------------- teardown
SERVE_PID=""
MOCK_PID=""
teardown() {
  local rc=$?
  if [ "$KEEP" = "1" ]; then
    echo "KEEP=1: everything is still running (work dir $WORK)"
    return
  fi
  for d in "$WORK/opt" "$WORK/opt2"; do
    if [ -f "$d/docker-compose.yml" ]; then docker compose --project-directory "$d" down -v >/dev/null 2>&1 || true; fi
  done
  if [ -f "$WORK/node/docker-compose.yml" ]; then docker compose --project-directory "$WORK/node" down >/dev/null 2>&1 || true; fi
  if [ -n "$SERVE_PID" ]; then kill "$SERVE_PID" 2>/dev/null || true; fi
  if [ -n "$MOCK_PID" ]; then kill "$MOCK_PID" 2>/dev/null || true; fi
  ip addr del "$WEB_IP/32" dev lo 2>/dev/null || true
  echo
  if [ "$rc" -eq 0 ]; then echo "ALL CHECKS PASSED (containers removed, images kept; logs in $WORK)"; else echo "FAILED (logs in $WORK)"; fi
}
trap teardown EXIT

# This sandbox-friendly override lowers the compose files' open files limit
# when the machine cannot grant 1048576 (compose picks the file up itself).
limits_override() { # DIR SERVICE...
  local dir="$1" hard
  shift
  hard="$(ulimit -Hn)"
  if [ "$hard" = "unlimited" ] || [ "$hard" -ge 1048576 ]; then return 0; fi
  mkdir -p "$dir"
  {
    echo "services:"
    for s in "$@"; do printf '  %s:\n    ulimits:\n      nofile:\n        soft: %s\n        hard: %s\n' "$s" "$hard" "$hard"; done
  } > "$dir/docker-compose.override.yml"
}

# ---------------------------------------------------------------- helpers
# The mock APIs are local: no proxy of this machine in between.
kp() { no_proxy='*' NO_PROXY='*' KLAUS_PANEL_CONF="$CONF" bash "$RWS/klaus-panel" "$@"; }
mock() { # PATH [curl args]: the mock APIs' own test endpoints
  local path="$1"
  shift
  curl -fsS --noproxy '*' "$@" "http://127.0.0.1:$MOCK_PORT$path"
}
sent_count() { # TEXT_PREFIX -> how many Telegram messages start with it
  mock /_mock/tg/sent | jq --arg p "$1" '[.[] | select(.text | startswith($p))] | length'
}
api() { # PATH [curl args] -> body (calls with the CLI token)
  local tok path="$1"
  shift
  # shellcheck disable=SC1090
  tok="$(. "$CONF" && printf '%s' "$API_TOKEN")"
  curl -sS --noproxy '*' -H 'X-Forwarded-For: 127.0.0.1' -H 'X-Forwarded-Proto: https' \
    -H @<(printf 'Authorization: Bearer %s\n' "$tok") "$@" "http://127.0.0.1:3000$path"
}
sub_get() { # UA URL [curl args] -> body; headers in $WORK/last.headers
  local ua="$1" url="$2"
  shift 2
  curl -sS --noproxy '*' --resolve "$SUB_DOMAIN:443:127.0.0.1" --cacert "$WORK/caddy-root.crt" \
    -A "$ua" -D "$WORK/last.headers" "$@" "$url"
}
https_get() { # NAME [curl args] -> body of https://NAME/ through Caddy
  local name="$1"
  shift
  curl -fsS --noproxy '*' --resolve "$name:443:127.0.0.1" --cacert "$WORK/caddy-root.crt" "$@" "https://$name/"
}
sub_code() { # PATH [curl args] -> HTTP status of https://SUB_DOMAIN/PATH (body in $WORK/last.body)
  local path="$1"
  shift
  curl -sS --noproxy '*' --resolve "$SUB_DOMAIN:443:127.0.0.1" --cacert "$WORK/caddy-root.crt" \
    -o "$WORK/last.body" -w '%{http_code}' "$@" "https://$SUB_DOMAIN/$path"
}
report() { # SHORT_UUID NETWORK [OPERATOR] [WHITELIST] -> HTTP status of a block report, as the app sends it
  sub_code klaus/report -A "$UA_APP" -H "X-Forwarded-For: $SPOOFED_IP" --get \
    --data-urlencode "s=$1" --data-urlencode "h=127.0.0.1" --data-urlencode "p=$VPN_PORT" \
    --data-urlencode "k=vless" --data-urlencode "n=$2" --data-urlencode "o=${3:-}" --data-urlencode "v=$APK_VERSION" \
    ${4:+--data-urlencode "w=$4"}
}
support_links() { # -> {header, page}: the support link in subscriptions and on the page
  local u
  u="$(api /api/subscription-page-configs | jq -r 'first((.response.configs[] | select(.uuid == "00000000-0000-0000-0000-000000000000")), .response.configs[0]) | .uuid')"
  # Through files: the page settings are too long for a jq argument.
  api /api/subscription-settings > "$WORK/settings.json"
  api "/api/subscription-page-configs/$u" > "$WORK/page-config.json"
  jq -nc --slurpfile s "$WORK/settings.json" --slurpfile p "$WORK/page-config.json" \
    '{header: $s[0].response.customResponseHeaders["support-url"], page: $p[0].response.config.brandingSettings.supportUrl}'
}
page_config() { # NAME -> the settings install-panel.sh wrote into Kirov VPN's page on https://NAME/
  curl -fsS --noproxy '*' --resolve "$1:443:127.0.0.1" --cacert "$WORK/caddy-root.crt" -H 'Accept: text/html' \
    "https://$1/page-check" | sed -n 's#.*<script id="klaus-config" type="application/json">\(.*\)</script>.*#\1#p'
}
db_volume() { docker volume inspect remnawave-db-data >/dev/null 2>&1; }
install_panel() { # RW_DIR ADMIN_FILE [VAR=value...]
  local dir="$1" admin="$2"
  shift 2
  limits_override "$dir" remnawave remnawave-db remnawave-redis remnawave-subscription-page caddy klaus-monitor
  env RW_DIR="$dir" ADMIN_FILE="$admin" SKIP_SYSTEM=1 CADDY_TLS=internal PANEL_IP=127.0.0.1 "$@" \
    bash "$RWS/install-panel.sh"
}
retry() { # N CMD...: CMD until it succeeds, 2 s apart
  local n="$1" i
  shift
  for i in $(seq 1 "$n"); do
    "$@" && return 0
    [ "$i" -lt "$n" ] && sleep 2
  done
  return 1
}

# ---------------------------------------------------------------- start
step "Test page on $WEB and the REALITY target on $TARGET"
(cd "$HERE/e2e" && go build -o "$WORK/e2e" .)
ip addr add "$WEB_IP/32" dev lo 2>/dev/null || true
"$WORK/e2e" serve -target "$TARGET" -http "$WEB" -token "$TOKEN" > "$WORK/serve.log" 2>&1 &
SERVE_PID=$!
retry 10 curl -fsS --noproxy '*' -o /dev/null "http://$WEB/" 2>/dev/null || fail "test page did not start"
pass "test page answers"

step "Mock Telegram and GitHub APIs on :$MOCK_PORT (a fake release with KirovVPN-$APK_VERSION.apk)"
APK="$WORK/KirovVPN-$APK_VERSION.apk"
head -c 3000000 /dev/urandom > "$APK"
EXE="$WORK/KirovVPN-Setup-$EXE_VERSION.exe"
head -c 2000000 /dev/urandom > "$EXE"
# The friends' page as the repository's branches have it: main with a mark
# of its own, one without its settings block and one cut off.
PAGE_MARK="klaus-e2e-page-from-main"
sed "s#<title>Kirov VPN</title>#<title>Kirov VPN</title><!-- $PAGE_MARK -->#" "$RWS/klaus-page.html" > "$WORK/page-main.html"
grep -q "$PAGE_MARK" "$WORK/page-main.html" || fail "test setup: the page in main"
grep -v 'id="klaus-config"' "$RWS/klaus-page.html" > "$WORK/page-broken.html"
head -c 6000 "$RWS/klaus-page.html" > "$WORK/page-cut.html"
python3 "$HERE/mock-apis.py" --port "$MOCK_PORT" --tg-token "$TG_BOT_TOKEN" --gh-token "$GH_TEST_TOKEN" \
  --tag "$RELEASE" --bad-tag klaus-bad-sum --temp-tag klaus-temp-key --apk "$APK" \
  --win-tag windows-stable --win-bad-tag klaus-win-bad --exe "$EXE" \
  --page main="$WORK/page-main.html" --page broken="$WORK/page-broken.html" --page cut="$WORK/page-cut.html" \
  > "$WORK/mock.log" 2>&1 &
MOCK_PID=$!
retry 10 mock /_mock/tg/sent -o /dev/null || fail "mock APIs did not start"
pass "mock APIs answer"

step "install-panel.sh (fresh)"
CONF="$WORK/opt/klaus-panel.env"
install_panel "$WORK/opt" "$WORK/admin.txt" PANEL_DOMAIN="$PANEL_DOMAIN" SUB_DOMAIN="$SUB_DOMAIN" \
  REALITY_SNI=www.example.com REALITY_TARGET="$TARGET" REALITY_PORT="$VPN_PORT" \
  APK_URL=https://example.com/KirovVPN.apk 2>&1 | tee "$WORK/install-1.log"
grep -q "Готово! Панель работает" "$WORK/install-1.log" || fail "install-panel.sh failed"
[ "$(stat -c %a "$WORK/admin.txt")" = "600" ] || fail "admin credentials are not 600"
grep -Eq '^Пароль: [A-Za-z0-9]{24,}$' "$WORK/admin.txt" || fail "admin password"
grep -qx "RELEASE_TAG=$RELEASE" "$CONF" && grep -qx "STABLE_DEFAULT=1" "$CONF" || fail "new panel does not take the stable release"
grep -qx "PAGE_BRANCH=main" "$CONF" || fail "new panel does not take the friends' page from main"
pass "panel installed, admin saved to admin.txt (600), app builds from the $RELEASE release, the friends' page from main"

step "klaus-panel telegram-setup: waits for the one-time code, takes its chat, sends a test message"
# The panel's containers reach the mock on the host through the bridge.
GW="$(docker network inspect remnawave-network -f '{{(index .IPAM.Config 0).Gateway}}')"
TG_API="http://$GW:$MOCK_PORT"
if TELEGRAM_API_BASE="$TG_API" kp telegram-setup 123456789:AAwrong-token-000000000000000000000 > "$WORK/tg-wrong.log" 2>&1; then
  fail "a wrong bot token was accepted"
fi
tail -n 1 "$WORK/tg-wrong.log"
tg_say() { # CHAT TEXT: a message to the bot
  mock /_mock/tg/say -X POST -H 'Content-Type: application/json' \
    -d "$(jq -nc --argjson c "$1" --arg t "$2" '{chat_id: $c, first_name: "Someone", text: $t}')" >/dev/null
}
STRANGER=555000111
# Somebody found the bot before: that message waits in its queue.
tg_say "$STRANGER" "/start"
TELEGRAM_API_BASE="$TG_API" kp telegram-setup "$TG_BOT_TOKEN" > "$WORK/tg-setup.log" 2>&1 &
tg_pid=$!
sleep 4
kill -0 "$tg_pid" 2>/dev/null || { cat "$WORK/tg-setup.log"; fail "telegram-setup did not wait for a message"; }
grep -q "@klaus_e2e_bot" "$WORK/tg-setup.log" || fail "the bot's name is not shown"
TG_CODE="$(grep -o 'klaus_e2e_bot?start=[0-9a-f]*' "$WORK/tg-setup.log" | head -n 1 | cut -d= -f2)"
[ "${#TG_CODE}" = "12" ] || { cat "$WORK/tg-setup.log"; fail "no one-time code shown"; }
grep -q "klaus_e2e_bot?startgroup=$TG_CODE" "$WORK/tg-setup.log" || fail "no link for a group"
# While it waits: a message without the code, then the owner's /start with it.
tg_say "$STRANGER" "привет"
sleep 4
kill -0 "$tg_pid" 2>/dev/null || { cat "$WORK/tg-setup.log"; fail "telegram-setup took a message without the code"; }
tg_say "$TG_CHAT" "/start $TG_CODE"
wait "$tg_pid" || { cat "$WORK/tg-setup.log"; fail "telegram-setup failed"; }
cat "$WORK/tg-setup.log"
grep -q "без кода" "$WORK/tg-setup.log" || fail "the message without the code was not reported"
grep -q "($TG_CHAT)" "$WORK/tg-setup.log" || fail "chat not taken from getUpdates"
if grep -q "$STRANGER" "$WORK/tg-setup.log" "$WORK/opt/klaus-panel.env" "$WORK/opt/.env"; then fail "a stranger's chat was taken"; fi
mock /_mock/tg/sent | jq -c '.[] | {chat_id, text: .text[0:60]}'
[ "$(mock /_mock/tg/sent | jq --arg c "$TG_CHAT" '[.[] | select(.chat_id == $c and (.text | startswith("Kirov VPN: оповещения включены")))] | length')" = "1" ] ||
  fail "no test message to chat $TG_CHAT"
grep -E '^(IS_TELEGRAM_NOTIFICATIONS_ENABLED|TELEGRAM_BOT_API_ROOT|TELEGRAM_NOTIFY_NODES)=' "$WORK/opt/.env"
grep -qx "IS_TELEGRAM_NOTIFICATIONS_ENABLED=true" "$WORK/opt/.env" || fail "panel notifications not enabled"
grep -qx "TELEGRAM_NOTIFY_NODES=$TG_CHAT" "$WORK/opt/.env" || fail "panel does not notify the chat"
grep -qx "TELEGRAM_BOT_API_ROOT=$TG_API" "$WORK/opt/.env" || fail "panel does not use the mock"
grep -qx "TELEGRAM_CHAT_ID=$TG_CHAT" "$WORK/opt/klaus-monitor.env" || fail "monitor settings"
[ "$(docker exec klaus-monitor printenv TELEGRAM_CHAT_ID)" = "$TG_CHAT" ] || fail "monitor not recreated with the chat"
[ "$(docker exec remnawave printenv TELEGRAM_NOTIFY_NODES)" = "$TG_CHAT" ] || fail "panel not recreated with the chat"
kp telegram-test
[ "$(sent_count "Kirov VPN: проверка оповещений")" = "1" ] || fail "telegram-test"
pass "old and code-less messages ignored, chat $TG_CHAT taken from /start CODE, test messages sent, panel and monitor recreated with it"

step "install-panel.sh again (must change nothing)"
caddy_started="$(docker inspect -f '{{.State.StartedAt}}' caddy)"
panel_started="$(docker inspect -f '{{.State.StartedAt}}' remnawave)"
monitor_started="$(docker inspect -f '{{.State.StartedAt}}' klaus-monitor)"
baks_before="$(find "$WORK/opt" -maxdepth 1 -name '*.bak-*' | sort)"
cp "$WORK/opt/klaus-monitor.env" "$WORK/monitor.env.before"
install_panel "$WORK/opt" "$WORK/admin.txt" 2>&1 | tee "$WORK/install-2.log"
grep -q "Готово! Панель работает" "$WORK/install-2.log" || fail "re-run failed"
if grep -E "Создаю|Обновляю|Перезапускаю" "$WORK/install-2.log"; then fail "re-run created or changed something"; fi
[ "$(docker inspect -f '{{.State.StartedAt}}' caddy)" = "$caddy_started" ] || fail "re-run restarted Caddy for nothing"
[ "$(docker inspect -f '{{.State.StartedAt}}' remnawave)" = "$panel_started" ] || fail "re-run restarted the panel (Telegram lines of .env not kept?)"
[ "$(docker inspect -f '{{.State.StartedAt}}' klaus-monitor)" = "$monitor_started" ] || fail "re-run restarted the monitor"
[ "$(find "$WORK/opt" -maxdepth 1 -name '*.bak-*' | sort)" = "$baks_before" ] || fail "re-run rewrote settings files"
cmp -s "$WORK/opt/klaus-monitor.env" "$WORK/monitor.env.before" || fail "re-run changed the monitor settings"
counts="$(jq -n --argjson p "$(api /api/config-profiles)" --argjson s "$(api /api/internal-squads)" \
  --argjson r "$(api /api/subscription-settings)" '{
    profiles: [$p.response.configProfiles[] | select(.name == "KlausVPN")] | length,
    squads: [$s.response.internalSquads[] | select(.name == "KlausVPN")] | length,
    rules: [$r.response.responseRules.rules[].name]}')"
echo "$counts"
jq -e '.profiles == 1 and .squads == 1 and ([.rules[] | select(. == "Klaus VPN")] | length) == 1
  and .rules[0] == "Browser Subscription" and .rules[1] == "Klaus VPN" and .rules[-1] == "Fallback Base64"' <<<"$counts" >/dev/null ||
  fail "duplicates or wrong rule order"
pass "re-run is idempotent (also after telegram-setup); rule order: browser, Klaus VPN, …, fallback"
docker exec caddy cat /data/caddy/pki/authorities/local/root.crt > "$WORK/caddy-root.crt"

step "Caddy: the monitor on /klaus/, nothing published on /app/ yet"
code="$(sub_code klaus/health)"
echo "https://$SUB_DOMAIN/klaus/health -> $code $(cat "$WORK/last.body")"
[ "$code" = "200" ] || fail "monitor health"
code="$(sub_code app/version.json)"
echo "https://$SUB_DOMAIN/app/version.json -> $code"
[ "$code" = "404" ] || fail "version.json before publishing"
pass "/klaus/health answers, /app/ is empty"

step "Caddy: the panel on its domain, nothing for other names"
code="$(curl -sS --noproxy '*' --resolve "$PANEL_DOMAIN:443:127.0.0.1" --cacert "$WORK/caddy-root.crt" \
  -o "$WORK/panel.html" -w '%{http_code}' "https://$PANEL_DOMAIN/")"
echo "https://$PANEL_DOMAIN/ -> $code $(grep -o '<title>[^<]*</title>' "$WORK/panel.html" || true)"
[ "$code" = "200" ] || fail "panel page"
if curl -sS -k --noproxy '*' --resolve "scanner.example:443:127.0.0.1" -o /dev/null https://scanner.example/ 2>"$WORK/scanner.err"; then
  fail "other names must not get an answer"
fi
echo "https://scanner.example/ -> $(cat "$WORK/scanner.err")"
pass "Caddy serves the panel on its domain and refuses other names"

step "support link without SUPPORT_URL: no header, the page's button leads to a note"
support_links | tee "$WORK/support-0.json"
jq -e --arg p "https://$SUB_DOMAIN/" '.header == null and .page == $p' "$WORK/support-0.json" >/dev/null ||
  fail "placeholder support link left"
https_get "$SUB_DOMAIN" -D "$WORK/root.headers" | tee "$WORK/root.txt"; echo
grep -qi '^content-type: text/plain; charset=utf-8' "$WORK/root.headers" || fail "note is not utf-8 text"
grep -q "кто дал вам ссылку" "$WORK/root.txt" || fail "no note at https://$SUB_DOMAIN/"
jq -e '.supportUrl == ""' <<<"$(page_config "$SUB_DOMAIN")" >/dev/null || fail "a support link on Kirov VPN's page without SUPPORT_URL"
pass "no support-url header, the page's support button opens the note on https://$SUB_DOMAIN/; none on Kirov VPN's page"

step "install-panel.sh with a new SUB_DOMAIN and SUPPORT_URL (and a branch's builds chosen): Caddy serves the new name"
install_panel "$WORK/opt" "$WORK/admin.txt" SUB_DOMAIN="$SUB_DOMAIN2" SUPPORT_URL="$SUPPORT" RELEASE_TAG="$RELEASE_OLD" \
  PAGE_BRANCH= 2>&1 | tee "$WORK/install-3.log"
grep -q "Готово! Панель работает" "$WORK/install-3.log" || fail "re-run with a new SUB_DOMAIN failed"
grep -q "адрес подписок меняется: $SUB_DOMAIN → $SUB_DOMAIN2" "$WORK/install-3.log" || fail "no warning about the old links"
https_get "$SUB_DOMAIN2" -o /dev/null || fail "Caddy does not serve the new $SUB_DOMAIN2"
if https_get "$SUB_DOMAIN" -o /dev/null 2>/dev/null; then fail "Caddy still serves the old $SUB_DOMAIN"; fi
grep -qx "SUB_PUBLIC_DOMAIN=$SUB_DOMAIN2" "$WORK/opt/.env" || fail "panel .env not updated"
support_links | tee "$WORK/support-1.json"
jq -e --arg s "$SUPPORT" '.header == $s and .page == $s' "$WORK/support-1.json" >/dev/null || fail "SUPPORT_URL not applied"
jq -e --arg s "$SUPPORT" '.supportUrl == $s' <<<"$(page_config "$SUB_DOMAIN2")" >/dev/null || fail "SUPPORT_URL not on Kirov VPN's page"
grep -qx "PAGE_BRANCH=''" "$CONF" || fail "PAGE_BRANCH= did not switch the page updates off"
pass "https://$SUB_DOMAIN2 served with a certificate, the old name is not; SUPPORT_URL in the header and on the page; page updates off"

step "install-panel.sh back to $SUB_DOMAIN with SUPPORT_URL removed (settings converge)"
install_panel "$WORK/opt" "$WORK/admin.txt" SUB_DOMAIN="$SUB_DOMAIN" SUPPORT_URL= 2>&1 | tee "$WORK/install-4.log"
grep -q "Готово! Панель работает" "$WORK/install-4.log" || fail "re-run back to $SUB_DOMAIN failed"
https_get "$SUB_DOMAIN" -o /dev/null || fail "Caddy does not serve $SUB_DOMAIN again"
if https_get "$SUB_DOMAIN2" -o /dev/null 2>/dev/null; then fail "Caddy still serves $SUB_DOMAIN2"; fi
support_links | tee "$WORK/support-2.json"
cmp -s "$WORK/support-0.json" "$WORK/support-2.json" || fail "support link did not return to the state without SUPPORT_URL"
jq -e '.supportUrl == ""' <<<"$(page_config "$SUB_DOMAIN")" >/dev/null || fail "SUPPORT_URL left on Kirov VPN's page"
grep -qx "RELEASE_TAG=$RELEASE_OLD" "$CONF" || fail "a release chosen on purpose was not kept"
if grep -q "стабильные" "$WORK/install-4.log"; then fail "a release chosen on purpose was moved to stable"; fi
grep -qx "PAGE_BRANCH=''" "$CONF" || fail "page updates switched off on purpose came back"
pass "back on $SUB_DOMAIN; support link as without SUPPORT_URL again; the chosen $RELEASE_OLD and the page updates switched off stay"

step "klaus-panel add-node + install-node.sh"
kp add-node test-node "$GW" DE --title "Германия" --host 127.0.0.1 --node-port "$NODE_PORT" > "$WORK/add-node.log"
sed -E "s/SECRET_KEY='[^']+'/SECRET_KEY='…'/" "$WORK/add-node.log"
SECRET_KEY="$(grep -o "SECRET_KEY='[^']*'" "$WORK/add-node.log" | sed "s/^SECRET_KEY='//; s/'$//")"
[ -n "$SECRET_KEY" ] || fail "no SECRET_KEY in the add-node output"
limits_override "$WORK/node" remnanode
env NODE_DIR="$WORK/node" SKIP_SYSTEM=1 PANEL_IP=127.0.0.1 NODE_PORT="$NODE_PORT" VPN_PORT="$VPN_PORT" \
  SECRET_KEY="$SECRET_KEY" bash "$RWS/install-node.sh" 2>&1 | tee "$WORK/install-node.log"
node_up() { grep -q "на связи" <<<"$(kp list-nodes)"; }
retry 45 node_up || { kp list-nodes; fail "node did not connect"; }
kp list-nodes
pass "node connected"

step "the node's Xray: idle connections kept 30 minutes, no access log, masked addresses (from the profile)"
PROFILE_UUID="$(api /api/config-profiles | jq -r 'first(.response.configProfiles[] | select(.name == "KlausVPN")) | .uuid')"
api "/api/config-profiles/$PROFILE_UUID" | jq -c '.response.config | {policy, log}' | tee "$WORK/profile-policy.json"
jq -e '.policy.levels["0"].connIdle == 1800' "$WORK/profile-policy.json" >/dev/null || fail "no idle policy in the profile"
jq -e '.log.access == "none" and .log.maskAddress == "full"' "$WORK/profile-policy.json" >/dev/null ||
  fail "the profile lets the servers log who connected"
# What the node's Xray really runs, read the way Xray itself reads it. Only
# the policy and the log settings are printed: the config holds the keys.
node_policy() {
  docker exec remnanode node -e '
    const fs = require("fs"), http = require("http");
    const env = (n) => fs.readFileSync("/run/s6/container_environment/" + n, "utf8").trim();
    http.get({socketPath: "\0" + env("INTERNAL_SOCKET_PATH"),
              path: "/internal/get-config?token=" + env("INTERNAL_REST_TOKEN")}, (r) => {
      let b = "";
      r.on("data", (d) => (b += d));
      r.on("end", () => {
        const c = JSON.parse(b);
        console.log(JSON.stringify({policy: c.policy || null, log: c.log || null}));
      });
    }).on("error", (e) => { console.error(e.message); process.exit(1); });' > "$WORK/node-policy.json" 2>&1 &&
    jq -e '.policy.levels["0"].connIdle == 1800 and .log.access == "none" and .log.maskAddress == "full"' \
      "$WORK/node-policy.json" >/dev/null
}
retry 10 node_policy || { cat "$WORK/node-policy.json"; fail "the node's Xray closes idle connections sooner or logs who connected"; }
cat "$WORK/node-policy.json"
pass "connIdle 1800 (next to the node's own statistics settings), no access log and masked addresses in the profile and in the node's running Xray"

step "the panel's own Telegram messages about servers reach the chat"
panel_msgs() { mock /_mock/tg/sent | jq --arg c "$TG_CHAT" '[.[] | select(.chat_id == $c and (.text | test("#node")))]'; }
has_node_msg() { [ "$(panel_msgs | jq 'length')" -gt 0 ]; }
retry 20 has_node_msg || fail "no node message from the panel"
panel_msgs | jq -r '.[].text' | grep -o '#node[A-Za-z]*' | sort | uniq -c
pass "Remnawave sends its node notifications to chat $TG_CHAT"

step "install-node.sh again with only PANEL_IP (after a panel move): NODE_PORT $NODE_PORT stays"
env NODE_DIR="$WORK/node" SKIP_SYSTEM=1 PANEL_IP=127.0.0.1 bash "$RWS/install-node.sh" 2>&1 | tee "$WORK/install-node-2.log"
grep -q "Готово! Сервер запущен" "$WORK/install-node-2.log" || fail "node re-run failed"
grep -E "^(NODE|VPN)_PORT=" "$WORK/node/.env"
grep -qx "NODE_PORT=$NODE_PORT" "$WORK/node/.env" || fail "NODE_PORT reset"
grep -qx "VPN_PORT=$VPN_PORT" "$WORK/node/.env" || fail "VPN_PORT reset"
grep -q "порты $VPN_PORT и $NODE_PORT" "$WORK/install-node-2.log" || fail "re-run talks about other ports"
if compgen -G "$WORK/node/.env.bak-*" >/dev/null; then fail "node .env rewritten"; fi
retry 45 node_up || { kp list-nodes; fail "node lost after the re-run"; }
pass "node .env unchanged (NODE_PORT=$NODE_PORT, VPN_PORT=$VPN_PORT), still connected"

step "klaus-panel add-user"
kp add-user friend_1 --devices 2 | tee "$WORK/add-user.log"
SUB="$(grep -o "https://$SUB_DOMAIN/[A-Za-z0-9_-]*" "$WORK/add-user.log" | head -n 1)"
[ -n "$SUB" ] || fail "no subscription link"
grep -q "klausvpn://add/$SUB" "$WORK/add-user.log" || fail "no deep link"
pass "link $SUB"

step "(a) the app's User-Agent gets a base64 list with a vless REALITY link"
sub_get "$UA_APP" "$SUB" -H "X-Hwid: $HWID" -H 'X-Device-Os: Android' -H 'X-Ver-Os: 15' \
  -H 'X-Device-Model: Google Pixel 8' > "$WORK/a.body"
grep -iE '^(HTTP|content-type|profile-title|profile-update-interval|subscription-userinfo)' "$WORK/last.headers"
base64 -d "$WORK/a.body" > "$WORK/a.links" || fail "body is not base64"
cat "$WORK/a.links"; echo
grep -q "^HTTP/[0-9.]* 200" "$WORK/last.headers" || fail "status"
grep -qi '^profile-title: Kirov VPN' "$WORK/last.headers" || fail "profile-title"
grep -iE '^klaus-(report|app)-url' "$WORK/last.headers"
grep -qi "^klaus-report-url: https://$SUB_DOMAIN/klaus/report" "$WORK/last.headers" || fail "klaus-report-url"
grep -qi "^klaus-app-url: https://$SUB_DOMAIN/app/version.json" "$WORK/last.headers" || fail "klaus-app-url"
if grep -i '^support-url' "$WORK/last.headers"; then fail "support-url header without SUPPORT_URL"; fi
if grep -qi 'dummy\.docs\.rw' "$WORK/last.headers"; then fail "placeholder in the headers"; fi
grep -Eq "^vless://[0-9a-f-]+@127\.0\.0\.1:$VPN_PORT\?.*security=reality.*pbk=.*#%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F$" "$WORK/a.links" ||
  fail "no vless reality link named Германия"
pass "base64 list with a vless REALITY link; report and app URLs in the headers"

step "(b) a browser gets Kirov VPN's own page"
sub_get "$UA_BROWSER" "$SUB" -H 'Accept: text/html' > "$WORK/b.html"
grep -q "^HTTP/[0-9.]* 200" "$WORK/last.headers" || fail "no HTML page"
grep -qi '^content-type: text/html' "$WORK/last.headers" || fail "no HTML page"
grep -o '<title>[^<]*</title>' "$WORK/b.html"
# The page makes its button from its own address: klausvpn://add/ + the link.
grep -q 'klausvpn://add/' "$WORK/b.html" || fail "no Kirov VPN button on the page"
page_config "$SUB_DOMAIN" | tee "$WORK/b.config.json"
jq -e '.apkUrl == "https://example.com/KirovVPN.apk" and .supportUrl == ""' "$WORK/b.config.json" >/dev/null || fail "page settings"
if grep -Eq '(src|href)="https?://' "$WORK/b.html"; then fail "the page loads something from another site"; fi
echo "the page turns the button into: klausvpn://add/$SUB"
pass "Kirov VPN's page with the klausvpn://add/ button, APK_URL and no SUPPORT_URL in its settings, nothing from other sites"

step "publish-apk: from the GitHub release to https://$SUB_DOMAIN/app/ (checksum verified)"
page_apk_buttons() { # -> the Kirov VPN block's download buttons in the panel's page settings
  local u
  u="$(api /api/subscription-page-configs | jq -r 'first((.response.configs[] | select(.uuid == "00000000-0000-0000-0000-000000000000")), .response.configs[0]) | .uuid')"
  api "/api/subscription-page-configs/$u" > "$WORK/page-config.json"
  jq -c '[.response.config.platforms.android.apps[0].blocks[].buttons[] | select(.type == "external") | {link, text: .text.ru}]' "$WORK/page-config.json"
}
# The owner adds the token later (and drops APK_URL: the app comes from here
# now) with the new install-panel.sh, on a panel whose settings were saved by
# an older one: its release is the old default, the work branch's test builds.
sed -i '/^STABLE_DEFAULT=/d' "$CONF"
grep -qx "RELEASE_TAG=$RELEASE_OLD" "$CONF" || fail "test setup: the old default is not saved"
install_panel "$WORK/opt" "$WORK/admin.txt" GITHUB_TOKEN="$GH_TEST_TOKEN" GITHUB_API="http://127.0.0.1:$MOCK_PORT" APK_URL= PAGE_BRANCH=main \
  2>&1 | tee "$WORK/install-5.log"
grep -q "Готово! Панель работает" "$WORK/install-5.log" || fail "re-run with GITHUB_TOKEN failed"
grep -q "только стабильные" "$WORK/install-5.log" || fail "no note about the move to stable"
grep -qx "RELEASE_TAG=$RELEASE" "$CONF" && grep -qx "STABLE_DEFAULT=1" "$CONF" || fail "the old default was not moved to $RELEASE"
# Before CI has made a release (or while it replaces it) the timer stays
# quiet; a person is told; a token that cannot see the repository is an error.
kp publish-apk --tag no-such-release --quiet || fail "the timer's run failed on a release that is not there yet"
if kp publish-apk --tag no-such-release > "$WORK/publish-none.log" 2>&1; then fail "a missing release went through"; fi
cat "$WORK/publish-none.log"
grep -q "нет релиза no-such-release" "$WORK/publish-none.log" || fail "wrong message for a missing release"
sed 's#^GITHUB_REPO=.*#GITHUB_REPO=someone/other#' "$CONF" > "$WORK/other-repo.env"
if KLAUS_PANEL_CONF="$WORK/other-repo.env" no_proxy='*' NO_PROXY='*' bash "$RWS/klaus-panel" publish-apk --quiet \
  > "$WORK/publish-norepo.log" 2>&1; then fail "a repository the token cannot see was taken for a missing release"; fi
cat "$WORK/publish-norepo.log"
grep -q "нет доступа" "$WORK/publish-norepo.log" || fail "wrong message for a repository without access"
rm -f "$WORK/other-repo.env"
# Without a token an open repository is read as it is (its temporary-key
# build refused as any); a closed one asks for the token, quietly for the
# timer.
sed -e '/^GITHUB_TOKEN=/d' -e 's#^GITHUB_REPO=.*#GITHUB_REPO=klausms17/open#' "$CONF" > "$WORK/open-repo.env"
if KLAUS_PANEL_CONF="$WORK/open-repo.env" no_proxy='*' NO_PROXY='*' bash "$RWS/klaus-panel" publish-apk --tag klaus-temp-key \
  > "$WORK/publish-open.log" 2>&1; then fail "a temporary-key build of an open repository went through"; fi
cat "$WORK/publish-open.log"
grep -q "временным ключом" "$WORK/publish-open.log" || fail "an open repository was not read without a token"
sed -e '/^GITHUB_TOKEN=/d' "$CONF" > "$WORK/closed-repo.env"
KLAUS_PANEL_CONF="$WORK/closed-repo.env" no_proxy='*' NO_PROXY='*' bash "$RWS/klaus-panel" publish-apk --quiet ||
  fail "the timer's run without a token failed on a closed repository"
if KLAUS_PANEL_CONF="$WORK/closed-repo.env" no_proxy='*' NO_PROXY='*' bash "$RWS/klaus-panel" publish-apk \
  > "$WORK/publish-closed.log" 2>&1; then fail "a closed repository was read without a token"; fi
cat "$WORK/publish-closed.log"
grep -q "закрытый" "$WORK/publish-closed.log" || fail "wrong message for a closed repository without a token"
rm -f "$WORK/open-repo.env" "$WORK/closed-repo.env"
if compgen -G "$WORK/opt/app/*" >/dev/null; then fail "something was published from a missing release"; fi
echo "download buttons without APK_URL, nothing published: $(page_apk_buttons)"
[ "$(page_apk_buttons)" = "[]" ] || fail "download button without an APK"
# Samsung Auto Blocker and Huawei/Honor Pure mode also refuse a file sent
# by the owner, so the tip is there without the download button too.
install_tip() { jq -e '.response.config.platforms.android.apps[0].blocks[0].description
  | (.ru | contains("«Автоблокировщик» (Samsung)") and contains("«Чистый режим»")) and (.en | contains("Auto Blocker"))' \
  "$WORK/page-config.json" >/dev/null; }
install_tip || fail "no install tip for Samsung and Huawei without the download button"
if kp publish-apk --tag klaus-bad-sum > "$WORK/publish-bad.log" 2>&1; then fail "an APK with a wrong checksum was published"; fi
cat "$WORK/publish-bad.log"
grep -q "контрольная сумма" "$WORK/publish-bad.log" || fail "wrong refusal of a bad checksum"
if compgen -G "$WORK/opt/app/*" >/dev/null || [ -e "$WORK/opt/.app-staging" ]; then fail "the refused APK was left behind"; fi
if kp publish-apk --tag klaus-temp-key > "$WORK/publish-temp.log" 2>&1; then fail "a build signed with the temporary key was published"; fi
cat "$WORK/publish-temp.log"
grep -q "временным ключом" "$WORK/publish-temp.log" || fail "wrong refusal of a temporary-key build"
if compgen -G "$WORK/opt/app/*" >/dev/null; then fail "the temporary-key build was published"; fi
kp publish-apk --tag klaus-temp-key --quiet || fail "the timer's run of a temporary-key build failed"
if compgen -G "$WORK/opt/app/*" >/dev/null; then fail "the timer published a temporary-key build"; fi
# Builds replaced earlier: one 14 hours ago goes, one 2 hours ago stays
# (an app may still offer it).
mkdir -p "$WORK/opt/app"
echo old > "$WORK/opt/app/KirovVPN-1.0.10.apk"
echo recent > "$WORK/opt/app/KirovVPN-1.0.11.apk"
touch -d '14 hours ago' "$WORK/opt/app/KirovVPN-1.0.10.apk"
touch -d '2 hours ago' "$WORK/opt/app/KirovVPN-1.0.11.apk"
# A panel set up before the app was renamed: the page entry and remark, and
# the build published, under the former name. Its setup takes them over.
page_apk_buttons >/dev/null
api /api/subscription-page-configs -X PATCH -H 'Content-Type: application/json' --data-binary @<(jq -c \
  '.response | {uuid, config: (.config | .platforms.android.apps[0].name = "Klaus VPN")}' "$WORK/page-config.json") |
  jq -e '.response != null' >/dev/null || fail "test setup: the former page entry"
api /api/subscription-settings -X PATCH -H 'Content-Type: application/json' --data-binary @<(api /api/subscription-settings | jq -c \
  '.response | {uuid, customRemarks: (.customRemarks | .HWIDNotSupported = ["Обновите приложение Klaus VPN"])}') |
  jq -e '.response != null' >/dev/null || fail "test setup: the former remark"
echo former > "$WORK/opt/app/KlausVPN-1.0.9.apk"
ln "$WORK/opt/app/KlausVPN-1.0.9.apk" "$WORK/opt/app/KlausVPN.apk"
touch -d '14 hours ago' "$WORK/opt/app/KlausVPN-1.0.9.apk"
jq -n --arg a "https://$SUB_DOMAIN/app/KlausVPN-1.0.9.apk" '{versionCode: 9, versionName: "1.0.9", apk: $a, sha256: "0"}' \
  > "$WORK/opt/app/version.json"
kp setup > "$WORK/setup-former.log"
[ "$WORK/opt/app/KirovVPN.apk" -ef "$WORK/opt/app/KlausVPN.apk" ] || fail "the former build did not get the new name"
echo "download buttons with the former build: $(page_apk_buttons)"
[ "$(page_apk_buttons)" = "[{\"link\":\"https://$SUB_DOMAIN/app/KirovVPN.apk\",\"text\":\"Скачать приложение\"}]" ] ||
  fail "no download button for the former build"
jq -e '[.response.config.platforms.android.apps[].name] | .[0] == "Kirov VPN" and index("Klaus VPN") == null' \
  "$WORK/page-config.json" >/dev/null || fail "the former page entry was kept"
api /api/subscription-settings | jq -e '.response.customRemarks.HWIDNotSupported == ["Обновите приложение Kirov VPN"]' >/dev/null ||
  fail "the former remark was kept"
kp publish-apk | tee "$WORK/publish-1.log"
grep -q "Опубликована версия $APK_VERSION" "$WORK/publish-1.log" || fail "publish-apk"
ls "$WORK/opt/app"
[ ! -e "$WORK/opt/app/KirovVPN-1.0.10.apk" ] || fail "a build replaced 14 hours ago was kept"
[ -e "$WORK/opt/app/KirovVPN-1.0.11.apk" ] || fail "a build replaced 2 hours ago was deleted"
rm -f "$WORK/opt/app/KirovVPN-1.0.11.apk"
if compgen -G "$WORK/opt/app/KlausVPN*" >/dev/null; then fail "files of the former name were kept"; fi
# An app that still offers a deleted build, or a link of the former name,
# gets the current one.
for f in KirovVPN-1.0.10.apk KlausVPN.apk KlausVPN-1.0.9.apk; do
  code="$(sub_code "app/$f" -D "$WORK/gone.headers")"
  echo "https://$SUB_DOMAIN/app/$f (deleted) -> $code $(grep -i '^location:' "$WORK/gone.headers" | tr -d '\r')"
  [ "$code" = "302" ] && grep -qi '^location: /app/KirovVPN.apk' "$WORK/gone.headers" || fail "no redirect from the deleted $f"
done
code="$(sub_code app/version.json)"
cat "$WORK/last.body"; echo
[ "$code" = "200" ] || fail "version.json not served"
jq -e --arg sub "$SUB_DOMAIN" --arg v "$APK_VERSION" --arg sha "$(sha256sum "$APK" | cut -d' ' -f1)" \
  '.versionCode == 99 and .versionName == $v and .apk == "https://\($sub)/app/KirovVPN-\($v).apk" and .sha256 == $sha' \
  "$WORK/last.body" >/dev/null || fail "version.json content"
for f in KirovVPN.apk "KirovVPN-$APK_VERSION.apk"; do
  code="$(sub_code "app/$f" -D "$WORK/apk.headers")"
  echo "https://$SUB_DOMAIN/app/$f -> $code, $(grep -i '^content-type' "$WORK/apk.headers" | tr -d '\r'), sha256 $(sha256sum < "$WORK/last.body" | cut -c1-16)…"
  [ "$code" = "200" ] || fail "$f not served"
  cmp -s "$WORK/last.body" "$APK" || fail "$f differs from the release"
  grep -qi '^content-type: application/vnd.android.package-archive' "$WORK/apk.headers" || fail "$f content type"
done
# The subscription page restarts with its new settings.
page_up() { sub_get "$UA_APP" "$SUB" -f -o /dev/null 2>/dev/null; }
retry 30 page_up || fail "subscription page did not come back"
kp publish-apk | tee "$WORK/publish-2.log"
grep -q "уже опубликована" "$WORK/publish-2.log" || fail "the same build downloaded again"
echo "download buttons now: $(page_apk_buttons)"
[ "$(page_apk_buttons)" = "[{\"link\":\"https://$SUB_DOMAIN/app/KirovVPN.apk\",\"text\":\"Скачать приложение\"}]" ] ||
  fail "no download button for the published APK"
install_tip || fail "no install tip for Samsung and Huawei next to the download button"
# What a friend's browser gets: without APK_URL, Kirov VPN's page offers the
# build it finds on /app/.
jq -e '.apkUrl == ""' <<<"$(page_config "$SUB_DOMAIN")" >/dev/null || fail "APK_URL left on Kirov VPN's page"
[ "$(sub_code app/version.json)" = "200" ] || fail "the page finds no published app"
pass "old default moved to the $RELEASE release; a missing release is quiet for the timer, an unseen repository is not; an open repository read without a token, a closed one asks for it; wrong checksum and temporary key refused; the page entry, remark and build of the former name taken over; replaced builds kept 13 h, then a redirect to the current one (also from the former name); KirovVPN.apk, KirovVPN-$APK_VERSION.apk and version.json (versionCode 99) served; page button «Скачать приложение»; the Samsung and Huawei install tip with and without it"

step "publish-windows: the installer to https://$SUB_DOMAIN/app/windows/ (checksum and manifest verified)"
code="$(sub_code app/windows/version.json)"
echo "https://$SUB_DOMAIN/app/windows/version.json before publishing -> $code"
[ "$code" = "404" ] || fail "a Windows version.json before publishing"
kp publish-windows --tag no-such-release --quiet || fail "the timer's run failed on a Windows release that is not there yet"
if kp publish-windows --tag klaus-win-bad > "$WORK/publish-win-bad.log" 2>&1; then fail "an installer with a wrong manifest was published"; fi
tail -n 1 "$WORK/publish-win-bad.log"
grep -q "не совпадает" "$WORK/publish-win-bad.log" || fail "wrong refusal of a manifest that does not match"
if compgen -G "$WORK/opt/app/windows/*" >/dev/null || [ -e "$WORK/opt/.windows-staging" ]; then fail "the refused installer was left behind"; fi
# The build being replaced stays 13 hours; one replaced 14 hours ago goes.
echo old > "$WORK/opt/app/windows/KirovVPN-Setup-1.0.10.exe"
echo recent > "$WORK/opt/app/windows/KirovVPN-Setup-1.0.11.exe"
jq -n '{versionCode: 11, versionName: "1.0.11", file: "KirovVPN-Setup-1.0.11.exe", size: 7, sha256: "0", minBuild: 17763}' \
  > "$WORK/opt/app/windows/version.json"
touch -d '14 hours ago' "$WORK/opt/app/windows/KirovVPN-Setup-1.0.10.exe" "$WORK/opt/app/windows/KirovVPN-Setup-1.0.11.exe"
kp publish-windows | tee "$WORK/publish-win-1.log"
grep -q "Опубликована версия $EXE_VERSION для Windows" "$WORK/publish-win-1.log" || fail "publish-windows"
ls "$WORK/opt/app/windows"
[ ! -e "$WORK/opt/app/windows/KirovVPN-Setup-1.0.10.exe" ] || fail "an installer replaced 14 hours ago was kept"
[ -e "$WORK/opt/app/windows/KirovVPN-Setup-1.0.11.exe" ] || fail "the installer just replaced was deleted"
[ "$WORK/opt/app/windows/KirovVPN-Setup.exe" -ef "$WORK/opt/app/windows/KirovVPN-Setup-$EXE_VERSION.exe" ] ||
  fail "KirovVPN-Setup.exe is not the published build"
code="$(sub_code app/windows/version.json)"
cat "$WORK/last.body"
[ "$code" = "200" ] || fail "the Windows version.json not served"
jq -e --arg v "$EXE_VERSION" --arg sha "$(sha256sum "$EXE" | cut -d' ' -f1)" \
  '.versionCode == 42 and .versionName == $v and .file == "KirovVPN-Setup-\($v).exe" and .sha256 == $sha' \
  "$WORK/last.body" >/dev/null || fail "the Windows version.json content"
for f in KirovVPN-Setup.exe "KirovVPN-Setup-$EXE_VERSION.exe"; do
  code="$(sub_code "app/windows/$f" -D "$WORK/exe.headers")"
  echo "https://$SUB_DOMAIN/app/windows/$f -> $code, $(grep -iE '^content-(type|disposition)' "$WORK/exe.headers" | tr -d '\r' | tr '\n' ' ')"
  [ "$code" = "200" ] || fail "$f not served"
  cmp -s "$WORK/last.body" "$EXE" || fail "$f differs from the release"
  grep -qi '^content-type: application/octet-stream' "$WORK/exe.headers" || fail "$f content type"
  grep -qi '^content-disposition: attachment' "$WORK/exe.headers" || fail "$f is not a download"
done
code="$(sub_code app/windows/KirovVPN-Setup-1.0.10.exe -D "$WORK/gone.headers")"
echo "https://$SUB_DOMAIN/app/windows/KirovVPN-Setup-1.0.10.exe (deleted) -> $code $(grep -i '^location:' "$WORK/gone.headers" | tr -d '\r')"
[ "$code" = "302" ] && grep -qi '^location: /app/windows/KirovVPN-Setup.exe' "$WORK/gone.headers" ||
  fail "no redirect from the deleted installer"
kp publish-windows | tee "$WORK/publish-win-2.log"
grep -q "уже опубликована" "$WORK/publish-win-2.log" || fail "the same installer downloaded again"
# A friend's browser on Windows: the page offers the download once the
# panel has published it.
sub_get "$UA_BROWSER" "$SUB" -H 'Accept: text/html' > "$WORK/b-win.html"
grep -qF 'fetch("/app/windows/version.json"' "$WORK/b-win.html" && grep -qF 'href="/app/windows/KirovVPN-Setup.exe"' "$WORK/b-win.html" ||
  fail "no Windows download on the page"
pass "a missing Windows release is quiet for the timer, a manifest that does not match is refused; KirovVPN-Setup.exe and KirovVPN-Setup-$EXE_VERSION.exe served as downloads, version.json (versionCode 42); the replaced installer kept, one replaced 14 h ago gone with a redirect to the current one; the page offers the download"

step "update-page: the friends' page from the repository's main branch, with this panel's settings"
grep -qx "PAGE_BRANCH=main" "$CONF" || fail "PAGE_BRANCH=main not saved"
if grep -q "$PAGE_MARK" "$WORK/opt/page/index.html"; then fail "test setup: the page of the folder has the mark of main"; fi
kp update-page | tee "$WORK/page-1.log"
grep -q "обновлена" "$WORK/page-1.log" || fail "the page in main was not taken"
sub_get "$UA_BROWSER" "$SUB" -H 'Accept: text/html' > "$WORK/page-served.html"
grep -q "$PAGE_MARK" "$WORK/page-served.html" || fail "a browser does not get the page from main"
jq -e '.apkUrl == "" and .supportUrl == ""' <<<"$(page_config "$SUB_DOMAIN")" >/dev/null || fail "the page from main lost the panel's settings"
[ "$(stat -c %a "$WORK/opt/page/index.html")" = "644" ] || fail "the page is not readable by Caddy"
kp update-page | tee "$WORK/page-2.log"
grep -q "уже последняя" "$WORK/page-2.log" || fail "the same page written again"
kp update-page --quiet > "$WORK/page-quiet.log" || fail "the timer's run failed"
[ ! -s "$WORK/page-quiet.log" ] || fail "the timer's run says something with nothing new"
pass "the page in main served with this panel's settings (644); taken once; the timer quiet with nothing new"

step "update-page: a broken or cut page refused, a missing one quiet for the timer, off when PAGE_BRANCH is empty"
page_kp() { # BRANCH ARGS...: update-page with PAGE_BRANCH=BRANCH ('' for none)
  local b="$1"
  shift
  sed "s#^PAGE_BRANCH=.*#PAGE_BRANCH='$b'#" "$CONF" > "$WORK/page-branch.env"
  KLAUS_PANEL_CONF="$WORK/page-branch.env" no_proxy='*' NO_PROXY='*' bash "$RWS/klaus-panel" update-page "$@"
}
cp "$WORK/opt/page/index.html" "$WORK/page-before.html"
for b in broken cut; do
  if page_kp "$b" --quiet > "$WORK/page-$b.log" 2>&1; then fail "a $b page was taken"; fi
  cat "$WORK/page-$b.log"
  grep -q "не целая" "$WORK/page-$b.log" || fail "wrong refusal of a $b page"
done
page_kp no-such-branch --quiet || fail "the timer's run failed on a branch without the page"
if page_kp no-such-branch > "$WORK/page-none.log" 2>&1; then fail "a branch without the page went through"; fi
cat "$WORK/page-none.log"
grep -q "нет server/remnawave/klaus-page.html" "$WORK/page-none.log" || fail "wrong message for a branch without the page"
page_kp '' --quiet || fail "the timer's run failed with the updates off"
if page_kp '' > "$WORK/page-off.log" 2>&1; then fail "update-page ran with the updates off"; fi
cat "$WORK/page-off.log"
grep -q "не обновляется сама" "$WORK/page-off.log" || fail "wrong message with the updates off"
rm -f "$WORK/page-branch.env"
cmp -s "$WORK/opt/page/index.html" "$WORK/page-before.html" || fail "a refused page changed the one served"
pass "a page without its settings block and a cut one refused, the served one kept; a branch without the page quiet for the timer; nothing with PAGE_BRANCH empty"

step "(c) the device is recorded without the friend's IP (the limit itself is off); no history, no page log"
# The Windows app sends the same headers: a PC is one more device.
sub_get 'KlausVPN/1.0.42 (Windows)' "$SUB" -H "X-Hwid: $HWID_PC" -H 'X-Device-Os: Windows' -H 'X-Ver-Os: 10.0.26100' \
  -H 'X-Device-Model: Microsoft Corporation Virtual Machine' | base64 -d > "$WORK/c.pc.links" || fail "no list for the Windows app"
grep -q "@127.0.0.1:$VPN_PORT" "$WORK/c.pc.links" || fail "no server for the Windows app"
USER_ID="$(api "/api/users/by-username/friend_1" | jq -r '.response.id')"
api "/api/hwid/devices/$USER_ID" | jq -c '.response.devices[] | {hwid, platform, osVersion, deviceModel, userAgent, requestIp}' | tee "$WORK/c.devices"
grep -q "\"hwid\":\"$HWID\"" "$WORK/c.devices" || fail "device not recorded"
jq -se --arg h "$HWID_PC" 'any(.hwid == $h and .platform == "Windows")' "$WORK/c.devices" >/dev/null || fail "the PC not recorded as Windows"
# Caddy gives the page 127.0.0.1 instead of the friend's address.
jq -se 'all(.requestIp == "127.0.0.1")' "$WORK/c.devices" >/dev/null || fail "the panel got the friend's IP"
api /api/subscription-settings | jq -c '.response.hwidSettings'
kp list-users
srh="$(docker exec remnawave-db psql -qAt -U "$(sed -n 's/^POSTGRES_USER=//p' "$WORK/opt/.env")" \
  -d "$(sed -n 's/^POSTGRES_DB=//p' "$WORK/opt/.env")" -c 'select count(*) from user_subscription_request_history')"
[ "$srh" = "0" ] || fail "the panel keeps a history of subscription downloads ($srh)"
[ "$(docker inspect -f '{{.HostConfig.LogConfig.Type}}' remnawave-subscription-page)" = "none" ] ||
  fail "the subscription page keeps a log"
pass "phone and PC recorded with 127.0.0.1 instead of the friend's IP; no history of subscription downloads; no log of the page"

step "(d) libxray parses the subscription and fetches a page through the node"
retry 5 "$WORK/e2e" check -sub "$SUB" -resolve "$SUB_DOMAIN:127.0.0.1" -cacert "$WORK/caddy-root.crt" \
  -hwid "$HWID" -url "http://$WEB/" -expect "$TOKEN" -save "$WORK/friend_1.sub" || fail "end to end"
pass "traffic goes through the node"

step "disable-user: the node refuses the old key, the subscription says why"
kp disable-user friend_1
sub_get "$UA_APP" "$SUB" -H "X-Hwid: $HWID" | base64 -d | tee "$WORK/disabled.links"; echo
grep -q '@0.0.0.0:1?' "$WORK/disabled.links" || fail "no placeholder"
retry 5 "$WORK/e2e" check -body "$WORK/friend_1.sub" -url "http://$WEB/" -expect-fail || fail "disabled user still works"
kp enable-user friend_1
retry 10 "$WORK/e2e" check -body "$WORK/friend_1.sub" -url "http://$WEB/" -expect "$TOKEN" >/dev/null || fail "enable-user"
pass "disabled friend refused by the node, works again after enable-user"

step "link, delete-user"
kp link friend_1 > "$WORK/link.log"
grep -q "klausvpn://add/$SUB" "$WORK/link.log" || fail "link"
if kp link nobody 2>"$WORK/nobody.err"; then fail "link for a missing user"; fi
cat "$WORK/nobody.err"
kp add-user friend_3 >/dev/null
kp delete-user friend_3 --yes
if grep -q '"username":"friend_3"' <<<"$(api /api/users/by-username/friend_3)"; then fail "delete-user"; fi
pass "link shows the same link, unknown names are reported, delete-user works"

step "tidy-users: a friend added in the web form gets the servers and no end date"
web_user() { # NAME EXPIRE_AT SQUADS_JSON -> subscription link; the body the panel's web form sends
  api /api/users -X POST -H 'Content-Type: application/json' --data-binary "$(jq -nc --arg n "$1" --arg e "$2" --argjson s "$3" \
    '{username: $n, status: "ACTIVE", expireAt: $e, trafficLimitBytes: 0, trafficLimitStrategy: "NO_RESET", activeInternalSquads: $s}')" |
    jq -r '.response.subscriptionUrl // empty'
}
web_state() { # NAME -> {squads, end}
  api "/api/users/by-username/$1" | jq -c '.response | {squads: [.activeInternalSquads[].name], end: (.expireAt | sub("\\.[0-9]+Z$"; ""))}'
}
squad_named() { api /api/internal-squads | jq -r --arg n "$1" 'first(.response.internalSquads[] | select(.name == $n) | .uuid)'; }
KLAUS_SQUAD="$(squad_named KlausVPN)"
PANEL_SQUAD="$(squad_named Default-Squad)"
TOMORROW="$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%S.000Z)"
MONTH="$(date -u -d '+30 days' +%Y-%m-%dT%H:%M:%S)"
WEB_SUB="$(web_user web_friend "$TOMORROW" '[]')"
[ -n "$WEB_SUB" ] || fail "the panel did not make a user as its web form does"
web_user web_panel_squad "$MONTH.000Z" "[\"$PANEL_SQUAD\"]" >/dev/null
web_user web_chosen "$MONTH.000Z" "[\"$KLAUS_SQUAD\"]" >/dev/null
web_user web_old "$TOMORROW" '[]' >/dev/null
docker exec remnawave-db psql -q -U "$(sed -n 's/^POSTGRES_USER=//p' "$WORK/opt/.env")" \
  -d "$(sed -n 's/^POSTGRES_DB=//p' "$WORK/opt/.env")" \
  -c "UPDATE users SET created_at = now() - interval '3 days' WHERE username = 'web_old'" || fail "web_old not moved back"
if grep -q "@127.0.0.1:$VPN_PORT" <<<"$(sub_get "$UA_APP" "$WEB_SUB" | base64 -d)"; then
  fail "a user from the web form has servers without tidy-users"
fi
kp tidy-users | tee "$WORK/tidy.log"
grep -qx "web_friend: добавлены серверы (группа KlausVPN), подписка теперь бессрочная" "$WORK/tidy.log" || fail "web_friend not fixed"
grep -qx "web_panel_squad: добавлены серверы (группа KlausVPN)" "$WORK/tidy.log" || fail "web_panel_squad not fixed"
[ "$(wc -l < "$WORK/tidy.log")" = "2" ] || fail "tidy-users touched more users"
for u in web_friend web_panel_squad web_chosen web_old friend_1; do echo "$u $(web_state "$u")"; done
[ "$(web_state web_friend)" = '{"squads":["KlausVPN"],"end":"2099-12-31T00:00:00"}' ] || fail "web_friend state"
[ "$(web_state web_panel_squad)" = "{\"squads\":[\"KlausVPN\"],\"end\":\"$MONTH\"}" ] || fail "web_panel_squad state"
[ "$(web_state web_chosen)" = "{\"squads\":[\"KlausVPN\"],\"end\":\"$MONTH\"}" ] || fail "a chosen date or squad was changed"
[ "$(web_state web_old | jq -c .squads)" = "[]" ] || fail "a user older than two days was changed"
[ "$(web_state friend_1 | jq -r .end)" = "2099-12-31T00:00:00" ] || fail "friend_1 changed"
grep -q "@127.0.0.1:$VPN_PORT" <<<"$(sub_get "$UA_APP" "$WEB_SUB" | base64 -d)" || fail "no server in web_friend's subscription"
retry 10 "$WORK/e2e" check -sub "$WEB_SUB" -resolve "$SUB_DOMAIN:127.0.0.1" -cacert "$WORK/caddy-root.crt" \
  -hwid cccccccccccccccccccccccccccccccc -url "http://$WEB/" -expect "$TOKEN" >/dev/null || fail "web_friend's traffic"
kp tidy-users | tee "$WORK/tidy-2.log"
grep -q "Исправлять нечего" "$WORK/tidy-2.log" || fail "second run"
[ -z "$(kp tidy-users --quiet)" ] || fail "the timer's run is not quiet"
for u in web_friend web_panel_squad web_chosen web_old; do kp delete-user "$u" --yes >/dev/null; done
pass "web form defaults (no squad, tomorrow; the panel's own squad) fixed, traffic flows; a chosen date and squad, older users and CLI users untouched; the timer's run is quiet"

step "disable-node / enable-node"
kp disable-node test-node
sub_get "$UA_APP" "$SUB" | base64 -d > "$WORK/node-off.links"
if grep -q "@127.0.0.1:$VPN_PORT" "$WORK/node-off.links"; then fail "disabled node still in the subscription"; fi
kp enable-node test-node
grep -q "@127.0.0.1:$VPN_PORT" <<<"$(sub_get "$UA_APP" "$SUB" | base64 -d)" || fail "node not back"
pass "a disabled node leaves the subscription and comes back"

step "hwid-limit"
kp add-user friend_2 > "$WORK/add-user-2.log"
SUB2="$(grep -o "https://$SUB_DOMAIN/[A-Za-z0-9_-]*" "$WORK/add-user-2.log" | head -n 1)"
sub_get "$UA_APP" "$SUB2" -H "X-Hwid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" -o /dev/null
kp hwid-limit 1 --yes
sub_get "$UA_APP" "$SUB2" -H "X-Hwid: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" -o /dev/null
if grep -qi '^x-hwid-max-devices-reached' "$WORK/last.headers"; then fail "known device refused"; fi
sub_get "$UA_APP" "$SUB2" -H "X-Hwid: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" -o /dev/null
grep -i '^x-hwid' "$WORK/last.headers"
grep -qi '^x-hwid-max-devices-reached: true' "$WORK/last.headers" || fail "second device not refused"
sub_get "$UA_APP" "$SUB2" -o /dev/null
grep -qi '^x-hwid-not-supported: true' "$WORK/last.headers" || fail "request without X-Hwid not flagged"
kp hwid-limit off
grep -q "@127.0.0.1:$VPN_PORT" <<<"$(sub_get "$UA_APP" "$SUB2" -H "X-Hwid: bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" | base64 -d)" ||
  fail "limit off"
pass "limit 1: known device served, second refused, no X-Hwid flagged; off again"

step "block reports from friends' apps -> one Telegram alert"
SHORT1="${SUB##*/}"
SHORT2="${SUB2##*/}"
kp add-user friend_5 > "$WORK/add-user-5.log"
SUB5="$(grep -o "https://$SUB_DOMAIN/[A-Za-z0-9_-]*" "$WORK/add-user-5.log" | head -n 1)"
SHORT5="${SUB5##*/}"
ALERT="Kirov VPN: сервер"
# The monitor's token finds one subscription by its id, never lists them.
MON_TOKEN="$(sed -n 's/^PANEL_TOKEN=//p' "$WORK/opt/klaus-monitor.env")"
code="$(curl -sS --noproxy '*' -o /dev/null -w '%{http_code}' -H 'X-Forwarded-For: 127.0.0.1' -H 'X-Forwarded-Proto: https' \
  -H @<(printf 'Authorization: Bearer %s\n' "$MON_TOKEN") "http://127.0.0.1:3000/api/users?start=0&size=1")"
echo "the monitor's token lists users -> $code"
[ "$code" = "403" ] || fail "the monitor's token can list users"
code="$(report "unknown${SHORT1:7}" wifi)"
echo "unknown subscription -> $code"
[ "$code" = "403" ] || fail "an unknown subscription was not refused"
code="$(sub_code "klaus/report?s=$SHORT1&h=127.0.0.1&p=0")"
echo "malformed report -> $code"
[ "$code" = "400" ] || fail "a malformed report was accepted"
# The mobile whitelist regime: the apps moved to a server inside the
# operator's whitelist (w=1). Not a block: a note, never "disable it".
NOTE="Kirov VPN: мобильный интернет в режиме белых списков"
code="$(report "$SHORT1" mobile "МТС" 1)"
echo "friend_1 (МТС, whitelist) report -> $code"
[ "$code" = "200" ] || fail "report refused"
code="$(report "$SHORT2" mobile "Билайн" 1)"
echo "friend_2 (Билайн, whitelist) report -> $code"
[ "$code" = "200" ] || fail "report refused"
has_note() { [ "$(sent_count "$NOTE")" -ge 1 ]; }
retry 10 has_note || { docker logs klaus-monitor; fail "no whitelist note after two friends"; }
mock /_mock/tg/sent | jq -r --arg p "$NOTE" '.[] | select(.text | startswith($p)) | .text' | tee "$WORK/note.txt"
[ "$(sent_count "$NOTE")" = "1" ] || fail "more than one whitelist note"
[ "$(sent_count "$ALERT")" = "0" ] || fail "the whitelist regime raised a blocking alert"
for want in "у 2 человек" "«Германия»" "МТС ×1" "Билайн ×1" "менять этот не нужно" "обычное сообщение о блокировке"; do
  grep -qF "$want" "$WORK/note.txt" || fail "whitelist note lacks: $want"
done
if grep -q "disable-node" "$WORK/note.txt"; then fail "the whitelist note suggests disabling the server"; fi
for i in 1 2; do
  code="$(report "$SHORT1" mobile "МТС")"
  echo "friend_1 (МТС) report $i -> $code"
  [ "$code" = "200" ] || fail "report refused"
done
sleep 3
[ "$(sent_count "$ALERT")" = "0" ] || fail "one friend alone raised an alert"
echo "one friend twice: no alert"
code="$(report "$SHORT2" wifi)"
echo "friend_2 (Wi-Fi) report -> $code"
[ "$code" = "200" ] || fail "report refused"
has_alert() { [ "$(sent_count "$ALERT")" -ge 1 ]; }
retry 10 has_alert || { docker logs klaus-monitor; fail "no alert after two friends"; }
mock /_mock/tg/sent | jq -r --arg p "$ALERT" '.[] | select(.text | startswith($p)) | "to \(.chat_id):\n\(.text)"' | tee "$WORK/alert.txt"
[ "$(sent_count "$ALERT")" = "1" ] || fail "more than one alert"
for want in "«Германия»" "у 2 человек" "МТС ×1" "Wi-Fi ×1" "похоже на блокировку" \
  "klaus-panel add-node" "klaus-panel disable-node test-node"; do
  grep -qF "$want" "$WORK/alert.txt" || fail "alert text lacks: $want"
done
grep -q "^to $TG_CHAT:" "$WORK/alert.txt" || fail "alert went to another chat"
code="$(report "$SHORT5" mobile "Билайн")"
echo "friend_5 (Билайн) report -> $code"
[ "$code" = "200" ] || fail "report refused"
sleep 3
[ "$(sent_count "$ALERT")" = "1" ] || fail "the cooldown did not hold back a second alert"
echo "a third friend within the cooldown: still one alert"
first429=""
for i in $(seq 1 35); do
  code="$(report "$SHORT5" other)"
  if [ "$code" = "429" ]; then first429="$i"; break; fi
  [ "$code" = "200" ] || fail "report $i -> $code"
done
echo "friend_5's report no. $((first429 + 1)) within an hour -> 429"
[ "$first429" = "30" ] || fail "rate limit (30 per hour) not applied"
# Made-up ids cost the panel nothing and never crowd out a friend.
codes=""
for i in $(seq 1 80); do codes="$codes $(report "random$(printf '%08d' "$i")x" wifi)"; done
echo "80 made-up ids ->$(tr ' ' '\n' <<<"$codes" | sort | uniq -c | awk '{printf " %s×%s", $2, $1}')"
[ -z "$(tr ' ' '\n' <<<"$codes" | grep -v '^$' | grep -vx 403)" ] || fail "a made-up id was not refused with 403"
code="$(report "$SHORT2" mobile "Tele2")"
echo "friend_2 right after them -> $code"
[ "$code" = "200" ] || fail "made-up ids crowded out a real friend"
docker logs klaus-monitor > "$WORK/monitor.log" 2>&1
docker logs caddy > "$WORK/caddy.log" 2>&1
cat "$WORK/monitor.log"
for secret in "$SHORT1" "$SHORT2" "$SHORT5" "unknown${SHORT1:7}" random0000 "$SPOOFED_IP" friend_; do
  if grep -qF "$secret" "$WORK/monitor.log" "$WORK/caddy.log"; then fail "logs contain $secret"; fi
done
if grep -Eq '([0-9]{1,3}\.){3}[0-9]{1,3}' "$WORK/monitor.log"; then fail "the monitor's log contains an IP address"; fi
if grep -Eq '"(remote_ip|client_ip|uri)"' "$WORK/caddy.log"; then fail "Caddy's log keeps request addresses or links"; fi
pass "monitor token cannot list users; unknown friend 403, two friends in the whitelist regime one note without disable-node, one friend twice no alert, two friends one alert (Russian, blocking hint), cooldown, rate limit 30/h, 80 made-up ids do not block a friend; no IPs or ids in the logs"

step "backup; a restore that stops early (a .ru domain given by mistake)"
BACKUP_DIR="$WORK/backups" kp backup
BACKUP="$(ls "$WORK"/backups/*.tar.gz)"
tar -tzf "$BACKUP" | sort | tr '\n' ' '; echo
docker compose --project-directory "$WORK/opt" down -v >/dev/null 2>&1
CONF="$WORK/opt2/klaus-panel.env"
if install_panel "$WORK/opt2" "$WORK/admin2.txt" RESTORE="$BACKUP" PANEL_DOMAIN=panel.klaus.ru > "$WORK/install-restore-1.log" 2>&1; then
  fail "restore with a .ru domain went through"
fi
tail -n 1 "$WORK/install-restore-1.log"
[ -f "$WORK/opt2/.env" ] || fail "the stopped restore should have left its settings"
pass "stopped before the database, settings already in place"

step "the restore again, now with a broken database dump: stops in pg_restore"
mkdir -p "$WORK/broken"
tar -C "$WORK/broken" -xzf "$BACKUP"
size="$(stat -c %s "$WORK/broken/remnawave-db.dump")"
head -c "$((size / 2))" "$WORK/broken/remnawave-db.dump" > "$WORK/broken/half" && mv "$WORK/broken/half" "$WORK/broken/remnawave-db.dump"
tar -C "$WORK/broken" -czf "$WORK/broken.tar.gz" .
if install_panel "$WORK/opt2" "$WORK/admin2.txt" RESTORE="$WORK/broken.tar.gz" > "$WORK/install-restore-2.log" 2>&1; then
  fail "restore of a broken dump went through"
fi
grep -E "Восстанавливаю базу|pg_restore|Ошибка" "$WORK/install-restore-2.log" | tail -n 4
grep -q "не удалось восстановить базу" "$WORK/install-restore-2.log" || fail "not stopped in pg_restore"
if db_volume; then fail "the half-restored database was left behind"; fi
pass "the retry of a stopped restore is accepted; the failed one removes its half-restored database"

step "install-panel.sh without RESTORE refuses to build an empty panel"
if install_panel "$WORK/opt2" "$WORK/admin2.txt" > "$WORK/install-norestore.log" 2>&1; then
  fail "a run without RESTORE went through"
fi
tail -n 1 "$WORK/install-norestore.log"
grep -q "перенос панели из резервной копии не закончен" "$WORK/install-norestore.log" || fail "wrong refusal"
if db_volume || grep -q "Создаю администратора" "$WORK/install-norestore.log"; then fail "an empty panel was started"; fi
pass "refused, nothing started"

step "the same restore command once more (over a leftover database, as if killed in pg_restore)"
docker run --rm -v remnawave-db-data:/d caddy:2 touch /d/leftover
install_panel "$WORK/opt2" "$WORK/admin2.txt" RESTORE="$BACKUP" 2>&1 | tee "$WORK/install-restore.log"
grep -q "Готово! Панель работает" "$WORK/install-restore.log" || fail "restore failed"
if grep -q "Создаю администратора" "$WORK/install-restore.log"; then fail "restore made a new admin"; fi
[ ! -e "$WORK/opt2/.restore-unfinished" ] || fail "restore marker left behind"
docker run --rm -v remnawave-db-data:/d caddy:2 test ! -e /d/leftover || fail "the leftover database was reused"
retry 45 node_up || { kp list-nodes; fail "node did not reconnect to the restored panel"; }
kp list-users
retry 10 "$WORK/e2e" check -sub "$SUB" -resolve "$SUB_DOMAIN:127.0.0.1" -cacert "$WORK/caddy-root.crt" \
  -hwid "$HWID" -url "http://$WEB/" -expect "$TOKEN" || fail "old link after restore"
code="$(sub_code app/version.json)"
{ [ "$code" = "200" ] && jq -e '.versionCode == 99' "$WORK/last.body" >/dev/null; } || fail "published app not restored"
sub_code app/KirovVPN.apk >/dev/null
cmp -s "$WORK/last.body" "$APK" || fail "restored APK differs"
code="$(sub_code app/windows/version.json)"
{ [ "$code" = "200" ] && jq -e '.versionCode == 42' "$WORK/last.body" >/dev/null; } || fail "published Windows installer not restored"
sub_code app/windows/KirovVPN-Setup.exe >/dev/null
cmp -s "$WORK/last.body" "$EXE" || fail "restored installer differs"
grep -qx "TELEGRAM_NOTIFY_NODES=$TG_CHAT" "$WORK/opt2/.env" || fail "Telegram settings not restored"
monitor_up() { [ "$(sub_code klaus/health)" = "200" ]; }
retry 20 monitor_up || fail "monitor not running after restore"
# The restored monitor token works: a known friend is accepted.
code="$(report "$SHORT2" wifi)"
echo "report after restore -> $code"
[ "$code" = "200" ] || fail "restored monitor refuses a known friend"
pass "restored panel: same link, node reconnected by itself, traffic flows; app (Android and Windows), Telegram and monitor back"

step "RESTORE over the working panel is refused"
if install_panel "$WORK/opt2" "$WORK/admin2.txt" RESTORE="$BACKUP" > "$WORK/install-restore-3.log" 2>&1; then
  fail "restore over a working panel went through"
fi
tail -n 1 "$WORK/install-restore-3.log"
grep -q "восстанавливать можно только на новый сервер" "$WORK/install-restore-3.log" || fail "wrong refusal"
kp list-users >/dev/null || fail "panel broken by the refused restore"
pass "refused, the panel keeps working"
