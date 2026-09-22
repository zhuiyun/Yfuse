param([ValidateSet('locks', 'server', 'client', 'lint', 'final', 'instrumented', 'focused')][string]$Mode = 'server', [switch]$DryRun)
$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '../..'))
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
# Match the existing local audit runtime workaround; no global JDK/cache configuration changes.
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$tasks = switch ($Mode) {
    locks { @(':composeApp:resolveHealthLocks', ':phoneShared:resolveHealthLocks', ':tvApp:resolveHealthLocks', ':tvShared:resolveHealthLocks', ':macrobenchmark:resolveHealthLocks', ':mdkAndroid:resolveHealthLocks', ':watchTogetherProtocol:resolveHealthLocks', ':watchTogetherServer:resolveHealthLocks', '-I', 'audit/health-fixes-20260920/resolve-locks.init.gradle', '--update-locks', 'org.bouncycastle:*,com.squareup.wire:*,org.apache.commons:commons-lang3,org.apache.httpcomponents:httpclient,org.jetbrains.kotlinx:kotlinx-serialization*') }
    server { @(':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test') }
    client { @(':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest', ':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin') }
    lint { @(':composeApp:lintDebug', ':tvApp:lintDebug', ':composeApp:verifyDesignSystemUsage', 'ktlintCheck') }
    final { @(':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test', ':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest', ':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin', ':composeApp:compileDebugAndroidTestKotlin', ':macrobenchmark:compileBenchmarkKotlin', ':composeApp:lintDebug', ':tvApp:lintDebug', ':composeApp:verifyDesignSystemUsage', 'ktlintCheck', '--continue') }
    instrumented { @(':composeApp:compileDebugAndroidTestKotlin', ':macrobenchmark:compileBenchmarkKotlin') }
    focused { @(':phoneShared:testAndroidHostTest', '--tests', 'com.yfuse.core.data.PlaybackEventOutboxTest', '--tests', 'com.yfuse.core.data.EmbyPathContractTest', '--tests', 'com.yfuse.core.sync.playback.PlaybackSyncManagerTest', '--tests', 'com.yfuse.core.sync.playback.PlaybackSyncStoreTest', '--tests', 'com.yfuse.feature.player.PlaybackReportingCoordinatorTest', '--tests', 'com.yfuse.feature.player.PlaybackSourceSwitchCoordinatorTest', '--tests', 'com.yfuse.core2.android.AndroidProbeBudgetTest', '-PyfuseHealthFocused=true') }
}
if ($DryRun) { $tasks += '--dry-run' }
$logName = if ($DryRun) { "$Mode-dry-run.log" } else { "$Mode.log" }
& .\gradlew.bat @tasks `
    -I audit/health-fixes-20260920/isolated-build.init.gradle `
    -I audit/health-fixes-20260920/local-maven.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --project-cache-dir .gradle-tmp/health-project-cache `
    --offline --console=plain --no-daemon --max-workers=1 --stacktrace > (Join-Path $PSScriptRoot $logName) 2>&1
$result = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot $logName) -Tail 60
exit $result
