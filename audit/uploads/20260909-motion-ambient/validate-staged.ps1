$ErrorActionPreference = 'Stop'
$env:GRADLE_USER_HOME = 'D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE = 'C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:ANDROID_HOME = 'D:\AndroidSDK'
$env:ANDROID_SDK_ROOT = 'D:\AndroidSDK'
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$gradle = 'D:\Demo\Yfuse\.gradle-tmp\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat'
$validationArguments = @(
    ':composeApp:verifyDesignSystemUsage', ':composeApp:testReleaseUnitTest',
    '--tests', 'com.yfuse.feature.search.SearchResultsHandoffTest',
    '--tests', 'com.yfuse.app.RootTabMotionTest',
    '--tests', 'com.yfuse.core.designsystem.LoadingMotionTest',
    '--tests', 'com.yfuse.core.designsystem.ThemeCrossfadeTest',
    '--tests', 'com.yfuse.core.data.ThemePreferencesTest',
    '--tests', 'com.yfuse.core.designsystem.AmbientLightTest',
    '--tests', 'com.yfuse.core.data.PlaybackPreferencesTest',
    '--tests', 'com.yfuse.feature.player.AmbientCopyTest',
    '--tests', 'com.yfuse.feature.player.Core2SurfaceTest',
    '-I', (Join-Path $PSScriptRoot 'frozen-mobile-sources.init.gradle'),
    '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9',
    '-PyfuseVersionName=1.0.49', '-PyfuseVersionCode=211',
    '-PyfuseNativeOnlyRuntime=false', '-PyfuseIncludeMdk=true',
    '-Dorg.gradle.jvmargs=-Xmx6144m -Dfile.encoding=UTF-8',
    '--offline', '--console=plain', '--no-daemon', '--max-workers=1'
)
& $gradle @validationArguments 2>&1 | Tee-Object -FilePath (Join-Path $PSScriptRoot 'validation.log')
if ($LASTEXITCODE -ne 0) { throw 'Staged source validation failed.' }
