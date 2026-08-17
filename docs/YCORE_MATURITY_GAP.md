# YCore 距离成熟内核还差什么

审查日期：2026-08-16 · 基线：本分支（含前几轮修复）
承接 [`YCORE_GAP_REVIEW.md`](YCORE_GAP_REVIEW.md)

> **2026-08-17 实施更新：** 本文保留的是实施前差距分析。除用户明确排除的直播/DVR 外，
> 客户端 HLS/DASH ABR、统一断网恢复、Exo/mpv A/V 同步观测、Widevine 离线许可证
> 下载/加密持久化/状态/续期/释放，以及显式授权的匿名 QoE 日聚合已经实现。发布门禁已有
> 可执行评估器，但真机矩阵、商业 Widevine 服务和 8/24 小时长稳仍必须产出外部证据，
> 不能由单元测试替代。以下“缺口”章节是历史依据，不再代表当前代码状态。

> 原审查环境无法编译 Kotlin；本次实施已经运行 Gradle/Kotlin 单元测试和 Android 编译，
> 但仍不是真机结果。

---

## 零、先把对照面说清楚

YCore **不是解码内核**，它不和 FFmpeg、Media3 的解码层竞争 —— 它是三个成熟内核
（ExoPlayer / mpv / MDK）之上的**编排层**。所以"成熟"的对照面应该是
Media3 自己的自适应与错误恢复层、Shaka Player、以及商业播放器 SDK 的编排部分，
而不是 libavcodec。

按这个对照面，YCore 已经具备的东西并不少，而且有些是多数第三方客户端没有的：

| 能力 | 状态 |
| --- | --- |
| 三后端编排 + 探测 + 故障切换 + 学习记忆 | ✅ 少见 |
| 帧率匹配（`ExoAlwaysFrameRateSurfaceBinder`） | ✅ 成熟播放器标配，很多客户端没做 |
| DRM 路由与安全解码契约（Widevine / ClearKey / PlayReady） | ✅ |
| 原盘 ISO / BDMV / DVD 判定与原生设备路由 | ✅ 少见 |
| 四档缓冲策略（`PlaybackBufferPolicy`）随用户意图切换 | ✅ |
| 电源 / 热压力感知降级 | ✅ 少见 |
| 切换时的中立交接快照（位置、播放意图、速度、轨道意图） | ✅ |
| 隐私边界（不落 URL / token / 标题，仅存能力签名） | ✅ 做得比多数商业 SDK 干净 |

下面是差距，按我认为的影响排序。

---

## 一、自适应只有"服务器档位"，没有客户端 ABR

`PlaybackAdaptiveNetworkController` 做的是：累计卡顿次数 + EWMA 吞吐 + 前向缓冲压力
→ 建议**降低服务端转码档位**。

全仓找不到 `DefaultBandwidthMeter`、`AdaptiveTrackSelection`、`setMaxVideoBitrate`
或任何 HLS variant 选择逻辑。也就是说：

- 降档 = **向服务器重新请求一条新的转码流**，意味着一次完整的重新起播与重新缓冲；
- 成熟栈的 ABR 是在**同一条 HLS/DASH ladder 内切 variant**，切换发生在分片边界，
  用户察觉不到；
- 三分钟升档冷却是为了压制震荡 —— 但成熟 ABR 不需要这么长的冷却，
  因为它的切换成本本来就接近零。

这是 YCore 与成熟内核**体感差距最大的一条**：弱网下 Yfuse 是"卡一下、黑一下、换一档"，
成熟播放器是"清晰度悄悄降了"。

补齐路径也很清楚：Emby 的 HLS 主播放列表本身就是多档的，Media3 的
`DefaultTrackSelector` + `DefaultBandwidthMeter` 直接支持在 ladder 内自适应。
把"降档"从"换 URL"改成"设 `maxVideoBitrate` 约束"，大部分收益就到手了。
mpv 侧没有等价物，可作为 Exo 专属能力。

---

## 二、错误分类读的是中文句子，导致学习记忆被污染

这是本轮**最该先修**的一条，而且是可验证的实际缺陷。

`classifyPlaybackFailure(message: String?)` 用**英文小写关键字**做子串匹配：

```kotlin
DECODER_FAILURES = listOf("decoder", "decode", "mediacodec", "codec failed", …)
NETWORK_FAILURES = listOf("timeout", "timed out", "network", …)
```

而引擎写进 `state.error` 的是**中文句子**。把引擎实际发出的 7 条消息代入分类器：

| 引擎实际消息 | 分类结果 | 应为 |
| --- | --- | --- |
| 当前视频无法解码，且服务器未提供可用转码流 | `Unknown` | Decoder |
| 当前视频无法解码，正在尝试其他播放器 | `Unknown` | Decoder |
| 当前音轨不受 ExoPlayer 支持，正在尝试其他播放器 | `Unknown` | AudioSink |
| 播放失败：ERROR_CODE_IO_NETWORK_CONNECTION_FAILED | `Network` | Network ✅ |
| 服务器返回了无效的转码清单 | `Unknown` | Container |
| **网络连接多次失败，已尝试所有播放方式** | **`Unknown`** | **Network** |

**7 条里 5 条分错。** 唯一分对的那条，是因为它把 `errorCodeName` 原样拼进了句子，
恰好带上了英文 `NETWORK`。

后果不是"标签不好看"。`PlaybackFailureKind.allowsBackendFallback` 的定义是
`kind !in {Network, Authorization, Drm}`，而架构文档明确承诺：

> Network and authentication failures never blacklist a decoder.

一条被判成 `Unknown` 的网络故障**满足** `allowsBackendFallback`，于是：

1. 它会去切换后端 —— 而后端根本没问题；
2. 它会向 `PlaybackFailureMemory` 写入一条**引擎作用域**的失败记录，
   把一个无辜的后端在该内容签名下**封禁 7 天**。

家宽抖动一次，用户可能就永久失去了那台设备上最合适的解码路径，直到一周后过期。

**根因和我前几轮修的是同一个**：结构化事实已经存在，却被转成给人看的句子之后再解析回来。
`ExoVideoEngine.onPlayerError` 手里就有 `error.errorCode`（并且**已经**用它做即时决策），
但它随后 `failPlayback("当前视频无法解码…")`，把 errorCode 丢掉，
留给下游去猜。修法是让失败沿着契约携带 `PlaybackFailureKind`，
而不是携带一句话 —— 文案继续给面板用。

---

## 三、没有 A/V 同步测量，但验收标准里有它

`YCORE_VALIDATION_MATRIX.md` 的发布门槛写着：

> A/V sync — absolute error no greater than 80 ms after seek and handover

全仓搜不到任何 A/V 同步的测量、上报或校正代码。这条门槛目前**无法被评估**，
无论人工还是自动。

尤其重要的是它点名的两个场景 —— seek 之后、**交接之后**。交接正是 YCore 的核心机制：
换后端时位置、速度、播放意图都会重建，而这恰恰是音画最容易错位的时刻。
一个以"自动切换后端"为卖点的内核，却没有测量切换是否把音画切歪了。

至少应该有：交接前后各采一次 `audioSessionPosition` 与渲染时间戳的差值，
写进现有的 `PlaybackHealth` 诊断。不必自动校正，先能看见。

---

## 四、网络中断后没有恢复路径

`ConnectivityManager` / `onAvailable` / 重连逻辑只存在于**一起看的 WebSocket**，
播放链路上没有。网络掉线再回来，播放不会自己续上。

成熟栈的做法是：监听网络可用性，在恢复时用 `Range` 请求从当前位置续，
而不是让用户手动重开。Exo 的 `DefaultLoadErrorHandlingPolicy` 已经有分片级重试
（YCore 也确实用到了 `scheduleRetry` 处理 manifest），但**进程级的"断网—恢复"**这一层是空的。

---

## 五、观测性止步于本机

`PlaybackPerformanceMemory` 有 30 天滚动基线（启动耗时 / 卡顿 / 丢帧），
按能力签名分组 —— 这一步做得很好，而且隐私边界干净。

但它只喂给本机的路由决策。成熟栈会把 QoE 指标聚合上报，用来回答
"哪些机型 + 哪些编码组合正在变差"。当前设计**刻意**不上报任何东西
（隐私优先，我认为这个取舍是对的），代价是：

- 发布门槛里的 P50/P95 只能靠人工在真机上采；
- 线上回归只能靠用户报障发现。

这不是缺陷，是一个需要**明确决定**的取舍：要么接受"只能人工采样"，
要么设计一条可选的、匿名的、用户显式同意的上报通道。含糊着不做决定，
就等于选了前者但没承认。

---

## 六、其余功能面缺口

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 直播 / DVR | ❌ | 无 LiveTv 路径，滑动窗口、时移、低延迟 HLS 全无 |
| 无缝换轨 | ⚠️ | 换音轨/字幕走引擎自身能力，未验证是否免重缓冲 |
| 离线 DRM 密钥 | ⚠️ | 有 DRM 路由，未见 offline keySetId 持久化 |
| Seek 优化 | ⚠️ | `seekTo` 直接透传，无合并/去抖；快速拖动会连发 seek |
| 长稳 | ❌ | 8/24 小时 soak 未跑，内存/句柄泄漏未知 |

---

## 七、建议顺序

| 顺序 | 事项 | 理由 |
| --- | --- | --- |
| 1 | **失败沿契约携带 `PlaybackFailureKind`** | 已在污染 7 天学习记忆，会误封健康后端；和前几轮修的是同一个模式 |
| 2 | 交接前后采集 A/V 同步差值 | 发布门槛已写死 80 ms，目前不可评估；交接是最可能出问题的时刻 |
| 3 | 客户端 ABR（Exo 内 ladder 约束） | 体感差距最大的一条，且 Media3 原生支持 |
| 4 | 断网恢复 | 用户可感知，实现成本中等 |
| 5 | Seek 去抖 | 小改动，改善拖动手感 |
| 6 | 上报取舍拍板 | 决定发布门槛能否自动化 |
| 7 | 直播 / soak / 离线 DRM | 功能面扩展，按产品优先级排 |

---

## 八、一句总结

YCore 的**策略层**已经接近成熟：边界清晰、纯函数、可测试、隐私干净，
甚至有一些成熟客户端都没有的能力（帧率匹配、原盘路由、三后端学习编排）。

它离成熟还差的，几乎全部集中在**策略层与运行时之间的那道接缝**：
结构化的事实在过缝时被转成人类可读的句子，到了另一侧再靠猜还原回来。
读取就绪状态是这样、Dolby 角标是这样、错误分类还是这样 —— 已经修掉两处，
第三处（错误分类）是眼下最该修的，因为它已经在污染持久化的学习数据。

把这道缝焊死之后，剩下的就是**功能面的补齐**（ABR、断网恢复、直播）
和**验证的开工**（设备通道、语料库、长稳），而后者决定了
"稳定 / HDR 兼容 / 更省电"这些话什么时候可以对外讲。
