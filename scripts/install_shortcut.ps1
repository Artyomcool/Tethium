$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$data = Join-Path $env:LOCALAPPDATA 'Tethium'
$branding = Join-Path $data 'branding'
New-Item -ItemType Directory -Force -Path $branding | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'launch_desktop.ps1') -Destination (Join-Path $data 'launch.ps1') -Force
Copy-Item -LiteralPath (Join-Path $taskRoot 'src/main/resources/dev/tethium/desktop/branding/tethium.ico') -Destination (Join-Path $branding 'tethium.ico') -Force
$desktop = [Environment]::GetFolderPath('Desktop')
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut((Join-Path $desktop 'Tethium.lnk'))
$shortcut.TargetPath = Join-Path $PSHOME 'powershell.exe'
$shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $data 'launch.ps1') + '"'
$shortcut.WorkingDirectory = $data
$shortcut.IconLocation = (Join-Path $branding 'tethium.ico') + ',0'
$shortcut.Description = 'Tethium'
$shortcut.WindowStyle = 7
$shortcut.Save()
Write-Output (Join-Path $desktop 'Tethium.lnk')
