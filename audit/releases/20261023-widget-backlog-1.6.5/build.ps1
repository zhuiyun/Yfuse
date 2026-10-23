$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath 'D:\Demo\Yfuse'
$env:JAVA_HOME = 'C:\Users\app-inkbird\.jdks\corretto-17.0.15'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:JAVA_TOOL_OPTIONS = '-Djava.io.tmpdir=D:/Demo/Yfuse/.tmp -Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/.tmp -Dfile.encoding=UTF-8'
$outputFolder = 'D:\Demo\Yfuse\artifacts-local\releases\widget-backlog-1.6.5-317'
$gradleArguments = @(
    ':composeApp:assembleRelease', '--offline', '--no-daemon', '--max-workers=1', '--no-parallel', '--console=plain',
    '-Pkotlin.compiler.execution.strategy=in-process', '-Pkotlin.incremental=false',
    '-Dorg.gradle.jvmargs=-Xmx4096M -Dfile.encoding=UTF-8',
    '-PyfuseIncludeMdk=true', '-PyfuseNativeOnlyRuntime=false',
    '-PconfirmMdkDistributionRights=true', '-PyfusePackageOnly=true'
)
$startedAt = (Get-Date).ToUniversalTime()
[ordered]@{
    versionName = '1.6.5'
    versionCode = 317
    profile = 'full'
    yfuseIncludeMdk = $true
    yfuseNativeOnlyRuntime = $false
    command = @('gradlew.bat') + $gradleArguments
    startedAtUtc = $startedAt.ToString('o')
    sourceInputsSha256 = (Get-FileHash -LiteralPath (Join-Path $outputFolder 'source-inputs.json') -Algorithm SHA256).Hash.ToLowerInvariant()
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $outputFolder 'build-start.json') -Encoding utf8
& .\gradlew.bat @gradleArguments *> (Join-Path $outputFolder 'build-full.log')
$buildExit = $LASTEXITCODE
[ordered]@{
    exitCode = $buildExit
    completedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
    elapsedSeconds = [math]::Round(((Get-Date).ToUniversalTime() - $startedAt).TotalSeconds, 2)
} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $outputFolder 'build-result.json') -Encoding utf8
Get-Content -LiteralPath (Join-Path $outputFolder 'build-full.log') -Tail 45
exit $buildExit
