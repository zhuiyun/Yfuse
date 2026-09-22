$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Demo\Yfuse'
$evidenceRoot = Join-Path $taskRoot 'audit/health-fixes-20260920/final-format'
$stageRoot = Join-Path $evidenceRoot 'format-stage'
$paths = @(Get-Content -LiteralPath (Join-Path $evidenceRoot 'format-paths.json') -Raw | ConvertFrom-Json)
$hashes = Get-Content -LiteralPath (Join-Path $evidenceRoot 'original-hashes.json') -Raw | ConvertFrom-Json
foreach ($relative in $paths) {
    $target = [IO.Path]::GetFullPath((Join-Path $taskRoot $relative))
    $staged = [IO.Path]::GetFullPath((Join-Path $stageRoot $relative))
    if (-not $target.StartsWith($taskRoot + '\') -or -not $staged.StartsWith($stageRoot + '\')) { throw 'Formatting path escaped the workspace.' }
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hashes.$relative) { throw "Concurrent edit detected: $relative. Nothing further is applied; reread that source before retry." }
}
$records = @(foreach ($relative in $paths) {
    $target = [IO.Path]::GetFullPath((Join-Path $taskRoot $relative))
    $staged = [IO.Path]::GetFullPath((Join-Path $stageRoot $relative))
    # Recheck immediately before replacement, after the all-files preflight.
    $before = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($before -ne $hashes.$relative) { throw "Concurrent edit before replacement: $relative. Reread before retry." }
    $after = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash
    if ($before -ne $after) { Move-Item -LiteralPath $staged -Destination $target -Force }
    [pscustomobject]@{ File = $relative; BeforeHash = $before; AfterHash = $after; LastWriteTime = (Get-Item -LiteralPath $target).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss.fff zzz') }
})
$records | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidenceRoot 'apply-results.json') -Encoding utf8
$records | ConvertTo-Json