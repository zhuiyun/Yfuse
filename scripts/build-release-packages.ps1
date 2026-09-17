param(
    [switch]$AllowDebugSigning,
    # Per-release acknowledgement of the MDK SDK distribution terms. It is intentionally not
    # stored in gradle.properties: the release owner passes it for each production build.
    [switch]$ConfirmMdkDistributionRights
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = Get-Content (Join-Path $root 'version.properties') | ConvertFrom-StringData
$destination = Join-Path $root 'composeApp/build/outputs/distribution'
New-Item -ItemType Directory -Force -Path $destination | Out-Null

if (-not $ConfirmMdkDistributionRights -and -not $AllowDebugSigning) {
    throw ('Production signing of the full (MDK) package requires the release owner to confirm the ' +
        'MDK distribution rights for this release. Complete the native-license checklist in ' +
        'docs/third-party-licenses/README.md, then rerun with -ConfirmMdkDistributionRights. ' +
        'For a non-distributable verification build pass -AllowDebugSigning instead.')
}

# Signed release validation recompiles from the checked-out source instead of reusing incremental
# Kotlin state, so a stale local cache cannot leak deleted or renamed classes into the package.
$commonArgs = @('-Pkotlin.incremental=false')
if ($AllowDebugSigning) { $commonArgs += '-PallowDebugSigning=true' }

$fullArgs = @(
    ':composeApp:assembleRelease',
    '-PyfuseNativeOnlyRuntime=false',
    '-PyfuseIncludeMdk=true'
) + $commonArgs
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
    @commonArgs
if ($LASTEXITCODE -ne 0) { throw 'Compact release build failed' }
Copy-Item -Force `
    (Join-Path $root 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
    (Join-Path $destination "Yfuse-$($version.VERSION_NAME)-compact-arm64.apk")

Get-ChildItem $destination -Filter "Yfuse-$($version.VERSION_NAME)-*-arm64.apk" |
    Select-Object Name, Length, @{Name='SHA256'; Expression={(Get-FileHash $_.FullName -Algorithm SHA256).Hash}}
