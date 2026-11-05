param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$RunLabel,
    [Parameter(Mandatory = $true)][string]$MediaSha256,
    [ValidateSet('YCore', 'Exo', 'Mpv', 'Mdk', 'SenPlayer')][string]$Engine = 'YCore',
    [ValidateSet('Off', 'Single', 'Dual')][string]$SubtitleMode = 'Off',
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_.]+$')][string]$Package = 'com.yfuse',
    [ValidateRange(10, 600)][int]$DurationSeconds = 90,
    [ValidateRange(1, 30)][int]$IntervalSeconds = 5,
    [string]$Adb = 'adb',
    [string]$OutputRoot = 'build/playback-memory'
)
$ErrorActionPreference = 'Stop'
$safeLabel = $RunLabel -replace '[^A-Za-z0-9_.-]', '_'
$runPath = Join-Path $OutputRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $safeLabel)
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$metadata = [ordered]@{
    startedUtc = (Get-Date).ToUniversalTime().ToString('o')
    serial = $Serial
    package = $Package
    mediaSha256 = $MediaSha256
    declaredEngine = $Engine
    declaredSubtitleMode = $SubtitleMode
    note = 'Labels are operator supplied. Correlate with player.performance/process_memory engine and subtitleMode events. PSS/RSS are process totals, not decoder-exclusive. Same media, seek position, warmup, brightness, volume and network are required for comparisons.'
    durationSeconds = $DurationSeconds
    intervalSeconds = $IntervalSeconds
    device = ((& $Adb -s $Serial shell getprop ro.product.model) -join '').Trim()
    android = ((& $Adb -s $Serial shell getprop ro.build.version.release) -join '').Trim()
    pageSize = ((& $Adb -s $Serial shell getconf PAGE_SIZE) -join '').Trim()
}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runPath 'run.json') -Encoding utf8
$samples = [System.Collections.Generic.List[object]]::new()
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$index = 0
while ($watch.Elapsed.TotalSeconds -lt $DurationSeconds) {
    $raw = (& $Adb -s $Serial shell dumpsys meminfo $Package) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $raw -match 'No process found') { throw 'Playback process is not available.' }
    $raw | Set-Content -LiteralPath (Join-Path $runPath ('meminfo-{0:D3}.txt' -f $index)) -Encoding utf8
    $pss = [regex]::Match($raw, 'TOTAL PSS:\s*(\d+)')
    $rss = [regex]::Match($raw, 'TOTAL RSS:\s*(\d+)')
    $native = [regex]::Match($raw, '(?m)^\s*Native Heap\s+(\d+)')
    $samples.Add([pscustomobject]@{
        seconds = [math]::Round($watch.Elapsed.TotalSeconds, 2)
        processPssKb = if ($pss.Success) { [long]$pss.Groups[1].Value } else { $null }
        processRssKb = if ($rss.Success) { [long]$rss.Groups[1].Value } else { $null }
        nativeHeapPssKb = if ($native.Success) { [long]$native.Groups[1].Value } else { $null }
    })
    $index++
    Start-Sleep -Seconds $IntervalSeconds
}
$samples | Export-Csv -LiteralPath (Join-Path $runPath 'memory.csv') -NoTypeInformation -Encoding utf8
(& $Adb -s $Serial shell dumpsys gfxinfo $Package framestats) | Set-Content -LiteralPath (Join-Path $runPath 'ui-framestats.txt') -Encoding utf8
Write-Output $runPath
