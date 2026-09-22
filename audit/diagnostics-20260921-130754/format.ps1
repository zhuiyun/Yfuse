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
 'composeApp/src/commonMain/kotlin/com/yfuse/core/network/EmbyStream.kt',
 'composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt',
 'composeApp/src/commonTest/kotlin/com/yfuse/feature/player/PlaybackRoadmapTest.kt'
)
& 'C:/Users/app-inkbird/.jdks/ms-21.0.9/bin/java.exe' -cp ($jars -join ';') com.pinterest.ktlint.Main --format @sources > audit/diagnostics-20260921-130754/format.log 2>&1
$result = $LASTEXITCODE
Get-Content audit/diagnostics-20260921-130754/format.log -Tail 25
exit $result
