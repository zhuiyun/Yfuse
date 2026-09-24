param(
    [switch]$AllowDebugSigning,
    # Per-release acknowledgement of the MDK SDK distribution terms. It is intentionally not
    # stored in gradle.properties: the release owner passes it for each production build.
    [switch]$ConfirmMdkDistributionRights,
    # A release built from an uncommitted working tree cannot be reproduced from git history
    # alone and must not silently claim to be the clean commit it was built on top of. Refused by
    # default; pass this only for a knowingly uncommitted verification/debugging package.
    [switch]$AllowDirty
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = Get-Content (Join-Path $root 'version.properties') | ConvertFrom-StringData
$destination = Join-Path $root 'composeApp/build/outputs/distribution'
New-Item -ItemType Directory -Force -Path $destination | Out-Null

# Traceability: record exactly what source produced this package, not just its version number.
# Uses the same recipe as gradle/diagnostic-build.gradle.kts (git status --porcelain for dirty,
# git diff HEAD -> sha256 first 12 hex chars for a short diff id), so a package built here and the
# BuildConfig.BUILD_REVISION baked into its APK can be cross-checked against each other.
$sourceCommit = (& git -C $root rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or -not $sourceCommit) {
    throw 'Unable to resolve the current git commit (git rev-parse HEAD failed). Run this from a git checkout.'
}

$statusPorcelain = & git -C $root status --porcelain
if ($LASTEXITCODE -ne 0) { throw 'git status --porcelain failed' }
$treeDirty = [bool]$statusPorcelain

$diffHash = ''
if ($treeDirty) {
    $diffLines = & git -C $root diff HEAD
    if ($LASTEXITCODE -ne 0) { throw 'git diff HEAD failed' }
    $diffText = if ($diffLines) { $diffLines -join "`n" } else { '' }
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hashBytes = $sha256.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($diffText))
    } finally {
        $sha256.Dispose()
    }
    $diffHash = (-join ($hashBytes | ForEach-Object { $_.ToString('x2') })).Substring(0, 12)
}

if ($treeDirty -and -not $AllowDirty) {
    throw ('Refusing to build a release package from an uncommitted working tree ' +
        "(commit $sourceCommit, diff hash $diffHash). Commit or stash your changes, or rerun " +
        'with -AllowDirty to knowingly package an uncommitted tree.')
}
if ($treeDirty -and $AllowDirty) {
    Write-Warning ('!' * 78)
    Write-Warning 'WARNING: building a RELEASE package from an UNCOMMITTED working tree (-AllowDirty).'
    Write-Warning "The packaged APK will NOT match commit $sourceCommit alone; diff hash $diffHash."
    Write-Warning 'Do not distribute this build as the tagged release unless this is intentional.'
    Write-Warning ('!' * 78)
}

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

$artifacts = Get-ChildItem $destination -Filter "Yfuse-$($version.VERSION_NAME)-*-arm64.apk" |
    Select-Object Name, Length, @{Name='SHA256'; Expression={(Get-FileHash $_.FullName -Algorithm SHA256).Hash}}
$artifacts

# P0 traceability: persist what commit and tree state produced these exact artifacts next to them,
# so a locally packaged release can never be mistaken for a clean-commit build it was not. Kept
# minimal on purpose; signing itself is untouched by this script and by this file.
$verification = [ordered]@{
    generatedAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    versionName  = $version.VERSION_NAME
    versionCode  = $version.VERSION_CODE
    sourceCommit = $sourceCommit
    treeDirty    = $treeDirty
    diffHash     = $diffHash
    artifacts    = @($artifacts | ForEach-Object {
        [ordered]@{ name = $_.Name; length = $_.Length; sha256 = $_.SHA256 }
    })
}
$verification | ConvertTo-Json -Depth 4 |
    Set-Content -Path (Join-Path $destination 'verification.json') -Encoding utf8
