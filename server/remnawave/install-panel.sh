#!/usr/bin/env bash
# Kirov VPN panel installer: Remnawave panel + subscription page behind Caddy
# (automatic HTTPS), for handing out personal subscription links to friends.
#
# Run as root on a fresh Ubuntu 22.04+/Debian 12+ VPS abroad (2 CPU, 4 GB).
# Both domains must already point at this server. Copy this file,
# klaus-panel, klaus-monitor.py and klaus-page.html into one folder, then:
#
#   sudo PANEL_DOMAIN=panel.example.com SUB_DOMAIN=sub.example.com bash install-panel.sh
#
# Options (environment variables, written AFTER sudo):
#   PANEL_DOMAIN   admin panel address (required)
#   SUB_DOMAIN     subscription page address given to friends (required)
#   APK_URL        https link to the Kirov VPN APK (adds a download button;
#                  without it the button appears once "klaus-panel
#                  publish-apk" has put the app on https://SUB_DOMAIN/app/)
#   GITHUB_TOKEN   read-only GitHub token, needed only while the repository
#                  is private: the panel publishes every new stable build of
#                  the app by itself (hourly)
#   GITHUB_REPO    repository with the releases (default klausms17/vpn)
#   RELEASE_TAG    release whose APK is published (default stable: the build
#                  CI makes on purpose from a v* tag or a manual run; the
#                  build-<branch> test builds come with every push)
#   PAGE_BRANCH    branch of GITHUB_REPO whose klaus-page.html friends'
#                  browsers get (default main): the panel takes a new one
#                  from there by itself every 15 minutes; PAGE_BRANCH=
#                  (empty) keeps the page from this folder
#   REPORT_THRESHOLD, REPORT_WINDOW_MIN, REPORT_COOLDOWN_MIN
#                  Telegram alert when this many different friends' apps
#                  (default 2) reported the same server within this many
#                  minutes (default 20); then quiet for (default 180) minutes
#   SUPPORT_URL    https link friends can use to reach you (e.g. https://t.me/you);
#                  without it the page's support button only says to ask you
#   ADMIN_USER     panel login (default admin); the password is generated
#   REALITY_SNI    site the VPN imitates (default: first working from a list)
#   FORCE=1        take over an existing Remnawave setup not made by this script
#   RESTORE=file   move the panel to this (new) server from a backup made by
#                  "klaus-panel backup": same users, links and servers. If it
#                  stops halfway, run the same command again
#   ALLOW_RU_DOMAIN=1  try a .ru/.su/.рф domain anyway (certificates for them
#                  are refused, so HTTPS will most likely not work)
#
# Re-running keeps all keys, users and passwords; it updates the containers
# and re-applies the settings (the previous files are kept as *.bak-*).
# Variables given again (a new domain, SUPPORT_URL=… or SUPPORT_URL= to
# remove it) replace the saved ones.
#
# Telegram alerts are switched on afterwards: klaus-panel telegram-setup.
#
# For the local test harness only (server/remnawave/test): RW_DIR, ADMIN_FILE,
# SKIP_SYSTEM=1 (no apt/Docker/firewall/sysctl/systemd changes),
# CADDY_TLS=internal, REALITY_TARGET=host:port, REALITY_PORT, GITHUB_API,
# TELEGRAM_API_BASE (mock APIs).
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
# The block report monitor: is this a real subscription, which servers are
# there and are they connected. Never users:list: the list holds every
# friend's keys, and the monitor faces the internet.
MONITOR_SCOPES='["users:by-short-uuid", "hosts:list", "nodes:list"]'
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mВнимание:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "запустите от root (sudo … bash install-panel.sh)"
for f in klaus-panel klaus-monitor.py klaus-page.html; do
  [ -f "$HERE/$f" ] || die "рядом со скриптом нет файла $f: скопируйте install-panel.sh, klaus-panel, klaus-monitor.py и klaus-page.html в одну папку"
done

# ---------------------------------------------------------------- restore
# A backup brings back the settings files here and the database below.
# Friends' links stay the same once the domains point at this server.
RESTORE="${RESTORE:-}"
RESTORE_DIR=""
# While a restore is unfinished this file says how far it got: "files" (the
# settings are back, the database is not) or "db" (both are back). A restore
# that stopped can then simply be run again (it starts over), and a run
# without RESTORE refuses to build an empty panel over a missing or
# half-restored database. Removed once all went well.
RESTORE_MARK="$RW_DIR/.restore-unfinished"
RESTORE_STAGE="$(cat "$RESTORE_MARK" 2>/dev/null || true)"
db_volume() { docker volume inspect remnawave-db-data >/dev/null 2>&1; }
env_secret() { sed -n 's/^APP_SECRET=//p' "$1" 2>/dev/null | tail -n 1; }
if [ -n "$RESTORE" ]; then
  [ -f "$RESTORE" ] || die "нет файла $RESTORE"
  # A stopped Docker would hide an existing database from the checks below.
  if command -v docker >/dev/null && ! docker info >/dev/null 2>&1; then
    die "Docker установлен, но не отвечает. Запустите его (systemctl restart docker) и повторите"
  fi
  RESTORE_DIR="$(mktemp -d)"
  trap 'rm -rf "$RESTORE_DIR"' EXIT
  tar -C "$RESTORE_DIR" -xzf "$RESTORE" || die "не удалось распаковать $RESTORE"
  for f in remnawave-db.dump .env klaus-panel.env; do
    [ -f "$RESTORE_DIR/$f" ] || die "в $RESTORE нет $f: это не резервная копия klaus-panel"
  done
  # Only onto a new server, or over a restore of this same panel that
  # stopped halfway (its settings are already here; its database, if any,
  # is restored again).
  same=0
  if [ -f "$RW_DIR/.env" ] && [ "$(env_secret "$RW_DIR/.env")" = "$(env_secret "$RESTORE_DIR/.env")" ]; then same=1; fi
  if db_volume; then
    { [ "$same" = "1" ] && [ -n "$RESTORE_STAGE" ]; } ||
      die "восстанавливать можно только на новый сервер: здесь уже есть панель (база remnawave-db-data)"
  elif [ -f "$RW_DIR/.env" ] && [ "$same" != "1" ] && [ -z "$RESTORE_STAGE" ]; then
    die "в $RW_DIR уже есть настройки другой панели (её базы здесь нет). Если они не нужны, удалите $RW_DIR и повторите"
  fi
  mkdir -p "$RW_DIR"
  chmod 700 "$RW_DIR"
  RESTORE_STAGE=files
  echo "$RESTORE_STAGE" > "$RESTORE_MARK"
  for f in .env subscription.env klaus-panel.env; do
    if [ -f "$RESTORE_DIR/$f" ]; then install -m 600 "$RESTORE_DIR/$f" "$RW_DIR/$f"; fi
  done
  if [ -f "$RESTORE_DIR/admin.txt" ] && [ ! -f "$ADMIN_FILE" ]; then install -m 600 "$RESTORE_DIR/admin.txt" "$ADMIN_FILE"; fi
  # The published app (klaus-panel publish-apk), so friends' update check
  # and the download button work right away.
  if [ -d "$RESTORE_DIR/app" ]; then
    mkdir -p "$RW_DIR/app"
    cp -a "$RESTORE_DIR/app/." "$RW_DIR/app/"
  fi
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
MONITOR_TOKEN="$(conf_get MONITOR_TOKEN)"
# Set by "klaus-panel telegram-setup".
TELEGRAM_BOT_TOKEN="$(conf_get TELEGRAM_BOT_TOKEN)"
TELEGRAM_CHAT_ID="$(conf_get TELEGRAM_CHAT_ID)"
TELEGRAM_API_BASE="${TELEGRAM_API_BASE:-$(conf_get TELEGRAM_API_BASE)}"
TELEGRAM_API_BASE="${TELEGRAM_API_BASE:-https://api.telegram.org}"
GITHUB_TOKEN="${GITHUB_TOKEN-$(conf_get GITHUB_TOKEN)}"
GITHUB_REPO="${GITHUB_REPO:-$(conf_get GITHUB_REPO)}"
GITHUB_REPO="${GITHUB_REPO:-klausms17/vpn}"
# Panels set up before the stable release existed saved the old default,
# the work branch's test builds (every push, never tried on a phone), as if
# it had been chosen. They move to stable once; STABLE_DEFAULT=1 says that
# is done (or was never needed), so a branch chosen later on purpose stays.
OLD_DEFAULT_TAG=build-claude-compassionate-mayer-6jph8m
MOVED_TO_STABLE=0
if [ -z "${RELEASE_TAG:-}" ] && [ -z "$(conf_get STABLE_DEFAULT)" ] &&
  [ "$(conf_get RELEASE_TAG)" = "$OLD_DEFAULT_TAG" ]; then
  RELEASE_TAG=stable
  MOVED_TO_STABLE=1
fi
RELEASE_TAG="${RELEASE_TAG:-$(conf_get RELEASE_TAG)}"
RELEASE_TAG="${RELEASE_TAG:-stable}"
GITHUB_API="${GITHUB_API:-$(conf_get GITHUB_API)}"
GITHUB_API="${GITHUB_API:-https://api.github.com}"
# Saved empty, it stays empty: the page from this folder is kept.
if [ -z "${PAGE_BRANCH+x}" ]; then
  if [ -f "$CONF" ] && grep -q '^PAGE_BRANCH=' "$CONF"; then PAGE_BRANCH="$(conf_get PAGE_BRANCH)"; else PAGE_BRANCH=main; fi
fi
REPORT_THRESHOLD="${REPORT_THRESHOLD:-$(conf_get REPORT_THRESHOLD)}"
REPORT_THRESHOLD="${REPORT_THRESHOLD:-2}"
REPORT_WINDOW_MIN="${REPORT_WINDOW_MIN:-$(conf_get REPORT_WINDOW_MIN)}"
REPORT_WINDOW_MIN="${REPORT_WINDOW_MIN:-20}"
REPORT_COOLDOWN_MIN="${REPORT_COOLDOWN_MIN:-$(conf_get REPORT_COOLDOWN_MIN)}"
REPORT_COOLDOWN_MIN="${REPORT_COOLDOWN_MIN:-180}"

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
for v in REPORT_THRESHOLD REPORT_WINDOW_MIN REPORT_COOLDOWN_MIN; do
  case "${!v}" in *[!0-9]* | "" | 0) die "$v должен быть числом больше нуля" ;; esac
done
[[ "$GITHUB_REPO" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || die "GITHUB_REPO — в виде владелец/репозиторий, например klausms17/vpn"
[[ "$RELEASE_TAG" =~ ^[A-Za-z0-9._/-]+$ ]] || die "странный RELEASE_TAG: $RELEASE_TAG"
[[ -z "$PAGE_BRANCH" || "$PAGE_BRANCH" =~ ^[A-Za-z0-9._/-]+$ ]] || die "странное имя ветки PAGE_BRANCH: $PAGE_BRANCH"
[[ "$GITHUB_TOKEN" =~ ^[A-Za-z0-9_]*$ ]] || die "GITHUB_TOKEN: токен GitHub состоит из латинских букв, цифр и _"
for u in "$GITHUB_API" "$TELEGRAM_API_BASE"; do
  [[ "$u" =~ ^https?://[A-Za-z0-9.:_-]+(/[A-Za-z0-9._/-]*)?$ ]] || die "странный адрес $u"
done
SAVED_SUB="$(norm_domain "$(conf_get SUB_DOMAIN)")"
if [ -n "$SAVED_SUB" ] && [ "$SAVED_SUB" != "$SUB_DOMAIN" ]; then
  warn "адрес подписок меняется: $SAVED_SUB → $SUB_DOMAIN. Ссылки на $SAVED_SUB, которые уже есть у знакомых, перестанут работать: отправьте им новые (klaus-panel link ИМЯ)"
fi

# Never silently take over a Remnawave setup this script did not create:
# FORCE=1 does it, keeping its database and secrets (copies of the files stay).
# Files written before the app was renamed say "Klaus VPN".
ADOPT=0
if [ ! -f "$CONF" ] && [ -f "$RW_DIR/docker-compose.yml" ] &&
  ! grep -Eq "Written by (Kirov|Klaus) VPN install-panel\.sh" "$RW_DIR/docker-compose.yml"; then
  [ "$FORCE" = "1" ] ||
    die "в $RW_DIR уже есть Remnawave, установленный не этим скриптом. Если его нужно перенастроить под Kirov VPN (пользователи и база сохранятся), запустите: sudo FORCE=1 … bash $0"
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
docker info >/dev/null 2>&1 || die "Docker не отвечает. Запустите его (systemctl restart docker) и повторите"

# Settings of a panel whose database is not here: an unfinished move, or
# the database was removed. A fresh empty panel with the old settings would
# look like it works while every friend's link is dead.
if [ -z "$RESTORE" ]; then
  if [ "$RESTORE_STAGE" = "files" ]; then
    die "перенос панели из резервной копии не закончен. Запустите ту же команду с RESTORE=файл-копии ещё раз"
  fi
  if [ -n "$API_TOKEN" ] && ! db_volume; then
    die "в $RW_DIR есть настройки панели, а её базы (том remnawave-db-data) нет. Если это перенос, запустите с RESTORE=файл-копии; если нужна новая пустая панель, сначала удалите $RW_DIR"
  fi
fi

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
# A panel set up by hand may already send its messages to Telegram: keep
# its bot and chat (the placeholders of Remnawave's sample .env are no bot).
if [ "$ADOPT" = "1" ] && [ -z "$TELEGRAM_BOT_TOKEN" ] && [ "$(env_get IS_TELEGRAM_NOTIFICATIONS_ENABLED "$OLD_ENV")" = "true" ]; then
  t="$(env_get TELEGRAM_BOT_TOKEN "$OLD_ENV")"
  c="$(env_get TELEGRAM_NOTIFY_NODES "$OLD_ENV")"
  if [[ "$t" =~ ^[0-9]+:[A-Za-z0-9_-]+$ ]] && [[ "$c" =~ ^-?[0-9]+(:[0-9]+)?$ ]]; then
    TELEGRAM_BOT_TOKEN="$t"
    TELEGRAM_CHAT_ID="$c"
  fi
fi

if [ "$ADOPT" = "1" ]; then say "Беру под управление существующую установку (прежние файлы: *.bak-$STAMP)"; fi

# The panel's own Telegram messages: servers lost and back (the chat and
# bot come from "klaus-panel telegram-setup", which edits these lines in
# place, so they must stay exactly as written here).
TELEGRAM_ENABLED=false
if [ -n "$TELEGRAM_BOT_TOKEN" ] && [ -n "$TELEGRAM_CHAT_ID" ]; then TELEGRAM_ENABLED=true; fi

say "Записываю настройки в $RW_DIR"
put_file "$RW_DIR/.env" <<EOF
# Written by Kirov VPN install-panel.sh; re-running the script rewrites it.
APP_PORT=3000
METRICS_PORT=3001
API_INSTANCES=1
DATABASE_URL="postgresql://$POSTGRES_USER:$POSTGRES_PASSWORD@remnawave-db:5432/$POSTGRES_DB"
REDIS_SOCKET=/var/run/valkey/valkey.sock
APP_SECRET=$APP_SECRET
IS_TELEGRAM_NOTIFICATIONS_ENABLED=$TELEGRAM_ENABLED
TELEGRAM_BOT_TOKEN=$TELEGRAM_BOT_TOKEN
TELEGRAM_BOT_API_ROOT=$TELEGRAM_API_BASE
TELEGRAM_NOTIFY_NODES=$TELEGRAM_CHAT_ID
PANEL_DOMAIN=$PANEL_DOMAIN
FRONT_END_DOMAIN=$PANEL_DOMAIN
SUB_PUBLIC_DOMAIN=$SUB_DOMAIN
METRICS_USER=admin
METRICS_PASS=$METRICS_PASS
WEBHOOK_ENABLED=false
# Privacy: no history of subscription downloads (who, when, which app) and
# no traffic of each friend by day; the totals and the last time online stay,
# the panel works with them. No request log either.
SERVICE_DISABLE_SRH_RECORDS=true
SERVICE_DISABLE_USER_USAGE_RECORDS=true
IS_HTTP_LOGGING_ENABLED=false
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
# Written by Kirov VPN install-panel.sh; re-running the script rewrites it.
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

  # It logs each request with the friend's personal link and has no switch
  # for that: no log at all.
  remnawave-subscription-page:
    image: remnawave/subscription-page:latest
    container_name: remnawave-subscription-page
    hostname: remnawave-subscription-page
    <<: *common
    logging:
      driver: none
    env_file: subscription.env
    ports:
      - 127.0.0.1:3010:3010
    depends_on:
      - remnawave

  # Block reports from friends' apps -> Telegram (klaus-monitor.py). Only
  # Caddy reaches it (/klaus/ on the subscription address).
  klaus-monitor:
    image: python:3-alpine
    container_name: klaus-monitor
    hostname: klaus-monitor
    <<: [*common, *logging]
    env_file: klaus-monitor.env
    environment:
      - PYTHONDONTWRITEBYTECODE=1
    command: ['python3', '-u', '/app/klaus-monitor.py']
    volumes:
      - ./klaus-monitor.py:/app/klaus-monitor.py:ro
    user: '65534:65534'
    read_only: true
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    healthcheck:
      test: ['CMD', 'python3', '-c', 'import urllib.request; urllib.request.urlopen("http://127.0.0.1:8080/klaus/health", timeout=3)']
      interval: 30s
      timeout: 5s
      retries: 3

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
      - ./app:/srv/app:ro
      - ./page:/srv/page:ro
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
# other name (IP scanners) are refused during the TLS handshake. The bare
# subscription address is where the page's support button leads when there
# is no SUPPORT_URL (the page itself only drops such requests). /app/ is the
# app published by "klaus-panel publish-apk", /klaus/ the block reports.
# A browser opening a friend's link gets Kirov VPN's own page (/srv/page,
# from klaus-page.html); the apps get their subscription from Remnawave.
# No access log, and the error log (a request that failed, e.g. while the
# page restarts) keeps neither the address nor the link of the friend.
# Nor does anything behind Caddy learn the address: the page, the panel it
# asks (which would store it with the friend's phone) and the monitor all
# get 127.0.0.1.
put_file "$RW_DIR/Caddyfile" <<EOF
# Written by Kirov VPN install-panel.sh; re-running the script rewrites it.
{
	log default {
		format filter {
			wrap json
			fields {
				request>remote_ip delete
				request>remote_port delete
				request>client_ip delete
				request>uri delete
				request>headers delete
			}
		}
	}
}

https://$PANEL_DOMAIN {
$tls_line
	encode
	reverse_proxy remnawave:3000
}

https://$SUB_DOMAIN {
$tls_line
	encode
	@page header Accept *text/html*
	handle / {
		header Content-Type "text/plain; charset=utf-8"
		respond "Kirov VPN: с вопросами обращайтесь к тому, кто дал вам ссылку на подписку." 200
	}
	handle /klaus/* {
		reverse_proxy klaus-monitor:8080 {
			header_up X-Forwarded-For 127.0.0.1
		}
	}
	handle_path /app/* {
		root * /srv/app
		header Cache-Control "no-cache"
		# An app may still offer a build that was replaced long ago, or a
		# file of the app's former name (KlausVPN): its link leads to the
		# current one.
		@gone {
			path /KirovVPN-*.apk /KlausVPN*.apk
			not file
		}
		redir @gone /app/KirovVPN.apk 302
		@apk path *.apk
		header @apk Content-Type "application/vnd.android.package-archive"
		file_server
	}
	handle {
		handle @page {
			root * /srv/page
			rewrite * /index.html
			header Cache-Control "no-cache"
			file_server
		}
		handle {
			reverse_proxy remnawave-subscription-page:3010 {
				header_up X-Forwarded-For 127.0.0.1
			}
		}
	}
}

:443 {
	tls internal
	respond 204
}
EOF
chmod 644 "$RW_DIR/Caddyfile"

# The subscription page needs an API token to start; until the panel has
# issued one it stays stopped (the file is completed below). The same for
# the monitor ("klaus-panel setup" writes its settings).
[ -f "$RW_DIR/subscription.env" ] || : > "$RW_DIR/subscription.env"
[ -f "$RW_DIR/klaus-monitor.env" ] || : > "$RW_DIR/klaus-monitor.env"
put_file "$RW_DIR/klaus-monitor.py" < "$HERE/klaus-monitor.py"
# Read by the monitor's unprivileged user.
chmod 644 "$RW_DIR/klaus-monitor.py"
# Kirov VPN's page for friends' browsers (klaus-panel writes it below) and
# the app published by "klaus-panel publish-apk". Earlier runs kept copies
# of the page next to it.
mkdir -p "$RW_DIR/page" "$RW_DIR/app"
rm -f "$RW_DIR/page/index.html.bak-"* "$RW_DIR/page/index.html.next"

# Containers left from a setup made by hand (e.g. the Caddy example from the
# Remnawave docs) would block ours by name.
for c in caddy remnawave-subscription-page klaus-monitor; do
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
# The panel of an earlier try goes too: it would keep running against the
# database removed under it.
drop_db() { docker rm -f remnawave remnawave-db >/dev/null 2>&1 || true; docker volume rm remnawave-db-data >/dev/null 2>&1 || true; }
# A half-restored database would block the next try: remove it, so the same
# command can simply be run again.
restore_failed() { drop_db; die "$1. Запустите ту же команду ещё раз"; }
if [ -n "$RESTORE_DIR" ]; then
  say "Восстанавливаю базу из $RESTORE"
  # A database here was left by a restore that stopped halfway (the check at
  # the top made sure of that): start it over.
  if db_volume; then drop_db; fi
  compose up -d --remove-orphans remnawave-db || restore_failed "база не запустилась (подробности выше)"
  # Over TCP: a new database is first set up by a temporary server that
  # listens only on the Unix socket and is stopped right after.
  for i in $(seq 1 60); do
    docker exec remnawave-db pg_isready -h 127.0.0.1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null 2>&1 && break
    [ "$i" -eq 60 ] && restore_failed "база не запустилась"
    sleep 2
  done
  docker exec -i remnawave-db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner --exit-on-error \
    < "$RESTORE_DIR/remnawave-db.dump" || restore_failed "не удалось восстановить базу"
  RESTORE_STAGE=db
  echo "$RESTORE_STAGE" > "$RESTORE_MARK"
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
  (umask 077 && printf 'Панель Kirov VPN (Remnawave): https://%s\nЛогин: %s\nПароль: %s\n' "$PANEL_DOMAIN" "$1" "$2" > "$ADMIN_FILE")
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
# The monitor faces the internet too: it only checks that a report comes
# from a real subscription and looks up the servers. A token that can list
# the users is replaced.
if ! token_ok "$MONITOR_TOKEN" /api/hosts || ! token_ok "$MONITOR_TOKEN" /api/nodes ||
  token_ok "$MONITOR_TOKEN" "/api/users?start=0&size=1"; then
  say "Создаю API-токен для сигналов о блокировках"
  new_token klaus-monitor "$MONITOR_SCOPES"
  MONITOR_TOKEN="$TOKEN"
fi

put_file "$RW_DIR/subscription.env" <<EOF
# Written by Kirov VPN install-panel.sh; re-running the script rewrites it.
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
MONITOR_TOKEN=$(q "$MONITOR_TOKEN")
TELEGRAM_BOT_TOKEN=$(q "$TELEGRAM_BOT_TOKEN")
TELEGRAM_CHAT_ID=$(q "$TELEGRAM_CHAT_ID")
TELEGRAM_API_BASE=$(q "$TELEGRAM_API_BASE")
REPORT_THRESHOLD=$(q "$REPORT_THRESHOLD")
REPORT_WINDOW_MIN=$(q "$REPORT_WINDOW_MIN")
REPORT_COOLDOWN_MIN=$(q "$REPORT_COOLDOWN_MIN")
GITHUB_TOKEN=$(q "$GITHUB_TOKEN")
GITHUB_REPO=$(q "$GITHUB_REPO")
RELEASE_TAG=$(q "$RELEASE_TAG")
STABLE_DEFAULT=1
PAGE_BRANCH=$(q "$PAGE_BRANCH")
GITHUB_API=$(q "$GITHUB_API")
EOF

KP="$HERE/klaus-panel"
if [ "$SKIP_SYSTEM" != "1" ]; then
  install -m 755 "$HERE/klaus-panel" /usr/local/bin/klaus-panel
  KP=/usr/local/bin/klaus-panel
fi

say "Настраиваю профиль VLESS + REALITY, подписки и страницу подписки"
KLAUS_PANEL_CONF="$CONF" bash "$KP" setup
KLAUS_PANEL_CONF="$CONF" bash "$KP" write-page "$HERE/klaus-page.html"

say "Открываю панель и страницу подписки в интернет (Caddy, HTTPS)"
compose up -d --remove-orphans || die "контейнеры не запустились (подробности выше)"
# Caddy reads its Caddyfile only when it starts, and its container keeps
# seeing the file it started with (the new one replaces it). Restart it when
# the two differ: a new domain, also one written by an earlier run that
# stopped before this point.
if ! docker exec caddy cat /etc/caddy/Caddyfile 2>/dev/null | cmp -s - "$RW_DIR/Caddyfile"; then
  say "Перезапускаю Caddy с новыми настройками"
  docker restart caddy >/dev/null || die "не удалось перезапустить Caddy"
fi
# The same for the monitor's script (a new version of klaus-monitor.py).
if ! docker exec klaus-monitor cat /app/klaus-monitor.py 2>/dev/null | cmp -s - "$RW_DIR/klaus-monitor.py"; then
  say "Перезапускаю монитор блокировок с новой версией"
  docker restart klaus-monitor >/dev/null || die "не удалось перезапустить klaus-monitor"
fi
for i in $(seq 1 60); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' remnawave-subscription-page 2>/dev/null)" = "healthy" ] && break
  # It keeps no log (friends' links): its errors show only when run by hand.
  [ "$i" -eq 60 ] && die "страница подписки не запустилась. Её журнал не ведётся ради приватности знакомых; ошибки покажет запуск вручную: cd $RW_DIR && docker compose run --rm remnawave-subscription-page"
  sleep 3
done
# Friends' VPN does not depend on it: only a warning.
for i in $(seq 1 20); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' klaus-monitor 2>/dev/null)" = "healthy" ] && break
  if [ "$i" -eq 20 ]; then
    docker compose logs --tail 20 klaus-monitor >&2 || true
    warn "монитор блокировок (klaus-monitor) не запустился, журнал выше; VPN знакомых от него не зависит"
  fi
  sleep 3
done

if [ "$SKIP_SYSTEM" != "1" ]; then
  # New app builds reach the subscription address by themselves (quietly;
  # a private repository needs GITHUB_TOKEN).
  units_changed=0
  put_unit() { # NAME: stdin -> /etc/systemd/system/NAME when it differs
    local dst="/etc/systemd/system/$1" tmp
    tmp="$(mktemp)"
    cat > "$tmp"
    if [ -f "$dst" ] && cmp -s "$dst" "$tmp"; then rm -f "$tmp"; return 0; fi
    install -m 644 "$tmp" "$dst"
    rm -f "$tmp"
    units_changed=1
  }
  put_unit klaus-panel-apk.service <<EOF
# Written by Kirov VPN install-panel.sh
[Unit]
Description=Kirov VPN: publish a new app build on https://$SUB_DOMAIN/app/
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
Environment=KLAUS_PANEL_CONF=$CONF
ExecStart=/usr/local/bin/klaus-panel publish-apk --quiet
EOF
  put_unit klaus-panel-apk.timer <<'EOF'
# Written by Kirov VPN install-panel.sh
[Unit]
Description=Kirov VPN: look for a new app build every hour

[Timer]
OnCalendar=hourly
RandomizedDelaySec=10min
Persistent=true

[Install]
WantedBy=timers.target
EOF
  # The page for friends' browsers from PAGE_BRANCH (nothing to do while
  # that is empty).
  put_unit klaus-panel-page.service <<EOF
# Written by Kirov VPN install-panel.sh
[Unit]
Description=Kirov VPN: take the page for friends' browsers from GitHub
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
Environment=KLAUS_PANEL_CONF=$CONF
ExecStart=/usr/local/bin/klaus-panel update-page --quiet
EOF
  put_unit klaus-panel-page.timer <<'EOF'
# Written by Kirov VPN install-panel.sh
[Unit]
Description=Kirov VPN: look for a new page for friends every 15 minutes

[Timer]
OnActiveSec=1min
OnUnitActiveSec=15min
RandomizedDelaySec=1min

[Install]
WantedBy=timers.target
EOF
  # Friends added in the panel's web form get the servers and lose the
  # form's end date of tomorrow ("klaus-panel tidy-users"). It runs every
  # 20 seconds, so the journal keeps only what it fixed and its errors.
  put_unit klaus-panel-users.service <<EOF
# Written by Kirov VPN install-panel.sh
[Unit]
Description=Kirov VPN: friends added in the web panel get the servers and no end date
After=docker.service

[Service]
Type=oneshot
Environment=KLAUS_PANEL_CONF=$CONF
ExecStart=/usr/local/bin/klaus-panel tidy-users --quiet
SyslogLevel=notice
LogLevelMax=notice
EOF
  put_unit klaus-panel-users.timer <<'EOF'
# Written by Kirov VPN install-panel.sh
[Unit]
Description=Kirov VPN: look for friends added in the web panel every 20 seconds

[Timer]
OnActiveSec=20s
OnUnitActiveSec=20s
AccuracySec=1s

[Install]
WantedBy=timers.target
EOF
  if [ "$units_changed" = "1" ]; then systemctl daemon-reload; fi
  systemctl enable --now klaus-panel-apk.timer >/dev/null 2>&1 || warn "не удалось включить таймер klaus-panel-apk.timer"
  systemctl enable --now klaus-panel-users.timer >/dev/null 2>&1 || warn "не удалось включить таймер klaus-panel-users.timer"
  systemctl enable --now klaus-panel-page.timer >/dev/null 2>&1 || warn "не удалось включить таймер klaus-panel-page.timer"
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

rm -f "$RESTORE_MARK"
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
echo "     или в панели: «Пользователи» → «Создать пользователя», достаточно имени"
echo "     (серверы и бессрочный срок панель поставит сама)"
if [ -z "$TELEGRAM_CHAT_ID" ]; then
  echo "  3. Оповещения в Telegram о сбоях и блокировках:  klaus-panel telegram-setup ТОКЕН-БОТА"
else
  echo "  3. Оповещения в Telegram включены (проверка: klaus-panel telegram-test)"
fi
echo "  4. Новые сборки приложения из релиза $RELEASE_TAG публикуются сами; сейчас:  klaus-panel publish-apk"
if [ -n "$PAGE_BRANCH" ]; then
  echo "     Страница для знакомых обновляется сама из ветки $PAGE_BRANCH; сейчас:  klaus-panel update-page"
fi
echo "  5. Резервная копия:  klaus-panel backup"
echo
echo "Все команды: klaus-panel help"
if [ "$MOVED_TO_STABLE" = "1" ]; then
  echo
  warn "панель больше не раздаёт знакомым каждую тестовую сборку (релиз $OLD_DEFAULT_TAG), только стабильные (релиз stable, инструкция, раздел 12). Вернуть как было: sudo RELEASE_TAG=$OLD_DEFAULT_TAG bash install-panel.sh"
fi
if [ -n "$RESTORE_DIR" ]; then
  echo
  warn "панель переехала на новый IP. Направьте оба домена на $PANEL_IP и на каждом VPN-сервере выполните: sudo PANEL_IP=$PANEL_IP bash install-node.sh"
fi
