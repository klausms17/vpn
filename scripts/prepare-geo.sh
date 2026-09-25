#!/usr/bin/env bash
# Trims the upstream databases in $1 and installs them as APK assets.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$(cd "${1:?usage: prepare-geo.sh <upstream-dir>}" && pwd)"
dst="$root/app/src/main/assets/geo"
mkdir -p "$dst"
(cd "$root/libxray" && go run ./cmd/geotrim -src "$src" -dst "$dst")
date +%s > "$dst/version.txt"
