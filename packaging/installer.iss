; Inno Setup script: builds dist\Bloom-Setup.exe from dist\Bloom\
; Installs for the current user only (no admin rights needed).
#define AppName "Bloom"
#define AppVersion "0.3.0"

[Setup]
AppId={{6B4CF6A1-3FCF-4F9F-B10D-B100D0000002}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher=Bloom
DefaultDirName={localappdata}\Programs\Bloom
DefaultGroupName=Bloom
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\dist
OutputBaseFilename=Bloom-Setup
SetupIconFile=..\bloom\assets\icon.ico
UninstallDisplayIcon={app}\Bloom.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes

[Tasks]
Name: "autostart"; Description: "Start Bloom when I log in"; GroupDescription: "Options:"
Name: "contextmenu"; Description: "Add ""Open in Bloom"" to the right-click menu"; GroupDescription: "Options:"

[Files]
Source: "..\dist\Bloom\*"; DestDir: "{app}"; Flags: recursesubdirs ignoreversion

[Icons]
Name: "{group}\Bloom"; Filename: "{app}\Bloom.exe"
Name: "{group}\Uninstall Bloom"; Filename: "{uninstallexe}"

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Bloom"; ValueData: """{app}\Bloom.exe"""; Tasks: autostart; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Classes\*\shell\Bloom"; ValueType: string; ValueData: "Open in Bloom"; Tasks: contextmenu; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\*\shell\Bloom"; ValueType: string; ValueName: "Icon"; ValueData: "{app}\Bloom.exe"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\*\shell\Bloom\command"; ValueType: string; ValueData: """{app}\Bloom.exe"" --pick ""%1"""; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\Directory\shell\Bloom"; ValueType: string; ValueData: "Open in Bloom"; Tasks: contextmenu; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\Directory\shell\Bloom"; ValueType: string; ValueName: "Icon"; ValueData: "{app}\Bloom.exe"; Tasks: contextmenu
Root: HKCU; Subkey: "Software\Classes\Directory\shell\Bloom\command"; ValueType: string; ValueData: """{app}\Bloom.exe"" --pick ""%1"""; Tasks: contextmenu

[Run]
Filename: "{app}\Bloom.exe"; Description: "Start Bloom now"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "taskkill.exe"; Parameters: "/IM Bloom.exe /F"; Flags: runhidden; RunOnceId: "StopBloom"
