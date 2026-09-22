param(
    [string]$BuildLog = 'audit/merge-all-20260916/agp9-tests.log',
    [string[]]$Tasks = @(':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin', ':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest', ':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test'),
    [switch]$Offline,
    [switch]$KeepLocks
)
$env:GRADLE_USER_HOME='D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE='C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME='C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$taskOptions = @()
if ($Offline) { $taskOptions += '--offline' }
if (-not $KeepLocks) { $taskOptions += '--write-locks' }
& .\gradlew.bat @Tasks -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' @taskOptions --console=plain --no-daemon --max-workers=1 > $BuildLog 2>&1
exit $LASTEXITCODE
