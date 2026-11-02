# Compatibility entry point for staged self-updates; uses the verified second-instance handoff.
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$updateRoot=Join-Path $taskRoot '.local/desktop-update'
$stage=Get-Content -Raw -LiteralPath (Join-Path $updateRoot 'pending.json') | ConvertFrom-Json
$PID | Set-Content -LiteralPath (Join-Path $updateRoot 'worker.pid')
& (Join-Path $PSScriptRoot 'reload_desktop.ps1') -AppPath $stage.appPath -PreviousAppPath $stage.previousAppPath -SessionId $stage.sessionId -StatusPath (Join-Path $updateRoot 'status.json')
