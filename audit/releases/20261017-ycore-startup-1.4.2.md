# YCore 起播修复：1.4.2（294）

状态：代码及本地回归验证完成；用户已授权推送 master 并执行云端仅打包流程。正式签名 APK 尚未生成或交付，不发布在线更新。

## 基线与问题

- 基线提交：`55eef177bb083a379a292c6b73db86702a3d7eeb`。
- 用户诊断包中的已安装版本为 1.4.1（293）；本次版本配置及更新说明递增到 1.4.2（294）。
- 远程 SDR MKV、H.264、E-AC-3 起播先经过平台探测和 NativeDirect；后者没有暴露服务端声明的音轨。
- 增强解封装在打开源时耗尽预算，自动重试又复用已经取消的预算，未获得实际重新打开的机会。
- 最终提示保留了更早的音轨缺失错误，掩盖增强路径实际因超时退出的原因。该日志不足以证明设备不能解码 E-AC-3。

## 修改

- 对符合既有短分析安全条件的远程 SDR MKV/E-AC-3，优先尝试增强解封装；已准备或验证过的路径仍可复用。服务端提示只影响解封装顺序，真实轨道和解码器仍需验证。
- 增强打开先进行短分析；视频尺寸、音频信息、预期音轨数量或所选轨道不完整时，在现有预算内重新执行完整分析。
- 本地 HTTP 代理起播时抑制远端及文件尾部预取，保留当前块的数据补充；开始播放后恢复常规预取。
- 有次数限制的传输重试可取得新的 20 秒预算。旧预算的取消及完成不会取消新一代；主动释放和切源取消不作为网络故障重试。
- 超时显示起播超时，历史音轨缺失原因保留在诊断中，避免把未完成的增强尝试归结为音频不支持。
- 更新版本、更新说明及与固定 MDK 校验和绑定的 package-only 记录。保留 full ARM64、`yfuseIncludeMdk=true`、`yfuseNativeOnlyRuntime=false`。

## 本地验证

使用 JDK 17、仓库 Gradle 9.8.1、Android SDK 37.0。最终代码已重新编译，以下 37 项测试全部通过，失败、错误和跳过均为 0：

| 测试类 | 数量 |
| --- | ---: |
| AndroidProbeBudgetTest | 10 |
| AndroidDemuxAnalysisPolicyTest | 6 |
| AndroidAdaptiveCore2RecoveryFailureTest | 12 |
| AndroidTransportStartupAndResumeTest | 9 |

命令为 `:phoneShared:testAndroidHostTest`，用 `--tests` 限定上述四个类；最终运行 `BUILD SUCCESSFUL`。

其他检查通过：修改过的 Kotlin 文件的 ktlint 1.3.1、`git diff --check`、`scripts/check_function_size.py`、`scripts/verify-module-boundaries.py`、`scripts/release_metadata.py`、`scripts/mdk_distribution_approval.py --package-only`。原生 MPV 与固定 MDK 下载均通过仓库校验和检查。

没有连接用户的私有播放源，也没有进行设备播放复测；上述结果证明编译和已覆盖行为通过，不能替代原片源实测。

## 签名与交付待办

本地不存在 `keystore.properties` 正式签名配置。现有 `.github/workflows/publish-android.yml` 支持提交消息包含 `[artifact only]` 时只构建、正式签名及保留 APK，不发布在线更新。

用户于 2026-10-18 01:28:59（Asia/Shanghai）明确回复“允许”，授权将本次修复推送 master 并触发现有云端仅打包流程。不修改签名或验证门禁，不发布在线更新。

交付前仍须读取最终 APK 的实际包名、版本号，验证生产签名、MDK 运行库、DEX 和产物 SHA-256；本记录不表示这些产物验证已经完成。
