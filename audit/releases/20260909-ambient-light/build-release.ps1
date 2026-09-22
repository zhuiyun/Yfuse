$ErrorActionPreference = 'Stop'
$releaseRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$snapshotParent = Join-Path $PSScriptRoot 'source/composeApp'
$snapshot = Join-Path $snapshotParent 'src'
if (!(Test-Path -LiteralPath $snapshot)) {
    New-Item -ItemType Directory -Force -Path $snapshotParent | Out-Null
    Copy-Item -LiteralPath (Join-Path $releaseRoot 'composeApp/src') -Destination $snapshot -Recurse
}
$sourceHashes = @(Get-ChildItem -LiteralPath $snapshot -Recurse -File | ForEach-Object {
    [PSCustomObject]@{
        Path = [IO.Path]::GetRelativePath($snapshot, $_.FullName)
        SHA256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
})
$sourceHashes | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'source-hashes.json') -Encoding utf8

# Gradle reads the existing release keystore directly. No signing secrets are copied or logged.
$env:GRADLE_USER_HOME = Join-Path $releaseRoot '.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$gradle = Join-Path $releaseRoot '.gradle-tmp/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
$buildArguments = @(
    ':composeApp:verifyDesignSystemUsage',
    ':composeApp:testReleaseUnitTest',
    '--tests', 'com.yfuse.feature.search.SearchResultsHandoffTest',
    '--tests', 'com.yfuse.app.RootTabMotionTest',
    '--tests', 'com.yfuse.core.designsystem.LoadingMotionTest',
    '--tests', 'com.yfuse.core.data.ThemePreferencesTest',
    '--tests', 'com.yfuse.core.designsystem.AmbientLightTest',
    '--tests', 'com.yfuse.core.data.PlaybackPreferencesTest',
    '--tests', 'com.yfuse.feature.player.AmbientCopyTest',
    '--tests', 'com.yfuse.feature.player.Core2SurfaceTest',
    ':composeApp:assembleRelease',
    '-I', (Join-Path $PSScriptRoot 'frozen-mobile-sources.init.gradle'),
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9',
    '-PyfuseVersionName=1.0.49', '-PyfuseVersionCode=211',
    '-PyfuseNativeOnlyRuntime=false', '-PyfuseIncludeMdk=true',
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8',
    '--offline', '--console=plain', '--no-daemon', '--max-workers=1'
)
Push-Location $releaseRoot
try {
    & $gradle @buildArguments 2>&1 | Tee-Object -FilePath (Join-Path $PSScriptRoot 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Signed release build failed; see build.log.' }
    Copy-Item -LiteralPath (Join-Path $releaseRoot 'composeApp/build/outputs/apk/release/composeApp-release.apk') `
        -Destination (Join-Path $PSScriptRoot 'Yfuse-1.0.49-signed-arm64.apk')
} finally {
    Pop-Location
}
