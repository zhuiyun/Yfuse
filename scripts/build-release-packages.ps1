param(
    [switch]$AllowDebugSigning,
    [switch]$ConfirmMdkDistributionRights
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = Get-Content (Join-Path $root 'version.properties') | ConvertFrom-StringData
$destination = Join-Path $root 'composeApp/build/outputs/distribution'
New-Item -ItemType Directory -Force -Path $destination | Out-Null

$signingArgs = @()
if ($AllowDebugSigning) { $signingArgs += '-PallowDebugSigning=true' }

$fullArgs = @(
    ':composeApp:assembleRelease',
    '-PyfuseNativeOnlyRuntime=false',
    '-PyfuseIncludeMdk=true'
) + $signingArgs
if ($ConfirmMdkDistributionRights) { $fullArgs += '-PconfirmMdkDistributionRights=true' }
& (Join-Path $root 'gradlew.bat') @fullArgs
if ($LASTEXITCODE -ne 0) { throw 'Full release build failed' }
Copy-Item -Force `
    (Join-Path $root 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
    (Join-Path $destination "Yfuse-$($version.VERSION_NAME)-full-arm64.apk")

& (Join-Path $root 'gradlew.bat') `
    ':composeApp:assembleRelease' `
    '-PyfuseNativeOnlyRuntime=false' `
    '-PyfuseIncludeMdk=false' `
    @signingArgs
if ($LASTEXITCODE -ne 0) { throw 'Compact release build failed' }
Copy-Item -Force `
    (Join-Path $root 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
    (Join-Path $destination "Yfuse-$($version.VERSION_NAME)-compact-arm64.apk")

Get-ChildItem $destination -Filter "Yfuse-$($version.VERSION_NAME)-*-arm64.apk" |
    Select-Object Name, Length, @{Name='SHA256'; Expression={(Get-FileHash $_.FullName -Algorithm SHA256).Hash}}
