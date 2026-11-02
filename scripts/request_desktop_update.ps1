param(
    [string]$AppPath='',
    [string]$SessionId='',
    [string]$DataPath=(Join-Path $env:LOCALAPPDATA 'Tethium'),
    [string]$StatusPath='',
    [switch]$Apply,
    [switch]$QueueOnly
)
$ErrorActionPreference='Stop'
$DataPath=[IO.Path]::GetFullPath($DataPath)
New-Item -ItemType Directory -Force -Path $DataPath | Out-Null
$requestFile=Join-Path $DataPath 'desktop-update-request.json'
if (-not $StatusPath) { $StatusPath=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../.local/desktop-update/status.json')) }
function RequestLock {
    $until=[DateTime]::UtcNow.AddSeconds(20)
    do {
        try { return [IO.File]::Open((Join-Path $DataPath 'update-request.lock'),'OpenOrCreate','ReadWrite','None') }
        catch { if ([DateTime]::UtcNow -gt $until) { throw }; Start-Sleep -Milliseconds 100 }
    } while ($true)
}
function SaveRequest($value) {
    $temporary=$requestFile+'.'+[Guid]::NewGuid().ToString()+'.tmp'
    [IO.File]::WriteAllText($temporary,($value | ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    if (Test-Path -LiteralPath $requestFile) { [IO.File]::Replace($temporary,$requestFile,$requestFile+'.previous') }
    else { [IO.File]::Move($temporary,$requestFile) }
}
function ReadRequest { Get-Content -LiteralPath $requestFile -Raw -Encoding UTF8 | ConvertFrom-Json }
function LaunchHidden($script,$arguments,$log) {
    $values=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$script)+$arguments
    $quoted=($values | ForEach-Object { '"'+$_.ToString().Replace('"','\"')+'"' }) -join ' '
    Start-Process powershell.exe -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput ($log+'.out.log') -RedirectStandardError ($log+'.err.log')
}
if (-not $Apply) {
    $AppPath=[IO.Path]::GetFullPath($AppPath)
    if (-not (Test-Path -LiteralPath $AppPath -PathType Leaf)) { throw 'Candidate application does not exist' }
    $id=[Guid]::NewGuid().ToString()
    $bundle=Join-Path $DataPath ('updates/requests/'+$id)
    New-Item -ItemType Directory -Force -Path $bundle | Out-Null
    # An updater keeps its own scripts: subsequent source changes cannot change a queued installation.
    Copy-Item -LiteralPath $PSCommandPath -Destination (Join-Path $bundle 'request_desktop_update.ps1')
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'reload_desktop.ps1') -Destination (Join-Path $bundle 'reload_desktop.ps1')
    $request=@{id=$id;appPath=$AppPath;sessionId=$SessionId;phase='queued';requestedAt=[DateTime]::UtcNow.ToString('o');coordinatorPath=(Join-Path $bundle 'request_desktop_update.ps1');statusPath=[IO.Path]::GetFullPath($StatusPath);attemptStatusPath=(Join-Path $bundle 'status.json')}
    $guard=RequestLock
    try { SaveRequest $request } finally { $guard.Dispose() }
    if (-not $QueueOnly) { $process=LaunchHidden $request.coordinatorPath @('-Apply','-DataPath',$DataPath) (Join-Path $bundle 'coordinator'); $request.workerPid=$process.Id }
    $request | ConvertTo-Json
    exit 0
}
$dispatcher=$null
try {
    try { $dispatcher=[IO.File]::Open((Join-Path $DataPath 'update-dispatch.lock'),'OpenOrCreate','ReadWrite','None') } catch { exit 0 }
    while ($true) {
        $guard=RequestLock
        try {
            if (-not (Test-Path -LiteralPath $requestFile)) { $dispatcher.Dispose();$dispatcher=$null;break }
            $request=ReadRequest
            if ($request.phase -notin @('queued','installing')) { $dispatcher.Dispose();$dispatcher=$null;break }
        } finally { $guard.Dispose() }
        # Wait outside the request lock so newer requests can replace the pending candidate.
        try { $updateLock=[IO.File]::Open((Join-Path $DataPath 'update.lock'),'OpenOrCreate','ReadWrite','None');$updateLock.Dispose() }
        catch { Start-Sleep -Seconds 2;continue }
        $guard=RequestLock
        try { $request=ReadRequest;$request.phase='installing';SaveRequest $request } finally { $guard.Dispose() }
        $outcome='failed';$message='Update did not complete'
        try {
            $active=Get-Content -LiteralPath (Join-Path $DataPath 'desktop-active.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            $attemptStatus=$request.attemptStatusPath
            if (-not $attemptStatus) { $attemptStatus=Join-Path (Split-Path $request.coordinatorPath) 'status.json' }
            $saved=$null
            if (Test-Path -LiteralPath $attemptStatus) { $saved=Get-Content -LiteralPath $attemptStatus -Raw -Encoding UTF8 | ConvertFrom-Json }
            if ($saved.phase -eq 'complete' -and $saved.appPath -eq $request.appPath -and $active.appPath -eq $request.appPath) {
                $outcome='complete';$message='Previously completed installation recovered'
            } else {
                $reload=Join-Path (Split-Path $request.coordinatorPath) 'reload_desktop.ps1'
                $process=LaunchHidden $reload @('-AppPath',$request.appPath,'-PreviousAppPath',$active.appPath,'-SessionId',$request.sessionId,'-DataPath',$DataPath,'-StatusPath',$attemptStatus,'-WaitForLock') (Join-Path (Split-Path $request.coordinatorPath) 'reload')
                New-Item -ItemType Directory -Force -Path (Split-Path $request.statusPath) | Out-Null
                do {
                    $ended=$process.WaitForExit(500)
                    if (Test-Path -LiteralPath $attemptStatus) {
                        try { $state=Get-Content -LiteralPath $attemptStatus -Raw -Encoding UTF8 | ConvertFrom-Json
                            if ($state.workerPid -eq $process.Id -or $ended) { Copy-Item -LiteralPath $attemptStatus -Destination $request.statusPath -Force }
                        } catch { }
                    }
                } while (-not $ended)
                $state=Get-Content -LiteralPath $attemptStatus -Raw -Encoding UTF8 | ConvertFrom-Json
                if ($state.appPath -eq $request.appPath) { $outcome=$state.phase;$message=$state.message }
                if ($outcome -notin @('complete','deferred')) { $outcome='failed' }
            }
        } catch { $message=$_.Exception.Message }
        @{id=$request.id;phase=$outcome;message=$message;appPath=$request.appPath;time=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $request.coordinatorPath) 'result.json') -Encoding UTF8
        $guard=RequestLock
        try {
            $latest=ReadRequest
            if ($latest.id -eq $request.id) { $latest.phase=$outcome;SaveRequest $latest }
        } finally { $guard.Dispose() }
    }
} finally { if ($null -ne $dispatcher) { $dispatcher.Dispose() } }
