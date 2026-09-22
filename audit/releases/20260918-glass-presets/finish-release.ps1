$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$releasePython = 'C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$destination = 'D:/Demo/Yfuse/artifacts/releases/glass-presets-1.0.72-234'
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Source inputs changed; do not deliver.' }
$suites = @(Get-ChildItem phoneShared/build/test-results/testAndroidHostTest -Filter '*.xml' | ForEach-Object {
    [xml]$report = Get-Content -LiteralPath $_.FullName -Raw
    [PSCustomObject]@{name=$report.testsuite.name; tests=[int]$report.testsuite.tests; failures=[int]$report.testsuite.failures; errors=[int]$report.testsuite.errors; skipped=[int]$report.testsuite.skipped}
})
$total = ($suites | Measure-Object -Property tests -Sum).Sum
$failed = ($suites | Measure-Object -Property failures -Sum).Sum + ($suites | Measure-Object -Property errors -Sum).Sum
$skipped = ($suites | Measure-Object -Property skipped -Sum).Sum
if ($total -ne 26 -or $failed -ne 0 -or $skipped -ne 0 -or $suites.Count -ne 3) { throw 'Unexpected release test results.' }
foreach ($suite in @('com.yfuse.core.data.ThemePreferencesTest','com.yfuse.core.designsystem.GlassMaterialTest','com.yfuse.core.designsystem.GlassTest')) {
    if ($suite -notin $suites.name) { throw "Required test suite missing: $suite" }
}
$uiBuild = Get-Content -Raw audit/glass-adjustments-20260918/verify-final.log
if ($uiBuild -notmatch 'BUILD SUCCESSFUL' -or $uiBuild -notmatch ':tvShared:compileAndroidMain') { throw 'TV validation missing.' }
$testSummary = [ordered]@{
    jvm=[ordered]@{tests=$total; failures=0; errors=0; skipped=0; suites=$suites}
    phoneAndTvMainCompilation='passed; audit/glass-adjustments-20260918/verify-final.log'
    scopedFormatting='passed; 13 Kotlin files'
    deviceUiTested=$false
    playbackTested=$false
}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'test-summary.json'), ($testSummary | ConvertTo-Json -Depth 8))
Copy-Item composeApp/build/outputs/apk/release/composeApp-release.apk "$destination/Yfuse-1.0.72-234-full-arm64-signed.apk"
Copy-Item version.properties,release-notes.txt $destination
& $releasePython (Join-Path $PSScriptRoot 'verify-release.py')
if ($LASTEXITCODE -ne 0) { throw 'Final APK verification failed; do not deliver.' }
Copy-Item (Join-Path $PSScriptRoot 'source-hashes.json'),(Join-Path $PSScriptRoot 'test-summary.json') $destination
$verified = Get-Content -Raw "$destination/verification.json" | ConvertFrom-Json
$readme = @"
# Yfuse 1.0.72（234）正式签名包

- APK：Yfuse-1.0.72-234-full-arm64-signed.apk
- 包名：com.yfuse；Full ARM64，沿用上一交付包的运行库与正式签名。
- 大小：$($verified.bytes) 字节。
- SHA-256：$($verified.sha256)
- 生产证书 SHA-256：$($verified.certificateSha256)，与上一交付 1.0.71（233）一致。

本次新增 01–06、S1–S3 共九款玻璃材质预设。默认仅显示雾化、通透度、亮边三项常用调节；颜色、折射、彩光、珠光、纵纹等完整参数放在折叠的高级区。浅深色独立保存，兼容旧设置，实时预览与实际弹窗共用渲染。

26 项相关测试全部通过，包含从经典材质通过调节还原全部九款配方、混合效果、保存与迁移。手机与 TV 主源码、格式与设计系统检查通过。

实际包名与版本、正式签名、Release 构建门禁、ARM64 运行库、包内网络策略、ZIP 完整性和 ZIP/ELF 16 KB 对齐均已验证。构建前后 $($verified.sourceFileCount) 份源码及构建输入一致，原生库与上一交付包一致。

未安装，未进行本次正式包的真机 UI 或播放验证。仅本地签名打包，未推送或发布。
"@
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'README.md'), $readme)
[IO.File]::WriteAllText("$destination/README.md", $readme)
Write-Output "$destination/$($verified.apk)"
