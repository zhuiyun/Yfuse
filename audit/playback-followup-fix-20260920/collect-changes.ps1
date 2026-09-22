$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$baseline = Get-Content (Join-Path $PSScriptRoot 'source-before.json') -Raw | ConvertFrom-Json -AsHashtable
$sourceFiles = @(rg --files composeApp/src tvApp/src phoneShared tvShared -g '*.kt' -g '*.kts' -g '*.xml' -g '!**/build/**')
$changes = @($sourceFiles | ForEach-Object {
    $relative = $_.Replace('\', '/')
    $hash = (Get-FileHash -LiteralPath $_).Hash.ToLowerInvariant()
    if (-not $baseline.ContainsKey($relative) -or $baseline[$relative] -ne $hash) { $relative }
} | Sort-Object -Unique)
ConvertTo-Json -InputObject $changes | Set-Content (Join-Path $PSScriptRoot 'changed-files.json')
$kotlinFiles = @($changes | Where-Object { $_ -match '\.kts?$' })
ConvertTo-Json -InputObject $kotlinFiles | Set-Content (Join-Path $PSScriptRoot 'format-paths.json')
Write-Output "$($changes.Count) changed source files; $($kotlinFiles.Count) Kotlin files."
