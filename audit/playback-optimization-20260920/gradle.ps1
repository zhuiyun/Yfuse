param([ValidateSet('locks', 'compile', 'test', 'lint', 'reports')][string]$Mode = 'compile')
$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$tasks = switch ($Mode) {
    locks { @(':composeApp:resolvePlaybackLocks', ':phoneShared:resolvePlaybackLocks', ':tvApp:resolvePlaybackLocks', ':tvShared:resolvePlaybackLocks', ':macrobenchmark:resolvePlaybackLocks', '-I', 'audit/playback-optimization-20260920/resolve-locks.init.gradle', '--update-locks', 'androidx.media3:media3-datasource-okhttp') }
    compile { @(':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin') }
    test { @(':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest', ':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin') }
    lint { @(':composeApp:lintDebug', ':tvApp:lintDebug', ':composeApp:verifyDesignSystemUsage') }
    reports { @(':phoneShared:playbackVerificationReport', ':tvShared:playbackVerificationReport') }
}
$sourceBefore = @{}
$inputPaths = @(rg --files composeApp/src tvApp/src phoneShared tvShared -g '*.kt' -g '*.kts' -g '*.xml' -g '!**/build/**') + @('gradle/libs.versions.toml','scripts/security-overrides.properties','scripts/native/ycore_demux_jni.cpp')
foreach ($sourcePath in $inputPaths) { $sourceBefore[$sourcePath] = (Get-FileHash -LiteralPath $sourcePath).Hash }
$sourceBefore | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot "$Mode-source-before.json")
& .\gradlew.bat @tasks `
    -I audit/playback-optimization-20260920/local-maven.init.gradle `
    -I audit/playback-optimization-20260920/isolated-results.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --offline --console=plain --no-daemon --max-workers=1 > (Join-Path $PSScriptRoot "$Mode.log") 2>&1
$result = $LASTEXITCODE
$changedDuringRun = @($inputPaths | Where-Object { (Get-FileHash -LiteralPath $_).Hash -ne $sourceBefore[$_] })
ConvertTo-Json -InputObject $changedDuringRun | Set-Content (Join-Path $PSScriptRoot "$Mode-concurrent-changes.json")
Get-Content (Join-Path $PSScriptRoot "$Mode.log") -Tail 50
exit $result
