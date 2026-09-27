# Kirov VPN

Android VPN client on the Xray core for the owner's friends in Russia
(about 100–1000 people), plus the servers and panel that feed it. An iPhone
app is in progress. The owner (GitHub `klausms17`) writes in Russian, uses
Windows, has no Mac, and is not a developer: answer in Russian, plainly,
and give step-by-step instructions for anything he must do himself.

## Names

- The Android app is called **Kirov VPN** (it was Klaus VPN). The iPhone app
  still says Klaus VPN until the owner decides.
- Technical identifiers keep the old name on purpose, because changing them
  breaks installed apps, links or the panel: package `com.klausms.vpn`, the
  `klausvpn://` deep-link scheme, the User-Agent `KlausVPN/<ver> (Android)`
  (the panel recognises the app by `^KlausVPN/`), `klaus-panel`,
  `klaus-monitor`, `/klaus/` URL paths, `KLAUS_*` variables, bundle IDs
  `com.klausms.vpn[.tunnel]` and the App Group `group.com.klausms.vpn`.

## Repository map

- `libxray/`: the Go core, bound with gomobile for Android (AAR) and iOS
  (xcframework). It covers share links and subscriptions, the Xray config
  (Russian sites go direct), the controller (start/stop, probes, fetch
  through the tunnel), geo files (trim, whitelist lookups) and the iOS utun
  fd. Its tests start real local Xray servers.
- `app/`: the Android app (Kotlin, Jetpack Compose). It runs as two
  processes: the UI and `:vpn`.
  - `service/`: `XrayVpnService` is only the coordinator. The work is split
    across `TunnelEngine` (start/stop/retries, `StartFailurePolicy`,
    `ResetScheduler`), `HealthMonitor` (checks, stall detection),
    `FailoverSearch`, `ServerSwitcher`, `StatusPublisher` (status and
    notices), `NetworkWatcher`, `SubscriptionRefresher`,
    `BlockReportDispatcher` and others. Each class's KDoc states its
    threading rules: Main vs. the single worker vs. IO, `Epoch`, and the
    non-reentrant start `Mutex`.
  - `ui/`: `MainActivity`, `MainViewModel`, `UiSession` (app-scoped
    feedback), `TunnelController` (connect/reconnect rules), `screens/`,
    `components/`.
  - `data/`: `JsonFileStore` (profiles and settings, shared by both
    processes behind a file lock), `Models`, `SubscriptionUpdater`,
    `GeoFiles`, `ProfilesOps`.
  - `widget/`, `core/` (`XrayCore`, `CoreHandle`, `DirectNet`,
    `CoreErrors`) and `util/`.
- `ios/`: the iPhone app. `project.yml` is the XcodeGen spec; `App/`,
  `PacketTunnel/` and `Shared/` hold the code. The plan is in
  `docs/ios/PLAN.md` and the drafts for later phases are in
  `docs/ios/drafts/`.
- `server/`:
  - `install.sh`: a standalone Xray VLESS+REALITY server.
  - `install-relay.sh`: a Russian relay for mobile "whitelist" mode.
  - `remnawave/`: the panel for friends. `install-panel.sh` and
    `install-node.sh` install it; `klaus-panel` is the owner's CLI (friends,
    nodes, publish-apk, telegram-setup); `klaus-monitor.py` turns block
    reports from the app into Telegram alerts; `test/` holds the Docker
    end-to-end test and the monitor unit tests.
  - The owner may add friends in the panel's web form instead of the CLI.
    That form starts with no squad and an end date of tomorrow, so a
    systemd timer runs `klaus-panel tidy-users` every 20 seconds: users
    made in the last two days get the `KlausVPN` squad and no end date.
- `scripts/`: `build-libxray.sh` (AAR), `build-libxray-ios.sh`,
  `fetch-geo.sh`, `prepare-geo.sh`.
- `docs/README.ru.md`: the owner's full guide in Russian: install, servers,
  whitelist mode, reliability, signing, panel and distribution.
- `tools/jvm-check/`: a local compile-and-test of the plain Kotlin code
  without the Android SDK.

## Building and testing

- **CI is the source of truth.**
  - `.github/workflows/android.yml` runs the Go tests (including the
    end-to-end ones), the monitor tests and a vet of the e2e tool. It builds
    the APK, runs lint (`abortOnError`), the unit tests and the 16 KB
    alignment check.
  - It then publishes the pre-release `build-<branch>` with
    `KirovVPN-1.0.<run>.apk`.
  - It publishes the `stable` release, the only one the panel gives to
    friends, only for a `v*` tag or a manual run with "stable" ticked.
  - A push cancels the branch's running build. A build takes about 10–15
    minutes.
  - `ios.yml` (Linux) runs the Go iOS checks. `ios-app.yml` (macOS) builds
    the unsigned app and checks the extension, its geo files and bitcode.
    The repository is public, so macOS minutes cost nothing.
- **In a cloud session there is no Android SDK and Google Maven is
  blocked.** Compose screens, resources and lint are checked only in CI.
  Locally:
  - Kotlin: `gradle -p tools/jvm-check test` compiles everything but the
    Compose UI and runs the JVM tests, currently 234.
  - Go:
    `scripts/fetch-geo.sh /tmp/geo && cd libxray && go vet . && GEO_DIR=/tmp/geo go test ./...`
  - iOS compile check of the core:
    `GOOS=ios GOARCH=arm64 CGO_ENABLED=0 go build .` and
    `GOOS=darwin GOARCH=arm64 CGO_ENABLED=0 go vet -tags ios .`
  - Panel: start Docker (`dockerd &`), then
    `WORK=/tmp/rw bash server/remnawave/test/run-local.sh`. It takes about
    25 minutes and must end with `ALL CHECKS PASSED`.
    `python3 -B server/remnawave/test/monitor_test.py` runs the monitor
    tests alone.
- Keep `tools/jvm-check/stubs` in step with the AIDL files and the
  `libxray` API.

## Rules

- Code must be professional, fast and maintainable:
  - small cohesive classes and no dead code;
  - comments only where the reason is not obvious, in plain English;
  - UI strings in Russian;
  - keep every `Build.VERSION.SDK_INT` guard, because lint fails the build.
- Keep behaviour the same when refactoring. Test logic in plain classes
  (JVM tests with `kotlinx-coroutines-test` and fakes).
- Privacy and security:
  - Never log keys, links, IPs or server hostnames. Go error texts must not
    quote link bodies.
  - Never commit secrets.
  - Never ask the owner to paste keys, passwords, tokens or UDIDs into a
    chat. Those go into GitHub secrets or the panel's hidden prompts.
- Commits: a short imperative title and a body that says what changed and
  why.

## Status (27 Sep 2026)

- **Android is ready for testing with friends.** A stability audit found
  45 issues; all are fixed except the ones listed as known limits below.
  The quality refactor is done: the service was split into classes and the
  UI into `UiSession` and `TunnelController`. The latest build is
  `KirovVPN-1.0.54.apk` on the `build-claude-compassionate-mayer-6jph8m`
  release, signed with a temporary key.
- **iPhone:** phases 1–2 of `docs/ios/PLAN.md` are done. The Go core builds
  for iOS, and `ios-app.yml` builds the unsigned app and packet tunnel and
  passes its checks (geo files in the extension, no bitcode). The app is
  still the skeleton: paste a key, connect, disconnect.
- **Panel:** the scripts are written and pass the Docker e2e, but they are
  not installed on real servers yet. Friends can be added in the web form
  too (see `tidy-users` above), so the owner needs no custom admin UI.
- The work of `claude/compassionate-mayer-6jph8m` goes to `main` through a
  PR from `claude/pensive-gates-mddsan`. Once it is merged, start new work
  from `main`.
- The owner's servers: the current VPN (`install.sh`, one shared key) runs
  on a VPS in Germany; the owner also has a Beget VPS in Russia.

## Next steps

1. The owner creates the permanent Android signing key on Windows
   (`docs/README.ru.md`, section 9: `keytool` from Temurin JDK, secrets
   `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD`). Then:
   - check for "Release key configured" in the CI log;
   - make the first stable build;
   - register `com.klausms.vpn` for Google developer verification with that
     key.
2. iPhone phases 4–6 (the real tunnel provider, the memory watchdog, the
   screens and feature parity) can move on in unsigned CI builds while
   signing waits; testing on a phone needs step 3.
3. The owner buys the Apple Developer Program ($99/year). Then follow
   `docs/ios/PLAN.md` section 8 (identifiers, certificate, API key, GitHub
   secrets), then phase 3 (signing, Ad Hoc via the App Store Connect API
   tool in the drafts), then phases 4–6 (a working tunnel within about
   50 MB, then feature parity).
4. The owner installs the panel (`docs/README.ru.md`, section 12): VPS and
   domain, `install-panel.sh`, `klaus-panel telegram-setup`, nodes. Running
   the installers again later applies `connIdle` 1800 and the new
   whitelist-note texts. The panel publishes only `KirovVPN-*.apk` from the
   `stable` release, so it needs a stable build first.
   - The panel goes on its own VPS abroad, not on the German VPN server: a
     block of that IP would take the subscription address down with it.
   - The German server becomes a node with `MIGRATE=1` once friends have
     their links (the old shared key stops then); a second node at another
     hoster lets the apps switch by themselves.
   - The Beget VPS is not needed: a Russian exit bypasses nothing, and a
     panel there would put friends' data under Russian requests and its
     links to the nodes behind TSPU. Later it might be a whitelist relay.
5. The owner once pasted a Telegram bot token into a chat. Make sure he
   revoked it (@BotFather → /revoke) and entered the new one only on the
   panel.
6. The owner agreed to move the work to `main` and merges the PR. The
   weekly geo rebuild runs only on the default branch.
7. Known limits, documented:
   - UDP flows outlive `Stop`, and a UDP socket is routed by its first
     packet (audit 41/42);
   - DNS for names never seen before still waits while the server is down
     (21, partly fixed);
   - candidate servers are tested only with the short 204 check, not for
     stalls (5, partly fixed).
