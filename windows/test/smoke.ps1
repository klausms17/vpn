# The smoke test of the Windows app on a CI runner, as administrator:
# refuse a folder outside Program Files, install, connect through a local
# REALITY server, check that DNS cannot leave outside the tunnel and that
# IPv6 fails at once, check the server, read the journal, send a site and
# a program directly, restart the tunnel 50 times, kill the service and
# see it come back, start with another adapter holding the tunnel's
# address, install over itself, uninstall. Run by windows.yml; it changes
# the PC's network and installs a service, so never run it on a real PC.
param(
  [Parameter(Mandatory)] [string] $Installer,
  # The folder with kirovctl.exe and testserver.exe.
  [Parameter(Mandatory)] [string] $Tools
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# kirovctl writes UTF-8: the service's messages are Russian.
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$app = Join-Path $env:ProgramFiles 'Kirov VPN'
$data = Join-Path $app 'Data'
$work = Join-Path $env:RUNNER_TEMP 'smoke'
New-Item -ItemType Directory -Force $work | Out-Null

function Check([bool] $ok, [string] $what) {
  if (-not $ok) { throw "FAILED: $what" }
  Write-Host "ok: $what"
}

function Ctl {
  $out = & (Join-Path $Tools 'kirovctl.exe') @args
  $code = $LASTEXITCODE
  $out | ForEach-Object { Write-Host "  $_" }
  if ($code -ne 0) { throw "kirovctl $args failed ($code)" }
  return $out
}

function Install {
  $p = Start-Process $Installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=$work\install.log" -Wait -PassThru
  Check ($p.ExitCode -eq 0) "the installer finished (exit code $($p.ExitCode))"
  Ctl wait-service | Out-Null
}

function Http204 {
  $code = & curl.exe -s -o NUL -w '%{http_code}' --max-time 20 https://www.gstatic.com/generate_204
  return $code -eq '204'
}

# The network card Windows sends through without the tunnel.
function Nic {
  $routes = Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Where-Object { $_.InterfaceAlias -ne 'Kirov VPN' }
  $best = $routes | Sort-Object { $_.RouteMetric + (Get-NetIPInterface -InterfaceIndex $_.InterfaceIndex -AddressFamily IPv4).InterfaceMetric } | Select-Object -First 1
  return $best.InterfaceIndex
}

# Asks 8.8.8.8 for example.com straight through network card $nic, whose
# address is $ip, as a program that ignores the tunnel's DNS would. True if
# it answered within $timeoutMs.
function DnsProbe([int] $nic, [string] $ip, [int] $timeoutMs) {
  $udp = [System.Net.Sockets.UdpClient]::new([System.Net.IPEndPoint]::new([IPAddress]::Parse($ip), 0))
  try {
    # IP_UNICAST_IF (31): this interface whatever the routes say.
    $udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::IP, [System.Net.Sockets.SocketOptionName]31, [System.Net.IPAddress]::HostToNetworkOrder($nic))
    $udp.Client.ReceiveTimeout = $timeoutMs
    [byte[]] $query = 0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 3, 0x63, 0x6f, 0x6d, 0, 0, 1, 0, 1
    $from = [System.Net.IPEndPoint]::new([IPAddress]::Any, 0)
    try {
      [void] $udp.Send($query, $query.Length, '8.8.8.8', 53)
      [void] $udp.Receive([ref] $from)
      return $true
    } catch [System.Net.Sockets.SocketException] { return $false }
  } finally {
    $udp.Close()
  }
}

function DirectDns {
  $nic = Nic
  $ip = (Get-NetIPAddress -InterfaceIndex $nic -AddressFamily IPv4 | Select-Object -First 1).IPAddress
  return DnsProbe $nic $ip 4000
}

function ServiceProcess { Get-Process -Id (Get-CimInstance Win32_Service -Filter "Name='KirovVPN'").ProcessId }

# Lines of the server's access log for gstatic, by name or by the outbound
# the server sends it through: how often the test site was reached through
# the server.
function Through { @(Select-String -Path "$work\server.log" -Pattern 'gstatic' -ErrorAction SilentlyContinue).Count }

# Saves settings and waits until the tunnel has restarted with them.
function Settings([string] $json) {
  $json | & (Join-Path $Tools 'kirovctl.exe') set-settings | Out-Null
  Check ($LASTEXITCODE -eq 0) "settings saved: $json"
  # The service restarts the tunnel 0.8 s after the last change.
  Start-Sleep -Seconds 2
  Ctl wait-connected | Out-Null
}

# Starts the stand-in for another VPN holding the tunnel's address.
function OtherVpn([string[]] $more) {
  Remove-Item "$work\other.ready" -ErrorAction SilentlyContinue
  $o = Start-Process (Join-Path $Tools 'othervpn.exe') -ArgumentList (@('-ready', "$work\other.ready") + $more) -PassThru -RedirectStandardError "$work\other.err"
  for ($i = 0; $i -lt 60 -and -not (Test-Path "$work\other.ready"); $i++) { Start-Sleep -Milliseconds 500 }
  Check (Test-Path "$work\other.ready") "another adapter has the tunnel's address ($($more -join ' '))"
  return $o
}

$server = $null
try {
  Write-Host '::group::Only into Program Files'
  $p = Start-Process $Installer -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/DIR=$work\elsewhere", "/LOG=$work\elsewhere.log" -Wait -PassThru
  Check ($p.ExitCode -eq 7 -and -not (Test-Path "$work\elsewhere\KirovVPNService.exe")) "another folder is refused (exit code $($p.ExitCode))"
  Write-Host '::endgroup::'

  Write-Host '::group::Install'
  Install
  $svc = Get-CimInstance Win32_Service -Filter "Name='KirovVPN'"
  Check ($svc.State -eq 'Running') 'the service runs'
  Check ($svc.StartMode -eq 'Auto') 'the service starts at boot'
  Check ($svc.StartName -eq 'LocalSystem') 'the service runs as LocalSystem'
  $run = Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'Kirov VPN'
  Check ($run.'Kirov VPN' -like '*KirovVPN.exe" --tray') 'the tray icon starts at logon'
  $acl = (Get-Acl $data).Access | ForEach-Object { $_.IdentityReference.Value }
  Check (-not ($acl | Where-Object { $_ -notmatch 'SYSTEM|Administrators|Администраторы' })) "only SYSTEM and administrators may open the data folder ($($acl -join ', '))"
  $sddl = "$(Ctl pipe-sddl)"
  Check ($sddl -match '^O:SY' -and $sddl -match ';;;IU\)' -and $sddl -match 'S:[A-Z]*\(ML;;NWNRNX;;;ME\)') "the pipe is SYSTEM's, for signed-in users and not for sandboxes ($sddl)"
  Write-Host '::endgroup::'

  Write-Host '::group::The window loads'
  $ui = Start-Process (Join-Path $app 'KirovVPN.exe') -ArgumentList '--selftest' -Wait -PassThru
  Check ($ui.ExitCode -eq 0) "the window loaded its page (exit code $($ui.ExitCode))"
  Write-Host '::endgroup::'

  Write-Host '::group::Connect through a local REALITY server'
  Check (DirectDns) 'without the tunnel, 8.8.8.8 answers DNS directly'
  $server = Start-Process (Join-Path $Tools 'testserver.exe') -ArgumentList '-link', "$work\link.txt", '-log', "$work\server.log" -PassThru -RedirectStandardError "$work\server.err"
  for ($i = 0; $i -lt 60 -and -not (Test-Path "$work\link.txt"); $i++) { Start-Sleep -Milliseconds 500 }
  Get-Content "$work\link.txt" | & (Join-Path $Tools 'kirovctl.exe') import
  Check ($LASTEXITCODE -eq 0) 'the key was added'
  # A key whose XHTTP "extra" asks for xdrive's local storage, which would
  # write files as SYSTEM.
  $extra = [uri]::EscapeDataString('{"downloadSettings":{"network":"xdrive","xdriveSettings":{"service":"local","remoteFolder":"C:\\KirovSmokeXdrive"}}}')
  $out = "vless://11111111-2222-3333-4444-555555555555@127.0.0.1:1?type=xhttp&security=tls&pcs=$('ab' * 32)&extra=$extra#bad" | & (Join-Path $Tools 'kirovctl.exe') import
  Check ($LASTEXITCODE -ne 0 -and "$out" -match 'downloadSettings\.network: xdrive' -and -not (Test-Path 'C:\KirovSmokeXdrive')) "a key that would write files as SYSTEM is refused ($out)"
  Ctl connect | Out-Null
  Check ((Get-NetAdapter -Name 'Kirov VPN').Status -eq 'Up') 'the Kirov VPN adapter is up'
  Check (Http204) 'a site answers through the tunnel'
  # Xray writes its access log a moment later.
  for ($i = 0; $i -lt 20 -and -not (Select-String -Path "$work\server.log" -Pattern 'accepted' -Quiet -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 250 }
  Check ((Select-String -Path "$work\server.log" -Pattern 'accepted' -Quiet)) 'the traffic went through the server'
  Check ($null -ne (Resolve-DnsName example.com -DnsOnly -Type A -ErrorAction SilentlyContinue)) 'names resolve through the tunnel'
  Check (-not (DirectDns)) 'DNS outside the tunnel is blocked'
  $sw = [Diagnostics.Stopwatch]::StartNew()
  & curl.exe -6 -s -o NUL --max-time 10 https://www.google.com
  $code = $LASTEXITCODE
  $sw.Stop()
  Check ($code -ne 0 -and $sw.Elapsed.TotalSeconds -lt 3) "IPv6 fails at once (curl $code after $([int]$sw.Elapsed.TotalMilliseconds) ms)"
  Write-Host '::endgroup::'

  Write-Host '::group::Server checks, the journal and the settings'
  $pings = (Ctl ping | Select-Object -Last 1) | ConvertFrom-Json
  $check = @($pings.PSObject.Properties)[0].Value
  Check ($check.state -eq 'ok') "the server's check went through it ($($check | ConvertTo-Json -Compress))"
  $journal = (& (Join-Path $Tools 'kirovctl.exe') logs) -join "`n"
  Check ($LASTEXITCODE -eq 0 -and $journal -match 'tunnel up' -and $journal -notmatch '127\.0\.0\.1' -and $journal -notmatch 'vless://') 'the journal tells what happened, without addresses or keys'
  # The check's own request reaches the access log a moment later.
  Start-Sleep -Seconds 3
  $before = Through
  Settings '{"mode":"ru_direct","directSites":["gstatic.com"],"torrentsDirect":true,"autoConnect":true}'
  Check (Http204) 'a site set to go directly answers'
  Start-Sleep -Seconds 3
  Check ((Through) -eq $before) 'and did not go through the server'
  Settings '{"mode":"ru_direct","directPrograms":["curl.exe"],"torrentsDirect":true,"autoConnect":true}'
  Check (Http204) 'a program set to go directly reaches the site'
  Start-Sleep -Seconds 3
  Check ((Through) -eq $before) 'and its traffic did not go through the server'
  $code = (Invoke-WebRequest -Uri 'https://www.gstatic.com/generate_204' -TimeoutSec 20).StatusCode
  for ($i = 0; $i -lt 20 -and (Through) -eq $before; $i++) { Start-Sleep -Milliseconds 250 }
  Check ($code -eq 204 -and (Through) -gt $before) 'other programs still go through the server'
  # A change restarts the core, and the tunnel's adapter, routes and filters
  # with it. Meanwhile nothing may go around the tunnel: probe all the while.
  $nic = Nic
  $ip = (Get-NetIPAddress -InterfaceIndex $nic -AddressFamily IPv4 | Select-Object -First 1).IPAddress
  '{"mode":"ru_direct","directSites":["example.org"],"torrentsDirect":true,"autoConnect":true}' | Set-Content "$work\settings.json"
  $set = Start-Process (Join-Path $Tools 'kirovctl.exe') -ArgumentList 'set-settings' -RedirectStandardInput "$work\settings.json" -NoNewWindow -PassThru
  # Without the handle taken now, PowerShell loses the exit code.
  $null = $set.Handle
  $probes, $answered = 0, 0
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt 5) {
    $probes++
    if (DnsProbe $nic $ip 50) { $answered++ }
  }
  $set.WaitForExit()
  Ctl wait-connected | Out-Null
  Check ($set.ExitCode -eq 0 -and $answered -eq 0) "nothing goes around the tunnel while it restarts ($answered of $probes DNS probes answered)"
  Check (Select-String -Path "$data\logs\service.log" -Pattern 'holding traffic while the tunnel restarts' -SimpleMatch -Quiet) 'the service held traffic during the restart'
  Check (Http204) 'and let it through again'
  Settings '{"mode":"ru_direct","torrentsDirect":true,"autoConnect":true}'
  Write-Host '::endgroup::'

  Write-Host '::group::50 restarts of the tunnel'
  Ctl disconnect | Out-Null
  Ctl connect | Out-Null
  $p = ServiceProcess
  $handles, $threads = $p.HandleCount, $p.Threads.Count
  $sw = [Diagnostics.Stopwatch]::StartNew()
  for ($i = 0; $i -lt 50; $i++) {
    Ctl disconnect | Out-Null
    Ctl connect | Out-Null
  }
  $sw.Stop()
  $p.Refresh()
  Write-Host "50 restarts in $([int]$sw.Elapsed.TotalSeconds) s; handles $handles -> $($p.HandleCount), threads $threads -> $($p.Threads.Count)"
  Check ($p.HandleCount - $handles -lt 150) 'no handles leak'
  Check ($p.Threads.Count - $threads -lt 30) 'no threads leak'
  Check (Http204) 'a site still answers'
  Write-Host '::endgroup::'

  Write-Host '::group::The service dies while connected'
  $old = (Get-CimInstance Win32_Service -Filter "Name='KirovVPN'").ProcessId
  Stop-Process -Id $old -Force
  # Its recovery actions start it again after 2 seconds.
  for ($i = 0; $i -lt 60; $i++) {
    $svc = Get-CimInstance Win32_Service -Filter "Name='KirovVPN'"
    if ($svc.State -eq 'Running' -and $svc.ProcessId -notin 0, $old) { break }
    Start-Sleep -Milliseconds 500
  }
  Check ($svc.ProcessId -notin 0, $old) 'the service was started again'
  Ctl wait-service | Out-Null
  Ctl wait-connected | Out-Null
  Check (Http204) 'and brought the tunnel back'
  Start-Sleep -Seconds 10
  Check (Http204) 'which still works ten seconds later'
  $adapters = @(Get-NetAdapter -IncludeHidden | Where-Object { $_.Name -like 'Kirov VPN*' })
  Check ($adapters.Count -eq 1) "one tunnel adapter ($($adapters.Name -join ', '))"
  Write-Host '::endgroup::'

  Write-Host '::group::Another program has the tunnel address'
  Ctl disconnect | Out-Null
  Copy-Item (Join-Path $app 'wintun.dll') $Tools -Force
  # Left behind on an adapter that is not connected: the service takes it off.
  $other = OtherVpn @()
  Ctl connect | Out-Null
  Check (Http204) 'an address left on another adapter does not stop the tunnel'
  Check (Select-String -Path "$data\logs\service.log" -Pattern "took the tunnel's address off the disconnected adapter" -SimpleMatch -Quiet) 'the service took the address off it'
  Ctl disconnect | Out-Null
  Stop-Process -Id $other.Id
  # Held by another VPN that runs: the error names it, without retries.
  $other = OtherVpn @('-up')
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $out = & (Join-Path $Tools 'kirovctl.exe') connect
  $code = $LASTEXITCODE
  $sw.Stop()
  Write-Host "  connect with another VPN up: exit $code after $([int]$sw.Elapsed.TotalSeconds) s: $out"
  Check ($code -ne 0 -and "$out" -match 'Other VPN' -and $sw.Elapsed.TotalSeconds -lt 10) 'another VPN holding the address is named at once'
  Stop-Process -Id $other.Id
  for ($i = 0; $i -lt 40 -and (Get-NetAdapter -Name 'Other VPN' -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 250 }
  Ctl connect | Out-Null
  Check (Http204) 'once it is gone, the tunnel comes up'
  Write-Host '::endgroup::'

  Write-Host '::group::Install over itself'
  Install
  # The tunnel was on, so the new service brings it back by itself.
  Ctl connect | Out-Null
  Check (Http204) 'the tunnel came back with the saved key'
  Ctl disconnect | Out-Null
  Check (DirectDns) 'without the tunnel, DNS works directly again'
  Write-Host '::endgroup::'

  Write-Host '::group::Uninstall'
  Start-Process (Join-Path $app 'unins000.exe') -ArgumentList '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART' -Wait
  # The uninstaller runs on as a copy of itself; wait for it.
  for ($i = 0; $i -lt 120 -and (Test-Path (Join-Path $app 'KirovVPNService.exe')); $i++) { Start-Sleep -Milliseconds 500 }
  Check ($null -eq (Get-Service KirovVPN -ErrorAction SilentlyContinue)) 'the service is gone'
  Check ($null -eq (Get-NetAdapter -Name 'Kirov VPN' -ErrorAction SilentlyContinue)) 'the adapter is gone'
  Check (-not (Test-Path $data)) 'the keys and logs are gone'
  Check (-not (Test-Path (Join-Path $app 'KirovVPNService.exe'))) 'the programs are gone'
  Write-Host '::endgroup::'
  Write-Host 'SMOKE TEST PASSED'
} catch {
  Write-Host "::error::$_"
  foreach ($log in @("$work\install.log", "$data\logs\service.log", "$data\logs\xray.log", "$data\logs\go-crash.log", "$work\server.log", "$work\server.err", "$work\other.err", "$env:LOCALAPPDATA\Kirov VPN\ui.log")) {
    if (Test-Path $log) {
      Write-Host "::group::$log"
      Get-Content $log -Tail 200
      Write-Host '::endgroup::'
    }
  }
  throw
} finally {
  # The tunnel first: without its server it would cut the runner off.
  Stop-Service -Name KirovVPN -ErrorAction SilentlyContinue
  if ($server) { Stop-Process -Id $server.Id -ErrorAction SilentlyContinue }
  Get-Process othervpn -ErrorAction SilentlyContinue | Stop-Process -ErrorAction SilentlyContinue
}
