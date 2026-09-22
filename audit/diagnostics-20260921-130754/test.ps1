$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$env:GRADLE_USER_HOME = 'D:/Demo/Yfuse/.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:/Users/app-inkbird/.gradle/caches'
$env:JAVA_HOME = 'C:/Users/app-inkbird/.jdks/ms-21.0.9'
$env:ANDROID_HOME = 'D:/AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:/AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$env:GIT_CONFIG_COUNT = '1'
$env:GIT_CONFIG_KEY_0 = 'safe.directory'
$env:GIT_CONFIG_VALUE_0 = 'D:/Demo/Yfuse'
& .\gradlew.bat :phoneShared:testAndroidHostTest --tests 'com.yfuse.feature.player.PlaybackRoadmapTest' --tests 'com.yfuse.feature.player.PlayerStoreTest' --tests 'com.yfuse.feature.player.PlexPlaybackRouteTest' --tests 'com.yfuse.feature.player.UhdBluRayServerStreamTest' --tests 'com.yfuse.core.network.EmbyStreamTest' `
    -I audit/kernel-fixes-20260920/isolated-build.init.gradle `
    -I audit/playback-optimization-20260920/local-maven.init.gradle `
    -I audit/playback-followup-fix-20260920/lint-dependencies.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --project-cache-dir .gradle-tmp/kernel-fixes-project-cache `
    --offline --console=plain --no-daemon --max-workers=1 > (Join-Path $PSScriptRoot 'test.log') 2>&1
$testExit = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot 'test.log') -Tail 45
exit $testExit
