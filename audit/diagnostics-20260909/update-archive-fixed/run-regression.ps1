param([string]$RepositoryRoot = 'D:\Demo\Yfuse', [string]$GradleCache = 'C:\Users\app-inkbird\.gradle\caches\modules-2\files-2.1')
$ErrorActionPreference = 'Stop'
$outputDirectory = $PSScriptRoot
function Resolve-CachedJar([string]$relativePath, [string]$name) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $GradleCache $relativePath) -Recurse -File -Filter $name)
    if ($matches.Count -ne 1) { throw "Expected one cached dependency: $relativePath/$name" }
    return $matches[0].FullName
}
function Write-Utf8([string]$name, [string]$value) {
    [System.IO.File]::WriteAllText((Join-Path $outputDirectory $name), $value, [System.Text.UTF8Encoding]::new($false))
}
$compiler = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-compiler-embeddable\2.2.0' 'kotlin-compiler-embeddable-2.2.0.jar'
$stdlib = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-stdlib\2.2.0' 'kotlin-stdlib-2.2.0.jar'
$reflect = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-reflect\2.0.21' 'kotlin-reflect-2.0.21.jar'
$scriptRuntime = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-script-runtime\2.2.0' 'kotlin-script-runtime-2.2.0.jar'
$coroutines = Resolve-CachedJar 'org.jetbrains.kotlinx\kotlinx-coroutines-core-jvm\1.8.0' 'kotlinx-coroutines-core-jvm-1.8.0.jar'
$annotations = Resolve-CachedJar 'org.jetbrains\annotations\13.0' 'annotations-13.0.jar'
$kotlinTest = Resolve-CachedJar 'org.jetbrains.kotlin\kotlin-test\2.2.21' 'kotlin-test-2.2.21.jar'
$compilerClassPath = @($compiler, $stdlib, $reflect, $scriptRuntime, $coroutines, $annotations) -join [System.IO.Path]::PathSeparator
$sourceClassPath = @($stdlib, $annotations, $kotlinTest, $coroutines) -join [System.IO.Path]::PathSeparator
$classesDirectory = Join-Path $outputDirectory 'classes'
New-Item -ItemType Directory -Path $classesDirectory -Force | Out-Null
$sources = @((Join-Path $RepositoryRoot 'composeApp\src\androidMain\kotlin\com\yfuse\update\UpdatePackageIdentity.kt'))
Write-Utf8 'UpdatePackageIdentityTest.kt' ([IO.File]::ReadAllText((Join-Path $RepositoryRoot 'composeApp\src\androidUnitTest\kotlin\com\yfuse\update\UpdatePackageIdentityTest.kt')).Replace('import kotlin.test.Test', '').Replace('@Test', ''))
$sources += Join-Path $outputDirectory 'UpdatePackageIdentityTest.kt'
Write-Utf8 'Runner.kt' 'fun main() { val instance = com.yfuse.update.UpdatePackageIdentityTest(); var count = 0; instance.javaClass.declaredMethods.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.returnType == Void.TYPE }.forEach { method -> method.invoke(instance); println("PASS ${method.name}"); count++ }; println("PASS $count production Kotlin update archive regressions") }'
$sources += Join-Path $outputDirectory 'Runner.kt'
& java '-cp' $compilerClassPath 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' '-jvm-target' '17' '-classpath' $sourceClassPath '-d' $classesDirectory @sources
if ($LASTEXITCODE -ne 0) { throw 'Update archive regression compilation failed.' }
$result = & java '-cp' (@($classesDirectory, $stdlib, $kotlinTest) -join [IO.Path]::PathSeparator) 'RunnerKt'
if ($LASTEXITCODE -ne 0) { throw 'Update archive regression assertions failed.' }
$result | Set-Content -LiteralPath (Join-Path $outputDirectory 'result.txt') -Encoding utf8
Write-Output $result