# Kirov VPN for Windows: implementation plan (checked 1 Oct 2026)

**Status (1 Oct 2026):** phase 1 (section 10) is built and checked by `windows.yml`; the owner tries its installer next. The sections below describe it as built. Five research reports went into it: the Android app's features, libxray and Xray on Windows, the panel side, Windows networking and services, and the UI, installer, signing and CI. Section 1 lists where they disagreed and what decided it.

## 0. Decisions in brief

- **Target.** Windows 10 (1809 or later; tested on 22H2) and Windows 11, x64 first. arm64 builds come from the same sources in phase 6.
- **Same functions as Android, Windows-shaped.** Section 3 maps every Android feature. The tray icon replaces the notification, tile and widget. Per-program bypass comes in phase 4; the mobile whitelist mode not at all.
- **Two programs, both Go.**
  - `KirovVPNService.exe`: a Windows service running as LocalSystem. It owns the store, the core and every rule of the Android VPN process: health checks, stall detection, failover, server switching, subscription refresh, block reports and network changes.
  - `KirovVPN.exe`: one per signed-in user, unprivileged. It is the tray icon and the window (Wails v3 on WebView2, plain HTML/CSS/JS without npm, in the look of `server/remnawave/klaus-page.html`). It holds no keys and does no networking; it talks to the service over a named pipe.
- **Tunnel.** Xray's own `tun` inbound on wintun: the same gVisor netstack as Android and iOS, with libxray used as a plain Go package. The adapter gets Android's IPv4 address, routes and DNS (`TunSettings`). There are no local proxy ports.
- **The service stays outside its own tunnel,** as Android's `addDisallowedApplication(self)` keeps the app out:
  - every socket of the service is bound to the physical interface (`IP_UNICAST_IF`);
  - its own name lookups go to the physical network's DNS servers, never through Windows' DNS Client, which would send them into the tunnel.
- **No DNS or IPv6 leaks.** Xray's WFP filters, new in Xray v26.9.30 of 30 Sep 2026 (`autoSystemWfpBlockLeak: ["dns", "misconfigtun"]`), do both:
  - DNS leaves only through the tunnel, for every program except the service;
  - the tunnel carries IPv4 only, and IPv6 is blocked outside it, so IPv6 fails at once and programs use IPv4. Android reaches the same result differently: it routes IPv6 into its tunnel and refuses it there. On Windows that would let gVisor accept an IPv6 connection before refusing it, which can defeat a browser's fallback to IPv4.
  - The filters live in a dynamic WFP session, so they vanish if the service dies.
- **Logic.** The Kotlin logic of the VPN process and the data layer is ported to Go packages under `libxray/client/`, together with its JVM tests. Windows uses them directly; iOS phase 5 later reuses them through a thin gomobile facade. Android stays on Kotlin.
- **Installer.** Inno Setup (preinstalled on `windows-2025` runners), per machine. It installs the service, the `klausvpn://` protocol, autostart of the tray app for every user, and the WebView2 runtime when it is missing.
- **Updates.**
  - The panel publishes the installer under `/app/windows/` with its own `version.json`.
  - The service checks it every 12 hours. After the user clicks «Обновить», it downloads the installer and checks its size, SHA-256 and an Ed25519 signature made in CI. The signing key exists only in GitHub secrets.
  - It then runs the installer silently: no UAC prompt, and it also works for standard users.
- **Identity.**
  - User-Agent `KlausVPN/1.0.N (Windows)`, so the panel's `^KlausVPN/` rule matches.
  - `X-Hwid` = first 32 hex characters of SHA-256(`"klausvpn-hwid-v1|"` + MachineGuid), Android's recipe with a different input.
  - `X-Device-Os: Windows`; `X-Ver-Os` such as `11 24H2`; `X-Device-Model` from the BIOS maker and product.
- **Unsigned at first.** SmartScreen warns once per downloaded version, and Smart App Control blocks unsigned programs outright. The signing options are in section 7.4.
- **CI.** `windows.yml` builds both programs on Linux, runs libxray's tests on `windows-2025`, builds the installer and runs install, connect and uninstall smoke tests there. Releases:
  - pre-release `windows-build-<branch>`;
  - `windows-stable`, made by a `v*` tag or a manual run with «stable» ticked.
- **Names.** Display name «Kirov VPN». Identifiers that exist only on Windows take the new name: service `KirovVPN`, pipe `\\.\pipe\ProtectedPrefix\Administrators\KirovVPN\control`, folders `Kirov VPN`, adapter «Kirov VPN». Kept from before, because the panel and the friend page depend on them: `klausvpn://` and `KlausVPN/` in the User-Agent.

## 1. The proposal and the reports, checked

The proposal is item 2 of "Next steps" in `CLAUDE.md`.

| # | Topic | Proposed or reported | What I checked | Decision |
|---|---|---|---|---|
| 1 | Go, libxray as a plain package | proposal | `GOOS=windows GOARCH=amd64` and `arm64`, `CGO_ENABLED=0`: `go build .`, `go vet ./...` and `go test -c` of libxray all pass unchanged. | Yes. No gomobile on Windows. |
| 2 | wintun into the same netstack | proposal | Xray `proxy/tun/tun_windows.go`: wintun adapter with an md5(name) GUID, 8 MiB ring; `gateway` → addresses, `autoSystemRoutingTable` → on-link routes with metric 0, interface metric 0, MTU, `dns` → adapter DNS. libxray's tun settings carry only `name` and `mtu`, because Android's VpnService owns the interface. | Yes. The Windows config adds `gateway`, `dns` and the 65 IPv4 routes of `TunSettings`, so LAN stays outside as on Android. IPv6 per row 20. |
| 3 | Does Xray block leaks on Windows? | core report: no WFP code anywhere; UI and network reports: Xray's TUN has WFP leak blocking | All are right. The pinned Xray (8 Sep 2026, `52a412d9e2f5`, v26.9.9) has none. v26.9.30 (30 Sep 2026, `b26a91de4f32`, XTLS/Xray-core PR 6853) adds `tun_windows_wfp.go` and the option `autoSystemWfpBlockLeak` (`"dns"`, `"misconfigtun"`). It also reuses an existing adapter by name (PR 6811), keeps Xray's own lookups off the tunnel's DNS (`internet.SkipDNSServers`), turns off DNS registration of the adapter, and flushes the DNS cache at start and stop. v2rayN switched it on on 1 Oct 2026. | Move to Xray ≥ v26.9.30 and wireguard/windows v1.1.1, which fixes a winipcfg callback deadlock (section 5.1). Use both values and rely on Xray for the details: for example, DNS-over-HTTPS of Windows 11's DNS Client is covered there. |
| 4 | Xray's own socket binder (`autoOutboundsInterface`) | core and network reports | It is turned on automatically whenever `autoSystemRoutingTable` is set (`infra/conf/tun.go:38-40`). Problems, unchanged in v26.9.30: <br>• any Wi-Fi default route wins over a lower-metric Ethernet one (`findOutboundInterface`, since PR 6478); <br>• it uses the IPv4 interface metric for both families; <br>• each Start appends one more global controller; <br>• after Stop it keeps binding to the last interface, so pings made while disconnected can leave through a network that is gone. <br>Windows itself, WireGuard, sing-tun and Tailscale all pick the lowest metric. | Turn it off (`"autoOutboundsInterface": ""`). The service registers one binder of its own (`windows/internal/netbind`): <br>• per address family, the lowest route-plus-interface metric among connected interfaces, skipping ours (wireguard-windows' `findDefaultLUID`); <br>• for TCP, it binds only when Windows would send the destination into our tunnel (`GetBestInterfaceEx`, Tailscale's "bind by route"), so a corporate VPN or a second NIC keeps its routes; <br>• active only while the tunnel runs, and binding every socket while the core starts; <br>• with no interface known, it binds the socket to loopback and refuses the dial, so even a dialer that ignores the error (Xray's) cannot loop. <br>Offer the metric fix upstream. |
| 5 | The service's own DNS lookups | (found while checking 4) | Xray dials an outbound whose server is a domain with Go's default resolver (`system_dialer.go:144`). Go on Windows uses Windows' resolver unless `PreferGo` is set (`net/conf.go:166-176`), and Windows sends the query to the adapter with metric 0: the tunnel's 198.18.0.2. The answer would need DoH through the very proxy being dialed. Xray v26.9.30 fixes this only together with its own binder (`resolveOnOwn`). | `net.DefaultResolver` uses Go's resolver for good, set once at start so that no lookup races with a change. Its dial skips `internet.IsSkippedDNSServer` and, while the tunnel runs, is bound. Go reads only adapters that have a gateway (`net/dnsconfig_windows.go:35-38`). Profile outbounds also get `sockopt.domainStrategy` with a `localhost` DNS entry for the running profile's server names, so the answer is cached. |
| 6 | An elevated service with a per-user window | proposal | Microsoft: WebView2 "cannot be run as a system user" and should live in a non-elevated process. Creating a wintun adapter, routes and WFP filters needs admin or SYSTEM. | Yes: the split is required, not only tidy. |
| 7 | Named pipe with an ACL | proposal | Both `golang.zx2c4.com/wireguard/ipc/namedpipe` (already a dependency; MIT) and `github.com/Microsoft/go-winio` v0.6.2 (MIT) take a security descriptor. `GENERIC_WRITE` includes `FILE_APPEND_DATA`, which for a pipe is `FILE_CREATE_PIPE_INSTANCE`: a client could add instances and pose as the server. Clash Verge's service had a world-reachable endpoint (CVE-2026-26422) and earlier ran a caller-given binary as SYSTEM (CVE-2025-50505). Its fix uses the mask `0x12019b` and checks the server's PID against the Service Control Manager. Tailscale puts its pipe under `ProtectedPrefix\Administrators`, where only administrators can create pipes. | Name `\\.\pipe\ProtectedPrefix\Administrators\KirovVPN\control`. SDDL `O:SYG:SYD:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)`. The window dials with `0x120083` and identification-level impersonation, then checks that the pipe belongs to SYSTEM: only administrators and SYSTEM can create a pipe under that prefix, and an administrator owns the PC anyway. The service never takes paths, binaries, URLs or raw Xray JSON. Section 2.3. |
| 8 | Wails on WebView2 | proposal | v2.16.0 (14 Sep 2026) is stable but has no tray (request #4990 is stale). v3.0.0-beta.26 (25 Sep 2026) has tray, single instance with argument forwarding, hidden start, `-tags server` for headless tests, no npm needed (`wails3 generate runtime`), windows/arm64, and about 10 MB binaries. Open v3 bug: the tray's right-click menu does not open on Windows (#6161; fix in PR #6162). | Wails v3, pinned to one exact beta. Left-click opens the window, which holds every control. Until a release carries PR #6162, the app shows the tray menu itself through Wails' `WndProcInterceptor` (`internal/ui/traymenu_windows.go`). Fallback: `wailsapp/go-webview2` + `fyne-io/systray`. |
| 9 | Installer adds service, protocol and autostart | proposal | `windows-2025` (= `windows-latest`) has Inno Setup 6.7.1 and WiX 3.14 (out of community support since Feb 2025), no NSIS; `windows-11-arm` has Inno Setup only. Inno has a Russian translation, Restart Manager, silent mode, and hardening for running as SYSTEM (6.5–6.7). | Inno Setup. The service exe installs and removes the service itself (`install` / `uninstall`), so the installer stays small. |
| 10 | Updates from `/app/version.json`, extended | proposal | `android.yml` deletes and recreates `stable` and `build-<branch>` as a whole, so a Windows asset uploaded by another workflow would vanish. `klaus-panel publish-apk` rewrites `version.json` with `jq -n`, and `refresh_version_json` breaks on a missing `.apk`. Android reads only the top-level `versionCode`, `apk` and `versionName`. | Own releases (`windows-build-<branch>`, `windows-stable`) and own files under `/app/windows/`: a `version.json` made and signed in CI, found relative to the `klaus-app-url` header (the iOS plan's precedent). The Android file stays as it is. |
| 11 | Look of `klaus-page.html` | proposal | Tokens: `--ice #EEF3F9`, `--ink #0E1726`, `--slate #556173`, `--mist #7D8799`, `--blue #0A74FF`, `--blue-deep #0659D6`, `--mint #2FC8A8`; glass sheets with `backdrop-filter`, which WebView2 (Chromium) draws. Windows has no colour flag emoji (Segoe UI Emoji shows two letters instead). | Reuse the tokens and components; the orb becomes the big button. Flags come from a bundled SVG set (section 3). |
| 12 | `KlausVPN/<ver> (Windows)` and the panel | proposal | The response rule matches `user-agent` against `^KlausVPN/`, case-sensitive (`klaus-panel:304-313`). Nothing keys on `(Android)` or on the device headers. Browser words in the User-Agent would get the web page. | Same treatment as Android. A PC counts as one more device under the HWID limit; the texts of `hwid-limit` and the guide say so. |
| 13 | `x-hwid` from MachineGuid | proposal | MachineGuid is per Windows installation, readable by any user, and duplicated on cloned images. | Android's recipe on MachineGuid. If it is missing or invalid, a random id kept in the service's data folder, as Android does for a bad ANDROID_ID. |
| 14 | Unsigned at first | proposal | SmartScreen (Microsoft, 4 May 2026): "EV certificates no longer bypass SmartScreen"; unsigned files build reputation per version from zero. Smart App Control blocks unsigned executables with no per-app exception. Azure Artifact Signing takes individuals only in the US and Canada. Certum suspended issuance for Russia and Belarus in 2022. SignPath Foundation is free but needs an OSI licence; the repository has none. | Unsigned for the owner's tests and the first friends. A Russian guide covers the Edge warning, SmartScreen, UAC and Smart App Control. The owner decides on a licence and SignPath before a wide rollout (phase 6). |
| 15 | Per-program "apps without VPN" later | proposal | Xray routing has a `process` condition; on Windows it looks up the owner of a connection through `GetExtendedTcpTable`/`GetExtendedUdpTable` (`common/net/find_process_windows.go`). It only picks direct or proxy: the traffic still enters the tunnel. | Phase 4, at the owner's request (1 Oct 2026): `process` rules to `direct`. No kernel driver. |
| 16 | No mobile whitelist mode | proposal | The Android logic marks "mobile data" from the cellular transport. On Windows only WWAN adapters are mobile; a phone used as a hotspot looks like Wi-Fi. | The shared logic keeps the flag; Windows sets it only for WWAN. No UI for it. |
| 17 | Xray's Windows TUN maturity | core report | Two suspected bugs, both still in v26.9.30: <br>• The family loop calls `IPInterface(AF_INET6)` unconditionally (`tun_windows.go:179` in v26.9.30), so a PC with IPv6 disabled system-wide (`DisabledComponents`, which "optimizer" tools set) may not start the tunnel at all. <br>• `ReadPacket`/`Wait` do not lock against `Close` → `session.End()`, which can leave a stuck reader thread per Stop. | Phase 1 runs 50 Start/Stop cycles on the runner with a thread count. `DisabledComponents` needs a reboot, which a hosted runner cannot do, so test it on a VM or the owner's spare PC. Offer upstream a fix that skips a family with nothing configured. Use a `replace` to a fork only if upstream is slow. |
| 18 | One process holds the core and all probes | Android report | A temporary core takes over Xray's global log handler and system dialer (`libxray.go:415-443`). Android escapes this with two processes; the Windows service is one. | One "core broker" in the service decides: while the tunnel runs, probes use `Controller.ProbeOutbounds` and downloads `FetchThroughTunnel`; temporary cores only while it is down. A test enforces it. |
| 19 | CI build host | UI report | `setup-go`'s cache restore on Windows has been reported at about 12 minutes against 30 s on Ubuntu (actions/setup-go#495). Nebula's smoke test creates a wintun adapter and passes traffic on `windows-latest`. Runners are admin, and Defender's real-time scanning is off there. | Compile and run the pure tests on Ubuntu. Use Windows for libxray's tests, packaging and smoke tests. Antivirus false positives cannot be caught in CI (section 7.5). |
| 20 | IPv6 into the tunnel, as on Android? | network report | gVisor answers a TCP handshake before Xray has chosen an outbound (Xray README, "Limitation"). An IPv6 connection to the refused `::/0` therefore "succeeds" and is then reset, which can stop a browser from falling back to IPv4. Android lives with it, because Xray's DNS gives no AAAA answers there. On Windows, `"misconfigtun"` blocks a family that has no tunnel routes at connect time. Microsoft advises against `DisabledComponents`. | IPv4 routes only, plus `"misconfigtun"`. A later IPv6 option (for servers with IPv6) routes `::/0` into the tunnel and drops it. |
| 21 | Windows' "no internet" mark (NCSI) | network report | Windows probes `www.msftconnecttest.com/connecttest.txt` (and `dns.msftncsi.com` before Windows 11) on each interface. With a full tunnel, the physical interface reports no route, so the tunnel interface must pass the probe itself. If the server is down, Windows would mark the PC offline, and Store, Office or OneDrive stop working, while Russian sites still work directly. | The Windows config sends `msftconnecttest.com` and `msftncsi.com` directly. The adapter GUID stays stable (md5 of the name), so Windows keeps one network profile for it. Phase 1 checks the tray icon. |
| 22 | Wintun version | network report | The newest tag is still 0.14.1 (17 Oct 2021); Xray's own CI pins it with SHA-256 `07c25618…`. Two race fixes landed on master in Feb–Mar 2026 (ring overrun under parallel UDP; a missed wake-up that stalls traffic 4–5 s), in no tagged or signed build yet. | Ship 0.14.1. The stall check tolerates hiccups of about 5 s. Watch for a newer signed build. |
| 23 | Russian VPN detection on PCs | network report | The Mintsifry methodology (Apr 2026, from news summaries) has companies check desktops for virtual adapters (`IF_TYPE_PROP_VIRTUAL`: wintun's `*IfType` is 53), routes, DNS and unusual MTUs. Russian apps scan 127.0.0.1 for open proxies to learn the server's IP (Habr, Mar–Apr 2026). | No local listener of any kind: no SOCKS or HTTP inbound, no Xray API, no pprof. MTU 1500. The adapter itself cannot be hidden. Per-program routes (phase 4) can send Russian programs directly. |
| 24 | Core in the service, or in a child process | network report | A child process (WireGuard runs one service per tunnel) would isolate Xray crashes and reset its process-wide state on every restart. It costs a second IPC channel. Android's `:vpn` process holds core and logic together. | One process, like Android's `:vpn`. The binder and resolver are ours, registered once, and the broker of row 18 handles the rest. Revisit only if Xray crashes show up in the field. |
| 25 | Restarting the core on every server switch | network report | Each Stop removes the adapter, routes and filters; Start brings them back (section 2.4, point 8). Xray's outbound manager can replace the proxy outbounds in a running instance. | Phase 4 measures how long the gap is and what leaks during it. Server switches then replace the outbounds without a restart, and network resets restart the core (Android's behaviour) behind a WFP hold if needed. |

## 2. Architecture

### 2.1 Programs

| Program | Runs as | Uses libxray | What it does |
|---|---|---|---|
| `KirovVPNService.exe` | Windows service `KirovVPN`, LocalSystem, unrestricted service SID, automatic start, recovery: restart | yes | Store, core, tunnel, the Android VPN-process logic, imports, pings, subscription downloads, update checks and installs, geo files, logs; answers the pipe |
| `KirovVPN.exe` | every signed-in user, `asInvoker`, started at logon (HKLM Run) | no | Tray icon and menu, window (WebView2), toasts, `klausvpn://` handler (single instance per session), clipboard and QR-image import; only talks to the service |
| `KirovVPN-Setup-1.0.N.exe` | administrator (UAC), or SYSTEM for updates | no | Files, service, protocol, autostart, WebView2 runtime, uninstall |

Android's UI process does imports, pings, subscription adds and update checks itself. On Windows those need the network outside the tunnel and the keys, so the service does them and the window only asks.

### 2.2 Data and files

- **`C:\Program Files\Kirov VPN\`**: both exes, `wintun.dll` (amd64), `geo\` (the bundled `geoip.dat`, `geosite.dat`, `version.txt`), `licenses\`. The installer refuses any other folder (`/DIR=`), because the service runs as SYSTEM from it.
- **`C:\Program Files\Kirov VPN\Data\`**: only SYSTEM and Administrators.
  - Next to the programs, as WireGuard keeps its data: only administrators can create anything there. In `C:\ProgramData` every user may create folders, so a user could make the folder before the service and hold it open to keep the service from starting.
  - At every start the service creates it with a protected ACL, or sets that ACL and its owner again; a link in its place is refused.
  - Contents:
    - `data\profiles.json` and `data\settings.json`: the JSON of Android's `Models.kt`, with `JsonFileStore` semantics (temporary file, fsync, rename; an undecodable file is set aside, at most 3 kept). On disk they are encrypted with DPAPI for SYSTEM, as WireGuard stores its configurations, because profiles hold keys and subscription links;
    - `data\runtime.json`: Android's `vpn_runtime` preferences (`should_run`, budgets, the way back home);
    - `geo\`: downloaded databases;
    - `logs\`: `service.log` (128 KB, rotated), `xray.log` (Android's 512 KB trim), `go-crash.log` (`SetCrashLog`);
    - `update\`: the downloaded installer.
  - The service is the only writer, so no cross-process file lock is needed.
- **`%LOCALAPPDATA%\Kirov VPN\`** (per user): `ui.log`, `ui.json` (dismissed update, window position, "do not start at logon"), and the WebView2 user data folder. The default folder next to the exe is not writable under Program Files.
- **Privacy, as on Android:** no keys, links, IPs or server hostnames in any log; Go error texts never quote link bodies.
  - The app's own logs mask every IP address in a line, and the engine drops the server's address from logged core errors.
  - The core logs warnings with `maskAddress: "full"`, without access or DNS log.
- **Replacing files.** Go opens files without `FILE_SHARE_DELETE` and `os.Rename` does not retry, so an antivirus reading a file makes replacing it fail. `fsx.Replace` retries for about 2 seconds; it is used for geo files, downloads, saved data and log rotation.

### 2.3 Service and window: the pipe

- **The pipe.** `\\.\pipe\ProtectedPrefix\Administrators\KirovVPN\control`.
  - The service creates it at start with the first-instance flag; only administrators can create pipes under that prefix, so nobody can squat the name while the service restarts.
  - It rejects remote clients.
  - SDDL: `O:SYG:SYD:P(D;;GA;;;NU)(A;;GA;;;SY)(A;;GA;;;BA)(A;;0x12019b;;;IU)S:(ML;;NWNRNX;;;ME)`.
    - Interactive users get read and write without `FILE_APPEND_DATA`, which for pipes is `FILE_CREATE_PIPE_INSTANCE`, so they can talk but cannot add instances.
    - Network logons are denied explicitly.
    - The medium integrity label with no read up: sandboxed programs (low integrity) cannot even read the status.
- **The window's side.** It dials with access `0x120083` at identification impersonation level, overlapped, and checks that the pipe belongs to SYSTEM. `namedpipe`'s own dial asks for `GENERIC_WRITE`, which the ACL refuses users, so the window opens the pipe itself (`ipc.Dial`).
- **The service's side** (phase 3, for toasts in the right session). It reads the caller's user and session from its token: `ImpersonateNamedPipeClient` on a locked thread, then `RevertToSelf`; if that fails, the process exits. Caller identity grants no extra rights.
- **Messages.** Newline-delimited JSON.
  - Requests `{id, op, args}` get `{id, result}` or `{id, error}`; the error is a Russian text the window shows as it is.
  - Events go to every window: `hello` (the protocol version), `status` (Android's `onStatus`: state, profile id and name, message, connected since), `profiles` (Android's `onProfilesChanged`, without keys or links); later `busy` (the busy captions) and `toast`. A new window gets `hello`, `status` and `profiles` first.
  - The protocol is versioned; a window of another version asks to be restarted after an update.
  - Limits: 32 connections; per connection 8 requests running and 20 at once then 10 a second (more are told to wait); 64 queued messages (a window that reads no more is dropped); 2 MB per line, which neither side sends beyond.
  - A connection keeps its place until its running requests end, so all windows together run at most 256 requests. When a window hangs up, its requests are cancelled.
  - The heavy request, `import`, runs one at a time and fetches at most 16 certificates (four at once), each a connection outside the tunnel to whatever the key names.
  - Saved servers are capped at 1000 and their names at 100 characters, so the server list always fits in one message.
  - The window waits longer and longer before dialling again after a failed dial or a connection that ended at once.
- **Operations** (phase 1 has `status`, `import`, `connect` and `disconnect`):
  - connection: `status`, `connect {picked}`, `disconnect`, `reconnect {picked}`, `select`;
  - servers: `import {text}` (at most 256 KB, Android's cap), `refresh {subscription}`, `rename`, `delete`, `ping {ids}`, `pingAll`;
  - settings and help: `settings`/`setSettings`, `logs` (the sections of Android's «Журнал»), `updateGeo`, `checkUpdate`, `installUpdate`, `diag`.
- **Never accepted from the pipe:**
  - a file path (logs are returned as text, «Сохранить в файл» writes from the window);
  - a URL to download and run;
  - a command line;
  - raw Xray JSON, whose log paths, inbounds, `api` and `sockopt` could write files as SYSTEM or open ports.

  Every argument is validated and sized, each connection is rate-limited, and keys and links are never sent back except by «Скопировать ключ».
- **Who may control it.** Any interactive user of the PC: a family PC shares one VPN, like a router. Nothing needs administrator rights after installation.

### 2.4 The tunnel on Windows

1. **Adapter.** Xray's `tun` inbound with:
   - `name` and `desc` «Kirov VPN» (a stable, unique GUID; `tun0` could collide with other Xray-based apps, now that Xray reopens an adapter by name);
   - `mtu` 1500;
   - `gateway`: 198.18.0.1/30;
   - `dns`: 198.18.0.2, inside the tunnel's own prefix. It is not a known DoH provider, so Chrome and Edge do not switch to their own DoH;
   - `autoSystemRoutingTable`: the IPv4 routes of `TunSettings` (all IPv4 but LAN, loopback, link-local, CGNAT, multicast and reserved space);
   - `autoOutboundsInterface`: `""`;
   - `autoSystemWfpBlockLeak`: `["dns", "misconfigtun"]`.

   The more specific routes beat the physical default route whatever its metric.
2. **Binder.** The service (`windows/internal/netbind`) registers one dialer controller once per process. The engine switches it on before the core starts and tells it once the core runs (`Activate`, `Settle`).
   - While the tunnel runs, it binds every socket of the service to the current physical interface with `IP_UNICAST_IF` / `IPV6_UNICAST_IF`. That covers the proxy, direct traffic to Russian sites, DNS to Yandex, probes, temporary cores, and libxray's own HTTP clients through a `direct` dialer.
   - It picks the default route with the lowest route-plus-interface metric per family, skipping our adapter (row 4 of section 1). Route, interface and address change callbacks keep the choice current, debounced. A callback is never unregistered from inside a callback, which Microsoft documents as a deadlock.
   - It skips loopback. If no physical interface is known, it binds the socket to loopback and refuses the dial.
   - Once the core runs, a dial whose destination Windows would not send into our tunnel (a corporate VPN, a second NIC) is left alone. While it starts, every socket is bound.
   - While the tunnel is down it does nothing, so sockets follow the normal routes.
3. **Resolver.** `net.DefaultResolver` uses `PreferGo` for good, set once when the service starts, so no lookup races with a change. Its dial skips the tunnel's DNS servers (`internet.IsSkippedDNSServer`) and goes through the binder. Xray's own version of this (`resolveOnOwn`) runs only with Xray's binder, which is off.
4. **DNS for apps,** unchanged from Android: port 53 into the tunnel goes to Xray's DNS module.
   - Russian domains go to Yandex directly; blocked and foreign domains go to DoH through the proxy.
   - `localhost` serves private and router names, and now asks the physical network's DNS servers directly (point 3).
   - `queryStrategy` stays `UseIPv4`.
   - Xray's WFP filters block DNS outside the tunnel for every other program, including Windows 11's DNS Client over DoH and DoT. mDNS and LLMNR stay on the LAN.
5. **IPv6.** The tunnel has no IPv6 routes, and `"misconfigtun"` blocks IPv6 outside it in both directions (loopback, neighbour discovery and DHCPv6 stay). An IPv6 attempt fails at connect time and programs use IPv4 (row 20). This also covers 6to4 and Teredo, which are IPv6 inside IPv4.
6. **Windows-only routing rules,** added by `BuildOptions.Windows`:
   - `msftconnecttest.com` and `msftncsi.com` go directly (row 21);
   - NetBIOS (UDP 137, 138), mDNS (5353), LLMNR (5355) and multicast or broadcast arriving from the tunnel are refused, as Windows also sends them into the tunnel's subnet;
   - the service also turns NetBIOS off on the adapter (`NetbiosOptions=2` under `NetBT\Parameters\Interfaces\Tcpip_{GUID}`), as Tailscale does.
7. **LAN** (printers, casting, the router page) stays outside over IPv4, as on Android.
8. **Restarts.** Xray removes the adapter, its routes and the filters at Stop, and creates them again at Start. Android's VPN interface outlives the core.
   - In between, about a second, traffic follows the physical routes, and browsers see a network change (Chrome may show `ERR_NETWORK_CHANGED` for requests in flight).
   - Phase 4 measures and closes it (row 25):
     - server switches replace the proxy outbounds in the running core;
     - resets after a network change hold traffic with WFP;
     - or Xray keeps the adapter across restarts (an upstream change: a process-wide adapter cache).

**Connect sequence.** It follows the order of Android's `TunnelEngine.start`.
1. Pick the profile (the switch winner, else the selection) and install the geo files if needed.
2. `BuildConfig` with the Windows tun options, then `Controller.Start(config, 0)`. Xray creates the adapter, routes, DNS and WFP filters.
3. Publish CONNECTED and run the START check.
4. Stop does the same in reverse; Xray removes the filters, routes and adapter, and flushes the DNS cache.

**Start failures** follow Android's `StartFailurePolicy`, with the same retries after 1.5 s and 5 s. "Another VPN active" becomes:
- creating the adapter failed because another program holds the name;
- or the routes could not be set.

### 2.5 Network changes, sleep and restarts

| Android signal | Windows source | Rule (from `HealthMonitor`) |
|---|---|---|
| default network changed | the binder's route and interface callbacks: the chosen interface changes, or disappears | NETWORK: check after 1.5 s; reset the core in place (sockets bind again) if the session is at least 3 s old |
| VALIDATED lost or regained | no clean equivalent without COM | dropped; the 5-minute tick and the checks after resume cover it |
| screen on, 5-minute tick | a 5-minute tick while a user session is active and unlocked | SCREEN |
| unlock | `SessionChange` → `WTS_SESSION_UNLOCK` | UNLOCK: at least 10 minutes since the last check |
| — | `PowerEvent` → `PBT_APMRESUMEAUTOMATIC`; a wall-clock jump of 10 minutes or more, polled every 15 s, for Modern Standby PCs that never report a suspend (Tailscale's way) | RESUME: wait about 5 s for the network (Mullvad does), pick the interface again, reset the core in place, check |
| — | Fast Startup: services keep running and the next "boot" is a resume, seen as an interactive logoff followed by a suspend within 5 s (Mullvad's detector) | RESUME |
| app shown | window opened (pipe `status` subscription) | APP: at least 2 minutes since the last check |
| captive portal | when nothing answers, a direct probe of `http://www.msftconnecttest.com/connecttest.txt` that is redirected | notice «Wi-Fi требует входа: отключите VPN, войдите в сеть и подключитесь снова» (the DNS filter blocks portal sign-in while connected) |
| cellular transport | WWAN adapter type | mobile flag for reports and candidate order |

- **Suspend.** On `PBT_APMSUSPEND` the service has about 2 seconds. It does nothing slow there: the tunnel stays up and is checked on resume.
- **Crash and restart.**
  - The Service Control Manager restarts the service after 2, 10 and 60 seconds; the failure count resets after a day; non-crash failures count too (`SetRecoveryActionsOnNonCrashFailures`).
  - A marker file tells a crash from a clean stop. If the service has restarted 3 times within 5 minutes while it should run, it does not resume by itself and shows Android's message («VPN несколько раз аварийно остановился…»). This is Android's `RestartGuard`.
- **Boot.** The service starts at boot (automatic, not delayed) and reconnects if the VPN was on (`should_run`). This replaces Android's always-on VPN and sticky restart.
  - Early in boot the adapter's IP interface may not exist yet. Xray retries for 15 seconds, and the service retries the start after that, as WireGuard does.
  - The setting «Подключаться при включении компьютера» is on by default.
- **Hung service.** A watchdog in the service exits the process when its main loop stops responding. The WFP filters go with it, so a hang can never leave the PC without DNS.

### 2.6 What is not ported, and why

- **Per-app bypass** («Российские сервисы», «Другие приложения»): Android lists app packages. On a PC Russian banks and services are mostly websites, which ru_direct already sends directly. Per-program rules come in phase 4 (row 15).
- **Background and battery settings, OEM autostart, notification permission:** not meaningful on Windows. «Запускать Kirov VPN при входе» and «Подключаться при включении компьютера» replace them.
- **Private DNS hint:** Android only. Windows' encrypted DNS is handled by the WFP filters.
- **Lockdown warning:** there is no system lockdown on Windows. A real kill switch («Блокировать интернет без VPN», persistent WFP filters) is optional, in phase 7.
- **Camera QR:** replaced by QR from an image file, the clipboard or a screenshot (phase 3).

## 3. Android features on Windows

| Android | Windows | Phase |
|---|---|---|
| Home: big button, status line with colours, session timer, notices in priority order, auto-ping of the shown server | The same in the window, the orb of `klaus-page.html` as the button; tray icon in four states with the status as its tooltip | 1 (minimal), 3 |
| Europe map with a pin on the server's country | The same map, redrawn in the light style, if it fits; otherwise a flag and country name | 3 |
| Flags from the emoji in server names | A bundled SVG flag set (for example Twemoji, CC-BY 4.0, credited on «Лицензии»), because Windows draws flag emoji as two letters | 3 |
| Server list: «Мои ключи» and one group per subscription with usage, notice, announce, refresh, «Проверить все серверы», ping grades, rename, copy key, delete | The same; right-click menu instead of long-press; copied keys excluded from clipboard history | 2, 3 |
| Add: paste, QR camera, manual entry, several keys, 256 KB cap, subscription link | Paste (also Ctrl+V anywhere in the window), manual entry, QR from an image file, a clipboard image or a screenshot (pure-Go decoder), drag and drop of text or an image | 2, 3 |
| `klausvpn://add/…`, `import/…`, `install-config?url=` and the share intent, with the confirmation dialog | The same parser behind a registered protocol; the second launch hands the link to the running window, which asks «Добавить ключи или подписку?» | 2 |
| Subscription requests with User-Agent and device headers; the response headers it reads; the merge rules; certificate pins | The same, in Go (section 5.2) | 2 |
| Refresh on app open (stale after 1 h, retry after 2 min) and during failover | The same, plus at service start and every 12 hours while connected, because the window can stay closed for weeks | 2 |
| Health checks, stall check, failover search, server switching, way back home, budgets, block reports, subscription refresh owed through the tunnel | The same rules and constants in Go; triggers per section 2.5 | 2 (logic), 4 (signals) |
| Notifications (status, errors) | Tray tooltip and icon; toasts for errors and notices (toasts need a Start-menu shortcut with an AppUserModelID, made by the installer) | 3 |
| Quick Settings tile, widget with ping | Tray menu: «Подключить» / «Отключить», server, «Проверить отклик», «Открыть», «Выход» | 3 |
| Settings: «Надёжность» | «Подключаться при включении компьютера», «Запускать Kirov VPN при входе», notifications state | 3, 4 |
| «Сообщить о проблеме»: the log sections | «Журнал» with «Компьютер» (Windows version and build, model, app version, WebView2 version, adapter and filter state, other VPN adapters found), service, window, Xray and crash logs; «Копировать» and «Сохранить в файл» | 3 |
| «Обновить списки» (about 90 MB, through the server, then directly) | The same, by the service | 4 |
| Version, «Лицензии» | The same, plus wintun, Wails, WebView2 loader, Go and flag licences | 3 |
| Update card (`version.json`, every 12 h, «Позже») | The same card; «Обновить» installs silently (section 7.3) | 5 |
| Always-on VPN, sticky restart, `ResumeReceiver`, `RestartGuard` | Service autostart, `should_run`, recovery actions, crash marker, resume after the installer | 1, 4 |

## 4. Repository layout

```
libxray/
  windows.go              Windows tun settings and routing rules for BuildConfig (pure, tested on Linux)
  direct.go               dialer and transport for http.go and cert.go through internet.Controllers
  internal/fsx/           Replace: rename with retries on Windows (antivirus), os.Rename elsewhere
  client/                 the Android logic in Go (section 5.2), tested on Linux
windows/
  go.mod                  module github.com/klausms17/vpn/windows; replace ../libxray
  cmd/kirovvpn-service/   service entry; install, stop, uninstall for the installer
  cmd/kirovvpn/           tray and window (Wails v3)
  cmd/kirovctl/           test client for the pipe, CI only, never shipped
  internal/ipc/           protocol types, server, client, pipe transport; tests over net.Pipe
  internal/netbind/       binder and resolver (row 4, row 5); the interface picker is pure and tested on Linux
  internal/engine/        start, stop, retries and resets of the tunnel (Android's TunnelEngine); tests with fakes
  internal/service/       the Windows service: wiring, the pipe's handler, install and recovery
  internal/winsys/        the protected data folder, DPAPI, elevation
  internal/ui/            Wails app: link to the service, tray, window; frontend/ holds index.html, app.css, app.js
  assets/                 app and tray icons, drawn by tools/mkicons and embedded
  installer/              KirovVPN.iss
  test/smoke.ps1          the smoke test of section 6
  tools/mkicons/          draws the icons
  tools/mkres/            the exes' icon, manifest and version as .syso
  tools/testserver/       a local VLESS+REALITY server for the smoke test, never shipped
.github/workflows/windows.yml
```

Later phases add `internal/broker` (row 18), `internal/netwatch` (section 2.5), `internal/update` (section 7) and `tools/signmanifest`.

The Windows module has its own `go.mod`, as the Remnawave e2e tool does, so Wails and Windows-only dependencies never reach the Android AAR. `android.yml` ignores `windows/**` and `windows.yml`.

## 5. Go changes

### 5.1 libxray (Android and iOS keep their behaviour)

1. **Versions.**
   - Move `github.com/xtls/xray-core` to v26.9.30 (`b26a91de4f32`, 30 Sep 2026) or later, for row 3. That also brings PR 6811 (adapter reuse) and a TUN UDP fix (PR 6814).
   - Move `golang.zx2c4.com/wireguard/windows` to v1.1.1 (the winipcfg callback deadlock fix).
   - This changes the core of the Android app too. The gate is the full Go suite (it starts real local Xray servers), the APK build and the iOS checks.
   - If anything regresses on Android, only `windows/go.mod` requires the new versions until it is fixed (Go picks the highest requirement). The Windows-only code that needs the new Xray API then lives in the Windows module.
2. **`BuildOptions.Windows`.** It adds:
   - the tun settings and routing rules of section 2.4;
   - for profile outbounds, `sockopt.domainStrategy` plus a `localhost` DNS entry listing the running profile's server names (row 5). The hot swap of phase 4 (row 25) lists every saved profile's, so the entry is there when it switches.

   Tests on Linux: the JSON of the inbound, IPv4 routes equal to `TunSettings` without `2000::/3`, and the extra rules and entry existing only on Windows. The options are passed as JSON, so the gomobile API and `tools/jvm-check/stubs` do not change.
3. **Binder:** in the Windows module (`internal/netbind`), not libxray, which stays free of Windows-only code. Its picker over route rows is pure and tested on Linux; the service switches it on around `Controller.Start` and off after `Stop`.
4. **`direct.go`**: `http.go`, `cert.go` (TCP and the QUIC pin) and `geo.go` dial through `internet.Controllers`. No controller is registered on Android and iOS, so nothing changes there.
5. **`fsx.Replace`** for `geo.go:200`, `http.go:327` and the crash-log rotation.
6. **Tests on Windows** (CI): the whole suite with `GEO_DIR`, and again with `DESKTOP_MATCHER=1`. `TestMain` forces the mobile domain matcher, while desktop Xray uses the MPH matcher. Timing assertions that fail on Windows get Windows margins, never a skip.

### 5.2 The Android logic in Go (`libxray/client/`)

Ported one to one, with the JVM tests (234 today) as Go tests and the same fakes (core, tunnel, direct network, virtual clock).

| Go package | Kotlin it replaces | Tests ported |
|---|---|---|
| `client/model`, `client/store` | `Models.kt`, `JsonFileStore`, `ProfilesOps`, `AppSettings` | `JsonFileStoreTest`, `ProfilesOpsTest`, `AppSettingsTest` |
| `client/applog` | `AppLog` (the 128 KB log with one old file) | its own |
| `client/subscription` | `SubscriptionUpdater`, `DeviceHeaders` (recipe, sanitising), `Pinned`, `ProfileImport` | `SubscriptionUpdaterTest`, `DeviceHeadersTest` (vector `0123456789abcdef` → `1012707cdd34d59dbc64b03534a44dc9`), `PinnedTest` |
| `client/importer` | `ImportText`, `DeepLink`, the key part of `MainViewModel.addLinks` (parse, pin four at a time, the result message) | `DeepLinkTest` and the import cases |
| `client/tunnel` | `StartFailurePolicy`, `ResetScheduler`, `Epoch`, `HealthMonitor`, `TrafficCheck`, `FailoverSearch`, `Failover`, `FailureMemory`, `WhitelistLookup`, `ServerSwitcher`, `SubscriptionRefresher`, `NoticeBoard`, `VpnStatus`, `RuntimeState` rules, `RestartGuard` rules, `XrayLog` | every service test of section 10 of the Android report |
| `client/report` | `BlockReport`, `BlockReporter` rules, `ReportThrottle` | `BlockReportTest` |
| `client/appupdate` | `AppUpdate` (Android shape) plus the Windows manifest | `AppUpdateTest` plus manifest and signature tests |
| `client/geofiles` | `GeoFiles` rules | `GeoFilesTest` |

- **Threading.** Kotlin's single worker plus the non-reentrant start `Mutex` becomes one owner goroutine for tunnel state. Checks and downloads run in goroutines with a `context` that the epoch cancels. Results come back tagged with their epoch, and outdated ones change nothing. Time comes through an injected clock.
- **For iOS later:** a facade in package `libxray` exposes these packages as JSON-string calls (the iOS plan's phase 5).

### 5.3 The Windows module

- **Service:** `golang.org/x/sys/windows/svc` with `AcceptPowerEvent`, `AcceptSessionChange` and `AcceptPreShutdown`, and `svc/mgr` for install:
  - its own process, LocalSystem;
  - `SidType` unrestricted;
  - dependencies `Nsi`, `Tcpip` and `BFE` (WFP needs the Base Filtering Engine);
  - recovery actions (section 2.5), also on non-crash failures;
  - a description in Russian;
  - automatic start, not delayed.

  It calls `log.SetOutput`, because wintun logs through Go's `log` and a service has no console.
- **Why LocalSystem and not a virtual service account:** creating the adapter (`SwDeviceCreate`), routes and interface settings needs an administrator. The first driver install also needs `SeLoadDriverPrivilege`. WireGuard, Mullvad and Tailscale all run as LocalSystem.
  - Privileges it never uses are dropped once phase 5 has settled the set: launching the tray app still needs `SeTcbPrivilege` for `WTSQueryUserToken`.
- **Store at rest:** DPAPI for SYSTEM (`CryptProtectData` without the machine flag), as `golang.zx2c4.com/wireguard/windows/conf/dpapi` does.
- **Pipe:** `golang.zx2c4.com/wireguard/ipc/namedpipe` (already a dependency) for the service's side; the window opens the pipe itself (section 2.3) as an overlapped `os.File`.
- **WFP:** none of our own in phases 1–3; Xray's filters cover DNS. A full block (phase 4 or 7) would adapt `golang.zx2c4.com/wireguard/windows/tunnel/firewall`. That package is MIT; keep its notice. As shipped it is all-or-nothing and would cut the LAN, so the adaptation must permit the LAN ranges.
- **Starting the tray app after an update:** `WTSEnumerateSessions`, `WTSQueryUserToken`, `CreateProcessAsUser`, as WireGuard starts its UI per session.
- **Resources:** `go-winres` writes the icon, version info and manifest for both exes.
  - Window: `asInvoker`, PerMonitorV2 DPI, Common Controls 6, `-H windowsgui`.
  - Service: console subsystem, `asInvoker`; `install` checks elevation at run time.
  - No UPX, no obfuscation (section 7.5).

## 6. CI design (`windows.yml`)

**Triggers:**
- pushes to any branch touching `windows/**`, `libxray/**`, `scripts/fetch-geo.sh`, `scripts/prepare-geo.sh` or the workflow;
- `v*` tags;
- `workflow_dispatch` with a boolean `stable`.

Concurrency as in `android.yml`: test builds of a branch replace each other; stable builds never cancel. `defaults.run.shell: bash` everywhere, Windows included (Git Bash has `sha256sum`).

| Job | Runner | Steps |
|---|---|---|
| `logic` | ubuntu-latest | `go vet` and `go test -race` of `libxray/client/...` and the Windows module; `gofmt`; `GOOS=windows go vet ./...`; the icons must match `tools/mkicons`; `tools/mkres` writes the exes' resources; cross-compile both exes (amd64; arm64 from phase 6) with `-trimpath -buildvcs=false -ldflags "-s -w -X main.version"`, and `kirovctl` and `testserver` for the smoke test; the licence files of every module in the programs; upload `bin` |
| `core-windows` | windows-2025 | `setup-go` (cache off if the restore proves slow); `scripts/fetch-geo.sh`; libxray `go test ./...` with `GEO_DIR`, and again with `DESKTOP_MATCHER=1` |
| `package` | windows-2025, needs `logic` | wintun 0.14.1 zip with SHA-256 `07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51` (the pin of Xray's own CI), `bin/amd64/wintun.dll` and its `LICENSE.txt`; geo files (`fetch-geo.sh`, `prepare-geo.sh` into the installer's folder); the WebView2 bootstrapper, its Microsoft signature checked; `iscc` (6.7.1 or later); `SHA256SUMS.txt` with LF line endings; the update manifest, signed when `WINDOWS_UPDATE_KEY` exists |
| `smoke` | windows-2025, needs `package` | below |
| `screens` (phase 3) | ubuntu-latest | the window built with `-tags server` against a fake service, Playwright (Chromium) captures every state; PNGs go to the `ui-screenshots-windows` branch (not `ui-screenshots`, which `android.yml` force-pushes) |
| `release` | ubuntu-latest, needs all | pre-release `windows-build-<branch>` (deleted and recreated), or `windows-stable` for a `v*` tag or a manual stable run; title «Kirov VPN для Windows 1.0.N (…)»; Russian notes; assets: the installer, `SHA256SUMS.txt`, `version.json` and `version.json.sig` |

**Smoke test** (`windows/test/smoke.ps1`) on the runner, as administrator, through `kirovctl`:
1. Silent install. The service runs as LocalSystem and starts at boot, the Run key exists, and only SYSTEM and administrators may open the data folder. `KirovVPN.exe --selftest` loads the page in a hidden WebView2 and exits.
2. `testserver`: a VLESS+REALITY server on 127.0.0.1 whose REALITY target is a local TLS 1.3 server. Its own sockets go through `netbind` like the service's and its DNS is DoH, so its traffic cannot loop back into the tunnel.
3. Import its link and connect.
   - `Get-NetAdapter` shows «Kirov VPN» up.
   - `curl https://www.gstatic.com/generate_204` gets 204, and the server's access log shows the connection.
   - `Resolve-DnsName example.com` works. A query to 8.8.8.8:53 sent through the network card (`IP_UNICAST_IF`) gets no answer, while before connecting and after disconnecting it does: the WFP filter. A plain query to 8.8.8.8 would prove nothing, as the tunnel's DNS answers it.
   - An IPv6 connection (`curl -6`) fails within 3 s.
4. 50 restarts of the tunnel: the service's handle and thread counts stay within 150 and 30 of where they were (row 17), and a site still answers.
5. Install over itself: the service stops, is replaced, and brings the tunnel back with the saved key. (Over the previous release once one exists.)
6. Uninstall: the service, adapter, keys and logs and the programs are gone.

On any failure the script prints the logs and stops the service first, so the runner keeps its connection to GitHub. The job's timeout covers a hang. Routing only TEST-NET-3 first (Nebula's approach) proved unnecessary: that stop restores the runner's network.

**Versions:**
- `windows.yml`'s run number gives 1.0.N, a sequence of its own (Android has `android.yml`'s).
- The Inno `AppId` is a fixed GUID that never changes.

## 7. Installer, updates and signing

### 7.1 Installer (Inno Setup)
- **Basics:** `PrivilegesRequired=admin`, `MinVersion=10.0.17763`, Russian and English, `CloseApplications=force`.
  - Architectures: `ArchitecturesAllowed=x64os` until phase 6, so an arm64 PC is refused instead of running the x64 build emulated. WireGuard's installer refuses that too, because the driver must match the system. Phase 6 adds native arm64 files.
- **Before copying:**
  - check the WebView2 runtime (registry `pv` under `EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}`, HKLM and HKCU);
  - if it is missing, run the bundled 2 MB bootstrapper with `/silent /install`. It downloads from Microsoft; if that fails, show a Russian message with Microsoft's link. Whether that CDN is reachable from Russia is not verified;
  - `KirovVPNService.exe stop`: the service tells every window to quit and stops.
- **Files:** section 2.2.
- **Registry:**
  - `HKLM\Software\Classes\klausvpn`: `URL:Kirov VPN`, an empty `URL Protocol` value, `shell\open\command` = `"…\KirovVPN.exe" "%1"`;
  - `HKLM\…\Run`: «Kirov VPN» = `"…\KirovVPN.exe" --tray`.
- **Start menu:** a shortcut with the AppUserModelID `KirovVPN`, needed for toasts.
- **After copying:** `KirovVPNService.exe install`, which creates or updates the service and starts it. The service starts the tray app in each active session.
  - Interactive installs also start it in the installing user's session.
  - Never a `[Run]` entry while running as SYSTEM: the app would start in session 0, where nobody sees it.
- **Uninstall:** `KirovVPNService.exe uninstall`, then the files, registry keys and `Data\`. Keys must not stay behind on a PC that is given away.
  - `uninstall` stops and deletes the service; the adapter goes with the process.
  - It removes the wintun driver if no adapter uses it (`WintunDeleteDriver`); another wintun program reinstalls it on first use.

### 7.2 Updates: what the panel serves
- **CI makes the manifest** `version.json`: `{"versionCode": N, "versionName": "1.0.N", "file": "KirovVPN-Setup-1.0.N.exe", "size": …, "sha256": "…", "minBuild": 17763}`.
  - It signs the exact bytes with Ed25519 into `version.json.sig`.
  - The signer is `windows/tools/signmanifest`, which reads the OpenSSH key in the `WINDOWS_UPDATE_KEY` secret.
- **The panel copies both files verbatim** to `/srv/app/windows/`, next to the installer and a fixed-name hard link `KirovVPN-Setup.exe`.
- **The client finds the manifest** by taking the `klaus-app-url` header (`https://SUB/app/version.json`) and replacing its last segment with `windows/version.json`. The installer URL is relative to the manifest, so a change of SUB_DOMAIN needs no rewrite.

### 7.3 Updates: what the client does
1. **When:** every 12 hours (Android's `AppUpdate.CHECK_MS`), and at most every 5 minutes after a failed try. Directly first (bound to the physical network), then through the tunnel.
2. **What it checks:** the signature against the public key in the source. `versionCode` must be higher than the installed one and than any seen before, which stops rollback attacks (Mozilla CVE-2020-15663). `minBuild` must fit the system.
3. **The card.** It shows «Доступна новая версия 1.0.N» with «Позже» and «Обновить», as on Android.
4. **Install.** «Обновить» makes the service:
   - download into `Data\update\`, checking size and SHA-256 while it downloads;
   - start the installer detached: `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /NOCANCEL /LOG=…`.

   The installer stops the service, replaces the files and starts it again. The service resumes the tunnel if it was on, and starts the tray app in each session again.
5. **Until the owner has made the signing key,** manifests are unsigned. The card then opens the installer in the browser, and the friend installs by hand (SmartScreen and UAC), as on Android.
6. **Why the service and not a UAC prompt:**
   - Through the window, every update would show the yellow «неизвестный издатель» prompt, and a standard user would need an administrator's password.
   - The file would also sit in a folder the user's programs can write to, so they could swap it before the user clicks «Да».
   - WireGuard, Tailscale and Mullvad all update from a SYSTEM service with signed metadata.
   - The signing key then decides who can run code on friends' PCs: it lives only in GitHub secrets. A compromised panel can withhold updates but cannot push its own installer. This is the protection Android's APK signature gives.

### 7.4 Code signing and what friends see
- **Unsigned for now.** A friend sees, in order:
  1. Edge «…обычно не скачивают» → «…» → «Сохранить» → «Всё равно сохранить»;
  2. SmartScreen «Система Windows защитила ваш компьютер» → «Подробнее» → «Выполнить в любом случае»;
  3. UAC with «неизвестный издатель» → «Да».

  These are the expected Russian texts; check them on a Russian Windows in phase 1. Updates through the service carry no Mark-of-the-Web, so SmartScreen does not ask again.
- **Smart App Control blocks unsigned programs entirely.** It is on only on some clean installs of Windows 11. The friend page and the guide explain how to switch it off (Безопасность Windows → Управление приложениями и браузером).
- **Options, for the owner to decide in phase 6:**
  - **SignPath Foundation:** free; needs an OSI licence (MIT or MPL-2.0 fit Xray's MPL-2.0), a public signing policy page and builds from GitHub-hosted runners. The publisher shown is «SignPath Foundation». Not verified: whether they accept a censorship-circumvention VPN.
  - **Azure Artifact Signing:** about $10 a month; individuals only from the US or Canada, companies from more countries.
  - **A commercial OV certificate:** about $150–300 a year, with a hardware token or cloud signing. DigiCert, Sectigo and Certum stopped issuing to Russia in 2022.

### 7.5 Antivirus
- **Precedents:** v2rayN (Wacatac, 2020–21), `Xray-windows-64.zip` (Wacatac.B!ml, Feb 2025) and the Hiddify MSIX (Pomal!rfn, Apr 2025) were flagged by Defender.
- **Making it less likely:**
  - no UPX and no obfuscation;
  - full version info, icons and manifests;
  - stable names, paths and service name;
  - updates only through the installer, never by swapping files in place;
  - Windows APIs instead of calling `netsh` or PowerShell (Xray calls `netsh` only on Windows older than 1809).
- **Each stable release** goes to Microsoft (WDSI, as «Software developer»), Kaspersky and Dr.Web as a false positive. Defender's real-time scanning is off on GitHub runners, so CI cannot catch this.

## 8. The panel side (phase 5)

1. **`klaus-panel publish-windows`**, or `publish-apk` publishing both.
   - It follows `publish-apk`: the release from `WINDOWS_RELEASE_TAG` (default `windows-stable`); the asset `^KirovVPN-Setup-[0-9A-Za-z._-]+\.exe$`; a mandatory `SHA256SUMS.txt` and `version.json`; no downgrade.
   - It shares the lock but has its own staging folder.
   - Replaced builds are kept 13 hours, then a 302 sends their names to `KirovVPN-Setup.exe`.
   - An hourly timer of its own.
   - No «временная подпись» gate: being unsigned is normal on Windows.
2. **Caddy `/app/`:** `@exe path *.exe` → `application/octet-stream` with `Content-Disposition: attachment`, plus the 302 for deleted installers. `.json` and `.sig` need nothing.
3. **Backup and restore:** include `app/windows/`. Restore already copies `app/` recursively; backup copies a flat list.
4. **Friend page `klaus-page.html`:**
   - A third tab «Windows». The segmented control becomes three columns; the arrow keys cycle; `aria-label` becomes «Ваше устройство».
   - Detection: `/Windows NT/` in the User-Agent, or `navigator.userAgentData.platform`. Today Windows gets the Android tab.
   - Steps:
     1. «Скачать для Windows» (`/app/windows/KirovVPN-Setup.exe`, shown only if `/app/windows/version.json` exists), with tips for SmartScreen, UAC and Smart App Control;
     2. «Добавить в Kirov VPN»: the browser asks «Открыть приложение Kirov VPN?»; fallback: copy, then Ctrl+V in the window;
     3. the big button.
5. **Texts:** `hwid-limit`, `list-users` and the guide say that a PC counts as a device. The response rule's description becomes «Kirov VPN apps».
6. **Monitor:** no change. A Windows client sends the same GET; Ethernet reports as `wifi`, as on Android; it never sends `w=1`. A friend's phone and PC count as one person.
7. **Docker e2e** (`run-local.sh`, `mock-apis.py`):
   - a fake Windows release (installer, sums, manifest, signature) and publish, retention and 302;
   - a subscription request with the Windows User-Agent and `X-Device-Os: Windows`, recorded as platform Windows;
   - phone plus PC under the device limit;
   - the page's third tab, checked statically;
   - backup and restore of `app/windows/`.
8. **Guide `docs/README.ru.md`:** a new section «Windows» (like section 11 «iPhone»), plus §1 (install and updates), §10 (build) and the §12 subsections on distribution, «Как знакомому подключиться», «Лимит устройств» and «Что панель и серверы хранят».

## 9. Owner steps

Never paste keys, passwords or tokens into a chat; GitHub secrets only. Say "готово".

**Trying a build (every phase):**
1. github.com/klausms17/vpn → Releases → `windows-build-…` → download `KirovVPN-Setup-1.0.N.exe`.
2. Open it. Edge and SmartScreen warn (section 7.4): «Подробнее» → «Выполнить в любом случае», then «Да».
3. The phase lists what to check.

**Before phase 5, once: the update signing key.** It works like the Android signing key, but Windows already has the tool.
1. Start → «Терминал» (or PowerShell) and run:
   ```
   ssh-keygen -t ed25519 -C "Kirov VPN Windows updates" -f kirov-windows-update
   ```
   When it asks for a passphrase, press Enter twice.
2. GitHub → the repository → Settings → Secrets and variables → Actions → New repository secret.
   - Name: `WINDOWS_UPDATE_KEY`.
   - Value: open `kirov-windows-update` (the file without `.pub`) in Notepad, copy everything, paste.
3. Keep both files where the Android key is kept, then delete them from the PC.
4. The next build prints the public key in its release notes, and a session commits it into the source. From then on CI refuses to sign with a different key.

**Phase 5, on the panel:** run `install-panel.sh` again (new Caddy rules and the timer), then `klaus-panel publish-windows`; check the friend page on a PC.

**Phase 6:** the licence and SignPath decision (section 7.4).

**Decisions for later**, from the owner's list of 1 Oct 2026 (items 1–4 of that list are in the phases):
- a second way into every server (XHTTP next to REALITY) and a second server, so failover has somewhere to go: panel settings;
- «Отправить отчёт владельцу»: diagnostics to the owner's Telegram, without IPs, keys or sites, only with the friend's consent;
- signing the programs (section 7.4).

## 10. Phases

Each phase ends in an installer built by CI that the owner installs and tries.

1. **The tunnel on the owner's PC.**
   - Work:
     - libxray: the Xray bump, `BuildOptions.Windows`, `direct.go`, `fsx.Replace`; in `client/`: the model, store, key import, log, `StartFailurePolicy` and the core log's trim;
     - service: binder and resolver, the engine (start, stop, Android's retries), install, uninstall, stop, `should_run`, DPAPI, logs; a reset in place after a move to another network and after sleep (Android's NETWORK rule);
     - pipe with `status`, `import` (keys, not yet subscriptions), `connect`, `disconnect`;
     - a minimal window: paste a key, the big button, status; tray with «Открыть», «Подключить» / «Отключить», «Выход» (or «Отключить VPN и выйти» while connected);
     - the installer and `windows.yml` with the smoke test.
   - CI proves:
     - libxray's tests pass on Windows;
     - the smoke test connects through a local REALITY server, the DNS filter holds and IPv6 fails at once;
     - 50 Start/Stop cycles leak nothing;
     - install, upgrade and uninstall are clean;
     - Android and iOS stay green after the Xray bump.
   - The owner checks:
     - install with the expected warnings;
     - paste his key and connect;
     - a foreign site such as ifconfig.me shows the server's address (Germany), while 2ip.ru, a Russian site that goes directly, shows his own;
     - a DNS leak test (browserleaks.com/dns) shows no provider resolver;
     - the router page and LAN still work;
     - Wi-Fi ↔ cable, sleep and wake;
     - the network icon has no "no internet" mark;
     - the window's colours and text.
2. **Subscriptions and the Android logic.**
   - Work: section 5.2 with its tests; the service runs it; servers and subscriptions in the window (add by link, refresh, select, ping, delete); `klausvpn://` registered.
   - CI proves: Go tests green on Linux; the smoke test adds a subscription from a local server.
   - The owner checks: the friend page button adds the subscription; switching servers; failover when the selected server stops answering (a stopped test server or a dead key). Windows Firewall cannot simulate that: Xray's filters give the service a hard permit.
3. **The window and the tray.**
   - Work: every screen of section 3 in the light look; tray states and menu; toasts; QR from an image, the clipboard or a screenshot; «Журнал»; «Лицензии»; settings; screenshots in CI.
   - CI proves: build, smoke test, and screenshots of every state on `ui-screenshots-windows`.
   - The owner checks: the screens, from the screenshots and on the PC.
4. **Reliability on a laptop, and no need to turn the VPN off.**
   - Work:
     - the triggers of section 2.5; stall check; way back home; recovery and `RestartGuard`; the watchdog; «Обновить списки»; idle CPU and memory budget;
     - a server switch without a break: the proxy outbounds replaced in the running core (row 25), so browsers see no «Сеть изменилась»; network resets behind a WFP hold or a kept adapter;
     - «Программы без VPN» (row 15): banking clients, 1C, games with Russian servers and torrents go directly, through Xray `process` rules;
     - Wi-Fi that needs a sign-in (hotels, cafés): seen by the captive probe of section 2.5, the tunnel pauses for a minute by itself, the sign-in page opens, and the tunnel comes back once the internet works;
     - heavy downloads directly: Windows Update, Steam, Epic and driver downloads, by domain lists, so they are faster and cost the server nothing.
   - CI proves: unit tests for every trigger; the smoke test switches the runner's routes and checks a reset in place; a hot swap keeps a running download alive.
   - The owner checks: a day of normal use with sleep, Wi-Fi changes and a server switch; a program set to go without VPN; a game or Windows update downloading directly.
5. **Updates and the panel.**
   - Work: the manifest, signing and `release` assets; the updater; section 8; the guide.
   - CI proves: updater tests with a test key (rollback, wrong signature, wrong size); `run-local.sh` ends with `ALL CHECKS PASSED` in a cloud session.
   - The owner checks: the update key; `publish-windows` on the panel; a one-click update from 1.0.N to 1.0.N+1; a friend installs from the page.
6. **Friends.**
   - Work: arm64 (Go, wintun's arm64 DLL, Wails; a smoke test on `windows-11-arm`); the signing decision; antivirus submissions; the friend-page guide for SmartScreen and Smart App Control.
   - The owner: first friends.
7. **Later.** An optional kill switch with persistent WFP filters; Android onto the Go logic, one module at a time (optional).

## 11. Risks and unknowns (most serious first)

1. **Unsigned builds.**
   - SmartScreen warns on every new version a friend downloads by hand.
   - Smart App Control blocks it completely.
   - Antivirus false positives are likely for an unsigned Go VPN that installs a driver; they cannot be seen in CI, and a Kaspersky or Dr.Web detection would stop many Russian users.
   - Signing (section 7.4) is the real fix.
2. **Censors may treat a PC differently.**
   - In Sep 2026 a Beeline home user reported that Windows TUN mode (not proxy mode) cut the whole home network down to whitelist-only after 5–15 minutes, while Android on the same line was unaffected. The cause is unknown (net4people/bbs#663).
   - Large waves of REALITY server bans followed on 4 Aug and 21 Sep 2026 (#671).
   - A PC sends all its traffic through the tunnel (updates, game launchers, QUIC), unlike a phone. ru_direct keeps domestic traffic direct, and libxray already refuses QUIC to the proxy with Vision.
   - Watch the owner's own tests closely in phase 1. Server switching, XHTTP and mux support decide how quickly friends recover.
3. **Young upstream code.** Xray's Windows WFP code is from 30 Sep 2026, Wails v3 is a beta (its tray menu bug is worked around: the app shows the menu itself), and Xray's Windows TUN has the two suspected bugs of row 17. Mitigations:
   - pin exact versions;
   - test on the runner;
   - fix upstream first; a `replace` to a fork only if upstream is slow.
4. **The DNS filter breaks some setups:**
   - a local resolver on 127.0.0.1:53;
   - another VPN's DNS;
   - virtual machines whose NAT asks the router's DNS;
   - signing in to a captive portal while connected.

   «Журнал» lists other VPN adapters found. The captive notice says to disconnect, sign in and connect again.
5. **Leaks the DNS filter does not cover.**
   - A program that binds to the physical interface's address, such as WebRTC in a browser, can still reach the internet directly.
   - About a second of direct traffic while the core restarts (point 8 of section 2.4).

   Phase 4 closes the restart gap, and phase 7 offers a full block.
6. **WebView2 missing** on some Windows 10 or "lite" builds, with Microsoft's download possibly unreachable from Russia. Fallback: host the 127 MB standalone runtime on the panel.
7. **Other security software** with its own WFP or TLS inspection (Kaspersky, ESET, AdGuard) may conflict with the tunnel or the filters. A third-party WFP callout can veto even Xray's hard permit.
8. **Wintun stalls.** The unreleased master fixes of row 22 (4–5 s stalls) may show up as hiccups in the health checks.
9. **One VPN for all users of a PC.** Everyone signed in shares its state and servers. Fine for a home PC; documented.
10. **MachineGuid is copied on cloned images.** Two such PCs would share one device slot. Rare at home.
11. **Windows 10 support ended on 14 Oct 2025.** Consumer ESU runs to 12 Oct 2027; Go 1.27 and WebView2 still support Windows 10.
12. **No arm64 device** to try. Only CI on `windows-11-arm` covers it.
13. **The update key** is now a key to friends' PCs, as important as the Android signing key.
    - Losing it stops automatic updates; a new key in a manual release fixes that.
    - A leaked key must be replaced at once.

## 12. Sources

Read on 1 Oct 2026 unless dated. Microsoft's pages were read from their MicrosoftDocs GitHub sources where learn.microsoft.com was blocked. Items marked (summary) were seen only as search summaries.

- **Repository:**
  - `libxray/{libxray.go,config.go,tunnet.go,http.go,cert.go,geo.go}`;
  - `app/src/main/java/com/klausms/vpn/{service,data,ui,core}/` (`TunnelEngine.kt:458` `addDisallowedApplication`);
  - `app/src/main/aidl/`;
  - `server/remnawave/{klaus-panel,install-panel.sh,klaus-monitor.py,klaus-page.html,test/run-local.sh,test/mock-apis.py}`;
  - `.github/workflows/{android.yml,ios.yml,ios-app.yml}`;
  - `docs/ios/PLAN.md`.
- **Xray-core**, read in the Go module cache:
  - pinned `v1.260327.1-0.20260908222543-52a412d9e2f5` (v26.9.9);
  - v26.9.30 `v1.260327.1-0.20260930074004-b26a91de4f32`:
    - `proxy/tun/{tun_windows.go,tun_windows_wfp.go,handler.go,README.md}`;
    - `infra/conf/tun.go`;
    - `transport/internet/{system_dialer.go,dns_skip.go,sockopt_windows.go}`;
    - `features/dns/localdns/client.go`;
    - `common/net/find_process_windows.go`, `app/router/condition.go`;
    - `.github/workflows/scheduled-assets-update.yml` (wintun 0.14.1 and its SHA-256).
  - Pull requests 6853 (WFP, 30 Sep 2026), 6811 (adapter reuse, 27 Sep), 6814 (TUN UDP), 6478 (Wi-Fi preference, 8 Sep); issue 6454: https://github.com/XTLS/Xray-core
- **Go 1.27:** `src/net/{conf.go,dnsconfig_windows.go}`.
- **Libraries:**
  - `golang.zx2c4.com/wintun` (`dll.go`: loads only from the exe's folder or System32);
  - wintun itself, https://github.com/WireGuard/wintun: README, `prebuilt-binaries-license.txt`, `api/adapter.c`, `driver/wintun.inf` (`*IfType` 53), master commits of Feb–Mar 2026;
  - `golang.zx2c4.com/wireguard/windows` v1.0.1 and v1.1.1 (MIT): `tunnel/firewall`, `tunnel/winipcfg`, `tunnel/mtumonitor.go`, `conf/dpapi`;
  - `golang.zx2c4.com/wireguard/ipc/namedpipe`;
  - `github.com/Microsoft/go-winio` v0.6.2 (`pipe.go`);
  - `golang.org/x/sys` v0.48.0 (`svc`, `svc/mgr`).
- **Other clients:**
  - WireGuard for Windows (v1.1.1, 20 Sep 2026): `tunnel/firewall/{blocker.go,rules.go}`, `tunnel/{interfacewatcher.go,pitfalls.go,service.go}`, `manager/install.go`, `docs/{netquirk.md,attacksurface.md}`: https://github.com/WireGuard/wireguard-windows
  - Tailscale: `safesocket/pipe_windows.go`, `wgengine/router/osrouter/router_windows.go`, `net/netns/netns_windows.go`, `net/netmon/netmon.go`, `net/dns/manager_windows.go`, `clientupdate/clientupdate_windows.go`, `clientupdate/distsign`: https://github.com/tailscale/tailscale
  - Mullvad: `windows/winfw`, `talpid-core/src/offline/windows.rs`, `mullvad-daemon/src/system_service.rs`, `mullvad-update/threat-model.md`, `win-split-tunnel`: https://github.com/mullvad/mullvadvpn-app
  - sing-tun and sing-box (GPL, read only, nothing reused): https://github.com/SagerNet/sing-tun, https://github.com/SagerNet/sing-box
  - clash-verge-service-ipc and CVE-2025-50505 (7 Oct 2025), CVE-2026-26422 (6 Jun 2026) at https://nvd.nist.gov
  - v2rayN (1 Oct 2026), hiddify-core, Throne.
- **Wails:**
  - releases (v3.0.0-beta.26, 25 Sep 2026; v2.16.0, 14 Sep 2026): https://github.com/wailsapp/wails/releases;
  - v3 docs in the repository: `docs/mpress/content/{features/menus/systray.md,guides/single-instance.md,guides/server-build.md,reference/frontend-runtime.md,guides/build/windows.md}`;
  - issues 6161, 6045, 4990; pull request 6162.
- **Microsoft:**
  - WFP object management and filter arbitration;
  - DNS Client over HTTPS;
  - multihomed DNS;
  - NCSI overview and FAQ (2025);
  - configuring IPv6 (12 Feb 2026);
  - named pipe security and access rights;
  - `ImpersonateNamedPipeClient`, `HandlerEx`, `SwDeviceCreate`, `NotifyRouteChange2`;
  - power events, and telling Fast Startup from hibernation;
  - WebView2 distribution, security and user data folder;
  - Run and RunOnce keys;
  - SmartScreen reputation (4 May 2026);
  - Smart App Control for developers;
  - Artifact Signing quickstart and FAQ (May 2026);
  - code signing options (Apr 2026);
  - Defender developer FAQ and https://www.microsoft.com/wdsi/filesubmission.
- **Runner images:**
  - https://github.com/actions/runner-images: `images/windows/Windows2025-VS2026-Readme.md` (image 20260922: Inno Setup 6.7.1, WiX 3.14, no NSIS; Go 1.24 default) and `Windows11-Arm64-Readme.md`;
  - actions/setup-go#495;
  - Nebula's `.github/workflows/smoke/smoke-windows.ps1`.
- **Installers:**
  - Inno Setup, https://github.com/jrsoftware/issrc (whatsnew, `Russian.isl`);
  - WiX releases and the OSMF EULA; the end of WiX v3/v4 community support (6 Feb 2025);
  - NSIS history.
- **Updates elsewhere:**
  - WireGuard `docs/attacksurface.md`;
  - Tailscale `distsign`;
  - Mullvad's update threat model;
  - Mozilla's rollback attack (CVE-2020-15663).
- **Signing:** SignPath Foundation terms and origin verification; Certum's 2022 notice on Russia and Belarus (summary); DigiCert release notes, 17 Mar 2022 (summary).
- **Antivirus:** 2dust/v2rayN#1156 and #1296, XTLS/Xray-core#4426, hiddify/hiddify-app#1709; Kaspersky's false-detection process; Go FAQ on antivirus.
- **Russia:**
  - net4people/bbs#490, #546, #605, #663 (2 Sep 2026), #671 (30 Sep 2026);
  - the Mintsifry methodology of Apr 2026 (summaries: xakep.ru, securitylab.ru, meduza.io);
  - localhost proxy scanning (summaries: Habr articles 1020080 and 1022422, Mar–Apr 2026);
  - RKNHardering, https://github.com/xtclovver/RKNHardering.
