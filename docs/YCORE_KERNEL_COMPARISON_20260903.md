# YCore 自研内核对照审查 · 2026-09-03

审查范围：`com.yfuse.core2`（commonMain + androidMain）、`ycore-native`、
`AndroidYCoreHttpProxy` 自适应链路。
对照面：ExoPlayer / Media3、libmpv、MDK。
承接 [`YCORE_GAP_REVIEW.md`](YCORE_GAP_REVIEW.md) 与 [`YCORE_MATURITY_GAP.md`](YCORE_MATURITY_GAP.md)，
这两份文档描述的是 Legacy 三后端编排层；本文审查的是 Core2 执行层。

> **修订 r2（评审后）**：初版对传输层缓冲的量级判断错误（把 `MIN_TRANSPORT_BLOCK_BYTES`
> 当成了实际块大小），并对差异化能力有两处夸大。第一章、第三章已按实际代码重写，
> 修正内容见文末「修订记录」。

## 适用范围

`yfuseNativeOnlyRuntime` 默认为 `false`（`composeApp/build.gradle.kts:20-27`），
**仓库默认构建仍带 mpv 兼容执行器**。只有 native-only 制品
（`AndroidCore2Trial.kt:145` 把 `compatibilityFactory` 置 `null`）没有兜底。
`release-notes.txt` 1.0.23 说明生产分发的是 native-only 制品。

因此下文标注「硬失败」的条目，指的是 **native-only 制品**；
默认构建下同样的路径会降级到 mpv，是体验损失而非功能缺失。

> 本文是静态代码审查。所有条目给到 `文件:行` 可直接复核；
> 凡涉及运行时性能的判断都标注了它依赖哪项真机数据才能定级。

---

## 零、总体判断

Core2 的**策略层**（`strategy/`、`capability/`、`adaptive/` 解析器、`bitstream/`、`dolby/`）
质量很高：纯函数、可测、边界清楚，HLS/DASH 解析和 Dolby Vision 路由的严格程度超过多数第三方客户端。
传输层的缓冲策略也比初版审查判断的成熟得多（见 1.1）。

确认成立的问题集中在四处：

| # | 问题 | 性质 |
| --- | --- | --- |
| 1 | Dolby Vision 配置就地破坏共享 `MediaFormat` | 确定性缺陷，可立即修 |
| 2 | >8 声道回落 stereo mask 但不 downmix | 确定性缺陷，可立即修 |
| 3 | ABR 用墙钟估算缓冲，pause/seek/speed 全部失真 | 确定性缺陷，需要接真实信号 |
| 4 | NativeDirect 解复用同步跑在 codec/render pump 上 | 架构风险，定级依赖真机数据 |

加上两个由产品承诺决定的功能空洞：纯音频、后台/息屏音频。

---

## 一、缓冲与传输（初版结论已推翻）

### 1.1 传输层预取实际是按 10 秒窗口计算的

初版把 `MIN_TRANSPORT_BLOCK_BYTES = 256 KiB` 当成了块大小，结论"3 MiB / 1.2 秒"是错的。
实际链路：

`AndroidTransportMediaDataSource.kt:45-57` 用 `mediaBitRateBitsPerSecond = 0` 调 `YCachePlanner.plan`
（`YMediaTransport.kt:157`），走到 `oneSecond = DEFAULT_READ_AHEAD_BYTES = 2 MiB` 分支，
`readAheadBytes = min(2 MiB, 64 MiB, 16 MiB) = 2 MiB`；
`blockSize = readAheadBytes.coerceAtLeast(MIN_TRANSPORT_BLOCK_BYTES)` = **2 MiB**。

`transportPrefetchDepthBlocks`（`AndroidTransportMediaDataSource.kt:601`）按
`TARGET_TRANSPORT_PREFETCH_WINDOW_MS = 10_000` 反算块数，
且 `mediaBitRateBitsPerSecond` 在运行时单调更新并重算深度（`:147-151`）。
12 块上限 × 2 MiB = **24 MiB**。实算：

| 媒体码率 | 预取深度 | 实际前向窗口 |
| --- | --- | --- |
| 8 Mbps | 6 块 | 12.6 s |
| 20 Mbps | 12 块 | **10.1 s** |
| 40 Mbps | 12 块（封顶） | 5.0 s |
| 80 Mbps | 12 块（封顶） | **2.5 s** |

**10 秒目标在 ~20.1 Mbps 以内完整兑现**，仓库测试也确实叫 "bounded ten second prefetch window"。
初版"网络抖 2 秒 YCore 必卡"的结论不成立，撤回。

保留的、范围收窄后的观察：**`MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 12` 的上限
在 20 Mbps 以上开始生效**，而 UHD 蓝光 remux（50–100 Mbps）正是本产品的招牌片源，
那里窗口收缩到 2–5 秒。这是一条按内存预算而非按块数封顶就能解决的问题，
但**是否值得改，应由真机上高码率原盘的 rebuffer 数据决定**，不应先验地当作瓶颈。

### 1.2 Enhanced 的读前水位是自适应的，不是 3 秒

初版引用的 `DEFAULT_HIGH_WATERMARK_US = 3s`（`AndroidDemuxReadAheadNode.kt:376`）
只是节点构造默认值。打开远程媒体后 `AndroidEnhancedPlaybackSession.kt:457,1533`
立即用 `YBufferController.plan` 的结果覆盖它（`YBufferController.kt:102`）：

| 条件 | 目标前瞻 |
| --- | --- |
| 本地 | 1.5 s |
| 直播 | 3 s |
| 远程 · 吞吐健康（≥1.4× 码率） | 4 s |
| 远程 · 尚无实测吞吐 | 6 s |
| 远程 · 码率未知 | 8 s |
| 远程 · 余量偏窄 | 10 s |
| 远程 · 吞吐低于码率 | 15 s |

这是"网络好就少缓冲、有压力才加深"的反向策略，比固定值合理。
另有 `memoryLimitedUs` 按 64 MiB 预算二次封顶——80 Mbps 下约 6.4 s，
与 1.1 的封顶是同一个内存预算问题的两个出口。

### 1.3 与 Exo 的对比方式更正

Media3 `DefaultLoadControl` 默认目标缓冲确实是 50 s，但它**同时受 allocator 字节目标约束**，
并不是无条件缓冲 50 秒。把它和 YCore 的「解码器输入前瞻」或「传输层预取窗口」
做一对一的秒数比较是无效的——三者语义不同。

有意义的对比只能是端到端指标：同一片源、同一网络损伤下的 rebuffer 次数与时长。
这项数据仓库里没有。

---

## 二、确认成立的执行层问题

### 2.1 NativeDirect 的解复用同步跑在 codec/render pump 上

`pump()`（`AndroidNativeDirectYPlayer.kt:503`）在同一线程上依次执行
`drainAudio()` → `drainVideo()` → `feedInput()`，而 `feedInput()`（`:961`）
直接同步调用 `demux.readSample(sampleBuffer)`。用的是
`AndroidMediaExtractorDemuxNode`（`:379`），**没有** Enhanced 那条路的
`AndroidDemuxReadAheadNode` 独立线程（`AndroidEnhancedPlaybackSession.kt:117`）。
构造时传入 `onBlockingReadStateChanged`（`:381`）说明这个阻塞是已知的。

**准确表述**：预取命中时不阻塞；**预取未命中、seek 后、启动期、以及长尾请求时**，
同步读会挡住同一线程后续的 `drainVideo()`/`drainAudio()`，
此时解码器输出队列里已有的帧和 AudioTrack 里的 PCM 都无法推进。

这是真实的架构风险，但**定级需要真机数据**：`publishQoeSnapshot` 已经在上报
`maximumPumpMs`、`sourceSynchronousLoads`、`sourceMaximumLoadMs`（`:1337-1380`），
读这三个字段就能判断它在实际片源上发生的频度。

即便如此，我仍认为把解复用移出 pump 比继续扩大缓存更根本：
缓存只能降低命中失败的概率，移出 pump 才能让命中失败不再冻结已解码的画面。

### 2.2 单 pump 导致音视频互相阻塞

`feedInput()` 每次只读一个 sample；视频解码器输入满时返回 `TryAgain`
→ `if (queued != YCodecQueueResult.Queued) return false`（`:1027`），
`demux.advance()` 不执行 → 音频样本也停止供给。
Exo 用 per-track `SampleQueue`，一路解码器打嗝不会饿死另一路。

### 2.3 ABR 的缓冲信号是墙钟估算

`HlsAbrSession` / `DashAbrSession`（`AndroidYCoreHttpProxy.kt:122-240`）的
`bufferedDurationUs` 不是播放器真实缓冲，是模型量：起始假定
`STARTUP_BUFFER_US = 10s`，分片下完就加，按墙钟减。

| 场景 | 模型行为 | 真实情况 |
| --- | --- | --- |
| 用户暂停 | 墙钟继续排空到 0 | 缓冲是满的 |
| 恢复播放 | `< LOW_BUFFER_US(2s)` → `return eligible.first()` | 应保持当前档 |
| 2x 倍速 | 仍按 1x 排空，高估缓冲 | 实际排空快一倍 |
| seek | 完全不建模 | 缓冲清空 |

`YAdaptiveBitrate.kt:185` 的 `if (bufferedDurationUs < LOW_BUFFER_US) return eligible.first()`
是**直接跳最低档**而不是降一档，所以一次暂停恢复就可能掉到最低画质。

根因是架构位置：ABR 在 loopback 代理里，真实缓冲
（`demux.transportQoeSnapshot()`、`AndroidDemuxReadAheadNode` 水位）在播放器里，
两者之间没有回传通道。

**建议**：开一条 `AbrFeedback` 接口，由 `AndroidAdaptiveCore2YPlayer` 在
`publishClockPosition()` 时上报 `{realBufferedUs, playing, speed, generation}`；
`drainBuffer` 只在 `playing` 时按 `speed` 排空，seek 时显式清零。

### 2.4 带宽估计的三个偏差

1. **并发预取各自独立计时** — `AndroidTransportMediaDataSource.kt:343` 在每块加载完成时
   `onNetworkSample(total, elapsed)`，但池是 `MAX_TRANSPORT_PREFETCH_CONCURRENCY = 8`。
   并发时墙钟互相重叠，EWMA 得到单连接吞吐而非聚合带宽，系统性低估。
2. **顺序服务路径把本地反压算成网络耗时** — `AndroidYCoreHttpProxy.kt:1029`
   `serveSequential` 的样本区间包含写 loopback socket 的时间，
   而那被下游按播放速率反压，测出的接近码率而非链路带宽。
3. **单条 EWMA 无百分位窗口** — `YAdaptiveBitrate.kt:118`
   `previousWeightPermille = 700`。Exo 的 `DefaultBandwidthMeter` 用滑动加权中位数，
   对 CDN 突发鲁棒得多。

冷启动 `INITIAL_BANDWIDTH_BITS_PER_SECOND = 25_000_000L`（`:1387`）偏乐观，
配合被谎报为 10 s 的起始缓冲，首片会按较高档拉取。

### 2.5 Dolby Vision 配置就地破坏共享 MediaFormat

`configureDolbyVisionDecoder`（`AndroidMediaCodecVideoNode.kt:216-217`）用
`variant.applyTo(format)` 在**调用方持有的** `MediaFormat` 上直接
`removeKey("csd-2")` / `removeKey(KEY_PROFILE)`，而
`configureVideoDecoder`（`AndroidNativeDirectYPlayer.kt:1708`）每次都复用同一个
缓存的 `videoFormat` 对象。

两层污染：

- **当前循环内**：`WithoutCsd2` 变体删掉 csd-2 之后，
  该变体下**后续每个候选解码器**拿到的都已经是被削过的 format，
  即使某个候选"成功"，也是在降级配置下成功的。
- **跨次重配**：`videoFormat` 被永久破坏。之后的旋转、回前台、
  `retryEmptyTailSeek` 重配时，`dolbyVisionConfigureVariants(hasCsd2 = …)`
  只会返回 `[Exact]`，而 DV 配置记录已经不在了。

同一模式也存在于非 DV 路径：`configure()` 对安全播放做
`format.setFeatureEnabled(FEATURE_SecurePlayback, true)`（`:116`）同样是就地修改。

**修法**：每次 configure 尝试前 `MediaFormat(format)` 拷贝一份再改。

### 2.6 >8 声道回落 stereo mask 但不 downmix

`channelMaskForCount`（`AndroidAudioTrackRenderNode.kt:329-343`）的
`else -> CHANNEL_OUT_STEREO`。解码器输出 12 声道（7.1.4）PCM 时，
AudioTrack 按 stereo 建立，而 `write()` 原样写入交错 PCM——
**既不报错也不 downmix**，按错误声道解释播放。
Exo 的 `Util.getAudioTrackChannelConfig` 覆盖到 12 声道，不支持时返回
`CHANNEL_INVALID` 明确失败。

**修法**：要么 fail-closed（明确报不支持并交由上层降级），要么真正做 downmix。
静默错误比明确失败更难排查。

### 2.7 纯音频内容无法播放

- `AndroidNativeDirectYPlayer.kt:585` — `no video track` → `YPlaybackException(Container)`
- `AndroidEnhancedPlaybackSession.kt:221` — `Enhanced demux contains no video track`

native-only 制品下无兜底，音乐、有声书、纯音频版本直接报错。
默认构建会降级到 mpv。是否要补，取决于产品是否承诺纯音频。

### 2.8 Surface 销毁导致音频一起停

`Core2SurfaceView.surfaceDestroyed` → `player?.setVideoOutput(null)`（`Core2Surface.kt:325`）
→ NativeDirect `setSurface(null)`（`:743`）释放视频解码器并
`pausePlaybackInternal(keepRequested = true)`；而 `canPump`（`:475`）要求
`videoConfigured && surface.isValid`。Enhanced 更彻底，直接关闭整个 session。

Exo 的 `clearVideoSurface()` 只停视频渲染器，音频继续。
影响：切后台听声音、锁屏播放、PiP 过渡。同样取决于产品承诺。

**建议**：`canPump` 拆成 `canPumpAudio` / `canPumpVideo`，无 Surface 时保留音频与解复用推进。

### 2.9 Surface 重建代价

`setSurface` 的 `setOutputSurface` 快路径（`:777`）要求
`videoConfigured && previous?.surface?.isValid == true`，
而 `setSurface(null)` 已经把 `videoConfigured` 置 false，
所以跨 null 的销毁→重建走不到快路径，只能
`configureVideoDecoder` + `seekTo(resumeUs)`（回关键帧 + 重缓冲）。
这是 2.8 的同一处代码，一并修。

### 2.10 `max-input-size` 未补齐（未复现，不作为确定 Bug）

`AndroidMediaFormatFactory.video()/audio()` 不设置
`MediaFormat.KEY_MAX_INPUT_SIZE`（`AndroidMediaFormatFactory.kt:18-57`），
而 `queueAccessUnit` 用 `require(size <= input.remaining())`
（`AndroidMediaCodecVideoNode.kt:322`）在超限时抛 `IllegalArgumentException`。

Exo 的 `MediaCodecVideoRenderer.getCodecMaxInputSize()` 会主动算一个下界写入 format。
YCore 依赖平台默认分配。

**准确表述**：这是一处未补齐的防御，**在超大 access unit 上可能失败**；
但平台默认分配在多数设备上是够的，**在 4K HDR 高码率样本上复现之前不应写成确定缺陷**。

---

## 三、其余对照面缺口

| 项 | YCore | 说明 |
| --- | --- | --- |
| 音频 offload | ❌ 纯 `MODE_STREAM` | 待机功耗高于 Exo 的 offload 路径 |
| audio session id | ❌ `buildAudioTrack` 不带 | 响度增强/均衡器无法挂载；`PLAYER_CONVERGENCE` 已承认这些走 mpv |
| MediaCodec 异步回调 | ❌ 全同步轮询 | `dequeueOutput()` 每帧 `new BufferInfo`（`:376`），渲染热路径分配 |
| `KEY_ROTATION` 透传解码 format | ❌ 仅 Vulkan 路径读（`AndroidVulkanVideoOutput.kt:123`） | |
| 直播 / DVR / 时移 | ❌ | 功能面扩展 |
| DVD-Video | ❌ | 授权原因，见 YCORE2_ARCHITECTURE |
| ASS 特效 | ⚠️ | libass 已打包（`build.gradle.kts:276`），但 `YTextSubtitleParser.parseAss` 的 `stripAssOverrides` 会把 `\pos`/`\move`/`\k` 拍平。需确认内嵌与外挂两条路都进 libass |

### 与既有文档的关系

`YCORE_GAP_REVIEW.md` 的 P0-A（判据读中文文案）和 P0-B（宽限窗口不重置）
针对 Legacy 的 `PlaybackRuntimeFaultDetector`。Core2 的路线降级用的是结构化的
`YPlaybackFailureCategory`/`Stage`，**那两个缺陷没有被带进 Core2**。这一点做对了。

---

## 四、修订优先级

> **r3 进度**：第 1 项已实施（见「已修复」）。第 2–6 项未动。

1. ~~**DV `MediaFormat` 拷贝副本** + **>8 声道 fail-closed 或真 downmix**（2.5、2.6）~~
   —— 已修复，另补了 `max-input-size` 兜底（2.10）。
2. **ABR 接真实 buffered position**，显式处理 pause / seek / speed（2.3）。
3. **把 NativeDirect 解复用彻底移出 pump**（2.1）
   —— 比继续扩大缓存更根本；缓存只降低未命中概率，移出 pump 才能让未命中不冻画面。
4. **按产品承诺补纯音频与后台音频**（2.7、2.8、2.9）。
5. **真机量化后再决定是否扩大缓冲**（1.1）
   —— 现有证据不支持"缓冲不足"的判断；只有高码率原盘的 12 块上限值得单独看。
6. **`max-input-size`** 在 4K HDR 样本复现后再定级（2.10）。

### 不建议现在做

- **GPU/软解路线继续加深**：`GpuEnhanced` 的 Vulkan 证据门未过（YCORE2_ARCHITECTURE Phase 7），
  在 2.1 / 2.3 之前投入这里收益不会被感知。
- **对外宣称「更稳 / 更省电 / HDR 更好」**：`YCoreNativeBaselineRequirements`
  要求 1000 次 seek、1000 次 Surface 重建、4 台真机 × 3 芯片族、8 h + 24 h 长稳；
  `evaluateYCoreNativeBaseline` 是 fail-closed 的，缺证据返回 `NotMeasured`。

---

## 五、关于「差异化」的更正

初版列了三条"Exo/mpv 结构上做不到"的差异化。经核对，**两条不成立，一条降级为待验证**：

| 初版主张 | 更正 |
| --- | --- |
| 首帧预热 / 预拉 GOP 是 Exo 做不到的 | **不成立。** Media3 已有 `DefaultPreloadManager`，可把数据预加载进 `SampleQueue`。YCore 的 `AndroidRuntimeCapabilityRegistry` 设备能力记忆（`AndroidNativeDirectYPlayer.kt:1730,1732,1750`）可以减少错误的解码器尝试，这是**增量优势，不是独占能力** |
| ABR 复用 `PlaybackPerformanceMemory` 的 30 天吞吐基线 | **不成立。** `PlaybackPerformanceRecord`（`PlaybackPerformanceMemory.kt:6-14`）只有 `averageStartupMs`、`averageRebufferEventsPerMinute`、`averageDroppedFramesPerMinute`。30 天 TTL 在，**吞吐字段根本不存在**。要做得先加字段，那是新工作不是复用 |
| 双实例交叠交接是 mpv/Exo 结构上不可能的 | **降级为待验证实验。** 值得试，但不是"结构上不可能"，且会遇到硬解实例数上限、secure codec 独占、Surface 交接时序、内存峰值四类问题。在这些问题被真机验证之前，不应当作确定性优势写进规划 |

**结论**：Core2 目前没有已证实的、对照面结构上拿不到的优势。
可信的差异化只能来自把第四章 1–4 项做扎实之后的端到端指标，
而不是来自架构叙事。

---

## 六、待验证清单

| # | 待验证项 | 依据的数据 |
| --- | --- | --- |
| 1 | 2.1 该不该升 P0 | 真机 `maximumPumpMs`、`sourceSynchronousLoads`、`sourceMaximumLoadMs`、rebuffer 次数 |
| 2 | 1.1 的 12 块上限是否构成瓶颈 | 50–100 Mbps 原盘片源的 rebuffer 数据 |
| 3 | 2.10 `max-input-size` | 4K HDR 高码率样本上复现 `IllegalArgumentException` |
| 4 | 2.5 DV format 破坏的实际表现 | 首次配置会降级的 DV 设备：播放 → 旋转 → 看诊断面板 `dynamicRange` 是否掉档 |
| 5 | 2.6 声道映射 | 一条 7.1.4 音轨 |
| 6 | ASS 两条路径是否都进 libass | 带 `\pos`/`\k` 的内嵌与外挂 ASS 各一 |

---

---

## 七、已修复（r3）

| 缺陷 | 修法 | 提交 |
| --- | --- | --- |
| 2.5 DV 配置就地破坏共享 `MediaFormat` | `configure()` 与每个候选解码器各自拿到 `copyForCodecAttempt()` 的副本；变体的 `applyTo` 作用在副本上，调用方缓存的 `videoFormat` 不再被削。API 29 用 `MediaFormat` 拷贝构造，更低版本本就不会移除键 | 见下 |
| 2.6 >8 声道静默降 stereo | `channelMaskForCount` 补齐 API 32 的 10/12 声道布局，其余返回 `CHANNEL_INVALID`；`buildAudioTrack` 显式拒绝而不是建一个会播出错乱声音的 stereo track | 见下 |
| 2.10 `max-input-size` 未补齐 | 下沉到两个 codec 节点（它们持有 `require(size <= input.remaining())` 守卫），Enhanced 与 NativeDirect 两条路一起覆盖。容器已给出的值优先；只在缺失时按 `w*h*3/(2*ratio)` 补一个下界 | 见下 |

**范围说明**：2.6 选择的是 fail-closed，不是 downmix。
真正的 N→2 下混需要按布局给系数，是另一件工作；当前改动把「静默播出错乱声音」
换成「明确失败并可被路由降级」，兼容构建下会交给 mpv，native-only 制品下是硬失败。
这是评审要求的两个选项之一，但**代价需要知情**：今天能出（错的）声音的 9 声道内容，
改后在 native-only 下不出声。

**验证状态**：本会话无法编译。`dl.google.com` 被代理策略拒绝（CONNECT 403），
Gradle 解析不到 Kotlin/AGP 插件，本地也没有 Android SDK 和发布密钥库。
新增的纯函数（`videoMaxInputSizeBytes`、`audioMaxInputSizeBytes`、`channelMaskForCount`）
已补单测，但**尚未执行**。编译、单测、ktlint 与签名均需走 CI。

## 修订记录

**r2 · 2026-09-03（评审后）**

推翻的初版结论：

- **传输层缓冲量级**：初版把 `MIN_TRANSPORT_BLOCK_BYTES = 256 KiB` 当成实际块大小，
  得出"3 MiB / 1.2 秒 / 网络抖 2 秒必卡"。实际块大小是 **2 MiB**
  （`YCachePlanner` 在 `mediaBitRate=0` 时取 `DEFAULT_READ_AHEAD_BYTES`，
  256 KiB 只是 `coerceAtLeast` 的地板），12 块上限 = 24 MiB，
  20 Mbps 下约 10 秒。整章重写为 1.1。
- **Enhanced 读前 3 秒**：那是节点构造默认值，打开远程媒体后由
  `YBufferController` 覆盖为 4/6/8/10/15 秒。改写为 1.2。
- **与 Exo 的 50 秒对比**：Media3 同时受 allocator 字节目标约束，
  秒数一对一比较无效。改写为 1.3。
- **三条差异化**：两条基于错误前提（Media3 已有 `DefaultPreloadManager`；
  `PlaybackPerformanceMemory` 没有吞吐字段），一条降级为待验证。整章重写为第五章。
- **`max-input-size`**：从"确定 Bug"降为"未补齐的防御，待复现"。
- **native-only 表述**：限定为 native-only 制品，仓库默认构建仍有 mpv 兜底。

维持的初版结论：2.1（同步 pump）、2.3（ABR 墙钟缓冲）、2.5（DV format 污染，
并按评审意见补充了「同一循环内后续候选解码器也被污染」）、2.6、2.7、2.8。
