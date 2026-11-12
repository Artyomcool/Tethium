param([string]$Destination)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $Destination) { $Destination = Join-Path $root 'build/uac-helper' }
$Destination = [IO.Path]::GetFullPath($Destination)
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$references = @('System.ServiceProcess.dll','System.Web.Extensions.dll',"$framework/WPF/UIAutomationClient.dll","$framework/WPF/UIAutomationTypes.dll","$framework/WPF/WindowsBase.dll")
$arguments = @('/nologo','/target:winexe','/platform:x64','/optimize+','/utf8output',('/out:' + (Join-Path $Destination 'TethiumUac.exe')))
foreach ($reference in $references) { $arguments += '/reference:' + $reference }
& (Join-Path $framework 'csc.exe') @arguments (Join-Path $root 'native/uac/TethiumUac.cs')
if ($LASTEXITCODE -ne 0) { throw 'UAC helper build failed' }
Write-Output (Join-Path $Destination 'TethiumUac.exe')
