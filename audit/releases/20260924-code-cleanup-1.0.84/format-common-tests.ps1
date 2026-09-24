param([switch]$Check)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$cache = 'C:\Users\app-inkbird\.gradle\caches\modules-2\files-2.1'
$classPath = (Get-Content (Join-Path $PSScriptRoot 'ktlint-classpath.txt') -Raw).Trim()
$extra = Get-ChildItem "$cache\com.github.ajalt.*" -Recurse -Filter '*.jar' |
    Where-Object { $_.Name -notlike '*sources.jar' -and $_.Name -notlike '*javadoc.jar' } |
    Select-Object -ExpandProperty FullName
$more = @(
    Get-ChildItem "$cache\net.java.dev.jna\jna\5.14.0" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    Get-ChildItem "$cache\org.jetbrains\markdown-jvm" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    Get-ChildItem "$cache\it.unimi.dsi\fastutil-core" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    Get-ChildItem "$cache\io.github.oshai\kotlin-logging-jvm" -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notlike '*sources.jar' } | Select-Object -First 1 -ExpandProperty FullName
    Get-ChildItem "$cache\org.slf4j\slf4j-api\2.0.17" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
)
$classPath += ';' + (($extra + $more) -join ';')
$reportRoot = Join-Path $root 'phoneShared/build/reports/ktlint'
$reports = @(
    'ktlintAndroidHostTestSourceSetCheck'
    'ktlintAndroidMainSourceSetCheck'
    'ktlintCommonMainSourceSetCheck'
    'ktlintCommonTestSourceSetCheck'
) | ForEach-Object { Join-Path $reportRoot "$_/$_.txt" }
$files = @(
    foreach ($report in $reports) {
        foreach ($line in (Get-Content $report)) {
            $clean = $line -replace "$([char]27)\[[0-9;]*m", ''
            if ($clean -match '(composeApp\\.*?\.kt):\d+:\d+:') {
                Join-Path $root $matches[1]
            }
        }
    }
) | Sort-Object -Unique
if ($files.Count -eq 0 -or @($files | Where-Object { -not (Test-Path $_) }).Count -gt 0) {
    throw 'No current KtLint findings found, or a finding does not map to a workspace file.'
}
$java = 'D:\software\android-studio-2024.1.1.11-windows\android-studio\jbr\bin\java.exe'
if ($Check) {
    $relativeFiles = $files | ForEach-Object {
        '../' + $_.Substring($root.Length + 1).Replace('\', '/')
    }
    Push-Location (Join-Path $root 'phoneShared')
    try {
        & $java -cp $classPath com.pinterest.ktlint.Main '--baseline=../config/ktlint/phoneShared-baseline.xml' @relativeFiles
    } finally {
        Pop-Location
    }
} else {
    & $java -cp $classPath com.pinterest.ktlint.Main -F @files
}
exit $LASTEXITCODE
