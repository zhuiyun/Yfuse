$ErrorActionPreference = 'Stop'
Set-Location 'D:\Demo\Yfuse'
$releasePython = 'C:\Users\app-inkbird\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$destination = 'artifacts/releases/library-handoff-1.0.70-232'
& $releasePython (Join-Path $PSScriptRoot 'source-manifest.py') verify
if ($LASTEXITCODE -ne 0) { throw 'Source inputs changed; do not deliver.' }
Copy-Item composeApp/build/outputs/apk/release/composeApp-release.apk "$destination/Yfuse-1.0.70-232-full-arm64-signed.apk"
Copy-Item version.properties, release-notes.txt $destination
& $releasePython (Join-Path $PSScriptRoot 'verify-release.py')
if ($LASTEXITCODE -ne 0) { throw 'Final APK verification failed; do not deliver.' }
Copy-Item (Join-Path $PSScriptRoot 'source-hashes.json'), (Join-Path $PSScriptRoot 'test-summary.json'), (Join-Path $PSScriptRoot 'changes-from-231.json') $destination
$verified = Get-Content -Raw "$destination/verification.json" | ConvertFrom-Json
$readme = @"
# Yfuse 1.0.70（232）正式签名包

- APK：Yfuse-1.0.70-232-full-arm64-signed.apk
- 包名：com.yfuse；Full ARM64，含 YCore、MPV、MDK。
- 大小：$($verified.bytes) 字节。
- SHA-256：$($verified.sha256)
- 生产证书：$($verified.certificateSha256)，与上一实际交付 1.0.69（231）一致。

修复共享 IP 下 HTTP 媒体服务器被主机级网络安全规则误拦截的问题；设备接力区分账号登录和服务连接状态，保留明确的失败原因。更新说明见 release-notes.txt。

验证：44 项相关 JVM 测试通过；2 项 Android 9 网络策略实机测试通过（独立内部测试包），未在用户 Android 16 设备或本次正式 APK 上进行实际媒体播放验证。

Release 构建门禁、版本及更新说明、正式签名、包内网络安全配置、ARM64 原生库、ZIP 完整性及 ZIP/ELF 16 KB 对齐均通过。构建前后 $($verified.sourceFileCount) 份源码和构建输入哈希一致，原生运行库与上一交付包一致。

用户明确确认本次 1.0.70 沿用现有 MDK 授权进行 Full 本地打包。未上传、发布或推送此版本。

沿用现有构建配置，自动更新清单校验公钥仍为空，正式应用会拒绝应用内更新；本次通过 APK 手动覆盖安装。此项不影响 APK 自身的生产签名校验，未在本次变更中放宽更新信任策略。
"@
$readme | Set-Content -Encoding UTF8 "$destination/README.md"
$readme | Set-Content -Encoding UTF8 (Join-Path $PSScriptRoot 'README.md')
Write-Output (Resolve-Path "$destination/$($verified.apk)").Path
