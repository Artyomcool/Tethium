$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reload=Join-Path $PSScriptRoot 'reload_desktop.ps1'
$probe=Join-Path $root ('.local/reload-control-probe/'+[Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $probe | Out-Null

# Exercise the production wait loop with deterministic host replies; no agent/model is started.
$parseErrors=$null; $tokens=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($reload,[ref]$tokens,[ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
$waitFunction=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'WaitForRuns'},$false)
. ([scriptblock]::Create($waitFunction.Extent.Text))
$legacy=$false; $oldHost=@{port=0}; $script:polls=0; $script:sleeps=0; $script:mode='resume'
function Api($endpoint,$path) {
    if ($path -eq '/health') { return @{draining=($script:mode -ne 'resume' -or $script:sleeps -eq 0)} }
    if ($path -eq '/state') {
        $script:polls++
        return @{sessions=@(@{running=($script:mode -ne 'queued');queued=3;queuePaused=$true})}
    }
    throw ('Unexpected mutation during wait: '+$path)
}
function Start-Sleep { param($Seconds) $script:sleeps++ }
if (WaitForRuns) { throw 'A resumed queue was still eligible for core replacement' }
if ($script:polls -ne 1 -or $script:sleeps -ne 1) { throw 'Queue recovery did not end the wait promptly' }
$script:mode='queued'
if (-not (WaitForRuns)) { throw 'Pending/paused jobs prevented drain completion' }
$script:mode='timeout'; $timedOut=$false
try { $null=WaitForRuns -1 } catch { $timedOut=$_.Exception.Message -like 'Core drain timed out*' }
if (-not $timedOut) { throw 'A stuck drain did not time out' }

# Run the complete updater as a second process while another update owns the lock.
# Its own failure must be recorded without hiding the existing updater's status.
$data=Join-Path $probe 'data'; New-Item -ItemType Directory -Path $data | Out-Null
$shared=Join-Path $probe 'active-status.json'
$original='{"phase":"waiting-for-runs","updateId":"existing-update"}'
[IO.File]::WriteAllText($shared,$original)
$held=[IO.File]::Open((Join-Path $data 'update.lock'),'OpenOrCreate','ReadWrite','None')
try {
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',$reload,'-AppPath',$reload,'-PreviousAppPath',$reload,'-DataPath',$data,'-StatusPath',$shared)
    $quoted=($arguments | ForEach-Object { '"'+$_+'"' }) -join ' '
    $process=Start-Process powershell.exe -WindowStyle Hidden -ArgumentList $quoted -PassThru -RedirectStandardOutput (Join-Path $probe 'contender.out.log') -RedirectStandardError (Join-Path $probe 'contender.err.log')
    try {
        if (-not $process.WaitForExit(20000)) { $process.Kill(); throw 'Contending updater did not exit' }
        if ($process.ExitCode -eq 0) { throw 'Contending updater succeeded without owning the lock' }
        if ([IO.File]::ReadAllText($shared) -cne $original) { throw 'Contending updater overwrote the active status' }
        $attempts=@(Get-ChildItem -LiteralPath (Join-Path $data 'updates') -Filter status.json -Recurse)
        if ($attempts.Count -ne 1) { throw 'Missing per-attempt failure status' }
        $status=Get-Content -Raw -LiteralPath $attempts[0].FullName | ConvertFrom-Json
        if ($status.phase -ne 'failed' -or $status.workerPid -ne $process.Id -or $status.message -ne 'Another reload is already running') { throw 'Incorrect contention diagnosis' }
    } finally { $process.Dispose() }
} finally { $held.Dispose() }
@{passed=$true;resumedQueueDefersCore=$true;queuedJobsDoNotBlockDrain=$true;drainTimeout=$true;concurrentStatusPreserved=$true;modelCalls=0} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $probe 'report.json') -Encoding UTF8
Write-Output ('RELOAD_CONTROL_PROBE_PASSED '+$probe)
