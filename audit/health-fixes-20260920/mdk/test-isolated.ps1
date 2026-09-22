$ErrorActionPreference = 'Stop'
$taskRoot = 'D:\Demo\Yfuse'
$resultRoot = Join-Path $taskRoot 'audit/health-fixes-20260920/mdk'
$java = 'C:\Users\app-inkbird\.jdks\ms-21.0.9\bin\java.exe'
function Jar([string]$coordinate) {
    $directory = Join-Path $taskRoot ('.gradle-tmp/caches/modules-2/files-2.1/' + $coordinate)
    $result = Get-ChildItem -LiteralPath $directory -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '(sources|javadoc)\.jar$' } | Select-Object -First 1 -ExpandProperty FullName
    if (-not $result) { throw "Missing cached dependency: $coordinate" }
    return $result
}
$compiler = @(
    Jar 'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20'
    Jar 'org.jetbrains.kotlin/kotlin-build-tools-api/2.4.20'
    Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.4.20'
    Jar 'org.jetbrains.kotlin/kotlin-script-runtime/2.4.20'
    Jar 'org.jetbrains.kotlin/kotlin-reflect/1.6.10'
    Jar 'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.4.20'
    Jar 'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0'
    Jar 'org.jetbrains/annotations/23.0.0'
)
$runtime = @(
    Jar 'org.jetbrains.kotlin/kotlin-stdlib/2.4.20'
    Jar 'org.jetbrains.kotlin/kotlin-test/2.4.20'
    Jar 'org.jetbrains.kotlin/kotlin-test-junit/2.4.20'
    Jar 'junit/junit/4.13.2'
    Jar 'org.hamcrest/hamcrest-core/1.3'
)
$sources = @(
    'composeApp/src/androidMain/kotlin/com/yfuse/feature/player/NativePlaybackStateSupport.kt'
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/MdkLoadStateGateTest.kt'
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/MdkEndStateGateTest.kt'
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/MpvEndFileTrackerTest.kt'
) | ForEach-Object { Join-Path $taskRoot $_ }
$classes = Join-Path $resultRoot 'isolated-classes'
& $java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath ($runtime -join ';') -d $classes @sources > (Join-Path $resultRoot 'compile.log') 2>&1
Get-Content -LiteralPath (Join-Path $resultRoot 'compile.log')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp (($runtime + $classes) -join ';') org.junit.runner.JUnitCore com.yfuse.feature.player.MdkLoadStateGateTest com.yfuse.feature.player.MdkEndStateGateTest com.yfuse.feature.player.MpvEndFileTrackerTest > (Join-Path $resultRoot 'tests.log') 2>&1
Get-Content -LiteralPath (Join-Path $resultRoot 'tests.log')
exit $LASTEXITCODE
