# SenPlayer SDR 转 HDR与 AI 超清接入

本次以当前正式分支为基线，适配用户提供 APK 的固定原版运行库，不重新编译反编译类，也不修改原始许可、DEX 或原生库。本文描述源码接入及离线验证；没有真机画质、功耗或模型精度的实测结论。

## 研究依据

原 APK SHA-256：`45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c`。

反编译资料根目录：`audit/senplayer-comparison-20261105/senplayer/`。`sources_readable` 仅解码原始字符串，保留原始行号；字段映射同时核对 `apktool/smali`。

| 能力 | 原版接口 | 范围与状态 |
| --- | --- | --- |
| SDR 转 HDR | `Player.v("surface-sdr-hdr", level)` | 0 关闭、1 低、2 中、3 高；`Playback.State.y` 报告实际 active |
| AI 超清 | `Player.v("surface-upscaling", level)` | 同上；`State.A` 报告实际 active，`State.z` 报告 available |

调用证据：`sources_readable/defpackage/ai4.java:23-24` 定义四档；`op3.java:674-691` 初始化选项并检查返回码；`w8.java:33-47` 为播放期间修改；`com/mplaykit/Player.java:275` 将调用串行送到原执行器，经 `com/mplaykit/c.java:33` 调用 `N.n7`。仅整数 0 是接受；非零、空值或畸形返回不视为成功。

状态证据：`com/mplaykit/Playback$State.java` 的 `y/A/z` 分别来自原生数组的 `19/20/21` 项；`yl1.java:120-123` 转为 `rp3.u/v/w`，对应已解码名称 `sdrToHDRActive`、`videoUpscalingActive`、`videoUpscalingAvailable`。原始 smali 中的数组下标、字段赋值和映射一致。

适用条件沿用原版：

- SDR 转 HDR：`vp3.java:87` 检查显示器 HDR10 能力；`op3.java:3965` 的 `w()` 检查所选视频 `dynamicRange=sdr` 且 `dvProfile=0`，并排除投屏。原版“峰值亮度超过 1000 nit”为提示，不是硬性测量门槛。
- 超清：`op3.java:4011` 的 `x()` 检查有效播放、时长大于零、原生 available，并排除投屏。原版文案为 1080p 及以下片源；Yfuse 采用原生 available，不用文件名或网络信息推测支持性。

`libFFmpegKit.so` 内存在两项选项名、SDR 转 HDR shader 和 NCNN/Vulkan 推理组件字符串。APK 未包含独立的外部模型文件，本次无需增加下载模型或其他播放内核。静态字符串不足以确认所用模型名称、结构和增强质量，故不作这类声明。

## Yfuse 行为

入口：播放页 → 更多 → 播放设置 → 画面设置 → SenPlayer 画质增强。

- 两项设置独立保存，默认关闭；关闭、低、中、高使用原始枚举值。
- SenPlayer 播放中可调整；非 SenPlayer 时仅保存偏好，切换到 SenPlayer 后应用。YCore 的 Anime4K 保持独立。
- 原执行器串行执行调用，设置不变时不重复发送；拒绝不无限重试。两项分别处理，一项不可用不阻断另一项或正常播放。
- 时长、片源色彩或设备能力尚未确认时显示等待/不可用原因；能力确认后重新尝试。切换到不适用的片源时发送关闭，避免沿用上一集增强状态。
- 换源、会话或 Surface 重建会重新应用；seek、Surface 分离和恢复清除过期的实际生效标记。投屏诊断不继承本地 SenPlayer 设置状态。
- UI 分开表示设置接受、当前未生效、原生报告生效及拒绝。只有当前有效源与画面上的原生 active 标记，才显示“已生效”；选项返回 0 不等同于 HDR 已显示或 AI 效果已验证。
- 超时或取消导致运行时退休时仍执行原播放失败处理，不吞掉致命会话错误。

主要实现：

- `senplayerBridge/.../SenPlayerSession.java`：固定选项的类型化适配、返回码和原生状态映射。
- `core/data/SenPlayerPicturePreferences.kt`：独立保存偏好及损坏设置回退。
- `feature/player/contract/SenPlayerPictureState.kt`：请求、确认与原生生效状态。
- `feature/player/SenPlayerPictureController.kt`、`SenPlayerPicturePolicy.kt`：去重、条件变化和独立失败处理。
- `feature/player/SenPlayerVideoEngine.kt`：输出生命周期和原版调用。
- `feature/player/PlayerSenPlayerPictureControls.kt`：四档选择、限制和状态提示。

## 验证边界

验证记录位于 `audit/senplayer-picture-20261106/`：Java 反射边界检查、JVM 回归测试、手机及 TV 编译、项目设计与分层检查、固定资产 SHA-256 校验。详细结果由同目录验证记录列出。

原始五项资产逐一匹配固定 SHA-256。没有新增许可请求、设备安装或真机运行；没有变更 HTTP 策略，没有加入其他内核。源码接入阶段未生成交付 APK；随后用户要求正式签名打包，版本配置更新为 1.9.0（342），打包结果以对应交付验证记录为准。旧 1.8.9（341）APK 不包含本次修改。

待设备条件允许时才可确认的部分：不同 GPU 的增强画质、HDR 实际亮度/色彩、持续播放发热耗电及掉帧。原生 active 是运行时报告，不能代替这些实测。
