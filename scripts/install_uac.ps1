param([Parameter(Mandatory=$true)][string]$HelperPath, [string]$OwnerSid, [switch]$Elevated, [switch]$Disable)
$ErrorActionPreference = 'Stop'
if (-not $OwnerSid) { $OwnerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value }
if ($OwnerSid -notmatch '^S-1-5-21-[0-9-]+$') { throw 'Invalid owner SID' }
$HelperPath = (Resolve-Path -LiteralPath $HelperPath).Path
if (-not $Elevated) {
    $statePath = Join-Path $env:LOCALAPPDATA 'Tethium/uac-install-state.json'
    [IO.File]::WriteAllText($statePath, (@{phase='awaiting-uac'; at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json))
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $PSCommandPath + '" -HelperPath "' + $HelperPath + '" -OwnerSid "' + $OwnerSid + '" -Elevated'
    if ($Disable) { $arguments += ' -Disable' }
    try {
        $process = Start-Process -FilePath (Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe') -ArgumentList $arguments -Verb RunAs -WindowStyle Hidden -PassThru
        $handle = $process.Handle
        $process.WaitForExit()
        [IO.File]::WriteAllText($statePath, (@{phase='exited'; exitCode=$process.ExitCode; at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json))
        if ($process.ExitCode -ne 0) { throw ('UAC helper installation failed: ' + $process.ExitCode) }
    } catch {
        [IO.File]::WriteAllText($statePath, (@{phase='failed'; error=$_.Exception.Message; at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json))
        throw
    }
    return
}
$admin = [Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $admin.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Administrator required' }
$data = Join-Path $env:ProgramData 'Tethium/Uac'
New-Item -ItemType Directory -Path $data -Force | Out-Null
try {
    $service = Get-Service -Name TethiumUac -ErrorAction SilentlyContinue
    if ($service) { Stop-Service -Name TethiumUac -Force; $service.WaitForStatus('Stopped',[TimeSpan]::FromSeconds(15)) }
    if ($Disable) { & sc.exe delete TethiumUac | Out-Null; return }
    # Protected binaries/configuration. The paired desktop owner may read descriptors, never replace service code.
    $acl = [Security.AccessControl.DirectorySecurity]::new()
    $acl.SetAccessRuleProtection($true,$false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) { $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($sid), 'FullControl', 'ContainerInherit,ObjectInherit', 'None','Allow')) }
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new($OwnerSid), 'ReadAndExecute', 'ContainerInherit,ObjectInherit', 'None','Allow'))
    Set-Acl -LiteralPath $data -AclObject $acl
    $binaryRoot = Join-Path $env:ProgramFiles 'Tethium UAC'
    New-Item -ItemType Directory -Path $binaryRoot -Force | Out-Null
    Set-Acl -LiteralPath $binaryRoot -AclObject $acl
    $hash = (Get-FileHash -LiteralPath $HelperPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $binary = Join-Path $binaryRoot ('TethiumUac-' + $hash.Substring(0,16) + '.exe')
    if (-not (Test-Path -LiteralPath $binary)) { Copy-Item -LiteralPath $HelperPath -Destination $binary }
    [IO.File]::WriteAllText((Join-Path $data 'config.json'), (@{ownerSid=$OwnerSid; protocol=1} | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $image = '"' + $binary + '"'
    if ($service) {
        $existing = Get-CimInstance Win32_Service -Filter "Name='TethiumUac'"
        $changed = Invoke-CimMethod -InputObject $existing -MethodName Change -Arguments @{PathName=$image; StartMode='Automatic'}
        if ($changed.ReturnValue -ne 0) { throw 'Cannot update TethiumUac' }
    } else { New-Service -Name TethiumUac -BinaryPathName $image -StartupType Automatic -DisplayName 'Tethium UAC' | Out-Null }
    & sc.exe description TethiumUac 'Shows genuine UAC dialogs on the paired phone; actions require a human tap.' | Out-Null
    Start-Service -Name TethiumUac
    [IO.File]::WriteAllText((Join-Path $data 'install-result.json'), (@{ok=$true; sha256=$hash; at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json))
} catch {
    [IO.File]::WriteAllText((Join-Path $data 'install-result.json'), (@{ok=$false; error=$_.Exception.Message; at=[DateTime]::UtcNow.ToString('o')} | ConvertTo-Json))
    exit 1
}
