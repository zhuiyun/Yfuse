$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$verificationTasks = @(
    ':watchTogetherServer:test', ':watchTogetherProtocol:jvmTest',
    ':composeApp:testDebugUnitTest', ':tvApp:testDebugUnitTest',
    ':watchTogetherServer:ktlintCheck', ':watchTogetherProtocol:ktlintCheck',
    ':composeApp:ktlintCheck', ':tvApp:ktlintCheck', ':mdkAndroid:ktlintCheck'
)
& '.\.gradle-tmp\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' `
    @verificationTasks `
    -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle `
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' `
    '-Pkotlin.compiler.execution.strategy=in-process' `
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' `
    --console=plain --no-daemon --max-workers=1 --continue `
    *> 'audit/releases/20260915-version-correction/verification.log'
$verificationExit = $LASTEXITCODE
Get-Content 'audit/releases/20260915-version-correction/verification.log' -Tail 85
exit $verificationExit

