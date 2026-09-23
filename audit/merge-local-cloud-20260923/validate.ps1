param(
    [string]$LogName = 'validation.log',
    [string[]]$Tasks = @(':phoneShared:testAndroidHostTest', ':tvShared:testAndroidHostTest', ':watchTogetherProtocol:jvmTest', ':watchTogetherServer:test'),
    [switch]$RefreshLocks
)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:/Users/app-inkbird/.jdks/ms-21.0.9'
$env:GRADLE_USER_HOME = 'D:/Demo/Yfuse/.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:/Users/app-inkbird/.gradle/caches'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$options = @()
if ($env:HTTPS_PROXY) {
    $validationProxy = [Uri]$env:HTTPS_PROXY
    $options += @("-Dhttps.proxyHost=$($validationProxy.Host)", "-Dhttps.proxyPort=$($validationProxy.Port)",
        "-Dhttp.proxyHost=$($validationProxy.Host)", "-Dhttp.proxyPort=$($validationProxy.Port)")
}
if ($RefreshLocks) { $options += '--write-locks' }
$log = Join-Path $PSScriptRoot $LogName
& 'C:/Users/app-inkbird/.gradle/wrapper/dists/gradle-9.7.1-bin/1w1c7tv4s851m17nbqdsro2tv/gradle-9.7.1/bin/gradle.bat' @Tasks @options `
    -I audit/merge-local-cloud-20260923/resolve-merge-locks.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --console=plain --no-daemon --max-workers=1 *> $log
$result = $LASTEXITCODE
Get-Content $log -Tail 65
exit $result
