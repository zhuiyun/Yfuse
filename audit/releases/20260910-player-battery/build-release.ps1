$ErrorActionPreference = 'Stop'
$releaseRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../..'))
$env:GRADLE_USER_HOME = Join-Path $releaseRoot '.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$env:PYTHONUTF8 = '1'
$python = 'C:\Users\app-inkbird\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$gradle = Join-Path $releaseRoot '.gradle-tmp/wrapper/dists/gradle-9.5.0-bin/bvnork1r7n8i6kp5cnkibsc9q/gradle-9.5.0/bin/gradle.bat'
$buildArguments = @(
    ':composeApp:verifyDesignSystemUsage', ':composeApp:assembleRelease',
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9',
    '-PyfuseVersionName=1.0.52', '-PyfuseVersionCode=214',
    '-PyfuseNativeOnlyRuntime=false', '-PyfuseIncludeMdk=true',
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8',
    '--offline', '--console=plain', '--no-daemon', '--max-workers=1'
)
Push-Location $releaseRoot
try {
    & $python (Join-Path $PSScriptRoot 'source-manifest.py') capture
    if ($LASTEXITCODE -ne 0) { throw 'Could not record release inputs.' }
    # Gradle loads the existing signing configuration directly; no secrets are printed or copied.
    & $gradle @buildArguments 2>&1 | Tee-Object -FilePath (Join-Path $PSScriptRoot 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Signed release build failed; see build.log.' }
    & $python (Join-Path $PSScriptRoot 'source-manifest.py') verify
    if ($LASTEXITCODE -ne 0) { throw 'Release inputs changed during packaging.' }
    $destination = Join-Path $PSScriptRoot 'Yfuse-1.0.52-signed-arm64.apk'
    Copy-Item -LiteralPath (Join-Path $releaseRoot 'composeApp/build/outputs/apk/release/composeApp-release.apk') -Destination $destination
    Write-Output "Signed package saved: $destination"
} finally {
    Pop-Location
}
