$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$releasePython = 'C:\Users\app-inkbird\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$destination = 'artifacts/releases/soft-feedback-1.0.69-231'
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Source inputs changed; do not deliver.' }
Copy-Item composeApp/build/outputs/apk/release/composeApp-release.apk "$destination/Yfuse-1.0.69-231-full-arm64-signed.apk"
Copy-Item version.properties, release-notes.txt $destination
& $releasePython (Join-Path $PSScriptRoot 'verify-release.py')
if ($LASTEXITCODE -ne 0) { throw 'Final APK verification failed; do not deliver.' }
Copy-Item (Join-Path $PSScriptRoot 'source-hashes.json'), (Join-Path $PSScriptRoot 'test-summary.json'), (Join-Path $PSScriptRoot 'changes-from-230.json') $destination
$verified = Get-Content -Raw "$destination/verification.json" | ConvertFrom-Json
$readme = @"
# Yfuse 1.0.69（231）正式签名包

- APK：Yfuse-1.0.69-231-full-arm64-signed.apk
- 包名：com.yfuse；Full ARM64，含 YCore、MPV、MDK。
- 大小：$($verified.bytes) 字节。
- SHA-256：$($verified.sha256)
- 生产证书：$($verified.certificateSha256)，与上一实际交付 1.0.68（230）一致。

本轮完成播放进度条局部柔性形变、菜单按压和选中反馈、详情播放区域整体回弹，并按用户要求去除主播放按钮及内部图标/时间胶囊的深色描边。保留当前工作区已有改动；相对上一交付包的文件摘要见 changes-from-230.json。更新说明见 release-notes.txt。

验证：2700 项全量 JVM 测试通过；最终视觉调整后 42 项相关回归与 8 项 Android 9 真机仪器测试通过。TV 共享代码编译通过。真机测试使用独立验证包，未安装本次正式 APK，也未进行本次正式包的媒体播放验证。

Release 构建及全部发布门禁通过；版本递增、更新说明、生产签名、ARM64 原生库、ZIP 完整性及 ZIP/ELF 16 KB 对齐均通过。最终构建前后 $($verified.sourceFileCount) 份源码与构建输入哈希一致。首次构建更新了 Release 依赖锁，随后以严格锁定配置复核通过。

用户明确确认本次沿用现有 MDK 授权进行 Full 本地打包。本次未上传、发布或推送。
"@
$readme | Set-Content -Encoding UTF8 "$destination/README.md"
$readme | Set-Content -Encoding UTF8 (Join-Path $PSScriptRoot 'README.md')
Write-Output (Resolve-Path "$destination/$($verified.apk)").Path
