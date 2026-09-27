#!/usr/bin/env bash
# Builds app/libs/libxray.aar (Xray core + our Go wrapper) with gomobile.
# Needs Go (version from libxray/go.mod), JDK and the Android SDK + NDK.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/libxray"

mobile_version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
go install "golang.org/x/mobile/cmd/gomobile@${mobile_version}"
go install "golang.org/x/mobile/cmd/gobind@${mobile_version}"
export PATH="$(go env GOPATH)/bin:$PATH"

gomobile init
mkdir -p "$root/app/libs"
# -checklinkname=0: some Xray dependencies use go:linkname.
# max-page-size: 16 KB page alignment required by new Android devices.
gomobile bind -v \
  -target=android/arm64,android/arm \
  -androidapi 26 \
  -trimpath \
  -ldflags='-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384' \
  -o "$root/app/libs/libxray.aar" \
  .
ls -la "$root/app/libs/libxray.aar"
