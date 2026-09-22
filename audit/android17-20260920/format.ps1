$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Demo\Yfuse'
$stageRoot = Join-Path $taskRoot 'audit/android17-20260920/format-stage'
$sourcePaths = @(
    'composeApp/build.gradle.kts',
    'tvApp/build.gradle.kts',
    'macrobenchmark/build.gradle.kts',
    'composeApp/src/androidMain/kotlin/com/yfuse/core/network/LocalNetworkPermission.android.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/core/network/LocalNetworkPermissionUi.android.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/core/network/LocalNetworkAccessNotice.android.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/feature/profile/PermissionHealthTools.android.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/network/LanDiscovery.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/network/LocalNetworkPermissionUi.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServerConnectionPermission.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersStore.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersScreen.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt',
    'tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvApp.kt',
    'tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvPermissionHealthScreen.kt',
    'tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServersSettingsScreens.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/feature/servers/ServersStoreTest.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/feature/servers/ServerConnectionPermissionTest.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/core/network/LocalNetworkPermissionTest.kt'
)
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
$staged = @(foreach ($relative in $sourcePaths) {
    $target = Join-Path $stageRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $taskRoot $relative) -Destination $target
    $target
})
Copy-Item -LiteralPath .editorconfig -Destination (Join-Path $stageRoot '.editorconfig')
& 'C:\Users\app-inkbird\.jdks\ms-21.0.9\bin\java.exe' -cp ($jars -join ';') com.pinterest.ktlint.Main --format @staged > audit/android17-20260920/format-files.log 2>&1
$formatResult = $LASTEXITCODE
Get-Content audit/android17-20260920/format-files.log -Tail 45
if ($formatResult -ne 0) { exit $formatResult }
foreach ($relative in $sourcePaths) {
    $target = [IO.Path]::GetFullPath((Join-Path $taskRoot $relative))
    $stagedFile = [IO.Path]::GetFullPath((Join-Path $stageRoot $relative))
    if (-not $target.StartsWith($taskRoot + '\') -or -not $stagedFile.StartsWith($stageRoot + '\')) { throw 'Formatting path escaped the workspace.' }
    if ((Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $stagedFile).Hash) {
        # Replacing the directory entry avoids truncating files mapped by the Windows editor.
        Move-Item -LiteralPath $stagedFile -Destination $target -Force
    }
}
Write-Output "Formatted $($sourcePaths.Count) scoped Kotlin files."





