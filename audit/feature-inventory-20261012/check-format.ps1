$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$env:JAVA_HOME='C:/Users/app-inkbird/.jdks/ms-21.0.9'
$env:GRADLE_USER_HOME='D:/Demo/Yfuse/.gradle-tmp'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
& ./gradlew.bat :phoneShared:ktlintCommonMainSourceSetCheck :phoneShared:ktlintCommonTestSourceSetCheck --no-daemon --console=plain 2>&1 | Tee-Object -FilePath audit/feature-inventory-20261012/ktlint.log
exit $LASTEXITCODE