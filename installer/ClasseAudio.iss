#define MyAppName "ClasseAudio"
#define MyAppVersion "0.1.0"
#define MyAppPublisher "ClasseAudio"
#define MyAppExeName "ClasseAudio.exe"

[Setup]
AppId={{3F0B521E-12AB-45BA-9C43-8F6A8A674142}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\ClasseAudio
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=ClasseAudio-Setup-x64
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
UninstallDisplayIcon={app}\{#MyAppExeName}

[Languages]
Name: "french"; MessagesFile: "compiler:Languages\French.isl"

[Tasks]
Name: "desktopicon"; Description: "Créer un raccourci sur le Bureau"; GroupDescription: "Raccourcis :"; Flags: unchecked

[Files]
Source: "..\publish\ClasseAudio.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\publish\runtime\livekit-server.exe"; DestDir: "{app}\runtime"; Flags: ignoreversion
Source: "..\publish\runtime\LIVEKIT-LICENSE"; DestDir: "{app}\runtime"; Flags: ignoreversion
Source: "..\README.md"; DestDir: "{app}"; DestName: "Lisez-moi.txt"; Flags: ignoreversion

[Icons]
Name: "{group}\ClasseAudio"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\ClasseAudio"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon
