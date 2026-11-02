param(
    [Parameter(Mandatory=$true)][string]$AppPath,
    [Parameter(Mandatory=$true)][string]$PreviousAppPath,
    [string]$SessionId='',
    [string]$DataPath=(Join-Path $env:LOCALAPPDATA 'Tethium'),
    [string]$StatusPath='',
    [switch]$DesktopOnly,
    [switch]$ForceCore,
    [switch]$WaitForLock
)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($StatusPath)) { $StatusPath=Join-Path $taskRoot '.local/desktop-update/status.json' }
$AppPath=[IO.Path]::GetFullPath($AppPath); $PreviousAppPath=[IO.Path]::GetFullPath($PreviousAppPath)
$DataPath=[IO.Path]::GetFullPath($DataPath); $StatusPath=[IO.Path]::GetFullPath($StatusPath)
$updateId=[Guid]::NewGuid().ToString()
$updateRoot=Join-Path $DataPath ('updates/' + $updateId)
New-Item -ItemType Directory -Force -Path $updateRoot,(Split-Path $StatusPath) | Out-Null
$newClient=$null; $oldClient=$null; $oldHost=$null; $probeHost=$null; $lock=$null
$draining=$false; $oldHostClosed=$false; $activated=$false; $legacy=$false; $handoff=$false
$previousCorePath=$PreviousAppPath
$candidateProcess=$null
$ledger=Join-Path $updateRoot 'legacy-queues.json'
function Status($phase,$message) {
    $value=@{phase=$phase;message=$message;time=[DateTime]::Now.ToString('o');updateId=$updateId;appPath=$AppPath;workerPid=$PID} | ConvertTo-Json
    $value | Set-Content -LiteralPath (Join-Path $updateRoot 'status.json') -Encoding UTF8
    # A rejected concurrent attempt must not overwrite the active update's status.
    if ($null -ne $lock) { $value | Set-Content -LiteralPath $StatusPath -Encoding UTF8 }
}
function Api($endpoint,$path,$body=$null) {
    $parameters=@{Uri=('http://127.0.0.1:' + $endpoint.port + $path);Headers=@{Authorization=('Bearer '+$endpoint.token)};TimeoutSec=20}
    if ($null -ne $body) { $parameters.Method='Post'; $parameters.ContentType='application/json; charset=utf-8'; $parameters.Body=[Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 100 -Compress)) }
    Invoke-RestMethod @parameters
}
function Launch($executable,$arguments,$label) {
    $quoted=($arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }) -join ' '
    Start-Process -FilePath $executable -ArgumentList $quoted -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $updateRoot ($label+'.out.log')) -RedirectStandardError (Join-Path $updateRoot ($label+'.err.log'))
}
function Bridge($mode,$database,$destination) {
    $output=& (Join-Path (Split-Path $AppPath) 'runtime/bin/java.exe') --enable-native-access=ALL-UNNAMED -cp (Join-Path (Split-Path $AppPath) 'app/*') dev.tethium.update.LegacyUpdateBridge $mode $database $destination
    if ($LASTEXITCODE -ne 0) { throw ('Update bridge failed: '+$mode) }
    $output | ConvertFrom-Json
}
function Desktop($app,$arguments,$label) {
    $base=@('--enable-native-access=ALL-UNNAMED',('-Dtethium.data='+$DataPath),'-cp',(Join-Path (Split-Path $app) 'app/*'),'dev.tethium.desktop.DesktopMain',('--app-path='+$app))
    Launch (Join-Path (Split-Path $app) 'runtime/bin/javaw.exe') ($base+$arguments) $label
}
function Host($app,$data,$label,$probe=$false) {
    New-Item -ItemType Directory -Force -Path $data | Out-Null
    $oldPid=0; $file=Join-Path $data 'host.json'
    if (Test-Path -LiteralPath $file) { $oldPid=(Get-Content -Raw -LiteralPath $file | ConvertFrom-Json).pid }
    $arguments=@('--enable-native-access=ALL-UNNAMED',('-Dtethium.data='+$data),'-Dtethium.standby=true',('-Dtethium.probe='+$probe.ToString().ToLower()),'-cp',(Join-Path (Split-Path $app) 'app/*'),'dev.tethium.host.HostMain')
    $process=Launch (Join-Path (Split-Path $app) 'runtime/bin/javaw.exe') $arguments $label
    $deadline=[DateTime]::Now.AddSeconds(30)
    while ([DateTime]::Now -lt $deadline) {
        if ($process.HasExited) { throw ('Host exited: '+$label) }
        if (Test-Path -LiteralPath $file) {
            try { $value=Get-Content -Raw -LiteralPath $file | ConvertFrom-Json; if ($value.pid -ne $oldPid) { $null=Api $value '/health'; return $value } } catch {}
        }
        Start-Sleep -Milliseconds 200
    }
    if (-not $process.HasExited) { $process.Kill(); $process.WaitForExit() }
    throw ('Host readiness timeout: '+$label)
}
function ClientReady($descriptorFile,$visible=$false) {
    $deadline=[DateTime]::Now.AddSeconds(30)
    while ([DateTime]::Now -lt $deadline) {
        $errorFile=[IO.Path]::ChangeExtension($descriptorFile,'error.json')
        if (Test-Path -LiteralPath $errorFile) { $failure=Get-Content -Raw -LiteralPath $errorFile | ConvertFrom-Json; throw ('Candidate startup failed: '+$failure.error) }
        if (Test-Path -LiteralPath $descriptorFile) {
            try { $value=Get-Content -Raw -LiteralPath $descriptorFile | ConvertFrom-Json; $state=Api $value '/state'; if ($state.ready -and (-not $visible -or $state.visible)) { return $value } } catch {}
        }
        Start-Sleep -Milliseconds 200
    }
    throw 'Desktop readiness timeout; previous window is retained or reopened.'
}
function WaitExit($processId) {
    $process=Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -ne $process -and -not $process.WaitForExit(20000)) { throw ('Process did not exit gracefully: '+$processId) }
}
function WaitForRuns($timeoutSeconds=5400) {
    $deadline=[DateTime]::Now.AddSeconds($timeoutSeconds)
    while ($true) {
        if ($legacy) { $null=Bridge 'pause' (Join-Path $DataPath 'tethium.db') $ledger }
        elseif (-not (Api $oldHost '/health').draining) {
            # Queue recovery cancels this core handoff, even while agents are still running.
            return $false
        }
        $state=Api $oldHost '/state'
        if (@($state.sessions | Where-Object { $_.running }).Count -eq 0) { return $true }
        if ([DateTime]::Now -gt $deadline) { throw 'Core drain timed out; restoring queue execution' }
        Start-Sleep -Seconds 2
    }
}
try {
    while ($null -eq $lock) {
        try { $lock=[IO.File]::Open((Join-Path $DataPath 'update.lock'),'OpenOrCreate','ReadWrite','None') }
        catch { if (-not $WaitForLock) { throw 'Another reload is already running' }; Start-Sleep -Seconds 2 }
    }
    if (-not (Test-Path -LiteralPath $AppPath) -or -not (Test-Path -LiteralPath $PreviousAppPath)) { throw 'Both application versions must exist' }
    $oldHost=Get-Content -Raw -LiteralPath (Join-Path $DataPath 'host.json') | ConvertFrom-Json
    $oldHealth=Api $oldHost '/health'
    # The visible window may already be newer than the running core. Roll back to the core's own package.
    $coreProcess=Get-Process -Id $oldHost.pid -ErrorAction Stop
    if ($coreProcess.Path) {
        $corePackage=Split-Path (Split-Path (Split-Path $coreProcess.Path))
        $coreApp=Join-Path $corePackage 'Tethium.exe'
        if (Test-Path -LiteralPath $coreApp) { $previousCorePath=$coreApp }
    }
    Status 'preflight' 'Checking the candidate core against a consistent copy of the database.'
    $probeData=Join-Path $updateRoot 'probe'
    New-Item -ItemType Directory -Force -Path $probeData | Out-Null
    $null=Bridge 'backup' (Join-Path $DataPath 'tethium.db') (Join-Path $probeData 'tethium.db')
    $probeHost=Host $AppPath $probeData 'probe-host' $true
    $candidateHealth=Api $probeHost '/health'
    if ($candidateHealth.protocol -ne 1 -or -not $candidateHealth.draining -or $candidateHealth.running -ne 0) { throw 'Candidate core is incompatible or started work during preflight' }
    $null=Api $probeHost '/state'
    $null=Api $probeHost '/updates/shutdown' @{}; WaitExit $probeHost.pid; $probeHost=$null
    $clientId=[Guid]::NewGuid().ToString(); $clientFile=Join-Path $DataPath ('clients/'+$clientId+'.json')
    Status 'starting-window' 'Starting the candidate window while the existing window and runs continue.'
    $arguments=@('--hidden',('--instance='+$clientId),('--app-path='+$AppPath),('--data='+$DataPath)); if ($SessionId) { $arguments+=('--session='+$SessionId) }
    $candidateProcess=Desktop $AppPath $arguments 'candidate-window'
    $newClient=ClientReady $clientFile
    $newState=Api $newClient '/state'
    if ($newState.hostPid -ne $oldHost.pid -or $newClient.build -ne $candidateHealth.build) { throw 'Candidate window connected to the wrong host or build' }
    $activeInstance=''; $bestScore=-1
    try { $activeInstance=(Get-Content -Raw -LiteralPath (Join-Path $DataPath 'desktop-active.json') | ConvertFrom-Json).instanceId } catch {}
    foreach ($file in @(Get-ChildItem -LiteralPath (Join-Path $DataPath 'clients') -Filter '*.json')) {
        if ($file.FullName -eq $clientFile -or $file.Name.EndsWith('.error.json')) { continue }
        try {
            $entry=Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json; $state=Api $entry '/state'
            if ($state.visible -and $state.hostPid -eq $oldHost.pid) {
                $score=0
                if ($entry.instanceId -eq $activeInstance) { $score+=4 }
                if ($SessionId -and $state.sessionId -eq $SessionId) { $score+=2 }
                if ($entry.appPath -eq $PreviousAppPath) { $score++ }
                if ($score -gt $bestScore) { $oldClient=$entry; $bestScore=$score }
            }
        } catch {}
    }
    if ($null -ne $oldClient) {
        $snapshot=Api $oldClient '/prepare' @{}
        $null=Api $newClient '/adopt' $snapshot; $newClient=ClientReady $clientFile
        $null=Api $newClient '/activate' @{}; $newClient=ClientReady $clientFile $true
        Start-Sleep -Milliseconds 500; $null=Api $newClient '/state'; $activated=$true
        try { $null=Api $oldClient '/close' @{} } catch {}
        WaitExit $oldClient.pid
    } else {
        # First migration from old builds: they flush drafts on a normal window close.
        $windows=@(Get-Process -Name Tethium -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq $PreviousAppPath -and $_.Id -ne $newClient.pid })
        foreach ($window in $windows) { if ($window.MainWindowHandle -ne 0) { [void]$window.CloseMainWindow() } }
        foreach ($window in $windows) { WaitExit $window.Id }
        $state=Api $oldHost ('/state?session='+$newState.sessionId)
        $drafts=@{}; foreach ($session in $state.sessions) { $drafts[$session.id]=$session.preferences.draft }
        $null=Api $newClient '/adopt' @{sessionId=$newState.sessionId;drafts=$drafts}
        $newClient=ClientReady $clientFile; $null=Api $newClient '/activate' @{}; $newClient=ClientReady $clientFile $true; $activated=$true
    }
    @{appPath=$AppPath;instanceId=$clientId;pid=$newClient.pid} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $DataPath 'desktop-active.json') -Encoding UTF8
    if ($DesktopOnly -or (-not $ForceCore -and $oldHealth.hostBuild -eq $candidateHealth.hostBuild)) { Status 'complete' 'Desktop replaced; the host PID and active runs were retained.'; exit 0 }
    $null=Api $newClient '/updating' @{value=$true}
    $legacy=$null -eq $oldHealth.protocol
    $draining=$true
    if ($legacy) { $null=Bridge 'pause' (Join-Path $DataPath 'tethium.db') $ledger }
    else { $null=Api $oldHost '/updates/drain' @{} }
    $handoff=$oldHealth.workerProtocol -eq 1 -and $candidateHealth.workerProtocol -eq 1 -and (Api $oldHost '/health').handoffReady
    if ($handoff) {
        Status 'handoff' 'Replacing the core while independent workers retain active turns, tool calls, and user questions.'
    } else {
        Status 'waiting-for-runs' 'One-time migration or incompatible worker protocol: existing runs finish; queued messages are retained.'
    }
    if (-not $handoff -and -not (WaitForRuns)) {
        $draining=$false
        $null=Api $newClient '/updating' @{value=$false}
        Status 'deferred' 'Core update deferred after queue execution was resumed; the new desktop and current runs are retained.'
        exit 0
    }
    Status 'switching-core' 'Starting the verified core in standby mode.'
    $null=Bridge 'backup' (Join-Path $DataPath 'tethium.db') (Join-Path $updateRoot 'before-core.db')
    $shutdown=if ($handoff) { '/updates/handoff' } elseif ($legacy) { '/shutdown' } else { '/updates/shutdown' }
    $null=Api $oldHost $shutdown @{}; $oldHostClosed=$true; WaitExit $oldHost.pid
    $replacement=Host $AppPath $DataPath 'replacement-host'
    $health=Api $replacement '/health'
    if (-not $health.draining -or $health.hostBuild -ne $candidateHealth.hostBuild) { throw 'Replacement core readiness check failed' }
    $state=Api $replacement '/state'
    $deadline=[DateTime]::Now.AddSeconds(30)
    do { $view=Api $newClient '/state'; if ($view.ready -and $view.hostPid -eq $replacement.pid) { break }; Start-Sleep -Milliseconds 300 } while ([DateTime]::Now -lt $deadline)
    if ($view.hostPid -ne $replacement.pid -or -not $view.ready) { throw 'Window did not reconnect to the replacement core' }
    if ($legacy) { $null=Bridge 'restore' (Join-Path $DataPath 'tethium.db') $ledger }
    $null=Api $replacement '/updates/resume' @{}; $draining=$false
    $null=Api $newClient '/updating' @{value=$false}
    Status 'complete' 'Desktop and core updated; active workers reattached, saved queues resumed, user-paused queues retained.'
} catch {
    $failure=$_.Exception.Message
    if ($null -ne $probeHost) { try { $null=Api $probeHost '/updates/shutdown' @{}; WaitExit $probeHost.pid } catch {} }
    try {
        if ($oldHostClosed) {
            try {
                $current=Get-Content -Raw -LiteralPath (Join-Path $DataPath 'host.json') | ConvertFrom-Json; $health=Api $current '/health'
                if ($health.workerProtocol -eq 1 -and $health.handoffReady) { $null=Api $current '/updates/handoff' @{}; WaitExit $current.pid }
                elseif ($health.draining -and $health.running -eq 0) { $null=Api $current '/updates/shutdown' @{}; WaitExit $current.pid }
            } catch {}
            $oldHost=Host $previousCorePath $DataPath 'rollback-host'
        }
        if ($draining) {
            if ($legacy) {
                $restored=Bridge 'restore' (Join-Path $DataPath 'tethium.db') $ledger
                foreach ($id in $restored.resumed) { $null=Api $oldHost ('/sessions/'+$id+'/queue-resume') @{} }
            } else { $null=Api $oldHost '/updates/resume' @{} }
        }
        if ($null -ne $newClient -and $activated) { $null=Api $newClient '/updating' @{value=$false} }
        elseif ($null -ne $newClient) { try { $null=Api $newClient '/close' @{} } catch {} }
        elseif ($null -ne $candidateProcess -and -not $candidateProcess.HasExited) { $candidateProcess.Kill(); $candidateProcess.WaitForExit() }
        if ($null -ne $oldClient -and -not $activated) { $null=Api $oldClient '/resume' @{} }
        elseif (-not $activated -and $null -ne $newClient) { $null=Desktop $PreviousAppPath @(('--session='+$SessionId)) 'rollback-window' }
    } catch { $failure+='; rollback: '+$_.Exception.Message }
    Status 'failed' $failure
    throw $failure
} finally { if ($null -ne $lock) { $lock.Dispose() } }
