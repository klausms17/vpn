#!/usr/bin/env bash
# Klaus VPN server installer: Xray (latest release) with VLESS + REALITY.
#
#   Copy this file to the server and run: sudo bash install.sh
#
# Options (environment variables):
#   SNI=www.example.com  site REALITY imitates (default: first working from a built-in list)
#   PORT=443             main port (VLESS + REALITY + Vision, fastest)
#   XHTTP_PORT=8443      backup port (VLESS + REALITY + XHTTP), 0 to disable
#   NAME=MyVPN           name shown in the app
#   RESET=1              generate new keys (old client keys stop working)
#
# Re-running the script keeps the existing keys, so it is also the way to
# update Xray.
set -euo pipefail

PORT="${PORT:-443}"
XHTTP_PORT="${XHTTP_PORT:-8443}"
NAME="${NAME:-KlausVPN}"
RESET="${RESET:-0}"
CONF_DIR=/usr/local/etc/xray
CONF="$CONF_DIR/config.json"
CONF_NEXT="$CONF_DIR/config.next.json" # Xray infers the format from the extension
STATE="$CONF_DIR/klausvpn.env"
KEYS_OUT=/root/klausvpn-keys.txt
SNI_CANDIDATES="www.nvidia.com www.samsung.com www.amd.com dl.google.com www.cisco.com"

say() { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "запустите от root (sudo bash install.sh)"
command -v systemctl >/dev/null || die "нужен systemd (Ubuntu 22.04+/Debian 12+)"

say "Устанавливаю зависимости"
if command -v apt-get >/dev/null; then
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq
  apt-get install -y -qq curl ca-certificates openssl qrencode >/dev/null
elif command -v dnf >/dev/null; then
  dnf install -y -q curl ca-certificates openssl qrencode >/dev/null
else
  die "поддерживаются Debian/Ubuntu и RHEL-подобные системы"
fi

say "Устанавливаю/обновляю Xray до последней версии"
bash -c "$(curl -fsSL https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install >/dev/null
XRAY=/usr/local/bin/xray
[ -x "$XRAY" ] || die "Xray не установился"
"$XRAY" version | head -1

# ---------------------------------------------------------------- keys
if [ -f "$STATE" ] && [ "$RESET" != "1" ]; then
  say "Использую существующие ключи ($STATE)"
  # shellcheck disable=SC1090
  . "$STATE"
else
  say "Генерирую новые ключи"
  UUID="$("$XRAY" uuid)"
  KEYPAIR="$("$XRAY" x25519)"
  # Handles both "PrivateKey:/Password (PublicKey):" and the older
  # "Private key:/Public key:" output formats.
  PRIVATE_KEY="$(printf '%s\n' "$KEYPAIR" | awk -F': *' 'tolower($1) ~ /private/ {print $2; exit}')"
  PUBLIC_KEY="$(printf '%s\n' "$KEYPAIR" | awk -F': *' 'tolower($1) ~ /public/ {print $2; exit}')"
  SHORT_ID="$(openssl rand -hex 8)"
  XHTTP_PATH="/$(openssl rand -hex 6)"
  [ -n "$PRIVATE_KEY" ] && [ -n "$PUBLIC_KEY" ] || die "не удалось сгенерировать ключи REALITY"
  SNI_SAVED=""
fi

# ---------------------------------------------------------------- SNI
check_sni() {
  # REALITY needs a real TLS 1.3 site; post-quantum key exchange support
  # makes the imitation match modern clients exactly.
  local out
  out="$(timeout 15 "$XRAY" tls ping "$1" 2>/dev/null)" || return 1
  printf '%s' "$out" | sed -n '/Pinging with SNI/,$p' | grep -q "Handshake succeeded" || return 1
  printf '%s' "$out" | sed -n '/Pinging with SNI/,$p' | grep -q "TLS 1.3" || return 1
}
if [ -n "${SNI:-}" ]; then
  check_sni "$SNI" || die "сайт $SNI не подходит для REALITY (нужен TLS 1.3)"
elif [ -n "${SNI_SAVED:-}" ]; then
  SNI="$SNI_SAVED"
else
  say "Подбираю сайт для маскировки"
  for c in $SNI_CANDIDATES; do
    if check_sni "$c"; then SNI="$c"; break; fi
  done
  [ -n "${SNI:-}" ] || die "ни один сайт из списка не подошёл; задайте SNI=сайт вручную"
fi
say "Маскировка под: $SNI"

umask 077
cat > "$STATE" <<EOF
UUID='$UUID'
PRIVATE_KEY='$PRIVATE_KEY'
PUBLIC_KEY='$PUBLIC_KEY'
SHORT_ID='$SHORT_ID'
XHTTP_PATH='$XHTTP_PATH'
SNI_SAVED='$SNI'
EOF

# ---------------------------------------------------------------- config
reality_settings="\"security\": \"reality\",
        \"realitySettings\": {
          \"target\": \"$SNI:443\",
          \"serverNames\": [\"$SNI\"],
          \"privateKey\": \"$PRIVATE_KEY\",
          \"shortIds\": [\"$SHORT_ID\"]
        }"

xhttp_inbound=""
if [ "$XHTTP_PORT" != "0" ]; then
  xhttp_inbound=",
    {
      \"tag\": \"vless-xhttp\",
      \"listen\": \"0.0.0.0\",
      \"port\": $XHTTP_PORT,
      \"protocol\": \"vless\",
      \"settings\": { \"clients\": [ { \"id\": \"$UUID\", \"email\": \"main-xhttp\" } ], \"decryption\": \"none\" },
      \"streamSettings\": {
        \"network\": \"xhttp\",
        \"xhttpSettings\": { \"path\": \"$XHTTP_PATH\" },
        $reality_settings
      },
      \"sniffing\": { \"enabled\": true, \"destOverride\": [\"http\", \"tls\", \"quic\"], \"routeOnly\": true }
    }"
fi

mkdir -p "$CONF_DIR"
cat > "$CONF_NEXT" <<EOF
{
  "log": { "loglevel": "warning", "access": "none" },
  "inbounds": [
    {
      "tag": "vless-vision",
      "listen": "0.0.0.0",
      "port": $PORT,
      "protocol": "vless",
      "settings": { "clients": [ { "id": "$UUID", "flow": "xtls-rprx-vision", "email": "main" } ], "decryption": "none" },
      "streamSettings": {
        "network": "raw",
        $reality_settings
      },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true }
    }$xhttp_inbound
  ],
  "outbounds": [
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "rules": [
      { "ip": ["geoip:private"], "outboundTag": "block" }
    ]
  }
}
EOF
"$XRAY" run -test -c "$CONF_NEXT" >/dev/null || die "Xray не принял конфигурацию (см. $CONF_NEXT)"
mv "$CONF_NEXT" "$CONF"
chmod 644 "$CONF"

# ---------------------------------------------------------------- system
say "Включаю BBR (быстрее на медленных и дальних каналах)"
cat > /etc/sysctl.d/99-klausvpn.conf <<'EOF'
net.core.default_qdisc = fq
net.ipv4.tcp_congestion_control = bbr
net.ipv4.tcp_fastopen = 3
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
EOF
sysctl --system >/dev/null 2>&1 || true

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  say "Открываю порты в ufw"
  ufw allow "$PORT/tcp" >/dev/null
  if [ "$XHTTP_PORT" != "0" ]; then ufw allow "$XHTTP_PORT/tcp" >/dev/null; fi
fi

systemctl enable xray >/dev/null 2>&1
systemctl restart xray
sleep 2
systemctl is-active --quiet xray || { journalctl -u xray -n 30 --no-pager; die "Xray не запустился"; }

# ---------------------------------------------------------------- output
IP="$(curl -4 -fsS --max-time 10 https://api.ipify.org || curl -4 -fsS --max-time 10 https://ifconfig.me || true)"
[ -n "$IP" ] || die "не удалось узнать внешний IP сервера"

enc() { printf '%s' "$1" | od -An -tx1 | tr -d ' \n' | sed 's/\(..\)/%\1/g'; }
LINK_VISION="vless://$UUID@$IP:$PORT?type=tcp&security=reality&pbk=$PUBLIC_KEY&fp=chrome&sni=$SNI&sid=$SHORT_ID&flow=xtls-rprx-vision&encryption=none#$(enc "$NAME")"
LINK_XHTTP=""
if [ "$XHTTP_PORT" != "0" ]; then
  LINK_XHTTP="vless://$UUID@$IP:$XHTTP_PORT?type=xhttp&path=$(enc "$XHTTP_PATH")&mode=auto&security=reality&pbk=$PUBLIC_KEY&fp=chrome&sni=$SNI&sid=$SHORT_ID&encryption=none#$(enc "$NAME XHTTP")"
fi

{
  echo "Klaus VPN — ключи сервера $IP ($(date '+%Y-%m-%d %H:%M'))"
  echo
  echo "Основной (быстрый):"
  echo "$LINK_VISION"
  if [ -n "$LINK_XHTTP" ]; then
    echo
    echo "Резервный (XHTTP, если основной начнут блокировать):"
    echo "$LINK_XHTTP"
  fi
} > "$KEYS_OUT"
chmod 600 "$KEYS_OUT"

echo
say "Готово! Xray работает."
echo
cat "$KEYS_OUT"
echo
echo "QR-код основного ключа (можно сфотографировать телефоном):"
qrencode -t ansiutf8 "$LINK_VISION" || true
echo
echo "Ключи сохранены в $KEYS_OUT. Скопируйте ключ целиком и вставьте в приложение (кнопка «+»)."
echo "Никому не пересылайте ключи: с ними любой сможет пользоваться вашим сервером."
