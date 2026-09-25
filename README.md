# Klaus VPN

Android VPN client on the latest Xray core with Russia split routing.
Full description in Russian: see `docs/` (to be added) and the release notes.

- `libxray/` – shared core (Go): share-link & subscription parsing, Xray
  config generation, routing presets. Built with gomobile for Android now,
  iOS later. Tested against real local Xray servers (`go test ./...`).
- `app/` – Android app (Kotlin, Jetpack Compose).
- `scripts/` – build helpers used by CI.
- `.github/workflows/android.yml` – tests, builds signed APKs, publishes a
  test release.
