$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$playbackTests = @(
    'com.yfuse.feature.player.PlayerStoreTest',
    'com.yfuse.feature.player.PlaybackPreloadTest',
    'com.yfuse.feature.player.PlaybackPreloaderPolicyTest',
    'com.yfuse.feature.player.PlaybackQueueUpdateTest',
    'com.yfuse.feature.player.PlaybackProbeCompletionGateTest',
    'com.yfuse.feature.player.AdaptiveStartupLoadControlTest',
    'com.yfuse.feature.player.StartupTransferEvidenceTest',
    'com.yfuse.core.playback.PlaybackStartupPolicyTest',
    'com.yfuse.core.playback.PlaybackBufferPolicyTest',
    'com.yfuse.core2.strategy.YProbeTruthPolicyTest',
    'com.yfuse.core2.android.AndroidTransportStartupAndResumeTest',
    'com.yfuse.core2.android.AndroidTransportMediaDataSourcePrefetchTest',
    'com.yfuse.core2.android.AndroidPreparedMediaSlotTest',
    'com.yfuse.core2.android.AndroidYCoreVerifiedRouteMemoryTest',
    'com.yfuse.core2.android.AndroidProbeBudgetTest',
    'com.yfuse.core2.android.AndroidBoundedProbeTest',
    'com.yfuse.core2.android.AndroidNextItemPreloadWindowTest',
    'com.yfuse.core2.android.AndroidYCoreBlockCacheTest'
)
$testArgs = @(':phoneShared:testAndroidHostTest')
foreach ($playbackTest in $playbackTests) { $testArgs += @('--tests', $playbackTest) }
& .\gradlew.bat @testArgs `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --offline --console=plain --no-daemon --max-workers=1 > (Join-Path $PSScriptRoot 'verify.log') 2>&1
$result = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot 'verify.log') -Tail 30
exit $result
