$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Demo\Yfuse'
$stageRoot = Join-Path $taskRoot 'audit/health-fixes-20260920/final-format/format-stage'
$sourcePaths = @(Get-Content audit/health-fixes-20260920/final-format/format-paths.json -Raw | ConvertFrom-Json)
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
$snapshotsRoot = Join-Path $taskRoot 'audit/health-fixes-20260920/final-format/originals'
$originalHashes = @{}
$staged = @(foreach ($relative in $sourcePaths) {
    $source = Join-Path $taskRoot $relative
    $target = Join-Path $stageRoot $relative
    $snapshot = Join-Path $snapshotsRoot $relative
    New-Item -ItemType Directory -Path (Split-Path $target), (Split-Path $snapshot) -Force | Out-Null
    $sourceBytes = [IO.File]::ReadAllBytes($source)
    [IO.File]::WriteAllBytes($snapshot, $sourceBytes)
    [IO.File]::WriteAllBytes($target, $sourceBytes)
    $originalHashes[$relative] = (Get-FileHash -LiteralPath $snapshot -Algorithm SHA256).Hash
    if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ne $originalHashes[$relative]) { throw "Concurrent edit during snapshot: $relative; reread before formatting." }
    $target
})
$originalHashes | ConvertTo-Json | Set-Content -LiteralPath 'audit/health-fixes-20260920/final-format/original-hashes.json' -Encoding utf8
Copy-Item -LiteralPath .editorconfig -Destination (Join-Path $stageRoot '.editorconfig')
& 'C:\Users\app-inkbird\.jdks\ms-21.0.9\bin\java.exe' -cp ($jars -join ';') com.pinterest.ktlint.Main --format @staged > audit/health-fixes-20260920/final-format/format-files.log 2>&1
$formatResult = $LASTEXITCODE
if ($formatResult -ne 0) {
    & 'C:\Users\app-inkbird\.jdks\ms-21.0.9\bin\java.exe' -cp ($jars -join ';') com.pinterest.ktlint.Main --format @staged >> audit/health-fixes-20260920/final-format/format-files.log 2>&1
    $formatResult = $LASTEXITCODE
}
Get-Content audit/health-fixes-20260920/final-format/format-files.log -Tail 45
if ($formatResult -ne 0) { exit $formatResult }
Write-Output "Formatted $($sourcePaths.Count) staged Kotlin files; source files are unchanged pending diff and hash verification."