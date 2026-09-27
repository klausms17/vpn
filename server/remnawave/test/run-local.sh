#!/usr/bin/env bash
# Local end-to-end test of server/remnawave. Not needed on real servers.
#
# Brings the whole friends' setup up on this one machine, with the real
# scripts and images, and checks that a friend's subscription really works:
#
#   install-panel.sh  panel, database, valkey, subscription page and Caddy
#                     (with its internal CA instead of Let's Encrypt), run
#                     twice to check that a re-run changes nothing, then
#                     with a new SUB_DOMAIN and SUPPORT_URL and back
#   klaus-panel       add-node (panel -> node over the Docker bridge, friends
#                     -> node on 127.0.0.1), add-user, disable/enable,
#                     hwid-limit, backup
#   install-node.sh   remnanode on the host network, run again with only
#                     PANEL_IP (as after a panel move)
#   telegram-setup    against a mock Telegram API (test/mock-apis.py): the
#                     chat is taken from getUpdates, a test message goes out,
#                     the panel's own node messages arrive too
#   publish-apk       against a mock GitHub API with a fake "stable"
#                     release (the default; a panel that saved the old
#                     default, the work branch's test builds, moves to it
#                     once, a branch chosen on purpose stays)
#
# and then
#   (a) the app's User-Agent gets a base64 list with a vless REALITY link
#       and the klaus-report-url / klaus-app-url headers,
#   (b) a browser gets the page; its Klaus VPN button is klausvpn://add/…,
#   (c) the device from X-Hwid is recorded in the panel,
#   (d) the app's own Go core (libxray, via test/e2e) parses the
#       subscription and fetches a page through the node,
#   plus: a disabled friend is refused by the node, a disabled node leaves
#   the subscription, the device limit works, a domain change reaches Caddy,
#   the support link follows SUPPORT_URL (never the panel's placeholder),
#   the node keeps its custom port on a re-run, the node's Xray keeps idle
#   connections 30 minutes (the profile's policy), the APK is published
#   with a verified checksum (a wrong one is refused, a missing release is
#   quiet for the timer) on https://SUB/app/ with version.json and the
#   page's download button, block reports (unknown friend refused, one
#   friend twice is no alert, two friends in the mobile whitelist regime
#   are one note that never says "disable", two friends are exactly one
#   Russian alert, then quiet; rate limit; no IPs or ids in the logs),
#   and a backup restored into a fresh panel serves the same link, the app
#   and the monitor, also after failed attempts (which leave no
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
UA_APP='KlausVPN/1.0.99 (Android)'
UA_BROWSER='Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36'
IMAGES="remnawave/backend:3 postgres:18.4 valkey/valkey:9-alpine remnawave/subscription-page:latest caddy:2 remnawave/node:latest python:3-alpine"
MOCK_PORT=18090 # mock Telegram and GitHub APIs
TG_BOT_TOKEN=123456789:AAklaus-e2e-bot-token-0123456789abcdef
TG_CHAT=4242
GH_TEST_TOKEN=github_pat_klaus_e2e_0123456789
RELEASE=stable
RELEASE_OLD=build-claude-compassionate-mayer-6jph8m # the default before "stable"
APK_VERSION=1.0.99
SPOOFED_IP=203.0.113.77 # a client IP that must never reach the monitor's log
# This machine's own tokens must never reach the panel under test.
unset GITHUB_TOKEN GH_TOKEN TELEGRAM_API_BASE

step() { printf '\n\033[1;36m### %s\033[0m\n' "$*"; }
pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- checks
[ "$(id -u)" -eq 0 ] || fail "run as root"
for c in docker go python3 jq curl openssl ip base64 sha256sum; do command -v "$c" >/dev/null || fail "missing $c"; done
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
api() { # PATH -> body (read-only calls with the CLI token)
  local tok
  # shellcheck disable=SC1090
  tok="$(. "$CONF" && printf '%s' "$API_TOKEN")"
  curl -sS --noproxy '*' -H 'X-Forwarded-For: 127.0.0.1' -H 'X-Forwarded-Proto: https' \
    -H @<(printf 'Authorization: Bearer %s\n' "$tok") "http://127.0.0.1:3000$1"
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

step "Mock Telegram and GitHub APIs on :$MOCK_PORT (a fake release with KlausVPN-$APK_VERSION.apk)"
APK="$WORK/KlausVPN-$APK_VERSION.apk"
head -c 3000000 /dev/urandom > "$APK"
python3 "$HERE/mock-apis.py" --port "$MOCK_PORT" --tg-token "$TG_BOT_TOKEN" --gh-token "$GH_TEST_TOKEN" \
  --tag "$RELEASE" --bad-tag klaus-bad-sum --temp-tag klaus-temp-key --apk "$APK" > "$WORK/mock.log" 2>&1 &
MOCK_PID=$!
retry 10 mock /_mock/tg/sent -o /dev/null || fail "mock APIs did not start"
pass "mock APIs answer"

step "install-panel.sh (fresh)"
CONF="$WORK/opt/klaus-panel.env"
install_panel "$WORK/opt" "$WORK/admin.txt" PANEL_DOMAIN="$PANEL_DOMAIN" SUB_DOMAIN="$SUB_DOMAIN" \
  REALITY_SNI=www.example.com REALITY_TARGET="$TARGET" REALITY_PORT="$VPN_PORT" \
  APK_URL=https://example.com/KlausVPN.apk 2>&1 | tee "$WORK/install-1.log"
grep -q "Готово! Панель работает" "$WORK/install-1.log" || fail "install-panel.sh failed"
[ "$(stat -c %a "$WORK/admin.txt")" = "600" ] || fail "admin credentials are not 600"
grep -Eq '^Пароль: [A-Za-z0-9]{24,}$' "$WORK/admin.txt" || fail "admin password"
grep -qx "RELEASE_TAG=$RELEASE" "$CONF" && grep -qx "STABLE_DEFAULT=1" "$CONF" || fail "new panel does not take the stable release"
pass "panel installed, admin saved to admin.txt (600), app builds from the $RELEASE release"

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
[ "$(mock /_mock/tg/sent | jq --arg c "$TG_CHAT" '[.[] | select(.chat_id == $c and (.text | startswith("Klaus VPN: оповещения включены")))] | length')" = "1" ] ||
  fail "no test message to chat $TG_CHAT"
grep -E '^(IS_TELEGRAM_NOTIFICATIONS_ENABLED|TELEGRAM_BOT_API_ROOT|TELEGRAM_NOTIFY_NODES)=' "$WORK/opt/.env"
grep -qx "IS_TELEGRAM_NOTIFICATIONS_ENABLED=true" "$WORK/opt/.env" || fail "panel notifications not enabled"
grep -qx "TELEGRAM_NOTIFY_NODES=$TG_CHAT" "$WORK/opt/.env" || fail "panel does not notify the chat"
grep -qx "TELEGRAM_BOT_API_ROOT=$TG_API" "$WORK/opt/.env" || fail "panel does not use the mock"
grep -qx "TELEGRAM_CHAT_ID=$TG_CHAT" "$WORK/opt/klaus-monitor.env" || fail "monitor settings"
[ "$(docker exec klaus-monitor printenv TELEGRAM_CHAT_ID)" = "$TG_CHAT" ] || fail "monitor not recreated with the chat"
[ "$(docker exec remnawave printenv TELEGRAM_NOTIFY_NODES)" = "$TG_CHAT" ] || fail "panel not recreated with the chat"
kp telegram-test
[ "$(sent_count "Klaus VPN: проверка оповещений")" = "1" ] || fail "telegram-test"
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
pass "no support-url header, the page's support button opens the note on https://$SUB_DOMAIN/"

step "install-panel.sh with a new SUB_DOMAIN and SUPPORT_URL (and a branch's builds chosen): Caddy serves the new name"
install_panel "$WORK/opt" "$WORK/admin.txt" SUB_DOMAIN="$SUB_DOMAIN2" SUPPORT_URL="$SUPPORT" RELEASE_TAG="$RELEASE_OLD" \
  2>&1 | tee "$WORK/install-3.log"
grep -q "Готово! Панель работает" "$WORK/install-3.log" || fail "re-run with a new SUB_DOMAIN failed"
grep -q "адрес подписок меняется: $SUB_DOMAIN → $SUB_DOMAIN2" "$WORK/install-3.log" || fail "no warning about the old links"
https_get "$SUB_DOMAIN2" -o /dev/null || fail "Caddy does not serve the new $SUB_DOMAIN2"
if https_get "$SUB_DOMAIN" -o /dev/null 2>/dev/null; then fail "Caddy still serves the old $SUB_DOMAIN"; fi
grep -qx "SUB_PUBLIC_DOMAIN=$SUB_DOMAIN2" "$WORK/opt/.env" || fail "panel .env not updated"
support_links | tee "$WORK/support-1.json"
jq -e --arg s "$SUPPORT" '.header == $s and .page == $s' "$WORK/support-1.json" >/dev/null || fail "SUPPORT_URL not applied"
pass "https://$SUB_DOMAIN2 served with a certificate, the old name is not; SUPPORT_URL in the header and on the page"

step "install-panel.sh back to $SUB_DOMAIN with SUPPORT_URL removed (settings converge)"
install_panel "$WORK/opt" "$WORK/admin.txt" SUB_DOMAIN="$SUB_DOMAIN" SUPPORT_URL= 2>&1 | tee "$WORK/install-4.log"
grep -q "Готово! Панель работает" "$WORK/install-4.log" || fail "re-run back to $SUB_DOMAIN failed"
https_get "$SUB_DOMAIN" -o /dev/null || fail "Caddy does not serve $SUB_DOMAIN again"
if https_get "$SUB_DOMAIN2" -o /dev/null 2>/dev/null; then fail "Caddy still serves $SUB_DOMAIN2"; fi
support_links | tee "$WORK/support-2.json"
cmp -s "$WORK/support-0.json" "$WORK/support-2.json" || fail "support link did not return to the state without SUPPORT_URL"
grep -qx "RELEASE_TAG=$RELEASE_OLD" "$CONF" || fail "a release chosen on purpose was not kept"
if grep -q "стабильные" "$WORK/install-4.log"; then fail "a release chosen on purpose was moved to stable"; fi
pass "back on $SUB_DOMAIN; support link as without SUPPORT_URL again; the chosen $RELEASE_OLD stays"

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

step "idle connections: the node's Xray keeps them 30 minutes (policy of the profile)"
PROFILE_UUID="$(api /api/config-profiles | jq -r 'first(.response.configProfiles[] | select(.name == "KlausVPN")) | .uuid')"
api "/api/config-profiles/$PROFILE_UUID" | jq -c '.response.config.policy' | tee "$WORK/profile-policy.json"
jq -e '.levels["0"].connIdle == 1800' "$WORK/profile-policy.json" >/dev/null || fail "no idle policy in the profile"
# What the node's Xray really runs, read the way Xray itself reads it. Only
# the policy is printed: the config holds the keys.
node_policy() {
  docker exec remnanode node -e '
    const fs = require("fs"), http = require("http");
    const env = (n) => fs.readFileSync("/run/s6/container_environment/" + n, "utf8").trim();
    http.get({socketPath: "\0" + env("INTERNAL_SOCKET_PATH"),
              path: "/internal/get-config?token=" + env("INTERNAL_REST_TOKEN")}, (r) => {
      let b = "";
      r.on("data", (d) => (b += d));
      r.on("end", () => console.log(JSON.stringify(JSON.parse(b).policy || null)));
    }).on("error", (e) => { console.error(e.message); process.exit(1); });' > "$WORK/node-policy.json" 2>&1 &&
    jq -e '.levels["0"].connIdle == 1800' "$WORK/node-policy.json" >/dev/null
}
retry 10 node_policy || { cat "$WORK/node-policy.json"; fail "the node's Xray closes idle connections sooner"; }
cat "$WORK/node-policy.json"
pass "connIdle 1800 in the profile and in the node's running Xray (next to its own statistics settings)"

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
grep -qi '^profile-title: Klaus VPN' "$WORK/last.headers" || fail "profile-title"
grep -iE '^klaus-(report|app)-url' "$WORK/last.headers"
grep -qi "^klaus-report-url: https://$SUB_DOMAIN/klaus/report" "$WORK/last.headers" || fail "klaus-report-url"
grep -qi "^klaus-app-url: https://$SUB_DOMAIN/app/version.json" "$WORK/last.headers" || fail "klaus-app-url"
if grep -i '^support-url' "$WORK/last.headers"; then fail "support-url header without SUPPORT_URL"; fi
if grep -qi 'dummy\.docs\.rw' "$WORK/last.headers"; then fail "placeholder in the headers"; fi
grep -Eq "^vless://[0-9a-f-]+@127\.0\.0\.1:$VPN_PORT\?.*security=reality.*pbk=.*#%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F$" "$WORK/a.links" ||
  fail "no vless reality link named Германия"
pass "base64 list with a vless REALITY link; report and app URLs in the headers"

step "(b) a browser gets the page with the Klaus VPN button"
sub_get "$UA_BROWSER" "$SUB" -H 'Accept: text/html' -c "$WORK/cookies" > "$WORK/b.html"
grep -q "^HTTP/[0-9.]* 200" "$WORK/last.headers" || fail "no HTML page"
grep -qi '^content-type: text/html' "$WORK/last.headers" || fail "no HTML page"
grep -o '<title>[^<]*</title>' "$WORK/b.html"
# The page loads its app list with the session cookie it just set.
sub_get "$UA_BROWSER" "https://$SUB_DOMAIN/assets/.app-config-v2.json" -b "$WORK/cookies" > "$WORK/b.config.json"
jq -r '.platforms.android.apps[0] | "first Android app: \(.name) (featured: \(.featured))",
  (.blocks[].buttons[] | "  \(.type): \(.link)  «\(.text.ru)»")' "$WORK/b.config.json"
jq -e '.platforms.android.apps[0].name == "Klaus VPN" and
  ([.platforms.android.apps[0].blocks[].buttons[] | select(.type == "subscriptionLink" and .link == "klausvpn://add/{{SUBSCRIPTION_LINK}}")] | length == 1) and
  ([.platforms.android.apps[0].blocks[].buttons[] | select(.type == "external" and .link == "https://example.com/KlausVPN.apk")] | length == 1) and
  ([.platforms.android.apps[].name] | index("Happ") != null)' "$WORK/b.config.json" >/dev/null || fail "Klaus VPN button"
jq -r '"support button: \(.brandingSettings.supportUrl)"' "$WORK/b.config.json"
jq -e --arg p "https://$SUB_DOMAIN/" '.brandingSettings.supportUrl == $p' "$WORK/b.config.json" >/dev/null || fail "page support link"
if grep -q 'dummy\.docs\.rw' "$WORK/b.html" "$WORK/b.config.json"; then fail "placeholder support link on the page"; fi
echo "the page turns the button into: klausvpn://add/$SUB"
pass "HTML page; Klaus VPN first with klausvpn://add/{{SUBSCRIPTION_LINK}} and the APK button, default apps kept"

step "publish-apk: from the GitHub release to https://$SUB_DOMAIN/app/ (checksum verified)"
page_apk_buttons() { # -> the Klaus VPN block's download buttons in the panel's page settings
  local u
  u="$(api /api/subscription-page-configs | jq -r 'first((.response.configs[] | select(.uuid == "00000000-0000-0000-0000-000000000000")), .response.configs[0]) | .uuid')"
  api "/api/subscription-page-configs/$u" > "$WORK/page-config.json"
  jq -c '[.response.config.platforms.android.apps[0].blocks[].buttons[] | select(.type == "external") | {link, text: .text.ru}]' "$WORK/page-config.json"
}
if kp publish-apk > "$WORK/publish-0.log" 2>&1; then fail "publish-apk without GITHUB_TOKEN went through"; fi
tail -n 1 "$WORK/publish-0.log"
grep -q "нет GITHUB_TOKEN" "$WORK/publish-0.log" || fail "wrong refusal without a token"
# The owner adds the token later (and drops APK_URL: the app comes from here
# now) with the new install-panel.sh, on a panel whose settings were saved by
# an older one: its release is the old default, the work branch's test builds.
sed -i '/^STABLE_DEFAULT=/d' "$CONF"
grep -qx "RELEASE_TAG=$RELEASE_OLD" "$CONF" || fail "test setup: the old default is not saved"
install_panel "$WORK/opt" "$WORK/admin.txt" GITHUB_TOKEN="$GH_TEST_TOKEN" GITHUB_API="http://127.0.0.1:$MOCK_PORT" APK_URL= \
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
if compgen -G "$WORK/opt/app/*" >/dev/null; then fail "something was published from a missing release"; fi
echo "download buttons without APK_URL, nothing published: $(page_apk_buttons)"
[ "$(page_apk_buttons)" = "[]" ] || fail "download button without an APK"
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
echo old > "$WORK/opt/app/KlausVPN-1.0.10.apk"
echo recent > "$WORK/opt/app/KlausVPN-1.0.11.apk"
touch -d '14 hours ago' "$WORK/opt/app/KlausVPN-1.0.10.apk"
touch -d '2 hours ago' "$WORK/opt/app/KlausVPN-1.0.11.apk"
kp publish-apk | tee "$WORK/publish-1.log"
grep -q "Опубликована версия $APK_VERSION" "$WORK/publish-1.log" || fail "publish-apk"
ls "$WORK/opt/app"
[ ! -e "$WORK/opt/app/KlausVPN-1.0.10.apk" ] || fail "a build replaced 14 hours ago was kept"
[ -e "$WORK/opt/app/KlausVPN-1.0.11.apk" ] || fail "a build replaced 2 hours ago was deleted"
rm -f "$WORK/opt/app/KlausVPN-1.0.11.apk"
# An app that still offers a deleted build gets the current one.
code="$(sub_code app/KlausVPN-1.0.10.apk -D "$WORK/gone.headers")"
echo "https://$SUB_DOMAIN/app/KlausVPN-1.0.10.apk (deleted) -> $code $(grep -i '^location:' "$WORK/gone.headers" | tr -d '\r')"
[ "$code" = "302" ] && grep -qi '^location: /app/KlausVPN.apk' "$WORK/gone.headers" || fail "no redirect from a deleted build"
code="$(sub_code app/version.json)"
cat "$WORK/last.body"; echo
[ "$code" = "200" ] || fail "version.json not served"
jq -e --arg sub "$SUB_DOMAIN" --arg v "$APK_VERSION" --arg sha "$(sha256sum "$APK" | cut -d' ' -f1)" \
  '.versionCode == 99 and .versionName == $v and .apk == "https://\($sub)/app/KlausVPN-\($v).apk" and .sha256 == $sha' \
  "$WORK/last.body" >/dev/null || fail "version.json content"
for f in KlausVPN.apk "KlausVPN-$APK_VERSION.apk"; do
  code="$(sub_code "app/$f" -D "$WORK/apk.headers")"
  echo "https://$SUB_DOMAIN/app/$f -> $code, $(grep -i '^content-type' "$WORK/apk.headers" | tr -d '\r'), sha256 $(sha256sum < "$WORK/last.body" | cut -c1-16)…"
  [ "$code" = "200" ] || fail "$f not served"
  cmp -s "$WORK/last.body" "$APK" || fail "$f differs from the release"
  grep -qi '^content-type: application/vnd.android.package-archive' "$WORK/apk.headers" || fail "$f content type"
done
# The page restarts with its download button.
page_up() { sub_get "$UA_BROWSER" "$SUB" -H 'Accept: text/html' -f -o /dev/null 2>/dev/null; }
retry 30 page_up || fail "subscription page did not come back"
kp publish-apk | tee "$WORK/publish-2.log"
grep -q "уже опубликована" "$WORK/publish-2.log" || fail "the same build downloaded again"
echo "download buttons now: $(page_apk_buttons)"
[ "$(page_apk_buttons)" = "[{\"link\":\"https://$SUB_DOMAIN/app/KlausVPN.apk\",\"text\":\"Скачать приложение\"}]" ] ||
  fail "no download button for the published APK"
# What a friend's browser gets.
sub_get "$UA_BROWSER" "$SUB" -H 'Accept: text/html' -c "$WORK/cookies2" -o /dev/null
sub_get "$UA_BROWSER" "https://$SUB_DOMAIN/assets/.app-config-v2.json" -b "$WORK/cookies2" > "$WORK/b2.config.json"
jq -e --arg a "https://$SUB_DOMAIN/app/KlausVPN.apk" \
  '[.platforms.android.apps[0].blocks[].buttons[] | select(.type == "external" and .link == $a)] | length == 1' \
  "$WORK/b2.config.json" >/dev/null || fail "the page does not show the download button"
pass "old default moved to the $RELEASE release; a missing release is quiet for the timer, an unseen repository is not; wrong checksum and temporary key refused; replaced builds kept 13 h, then a redirect to the current one; KlausVPN.apk, KlausVPN-$APK_VERSION.apk and version.json (versionCode 99) served; page button «Скачать приложение»"

step "(c) the device is recorded (the limit itself is off)"
USER_ID="$(api "/api/users/by-username/friend_1" | jq -r '.response.id')"
api "/api/hwid/devices/$USER_ID" | jq -c '.response.devices[] | {hwid, platform, osVersion, deviceModel, userAgent}' | tee "$WORK/c.devices"
grep -q "\"hwid\":\"$HWID\"" "$WORK/c.devices" || fail "device not recorded"
api /api/subscription-settings | jq -c '.response.hwidSettings'
kp list-users
pass "device recorded"

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
ALERT="Klaus VPN: сервер"
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
NOTE="Klaus VPN: мобильный интернет в режиме белых списков"
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
for want in "у 2 человек" "«Германия»" "МТС ×1" "Билайн ×1" "не блокировка" "не нужно"; do
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
sub_code app/KlausVPN.apk >/dev/null
cmp -s "$WORK/last.body" "$APK" || fail "restored APK differs"
grep -qx "TELEGRAM_NOTIFY_NODES=$TG_CHAT" "$WORK/opt2/.env" || fail "Telegram settings not restored"
monitor_up() { [ "$(sub_code klaus/health)" = "200" ]; }
retry 20 monitor_up || fail "monitor not running after restore"
# The restored monitor token works: a known friend is accepted.
code="$(report "$SHORT2" wifi)"
echo "report after restore -> $code"
[ "$code" = "200" ] || fail "restored monitor refuses a known friend"
pass "restored panel: same link, node reconnected by itself, traffic flows; app, Telegram and monitor back"

step "RESTORE over the working panel is refused"
if install_panel "$WORK/opt2" "$WORK/admin2.txt" RESTORE="$BACKUP" > "$WORK/install-restore-3.log" 2>&1; then
  fail "restore over a working panel went through"
fi
tail -n 1 "$WORK/install-restore-3.log"
grep -q "восстанавливать можно только на новый сервер" "$WORK/install-restore-3.log" || fail "wrong refusal"
kp list-users >/dev/null || fail "panel broken by the refused restore"
pass "refused, the panel keeps working"
