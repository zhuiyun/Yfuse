$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
& .\gradlew.bat :tvApp:resolveAndroid17Locks :tvShared:resolveAndroid17Locks --update-locks 'io.ktor:*' -I audit/android17-20260920/resolve-tv-locks.init.gradle `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' '-Pkotlin.incremental=false' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --offline --console=plain --no-daemon --max-workers=1 > (Join-Path $PSScriptRoot 'resolve-tv-locks.log') 2>&1
$result = $LASTEXITCODE
Get-Content (Join-Path $PSScriptRoot 'resolve-tv-locks.log') -Tail 40
exit $result

