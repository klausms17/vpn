#!/usr/bin/env bash
# Trims the upstream databases in $1 and installs them as APK assets, or
# into $2 (e.g. for the iPhone app's tunnel extension).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$(cd "${1:?usage: prepare-geo.sh <upstream-dir> [destination]}" && pwd)"
dst="${2:-$root/app/src/main/assets/geo}"
mkdir -p "$dst"
dst="$(cd "$dst" && pwd)"  # geotrim runs in libxray/, so no relative path
(cd "$root/libxray" && go run ./cmd/geotrim -src "$src" -dst "$dst")
date +%s > "$dst/version.txt"
