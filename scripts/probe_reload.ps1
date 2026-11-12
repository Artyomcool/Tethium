param([string]$AppPath=(Join-Path $PSScriptRoot '../build/desktop-reload-20261102/Tethium/Tethium.exe'),[switch]$TrayOnly)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$AppPath=[IO.Path]::GetFullPath($AppPath)
$probe=Join-Path $root ('.local/reload-probe/'+[Guid]::NewGuid().ToString())
$data=Join-Path $probe 'data'; New-Item -ItemType Directory -Force -Path $data,(Join-Path $probe 'workspace') | Out-Null
$clientId=[Guid]::NewGuid().ToString(); $clientFile=Join-Path $data ('clients/'+$clientId+'.json')
function Api($endpoint,$path,$body=$null) {
    $p=@{Uri=('http://127.0.0.1:'+$endpoint.port+$path);Headers=@{Authorization=('Bearer '+$endpoint.token)};TimeoutSec=20}
    if($null -ne $body){$p.Method='Post';$p.ContentType='application/json; charset=utf-8';$p.Body=[Text.Encoding]::UTF8.GetBytes(($body|ConvertTo-Json -Depth 100 -Compress))}
    Invoke-RestMethod @p
}
function ReadClient($file) {
    $until=[DateTime]::Now.AddSeconds(30)
    do { try {$c=Get-Content -Raw -LiteralPath $file | ConvertFrom-Json; if((Api $c '/state').ready){return $c}}catch{};Start-Sleep -Milliseconds 200 } while([DateTime]::Now -lt $until)
    throw 'Probe client did not become ready'
}
function Reload($app,$name,$mode) {
    $arguments=@('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'reload_desktop.ps1'),'-AppPath',$app,'-PreviousAppPath',$AppPath,'-SessionId',$script:session,'-DataPath',$data,'-StatusPath',(Join-Path $probe ($name+'.json')))
    if($mode){$arguments+=$mode}
    $quoted=($arguments|ForEach-Object{ '"'+$_+'"' }) -join ' '
    $info=New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName='powershell.exe'; $info.Arguments=$quoted; $info.UseShellExecute=$true; $info.WindowStyle='Hidden'
    $process=[Diagnostics.Process]::Start($info)
    if(-not $process.WaitForExit(90000)){throw ('Probe timed out: '+$name)}
    $code=$process.ExitCode; $process.Dispose(); return $code
}
function ActiveClient {
    $active=Get-Content -Raw -LiteralPath (Join-Path $data 'desktop-active.json')|ConvertFrom-Json
    ReadClient (Join-Path $data ('clients/'+$active.instanceId+'.json'))
}
$client=$null;$hostDescriptor=$null
try {
    if($TrayOnly){
        # Exercise the real logon launcher against an empty, separate data directory.
        @{appPath=$AppPath;pid=0;instanceId=$clientId}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $data 'desktop-active.json') -Encoding UTF8
        $launchArgs='-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+(Join-Path $PSScriptRoot 'launch_desktop.ps1')+'" -Tray -DataPath "'+$data+'"'
        $launcher=Start-Process powershell.exe -ArgumentList $launchArgs -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $probe 'launcher.out.log') -RedirectStandardError (Join-Path $probe 'launcher.err.log')
        $null=$launcher.Handle
        if(-not $launcher.WaitForExit(30000) -or $launcher.ExitCode -ne 0){throw ('Tray launcher failed; exit='+$launcher.ExitCode)}
        $client=ActiveClient;$hostDescriptor=Get-Content -Raw -LiteralPath (Join-Path $data 'host.json')|ConvertFrom-Json;$originalHostPid=$hostDescriptor.pid
        Write-Output ('TRAY_PROBE_PIDS client='+$client.pid+' host='+$originalHostPid)
        $state=Api $client '/state';if(-not $state.tray -or $state.visible){throw 'Startup opened a window instead of the tray'}
        # Re-running autostart must keep a single GUI and leave it hidden.
        $second=Start-Process powershell.exe -ArgumentList $launchArgs -WindowStyle Hidden -PassThru
        $null=$second.Handle
        if(-not $second.WaitForExit(15000) -or $second.ExitCode -ne 0){throw 'Second tray launcher failed'}
        if((ActiveClient).pid -ne $client.pid -or (Api $client '/state').visible){throw 'Autostart duplicated or activated the existing GUI'}
        $saved=Api $client '/prepare' @{};if(-not $saved.trayHidden){throw 'Handoff did not save tray mode'}
        $null=Api $client '/resume' @{};if(-not (Api $client '/state').tray){throw 'Resume lost tray mode'}
        $opened=Api $client '/activate' @{};if(-not $opened.visible){throw 'Tray window could not be opened'}
        $hidden=Api $client '/tray' @{};if(-not $hidden.tray -or $hidden.visible){throw 'Window could not be hidden in the tray'}
        $oldWindowPid=$client.pid;$script:session=''
        if((Reload $AppPath 'tray-replacement' '-DesktopOnly') -ne 0){throw 'Tray desktop replacement failed'}
        $client=ActiveClient;$state=Api $client '/state'
        if(-not $state.tray -or $state.visible -or $state.hostPid -ne $originalHostPid){throw 'Replacement lost tray mode or restarted the host'}
        if(Get-Process -Id $oldWindowPid -ErrorAction SilentlyContinue){throw 'Previous tray window was retained'}
        if(-not (Api $client '/activate' @{}).visible){throw 'Replacement tray did not restore its window'}
        @{passed=$true;startupInTray=$true;singleGui=$true;openAndHide=$true;handoffPreservesTray=$true;hostPreserved=$true;modelCalls=0}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $probe 'report.json') -Encoding UTF8
        Write-Output ('TRAY_RELOAD_PROBE_PASSED '+$probe);return
    }
    $arguments=@('--enable-native-access=ALL-UNNAMED',('-Dtethium.data='+$data),'-cp',(Join-Path (Split-Path $AppPath) 'app/*'),'dev.tethium.desktop.DesktopMain','--hidden',('--instance='+$clientId),('--app-path='+$AppPath))
    $quoted=($arguments|ForEach-Object{ '"'+$_+'"' }) -join ' '
    $null=Start-Process (Join-Path (Split-Path $AppPath) 'runtime/bin/javaw.exe') -ArgumentList $quoted -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $probe 'initial.out.log') -RedirectStandardError (Join-Path $probe 'initial.err.log')
    $client=ReadClient $clientFile
    $null=Api $client '/activate' @{}
    $hostDescriptor=Get-Content -Raw -LiteralPath (Join-Path $data 'host.json')|ConvertFrom-Json; $originalHostPid=$hostDescriptor.pid
    $project=Api $hostDescriptor '/projects' @{path=(Join-Path $probe 'workspace')}
    $script:session=(Api $hostDescriptor '/sessions' @{projectId=$project.id;title='Reload probe'}).id
    $null=Api $hostDescriptor ('/sessions/'+$session+'/stop') @{}
    $null=Api $hostDescriptor ('/sessions/'+$session+'/run') @{prompt='Never execute: a paused queue fixture.'}
    $drafts=@{}; $drafts[$session]="Unsent draft`n`nSecond paragraph"
    $null=Api $client '/adopt' @{sessionId=$session;drafts=$drafts}; $client=ReadClient $clientFile
    $bad=Join-Path $root 'build/desktop-queue-20261102/Tethium/Tethium.exe'
    if((Reload $bad 'rejected-candidate' '-DesktopOnly') -eq 0){throw 'An incompatible candidate was accepted'}
    if(-not (Api $client '/state').ready -or -not (Get-Process -Id $originalHostPid -ErrorAction SilentlyContinue)){throw 'Rejected update damaged the original instance'}
    $oldWindowPid=$client.pid; $requestedSession=$script:session
    $script:session='selection-changed-before-reload'
    $desktopCode=Reload $AppPath 'desktop-only' '-DesktopOnly'
    $script:session=$requestedSession
    if($desktopCode -ne 0){throw 'Desktop replacement failed'}
    if(Get-Process -Id $oldWindowPid -ErrorAction SilentlyContinue){throw 'The old window was retained after a session selection change'}
    $client=ActiveClient
    if((Api $client '/state').hostPid -ne $originalHostPid){throw 'Desktop reload restarted the host'}
    if((Api $client '/state').sessionId -ne $session){throw 'Reload lost the actual current session selection'}
    $saved=Api $client '/prepare' @{}; $null=Api $client '/resume' @{}
    if($saved.drafts.$session -ne $drafts[$session]){throw 'Draft was lost during window replacement'}
    if((Reload $AppPath 'core-replacement' '-ForceCore') -ne 0){throw 'Core replacement failed'}
    $client=ActiveClient; $hostDescriptor=Get-Content -Raw -LiteralPath (Join-Path $data 'host.json')|ConvertFrom-Json
    if($hostDescriptor.pid -eq $originalHostPid){throw 'Core replacement did not change the host'}
    $state=Api $hostDescriptor ('/state?session='+$session)
    if(-not $state.sessions[0].queuePaused -or $state.conversation.queued.Count -ne 1 -or $state.sessions[0].running){throw 'Core replacement changed or executed the paused queue'}
    if($state.sessions[0].preferences.draft -ne $drafts[$session]){throw 'Core replacement lost the draft'}
    @{passed=$true;rejectedCandidateRetainsOldInstance=$true;desktopRetainsHost=$true;draftPreserved=$true;coreReplaced=$true;pausedQueuePreserved=$true;modelCalls=0} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $probe 'report.json') -Encoding UTF8
    Write-Output ('RELOAD_PROBE_PASSED '+$probe)
} finally {
    if(Test-Path -LiteralPath (Join-Path $data 'desktop-active.json')){try{$active=Get-Content -Raw -LiteralPath (Join-Path $data 'desktop-active.json')|ConvertFrom-Json;$client=Get-Content -Raw -LiteralPath (Join-Path $data ('clients/'+$active.instanceId+'.json'))|ConvertFrom-Json}catch{}}
    if($null -ne $client){try{$null=Api $client '/close' @{}}catch{}}
    if(Test-Path -LiteralPath (Join-Path $data 'host.json')){try{$hostDescriptor=Get-Content -Raw -LiteralPath (Join-Path $data 'host.json')|ConvertFrom-Json;$null=Api $hostDescriptor '/shutdown' @{}}catch{}}
}
