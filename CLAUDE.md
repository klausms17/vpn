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
- `windows/`: the Windows app, a Go module of its own on top of `libxray`.
  - `cmd/kirovvpn-service`: the service (LocalSystem). `internal/service`
    wires `internal/engine` (start, stop and retries, Android's
    `TunnelEngine`), `internal/netbind` (keeps the service's own sockets
    and DNS outside the tunnel), `internal/ipc` (the protected pipe to the
    window) and `internal/winsys` (the data folder
    `C:\Program Files\Kirov VPN\Data`, DPAPI). In `internal/service`:
    `handler.go` answers the window, `settings.go` checks the settings
    and holds the torrent clients, `pinger.go` checks servers,
    `explain.go` turns the core's start errors into advice and
    `tunaddr_windows.go` finds or frees an adapter holding the tunnel's
    address.
  - `cmd/kirovvpn`: the tray icon and window (Wails v3, `internal/ui`; the
    page is `internal/ui/frontend`, ES modules with one per part and round
    flags in `flags/`; `origin.go` refuses calls from any other page;
    `deeplink.go` keeps a `klausvpn://` link until the user answers;
    `proxy.go` finds a proxy another VPN program left on a local port
    where nothing listens and offers to remove it). It holds no keys.
  - `installer/KirovVPN.iss` (Inno Setup), `test/smoke.ps1` (CI only),
    `cmd/kirovctl` (the smoke test's pipe client, never shipped), `tools/`
    (icons, exe resources, a test server, `othervpn` for CI).
- `libxray/client/`: the Android logic ported to Go with its tests (model,
  store, key import, links in text and `klausvpn://` links, subscriptions
  with the device headers, log, tunnel rules), used by Windows and later
  iOS.
  `libxray/internal/privileged` is the allowlist of what the Windows
  service's core may run, and `internal/redact` takes addresses and host
  names out of the logs (the core's `xray.log` too, on every platform).
- `ios/`: the iPhone app. `project.yml` is the XcodeGen spec; `App/`,
  `PacketTunnel/` and `Shared/` hold the code. The plan is in
  `docs/ios/PLAN.md` and the drafts for later phases are in
  `docs/ios/drafts/`.
- `server/`:
  - `install.sh`: a standalone Xray VLESS+REALITY server.
  - `install-relay.sh`: a Russian relay for mobile "whitelist" mode.
  - `remnawave/`: the panel for friends. `install-panel.sh` and
    `install-node.sh` install it; `klaus-panel` is the owner's CLI (friends,
    nodes, publish-apk and publish-windows, telegram-setup; publishing
    needs `GITHUB_TOKEN` only for a private repository); `klaus-monitor.py` turns block
    reports from the app into Telegram alerts; `klaus-page.html` is the
    light iOS-style page a friend's browser gets for the link (Caddy serves
    it for `Accept: text/html`, apps still get their list from Remnawave;
    tabs Android, iPhone and Windows, the device's own opens; iPhone says
    «Скоро», Windows too until `publish-windows` has put the installer on
    `/app/windows/`). `klaus-panel write-page` puts it in place with the APK and
    support links, and a timer runs `klaus-panel update-page` every 15
    minutes: the page in `main` (`PAGE_BRANCH`) reaches friends with
    nobody on the server, so a page change is live once it is in `main`.
    A page that is not whole is refused, and `android.yml` checks that
    the page in a branch is one the panel takes. The page must work with
    the panel as installed: it only reads files on `/app/`.
    `test/` holds the Docker end-to-end test and the monitor unit tests.
  - The owner may add friends in the panel's web form instead of the CLI.
    That form starts with no squad and an end date of tomorrow, so a
    systemd timer runs `klaus-panel tidy-users` every 20 seconds: users
    made in the last two days get the `KlausVPN` squad and no end date.
  - Nothing about friends is kept beyond what the panel needs (name, link,
    traffic totals, last time online, and their phones for the device
    limit): Caddy hands 127.0.0.1 instead of their IP to everything behind
    it, the subscription page has no log, `.env` switches off the
    subscription-download and per-day traffic history
    (`SERVICE_DISABLE_*`), and the profile gives the nodes' Xray no access
    log and masked addresses.
- `scripts/`: `build-libxray.sh` (AAR), `build-libxray-ios.sh`,
  `fetch-geo.sh`, `prepare-geo.sh`.
- `docs/windows/PLAN.md`: the plan for the Windows app (section 1 lists
  what was checked and decided; section 10 the phases and what is built).
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
  - `windows.yml` builds the Windows programs on Linux, runs libxray's
    tests on a Windows runner, builds the installer, installs it there,
    connects through a local REALITY server, checks DNS and IPv6, restarts
    the tunnel 50 times and uninstalls. It publishes
    `windows-build-<branch>` with `KirovVPN-Setup-1.0.<run>.exe`, and
    `windows-stable` for a `v*` tag or a manual run with "stable" ticked.
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
  - Windows: `cd windows && go vet ./... && GOOS=windows go vet ./... && go test -race ./...`,
    and `cd libxray && go test -race ./client/...`. The installer, the
    core's tests on Windows and the smoke test run only in `windows.yml`.
  - Panel: start Docker (`dockerd &`; install `iproute2` if `ip` is
    missing), then `WORK=/tmp/rw bash server/remnawave/test/run-local.sh`.
    It takes about 25 minutes and must end with `ALL CHECKS PASSED`.
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
  - The Windows service runs as SYSTEM and takes input from every user of
    the PC: it runs only outbounds that pass `libxray/internal/privileged`,
    and the pipe stays bounded (sizes, rates, one import at a time). After
    an Xray bump, check its new outbound features against the allowlist.
  - Never commit secrets.
  - Never ask the owner to paste keys, passwords, tokens or UDIDs into a
    chat. Those go into GitHub secrets or the panel's hidden prompts.
- Commits: a short imperative title and a body that says what changed and
  why.

## Status (3 Oct 2026)

- **Android is ready for testing with friends.** A stability audit found
  45 issues; all are fixed except the ones listed as known limits below.
  The quality refactor is done: the service was split into classes and the
  UI into `UiSession` and `TunnelController`. The latest build is
  `KirovVPN-1.0.66.apk` on the `build-claude-panel-privacy` release (Xray
  v26.9.30, the core's log without addresses and names, bounded user
  rules), signed with a temporary key.
- **Windows:** phase 1 of `docs/windows/PLAN.md` is built (1 Oct 2026):
  the service with Xray's TUN and WFP leak filters, the window and tray,
  the installer and `windows.yml`.
  - Two independent security reviews followed. Fixed: keys or a pasted
    config could make the SYSTEM service write files (xdrive), keep TLS
    key logs, open a VLESS `reverse` into the LAN or send everything past
    the server, so the service now runs only what share links make; one
    import could make it fetch thousands of certificates; long server
    names locked every window out; a user could keep the service from
    starting by creating its ProgramData folder first, so the data moved
    to `Program Files\Kirov VPN\Data`; a page the window was led to could
    call the service; site names and addresses in `xray.log`; a bug in the
    engine ended the service.
  - The owner tried 1.0.5 on 1 Oct: it connected, then a later start
    failed with a core error that was gone an hour later. He asked for
    ping, settings (a preset plus his own rules, more for advanced users)
    and a journal in the app. Built the same day, with a third review:
    - a failed start is retried (twice for the user's, about two minutes
      for one nobody asked for) and explained: another VPN's connected
      adapter holding the tunnel's address is named and refused at once,
      IPv6 switched off, the adapter not ready, WFP; the address is freed
      from other VPNs' disconnected adapters;
    - «Серверы»: each server's check (graded as Android's), select,
      rename, delete, «Проверить все»; checks run through the tunnel's
      controller, never as cores of their own (the review found those
      taking the tunnel's log, DNS and outbounds);
    - «Настройки»: Android's three modes, own sites directly, through the
      VPN or blocked, programs directly or through the VPN, «Торренты без
      VPN» (on by default; the owner should confirm), connect at boot;
      bounded in size, applied at once, only a routing change restarts;
    - «Журнал»: the service's, the core's and the window's logs, without
      addresses or host names, with «Скопировать»;
    - a WFP hold: while a tunnel that was up restarts, only the
      service's own traffic passes, for at most 20 s; should the engine
      hang, the hold ends by itself after 25 s.
  - CI (`windows.yml`) passes: libxray's tests on Windows, and the smoke
    test (refuse a folder outside Program Files, install, the pipe's
    security, refuse a file-writing key, connect through a local REALITY
    server, DNS filter, IPv6, the server check, the journal without
    addresses, a site and a program sent directly, no DNS around the
    tunnel during a restart (0 of 72 probes), 50 restarts in 164 s with
    515 → 517 handles and 19 → 19 threads, the service killed while
    connected and back, another VPN holding the address named at once,
    install over itself, uninstall). The installer is
    `KirovVPN-Setup-1.0.11.exe` on the `windows-build-claude-panel-privacy`
    release; the owner has not tried it yet. On 2 Oct he asked whether the
    app could have broken his PC's network settings (internet trouble that
    day): it changes nothing that outlives the tunnel; he was given steps
    to tell the app from his ISP.
  - On 3 Oct the owner asked for a desktop look like Happ's: the window
    was a phone-sized column. Built with phase 2a of the plan:
    - the window: a rail (Серверы, Добавить, Настройки, Журнал, О
      программе), the servers in groups with round flags, search, usage
      and the panel's notices, and the connection on the right (the orb,
      the mode, the current server); checked in a browser with a fake
      service at 1080×700 and 900×600;
    - subscriptions as Android's (`libxray/client/subscription`, the
      device id from the MachineGuid with Android's recipe), refreshed 30 s
      after the service starts, every hour and when a window opens,
      directly and then through the tunnel; bounded for the shared
      service (20 subscriptions, links of 1000 characters, the panel's
      texts of 500);
    - `klausvpn://` registered by the installer; the window names the
      subscription's host and asks before adding;
    - the smoke test adds and refreshes a subscription served over HTTPS
      and checks the device headers and the link registration;
    - an independent review found, and the fixes cover: a `klausvpn://`
      link inside another got past the dialog; a refresh cancelled
      halfway, or one past 16 servers that need a pinned certificate,
      dropped servers; a far-off `expire` from a panel stopped every
      window; a panel's error texts were unbounded; queued refreshes
      outlived their window; the dialog could answer for a newer link;
    - merged into `main` the same day
      (https://github.com/klausms17/vpn/pull/3).
  - The same day the owner found Claude Code failing on his PC under our
    VPN with ECONNREFUSED while Happ worked. Our tunnel accepts every
    connection (gVisor completes the handshake, IPv6 is denied, not
    refused), so a refusal means a program set to a proxy on a local port
    where nothing listens: Happ's, used by Claude Code. He got the manual
    fix, and the window now finds such a proxy (Windows' own, or the
    user's `HTTP(S)_PROXY`/`ALL_PROXY`) while connected, shows it and
    removes it with «Убрать прокси» (variables for all users only named:
    they need an administrator).
- **iPhone:** phases 1–2 of `docs/ios/PLAN.md` are done. The Go core builds
  for iOS, and `ios-app.yml` builds the unsigned app and packet tunnel and
  passes its checks (geo files in the extension, no bitcode). The app is
  still the skeleton: paste a key, connect, disconnect.
- **Panel:** installed on the owner's own panel VPS on 28 Sep 2026; the old
  German VPN server is registered as node `de-1` but not switched over yet
  (`MIGRATE=1`, once friends have their links). The privacy changes and
  `klaus-page.html` of 28 Sep (see the map above) pass the Docker e2e
  (1 Oct 2026, run in a cloud session; never run it on the live panel).
  Friends can be added in the web form too (see `tidy-users` above), so the
  owner needs no custom admin UI.
- `claude/panel-privacy` was merged into `main` on 3 Oct 2026
  (https://github.com/klausms17/vpn/pull/2). Start new work from `main` on
  a new branch. On 3 Oct the owner told Claude to merge its own PRs
  («да, сливай сам»): merge with a merge commit once CI is green. Builds
  of `main` go to the `build-main` and `windows-build-main` releases.
- The owner's servers: the current VPN (`install.sh`, one shared key) runs
  on a VPS in Germany; the owner also has a Beget VPS in Russia.

## Next steps

1. The owner creates the permanent Android signing key on Windows
   (`docs/README.ru.md`, section 9, with PowerShell commands: `keytool`
   from Temurin JDK, secrets `ANDROID_KEYSTORE_BASE64` and
   `ANDROID_KEYSTORE_PASSWORD`; he got the steps on 3 Oct, when he asked
   for the friend page's Android download). Then:
   - check for "Release key configured" in the CI log;
   - make the first stable build;
   - register `com.klausms.vpn` for Google developer verification with that
     key.
2. **Windows desktop app**, the owner's next priority (before the Apple
   Developer Program): the same functions as the Android app, and code that
   is professional, fast and maintainable. The plan is
   `docs/windows/PLAN.md` (checked 1 Oct 2026); build it in its phases,
   each ending in a CI-built installer the owner tries. Phase 1, the tunnel
   on the owner's PC, is built with what the owner asked for after his
   first try (servers with checks, settings, journal, the restart hold);
   the owner tries it (the checks are in the plan's section 10). Phase 2a
   (subscriptions, `klausvpn://`, the desktop window) is built, with the
   panel's `publish-windows` and the page's Windows tab; next: the owner
   tries the new build, then a `windows-stable` build for friends (only
   when he says so: Actions → Windows → Run workflow with «stable»), then
   phase 2b, the failover logic. In brief:
   - Go only. An elevated service (LocalSystem) holds libxray and the
     Android logic, ported to `libxray/client/` with its JVM tests. A
     per-user tray icon and window (Wails v3, a pinned beta, on WebView2)
     talk to it over a protected named pipe; the window holds no keys.
   - Xray's own wintun TUN with Android's IPv4 routes and DNS, no local
     ports. Xray v26.9.30 or later for its WFP filters
     (`autoSystemWfpBlockLeak: ["dns", "misconfigtun"]`). libxray's own
     socket binder and resolver keep the service outside its tunnel
     (Windows has no `addDisallowedApplication`).
   - Inno Setup installer: service, `klausvpn://`, autostart. Updates come
     from the panel's `/app/windows/`, signed in CI with the
     `WINDOWS_UPDATE_KEY` secret, installed silently by the service.
   - Unsigned at first (SmartScreen, Smart App Control); the owner decides
     on signing (a licence plus SignPath, or a paid certificate) before a
     wide rollout.
   - Then the panel side: `publish-windows` and a Windows tab on the
     friend page.
3. iPhone phases 4–6 (the real tunnel provider, the memory watchdog, the
   screens and feature parity) can move on in unsigned CI builds while
   signing waits; testing on a phone needs step 4.
4. The owner buys the Apple Developer Program ($99/year). Then follow
   `docs/ios/PLAN.md` section 8 (identifiers, certificate, API key, GitHub
   secrets), then phase 3 (signing, Ad Hoc via the App Store Connect API
   tool in the drafts), then phases 4–6 (a working tunnel within about
   50 MB, then feature parity).
5. The panel is installed (see Status). Still open:
   - No node serves it yet, so friends' links connect nowhere. The old
     German VPN server runs the owner's own admin panel with per-friend
     keys that many iPhone friends use, so it keeps working until the
     iPhone app exists. Recommended: a new VPS abroad becomes the first
     node (then `klaus-panel disable-node de-1` until de-1 is switched).
     The other way, de-1 on another port next to the old VPN (8443 is free
     there), first needs `klaus-panel setup` to move existing hosts to a
     changed `REALITY_PORT`, which it does not do yet.
   - de-1 becomes a node with `MIGRATE=1` once friends have their links.
     Find out first what the old admin panel is: `install-node.sh` only
     stops the Xray of `install.sh`.
   - `klaus-panel telegram-setup`, a backup, and deleting the test user
     `test` (its link was posted in a chat).
   - The panel publishes only `KirovVPN-*.apk` from the `stable` release
     and the Windows installer from `windows-stable`, so each needs a
     stable build first (no token while the repository is public). The owner updates the panel with `cd ~/vpn && git pull &&
     sudo bash server/remnawave/install-panel.sh`.
   - On 3 Oct the owner was asked to run once on the panel server
     `cd ~/vpn && git checkout main && git pull && sudo bash
     server/remnawave/install-panel.sh`: it moves the checkout to `main`
     and adds the page timer, the tokenless `publish-apk` and the Windows
     tab. From then on a page change merged into `main` reaches friends by
     itself. He ran it the same day («готово, панель работает»). Changes
     to the panel itself, such as `publish-windows`, still need that
     command once.
   - The Beget VPS is not needed: a Russian exit bypasses nothing, and a
     panel there would put friends' data under Russian requests and its
     links to the nodes behind TSPU. Later it might be a whitelist relay.
6. The owner once pasted a Telegram bot token into a chat. Make sure he
   revoked it (@BotFather → /revoke) and entered the new one only on the
   panel.
7. Known limits, documented:
   - UDP flows outlive `Stop`, and a UDP socket is routed by its first
     packet (audit 41/42);
   - DNS for names never seen before still waits while the server is down
     (21, partly fixed);
   - candidate servers are tested only with the short 204 check, not for
     stalls (5, partly fixed);
   - Windows: while the core restarts (a reset after a network change or
     sleep, a change of server or settings, a retry after a failure) a WFP
     hold lets only the service's own traffic through, for at most 20
     seconds; a tunnel not back by then lets traffic go directly until it
     is;
   - Windows: with fast startup (on by default) a shutdown and power-on
     resumes the service as after sleep, so «Подключаться при запуске
     Windows» switched off does not apply then (phase 4).
