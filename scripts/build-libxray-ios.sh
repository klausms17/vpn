#!/usr/bin/env bash
# Builds ios/Frameworks/Libxray.xcframework (Xray core + our Go wrapper) with
# gomobile. macOS with Xcode only (the iOS link needs Apple's clang). Only the
# device slice: Network Extensions do not run in the Simulator anyway, and a
# second slice would double the Go build time.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/libxray"

mobile_version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
go install "golang.org/x/mobile/cmd/gomobile@${mobile_version}"
go install "golang.org/x/mobile/cmd/gobind@${mobile_version}"
export PATH="$(go env GOPATH)/bin:$PATH"

out="$root/ios/Frameworks/Libxray.xcframework"
rm -rf "$out"
mkdir -p "$root/ios/Frameworks"
# -checklinkname=0: some Xray dependencies use go:linkname (as for Android).
# -iosversion: the app's deployment target (ios/project.yml).
gomobile bind -v \
  -target=ios/arm64 \
  -iosversion 17.0 \
  -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0' \
  -o "$out" \
  .

# gomobile still compiles its C glue with -fembed-bitcode. The framework is a
# static archive, and bitcode_strip cannot process the Go object in it; the
# linker drops that bitcode from the app instead (ios-app.yml checks it).
du -sh "$out"
