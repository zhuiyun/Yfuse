param(
    [string]$RepositoryRoot = 'D:\Demo\Yfuse',
    [string]$GradleCache = 'C:\Users\app-inkbird\.gradle\caches\modules-2\files-2.1'
)
$ErrorActionPreference = 'Stop'
$outputDirectory = $PSScriptRoot
$mapperPath = Join-Path $RepositoryRoot 'composeApp\src\androidMain\kotlin\com\yfuse\core2\android\AndroidFfmpegDemuxer.kt'
$subtitlePath = Join-Path $RepositoryRoot 'composeApp\src\commonMain\kotlin\com\yfuse\core2\subtitle\YSubtitle.kt'
$source = [System.IO.File]::ReadAllText($mapperPath)
$begin = $source.IndexOf('internal fun ByteArray.toBitmapSubtitleCues(')
$lastLine = 'private fun ByteBuffer.unsignedIntToLong(): Long = int.toLong() and 0xffff_ffffL'
$end = $source.IndexOf($lastLine, $begin)
if ($begin -lt 0 -or $end -lt 0) { throw 'Production mapper source boundaries changed.' }
$mapper = $source.Substring($begin, $end + $lastLine.Length - $begin)
$constants = [regex]::Matches($source, '(?m)^private const val (?:SUBTITLE_PAYLOAD_\w+|SUBTITLE_RECT_HEADER_BYTES|MAX_SUBTITLE_\w+|MICROS_PER_MILLISECOND|DEFAULT_BITMAP_SUBTITLE_DURATION_US) = [^\r\n]+')
if ($constants.Count -ne 9) { throw 'Production subtitle constants changed.' }
$prefix = @'
package com.yfuse.core2.android
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitlePayload
import java.nio.ByteBuffer
import java.nio.ByteOrder
// Data-only scaffolding for the mapper's three sample fields; no playback/decoder logic.
data class YTrackId(val value: Int)
data class YCompressedSample(val trackId: YTrackId, val presentationTimeUs: Long, val durationUs: Long? = null)
'@
$generated = $prefix + "`n" + $mapper + "`n" + (($constants | ForEach-Object { $_.Value }) -join "`n") + "`n"
$generatedPath = Join-Path $outputDirectory 'ProductionBitmapMapper.kt'
[System.IO.File]::WriteAllText($generatedPath, $generated, [System.Text.UTF8Encoding]::new($false))

function Resolve-CachedJar([string]$relativePath, [string]$name) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache $relativePath) -Recurse -File -Filter $name)
    if ($matches.Count -ne 1) { throw "Expected one cached dependency: $relativePath/$name" }
    return $matches[0].FullName
}
$compiler = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.0' 'kotlin-compiler-embeddable-2.2.0.jar'
$stdlib = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.0' 'kotlin-stdlib-2.2.0.jar'
$reflect = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-reflect\2.0.21' 'kotlin-reflect-2.0.21.jar'
$scriptRuntime = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\2.2.0' 'kotlin-script-runtime-2.2.0.jar'
$coroutines = Resolve-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.8.0' 'kotlinx-coroutines-core-jvm-1.8.0.jar'
$annotations = Resolve-CachedJar 'org.jetbrains\annotations\13.0' 'annotations-13.0.jar'
$compilerClassPath = @($compiler, $stdlib, $reflect, $scriptRuntime, $coroutines, $annotations) -join [System.IO.Path]::PathSeparator
$sourceClassPath = @($stdlib, $annotations) -join [System.IO.Path]::PathSeparator
$classesDirectory = Join-Path $outputDirectory 'classes'
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
& java '-cp' $compilerClassPath 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' $sourceClassPath '-d' $classesDirectory $subtitlePath $generatedPath (Join-Path $outputDirectory 'Repro.kt')
if ($LASTEXITCODE -ne 0) { throw 'Reproduction compilation failed.' }
$runtimeClassPath = @($classesDirectory, $stdlib) -join [System.IO.Path]::PathSeparator
$result = & java '-cp' $runtimeClassPath 'com.yfuse.core2.android.ReproKt'
if ($LASTEXITCODE -ne 0) { throw 'Reproduction assertions failed.' }
$result | Set-Content -LiteralPath (Join-Path $outputDirectory 'result.json') -Encoding utf8
@{
    method = 'Verbatim production mapper extraction and complete production YSubtitle.kt; synthetic decoded display sets; no Android or native runtime.'
    sources = @($mapperPath, $subtitlePath) | ForEach-Object { @{path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} }
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputDirectory 'source-hashes.json') -Encoding utf8
Write-Output $result
