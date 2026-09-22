$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Demo\Yfuse'
$stageRoot = Join-Path $taskRoot 'audit/glass-presets-20260918/format-stage'
$sourcePaths = @(
    'composeApp/src/androidMain/kotlin/com/yfuse/core/designsystem/Backdrop.android.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Backdrop.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/Dialogs.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/GlassMaterial.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/GlassMaterialPreview.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ModalGlass.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/GlassMaterialSettingsScreen.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/core/data/ThemePreferencesTest.kt',
    'composeApp/src/commonTest/kotlin/com/yfuse/core/designsystem/GlassMaterialTest.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/GlassMaterialPreset.kt',
    'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/GlassMaterialFinish.kt'
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
& 'C:\Users\app-inkbird\.jdks\ms-21.0.9\bin\java.exe' -cp ($jars -join ';') com.pinterest.ktlint.Main --format @staged > audit/glass-presets-20260918/format-files.log 2>&1
$formatResult = $LASTEXITCODE
Get-Content audit/glass-presets-20260918/format-files.log -Tail 45
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


