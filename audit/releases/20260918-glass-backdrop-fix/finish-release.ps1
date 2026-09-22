$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$releasePython = 'C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$destination = 'D:/Demo/Yfuse/artifacts/releases/glass-backdrop-fix-1.0.73-235'
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Source inputs changed; do not deliver.' }
$suites = @(Get-ChildItem phoneShared/build/test-results/testAndroidHostTest -Filter '*.xml' | ForEach-Object {
    [xml]$report = Get-Content -LiteralPath $_.FullName -Raw
    [PSCustomObject]@{name=$report.testsuite.name; tests=[int]$report.testsuite.tests; failures=[int]$report.testsuite.failures; errors=[int]$report.testsuite.errors; skipped=[int]$report.testsuite.skipped}
})
$total = ($suites | Measure-Object -Property tests -Sum).Sum
$failed = ($suites | Measure-Object -Property failures -Sum).Sum + ($suites | Measure-Object -Property errors -Sum).Sum
$skipped = ($suites | Measure-Object -Property skipped -Sum).Sum
if ($total -ne 30 -or $failed -ne 0 -or $skipped -ne 0 -or $suites.Count -ne 5) { throw 'Unexpected release test results.' }
foreach ($suite in @('com.yfuse.core.data.ThemePreferencesTest','com.yfuse.core.designsystem.GlassMaterialTest','com.yfuse.core.designsystem.GlassTest','com.yfuse.core.designsystem.BackdropFramesTest','com.yfuse.core.designsystem.OverlayVisibilityTest')) {
    if ($suite -notin $suites.name) { throw "Required test suite missing: $suite" }
}
$uiBuild = Get-Content -Raw audit/glass-backdrop-fix-20260918/verify.log
if ($uiBuild -notmatch 'BUILD SUCCESSFUL' -or $uiBuild -notmatch ':tvShared:compileAndroidMain') { throw 'TV validation missing.' }
$testSummary = [ordered]@{
    jvm=[ordered]@{tests=$total; failures=0; errors=0; skipped=0; suites=$suites}
    phoneAndTvMainCompilation='passed; audit/glass-backdrop-fix-20260918/verify.log'
    scopedFormatting='passed; 5 changed Kotlin files'
    deviceUiTested=$false
    playbackTested=$false
}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'test-summary.json'), ($testSummary | ConvertTo-Json -Depth 8))
Copy-Item -LiteralPath composeApp/build/outputs/apk/release/composeApp-release.apk -Destination "$destination/Yfuse-1.0.73-235-full-arm64-signed.apk"
Copy-Item -LiteralPath version.properties,release-notes.txt -Destination $destination
& $releasePython (Join-Path $PSScriptRoot 'verify-release.py')
if ($LASTEXITCODE -ne 0) { throw 'Final APK verification failed; do not deliver.' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'source-hashes.json'),(Join-Path $PSScriptRoot 'test-summary.json') -Destination $destination
$verified = Get-Content -Raw "$destination/verification.json" | ConvertFrom-Json
$readme = @"
# Yfuse 1.0.73（235）正式签名包

- APK：Yfuse-1.0.73-235-full-arm64-signed.apk
- 包名：com.yfuse；Full ARM64，沿用上一交付包的运行库与正式签名。
- 大小：$($verified.bytes) 字节。
- SHA-256：$($verified.sha256)
- 生产证书 SHA-256：$($verified.certificateSha256)，与上一交付 1.0.72（234）一致。

本次修复多款玻璃预设下背景文字清晰透出的问题：统一弹窗状态与背景采样控制，并补齐首帧到达后的绘制刷新。后续背景更新保持同步，录制源不订阅自身，避免持续重绘。

30 项相关测试全部通过，包含首帧、后续帧、源录制不自订阅与多个弹窗的生命周期。手机与 TV 主源码、格式与设计系统检查通过。

实际包名与版本、正式签名、Release 构建门禁、ARM64 运行库、包内网络策略、ZIP 完整性和 ZIP/ELF 16 KB 对齐均已验证。构建前后 $($verified.sourceFileCount) 份源码及构建输入一致，原生库与上一交付包一致。

未安装，未进行本次正式包的真机 UI 或播放验证。仅本地签名打包，未推送或发布。
"@
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'README.md'), $readme)
[IO.File]::WriteAllText("$destination/README.md", $readme)
Write-Output "$destination/$($verified.apk)"
