# Short check of PruneAfterInstall on a throwaway tree: what a finished installation removes and what it must keep.
$ErrorActionPreference='Stop'
$script=Join-Path $PSScriptRoot 'request_desktop_update.ps1'
$root=Join-Path ([IO.Path]::GetTempPath()) ('tethium-prune-'+[Guid]::NewGuid())
$data=Join-Path $root 'data'; $build=Join-Path $root 'build'
function Touch($path) { New-Item -ItemType Directory -Force -Path (Split-Path $path) | Out-Null; Set-Content -LiteralPath $path -Value 'x' }
function Check($what,$ok) { if (-not $ok) { throw "FAILED: $what" }; "ok: $what" }
try {
    $now=[DateTime]::UtcNow
    foreach ($i in 1..5) {
        $u=Join-Path $data "updates/u$i"; Touch (Join-Path $u 'before-core.db'); Touch (Join-Path $u 'probe/tethium.db'); Touch (Join-Path $u 'status.json')
        (Get-Item -LiteralPath $u).CreationTimeUtc=$now.AddHours(-$i)
    }
    (Get-Item -LiteralPath (Join-Path $data 'updates/u5')).CreationTimeUtc=$now.AddDays(-30)
    Touch (Join-Path $data 'updates/requests/r1/status.json')
    $dead=Join-Path $data 'browser-profiles/999999'; $live=Join-Path $data "browser-profiles/$PID"; $ancient=Join-Path $data 'browser-profiles/999998'
    foreach ($p in @($dead,$live,$ancient)) { Touch (Join-Path $p 'cache') }
    (Get-Item -LiteralPath $ancient).CreationTimeUtc=$now.AddDays(-30)
    foreach ($i in 1..8) { $d=Join-Path $build "desktop-b$i"; Touch (Join-Path $d 'Tethium/Tethium.exe'); (Get-Item -LiteralPath $d).LastWriteTimeUtc=$now.AddHours(-3-$i) }
    Touch (Join-Path $build 'classes/a.class')
    $previous=Join-Path $build 'desktop-b8/Tethium/Tethium.exe'
    @{appPath=$previous} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $data 'desktop-active.json') -Encoding UTF8
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $script -PruneOnly -DataPath $data -AppPath (Join-Path $build 'desktop-b1/Tethium/Tethium.exe') -PruneSince $now.AddDays(-1).ToString('o')
    Check 'newest update keeps its copies' (Test-Path (Join-Path $data 'updates/u3/before-core.db'))
    Check 'older update loses its copies' ((-not (Test-Path (Join-Path $data 'updates/u4/before-core.db'))) -and (-not (Test-Path (Join-Path $data 'updates/u4/probe'))))
    Check 'older update keeps its logs' (Test-Path (Join-Path $data 'updates/u4/status.json'))
    Check 'copies before the cut-off stay' (Test-Path (Join-Path $data 'updates/u5/before-core.db'))
    Check 'requests stay' (Test-Path (Join-Path $data 'updates/requests/r1/status.json'))
    Check 'dead profile goes' (-not (Test-Path $dead))
    Check 'live profile stays' (Test-Path $live)
    Check 'profile before the cut-off stays' (Test-Path $ancient)
    Check 'newest five builds stay' (Test-Path (Join-Path $build 'desktop-b5'))
    Check 'older build goes' ((-not (Test-Path (Join-Path $build 'desktop-b6'))) -and (-not (Test-Path (Join-Path $build 'desktop-b7'))))
    Check 'active build stays' (Test-Path $previous)
    Check 'other build output stays' (Test-Path (Join-Path $build 'classes/a.class'))
    'PRUNE_PROBE_PASSED'
} finally { Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue }
