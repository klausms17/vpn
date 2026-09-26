#!/usr/bin/env bash
# Klaus VPN panel installer: Remnawave panel + subscription page behind Caddy
# (automatic HTTPS), for handing out personal subscription links to friends.
#
# Run as root on a fresh Ubuntu 22.04+/Debian 12+ VPS abroad (2 CPU, 4 GB).
# Both domains must already point at this server. Copy this file and
# klaus-panel into one folder, then:
#
#   sudo PANEL_DOMAIN=panel.example.com SUB_DOMAIN=sub.example.com bash install-panel.sh
#
# Options (environment variables, written AFTER sudo):
#   PANEL_DOMAIN   admin panel address (required)
#   SUB_DOMAIN     subscription page address given to friends (required)
#   APK_URL        https link to the Klaus VPN APK (adds a download button)
#   SUPPORT_URL    https link friends can use to reach you (e.g. https://t.me/you)
#   ADMIN_USER     panel login (default admin); the password is generated
#   REALITY_SNI    site the VPN imitates (default: first working from a list)
#   FORCE=1        take over an existing Remnawave setup not made by this script
#   RESTORE=file   move the panel to this (new) server from a backup made by
#                  "klaus-panel backup": same users, links and servers
#   ALLOW_RU_DOMAIN=1  try a .ru/.su/.рф domain anyway (certificates for them
#                  are refused, so HTTPS will most likely not work)
#
# Re-running keeps all keys, users and passwords; it updates the containers
# and re-applies the settings (the previous files are kept as *.bak-*).
#
# For the local test harness only (server/remnawave/test): RW_DIR, ADMIN_FILE,
# SKIP_SYSTEM=1 (no apt/Docker/firewall/sysctl changes), CADDY_TLS=internal,
# REALITY_TARGET=host:port, REALITY_PORT.
set -euo pipefail

RW_DIR="${RW_DIR:-/opt/remnawave}"
ADMIN_FILE="${ADMIN_FILE:-/root/remnawave-admin.txt}"
SKIP_SYSTEM="${SKIP_SYSTEM:-0}"
CADDY_TLS="${CADDY_TLS:-acme}"
FORCE="${FORCE:-0}"
CONF="$RW_DIR/klaus-panel.env"
API_URL="http://127.0.0.1:3000"
SNI_CANDIDATES="www.nvidia.com www.samsung.com www.amd.com dl.google.com www.cisco.com"
# What the subscription page may read with its API token: panel version,
# its own settings and which of them a user gets. The page itself (/api/sub)
# needs no token.
SUBPAGE_SCOPES='["system:metadata", "subscription-page-configs:list", "subscription-page-configs:get", "subscriptions:subpage-config"]'
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mВнимание:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "запустите от root (sudo … bash install-panel.sh)"
[ -f "$HERE/klaus-panel" ] || die "рядом со скриптом нет файла klaus-panel: скопируйте оба файла в одну папку"

# ---------------------------------------------------------------- restore
# A backup brings back the settings files here and the database below.
# Friends' links stay the same once the domains point at this server.
RESTORE="${RESTORE:-}"
RESTORE_DIR=""
if [ -n "$RESTORE" ]; then
  [ -f "$RESTORE" ] || die "нет файла $RESTORE"
  if [ -f "$RW_DIR/.env" ] || docker volume inspect remnawave-db-data >/dev/null 2>&1; then
    die "восстанавливать можно только на новый сервер: здесь уже есть панель ($RW_DIR или база remnawave-db-data)"
  fi
  RESTORE_DIR="$(mktemp -d)"
  trap 'rm -rf "$RESTORE_DIR"' EXIT
  tar -C "$RESTORE_DIR" -xzf "$RESTORE" || die "не удалось распаковать $RESTORE"
  for f in remnawave-db.dump .env klaus-panel.env; do
    [ -f "$RESTORE_DIR/$f" ] || die "в $RESTORE нет $f: это не резервная копия klaus-panel"
  done
  mkdir -p "$RW_DIR"
  chmod 700 "$RW_DIR"
  for f in .env subscription.env klaus-panel.env; do
    if [ -f "$RESTORE_DIR/$f" ]; then install -m 600 "$RESTORE_DIR/$f" "$RW_DIR/$f"; fi
  done
  if [ -f "$RESTORE_DIR/admin.txt" ] && [ ! -f "$ADMIN_FILE" ]; then install -m 600 "$RESTORE_DIR/admin.txt" "$ADMIN_FILE"; fi
fi

# Settings from a previous run are the defaults for this one; variables
# given on the command line win.
conf_get() {
  [ -f "$CONF" ] || return 0
  # shellcheck disable=SC1090
  (. "$CONF" && eval "printf '%s' \"\${$1:-}\"")
}
PANEL_DOMAIN="${PANEL_DOMAIN:-$(conf_get PANEL_DOMAIN)}"
SUB_DOMAIN="${SUB_DOMAIN:-$(conf_get SUB_DOMAIN)}"
ADMIN_USER="${ADMIN_USER:-admin}"
APK_URL="${APK_URL-$(conf_get APK_URL)}"
SUPPORT_URL="${SUPPORT_URL-$(conf_get SUPPORT_URL)}"
SAVED_SNI="$(conf_get REALITY_SNI)"
REALITY_SNI="${REALITY_SNI:-$SAVED_SNI}"
REALITY_TARGET="${REALITY_TARGET-$(conf_get REALITY_TARGET)}"
REALITY_PORT="${REALITY_PORT:-$(conf_get REALITY_PORT)}"
REALITY_PORT="${REALITY_PORT:-443}"
PANEL_IP="${PANEL_IP:-}"
API_TOKEN="$(conf_get API_TOKEN)"

# ---------------------------------------------------------------- checks
norm_domain() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed -e 's#^[a-z]*://##' -e 's#/.*$##' -e 's/\.$//'; }
PANEL_DOMAIN="$(norm_domain "$PANEL_DOMAIN")"
SUB_DOMAIN="$(norm_domain "$SUB_DOMAIN")"
if [ -z "$PANEL_DOMAIN" ] || [ -z "$SUB_DOMAIN" ]; then
  die "укажите домены: sudo PANEL_DOMAIN=panel.example.com SUB_DOMAIN=sub.example.com bash install-panel.sh"
fi
[ "$PANEL_DOMAIN" != "$SUB_DOMAIN" ] || die "панели и подписке нужны два разных адреса (например panel.… и sub.…)"
for d in "$PANEL_DOMAIN" "$SUB_DOMAIN"; do
  case "$d" in
    *.ru | *.su | *.xn--p1ai | *.рф | *.РФ)
      [ "${ALLOW_RU_DOMAIN:-0}" = "1" ] ||
        die "$d: для доменов .ru/.su/.рф автоматический сертификат HTTPS (Let's Encrypt, ZeroSSL) не выдаётся. Купите домен в зоне .com/.net/.org и т. п." ;;
  esac
  [[ "$d" =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z0-9-]{2,}$ ]] ||
    die "странный домен: $d (нужен адрес вида panel.example.com, русские буквы — в виде xn--…)"
done
case "$APK_URL" in "" | https://*) ;; *) die "APK_URL должен начинаться с https://" ;; esac
case "$SUPPORT_URL" in "" | https://* | http://*) ;; *) die "SUPPORT_URL должен быть ссылкой https://…" ;; esac
case "$REALITY_PORT" in *[!0-9]* | "") die "REALITY_PORT должен быть числом" ;; esac

# Never silently take over a Remnawave setup this script did not create:
# FORCE=1 does it, keeping its database and secrets (copies of the files stay).
ADOPT=0
if [ ! -f "$CONF" ] && [ -f "$RW_DIR/docker-compose.yml" ] &&
  ! grep -q "Written by Klaus VPN install-panel.sh" "$RW_DIR/docker-compose.yml"; then
  [ "$FORCE" = "1" ] ||
    die "в $RW_DIR уже есть Remnawave, установленный не этим скриптом. Если его нужно перенастроить под Klaus VPN (пользователи и база сохранятся), запустите: sudo FORCE=1 … bash $0"
  ADOPT=1
fi

# ---------------------------------------------------------------- system
apt_install() {
  export DEBIAN_FRONTEND=noninteractive
  # A fresh VPS may still be running its first automatic updates: wait for
  # apt instead of failing on its lock.
  cloud-init status --wait >/dev/null 2>&1 || true
  for i in $(seq 1 60); do
    apt-get update -qq && break
    [ "$i" -eq 60 ] && die "apt занят другим процессом, повторите через несколько минут"
    sleep 10
  done
  apt-get -o DPkg::Lock::Timeout=600 install -y -qq "$@" >/dev/null
}

install_docker() {
  if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then return; fi
  say "Устанавливаю Docker (официальный скрипт get.docker.com)"
  # The official script calls apt-get itself; make it wait for the lock too.
  echo 'DPkg::Lock::Timeout "600";' > /etc/apt/apt.conf.d/99klausvpn-lock-wait
  local ok=0
  for _ in 1 2 3; do
    if curl -fsSL https://get.docker.com | sh >/dev/null; then ok=1; break; fi
    sleep 20
  done
  rm -f /etc/apt/apt.conf.d/99klausvpn-lock-wait
  [ "$ok" = "1" ] || die "не удалось установить Docker. Повторите запуск через пару минут"
  systemctl enable --now docker >/dev/null 2>&1 || true
  docker compose version >/dev/null 2>&1 || die "Docker установлен без docker compose"
}

if [ "$SKIP_SYSTEM" != "1" ]; then
  command -v systemctl >/dev/null || die "нужен systemd (Ubuntu 22.04+/Debian 12+)"
  command -v apt-get >/dev/null || die "поддерживаются Debian и Ubuntu"
  say "Устанавливаю зависимости"
  apt_install curl ca-certificates openssl jq qrencode bsdextrautils
  install_docker
fi
for c in docker curl jq openssl; do command -v "$c" >/dev/null || die "не найдена программа $c"; done

if [ -z "$PANEL_IP" ]; then
  PANEL_IP="$(curl -4 -fsS --max-time 10 https://api.ipify.org || curl -4 -fsS --max-time 10 https://ifconfig.me || true)"
  [ -n "$PANEL_IP" ] || die "не удалось узнать внешний IP сервера"
fi
if [ "$CADDY_TLS" != "internal" ]; then
  for d in "$PANEL_DOMAIN" "$SUB_DOMAIN"; do
    ips="$(getent ahostsv4 "$d" 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')"
    case " $ips " in
      *" $PANEL_IP "*) ;;
      *) warn "$d указывает на «${ips:-никуда}», а у сервера IP $PANEL_IP. Сертификат HTTPS не выдадут, пока DNS не исправлен (обычно 5–30 минут после изменения). Если домен за Cloudflare, выключите для него проксирование (серое облако)." ;;
    esac
  done
fi

# ---------------------------------------------------------------- REALITY site
# REALITY needs a real foreign site with TLS 1.3 and HTTP/2 to imitate.
check_sni() {
  local out
  out="$(timeout 15 openssl s_client -connect "$1:443" -servername "$1" -tls1_3 -alpn h2 </dev/null 2>/dev/null)" || return 1
  grep -q 'TLSv1.3' <<<"$out" && grep -q 'ALPN protocol: h2' <<<"$out"
}
# A custom REALITY_TARGET (the local test's own TLS server) skips the check.
if [ -z "$REALITY_TARGET" ]; then
  if [ -z "$REALITY_SNI" ]; then
    say "Подбираю сайт для маскировки"
    for c in $SNI_CANDIDATES; do
      if check_sni "$c"; then REALITY_SNI="$c"; break; fi
    done
    [ -n "$REALITY_SNI" ] || die "ни один сайт из списка не подошёл; задайте REALITY_SNI=сайт вручную"
  elif [ "$REALITY_SNI" != "$SAVED_SNI" ]; then
    check_sni "$REALITY_SNI" || die "сайт $REALITY_SNI не подходит для маскировки (нужны TLS 1.3 и HTTP/2)"
  fi
fi
[ -n "$REALITY_SNI" ] || die "задайте REALITY_SNI"
say "Маскировка под: $REALITY_SNI"

# ---------------------------------------------------------------- files
mkdir -p "$RW_DIR"
chmod 700 "$RW_DIR"
umask 077

# Replace a file only when its content changes; keep the old one as a copy.
put_file() {
  local dst="$1" tmp="$1.next"
  cat > "$tmp"
  if [ -f "$dst" ] && cmp -s "$dst" "$tmp"; then rm -f "$tmp"; return; fi
  if [ -f "$dst" ]; then cp -p "$dst" "$dst.bak-$STAMP"; fi
  mv "$tmp" "$dst"
}
env_get() {
  [ -f "$2" ] || return 0
  sed -n "s/^$1=//p" "$2" | tail -n 1 | sed -e 's/^"\(.*\)"$/\1/'
}
rand_alnum() { local r; r="$(openssl rand -base64 96 | tr -dc 'A-Za-z0-9')"; printf '%s' "${r:0:$1}"; }

# Secrets survive re-runs (and are taken over from an adopted setup): a new
# APP_SECRET would log everybody out, a new DB password would lock the
# panel out of its own database.
OLD_ENV="$RW_DIR/.env"
APP_SECRET="$(env_get APP_SECRET "$OLD_ENV")"
POSTGRES_USER="$(env_get POSTGRES_USER "$OLD_ENV")"
POSTGRES_PASSWORD="$(env_get POSTGRES_PASSWORD "$OLD_ENV")"
POSTGRES_DB="$(env_get POSTGRES_DB "$OLD_ENV")"
METRICS_PASS="$(env_get METRICS_PASS "$OLD_ENV")"
{ [ -z "$APP_SECRET" ] || [ "$APP_SECRET" = "change_me" ]; } && APP_SECRET="$(openssl rand -hex 64)"
[ -n "$POSTGRES_USER" ] || POSTGRES_USER=postgres
[ -n "$POSTGRES_DB" ] || POSTGRES_DB=postgres
if [ -z "$POSTGRES_PASSWORD" ]; then
  # Postgres keeps the password of an existing database: a new one would
  # lock the panel out of it.
  docker volume inspect remnawave-db-data >/dev/null 2>&1 &&
    die "на сервере осталась база прежней установки Remnawave (том remnawave-db-data), а пароля от неё нет. Верните прежний $RW_DIR/.env или удалите базу: docker volume rm remnawave-db-data"
  POSTGRES_PASSWORD="$(openssl rand -hex 24)"
fi
{ [ -z "$METRICS_PASS" ] || [ "$METRICS_PASS" = "admin" ]; } && METRICS_PASS="$(openssl rand -hex 32)"

if [ "$ADOPT" = "1" ]; then say "Беру под управление существующую установку (прежние файлы: *.bak-$STAMP)"; fi

say "Записываю настройки в $RW_DIR"
put_file "$RW_DIR/.env" <<EOF
# Written by Klaus VPN install-panel.sh; re-running the script rewrites it.
APP_PORT=3000
METRICS_PORT=3001
API_INSTANCES=1
DATABASE_URL="postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@remnawave-db:5432/$POSTGRES_DB"
REDIS_SOCKET=/var/run/valkey/valkey.sock
APP_SECRET=$APP_SECRET
IS_TELEGRAM_NOTIFICATIONS_ENABLED=false
PANEL_DOMAIN=$PANEL_DOMAIN
FRONT_END_DOMAIN=$PANEL_DOMAIN
SUB_PUBLIC_DOMAIN=$SUB_DOMAIN
METRICS_USER=admin
METRICS_PASS=$METRICS_PASS
WEBHOOK_ENABLED=false
SHORT_UUID_METHOD=nanoid
SHORT_UUID_LENGTH=16
POSTGRES_USER=$POSTGRES_USER
POSTGRES_PASSWORD=$POSTGRES_PASSWORD
POSTGRES_DB=$POSTGRES_DB
EOF

# backend, db and valkey follow remnawave/backend docker-compose-prod.yml
# (3.4.x); the subscription page and Caddy are added. Only Caddy is
# reachable from the internet.
put_file "$RW_DIR/docker-compose.yml" <<'EOF'
# Written by Klaus VPN install-panel.sh; re-running the script rewrites it.
name: remnawave

x-common: &common
  ulimits:
    nofile:
      soft: 1048576
      hard: 1048576
  restart: always
  networks:
    - remnawave-network

x-logging: &logging
  logging:
    driver: json-file
    options:
      max-size: 100m
      max-file: 5

x-env: &env
  env_file: .env

services:
  remnawave:
    image: remnawave/backend:3
    container_name: remnawave
    hostname: remnawave
    <<: [*common, *logging, *env]
    volumes:
      - valkey-socket:/var/run/valkey
    ports:
      - 127.0.0.1:3000:${APP_PORT:-3000}
      - 127.0.0.1:3001:${METRICS_PORT:-3001}
    healthcheck:
      test: ['CMD-SHELL', 'curl -f http://localhost:${METRICS_PORT:-3001}/health']
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 30s
    depends_on:
      remnawave-db:
        condition: service_healthy
      remnawave-redis:
        condition: service_healthy

  remnawave-db:
    image: postgres:18.4
    container_name: remnawave-db
    hostname: remnawave-db
    shm_size: 512mb
    <<: [*common, *logging, *env]
    environment:
      - POSTGRES_USER=${POSTGRES_USER}
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
      - POSTGRES_DB=${POSTGRES_DB}
      - TZ=UTC # DO NOT CHANGE DATABASE TIMEZONE. IT MUST BE UTC.
    ports:
      - 127.0.0.1:6767:5432
    volumes:
      - remnawave-db-data:/var/lib/postgresql
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}']
      interval: 3s
      timeout: 10s
      retries: 3

  remnawave-redis:
    image: valkey/valkey:9-alpine
    container_name: remnawave-redis
    hostname: remnawave-redis
    <<: [*common, *logging]
    volumes:
      - valkey-socket:/var/run/valkey
    command: >
      valkey-server
      --save ""
      --appendonly no
      --maxmemory-policy noeviction
      --loglevel warning
      --unixsocket /var/run/valkey/valkey.sock
      --unixsocketperm 777
      --port 0
    healthcheck:
      test: ['CMD', 'valkey-cli', '-s', '/var/run/valkey/valkey.sock', 'ping']
      interval: 3s
      timeout: 3s
      retries: 3

  remnawave-subscription-page:
    image: remnawave/subscription-page:latest
    container_name: remnawave-subscription-page
    hostname: remnawave-subscription-page
    <<: [*common, *logging]
    env_file: subscription.env
    ports:
      - 127.0.0.1:3010:3010
    depends_on:
      - remnawave

  caddy:
    image: caddy:2
    container_name: caddy
    hostname: caddy
    <<: [*common, *logging]
    ports:
      - 0.0.0.0:80:80
      - 0.0.0.0:443:443
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
    depends_on:
      - remnawave
      - remnawave-subscription-page

networks:
  remnawave-network:
    name: remnawave-network
    driver: bridge
    external: false

volumes:
  remnawave-db-data:
    name: remnawave-db-data
    driver: local
    external: false
  valkey-socket:
    name: valkey-socket
    driver: local
    external: false
  caddy-data:
    name: remnawave-caddy-data
  caddy-config:
    name: remnawave-caddy-config
EOF

tls_line=""
[ "$CADDY_TLS" = "internal" ] && tls_line="	tls internal"
# Caddy obtains and renews the certificates itself and adds the
# X-Forwarded-For/-Proto headers the panel insists on. Connections for any
# other name (IP scanners) are refused during the TLS handshake.
put_file "$RW_DIR/Caddyfile" <<EOF
# Written by Klaus VPN install-panel.sh; re-running the script rewrites it.
https://$PANEL_DOMAIN {
$tls_line
	encode
	reverse_proxy remnawave:3000
}

https://$SUB_DOMAIN {
$tls_line
	encode
	reverse_proxy remnawave-subscription-page:3010
}

:443 {
	tls internal
	respond 204
}
EOF
chmod 644 "$RW_DIR/Caddyfile"

# The subscription page needs an API token to start; until the panel has
# issued one it stays stopped (the file is completed below).
[ -f "$RW_DIR/subscription.env" ] || : > "$RW_DIR/subscription.env"

# Containers left from a setup made by hand (e.g. the Caddy example from the
# Remnawave docs) would block ours by name.
for c in caddy remnawave-subscription-page; do
  project="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$c" 2>/dev/null || true)"
  if docker inspect "$c" >/dev/null 2>&1 && [ "$project" != "remnawave" ]; then
    [ "$FORCE" = "1" ] || die "уже есть контейнер $c не из этой установки; FORCE=1 заменит его"
    docker rm -f "$c" >/dev/null
  fi
done

# ---------------------------------------------------------------- start
cd "$RW_DIR"
# docker compose, quiet unless it fails.
compose() {
  local log
  log="$(mktemp)"
  if ! docker compose "$@" >"$log" 2>&1; then
    tail -n 30 "$log" >&2
    rm -f "$log"
    return 1
  fi
  rm -f "$log"
}
if [ "$SKIP_SYSTEM" != "1" ]; then
  say "Скачиваю/обновляю образы Remnawave"
  compose pull || die "не удалось скачать образы (Docker Hub недоступен?). Повторите через пару минут"
fi
if [ -n "$RESTORE_DIR" ]; then
  say "Восстанавливаю базу из $RESTORE"
  compose up -d --remove-orphans remnawave-db || die "база не запустилась (подробности выше)"
  for i in $(seq 1 60); do
    docker exec remnawave-db pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 && break
    [ "$i" -eq 60 ] && die "база не запустилась"
    sleep 2
  done
  docker exec -i remnawave-db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error \
    < "$RESTORE_DIR/remnawave-db.dump" || die "не удалось восстановить базу"
  # Certificates are optional: Caddy would get new ones.
  if [ -f "$RESTORE_DIR/caddy-data.tar" ]; then
    docker run --rm -i -v remnawave-caddy-data:/data caddy:2 tar -C /data -xf - < "$RESTORE_DIR/caddy-data.tar" >/dev/null 2>&1 ||
      warn "сертификаты не восстановлены, Caddy получит новые"
  fi
fi
say "Запускаю панель (пока она доступна только с самого сервера)"
compose up -d --remove-orphans remnawave-db remnawave-redis remnawave || die "контейнеры панели не запустились (подробности выше)"

# All local calls must look like they came through the HTTPS proxy, and an
# admin login is only accepted from the panel's own web page (its client
# type header). The token and body go through files, so they never show up
# in "ps".
api() { # METHOD PATH [BODY] [TOKEN]; sets RESP and HTTP_CODE
  local tmp data=()
  tmp="$(mktemp -d)"
  printf 'X-Forwarded-For: 127.0.0.1\nX-Forwarded-Proto: https\nX-Remnawave-Client-Type: browser\nContent-Type: application/json\n' > "$tmp/h"
  if [ -n "${4:-}" ]; then printf 'Authorization: Bearer %s\n' "$4" >> "$tmp/h"; fi
  if [ -n "${3:-}" ]; then printf '%s' "$3" > "$tmp/b"; data=(--data-binary "@$tmp/b"); fi
  HTTP_CODE="$(curl -sS --noproxy '*' --max-time 60 -o "$tmp/o" -w '%{http_code}' -X "$1" \
    -H "@$tmp/h" "${data[@]}" "$API_URL$2" 2>/dev/null)" || HTTP_CODE=000
  RESP="$(cat "$tmp/o" 2>/dev/null || true)"
  rm -rf "$tmp"
}

say "Жду, пока панель подготовит базу (первый запуск — до пары минут)"
for i in $(seq 1 120); do
  api GET /api/auth/status
  [ "$HTTP_CODE" = "200" ] && break
  [ "$i" -eq 120 ] && { docker compose logs --tail 40 remnawave >&2 || true; die "панель не запустилась (журнал выше)"; }
  sleep 3
done

# ---------------------------------------------------------------- admin
# The first account becomes the super-admin. It is created here, before
# Caddy opens the panel to the internet, so no visitor can take it first.
ADMIN_JWT=""
save_admin() { # USER PASSWORD
  mkdir -p "$(dirname "$ADMIN_FILE")"
  (umask 077 && printf 'Панель Klaus VPN (Remnawave): https://%s\nЛогин: %s\nПароль: %s\n' "$PANEL_DOMAIN" "$1" "$2" > "$ADMIN_FILE")
  chmod 600 "$ADMIN_FILE"
}
if [ "$(jq -r '.response.isRegisterAllowed' <<<"$RESP")" = "true" ]; then
  say "Создаю администратора панели"
  ADMIN_PASSWORD=""
  # The panel wants 24+ characters with upper and lower case and digits.
  until [[ "$ADMIN_PASSWORD" =~ [A-Z] && "$ADMIN_PASSWORD" =~ [a-z] && "$ADMIN_PASSWORD" =~ [0-9] ]]; do
    ADMIN_PASSWORD="$(rand_alnum 32)"
  done
  api POST /api/auth/register "$(jq -nc --arg u "$ADMIN_USER" --arg p "$ADMIN_PASSWORD" '{username: $u, password: $p}')"
  [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "200" ] || die "панель не создала администратора: ${RESP:0:300}"
  ADMIN_JWT="$(jq -r '.response.accessToken' <<<"$RESP")"
  save_admin "$ADMIN_USER" "$ADMIN_PASSWORD"
fi

# The CLI token (full access) and the subscription page token (read-only
# access to what the page needs) are kept between runs while they work.
token_ok() { [ -n "$1" ] || return 1; api GET "$2" "" "$1"; [ "$HTTP_CODE" = "200" ]; }
admin_jwt() {
  [ -n "$ADMIN_JWT" ] && return
  local u p
  u="$(sed -n 's/^Логин: //p' "$ADMIN_FILE" 2>/dev/null || true)"
  p="${ADMIN_PASSWORD:-$(sed -n 's/^Пароль: //p' "$ADMIN_FILE" 2>/dev/null || true)}"
  [ -n "$u" ] || u="$ADMIN_USER"
  [ -n "$p" ] || die "администратор панели уже создан, а пароля от него нет в $ADMIN_FILE. Запустите с ADMIN_USER=… ADMIN_PASSWORD=…"
  api POST /api/auth/login "$(jq -nc --arg u "$u" --arg p "$p" '{username: $u, password: $p}')"
  [ "$HTTP_CODE" = "200" ] || [ "$HTTP_CODE" = "201" ] || die "не удалось войти в панель как $u: ${RESP:0:300}"
  ADMIN_JWT="$(jq -r '.response.accessToken' <<<"$RESP")"
  if [ ! -f "$ADMIN_FILE" ]; then save_admin "$u" "$p"; fi
}
# Tokens can only be made with an admin login; they are kept between runs
# while they work. 3650 days: an expired token stops the subscription page
# at its next restart.
new_token() { # PREFIX SCOPES_JSON; sets TOKEN, deletes older PREFIX-* tokens
  local name="$1-$STAMP" old
  admin_jwt
  api POST /api/tokens "$(jq -nc --arg n "$name" --argjson s "$2" '{name: $n, expiresInDays: 3650, scopes: $s}')" "$ADMIN_JWT"
  [ "$HTTP_CODE" = "201" ] || [ "$HTTP_CODE" = "200" ] || die "панель не выдала API-токен: ${RESP:0:300}"
  TOKEN="$(jq -r '.response.token' <<<"$RESP")"
  api GET /api/tokens "" "$ADMIN_JWT"
  [ "$HTTP_CODE" = "200" ] || return 0
  for old in $(jq -r --arg p "$1-" --arg n "$name" '.response.tokens[] | select((.name | startswith($p)) and .name != $n) | .uuid' <<<"$RESP"); do
    api DELETE "/api/tokens/$old" "" "$ADMIN_JWT"
  done
}

if ! token_ok "$API_TOKEN" /api/config-profiles; then
  say "Создаю API-токен для klaus-panel"
  new_token klaus-panel '["*"]'
  API_TOKEN="$TOKEN"
fi
# The subscription page is the part exposed to everyone, so its token can
# only read what the page shows.
SUBPAGE_TOKEN="$(env_get REMNAWAVE_API_TOKEN "$RW_DIR/subscription.env")"
if ! token_ok "$SUBPAGE_TOKEN" /api/system/metadata || ! token_ok "$SUBPAGE_TOKEN" /api/subscription-page-configs; then
  say "Создаю API-токен для страницы подписки"
  new_token subpage "$SUBPAGE_SCOPES"
  SUBPAGE_TOKEN="$TOKEN"
fi

put_file "$RW_DIR/subscription.env" <<EOF
# Written by Klaus VPN install-panel.sh; re-running the script rewrites it.
APP_PORT=3010
REMNAWAVE_PANEL_URL=http://remnawave:3000
REMNAWAVE_API_TOKEN=$SUBPAGE_TOKEN
TRUST_PROXY=1
EOF

q() { printf '%q' "$1"; }
put_file "$CONF" <<EOF
# klaus-panel settings, written by install-panel.sh. Keep it secret: the
# token gives full control over the panel.
RW_DIR=$(q "$RW_DIR")
API_URL=$(q "$API_URL")
API_TOKEN=$(q "$API_TOKEN")
PANEL_DOMAIN=$(q "$PANEL_DOMAIN")
SUB_DOMAIN=$(q "$SUB_DOMAIN")
PANEL_IP=$(q "$PANEL_IP")
ADMIN_FILE=$(q "$ADMIN_FILE")
APK_URL=$(q "$APK_URL")
SUPPORT_URL=$(q "$SUPPORT_URL")
REALITY_SNI=$(q "$REALITY_SNI")
REALITY_TARGET=$(q "$REALITY_TARGET")
REALITY_PORT=$(q "$REALITY_PORT")
EOF

KP="$HERE/klaus-panel"
if [ "$SKIP_SYSTEM" != "1" ]; then
  install -m 755 "$HERE/klaus-panel" /usr/local/bin/klaus-panel
  KP=/usr/local/bin/klaus-panel
fi

say "Настраиваю профиль VLESS + REALITY, подписки и страницу подписки"
KLAUS_PANEL_CONF="$CONF" bash "$KP" setup

say "Открываю панель и страницу подписки в интернет (Caddy, HTTPS)"
compose up -d --remove-orphans || die "контейнеры не запустились (подробности выше)"
for i in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' remnawave-subscription-page 2>/dev/null)" = "healthy" ] && break
  [ "$i" -eq 60 ] && { docker compose logs --tail 40 remnawave-subscription-page >&2 || true; die "страница подписки не запустилась (журнал выше)"; }
  sleep 3
done

if [ "$SKIP_SYSTEM" != "1" ]; then
  if command -v ufw >/dev/null && grep -q "Status: active" <<<"$(ufw status)"; then
    say "Открываю порты 80 и 443 в ufw"
    ufw allow 80/tcp >/dev/null
    ufw allow 443/tcp >/dev/null
  fi
  if [ "$CADDY_TLS" != "internal" ]; then
    say "Жду сертификаты HTTPS"
    for d in "$PANEL_DOMAIN" "$SUB_DOMAIN"; do
      ok=0
      for _ in $(seq 1 30); do
        if openssl s_client -connect 127.0.0.1:443 -servername "$d" -verify_return_error </dev/null >/dev/null 2>&1; then
          ok=1; break
        fi
        sleep 4
      done
      [ "$ok" = "1" ] || warn "сертификат для $d пока не получен. Проверьте, что домен указывает на $PANEL_IP и порт 80 открыт; журнал: docker logs caddy"
    done
  fi
fi

echo
say "Готово! Панель работает."
echo
echo "Панель:            https://$PANEL_DOMAIN  (логин и пароль: $ADMIN_FILE)"
echo "Страница подписки: https://$SUB_DOMAIN"
echo
echo "Дальше:"
echo "  1. Купите VPS для VPN-сервера и выполните:  klaus-panel add-node Имя IP-адрес DE"
echo "     команда покажет, что запустить на этом VPS;"
echo "  2. Для каждого знакомого:  klaus-panel add-user Имя"
echo "  3. Резервная копия:  klaus-panel backup"
echo
echo "Все команды: klaus-panel help"
if [ -n "$RESTORE_DIR" ]; then
  echo
  warn "панель переехала на новый IP. Направьте оба домена на $PANEL_IP и на каждом VPN-сервере выполните: sudo PANEL_IP=$PANEL_IP bash install-node.sh"
fi
