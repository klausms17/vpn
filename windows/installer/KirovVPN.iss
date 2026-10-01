; The Kirov VPN installer for Windows (Inno Setup 6.7 or later).
; CI compiles it from a staging folder:
;   iscc /DAppVersion=1.0.N /DStage=<folder> KirovVPN.iss
; <folder> holds KirovVPN.exe, KirovVPNService.exe, wintun.dll, geo\,
; licenses\ and MicrosoftEdgeWebview2Setup.exe.

#ifndef AppVersion
  #error AppVersion is not defined
#endif
#ifndef Stage
  #error Stage is not defined
#endif

[Setup]
; Never change the AppId: Windows tells installations apart by it.
AppId={{D17F6D05-1CC3-4C33-B4E8-B8DA1260E249}
AppName=Kirov VPN
AppVersion={#AppVersion}
AppVerName=Kirov VPN {#AppVersion}
AppPublisher=Kirov VPN
VersionInfoVersion={#AppVersion}
VersionInfoProductName=Kirov VPN
VersionInfoDescription=Установка Kirov VPN
DefaultDirName={autopf}\Kirov VPN
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
; Windows 10 1809: the oldest with every network call the service uses.
MinVersion=10.0.17763
SetupIconFile=..\assets\app.ico
UninstallDisplayIcon={app}\KirovVPN.exe
UninstallDisplayName=Kirov VPN
WizardStyle=modern
OutputDir=output
OutputBaseFilename=KirovVPN-Setup-{#AppVersion}
Compression=lzma2/ultra64
SolidCompression=yes
; The installer stops the service and closes the windows itself.
CloseApplications=no
RestartIfNeededByRun=no

[Languages]
Name: "ru"; MessagesFile: "compiler:Languages\Russian.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "{#Stage}\KirovVPN.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\KirovVPNService.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\wintun.dll"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#Stage}\geo\*"; DestDir: "{app}\geo"; Flags: ignoreversion recursesubdirs
Source: "{#Stage}\licenses\*"; DestDir: "{app}\licenses"; Flags: ignoreversion recursesubdirs
Source: "{#Stage}\MicrosoftEdgeWebview2Setup.exe"; DestDir: "{tmp}"; Flags: deleteafterinstall; Check: NeedsWebView2

[Icons]
Name: "{autoprograms}\Kirov VPN"; Filename: "{app}\KirovVPN.exe"

[Registry]
; The tray icon starts for every user at logon, without its window.
Root: HKLM; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Kirov VPN"; ValueData: """{app}\KirovVPN.exe"" --tray"; Flags: uninsdeletevalue

[Run]
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; StatusMsg: "Установка WebView2…"; Flags: waituntilterminated; Check: NeedsWebView2
Filename: "{app}\KirovVPNService.exe"; Parameters: "install"; StatusMsg: "Запуск службы Kirov VPN…"; Flags: runhidden waituntilterminated
Filename: "{app}\KirovVPN.exe"; Description: "Открыть Kirov VPN"; Flags: postinstall nowait runasoriginaluser skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM KirovVPN.exe"; Flags: runhidden waituntilterminated; RunOnceId: "CloseWindows"
Filename: "{app}\KirovVPNService.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "RemoveService"

[UninstallDelete]
; The saved keys, settings and logs.
Type: filesandordirs; Name: "{app}\Data"

[Code]
const
  WebView2Key = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

function WebView2Version(Root: Integer; Key: String): String;
begin
  if not RegQueryStringValue(Root, Key, 'pv', Result) then
    Result := '';
end;

// WebView2 is part of Windows 11 and of most Windows 10 installations; the
// bootstrapper adds it where it is missing.
function NeedsWebView2(): Boolean;
var
  V: String;
begin
  V := WebView2Version(HKLM32, WebView2Key);
  if (V = '') or (V = '0.0.0.0') then
    V := WebView2Version(HKCU, WebView2Key);
  Result := (V = '') or (V = '0.0.0.0');
end;

// An update replaces running files: stop the service and close the
// windows first. The service runs as SYSTEM from {app} and keeps the keys
// there, so only Program Files will do, whatever /DIR= says: users can
// change folders elsewhere.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
  Service: String;
begin
  if CompareText(ExpandConstant('{app}'), ExpandConstant('{commonpf}\Kirov VPN')) <> 0 then
  begin
    Result := 'Kirov VPN устанавливается только в папку ' + ExpandConstant('{commonpf}\Kirov VPN') + '.';
    exit;
  end;
  Result := '';
  Service := ExpandConstant('{app}\KirovVPNService.exe');
  if FileExists(Service) then
  begin
    if not Exec(Service, 'stop', '', SW_HIDE, ewWaitUntilTerminated, Code) or (Code <> 0) then
      Result := 'Не удалось остановить службу Kirov VPN. Перезагрузите компьютер и запустите установку снова.';
  end;
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM KirovVPN.exe', '', SW_HIDE, ewWaitUntilTerminated, Code);
end;
