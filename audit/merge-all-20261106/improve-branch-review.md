# improve 分支独有改动审查与合并裁定

审查日期：2026-11-06。旧共同基线 `80fac8caab99a4db9c491fb9ac9edf1c758f7cc1`；待整合分支 `4c38e8cfb9020321841af892956ba2cce9c32c24`；正式对照为已包含 CI 修复的输出分支 `9be95151403d13f811b4d35733ec2dbe45f840c1`。共逐项审查 49 个文件，完整机器可读裁定见 `improve-branch-file-decisions.json`。本文先于后续授权的 MPV 手工冲突解决记录；此处未声称构建或设备验证通过。

## 应保留的独有价值

| 改动 | 具体证据 | 合并方式与验证 |
|---|---|---|
| MPV 真实启动进度 | improve `MpvStartupProgress.kt:19-36` 以读位置/缓存时长高水位增长更新进度；`MpvVideoEngine.kt` 去掉日志和 cache-speed 心跳，增加 stream-pos | 移植追踪器、属性观测和对应测试。保正式分支的 seek/source gate、buffer generation 与 `YBufferSnapshot`。日志、零速度、重复缓存值不能续期。 |
| 独立时间轴绘制文件 | improve `PlayerSeekRail.kt:13-86` 抽出章节间隙、缓存区间和已播放渐变；正式 `PlayerChromeRefined.kt` 仍内联等价绘制 | 采纳职责抽取，类型改为已有 `YBufferedRange`；只用实际 `bufferSnapshot.ranges`，不恢复 nullable fallback。保拖动/预览/章节所有行为。 |
| Sen 显式选择限制 | improve `PlaybackPlanner.kt:274` 新增 `filterNot { SenPlayer && lockedEngine != SenPlayer }` | 必须移植。正式旧测试只覆盖 preferredExo+Auto，不能防 preferredSen+Auto；新测试明确覆盖这一状态和未打包情况。 |
| Sen 输出能力保守声明 | improve `PlaybackOutputCapabilities.kt:75` 将帧率匹配和音频直通标为 BackendApiNotVerified | 应移植。正式对照默认路径会把 Sen 视作支持这些设置；现有实播不等于已验证压缩音频直通和刷新率 API。增加 Sen/API 28/30/31 能力测试。 |
| QoE 部署兼容 | improve `YCorePlayerRuntime.android.kt:220` 禁止把新 Sen 枚举上传给未知部署版本 | 保门控或增加已确认服务能力协商。两边本地 `QoeEngine.SenPlayer` 相同，但无法据此确认线上服务已升级。不要丢弃本地诊断。 |
| 导入完整校验及负面测试 | improve `scripts/import-senplayer-runtime.py` 先验证全组再临时写入；测试拒绝错二进制及无关 ZIP 项 | 只移植流程和测试意图到正式 **5 资产 + manifest** 导入器；保整 APK SHA256 门禁、原 DEX/license/MLibKit，不能整文件覆盖成仅两 so 导入。 |

## MPV 改动必须修正的一点

improve `MpvFileLoadWatchdogPolicy.kt:98` 在 `hardLimitMs` 判断之前，只要曾有可信增长且距上次增长小于 stallMs 就返回 Wait。持续输入但永远无法完成初始化的流因此可以无限越过所谓 hard limit。保留真实进度判定，但必须有优先执行、不可被心跳绕过的绝对截止；如后续希望弱网获得更长时间，应明确单独受限预算，不能让 hardLimit 名存实亡。

追踪器的第一次非零 stream-pos 只是 offset 基线；倒退和相同值不能算收到新内容。只有当前 Loading 阶段、已确认 native identity 和当前 attempt 的观测有效。正式 `MpvVideoReadinessGate` 新增的 `seekPending/bufferRevision/canReportBufferRanges` 是缓存生命周期修复，不能因合入 `acceptsStartupProgress` 而回滚。需要回归：未知/错误 source 不续期；旧 attempt 不续期；首值不确认；cache 增长可确认；不变或倒退不续期；停止增长超时；绝对截止仍终止；seek/retry 后旧区间清空且 generation 前进。

## 缓存模型：复用正式契约，不引入第二个真值

improve `PlaybackBufferedRanges.kt` 的保断档、重叠合并、Long 边界测试有价值；但其模型只有 `start/end` 和 nullable 列表。正式 `core2/api/YBufferedRanges.kt` 已包含 `source/sessionId/generation/coverage`、Unknown/ForwardOnly/SeekableRanges、上限归一化、当前位置裁剪，且 MPV/Sen/Media3/MDK/YCore 均已接入。

因此 `VideoEngine.kt`、`PlaybackTransportState.kt`、`PlaybackRuntimeProjection.kt` 保正式 `bufferSnapshot` 单一契约。把 improve 的 range-only 不重组按钮/运行时投影、empty 与 unsupported、保断档测试迁移到该契约；不要同时新增 `bufferedRanges`，也不要把 Unknown 自动合成为 position→bufferedEnd。正式接口已明确没有可确认区间时不伪造范围。

## Sen 实验模块为何不能替换正式桥

improve 新 `senPlayerAndroid` 并非现有正式桥的增量。`com/mplaykit/Player.java:35` 直接 `N.n5(...,0L)`，`N.java` 在宿主用 `System.loadLibrary("FFmpegKit")`；导入器仅携带 FFmpegKit/libc++ 两个库，README 明确没有原 ProductRules/runtimePermit 初始化，也未做 ARM64 实播。其单 Surface 及直接 native 同步调用不能替代当前实现。

正式 `senplayerBridge/SenPlayerSession.java:414-419` 用 framework-only parent 的 DexClassLoader 加载原 DEX，`:483` 使用原许可 manager；保原 license.json、MLibKit、runtimePermit 更新及所有者释放。此前同名宿主混淆类撞名已实际修过，不得再把手写 com.mplaykit 类打入宿主并替代原加载链。

正式引擎另有双视频/字幕 surface、surface epoch、源/会话缓存身份、不可用 session 重建、取消读、待 seek、EOF/队列、像素输出证明/cover。improve `SenPlayerVideoEngine.kt` 会拒绝 customHeaders、没有正式原许可桥、用未分来源的 nullable range，因此既不是完整新功能也不应并存成为第二个引擎。

逐个 JNI/DTO 文件、模块配置、surface 及对应旧测试均已在 JSON 指向正式替代位置。DTO 数组复制、ABI 不兼容拒绝、非有限时间处理等测试意图可以迁移到正式 bridge host checks；文档保留为带提交号的历史记录，不宣称那条实验路径已经通过当前实播。

## 必须保住的既有约束

- HTTP 继续可用：保正式 Sen loopback proxy 的 `widgetPlaybackRequest()` 来源绑定 headers；保 `requiresYCoreFileTransport()` 对 SMB/FTP/传输凭据的必要路由。不要采用实验引擎一律拒绝 customHeaders 作为默认正式行为。
- MDK full 配置与既有打包校验不变；不新增第二份全局 Sen libc++ 与 MDK/native 库争抢。原 Sen native 继续作为受校验资产按原路径隔离加载。
- `NativeCrashIsolation` 保正式对 `libFFmpegKit.so` **及 `libMLibKit.so`** 的归因；实验分支只识别前者，不能覆盖导致许可原生崩溃归因回退。
- Android API28/ARM64 门禁、Sen 手动选择、DRM/Dolby 安全路由、原许可初始化及验证证据继续有效。此次合并不授权重新做许可联网、改版本或出包。

## 合并后必要验证（主任务统一执行）

1. MPV：`MpvStartupProgressTest`、`MpvFileLoadWatchdogTest`、`MpvVideoReadinessGateTest`；增加可信输入不能绕过绝对截止。
2. 时间轴：`PlaybackTransportStateTest`、`MpvBufferedRangesTest`、已有 YBufferedRanges/seek/章节/预览测试；确保只有一套 snapshot 契约、无 duplicate bufferedRanges 参数。
3. 路由/能力：`PlaybackPlannerTest`、`SenPlayerRoutingTest`、`PlaybackOutputCapabilitiesTest`；覆盖 preferredSen+Auto、Sen 未打包、平台限制及 HTTP headers/文件源路由。
4. 桥：现有 Sen bridge host checks、`SenPlayerStatePolicyTest`、`SenPlayerOutputPolicyTest`；保原许可/隔离加载/双surface与源身份。
5. 导入器：错 APK、缺成员、损坏成员不得覆盖已有正式资产；来源 manifest 与 5 资产哈希一致。打包与联网不是本审查步骤。
6. phone/TV 编译、结构与大小门禁、依赖重复类检查；这能发现两套 JNI 模块/重复契约意外保留，但不能替代物理输出验证。

## 授权后 MPV 实施记录

在逐文件裁定完成后，主任务授权本代理仅解决 4 个 MPV 生产文件及 2 个测试文件，未执行 Git add/commit、Gradle、联网或打包。

- `MpvVideoEngine.kt` 的唯一冲突已手工消除，保留正式分支所有 seek/source/generation 区间修复；移植 stream-pos/有效 cache duration 增长。日志、cache-speed 和 START_FILE 不再制造进度。arm/mark/cancel 与 watchdog 判定使用同一实例锁，避免迟到截止或重置污染替换请求。
- `MpvVideoReadinessGate.kt` 仅新增已绑定、非空且匹配 native identity 的 Loading 进度门禁，原 seek/buffer revision 逻辑完整保留。
- `MpvFileLoadWatchdogPolicy.kt` 先检查绝对 hard limit；只有 confirmed 且在有效时间区间内的进度能更新 stall 基线，未确认/负数/未来时间按启动时间处理。
- `MpvStartupProgressTest` 5 项、`MpvFileLoadWatchdogTest` 7 项，共 12 项待主任务统一运行；覆盖高水位、重置/旧 owner、未知/错误源、playlist 子源、确认与未确认心跳、绝对截止。额外运行现有 `MpvVideoReadinessGateTest` 验证原 seek/range 行为。
- 6 文件 standalone ktlint exit 0；`git diff --check` 无输出；MpvVideoEngine 无冲突标记。此记录不把格式检查等同于编译或测试通过。
