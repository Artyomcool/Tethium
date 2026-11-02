param([Parameter(Mandatory=$true)][string]$AppPath,[Parameter(Mandatory=$true)][string]$TestClasses)
$ErrorActionPreference='Stop'
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$AppPath=[IO.Path]::GetFullPath($AppPath);$TestClasses=[IO.Path]::GetFullPath($TestClasses)
$probe=Join-Path $taskRoot ('.local/worker-reload-probe/'+[Guid]::NewGuid().ToString())
$data=Join-Path $probe 'data';$fixture=Join-Path $probe 'fixture'
New-Item -ItemType Directory -Force -Path $data,$fixture,(Join-Path $probe 'empty-codex') | Out-Null
$package=Split-Path $AppPath;$java=Join-Path $package 'runtime/bin/java.exe';$javaw=Join-Path $package 'runtime/bin/javaw.exe'
$classpath=(Join-Path $package 'app/*')+';'+$TestClasses
$wrapper=Join-Path $fixture 'fixture-agent.cmd'
[IO.File]::WriteAllText($wrapper,('@"'+$java+'" --enable-native-access=ALL-UNNAMED -cp "'+$classpath+'" dev.tethium.host.DetachedAgentFixture "'+$fixture+'" %*'))
$env:TETHIUM_CODEX=$wrapper;$env:CODEX_HOME=Join-Path $probe 'empty-codex'
function Api($endpoint,$path,$body=$null) {
    $p=@{Uri=('http://127.0.0.1:'+$endpoint.port+$path);Headers=@{Authorization=('Bearer '+$endpoint.token)};TimeoutSec=10}
    if($null -ne $body){$p.Method='Post';$p.ContentType='application/json; charset=utf-8';$p.Body=[Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 100 -Compress))}
    Invoke-RestMethod @p
}
function Until($test) {
    $until=[DateTime]::UtcNow.AddSeconds(25)
    do { try { $value=& $test;if($value){return $value} } catch {} ;Start-Sleep -Milliseconds 100 } while([DateTime]::UtcNow -lt $until)
    throw 'Worker reload probe did not converge'
}
$client=$null;$hostDescriptor=$null;$reloadProcess=$null
try {
    $clientId=[Guid]::NewGuid().ToString();$clientFile=Join-Path $data ('clients/'+$clientId+'.json')
    $arguments=@('--enable-native-access=ALL-UNNAMED',('-Dtethium.data='+$data),'-cp',(Join-Path $package 'app/*'),'dev.tethium.desktop.DesktopMain','--hidden',('--instance='+$clientId),('--app-path='+$AppPath))
    $quoted=($arguments|ForEach-Object{'"'+$_+'"'}) -join ' '
    $null=Start-Process $javaw -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $probe 'window.out.log') -RedirectStandardError (Join-Path $probe 'window.err.log')
    $client=Until { $c=Get-Content -LiteralPath $clientFile -Raw|ConvertFrom-Json;if((Api $c '/state').ready){$c} }
    $null=Api $client '/activate' @{}
    $hostDescriptor=Get-Content -LiteralPath (Join-Path $data 'host.json') -Raw|ConvertFrom-Json;$oldHostPid=$hostDescriptor.pid
    $project=Api $hostDescriptor '/projects' @{path=$fixture}
    $session=(Api $hostDescriptor '/sessions' @{projectId=$project.id;title='Run through a core replacement'}).id
    $target=(Api $hostDescriptor '/sessions' @{projectId=$project.id;title='Paused recipient'}).id
    [IO.File]::WriteAllText((Join-Path $fixture 'target'),$target);[IO.File]::WriteAllText((Join-Path $fixture 'ask'),'yes')
    $null=Api $hostDescriptor ('/sessions/'+$target+'/stop') @{}
    $null=Api $hostDescriptor ('/sessions/'+$target+'/run') @{prompt='Must remain paused'}
    $drafts=@{};$drafts[$session]='Unsent draft survives handoff'
    $null=Api $client '/adopt' @{sessionId=$session;drafts=$drafts}
    $initialDraft=Api $client '/prepare' @{};$null=Api $client '/resume' @{}
    if($initialDraft.drafts.$session -ne $drafts[$session]){throw 'Draft fixture was not established'}
    $null=Api $hostDescriptor ('/sessions/'+$session+'/run') @{prompt='Run local protocol fixture';preserveDesktopDraft=$true}
    $before=Until { $s=Api $hostDescriptor ('/state?session='+$session);if($s.conversation.live -eq 'before '){$s} }
    $startedAt=($before.sessions | Where-Object id -eq $session).progress.startedAt
    $worker=Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'javaw.exe' -and $_.CommandLine.Contains('dev.tethium.worker.WorkerMain') -and $_.CommandLine.Contains($data) }
    if(@($worker).Count -ne 1){throw 'Expected one independent worker'}
    $workerPid=$worker.ProcessId
    [IO.File]::WriteAllText((Join-Path $fixture 'watch-host-pid'),$oldHostPid.ToString());[IO.File]::WriteAllText((Join-Path $fixture 'gap'),'yes')
    $status=Join-Path $probe 'reload.json'
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'reload_desktop.ps1'),'-AppPath',$AppPath,'-PreviousAppPath',$AppPath,'-DataPath',$data,'-StatusPath',$status,'-ForceCore')
    $quoted=($arguments|ForEach-Object{'"'+$_+'"'}) -join ' '
    $timer=[Diagnostics.Stopwatch]::StartNew()
    $reloadProcess=Start-Process powershell.exe -ArgumentList $quoted -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $probe 'reload.out.log') -RedirectStandardError (Join-Path $probe 'reload.err.log')
    [void]$reloadProcess.Handle
    if(-not $reloadProcess.WaitForExit(60000)){throw 'The updater waited for the active turn instead of handing it off'}
    $timer.Stop()
    if($reloadProcess.ExitCode -ne 0){throw 'Packaged worker handoff failed'}
    $hostDescriptor=Get-Content -LiteralPath (Join-Path $data 'host.json') -Raw|ConvertFrom-Json
    if($hostDescriptor.pid -eq $oldHostPid){throw 'The core was not replaced'}
    if(-not (Get-Process -Id $workerPid -ErrorAction SilentlyContinue)){throw 'The active worker was terminated'}
    $active=Get-Content -LiteralPath (Join-Path $data 'desktop-active.json') -Raw|ConvertFrom-Json
    $client=Get-Content -LiteralPath (Join-Path $data ('clients/'+$active.instanceId+'.json')) -Raw|ConvertFrom-Json
    $waiting=Until {$s=Api $hostDescriptor ('/state?session='+$session);if(@($s.conversation.requests).Count -eq 1){$s}}
    if($waiting.conversation.live -ne 'before during '){throw 'Output emitted during replacement was lost or duplicated'}
    if(($waiting.sessions|Where-Object id -eq $session).progress.startedAt -ne $startedAt){throw 'Run elapsed time was reset'}
    $snapshot=Api $client '/prepare' @{};$null=Api $client '/resume' @{}
    if($snapshot.drafts.$session -ne $drafts[$session]){throw 'The desktop draft was lost'}
    $null=Api $hostDescriptor '/answer' @{id=$waiting.conversation.requests[0].id;answer=@{answers=@{q='yes'}}}
    [IO.File]::WriteAllText((Join-Path $fixture 'finish'),'yes')
    $finished=Until {$s=Api $hostDescriptor ('/state?session='+$session);if(-not ($s.sessions|Where-Object id -eq $session).running){$s}}
    if($finished.conversation.messages[-1].text -ne 'before during answered after'){throw 'Final answer is incomplete'}
    $recipient=Api $hostDescriptor ('/state?session='+$target)
    if(@($recipient.conversation.messages).Count -ne 1 -or @($recipient.conversation.queued).Count -ne 1 -or -not ($recipient.sessions|Where-Object id -eq $target).queuePaused){throw 'Tool delivery or paused queue was not preserved'}
    if(@(Get-Content -LiteralPath (Join-Path $fixture 'submissions')).Count -ne 1){throw 'The agent turn was submitted more than once'}
    @{passed=$true;oldHostPid=$oldHostPid;newHostPid=$hostDescriptor.pid;workerPid=$workerPid;seconds=$timer.Elapsed.TotalSeconds;outputDuringHandoff=$true;toolExactlyOnce=$true;questionPreserved=$true;draftPreserved=$true;pausedQueuePreserved=$true;modelCalls=0} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $probe 'report.json') -Encoding UTF8
    Write-Output ('WORKER_RELOAD_PROBE_PASSED '+$probe)
} finally {
    if($null -ne $reloadProcess -and -not $reloadProcess.HasExited){$reloadProcess.Kill();$reloadProcess.WaitForExit()}
    try{$active=Get-Content -LiteralPath (Join-Path $data 'desktop-active.json') -Raw|ConvertFrom-Json;$client=Get-Content -LiteralPath (Join-Path $data ('clients/'+$active.instanceId+'.json')) -Raw|ConvertFrom-Json}catch{}
    if($null -ne $client){try{$null=Api $client '/close' @{}}catch{}}
    foreach($file in @(Get-ChildItem -LiteralPath (Join-Path $data 'clients') -Filter '*.json' -ErrorAction SilentlyContinue)) {
        try {$endpoint=Get-Content -LiteralPath $file.FullName -Raw|ConvertFrom-Json;$null=Api $endpoint '/close' @{}}catch{}
    }
    try{$hostDescriptor=Get-Content -LiteralPath (Join-Path $data 'host.json') -Raw|ConvertFrom-Json;$null=Api $hostDescriptor '/shutdown' @{}}catch{}
}
