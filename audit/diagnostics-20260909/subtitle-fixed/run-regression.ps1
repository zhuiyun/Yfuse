param(
    [string]$RepositoryRoot = 'D:\Demo\Yfuse',
    [string]$GradleCache = 'C:\Users\app-inkbird\.gradle\caches\modules-2\files-2.1'
)
$ErrorActionPreference = 'Stop'
$outputDirectory = $PSScriptRoot
function Write-Utf8([string]$name, [string]$value) {
    [System.IO.File]::WriteAllText((Join-Path $outputDirectory $name), $value, [System.Text.UTF8Encoding]::new($false))
}
function Resolve-CachedJar([string]$relativePath, [string]$name) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache $relativePath) -Recurse -File -Filter $name)
    if ($matches.Count -ne 1) { throw "Expected one cached dependency: $relativePath/$name" }
    return $matches[0].FullName
}
$mapperPath = Join-Path $RepositoryRoot 'composeApp\src\androidMain\kotlin\com\yfuse\core2\android\AndroidFfmpegDemuxer.kt'
$source = [System.IO.File]::ReadAllText($mapperPath)
$begin = $source.IndexOf('internal fun ByteArray.toBitmapSubtitleCues(')
$lastLine = 'private fun ByteBuffer.unsignedIntToLong(): Long = int.toLong() and 0xffff_ffffL'
$end = $source.IndexOf($lastLine, $begin)
if ($begin -lt 0 -or $end -lt 0) { throw 'Production mapper source boundaries changed.' }
$mapper = $source.Substring($begin, $end + $lastLine.Length - $begin)
$constants = [regex]::Matches($source, '(?m)^private const val (?:SUBTITLE_PAYLOAD_\w+|SUBTITLE_RECT_HEADER_BYTES|MAX_SUBTITLE_\w+|MICROS_PER_MILLISECOND|DEFAULT_BITMAP_SUBTITLE_DURATION_US) = [^\r\n]+')
$imports = @'
package com.yfuse.core2.android
import com.yfuse.core2.subtitle.*
import com.yfuse.core2.demux.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
'@
Write-Utf8 'ProductionBitmapMapper.kt' ($imports + "`n" + $mapper + "`n" + (($constants | ForEach-Object { $_.Value }) -join "`n"))
Write-Utf8 'Scaffold.kt' @'
package com.yfuse.core2.demux
// Data-only scaffolding; the production mapper and buffer below are unmodified.
data class YTrackId(val value: Int)
data class YCompressedSample(val trackId: YTrackId, val data: ByteArray, val presentationTimeUs: Long, val durationUs: Long? = null)
'@
$testRoot = Join-Path $RepositoryRoot 'composeApp\src\androidUnitTest\kotlin\com\yfuse\core2\android'
$tests = [System.IO.File]::ReadAllText((Join-Path $testRoot 'AndroidFfmpegDemuxerMappingTest.kt'))
$first = $tests.IndexOf('class AndroidFfmpegDemuxerMappingTest {')
$next = $tests.IndexOf('    @Test' + "`n" + '    fun `FFmpeg video codec', $first)
if ($next -lt 0) { $next = $tests.IndexOf('    @Test' + "`r`n" + '    fun `FFmpeg video codec', $first) }
$tail = $tests.IndexOf('    private fun sampleAt(')
$selected = $tests.Substring($first, $next - $first) + $tests.Substring($tail)
$selected = $selected.Replace('@Test', '')
Write-Utf8 'MappingTests.kt' ($imports + "`nimport kotlin.test.*`n" + $selected)
$bufferTestPath = Join-Path $RepositoryRoot 'composeApp\src\commonTest\kotlin\com\yfuse\core2\subtitle\YSubtitleCueBufferTest.kt'
Write-Utf8 'BufferTests.kt' ([System.IO.File]::ReadAllText($bufferTestPath).Replace('import kotlin.test.Test', '').Replace('@Test', ''))
Write-Utf8 'Runner.kt' @'
fun main() {
    var count = 0
    listOf(com.yfuse.core2.subtitle.YSubtitleCueBufferTest(), com.yfuse.core2.android.AndroidFfmpegDemuxerMappingTest()).forEach { instance ->
        instance.javaClass.declaredMethods.filter { it.parameterCount == 0 && it.returnType == Void.TYPE }.forEach { method ->
            method.invoke(instance)
            println("PASS ${method.name}")
            count++
        }
    }
    println("PASS $count production Kotlin subtitle regressions")
}
'@
$compiler = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.0' 'kotlin-compiler-embeddable-2.2.0.jar'
$stdlib = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.0' 'kotlin-stdlib-2.2.0.jar'
$reflect = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-reflect\2.0.21' 'kotlin-reflect-2.0.21.jar'
$scriptRuntime = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\2.2.0' 'kotlin-script-runtime-2.2.0.jar'
$coroutines = Resolve-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.8.0' 'kotlinx-coroutines-core-jvm-1.8.0.jar'
$annotations = Resolve-CachedJar 'org.jetbrains\annotations\13.0' 'annotations-13.0.jar'
$kotlinTest = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-test\2.2.21' 'kotlin-test-2.2.21.jar'
$compilerClassPath = @($compiler, $stdlib, $reflect, $scriptRuntime, $coroutines, $annotations) -join [System.IO.Path]::PathSeparator
$sourceClassPath = @($stdlib, $annotations, $kotlinTest) -join [System.IO.Path]::PathSeparator
$classesDirectory = Join-Path $outputDirectory 'classes'
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$subtitleRoot = Join-Path $RepositoryRoot 'composeApp\src\commonMain\kotlin\com\yfuse\core2\subtitle'
$sources = @((Join-Path $subtitleRoot 'YSubtitle.kt'), (Join-Path $subtitleRoot 'YSubtitleCueBuffer.kt'))
$sources += @('ProductionBitmapMapper.kt', 'Scaffold.kt', 'MappingTests.kt', 'BufferTests.kt', 'Runner.kt') | ForEach-Object { Join-Path $outputDirectory $_ }
& java '-cp' $compilerClassPath 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' $sourceClassPath '-d' $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Subtitle regression compilation failed.' }
$result = & java '-cp' (@($classesDirectory, $stdlib, $kotlinTest) -join [System.IO.Path]::PathSeparator) 'RunnerKt'
if ($LASTEXITCODE -ne 0) { throw 'Subtitle regression assertions failed.' }
$result | Set-Content -LiteralPath (Join-Path $outputDirectory 'result.txt') -Encoding utf8
Write-Output $result
