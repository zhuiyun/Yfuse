param(
    [string[]] $ExtraSources = @(),
    [string[]] $TestClasses = @('com.yfuse.feature.search.SearchResultsHandoffTest'),
    [string] $HandoffSource = ''
)

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $PSScriptRoot ('isolated-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $run | Out-Null
$moduleCache = Join-Path $repo '.gradle-tmp/caches/modules-2/files-2.1'
$transforms = Join-Path $repo '.gradle-tmp/caches/9.5.0/transforms'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (!(Test-Path -LiteralPath $java)) { $java = (Get-Command java).Source }

function CachedJar([string] $group, [string] $artifact, [string] $version) {
    $directory = Join-Path $moduleCache "$group/$artifact/$version"
    $found = @(Get-ChildItem -LiteralPath $directory -Filter "$artifact-$version.jar" -Recurse -File)
    if ($found.Count -ne 1) { throw "Expected one cached $artifact $version jar, found $($found.Count)" }
    return $found[0].FullName
}

$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.2.21'
$compiler = CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.2.21'
$composePlugin = CachedJar 'org.jetbrains.kotlin' 'kotlin-compose-compiler-plugin-embeddable' '2.2.21'
$compilerCp = @(
    $compiler,
    $stdlib,
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.2.21'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.8.0'),
    (CachedJar 'org.jetbrains' 'annotations' '13.0')
)
$testCp = @(
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-test' '2.2.21'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-test-junit' '2.2.21'),
    (CachedJar 'junit' 'junit' '4.13.2'),
    (CachedJar 'org.hamcrest' 'hamcrest-core' '1.3')
)

# Reuse dependency coordinates recorded by a successful project compilation. No Gradle
# configuration or dependency resolution runs. Native-player jars are not needed here.
$optionsFile = Join-Path $repo 'composeApp/build/20260903_3415494955909560431.compiler.options'
$raw = Get-Content -Raw -LiteralPath $optionsFile
$tokens = @([regex]::Matches($raw, '"((?:\\.|[^"\\])*)"') | ForEach-Object {
    $_.Groups[1].Value.Replace('\\', '\')
})
$cpIndex = [Array]::IndexOf($tokens, '-classpath')
if ($cpIndex -lt 0) { throw 'Cached compiler options do not contain a classpath' }
$dependencies = @($tokens[$cpIndex + 1].Split(';') | Where-Object {
    $_ -notmatch '(libmpv|ycore-native|mdkAndroid)'
})
foreach ($file in $dependencies) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing cached dependency: $file" }
}
$appJar = Join-Path $repo 'composeApp/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar'
if (!(Test-Path -LiteralPath $appJar)) { throw "Missing existing app dependency classes: $appJar" }

if ([string]::IsNullOrEmpty($HandoffSource)) {
    $HandoffSource = Join-Path $repo 'composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchResultsHandoff.kt'
}
$sources = @(
    ([IO.Path]::GetFullPath($HandoffSource)),
    (Join-Path $repo 'composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchScreen.kt'),
    (Join-Path $repo 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/PulseSweep.kt'),
    (Join-Path $repo 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/OrbProgress.kt'),
    (Join-Path $repo 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/PageStates.kt'),
    (Join-Path $repo 'composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ArrivalReveal.kt'),
    (Join-Path $repo 'composeApp/src/commonTest/kotlin/com/yfuse/feature/search/SearchResultsHandoffTest.kt')
) + @($ExtraSources | ForEach-Object { [IO.Path]::GetFullPath($_) })
$sourceHashes = @($sources | ForEach-Object { Get-FileHash -LiteralPath $_ -Algorithm SHA256 })
$sourceHashes | Select-Object Path, Hash | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'source-hashes.json') -Encoding utf8
$output = Join-Path $run 'fresh-classes'
$compileCp = (@($appJar) + $testCp + $dependencies) -join ';'
$compilerArgs = @(
    '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-module-name', 'isolated_search_handoff',
    '-classpath', $compileCp,
    "-Xfriend-paths=$appJar", "-Xplugin=$composePlugin",
    '-d', $output
) + $sources

function WriteArguments([string] $path, [string[]] $values) {
    $values | ForEach-Object { '"' + $_.Replace('\', '/').Replace('"', '\"') + '"' } |
        Set-Content -LiteralPath $path -Encoding ascii
}

$compileArgFile = Join-Path $run 'compile.args'
WriteArguments $compileArgFile $compilerArgs
& $java '-Xmx1536m' '-cp' ($compilerCp -join ';') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$compileArgFile" 2>&1 |
    Tee-Object -FilePath (Join-Path $run 'compile.log')
if ($LASTEXITCODE -ne 0) { throw "Fresh Kotlin/Compose compilation failed; see $run" }

# API jars may omit executable method bodies. Substitute corresponding cached runtime
# jars for the JUnit process, and keep freshly compiled classes first in the classpath.
$runtimeJars = @(Get-ChildItem -LiteralPath $transforms -Filter '*-runtime.jar' -Recurse -File)
$runtimeDependencies = @($dependencies | ForEach-Object {
    if ($_ -like '*-api.jar') {
        $name = [IO.Path]::GetFileName($_).Replace('-api.jar', '-runtime.jar')
        $match = @($runtimeJars | Where-Object Name -eq $name)
        if ($match.Count -eq 1) { $match[0].FullName }
        elseif ($match.Count -eq 0) { $_ }
        else { throw "Ambiguous runtime counterpart for ${name}: $($match.Count) jars" }
    } else { $_ }
})
$runtimeCp = (@($output, $appJar) + $testCp + $runtimeDependencies) -join ';'
$runArgFile = Join-Path $run 'run.args'
WriteArguments $runArgFile (@('-cp', $runtimeCp, 'org.junit.runner.JUnitCore') + $TestClasses)
& $java "@$runArgFile" 2>&1 | Tee-Object -FilePath (Join-Path $run 'junit.log')
if ($LASTEXITCODE -ne 0) { throw "JUnit failed; see $run" }
foreach ($entry in $sourceHashes) {
    if ((Get-FileHash -LiteralPath $entry.Path -Algorithm SHA256).Hash -ne $entry.Hash) {
        throw "Source changed during validation: $($entry.Path). Rerun against the final source."
    }
}
Write-Output "Fresh actual-source compilation and JUnit succeeded: $run"
Write-Output 'Scope: source compilation and JVM helper tests; this does not verify Android rendering, packaging, or native playback.'
