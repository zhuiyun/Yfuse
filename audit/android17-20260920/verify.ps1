$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
& .\gradlew.bat :phoneShared:testAndroidHostTest --tests com.yfuse.core.network.* --tests com.yfuse.feature.servers.* --tests com.yfuse.feature.player.PlaybackKeepAliveServiceTest --tests com.yfuse.feature.player.PlaybackLifecyclePolicyTest :composeApp:compileDebugKotlin :tvApp:compileDebugKotlin :tvShared:testAndroidHostTest :composeApp:processDebugMainManifest :tvApp:processDebugMainManifest :composeApp:verifyDesignSystemUsage :composeApp:lintDebug :tvApp:lintDebug `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --offline --console=plain --no-daemon --max-workers=1 > (Join-Path $PSScriptRoot 'verify.log') 2>&1
$result = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot 'verify.log') -Tail 40
exit $result

