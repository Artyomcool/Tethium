param([switch]$Disable,[switch]$CheckOnly,
    [string]$DataPath=(Join-Path $env:LOCALAPPDATA 'Tethium'),
    [string]$StartupPath=[Environment]::GetFolderPath('Startup'))
$ErrorActionPreference='Stop'
$DataPath=[IO.Path]::GetFullPath($DataPath);$StartupPath=[IO.Path]::GetFullPath($StartupPath)
$link=Join-Path $StartupPath 'Tethium.lnk'
if($CheckOnly){[pscustomobject]@{enabled=(Test-Path -LiteralPath $link);shortcut=$link;launcher=(Join-Path $DataPath 'launch.ps1')}|ConvertTo-Json;exit 0}
if($Disable){if(Test-Path -LiteralPath $link){Remove-Item -LiteralPath $link};Write-Output 'Tethium autostart disabled';exit 0}
New-Item -ItemType Directory -Force -Path $DataPath,$StartupPath,(Join-Path $DataPath 'branding')|Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'launch_desktop.ps1') -Destination (Join-Path $DataPath 'launch.ps1') -Force
$branding=Join-Path $DataPath 'branding/tethium.ico'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../src/main/resources/dev/tethium/desktop/branding/tethium.ico') -Destination $branding -Force
$shell=New-Object -ComObject WScript.Shell
$shortcut=$shell.CreateShortcut($link)
$shortcut.TargetPath=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
$shortcut.Arguments='-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+(Join-Path $DataPath 'launch.ps1')+'" -Tray -DataPath "'+$DataPath+'"'
$shortcut.WorkingDirectory=$DataPath;$shortcut.IconLocation=$branding+',0';$shortcut.WindowStyle=7
$shortcut.Description='Start Tethium in the system tray at Windows sign-in'
$shortcut.Save()
Write-Output $link
