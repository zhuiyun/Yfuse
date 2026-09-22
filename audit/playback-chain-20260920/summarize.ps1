$ErrorActionPreference = 'Stop'
$auditRoot = $PSScriptRoot
$projectRoot = 'D:\Demo\Yfuse'
$resultsPath = Join-Path $projectRoot 'phoneShared/build/test-results/testAndroidHostTest'
$resultFiles = @(Get-ChildItem -LiteralPath $resultsPath -Filter 'TEST-*.xml')
$rows = @(
    foreach ($file in $resultFiles) {
        [xml]$result = Get-Content -LiteralPath $file.FullName -Raw
        [pscustomobject]@{
            suite = $result.testsuite.name
            tests = [int]$result.testsuite.tests
            failures = [int]$result.testsuite.failures
            errors = [int]$result.testsuite.errors
            skipped = [int]$result.testsuite.skipped
            timestamp = $result.testsuite.timestamp
        }
    }
)
$testCount = ($rows | Measure-Object -Property tests -Sum).Sum
$failureCount = ($rows | Measure-Object -Property failures -Sum).Sum
$errorCount = ($rows | Measure-Object -Property errors -Sum).Sum
$skippedCount = ($rows | Measure-Object -Property skipped -Sum).Sum
if ($rows.Count -ne 18 -or $testCount -ne 111 -or $failureCount -ne 0 -or $errorCount -ne 0 -or $skippedCount -ne 0) {
    throw 'Unexpected test coverage or non-passing results.'
}
if ($rows.Where({ -not $_.timestamp.StartsWith('2026-09-20') }).Count -ne 0) {
    throw 'Test result date does not match this review.'
}
$log = Get-Content -LiteralPath (Join-Path $auditRoot 'verify.log') -Raw
if (-not $log.Contains('BUILD SUCCESSFUL')) { throw 'Missing successful Gradle verification.' }
$archive = Join-Path $auditRoot 'unit-results'
New-Item -ItemType Directory -Path $archive -Force | Out-Null
foreach ($file in $resultFiles) { Copy-Item -LiteralPath $file.FullName -Destination $archive }
$reportPath = Join-Path $projectRoot 'docs/PLAYBACK_CHAIN_REVIEW_20260920.md'
$report = Get-Content -LiteralPath $reportPath -Raw
$sourcePaths = [regex]::Matches($report, '\]\((?:/)?(D:/Demo/Yfuse/[^\)]+?):\d+\)') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
$sourceHashes = @(
    foreach ($sourcePath in $sourcePaths) {
        [pscustomobject]@{ path = $sourcePath; sha256 = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
)
$summary = [ordered]@{
    date = '2026-09-20'
    baselineHead = '1bbf5f12f05e43ef4b6e84ef837766ee92a31bfc'
    basis = 'Current dirty workspace, including Android 17 migration; no playback implementation changes made in this review.'
    gradleTask = ':phoneShared:testAndroidHostTest'
    buildSuccessful = $true
    suites = $rows.Count
    tests = [int]$testCount
    failures = [int]$failureCount
    errors = [int]$errorCount
    skipped = [int]$skippedCount
    resultProvenance = 'XML results were produced at 2026-09-20T01:56:34Z through 01:56:37Z. Final Gradle verification reused them as UP-TO-DATE. The first Windows PowerShell wrapper reported the Java stderr banner as an error; the child Gradle execution still produced these results.'
    device = @{ androidApi = 28; installedVersionName = '1.0.54'; installedVersionCode = 216; currentBuildInstalled = $false }
    playbackPerformanceMeasured = $false
    results = $rows
    evidenceSourceHashes = $sourceHashes
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $auditRoot 'verification.json') -Encoding utf8
[pscustomobject]@{ suites = $rows.Count; tests = $testCount; failures = $failureCount; errors = $errorCount; skipped = $skippedCount; evidenceFiles = $sourceHashes.Count } | ConvertTo-Json
