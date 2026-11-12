param([switch]$CheckOnly,[switch]$Tray,[string]$DataPath=(Join-Path $env:LOCALAPPDATA 'Tethium'))
$ErrorActionPreference = 'Stop'
$data = [IO.Path]::GetFullPath($DataPath)
$activeFile = Join-Path $data 'desktop-active.json'
$active = Get-Content -LiteralPath $activeFile -Raw -Encoding UTF8 | ConvertFrom-Json
$app = [IO.Path]::GetFullPath($active.appPath)
if (-not (Test-Path -LiteralPath $app -PathType Leaf)) { throw 'The active Tethium application was not found.' }
if ($CheckOnly) { Write-Output $app; exit 0 }
$descriptor = Join-Path $data ('clients/' + $active.instanceId + '.json')
if (Test-Path -LiteralPath $descriptor) {
    try {
        $client = Get-Content -LiteralPath $descriptor -Raw -Encoding UTF8 | ConvertFrom-Json
        $liveProcess = Get-Process -Id $client.pid -ErrorAction Stop
        if($Tray){$response=Invoke-RestMethod -Uri ('http://127.0.0.1:'+$client.port+'/state') -Headers @{Authorization=('Bearer '+$client.token)} -TimeoutSec 5}
        else {$response = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $client.port + '/activate') -Method Post -ContentType 'application/json' -Body '{}' -Headers @{Authorization=('Bearer ' + $client.token)} -TimeoutSec 5}
        exit 0
    } catch { }
}
$launch=@{FilePath=$app;WindowStyle='Hidden';WorkingDirectory=(Split-Path $app);PassThru=$true}
$launch.ArgumentList=@(('"--data='+$data+'"'));if($Tray){$launch.ArgumentList+='--tray'}
$process=Start-Process @launch
# The next shortcut invocation must see the new live descriptor, not the old PID.
$until=[DateTime]::UtcNow.AddSeconds(20)
do {
    try {$next=Get-Content -LiteralPath $activeFile -Raw -Encoding UTF8|ConvertFrom-Json;if($next.pid -ne $active.pid){break}}catch{}
    if($process.HasExited -and $process.ExitCode -ne 0){throw 'Tethium exited before its desktop was registered.'}
    Start-Sleep -Milliseconds 200
}while([DateTime]::UtcNow -lt $until)
