param([Parameter(ValueFromRemainingArguments = $true)][string[]]$GradleArguments)
$ErrorActionPreference = 'Stop'
$env:GRADLE_USER_HOME = 'C:\Users\app-inkbird\.gradle'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
# This Windows host rejects AF_UNIX connect in Java's selector wakeup pipe. Pointing the
# socket temporary directory at a file makes JDK PipeImpl fall back to local TCP. This
# affects this process tree only; no firewall, global JDK, or project settings change.
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
& 'C:\Users\app-inkbird\.gradle\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat' @GradleArguments --offline --console=plain --no-daemon
exit $LASTEXITCODE
