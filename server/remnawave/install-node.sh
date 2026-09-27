#!/usr/bin/env bash
# Kirov VPN server (Remnawave node) installer.
#
# Run as root on each VPN VPS abroad (Ubuntu 22.04+/Debian 12+). The exact
# command, with the key, is printed by "klaus-panel add-node" on the panel:
#
#   sudo PANEL_IP=198.51.100.7 SECRET_KEY='…' bash install-node.sh
#
# Options (environment variables, written AFTER sudo):
#   PANEL_IP     IP address of the panel server (required)
#   SECRET_KEY   key from "klaus-panel add-node" (required the first time;
#                a re-run keeps the one already installed)
#   NODE_PORT    port the panel uses to control this server (default 2222;
#                only PANEL_IP may connect to it). Must match the port the
#                panel has for this server (add-node --node-port); a re-run
#                keeps the one already installed
#   VPN_PORT     the VPN port set on the panel (REALITY_PORT, default 443),
#                only checked and opened in ufw here; "klaus-panel add-node"
#                prints it when it is not 443. A re-run keeps it too
#   MIGRATE=1    this VPS runs the old install.sh Xray: switch it off and
#                hand port 443 to the new server (the old keys stop working)
#
# Re-running with a new SECRET_KEY replaces the old one; it also updates
# the node to the latest version. After a panel move only PANEL_IP is needed.
#
# For the local test harness only (server/remnawave/test): NODE_DIR and
# SKIP_SYSTEM=1 (only writes the compose file and starts the container).
set -euo pipefail

NODE_DIR="${NODE_DIR:-/opt/remnanode}"
NODE_PORT="${NODE_PORT:-}"
VPN_PORT="${VPN_PORT:-}"
MIGRATE="${MIGRATE:-0}"
SKIP_SYSTEM="${SKIP_SYSTEM:-0}"
PANEL_IP="${PANEL_IP:-}"
SECRET_KEY="${SECRET_KEY:-}"
FW_TABLE=klausvpn_node
# A re-run (e.g. the panel moved to a new IP) keeps the installed key and
# ports: the panel still has them.
if [ -f "$NODE_DIR/.env" ]; then
  installed() { sed -n "s/^$1=//p" "$NODE_DIR/.env" | tail -n 1; }
  [ -n "$SECRET_KEY" ] || SECRET_KEY="$(installed SECRET_KEY)"
  [ -n "$NODE_PORT" ] || NODE_PORT="$(installed NODE_PORT)"
  [ -n "$VPN_PORT" ] || VPN_PORT="$(installed VPN_PORT)"
fi
NODE_PORT="${NODE_PORT:-2222}"
VPN_PORT="${VPN_PORT:-443}"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mВнимание:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "запустите от root (sudo … bash install-node.sh)"
if [ -z "$PANEL_IP" ] || [ -z "$SECRET_KEY" ]; then
  die "нужны PANEL_IP и SECRET_KEY: скопируйте готовую команду из вывода «klaus-panel add-node» на панели"
fi
[[ "$PANEL_IP" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ || "$PANEL_IP" =~ ^[0-9A-Fa-f:]+$ ]] ||
  die "PANEL_IP должен быть IP-адресом панели, например 198.51.100.7"
for p in "$NODE_PORT" "$VPN_PORT"; do
  if ! [[ "$p" =~ ^[0-9]+$ ]] || [ "$p" -lt 1 ] || [ "$p" -gt 65535 ]; then die "странный порт: $p"; fi
done
[ "$NODE_PORT" != "$VPN_PORT" ] || die "NODE_PORT и VPN_PORT должны различаться"

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
fi

# The key is base64 JSON with the certificates the panel signed for nodes.
command -v base64 >/dev/null || die "нет программы base64"
key_json="$(printf '%s' "$SECRET_KEY" | base64 -d 2>/dev/null || true)"
for f in caCertPem jwtPublicKey nodeCertPem nodeKeyPem; do
  grep -q "\"$f\"" <<<"$key_json" || die "SECRET_KEY повреждён: скопируйте его из вывода «klaus-panel add-node» целиком, в одинарных кавычках"
done

# ---------------------------------------------------------------- old Xray
# server/install.sh runs Xray as a systemd service on 443 (and 8443). The
# node needs the port, and switching the old server off breaks the keys
# friends got from it, so that only happens on request.
if [ "$SKIP_SYSTEM" != "1" ] && systemctl is-active --quiet xray 2>/dev/null &&
  { ! command -v ss >/dev/null || grep -q '"xray"' <<<"$(ss -Hltnp "sport = :$VPN_PORT" 2>/dev/null)"; }; then
  if [ "$MIGRATE" != "1" ]; then
    die "здесь работает прежний сервер Kirov VPN (Xray из install.sh). После перехода на Remnawave его ключи перестанут работать. Когда знакомые получат подписки, запустите: sudo MIGRATE=1 PANEL_IP=… SECRET_KEY='…' bash install-node.sh (вернуть старый: systemctl enable --now xray, предварительно остановив новый: cd $NODE_DIR && docker compose down)"
  fi
  say "Выключаю прежний Xray (настройки и ключи остаются в /usr/local/etc/xray)"
  systemctl disable --now xray >/dev/null 2>&1 || true
fi
if [ "$SKIP_SYSTEM" != "1" ] && command -v ss >/dev/null; then
  for p in "$VPN_PORT" "$NODE_PORT"; do
    owner="$(ss -Hltnp "sport = :$p" 2>/dev/null | grep -o 'users:(("[^"]*"' | head -n 1 | sed 's/users:(("//' || true)"
    # remnanode's own processes (on host network) hold the ports on a re-run.
    if [ -n "$owner" ] && ! grep -qx remnanode <<<"$(docker ps --format '{{.Names}}' 2>/dev/null)"; then
      # Both ports are set on the panel: changing them only here would
      # leave the panel and friends' apps knocking on the old ones.
      if [ "$p" = "$VPN_PORT" ]; then
        die "порт $p уже занят программой «$owner». Освободите его: порт VPN задаётся на панели сразу для всех серверов (REALITY_PORT в install-panel.sh), здесь его не поменять"
      else
        die "порт $p уже занят программой «$owner». Освободите его или смените порт управления этого сервера в веб-панели (Nodes → сервер → порт) и запустите снова с NODE_PORT=новый-порт"
      fi
    fi
  done
fi

if [ "$SKIP_SYSTEM" != "1" ]; then
  say "Устанавливаю зависимости"
  apt_install curl ca-certificates
  install_docker

  say "Включаю BBR (быстрее на медленных и дальних каналах)"
  cat > /etc/sysctl.d/99-klausvpn.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_fastopen = 3
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
EOF
  sysctl --system >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- node
mkdir -p "$NODE_DIR"
chmod 700 "$NODE_DIR"
umask 077
STAMP="$(date +%Y%m%d-%H%M%S)"
put_file() {
  local dst="$1" tmp="$1.next"
  cat > "$tmp"
  if [ -f "$dst" ] && cmp -s "$dst" "$tmp"; then rm -f "$tmp"; return; fi
  if [ -f "$dst" ]; then cp -p "$dst" "$dst.bak-$STAMP"; fi
  mv "$tmp" "$dst"
}

put_file "$NODE_DIR/.env" <<EOF
# Written by Kirov VPN install-node.sh; re-running the script rewrites it.
NODE_PORT=$NODE_PORT
SECRET_KEY=$SECRET_KEY
# Not used by the node (the panel sets its VPN port); kept for re-runs.
VPN_PORT=$VPN_PORT
EOF

# As remnawave/node docker-compose-prod.yml, with the key in .env and
# rotated logs.
put_file "$NODE_DIR/docker-compose.yml" <<'EOF'
# Written by Kirov VPN install-node.sh; re-running the script rewrites it.
name: remnanode

services:
  remnanode:
    container_name: remnanode
    hostname: remnanode
    image: remnawave/node:latest
    network_mode: host
    restart: always
    cap_add:
      - NET_ADMIN
    ulimits:
      nofile:
        soft: 1048576
        hard: 1048576
    env_file: .env
    logging:
      driver: json-file
      options:
        max-size: 50m
        max-file: 3
EOF

cd "$NODE_DIR"
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
  say "Скачиваю/обновляю Remnawave Node"
  compose pull || die "не удалось скачать образ (Docker Hub недоступен?). Повторите через пару минут"
fi
say "Запускаю Remnawave Node"
compose up -d --remove-orphans || die "контейнер не запустился (подробности выше)"
sleep 3
[ "$(docker inspect -f '{{.State.Running}}' remnanode 2>/dev/null)" = "true" ] ||
  { docker compose logs --tail 30 >&2 || true; die "контейнер remnanode не запустился (журнал выше)"; }

# ---------------------------------------------------------------- firewall
# Only the panel may reach NODE_PORT. SSH and everything else stay as they
# are, so a mistake here cannot lock you out.
if [ "$SKIP_SYSTEM" != "1" ]; then
  if command -v ufw >/dev/null && grep -q "Status: active" <<<"$(ufw status)"; then
    say "Настраиваю ufw: $VPN_PORT для всех, $NODE_PORT только для панели ($PANEL_IP)"
    ufw allow "$VPN_PORT/tcp" >/dev/null
    # The panel rule must come before the deny, also when it is added on a
    # later run (new panel IP).
    ufw insert 1 allow from "$PANEL_IP" to any port "$NODE_PORT" proto tcp >/dev/null 2>&1 ||
      ufw allow from "$PANEL_IP" to any port "$NODE_PORT" proto tcp >/dev/null
    ufw deny "$NODE_PORT/tcp" >/dev/null
  else
    say "Закрываю порт $NODE_PORT для всех, кроме панели ($PANEL_IP)"
    command -v nft >/dev/null || apt_install nftables
    if [[ "$PANEL_IP" == *:* ]]; then
      allow="ip6 saddr $PANEL_IP tcp dport $NODE_PORT accept"
    else
      allow="ip saddr $PANEL_IP tcp dport $NODE_PORT accept"
    fi
    # A separate table: the rest of the host firewall is not touched.
    cat > /etc/klausvpn-node.nft <<EOF
table inet $FW_TABLE
delete table inet $FW_TABLE
table inet $FW_TABLE {
	chain input {
		type filter hook input priority -10; policy accept;
		$allow
		tcp dport $NODE_PORT drop
	}
}
EOF
    nft -f /etc/klausvpn-node.nft
    cat > /etc/systemd/system/klausvpn-node-firewall.service <<'EOF'
[Unit]
Description=Kirov VPN: only the panel may reach the Remnawave node port
After=network-pre.target
Before=network.target docker.service

[Service]
Type=oneshot
ExecStart=/usr/sbin/nft -f /etc/klausvpn-node.nft
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
EOF
    sed -i "s#/usr/sbin/nft#$(command -v nft)#" /etc/systemd/system/klausvpn-node-firewall.service
    systemctl daemon-reload
    systemctl enable klausvpn-node-firewall >/dev/null 2>&1
  fi
fi

echo
say "Готово! Сервер запущен."
echo
echo "Через минуту на панели выполните: klaus-panel list-nodes — у сервера должно быть «на связи»."
echo "Если нет: проверьте, что у хостера не закрыты порты $VPN_PORT и $NODE_PORT (внешний файрвол),"
echo "журнал сервера: cd $NODE_DIR && docker compose logs --tail 50"
