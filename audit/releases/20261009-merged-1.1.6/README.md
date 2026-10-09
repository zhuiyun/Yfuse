# 1.1.6（268）合并与签名打包记录

合并远端 `335530f494ec30a22e66d21ac70d6a1e5750b861`（1.1.5 / 267），保留本地 `64b56c47` 中的返回手势、播放恢复、离线、后端隔离与服务端修复。新版本已同步写入根目录配置与更新说明，实际上一份签名 APK 已核验为 1.1.5 / 267。

本次适配远端拆分后的 DI、页面导航、个人内容自动同步和新手机登录协议；新增 TMDB 代理移入独立后端模块。补齐播放按钮、重试与选集的音频焦点准入，按恢复后的实际集选择方向；修复文件源 URI 转义与资料库原子覆盖。测试夹具同步适配新版导航来源表。

验证见 [validation.json](validation.json)：手机 4,707、TV 117、后端客户端 43、协议 25、服务端 257 项 JVM 测试通过；后端关闭配置另 43 项通过。Python 107、JavaScript 17、portable native 19 项通过；两壳编译、全仓 ktlint、设计契约和包含共享产品源码的手机/TV lint 通过。Lint 基线没有调整。

原生库由同源、验签通过的 1.1.5 APK 恢复，二进制、构建 ID 与 CI 来源核验见 [native-runtime.md](native-runtime.md)。portable 测试限制见 [portable-native.json](portable-native.json)。原始本地日志与二进制产物保留在受忽略目录，审查元数据不包含用户目录、设备序列号或密钥。

签名精简版已经构建并验证：`Yfuse-1.1.6-compact-arm64.apk`，包名 `com.yfuse`，APK 实际版本 `1.1.6 / 268`，arm64-v8a，23,572,646 字节。源码提交 `41a5c4cea832f41f60386d367187f31780af34a3`，构建时工作区干净。

SHA-256：`5f842df8d469cac6ba36c49d441a22fa4fa249e5c16478102db6b6333572874d`。正式签名与上一份实际交付 APK 的证书一致，唯一签名者、v2 验证、16 KiB 对齐及 DEX 验证全部通过。APK 内 8 个 YCore 运行库逐字节匹配已核验的 AAR；精简版不含 MDK 运行库。

实际元数据及构建命令见 [verification.json](verification.json)，包信息与 DEX 输出分别见 [apk-badging.txt](apk-badging.txt)、[apk-dex.txt](apk-dex.txt)。APK 与 R8 mapping 本地保存于 `artifacts-local/releases/merged-master-1.1.6-268/`，不纳入 Git。未安装新版 APK 到设备或修改现有 App 数据。

本次仅本地交付 APK 和上传代码，提交使用 `[artifact only]`，禁止触发线上更新发布。用户于 2026-10-09 明确要求“以后打包都需要包含mdk”，已保存为项目长期要求，并据此继续本次待补齐的 1.1.6 / 268 完整版打包；沿用相同 MDK 校验和与 package-only 范围。本次为同一交付的完整配置补齐，版本不重复递增，已有精简包证据保留。
