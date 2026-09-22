$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$env:PYTHONUTF8 = '1'
$releasePython = 'C:\Users\app-inkbird\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') capture
if ($LASTEXITCODE -ne 0) { throw 'Could not record release inputs.' }
& '.\.gradle-tmp\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' `
    :composeApp:verifyDesignSystemUsage :composeApp:assembleRelease `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' `
    '-PyfuseNativeOnlyRuntime=false' '-PyfuseIncludeMdk=true' '-PallowDebugSigning=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --offline --console=plain --no-daemon --max-workers=1 `
    *> (Join-Path $PSScriptRoot 'build.log')
$releaseExit = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot 'build.log') -Tail 70
if ($releaseExit -ne 0) { throw "Signed release build failed: $releaseExit" }
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Release inputs changed during packaging.' }
Copy-Item -LiteralPath 'composeApp/build/outputs/apk/release/composeApp-release.apk' `
    -Destination 'artifacts/releases/ambient-family-1.0.65-227/Yfuse-1.0.65-227-full-arm64-signed.apk'
Write-Output 'Signed APK created; final package verification is still required.'
