#!/usr/bin/env bash
# Kirov VPN relay for mobile "whitelist" shutdowns.
#
# When mobile internet runs in whitelist-only mode, phones can reach only
# approved (mostly Russian) IP ranges, so a foreign VPN server is cut off.
# Run this on a VPS in Russia whose IP is on the whitelist (the app marks
# such servers with «белый список»). The phone connects to this relay, the
# relay forwards everything to your main foreign server.
#
#   sudo UPSTREAM='vless://...main server key...' bash install-relay.sh
#
# Options: SNI=<Russian site to imitate>, PORT=443, NAME=..., RESET=1.
set -euo pipefail

PORT="${PORT:-443}"
NAME="${NAME:-KirovVPN RU}"
RESET="${RESET:-0}"
CONF_DIR=/usr/local/etc/xray
CONF="$CONF_DIR/config.json"
CONF_NEXT="$CONF_DIR/config.next.json"
STATE="$CONF_DIR/klausvpn-relay.env"
KEYS_OUT=/root/klausvpn-relay-key.txt
SNI_CANDIDATES="ya.ru vk.com www.ozon.ru mail.ru dzen.ru"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }
urldecode() { local s="${1//+/%2B}"; printf '%b' "${s//%/\\x}"; }

[ "$(id -u)" -eq 0 ] || die "запустите от root"
[ -n "${UPSTREAM:-}" ] || die "укажите ключ основного сервера: UPSTREAM='vless://...'"

# ------------------------------------------------------------ upstream key
case "$UPSTREAM" in vless://*) ;; *) die "нужен ключ vless:// основного сервера" ;; esac
body="${UPSTREAM#vless://}"
body="${body%%#*}"
UP_ID="$(urldecode "${body%%@*}")"
rest="${body#*@}"
hostport="${rest%%\?*}"
query=""
if [ "$rest" != "$hostport" ]; then query="${rest#*\?}"; fi
hostport="${hostport%/}"
UP_PORT="${hostport##*:}"
UP_HOST="${hostport%:*}"
UP_HOST="${UP_HOST#[}"; UP_HOST="${UP_HOST%]}"
q() {
  printf '%s\n' "$query" | tr '&' '\n' | while IFS='=' read -r k v; do
    if [ "$k" = "$1" ]; then urldecode "$v"; break; fi
  done
  return 0
}
UP_SEC="$(q security)"; UP_TYPE="$(q type)"; UP_PBK="$(q pbk)"; UP_SID="$(q sid)"
UP_SNI="$(q sni)"; UP_FP="$(q fp)"; UP_FLOW="$(q flow)"; UP_PATH="$(q path)"
[ "$UP_SEC" = "reality" ] || die "поддерживаются ключи с REALITY (security=reality)"
# Current REALITY servers need a post-quantum key share in the hello; only
# these fingerprints always send it.
case "$(printf '%s' "${UP_FP:-chrome}" | tr 'A-Z' 'a-z')" in
  chrome) UP_FP=chrome ;; firefox) UP_FP=firefox ;; safari) UP_FP=safari ;;
  *) UP_FP=chrome ;;
esac
[ -n "$UP_PBK" ] && [ -n "$UP_SNI" ] || die "в ключе нет pbk или sni"
case "${UP_TYPE:-tcp}" in
  tcp|raw) UP_NET=raw ;;
  xhttp) UP_NET=xhttp ;;
  *) die "транспорт $UP_TYPE не поддерживается для ретранслятора" ;;
esac
say "Основной сервер: $UP_HOST:$UP_PORT ($UP_NET)"

# ------------------------------------------------------------ install
say "Устанавливаю зависимости и Xray"
if command -v apt-get >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  # A fresh VPS may still be running its first automatic updates: wait for
  # apt instead of failing on its lock.
  cloud-init status --wait >/dev/null 2>&1 || true
  for i in $(seq 1 60); do
    apt-get update -qq && break
    [ "$i" -eq 60 ] && die "apt занят другим процессом, повторите через несколько минут"
    sleep 10
  done
  apt-get -o DPkg::Lock::Timeout=600 install -y -qq curl ca-certificates openssl qrencode >/dev/null
fi
bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install >/dev/null ||
  die "не удалось установить Xray (нет доступа к github.com?). Повторите запуск через минуту"
XRAY=/usr/local/bin/xray

# Never silently replace an Xray setup this script did not create: its
# clients would stop working. FORCE=1 replaces it (a copy is kept).
if [ ! -f "$STATE" ] && [ -s "$CONF" ] && [ "$(tr -d ' \t\r\n' < "$CONF")" != "{}" ] && [ "${FORCE:-0}" != "1" ]; then
  die "в $CONF уже есть настройки Xray, сделанные не этим скриптом: после замены старые ключи перестанут работать. Если это нужно, запустите: sudo FORCE=1 bash $0 (копия сохранится рядом)"
fi

if [ -f "$STATE" ] && [ "$RESET" != "1" ]; then
  # shellcheck disable=SC1090
  . "$STATE"
else
  UUID="$("$XRAY" uuid)"
  KEYPAIR="$("$XRAY" x25519)"
  PRIVATE_KEY="$(printf '%s\n' "$KEYPAIR" | awk -F': *' 'tolower($1) ~ /private/ {print $2; exit}')"
  PUBLIC_KEY="$(printf '%s\n' "$KEYPAIR" | awk -F': *' 'tolower($1) ~ /public/ {print $2; exit}')"
  SHORT_ID="$(openssl rand -hex 8)"
  SNI_SAVED=""
fi

check_sni() {
  local out
  out="$(timeout 15 "$XRAY" tls ping "$1" 2>/dev/null)" || return 1
  printf '%s' "$out" | sed -n '/Pinging with SNI/,$p' | grep -q "TLS 1.3"
}
if [ -n "${SNI:-}" ]; then
  check_sni "$SNI" || die "сайт $SNI не подходит для маскировки (нужен TLS 1.3)"
else
  if [ -n "${SNI_SAVED:-}" ]; then SNI="$SNI_SAVED"; else
    for c in $SNI_CANDIDATES; do if check_sni "$c"; then SNI="$c"; break; fi; done
  fi
fi
[ -n "${SNI:-}" ] || die "не найден подходящий сайт для маскировки; задайте SNI=..."
say "Маскировка под: $SNI"

umask 077
cat > "$STATE" <<EOF
UUID='$UUID'
PRIVATE_KEY='$PRIVATE_KEY'
PUBLIC_KEY='$PUBLIC_KEY'
SHORT_ID='$SHORT_ID'
SNI_SAVED='$SNI'
EOF

flow_json=""
if [ -n "$UP_FLOW" ]; then flow_json=", \"flow\": \"$UP_FLOW\""; fi
xhttp_json=""
if [ "$UP_NET" = "xhttp" ]; then xhttp_json="\"xhttpSettings\": { \"path\": \"${UP_PATH:-/}\" },"; fi

mkdir -p "$CONF_DIR"
# policy: 30 idle minutes instead of Xray's 5, as in install.sh: phones keep
# push channels quiet for up to 28 minutes (both the phone's side and the
# upstream side of the relay use level 0).
cat > "$CONF_NEXT" <<EOF
{
  "log": { "loglevel": "warning", "access": "none" },
  "policy": { "levels": { "0": { "connIdle": 1800 } } },
  "inbounds": [
    {
      "tag": "relay-in",
      "listen": "0.0.0.0",
      "port": $PORT,
      "protocol": "vless",
      "settings": { "clients": [ { "id": "$UUID", "flow": "xtls-rprx-vision", "email": "relay" } ], "decryption": "none" },
      "streamSettings": {
        "network": "raw",
        "security": "reality",
        "realitySettings": {
          "target": "$SNI:443",
          "serverNames": ["$SNI"],
          "privateKey": "$PRIVATE_KEY",
          "shortIds": ["$SHORT_ID"]
        }
      }
    }
  ],
  "outbounds": [
    {
      "tag": "upstream",
      "protocol": "vless",
      "settings": { "vnext": [ { "address": "$UP_HOST", "port": $UP_PORT, "users": [ { "id": "$UP_ID", "encryption": "none"$flow_json } ] } ] },
      "streamSettings": {
        "network": "$UP_NET",
        $xhttp_json
        "security": "reality",
        "realitySettings": {
          "serverName": "$UP_SNI",
          "fingerprint": "${UP_FP:-chrome}",
          "password": "$UP_PBK",
          "shortId": "$UP_SID"
        }
      }
    },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "rules": [
      { "ip": ["geoip:private"], "outboundTag": "block" },
      { "network": "tcp,udp", "outboundTag": "upstream" }
    ]
  }
}
EOF
"$XRAY" run -test -c "$CONF_NEXT" >/dev/null || die "Xray не принял конфигурацию ($CONF_NEXT)"
if [ -s "$CONF" ] && ! cmp -s "$CONF" "$CONF_NEXT"; then cp -p "$CONF" "$CONF.bak-$(date +%Y%m%d-%H%M%S)"; fi
mv "$CONF_NEXT" "$CONF"
chmod 644 "$CONF"

cat > /etc/sysctl.d/99-klausvpn.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
EOF
sysctl --system >/dev/null 2>&1 || true
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then ufw allow "$PORT/tcp" >/dev/null; fi

systemctl enable xray >/dev/null 2>&1
systemctl restart xray
sleep 2
systemctl is-active --quiet xray || { journalctl -u xray -n 30 --no-pager; die "Xray не запустился"; }

IP="$(curl -4 -fsS --max-time 10 https://api.ipify.org || true)"
[ -n "$IP" ] || die "не удалось узнать внешний IP"
enc() { printf '%s' "$1" | od -An -tx1 | tr -d ' \n' | sed 's/\(..\)/%\1/g'; }
LINK="vless://$UUID@$IP:$PORT?type=tcp&security=reality&pbk=$PUBLIC_KEY&fp=chrome&sni=$SNI&sid=$SHORT_ID&flow=xtls-rprx-vision&encryption=none#$(enc "$NAME")"
printf 'Ключ ретранслятора (%s):\n%s\n' "$IP" "$LINK" > "$KEYS_OUT"
chmod 600 "$KEYS_OUT"

say "Готово"
cat "$KEYS_OUT"
qrencode -t ansiutf8 "$LINK" || true
echo "Добавьте этот ключ в приложение. Если у сервера появилась отметка «белый список», он будет работать и при ограничениях мобильного интернета."
