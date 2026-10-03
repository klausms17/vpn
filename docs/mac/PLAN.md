# Kirov VPN for Mac: plan (3 Oct 2026)

**Status (3 Oct 2026):** planned, not built. The owner asked for a Mac app
"from old MacBooks to the newest", with accounts
(`docs/accounts/PLAN.md`) from its first version. A research report on
3 Oct (sources in section 1) decided the design.

## 0. Decisions in brief

- **Reach: macOS 13 Ventura or later, Intel and Apple silicon.**
  - Every build is universal (arm64 + x86_64).
  - The floor is the Go toolchain's, not ours. Xray-core needs Go 1.27
    (its `go.mod`, from v26.9.8 of 8 Sep 2026), and Go 1.27 binaries need
    macOS 13. Go 1.25 and 1.26 still ran on macOS 12. Holding the Mac on
    an old Xray with Go 1.26 would end with that toolchain's support in
    Feb 2027, so it is not done.
  - Macs this reaches:
    - MacBook, MacBook Pro and iMac from 2017;
    - MacBook Air and Mac mini from 2018;
    - every Apple silicon Mac.
  - Left out: Macs that stop at Monterey (MacBook Air up to 2017,
    MacBook Pro 2015–2016, MacBook 2016, iMac 2015, Mac mini 2014).
  - macOS 26 Tahoe is the last one for Intel Macs; macOS 27 runs only on
    Apple silicon.
- **Design: the Windows app, Mac-shaped (design B of section 2).**
  - **The service.** A root LaunchDaemon in Go runs libxray with Xray's
    own `tun` inbound on a utun. It holds every rule of the Windows
    service: servers, subscriptions, settings, checks, the journal and
    the account.
  - **The app.** A per-user menu-bar icon and window (Wails v3 on
    WKWebView), reusing the Windows window's page. It talks to the
    service over a Unix socket and holds no keys.
- **Runs without Apple's paid program.**
  - At first the installer is an unsigned `.pkg`. On first use a friend
    clicks «Всё равно открыть» (Open Anyway) once in System Settings and
    enters the Mac's password twice (section 6).
  - Once the owner has the Apple Developer Program (also needed for the
    iPhone), the same `.pkg` is signed with Developer ID and notarized,
    and the warnings go.
  - No Network Extension entitlement or provisioning profile is needed.
- **Tested in CI on GitHub's macOS runners** (`mac.yml`), as `windows.yml`
  tests Windows: install, connect through a local REALITY server, DNS and
  leak checks, sign in to an account, uninstall. Nobody on the team has a
  Mac, so CI is the main check before a friend tries a build.
- **Kept for later: design A**, a Network Extension system extension
  sharing the iPhone app's Swift code. It goes first if B's DNS or sleep
  handling proves weak on friends' Macs. The Go engine stays independent
  of how packets reach it, so A can reuse it.
- **Names.**
  - Display name «Kirov VPN»; bundle `Kirov VPN.app` in `/Applications`.
  - Identifiers new on the Mac take the new name: daemon label
    `com.kirovvpn.service`, socket `/var/run/kirovvpn.sock`, data in
    `/Library/Application Support/Kirov VPN`.
  - Kept, because the panel depends on them: `klausvpn://` and the
    User-Agent `KlausVPN/1.0.N (macOS)`.

## 1. What was checked (3 Oct 2026)

| Question | Finding | Decided |
|---|---|---|
| Lowest macOS for Go | Go 1.24 runs on macOS 11+, 1.25 and 1.26 on 12+, 1.27 (19 Aug 2026) on 13+ (https://go.dev/doc/go1.27#darwin, https://go.dev/issue/75836) | macOS 13; Xray and this repo need Go 1.27 |
| Which Macs run 13 | MacBook Pro and iMac 2017+, MacBook Air and Mac mini 2018+ (https://support.apple.com/102861); Tahoe is the last macOS for Intel | Universal builds while Intel Macs remain |
| A Network Extension outside the App Store | Must be a system extension, signed with Developer ID, notarized, with Developer ID provisioning profiles, the app in /Applications, and approval in System Settings (Apple TN3134) | Needs the paid program before anyone can run it: design A waits |
| How other clients do it | sing-box SFM and Karing use system extensions (signed). Clash Verge Rev and V2rayU use a root LaunchDaemon. FlClash and Clash Party use a setuid core. Hiddify sets a system proxy | B is the common way without signing |
| Unsigned apps on macOS 15 and later | Control-click no longer opens them. Privacy & Security → «Всё равно открыть», then the admin password. An unsigned `.pkg` that installs a LaunchDaemon works this way, and files it installs are not quarantined | B can ship unsigned |
| Wails v3 on macOS | Supported (WKWebView, cgo). Universal builds via `darwin:package:universal`. The template targets 12.0, but our Go 1.27 binary needs 13 | The window works; its page needs a check on WebKit |
| gomobile for macOS | `-target=macos` builds arm64 and amd64 slices | Only needed for design A |
| DNS with a utun | Xray adds the routes and binds its outbounds to the physical interface. It leaves the system DNS alone on macOS. Mullvad and vpnc-script publish the tunnel's DNS in the SystemConfiguration dynamic store; Mullvad blocks leaks with a PF anchor | Temporary dynamic-store keys and a PF anchor (section 3) |
| CI | GitHub's macOS runners allow sudo and utun; Tailscale's own action starts `tailscaled` with a utun there | A smoke test like Windows' |

Risks found:
- **Apple's program from Russia.** Forum threads of 2026 report enrolment
  from Russia being refused; this is uncertain. It matters for the iPhone
  too.
- **Open Anyway.** Apple may tighten it again, so signing remains the goal.

## 2. Architecture

### 2.1 Programs

| Program | Runs as | Holds keys | Does |
|---|---|---|---|
| `kirovvpn-service` (in the app bundle, run by launchd from `/Library/LaunchDaemons/com.kirovvpn.service.plist`) | root | yes, in files only root can read | Everything the Windows service does: the core, the tunnel, servers, subscriptions, settings, checks, the account, the journal, DNS and PF |
| `Kirov VPN.app` | the signed-in user | no | Menu-bar icon and window, `klausvpn://` links |
| `Kirov VPN.pkg` | Installer (root) | no | Installs both and loads the daemon; an uninstall script removes them |

### 2.2 Shared with Windows

The Windows module (`windows/`) is already mostly platform-neutral. Phase
M0 makes it the desktop module for both:
- **Platform-neutral already:**
  - `internal/engine` (start, stop, retries, the hold);
  - `internal/ipc` (protocol, server, client);
  - `internal/service`: handler, settings, pinger, subscriptions, account,
    runtime, config;
  - `internal/ui`: bridge, link, deeplink, origin, tray logic, the page;
  - `libxray/client/*`.
- **Windows-only, behind build tags, each gaining a Mac sibling:**
  - the pipe (Unix socket);
  - `netbind` (`IP_BOUND_IF` instead of `IP_UNICAST_IF`);
  - `winsys` (data folder, DPAPI, MachineGuid, version);
  - the WFP hold (PF anchor);
  - `explain` and `tunaddr` (Windows errors);
  - the service wiring (launchd instead of the SCM);
  - the window's proxy check (`networksetup` instead of the registry);
  - the tray menu.

### 2.3 The socket

- `/var/run/kirovvpn.sock`, mode 0666, owned by root.
- Each connection is checked with `getpeereid`. Only users with a console
  session (the person at the Mac) and root are served, the counterpart of
  the pipe's interactive-users rule.
- The protocol and its bounds are the pipe's, unchanged: sizes, rates, one
  import at a time, 256 requests in all. Every rule of the Windows service
  about input from every user of the PC applies, including
  `libxray/internal/privileged`.

### 2.4 Data

- `/Library/Application Support/Kirov VPN/data`, root-only (0700): keys,
  settings, subscriptions, the account's session.
- No DPAPI: on the Mac, file permissions do that job, as on Android.
- The device id for the panel (`X-Hwid`) is the IOPlatformUUID hashed with
  Android's recipe, kept in the data folder.

## 3. The tunnel on macOS

- **Core.**
  - Xray's `tun` inbound creates the utun (`tun_darwin.go`, with a kqueue
    wait).
  - `autoSystemRoutingTable` routes IPv4 through it in eight prefixes, so
    the default route of the Mac stays untouched.
  - `autoOutboundsInterface` binds the core's own connections to the
    physical interface and follows route changes.
- **The service's own requests** (subscriptions, the account, checks,
  updates) are bound with `IP_BOUND_IF` by the Mac `netbind`. Their names
  are resolved with the physical network's DNS servers, read from the
  dynamic store, as the Windows binder does.
- **DNS.**
  - While connected, the service publishes the tunnel's DNS server as the
    primary service in the SystemConfiguration dynamic store, with
    temporary keys (`SCDynamicStoreAddTemporaryValue`). macOS removes them
    when the service's session ends, so a crash leaves nothing behind.
  - The DNS cache is flushed on connect and disconnect.
  - Nothing is written to the user's network settings (`networksetup`), so
    nothing outlives the app.
- **Leaks.** A PF anchor, `com.kirovvpn`, the counterpart of the WFP
  filters:
  - DNS (53, 853) leaves only through the utun, except the service's own;
  - IPv6 outside the tunnel is refused at once;
  - during a restart, the hold lets only the service's traffic through,
    for at most 20 s, as on Windows.
  - The anchor is flushed when the service stops, and at its start in
    case of a crash.
- **Sleep and network changes.**
  - The service listens to IOKit's power notifications and the route
    socket, and restarts the tunnel after a wake or a change of network,
    as on Windows.
  - Its settle and retry rules are the engine's.
- **IPv6.** As on Windows: the tunnel carries IPv4, so IPv6 fails at once
  and programs fall back to IPv4.

## 4. The window

- **The same page as Windows:** the rail, servers in groups with flags,
  search, settings, journal, account and the orb. On macOS it runs in
  WKWebView, so CSS that only Chromium knows is checked on WebKit in CI
  screenshots.
- **The menu-bar icon** replaces the tray icon: state, connect or
  disconnect, servers, open the window.
- **`klausvpn://`** is registered by the app's `Info.plist`. The
  confirmation dialog is the Windows one.
- **«Убрать прокси»** finds a proxy another VPN program left in the
  system's network settings, as on Windows. The change needs the user's
  password.

## 5. Installer and updates

- **The `.pkg`**, built on a macOS runner with `pkgbuild` and
  `productbuild`, installs:
  - the app into `/Applications`;
  - the daemon's plist into `/Library/LaunchDaemons`;
  - a postinstall script that loads the daemon.
- **Uninstall** is a script in the app («Удалить Kirov VPN» in the
  menu). It unloads the daemon and removes the plist, the data and the PF
  anchor.
- **Updates.** The panel publishes the `.pkg` under `/app/mac/` with a
  `version.json`, signed in CI with the same Ed25519 key as Windows
  (`WINDOWS_UPDATE_KEY`, renamed to `DESKTOP_UPDATE_KEY` when M4 comes).
  The daemon checks it, and after «Обновить» runs `installer -pkg` itself.
  As root, this needs no Open Anyway again.

## 6. What a friend does, unsigned

1. Downloads `KirovVPN-1.0.N.pkg` from the friend page and opens it. macOS
   says it cannot check it: «Готово».
2. System Settings → Конфиденциальность и безопасность →
   «Всё равно открыть» → the Mac's password.
3. Installer → «Установить» → the password again.
4. macOS notes «Добавлены объекты для фоновой работы»: nothing to do.
5. Kirov VPN opens; the friend adds the link, or signs in to the account.

Once signed and notarized, steps 1–2 go away.

## 7. CI (`mac.yml`)

- **Build on `macos-latest`:**
  - libxray's tests;
  - the desktop module's tests on darwin;
  - universal `kirovvpn-service` and app with Wails, ad-hoc signed;
  - the `.pkg`.
- **Smoke test** on the runner (sudo), as `windows.yml` does:
  - install;
  - connect through a local REALITY server;
  - DNS only through the tunnel, IPv6 refused;
  - the journal without addresses;
  - a subscription and an account against the test server;
  - 50 restarts without leaks;
  - the service killed and back;
  - uninstall leaving no utun, PF anchor or DNS key.
- **Screenshots** of the window on WebKit.
- **Releases:** `mac-build-<branch>`, and `mac-stable` for a `v*` tag or a
  manual run with «stable», as for Windows.

## 8. Phases

- **M0. One desktop module.**
  - `windows/` becomes `desktop/`; Windows-only code stays behind build
    tags.
  - CI stays green, and Windows behaves as before.
  - No Mac code yet.
- **M1. The tunnel on a Mac.**
  - The daemon with the socket, data folder, utun, DNS keys, the PF
    anchor, sleep and network changes.
  - `kirovctl` for darwin.
  - `mac.yml` with the smoke test of section 7.
  - The window's code runs, without the menu-bar polish.
- **M2. The app.** The menu-bar icon, the window on WebKit, `klausvpn://`,
  «Убрать прокси», the `.pkg`, uninstall, and screenshots. A friend with a
  Mac tries the unsigned build (section 6).
- **M3. Signing.** Once the owner has the Apple Developer Program:
  - Developer ID Application and Installer certificates in GitHub secrets;
  - notarization with an App Store Connect API key (`notarytool`);
  - stapling.
- **M4. The panel side.** `publish-mac`, a Mac tab on the friend page, and
  updates from `/app/mac/`.

Accounts, subscriptions, settings and the journal come with M1–M2: they
are the shared service code, already tested on Windows.

## 9. Owner steps

- **Now:** none.
- **For M2:** a friend with a Mac on macOS 13 or later to try the unsigned
  build.
- **For M3:** the Apple Developer Program. It is the same program the
  iPhone app needs. The steps come with `docs/ios/PLAN.md` section 8: one
  enrolment covers both, plus two Developer ID certificates for the Mac.
