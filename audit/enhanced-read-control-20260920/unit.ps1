$ErrorActionPreference = 'Stop'
$taskRoot = 'D:/Demo/Yfuse'
$cacheRoot = Join-Path $taskRoot '.gradle-tmp/caches/modules-2/files-2.1'
function Find-TaskJar([string] $module) {
    $found = @(Get-ChildItem -LiteralPath (Join-Path $cacheRoot $module) -Recurse -Filter '*.jar' | Where-Object { $_.Name -notmatch '(sources|javadoc)\.jar$' })
    if ($found.Count -ne 1) { throw "Expected one jar for $module" }
    return $found[0].FullName
}
$java = 'C:/Users/app-inkbird/.jdks/ms-21.0.9/bin/java.exe'
$compiler = @(
    'org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.20',
    'org.jetbrains.kotlin/kotlin-build-tools-api/2.4.20',
    'org.jetbrains.kotlin/kotlin-stdlib/2.4.20',
    'org.jetbrains.kotlin/kotlin-script-runtime/2.4.20',
    'org.jetbrains.kotlin/kotlin-reflect/1.6.10',
    'org.jetbrains.kotlin/kotlin-daemon-embeddable/2.4.20',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0',
    'org.jetbrains/annotations/13.0'
) | ForEach-Object { Find-TaskJar $_ }
$runtime = @(
    'org.jetbrains.kotlin/kotlin-stdlib/2.4.20',
    'org.jetbrains.kotlin/kotlin-test/2.4.20',
    'org.jetbrains.kotlin/kotlin-test-junit/2.4.20',
    'org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.11.0',
    'junit/junit/4.13.2',
    'org.hamcrest/hamcrest-core/1.3'
) | ForEach-Object { Find-TaskJar $_ }
$runtime += 'D:/AndroidSDK/platforms/android-36/android.jar'
$mainClasses = 'D:/Demo/Yfuse/phoneShared/build/classes/kotlin/android/main'
$runtime += $mainClasses
$sources = @(
    'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidDemuxReadControl.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNode.kt',
    'composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidPlaybackMemoryBudget.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidDemuxReadControlTest.kt',
    'composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNodeTest.kt'
)
$output = Join-Path $taskRoot 'audit/enhanced-read-control-20260920/unit-classes'
New-Item -ItemType Directory -Path $output -Force | Out-Null
& $java -cp ($compiler -join ';') org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "-Xfriend-paths=$mainClasses" -classpath ($runtime -join ';') -d $output @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java -cp ((@($output) + $runtime) -join ';') org.junit.runner.JUnitCore com.yfuse.core2.android.AndroidDemuxReadControlTest com.yfuse.core2.android.AndroidDemuxReadAheadNodeTest
exit $LASTEXITCODE