param(
    [string]$LogName = 'unit-online.log',
    [string[]]$Tasks = @(':phoneShared:testAndroidHostTest', '--tests', 'com.yfuse.feature.player.SeekFeedbackTest', '--tests', 'com.yfuse.feature.player.PlayerMotionPhysicsTest', '--tests', 'com.yfuse.core.designsystem.MotionAccessibilityPolicyTest', ':composeApp:verifyDesignSystemUsage'),
    [string[]]$Extra = @('--update-locks', 'org.jetbrains.kotlinx:kotlinx-serialization*,io.ktor:*')
)
$env:GRADLE_USER_HOME='D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE='C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME='C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
& .\gradlew.bat @Tasks @Extra -I audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8' --console=plain --no-daemon --max-workers=1 > "audit/soft-feedback-20260917/$LogName" 2>&1
$result = $LASTEXITCODE
Get-Content "audit/soft-feedback-20260917/$LogName" -Tail 55
exit $result
