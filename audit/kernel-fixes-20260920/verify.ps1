param([ValidateSet('compile', 'tests', 'lint', 'native', 'artifacts', 'all')][string]$Mode = 'tests')
$ErrorActionPreference = 'Stop'
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '../..'))
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
[string[]]$tasks = @(switch ($Mode) {
    compile { @(':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin', ':composeApp:compileDebugAndroidTestKotlin') }
    tests { @(':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest') }
    lint { @(':composeApp:lintDebug', ':tvApp:lintDebug', ':composeApp:verifyDesignSystemUsage') }
    native { @(':mdkAndroid:externalNativeBuildDebug') }
    artifacts { @(':composeApp:verifyStandaloneYCoreArtifact', ':composeApp:verifyYCoreGpuCompanionArtifact') }
    all { @(':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest', ':composeApp:compileDebugKotlin', ':tvApp:compileDebugKotlin', ':composeApp:compileDebugAndroidTestKotlin', ':mdkAndroid:externalNativeBuildDebug', ':composeApp:lintDebug', ':tvApp:lintDebug', ':composeApp:verifyDesignSystemUsage', '--continue') }
})
$sourceBefore = @{}
$inputPaths = @(rg --files composeApp/src tvApp/src phoneShared tvShared mdkAndroid/src scripts/native -g '*.kt' -g '*.kts' -g '*.java' -g '*.cpp' -g '*.h' -g '*.xml' -g '!**/build/**') + @('gradle/libs.versions.toml', 'scripts/security-overrides.properties', 'version.properties', 'release-notes.txt', 'settings.gradle.kts', 'build.gradle.kts', 'composeApp/build.gradle.kts', 'tvApp/build.gradle.kts', 'mdkAndroid/build.gradle.kts')
foreach ($sourcePath in $inputPaths) { $sourceBefore[$sourcePath] = (Get-FileHash -LiteralPath $sourcePath).Hash }
$sourceBefore | ConvertTo-Json | Set-Content (Join-Path $PSScriptRoot "$Mode-source-before.json")
& .\gradlew.bat @tasks `
    -I audit/kernel-fixes-20260920/isolated-build.init.gradle `
    -I audit/playback-optimization-20260920/local-maven.init.gradle `
    -I audit/playback-followup-fix-20260920/lint-dependencies.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --project-cache-dir .gradle-tmp/kernel-fixes-project-cache `
    --offline --console=plain --no-daemon --max-workers=1 --stacktrace > (Join-Path $PSScriptRoot "$Mode.log") 2>&1
$result = $LASTEXITCODE
$sourceAfter = @{}
$afterPaths = @(rg --files composeApp/src tvApp/src phoneShared tvShared mdkAndroid/src scripts/native -g '*.kt' -g '*.kts' -g '*.java' -g '*.cpp' -g '*.h' -g '*.xml' -g '!**/build/**') + @('gradle/libs.versions.toml', 'scripts/security-overrides.properties', 'version.properties', 'release-notes.txt', 'settings.gradle.kts', 'build.gradle.kts', 'composeApp/build.gradle.kts', 'tvApp/build.gradle.kts', 'mdkAndroid/build.gradle.kts')
foreach ($sourcePath in $afterPaths) { $sourceAfter[$sourcePath] = (Get-FileHash -LiteralPath $sourcePath).Hash }
$changedDuringRun = @((@($sourceBefore.Keys) + @($sourceAfter.Keys) | Sort-Object -Unique) | Where-Object { $sourceBefore[$_] -ne $sourceAfter[$_] })
ConvertTo-Json -InputObject $changedDuringRun | Set-Content (Join-Path $PSScriptRoot "$Mode-concurrent-changes.json")
Get-Content (Join-Path $PSScriptRoot "$Mode.log") -Tail 65
if ($changedDuringRun.Count -gt 0) { Write-Output "Source changed during verification: $($changedDuringRun.Count) files."; if ($result -eq 0) { $result = 2 } }
exit $result
