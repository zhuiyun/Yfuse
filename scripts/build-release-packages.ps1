param(
    [switch]$AllowDebugSigning,
    [ValidateSet('ycore-senplayer')]
    [string]$PlaybackProfile = 'ycore-senplayer',
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

# R8 can emit a method ART's verifier rejects; its class then throws VerifyError the first time it
# is used, long after the app has started (the 1.0.97 player crash). Same check as
# scripts/verify-release-dex.sh, on the same pinned jars; the R8 mapping names the app's own
# methods, which must stay within 256 registers.
function Assert-DexVerifies([string]$Apk, [string]$Mapping) {
    $tools = Join-Path $root 'build/dex-verify-tools'
    New-Item -ItemType Directory -Force -Path $tools | Out-Null
    $classpath = @()
    foreach ($line in Get-Content (Join-Path $root 'scripts/dex-verify/tools.sha256')) {
        if (-not $line.Trim() -or $line.StartsWith('#')) { continue }
        $expected, $path = $line.Trim() -split '\s+', 2
        $jar = Join-Path $tools (Split-Path $path -Leaf)
        if (-not (Test-Path $jar) -or (Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) {
            Invoke-WebRequest -UseBasicParsing -Uri "https://repo1.maven.org/maven2/$path" -OutFile "$jar.part"
            $actual = (Get-FileHash "$jar.part" -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actual -ne $expected) {
                Remove-Item -Force "$jar.part"
                throw "$path has SHA-256 $actual, expected $expected"
            }
            Move-Item -Force "$jar.part" $jar
        }
        $classpath += $jar
    }
    & java -Xmx2g -cp ($classpath -join [IO.Path]::PathSeparator) `
        (Join-Path $root 'scripts/dex-verify/DexRegisterTypeCheck.java') --android-project $root --mapping $Mapping $Apk
    if ($LASTEXITCODE -ne 0) {
        throw "ART would reject DEX code in $Apk (findings above); do not distribute this package"
    }
}

# Verify the output, including original SenPlayer asset pins and unobfuscated class identities.
# Build flags alone do not prove that a transitive legacy runtime stayed out of the APK.
function Assert-PlaybackPackage([string]$Apk, [string]$Mapping) {
    & python (Join-Path $root 'scripts/verify-playback-package.py') $Apk --mapping $Mapping
    if ($LASTEXITCODE -ne 0) {
        throw "The YCore + SenPlayer package boundary failed for $Apk; do not distribute this package"
    }
}

# Signed release validation recompiles from the checked-out source instead of reusing incremental
# Kotlin state, so a stale local cache cannot leak deleted or renamed classes into the package.
$commonArgs = @('-Pkotlin.incremental=false')
if ($AllowDebugSigning) { $commonArgs += '-PallowDebugSigning=true' }

$releaseArgs = @(
    ':composeApp:assembleRelease',
    '-PyfuseNativeOnlyRuntime=true',
    '-PyfuseIncludeMdk=false',
    "-PyfusePlaybackProfile=$PlaybackProfile"
) + $commonArgs
& (Join-Path $root 'gradlew.bat') @releaseArgs
if ($LASTEXITCODE -ne 0) { throw 'YCore + SenPlayer release build failed' }
Assert-PlaybackPackage `
    (Join-Path $root 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
    (Join-Path $root 'composeApp/build/outputs/mapping/release/mapping.txt')
Assert-DexVerifies `
    (Join-Path $root 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
    (Join-Path $root 'composeApp/build/outputs/mapping/release/mapping.txt')
Copy-Item -Force `
    (Join-Path $root 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
    (Join-Path $destination "Yfuse-$($version.VERSION_NAME)-$PlaybackProfile-arm64.apk")

# List only the artifact produced by this invocation, excluding older package profiles.
$artifacts = Get-Item (Join-Path $destination "Yfuse-$($version.VERSION_NAME)-$PlaybackProfile-arm64.apk") |
    Select-Object Name, Length, @{Name='SHA256'; Expression={(Get-FileHash $_.FullName -Algorithm SHA256).Hash}}
$artifacts

# P0 traceability: persist what commit and tree state produced these exact artifacts next to them,
# so a locally packaged release can never be mistaken for a clean-commit build it was not. Kept
# minimal on purpose; signing itself is untouched by this script and by this file.
$verification = [ordered]@{
    generatedAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    versionName  = $version.VERSION_NAME
    versionCode  = $version.VERSION_CODE
    playbackProfile = $PlaybackProfile
    playbackEngines = @('YCore', 'SenPlayer')
    mdkIncluded = $false
    sourceCommit = $sourceCommit
    treeDirty    = $treeDirty
    diffHash     = $diffHash
    artifacts    = @($artifacts | ForEach-Object {
        [ordered]@{ name = $_.Name; length = $_.Length; sha256 = $_.SHA256 }
    })
}
$verification | ConvertTo-Json -Depth 4 |
    Set-Content -Path (Join-Path $destination 'verification.json') -Encoding utf8
