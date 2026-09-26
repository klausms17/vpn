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
#
# and then
#   (a) the app's User-Agent gets a base64 list with a vless REALITY link,
#   (b) a browser gets the page; its Klaus VPN button is klausvpn://add/…,
#   (c) the device from X-Hwid is recorded in the panel,
#   (d) the app's own Go core (libxray, via test/e2e) parses the
#       subscription and fetches a page through the node,
#   plus: a disabled friend is refused by the node, a disabled node leaves
#   the subscription, the device limit works, a domain change reaches Caddy,
#   the support link follows SUPPORT_URL (never the panel's placeholder),
#   the node keeps its custom port on a re-run, and a backup restored into a
#   fresh panel serves the same link, also after failed attempts (which
#   leave no half-restored database, and a run without RESTORE refuses to
#   build an empty panel over them).
#
# Everything is removed at the end (the images stay); KEEP=1 leaves it
# running. Logs stay in $WORK.
#
# Needs root (it adds 11.11.11.11 to lo for the test page: the profile
# blocks private addresses), Docker with compose, go, jq, curl, openssl,
# iproute2, the free ports 80, 443, 3000, 3001, 3010, 6767, 42222, 44443,
# 44080 and no real Remnawave on this machine. Missing images are pulled
# from mirror.gcr.io (Docker Hub limits anonymous pulls).
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
IMAGES="remnawave/backend:3 postgres:18.4 valkey/valkey:9-alpine remnawave/subscription-page:latest caddy:2 remnawave/node:latest"

step() { printf '\n\033[1;36m### %s\033[0m\n' "$*"; }
pass() { printf '\033[1;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- checks
[ "$(id -u)" -eq 0 ] || fail "run as root"
for c in docker go jq curl openssl ip base64; do command -v "$c" >/dev/null || fail "missing $c"; done
docker info >/dev/null 2>&1 || fail "docker is not running"
if [ -e /opt/remnawave ] || [ -e /opt/remnanode ]; then fail "this machine has a real Remnawave setup; not touching it"; fi
for c in remnawave remnawave-db remnawave-redis remnawave-subscription-page caddy remnanode; do
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
kp() { KLAUS_PANEL_CONF="$CONF" bash "$RWS/klaus-panel" "$@"; }
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
  limits_override "$dir" remnawave remnawave-db remnawave-redis remnawave-subscription-page caddy
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

step "install-panel.sh (fresh)"
CONF="$WORK/opt/klaus-panel.env"
install_panel "$WORK/opt" "$WORK/admin.txt" PANEL_DOMAIN="$PANEL_DOMAIN" SUB_DOMAIN="$SUB_DOMAIN" \
  REALITY_SNI=www.example.com REALITY_TARGET="$TARGET" REALITY_PORT="$VPN_PORT" \
  APK_URL=https://example.com/KlausVPN.apk 2>&1 | tee "$WORK/install-1.log"
grep -q "Готово! Панель работает" "$WORK/install-1.log" || fail "install-panel.sh failed"
[ "$(stat -c %a "$WORK/admin.txt")" = "600" ] || fail "admin credentials are not 600"
grep -Eq '^Пароль: [A-Za-z0-9]{24,}$' "$WORK/admin.txt" || fail "admin password"
pass "panel installed, admin saved to admin.txt (600)"

step "install-panel.sh again (must change nothing)"
caddy_started="$(docker inspect -f '{{.State.StartedAt}}' caddy)"
install_panel "$WORK/opt" "$WORK/admin.txt" 2>&1 | tee "$WORK/install-2.log"
grep -q "Готово! Панель работает" "$WORK/install-2.log" || fail "re-run failed"
if grep -E "Создаю|Обновляю|Перезапускаю Caddy" "$WORK/install-2.log"; then fail "re-run created or changed something"; fi
[ "$(docker inspect -f '{{.State.StartedAt}}' caddy)" = "$caddy_started" ] || fail "re-run restarted Caddy for nothing"
counts="$(jq -n --argjson p "$(api /api/config-profiles)" --argjson s "$(api /api/internal-squads)" \
  --argjson r "$(api /api/subscription-settings)" '{
    profiles: [$p.response.configProfiles[] | select(.name == "KlausVPN")] | length,
    squads: [$s.response.internalSquads[] | select(.name == "KlausVPN")] | length,
    rules: [$r.response.responseRules.rules[].name]}')"
echo "$counts"
jq -e '.profiles == 1 and .squads == 1 and ([.rules[] | select(. == "Klaus VPN")] | length) == 1
  and .rules[0] == "Browser Subscription" and .rules[1] == "Klaus VPN" and .rules[-1] == "Fallback Base64"' <<<"$counts" >/dev/null ||
  fail "duplicates or wrong rule order"
pass "re-run is idempotent; rule order: browser, Klaus VPN, …, fallback"
docker exec caddy cat /data/caddy/pki/authorities/local/root.crt > "$WORK/caddy-root.crt"

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

step "install-panel.sh with a new SUB_DOMAIN and SUPPORT_URL: Caddy serves the new name"
install_panel "$WORK/opt" "$WORK/admin.txt" SUB_DOMAIN="$SUB_DOMAIN2" SUPPORT_URL="$SUPPORT" 2>&1 | tee "$WORK/install-3.log"
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
pass "back on $SUB_DOMAIN; support link as without SUPPORT_URL again"

step "klaus-panel add-node + install-node.sh"
GW="$(docker network inspect remnawave-network -f '{{(index .IPAM.Config 0).Gateway}}')"
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
if grep -i '^support-url' "$WORK/last.headers"; then fail "support-url header without SUPPORT_URL"; fi
if grep -qi 'dummy\.docs\.rw' "$WORK/last.headers"; then fail "placeholder in the headers"; fi
grep -Eq "^vless://[0-9a-f-]+@127\.0\.0\.1:$VPN_PORT\?.*security=reality.*pbk=.*#%D0%93%D0%B5%D1%80%D0%BC%D0%B0%D0%BD%D0%B8%D1%8F$" "$WORK/a.links" ||
  fail "no vless reality link named Германия"
pass "base64 list with a vless REALITY link"

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
pass "restored panel: same link, node reconnected by itself, traffic flows"

step "RESTORE over the working panel is refused"
if install_panel "$WORK/opt2" "$WORK/admin2.txt" RESTORE="$BACKUP" > "$WORK/install-restore-3.log" 2>&1; then
  fail "restore over a working panel went through"
fi
tail -n 1 "$WORK/install-restore-3.log"
grep -q "восстанавливать можно только на новый сервер" "$WORK/install-restore-3.log" || fail "wrong refusal"
kp list-users >/dev/null || fail "panel broken by the refused restore"
pass "refused, the panel keeps working"
