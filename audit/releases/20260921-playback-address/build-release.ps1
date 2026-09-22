$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$env:GRADLE_USER_HOME = 'D:/Demo/Yfuse/.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:/Users/app-inkbird/.gradle/caches'
$env:JAVA_HOME = 'C:/Users/app-inkbird/.jdks/ms-21.0.9'
$env:ANDROID_HOME = 'D:/AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:/AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$env:PYTHONUTF8 = '1'
$env:GIT_CONFIG_COUNT = '1'
$env:GIT_CONFIG_KEY_0 = 'safe.directory'
$env:GIT_CONFIG_VALUE_0 = 'D:/Demo/Yfuse'
$releasePython = 'C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
& $releasePython -B (Join-Path $PSScriptRoot 'source-manifest.py') capture
if ($LASTEXITCODE -ne 0) { throw 'Could not record release inputs.' }

# Same phone Full ARM64 profile and production signer as the verified preceding delivery.
# Version comes from version.properties; this delivery never overrides it on the command line.
& .\gradlew.bat :composeApp:verifyDesignSystemUsage :composeApp:assembleRelease `
    -I audit/kernel-fixes-20260920/isolated-build.init.gradle `
    -I audit/playback-optimization-20260920/local-maven.init.gradle `
    -I audit/playback-followup-fix-20260920/lint-dependencies.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-PyfuseApplicationId=com.yfuse' '-PyfuseNativeOnlyRuntime=false' '-PyfuseIncludeMdk=true' `
    '-PallowDebugSigning=false' '-PconfirmMdkDistributionRights=true' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --project-cache-dir .gradle-tmp/kernel-fixes-project-cache `
    --offline --console=plain --no-daemon --max-workers=1 --stacktrace > (Join-Path $PSScriptRoot 'build.log') 2>&1
$releaseExit = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot 'build.log') -Tail 35
if ($releaseExit -ne 0) { throw "Signed release build failed: $releaseExit" }
& $releasePython -B (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Release inputs changed during packaging; do not deliver.' }
Write-Output 'Signed release built; final APK verification is still required.'


