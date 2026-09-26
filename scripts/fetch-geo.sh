#!/usr/bin/env bash
# Downloads the upstream Russian routing databases (runetfreedom, updated
# every 6 hours) into the given directory.
set -euo pipefail
out="${1:?usage: fetch-geo.sh <dir>}"
base="https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release"
mkdir -p "$out"
for f in geoip.dat geosite.dat; do
  curl --fail --location --silent --show-error --retry 5 --retry-delay 5 --retry-all-errors -o "$out/$f" "$base/$f"
  echo "$f: $(stat -c %s "$out/$f") bytes"
done
