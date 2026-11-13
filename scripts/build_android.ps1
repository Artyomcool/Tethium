param([string]$SdkPath = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (-not $SdkPath) { $SdkPath = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (-not (Test-Path -LiteralPath "$SdkPath/platforms/android-36/android.jar")) { throw 'Android SDK 36 not found. Pass -SdkPath.' }
$data = Join-Path $env:LOCALAPPDATA 'Tethium'
$signing = Join-Path $data 'android-signing.properties'
New-Item -ItemType Directory -Force -Path $data | Out-Null
if (-not (Test-Path -LiteralPath $signing)) {
    $password = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $key = Join-Path $data 'android-release.p12'
    $env:TETHIUM_SIGNING_PASSWORD = $password
    & keytool -genkeypair -keystore $key -storetype PKCS12 -storepass:env TETHIUM_SIGNING_PASSWORD -keypass:env TETHIUM_SIGNING_PASSWORD -alias tethium -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Tethium Android' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
    [IO.File]::WriteAllText($signing, "storeFile=$($key.Replace('\','/'))`npassword=$password`n", [Text.UTF8Encoding]::new($false))
    Remove-Item Env:TETHIUM_SIGNING_PASSWORD
}
[IO.File]::WriteAllText((Join-Path $root 'android/local.properties'), "sdk.dir=$($SdkPath.Replace('\','/').Replace(':','\:'))`n", [Text.UTF8Encoding]::new($false))
$drawable = Join-Path $root 'android/app/src/main/res/drawable-nodpi'
New-Item -ItemType Directory -Force -Path $drawable | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'src/main/resources/dev/tethium/desktop/branding/client/tethium-256.png') -Destination (Join-Path $drawable 'tethium.png')
& "$root/gradlew.bat" -p "$root/android" :app:assembleRelease
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
$output = Join-Path $root 'src/main/resources/mobile'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$apk = Join-Path $root 'android/app/build/outputs/apk/release/app-release.apk'
Copy-Item -LiteralPath $apk -Destination (Join-Path $output 'tethium.apk')
$metadata = Get-Content (Join-Path (Split-Path $apk) 'output-metadata.json') -Raw | ConvertFrom-Json
$release = @{ versionCode=$metadata.elements[0].versionCode; versionName=$metadata.elements[0].versionName; sha256=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant() }
[IO.File]::WriteAllText((Join-Path $output 'release.json'), ($release | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Write-Output "APK: $apk"
