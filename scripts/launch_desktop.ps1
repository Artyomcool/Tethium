param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
$data = Join-Path $env:LOCALAPPDATA 'Tethium'
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
        $response = Invoke-RestMethod -Uri ('http://127.0.0.1:' + $client.port + '/activate') -Method Post -ContentType 'application/json' -Body '{}' -Headers @{Authorization=('Bearer ' + $client.token)} -TimeoutSec 5
        exit 0
    } catch { }
}
Start-Process -FilePath $app -WindowStyle Hidden -WorkingDirectory (Split-Path $app)
