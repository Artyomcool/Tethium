param(
    [string]$AppPath='',
    [string]$SessionId='',
    [string]$DataPath=(Join-Path $env:LOCALAPPDATA 'Tethium'),
    [string]$StatusPath='',
    [switch]$Apply,
    [switch]$RestartWorkloads,
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
function SaveStatusFile([string]$file,[string]$json) {
    $temporary=$file+'.'+[Guid]::NewGuid().ToString()+'.tmp'
    try {
        [IO.File]::WriteAllText($temporary,$json,[Text.UTF8Encoding]::new($false))
        for ($attempt=0; $attempt -lt 100; $attempt++) {
            try {
                if ([IO.File]::Exists($file)) { [IO.File]::Replace($temporary,$file,[NullString]::Value) }
                else { [IO.File]::Move($temporary,$file) }
                return
            } catch { if ($attempt -eq 99) { throw }; Start-Sleep -Milliseconds 20 }
        }
    } finally { if ([IO.File]::Exists($temporary)) { Remove-Item -LiteralPath $temporary -Force } }
}
function ReadStatusFile([string]$file) {
    for ($attempt=0; $attempt -lt 100; $attempt++) {
        $stream=$null;$reader=$null
        try {
            $stream=[IO.FileStream]::new($file,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
            return ($reader.ReadToEnd() | ConvertFrom-Json -ErrorAction Stop)
        } catch { if ($attempt -eq 99) { throw }; Start-Sleep -Milliseconds 20 }
        finally { if ($null -ne $reader) { $reader.Dispose() } elseif ($null -ne $stream) { $stream.Dispose() } }
    }
}
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
    $request=@{restartWorkloads=[bool]$RestartWorkloads;id=$id;appPath=$AppPath;sessionId=$SessionId;phase='queued';requestedAt=[DateTime]::UtcNow.ToString('o');coordinatorPath=(Join-Path $bundle 'request_desktop_update.ps1');statusPath=[IO.Path]::GetFullPath($StatusPath);attemptStatusPath=(Join-Path $bundle 'status.json')}
    $guard=RequestLock
    try { SaveRequest $request } finally { $guard.Dispose() }
    if (-not $QueueOnly) {
        # The host launches the dispatcher outside the requesting worker's CPU job.
        $endpoint=$null;$health=$null
        try {
            $endpoint=Get-Content -LiteralPath (Join-Path $DataPath 'host.json') -Raw -Encoding UTF8 | ConvertFrom-Json
            $headers=@{Authorization=('Bearer '+$endpoint.token)}
            $base='http://127.0.0.1:'+$endpoint.port
            $health=Invoke-RestMethod -Uri ($base+'/health') -Headers $headers -TimeoutSec 5
        } catch { }
        if ($health.resourceControlProtocol -ge 1) {
            $launched=Invoke-RestMethod -Method Post -Uri ($base+'/updates/apply') -Headers $headers -ContentType 'application/json' -Body '{}' -TimeoutSec 10
            $request.workerPid=$launched.pid
        } else {
            $process=LaunchHidden $request.coordinatorPath @('-Apply','-DataPath',$DataPath) (Join-Path $bundle 'coordinator'); $request.workerPid=$process.Id
        }
    }
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
            if (Test-Path -LiteralPath $attemptStatus) { $saved=ReadStatusFile $attemptStatus }
            if ($saved.phase -eq 'complete' -and $saved.appPath -eq $request.appPath -and $active.appPath -eq $request.appPath) {
                $outcome='complete';$message='Previously completed installation recovered'
            } else {
                $reload=Join-Path (Split-Path $request.coordinatorPath) 'reload_desktop.ps1'
                $reloadArgs=@('-AppPath',$request.appPath,'-PreviousAppPath',$active.appPath,'-SessionId',$request.sessionId,'-DataPath',$DataPath,'-StatusPath',$attemptStatus,'-WaitForLock')
                if ($request.restartWorkloads) { $reloadArgs+=@('-RestartWorkloads','-RestartId',$request.id) }
                $process=LaunchHidden $reload $reloadArgs (Join-Path (Split-Path $request.coordinatorPath) 'reload')
                New-Item -ItemType Directory -Force -Path (Split-Path $request.statusPath) | Out-Null
                do {
                    $ended=$process.WaitForExit(500)
                    if (Test-Path -LiteralPath $attemptStatus) {
                        try { $state=ReadStatusFile $attemptStatus
                            if ($state.workerPid -eq $process.Id -or $ended) { SaveStatusFile $request.statusPath ($state | ConvertTo-Json -Depth 20) }
                        } catch { }
                    }
                } while (-not $ended)
                $state=ReadStatusFile $attemptStatus
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
