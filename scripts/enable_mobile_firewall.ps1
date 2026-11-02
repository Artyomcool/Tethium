# Run elevated once. Only the two mobile ports, private networks and the local subnet.
$ErrorActionPreference = 'Stop'
$name = 'Tethium-Mobile-LAN'
$rule = Get-NetFirewallRule -Name $name -ErrorAction SilentlyContinue
if ($rule) {
    Set-NetFirewallRule -Name $name -Enabled True -Profile Private -Direction Inbound -Action Allow | Out-Null
    $rule | Get-NetFirewallPortFilter | Set-NetFirewallPortFilter -Protocol TCP -LocalPort 8765,8766 | Out-Null
    $rule | Get-NetFirewallAddressFilter | Set-NetFirewallAddressFilter -RemoteAddress LocalSubnet | Out-Null
} else {
    New-NetFirewallRule -Name $name -DisplayName 'Tethium Android (local Wi-Fi)' -Description 'Paired HTTPS API 8765 and APK download 8766; private local subnet only.' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765,8766 -RemoteAddress LocalSubnet -Profile Private | Out-Null
}
$folder = Join-Path $env:LOCALAPPDATA 'Tethium/mobile'
New-Item -ItemType Directory -Force -Path $folder | Out-Null
[IO.File]::WriteAllText((Join-Path $folder 'firewall-ready.txt'), (Get-Date -Format o))
