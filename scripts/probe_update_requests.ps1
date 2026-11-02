$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$probe=Join-Path $taskRoot ('.local/update-request-probe/'+[Guid]::NewGuid().ToString())
$data=Join-Path $probe 'data';New-Item -ItemType Directory -Force -Path $data | Out-Null
$script=Join-Path $PSScriptRoot 'request_desktop_update.ps1'
$requestFile=Join-Path $data 'desktop-update-request.json'
$status=Join-Path $probe 'status.json'
function Queue($name) {
    $app=Join-Path $probe ($name+'.exe');[IO.File]::WriteAllText($app,'test fixture only')
    & $script -AppPath $app -DataPath $data -StatusPath $status -QueueOnly | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not queue candidate' }
    Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
}
function StartDispatcher($request) {
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$request.coordinatorPath,'-Apply','-DataPath',$data)
    $quoted=($arguments | ForEach-Object { '"'+$_+'"' }) -join ' '
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList $quoted -PassThru -RedirectStandardOutput (Join-Path $probe ([Guid]::NewGuid().ToString()+'.out.log')) -RedirectStandardError (Join-Path $probe ([Guid]::NewGuid().ToString()+'.err.log'))
}
$first=Queue 'superseded'
@{appPath=$first.appPath} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $data 'desktop-active.json') -Encoding UTF8
$held=[IO.File]::Open((Join-Path $data 'update.lock'),'OpenOrCreate','ReadWrite','None')
$worker=$null;$resumed=$null
try {
    $worker=StartDispatcher $first
    Start-Sleep -Seconds 1
    $latest=Queue 'latest'
    if ($latest.id -eq $first.id) { throw 'New requests must have distinct identities' }
    # Simulate loss of the waiting dispatcher. The on-disk request remains authoritative.
    $worker.Kill();$worker.WaitForExit()
    $saved=Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($saved.phase -ne 'queued' -or $saved.id -ne $latest.id) { throw 'Pending update was lost' }
    $fixture=@'
param($AppPath,$PreviousAppPath,$SessionId,$DataPath,$StatusPath,[switch]$WaitForLock)
$ErrorActionPreference='Stop'
Add-Content -LiteralPath (Join-Path $DataPath 'installed.txt') -Value $AppPath
@{appPath=$AppPath} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $DataPath 'desktop-active.json') -Encoding UTF8
@{phase='complete';appPath=$AppPath;message='fixture installation'} | ConvertTo-Json | Set-Content -LiteralPath $StatusPath -Encoding UTF8
'@
    Set-Content -LiteralPath (Join-Path (Split-Path $latest.coordinatorPath) 'reload_desktop.ps1') -Value $fixture -Encoding UTF8
    $resumed=StartDispatcher $saved
    $held.Dispose();$held=$null
    if (-not $resumed.WaitForExit(20000)) { throw 'Dispatcher did not complete' }
    $final=Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($final.phase -ne 'complete') { throw ('Installation failed: '+$final.phase) }
    $calls=@(Get-Content -LiteralPath (Join-Path $data 'installed.txt'))
    if ($calls.Count -ne 1 -or $calls[0] -ne $latest.appPath) { throw 'An obsolete candidate was installed or an installation was duplicated' }
    $again=StartDispatcher $final
    if (-not $again.WaitForExit(10000)) { $again.Kill();throw 'Completed request was not ignored' }
    if (@(Get-Content -LiteralPath (Join-Path $data 'installed.txt')).Count -ne 1) { throw 'Completed request executed twice' }
    @{passed=$true;durableRequest=$true;supersededPendingSkipped=$true;dispatcherRestart=$true;completedNotRepeated=$true;modelCalls=0} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $probe 'report.json') -Encoding UTF8
    Write-Output ('UPDATE_REQUEST_PROBE_PASSED '+$probe)
} finally {
    if ($null -ne $held) { $held.Dispose() }
    foreach($p in @($worker,$resumed)) { if ($null -ne $p -and -not $p.HasExited) { $p.Kill();$p.WaitForExit() } }
}
