# The smoke test of the Windows app on a CI runner, as administrator:
# refuse a folder outside Program Files, install, connect through a local
# REALITY server, check that DNS cannot leave outside the tunnel and that
# IPv6 fails at once, restart the tunnel 50 times, install over itself,
# uninstall. Run by windows.yml; it changes the PC's network and installs
# a service, so never run it on a real PC.
param(
  [Parameter(Mandatory)] [string] $Installer,
  # The folder with kirovctl.exe and testserver.exe.
  [Parameter(Mandatory)] [string] $Tools
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

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

# Asks 8.8.8.8 for example.com straight through the network card, as a
# program that ignores the tunnel's DNS would. True if it answered.
function DirectDns {
  $nic = Nic
  $ip = (Get-NetIPAddress -InterfaceIndex $nic -AddressFamily IPv4 | Select-Object -First 1).IPAddress
  $udp = [System.Net.Sockets.UdpClient]::new([System.Net.IPEndPoint]::new([IPAddress]::Parse($ip), 0))
  try {
    # IP_UNICAST_IF (31): this interface whatever the routes say.
    $udp.Client.SetSocketOption([System.Net.Sockets.SocketOptionLevel]::IP, [System.Net.Sockets.SocketOptionName]31, [System.Net.IPAddress]::HostToNetworkOrder([int]$nic))
    $udp.Client.ReceiveTimeout = 4000
    [byte[]] $query = 0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 0x65, 0x78, 0x61, 0x6d, 0x70, 0x6c, 0x65, 3, 0x63, 0x6f, 0x6d, 0, 0, 1, 0, 1
    [void] $udp.Send($query, $query.Length, '8.8.8.8', 53)
    $from = [System.Net.IPEndPoint]::new([IPAddress]::Any, 0)
    try { [void] $udp.Receive([ref] $from); return $true } catch [System.Net.Sockets.SocketException] { return $false }
  } finally {
    $udp.Close()
  }
}

function ServiceProcess { Get-Process -Id (Get-CimInstance Win32_Service -Filter "Name='KirovVPN'").ProcessId }

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
  foreach ($log in @("$work\install.log", "$data\logs\service.log", "$data\logs\xray.log", "$data\logs\go-crash.log", "$work\server.log", "$work\server.err", "$env:LOCALAPPDATA\Kirov VPN\ui.log")) {
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
}
