$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$jars = @(foreach ($line in Get-Content phoneShared/gradle.lockfile) {
    if ($line -notmatch '^([^:#]+):([^:]+):([^=]+)=(.*)$') { continue }
    if (($Matches[4] -split ',') -notcontains 'ktlint') { continue }
    $module = $Matches[1] + '/' + $Matches[2] + '/' + $Matches[3]
    foreach ($cache in @('.gradle-tmp/caches/modules-2/files-2.1', 'C:/Users/app-inkbird/.gradle/caches/modules-2/files-2.1')) {
        $modulePath = Join-Path $cache $module
        if (Test-Path -LiteralPath $modulePath) {
            Get-ChildItem -LiteralPath $modulePath -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch '(sources|javadoc)\.jar$' } | Select-Object -ExpandProperty FullName
        }
    }
}) | Sort-Object -Unique
$jars += @(foreach ($cache in @('.gradle-tmp/caches/modules-2/files-2.1', 'C:/Users/app-inkbird/.gradle/caches/modules-2/files-2.1')) {
    foreach ($extraGroup in @('ch.qos.logback', 'org.slf4j', 'com.github.ajalt.mordant', 'com.github.ajalt.colormath', 'net.java.dev.jna', 'org.jetbrains')) {
    $loggingPath = Join-Path $cache $extraGroup
    if (Test-Path -LiteralPath $loggingPath) {
        Get-ChildItem -LiteralPath $loggingPath -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch '(sources|javadoc)\.jar$' } | Select-Object -ExpandProperty FullName
    }
    }
})
$jars = $jars | Where-Object {
    ($_ -notmatch 'org.slf4j' -or $_ -match 'slf4j-api[/\\]2\.0\.17[/\\]') -and
    ($_ -notmatch 'ch.qos.logback' -or $_ -match '[/\\]1\.5\.38[/\\]')
}
$sources = @(
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCore2MediaProbe.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNode.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedMediaProbe.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedPlaybackSession.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidFfmpegDemuxer.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/YCoreStartupTiming.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidMediaExtractorDemuxNode.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeEnhancedYPlayer.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidRangeReadWatchdog.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreHttpProxy.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/AppUpdateTools.android.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt',
 'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNodeTest.kt',
 'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidYCoreProxyCloseTest.kt',
 'composeApp/src/androidUnitTest/kotlin/com/yfuse/update/UpdateCheckPolicyTest.kt',
 'composeApp/src/commonMain/kotlin/com/yfuse/core2/network/YBufferController.kt',
 'composeApp/src/commonTest/kotlin/com/yfuse/core2/network/YBufferControllerTest.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidBufferWaitMonitor.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidMediaSourceFailure.kt',
 'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidRangeReadDiagnostics.kt',
 'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidBufferWaitMonitorTest.kt',
 'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidMediaSourceFailureTest.kt'
)
& 'C:/Users/app-inkbird/.jdks/ms-21.0.9/bin/java.exe' -cp ($jars -join ';') com.pinterest.ktlint.Main --format @sources > audit/releases/20260921-playback-recovery/format-check.log 2>&1
$result = $LASTEXITCODE
Get-Content audit/releases/20260921-playback-recovery/format-check.log -Tail 25
exit $result


