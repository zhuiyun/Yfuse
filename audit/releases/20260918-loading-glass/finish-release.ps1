$ErrorActionPreference = 'Stop'
Set-Location 'D:/Demo/Yfuse'
$releasePython = 'C:/Users/app-inkbird/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$destination = 'artifacts/releases/loading-glass-1.0.71-233'
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Source inputs changed; do not deliver.' }
$suites = @(Get-ChildItem phoneShared/build/test-results/testAndroidHostTest -Filter '*.xml' | ForEach-Object {
    [xml]$report = Get-Content -LiteralPath $_.FullName -Raw
    [PSCustomObject]@{name=$report.testsuite.name; tests=[int]$report.testsuite.tests; failures=[int]$report.testsuite.failures; errors=[int]$report.testsuite.errors; skipped=[int]$report.testsuite.skipped}
})
$total = ($suites | Measure-Object -Property tests -Sum).Sum
$failed = ($suites | Measure-Object -Property failures -Sum).Sum + ($suites | Measure-Object -Property errors -Sum).Sum
if ($total -ne 25 -or $failed -ne 0) { throw "Unexpected release tests: $total tests, $failed failures/errors" }
$testSummary = [ordered]@{jvm=[ordered]@{tests=$total; failures=0; errors=0; suites=$suites}; tvMainCompilation='passed'; scopedFormatting='passed'; tvSearchTests='blocked: existing Ktor 3.0.3 lock conflicts with 3.1.0 dependencies'; deviceUiTested=$false; playbackTested=$false}
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'test-summary.json'), ($testSummary | ConvertTo-Json -Depth 8))
Copy-Item composeApp/build/outputs/apk/release/composeApp-release.apk "$destination/Yfuse-1.0.71-233-full-arm64-signed.apk"
Copy-Item version.properties, release-notes.txt $destination
& $releasePython (Join-Path $PSScriptRoot 'verify-release.py')
if ($LASTEXITCODE -ne 0) { throw 'Final APK verification failed; do not deliver.' }
Copy-Item (Join-Path $PSScriptRoot 'source-hashes.json'), (Join-Path $PSScriptRoot 'test-summary.json') $destination
$verified = Get-Content -Raw "$destination/verification.json" | ConvertFrom-Json
$readme = @"
# Yfuse 1.0.71（233）正式签名包

- APK：Yfuse-1.0.71-233-full-arm64-signed.apk
- 包名：com.yfuse；Full ARM64，沿用既有 YCore、MPV、MDK 运行库和生产签名配置。
- 大小：$($verified.bytes) 字节。
- SHA-256：$($verified.sha256)
- 生产证书 SHA-256：$($verified.certificateSha256)，与实际上一交付 1.0.70（232）一致。

新增九款加载动画的设置切换，以及独立玻璃材质页面：三条滑杆、真实玻璃预览、浅深色分别保存、恢复默认和浅色改动前效果。手机和 TV 均提供入口。

验证：25 项相关 JVM 测试通过；手机/TV 主源码编译通过；本次改动的格式检查和设计系统检查通过。额外 TV 设置搜索测试被既有 Ktor 测试依赖锁冲突阻塞，未改动 TV 锁文件。

Release 构建门禁、实际版本和包名、正式签名、包内网络安全配置、ARM64 原生库、ZIP 完整性及 ZIP/ELF 16 KB 对齐验证通过。构建前后 $($verified.sourceFileCount) 份源码和构建输入哈希一致，原生库与上一交付包一致。

未安装 APK，未执行本次正式包的真机 UI 或媒体播放验证。

应用内更新清单校验公钥沿用原配置，仍未配置；此包通过 APK 手动安装交付。代码推送使用仓库既有 [artifact only] 标记，不触发应用发布。
"@
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'README.md'), $readme)
[IO.File]::WriteAllText("D:/Demo/Yfuse/$destination/README.md", $readme)
Write-Output (Resolve-Path "$destination/$($verified.apk)").Path