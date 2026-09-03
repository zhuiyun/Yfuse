# YCore 自研内核对照审查 · 2026-09-03

审查范围：`com.yfuse.core2`（commonMain + androidMain）、`ycore-native`、
`AndroidYCoreHttpProxy` 自适应链路。
对照面：ExoPlayer / Media3、libmpv、MDK。
承接 [`YCORE_GAP_REVIEW.md`](YCORE_GAP_REVIEW.md) 与 [`YCORE_MATURITY_GAP.md`](YCORE_MATURITY_GAP.md)，
这两份文档描述的是 Legacy 三后端编排层；本文审查的是**已经接管生产 APK 的 Core2 执行层**。

> 前提：`release-notes.txt` 1.0.23 起生产运行时为 **native-only**，
> `AndroidCore2TrialFactory.create(nativeOnly = true)` 时 `fallbackRouteFactory = null`
> （`AndroidAdaptiveCore2YPlayer.kt:90`）。**没有 mpv/Exo/MDK 兜底**。
> 因此下文每一条"不支持"都不是降级，而是硬失败。

> 本次审查为静态代码审查。所有条目都给到 `文件:行`，可直接复核；
> 但没有真机运行结果，性能类判断标注为"推断"。

---

## 零、总体判断

Core2 的**策略层**（`strategy/`、`capability/`、`adaptive/` 解析器、`bitstream/`、`dolby/`）
质量很高：纯函数、可测、边界清楚，HLS/DASH 解析和 Dolby Vision 路由的严格程度超过多数第三方客户端。

差距集中在**执行层的三件事**上，而且都是结构性的，不是补 if 能解决的：

| # | 结构问题 | 后果 |
| --- | --- | --- |
| 1 | 缓冲深度只有 Exo 的 1/20 量级 | 弱网体感全面落后 |
| 2 | NativeDirect 把解复用、解码、渲染、音频写入放在同一个 pump 线程 | 网络抖动直接冻结画面 |
| 3 | ABR 跑在 loopback HTTP 代理里，拿不到播放器真实缓冲，只能用一个墙钟模拟量 | 暂停/倍速/seek 后画质错判 |

再叠加两个**功能空洞**：纯音频不可播、后台/息屏音频不可播。

---

## 一、功能面差距（对照 Exo / mpv / MDK）

### 1.1 缓冲深度（差距最大的一条）

| 层 | YCore 取值 | 位置 |
| --- | --- | --- |
| NativeDirect 解码器输入前瞻 | **1.5 s** | `AndroidNativeDirectYPlayer.kt:2332` |
| Enhanced 解复用读前高水位 | 默认 **3 s**（上限 30 s） | `AndroidDemuxReadAheadNode.kt:374,376` |
| Enhanced 解码器输入前瞻 | **1.5 s** | `AndroidEnhancedPlaybackSession.kt:1882` |
| 传输层预取 | 最多 **12 × 256 KiB = 3 MiB** | `AndroidTransportMediaDataSource.kt` 尾部常量 |

对照 ExoPlayer `DefaultLoadControl` 默认值：目标缓冲 50 s，起播 2.5 s，卡顿后恢复 5 s，
按内存而不是按秒封顶。mpv 的 `demuxer-max-bytes` 是百 MiB 量级。

**推断后果**：3 MiB 预取对一条 20 Mbps 直连原盘流约等于 **1.2 秒**。
家宽/移动网一次 2 秒抖动，Exo 无感，YCore 必然卡顿。这条不需要真机就能从量级上判定。

**建议**：把 `TARGET_TRANSPORT_PREFETCH_WINDOW_MS`（当前 10 s）真正兑现成"按实测吞吐动态计算块数"，
并把 `MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS` 从 12 提到按内存预算（例如 32–64 MiB）而不是按块数封顶。
解码器输入前瞻 1.5 s 可以保留（那是解码器队列，不是网络缓冲），但网络层必须加深。

### 1.2 NativeDirect 没有独立的解复用线程

`AndroidNativeDirectYPlayer.kt:379` 用的是 `AndroidMediaExtractorDemuxNode`，
`feedInput()`（`:961`）在 pump 线程上同步调用 `demux.readSample(sampleBuffer)`。
Enhanced 路线有 `AndroidDemuxReadAheadNode` 独立线程（`:117`），**NativeDirect 没有**。

而 NativeDirect 是首选路线（大多数片源走它）。构造函数里传了
`onBlockingReadStateChanged`（`:381`）说明这个阻塞是已知的。

阻塞期间同一个 pump 线程无法执行 `drainVideo()` / `drainAudio()`，
所以**即使解码器输出队列里已经有解好的帧、AudioTrack 里还有 2 秒 PCM，
画面也会跟着网络读一起冻住**。Exo 的 Loader 线程与 playback 线程分离正是为了这一点。

**建议**：把 `AndroidDemuxReadAheadNode` 复用到 NativeDirect（它已经是 `YDemuxer` 通用装饰器），
这是收益/成本比最高的一次改动。

### 1.3 单 pump 导致音视频互相阻塞

`feedInput()` 每次只读一个 sample，且视频解码器输入满时返回 `TryAgain`
→ `if (queued != YCodecQueueResult.Queued) return false`（`:1027`），
`demux.advance()` 不执行 → **音频样本也停止供给**。

Exo 用 per-track `SampleQueue`，一路解码器打嗝不会饿死另一路。
这解释了为什么会出现"画面卡一下、声音也断一下"而不是"画面卡、声音继续"。

### 1.4 纯音频内容无法播放

两条 route 都硬失败：

- `AndroidNativeDirectYPlayer.kt:585-591` — `no video track` → `YPlaybackException(Container)`
- `AndroidEnhancedPlaybackSession.kt:221-227` — `Enhanced demux contains no video track`

native-only 包没有兜底，所以音乐、有声书、纯音频版本、以及任何视频轨探测失败的片源
都会直接报错。Exo/mpv/MDK 三个都支持纯音频。

### 1.5 后台 / 息屏音频丢失

`Core2SurfaceView.surfaceDestroyed` → `player?.setVideoOutput(null)`（`Core2Surface.kt:325-326`）
→ `AndroidNativeDirectYPlayer.setSurface(null)`（`:743`）执行：

```
videoDecoder.release(); videoConfigured = false
pausePlaybackInternal(keepRequested = true)   // pauseAudio()
```

而 `canPump`（`:475`）要求 `videoConfigured && surfaceOutput?.surface?.isValid == true`。
两者叠加 = **Surface 一销毁，整条流水线停摆，音频一起停**。

Exo 的 `clearVideoSurface()` 只停视频渲染器，音频继续；mpv 同理。
直接影响：切后台听声音、锁屏播放、PiP 过渡、通知栏控制。

**建议**：`canPump` 拆成 `canPumpAudio` / `canPumpVideo`；无 Surface 时保留音频与解复用推进，
视频侧只停 dequeue/release。这同时是 1.6 的前提。

### 1.6 Surface 重建代价过高

`setSurface` 里有 `videoDecoder.setOutputSurface(newSurface)` 快路径（`:777`），
但它的前置条件是 `videoConfigured && previous?.surface?.isValid == true`。
由于 `setSurface(null)` 已经把 `videoConfigured` 置 false 并释放了解码器，
**跨 null 的销毁→重建永远走不到快路径**，只能走：

```
configureVideoDecoder(newSurface)  →  seekTo(resumeUs)
```

`seekTo` 会 `demux.seekTo` 回到前一个关键帧、flush 解码器、`firstVideoFrameRendered = false`。
所以每次旋转/PiP/回前台 = 一次解码器重建 + 一次重缓冲。

### 1.7 ABR 的输入信号是虚构的

这是画质体验里最值得先修的一条。

`HlsAbrSession` / `DashAbrSession`（`AndroidYCoreHttpProxy.kt:122-240`）的
`bufferedDurationUs` **不是播放器真实缓冲**，而是一个模型量：

```kotlin
private var bufferedDurationUs = STARTUP_BUFFER_US   // 起始假定 10 s，实际是 0
fun complete(...) { bufferedDurationUs += durationUs }        // 分片下完就加
private fun drainBuffer(nowNs) {                             // 按墙钟减
    bufferedDurationUs -= (nowNs - updatedAtNs) / 1000
}
```

四个失真：

| 场景 | 模型行为 | 真实情况 |
| --- | --- | --- |
| 用户暂停 | 墙钟继续排空到 0 | 缓冲是满的 |
| 恢复播放 | `< LOW_BUFFER_US(2s)` → `return eligible.first()` | 应保持当前档 |
| 2x 倍速 | 仍按 1x 排空，高估缓冲 | 实际排空快一倍 → 会升档然后卡 |
| seek | 完全不建模 | 缓冲清空 |

`YAdaptiveBitrate.kt:185` 的 `if (bufferedDurationUs < LOW_BUFFER_US) return eligible.first()`
是**直接跳到最低档**而不是降一档。所以一次暂停恢复就可能掉到 240p，
再靠 `UPGRADE_BUFFER_US = 10s` + 125% headroom 一档一档爬回来。

根因是架构位置：ABR 在 loopback HTTP 代理里，播放器的真实缓冲
（`demux.transportQoeSnapshot()`、`AndroidDemuxReadAheadNode.bufferedDurationUs`）在播放器里，
**两者之间没有回传通道**。Exo 的 ABR 直接读 `LoadControl` 的真实缓冲。

**建议**：给代理开一条 `AbrFeedback` 回调接口，由 `AndroidAdaptiveCore2YPlayer` 每次
`publishClockPosition()` 时上报 `{realBufferedUs, playing, speed, generation}`；
`drainBuffer` 改为只在 `playing` 时按 `speed` 排空，或直接用上报值替换模型量。

### 1.8 带宽估计的三个系统性偏差

1. **并发预取各自独立计时** — `AndroidTransportMediaDataSource.kt:343` 在每个块加载完成时
   `onNetworkSample(total, elapsed)`，但预取池是 `MAX_TRANSPORT_PREFETCH_CONCURRENCY = 8`。
   8 路并发时每路的墙钟时间互相重叠，EWMA 得到的是**单连接吞吐**而非聚合带宽，
   系统性低估最多接近 8 倍 → ABR 长期压在低档。
2. **顺序服务路径把本地反压算成网络耗时** — `AndroidYCoreHttpProxy.kt:1029` 的
   `serveSequential` 用 `startedNs` 到写完 socket 的总时长做样本，
   而写 loopback socket 会被下游按播放速率反压。这个"带宽"约等于码率，不是链路带宽。
3. **单条 EWMA 无百分位窗口** — `YAdaptiveBitrate.kt:118` 的
   `YAdaptiveBandwidthEstimator(previousWeightPermille = 700)`。
   Exo 的 `DefaultBandwidthMeter` 用的是滑动加权中位数（0.5 分位、2000 样本窗口），
   对 CDN 突发和缓存命中鲁棒得多。

另外 `INITIAL_BANDWIDTH_BITS_PER_SECOND = 25_000_000L`（`:1387`）冷启动过于乐观：
配合被谎报为 10 s 的起始缓冲，第一个分片会按最高档拉取，弱网下直接拖长起播。

### 1.9 MediaFormat 未设 `max-input-size`

`AndroidMediaFormatFactory.video()` / `audio()` 全程不设置 `MediaFormat.KEY_MAX_INPUT_SIZE`
（`AndroidMediaFormatFactory.kt:18-57`；全仓仅 `AndroidMediaExtractorDemuxNode.kt:424` 读取，无人写入）。

而 `AndroidMediaCodecVideoNode.queueAccessUnit` 直接：

```kotlin
require(size <= input.remaining()) { "Encoded access unit ($size bytes) exceeds ..." }
```

（`AndroidMediaCodecVideoNode.kt:322`）。输入缓冲不足时抛 `IllegalArgumentException`，
沿 pump 冒泡成播放失败，而不是重配解码器。

Exo 的 `MediaCodecVideoRenderer.getCodecMaxInputSize()` 会按分辨率/编码算一个下界并写入 format，
正是为了 4K HDR 大 IDR 帧。这条在高码率原盘上是可复现的失败源。

### 1.10 声道映射对 >8 声道静默降级

`AndroidAudioTrackRenderNode.channelMaskForCount`（`:329-343`）的 `else -> CHANNEL_OUT_STEREO`。
解码器输出 12 声道（7.1.4）PCM 时，AudioTrack 按 stereo 建立，
写入的交错 PCM **不会报错，会以错误的声道解释播放**（声音错乱 / 速度异常）。

Exo 的 `Util.getAudioTrackChannelConfig` 覆盖到 12 声道，不支持时返回 `CHANNEL_INVALID` 明确失败。
静默错误比明确失败更难排查。

### 1.11 其余对照面缺口

| 项 | YCore | Exo | mpv |
| --- | --- | --- | --- |
| 音频 offload（省电待机） | ❌ 纯 `MODE_STREAM`，无 `setOffloadedPlayback` | ✅ | — |
| audio session id / 响度增强 / 均衡器 | ❌ `buildAudioTrack` 不带 session id | ✅ | ✅ |
| MediaCodec 异步回调模式 | ❌ 全同步轮询 | ✅ | — |
| `KEY_LOW_LATENCY` / `KEY_OPERATING_RATE` / `KEY_PRIORITY` | ❌ | ✅ | — |
| `KEY_ROTATION` 透传到解码 format | ❌ 仅 Vulkan 路径读（`AndroidVulkanVideoOutput.kt:123`） | ✅ | ✅ |
| 直播 / DVR / 时移 | ❌ | ✅ | ✅ |
| DVD-Video | ❌（授权原因，见 YCORE2_ARCHITECTURE） | — | ✅ |

`PLAYER_CONVERGENCE_20260830.md` 已经承认"音量增强 / 夜间人声压缩 = MPV 执行路径"，
在 native-only 包里这等于**这两个功能不存在**。

### 1.12 ASS 字幕的两条路径需要对齐

libass 0.17.4 已打包（`composeApp/build.gradle.kts:276`，`FfmpegNativeBridge.assRendererAvailable`）。
但 `YTextSubtitleParser.parseAss`（`YTextSubtitleParser.kt:60`）走的是
`markup.stripAssOverrides()` —— 把 `\pos` `\move` `\k` `\fad` 等特效**拍平成纯文本**。

需要确认外挂 ASS 与内嵌 ASS 两条路都进 libass；任何一条落到 `YTextSubtitleParser`，
特效字幕（尤其是番剧 OP/ED 卡拉 OK）就会退化成静态文本。

---

## 二、Bug 清单

按"用户可感知程度 × 定位确定性"排序。所有条目均可按 `文件:行` 直接复核。

### P0

| # | 位置 | 缺陷 |
| --- | --- | --- |
| 1 | `AndroidNativeDirectYPlayer.kt:475` + `Core2Surface.kt:325` | Surface 销毁即全流水线停摆，**音频一起停**。后台/息屏/PiP 无音频 |
| 2 | `AndroidNativeDirectYPlayer.kt:585`、`AndroidEnhancedPlaybackSession.kt:221` | 纯音频片源硬失败；native-only 无兜底 |
| 3 | `AndroidYCoreHttpProxy.kt:128,181,194,232` | ABR 缓冲量按墙钟排空，暂停/倍速/seek 三种场景全部失真 → 无谓掉到最低档 |
| 4 | `AndroidMediaFormatFactory.kt:18` + `AndroidMediaCodecVideoNode.kt:322` | 未设 `KEY_MAX_INPUT_SIZE`，大 IDR 帧抛异常终止播放 |

### P1

| # | 位置 | 缺陷 |
| --- | --- | --- |
| 5 | `AndroidMediaCodecVideoNode.kt:216-217` + `AndroidNativeDirectYPlayer.kt:1708` | `configureDolbyVisionDecoder` 用 `variant.applyTo(format)` **就地删除调用方缓存 `videoFormat` 的 `csd-2` 和 `KEY_PROFILE`**。一旦发生过一次降级配置，后续每次重配（旋转、回前台、`retryEmptyTailSeek`）都拿不到 DV 配置记录，`dolbyVisionConfigureVariants` 也只会返回 `[Exact]`。同一问题存在于 `configure()` 里对安全播放的 `setFeatureEnabled(FEATURE_SecurePlayback, true)`（`:116`）。**修法：configure 前 `MediaFormat(format)` 拷贝一份再改** |
| 6 | `AndroidNativeDirectYPlayer.kt:743-790` | 跨 null 的 Surface 重建永远走不到 `setOutputSurface` 快路径，必然解码器重建 + seek 回关键帧 |
| 7 | `AndroidAudioTrackRenderNode.kt:342` | >8 声道静默降为 stereo，PCM 按错误声道播放而不报错 |
| 8 | `AndroidTransportMediaDataSource.kt:343` | 8 路并发预取各自独立计时，带宽被系统性低估 |
| 9 | `AndroidYCoreHttpProxy.kt:1017-1032` | `serveSequential` 的带宽样本包含本地 socket 反压时间，测出的是码率不是带宽 |
| 10 | `AndroidNativeDirectYPlayer.kt:1027` | 视频解码器输入满时连带停止音频供给（无 per-track 队列） |

### P2

| # | 位置 | 缺陷 |
| --- | --- | --- |
| 11 | `YAdaptiveBitrate.kt:185` | 缓冲低于 2 s 直接跳最低档，而非降一档 |
| 12 | `AndroidYCoreHttpProxy.kt:1387` | 冷启动带宽假定 25 Mbps，弱网首片拉最高档拖长起播 |
| 13 | `AndroidNativeDirectYPlayer.kt:1385-1398` | `monotonicPositionFloorUs` 是单调地板：音频时钟一次向前跳变会被永久固化，之后真实位置再也拉不回来，且会让 `videoFrameReleaseDecision` 的 `masterPositionUs` 偏大、帧提前释放 |
| 14 | `AndroidMediaCodecVideoNode.kt:376` | `dequeueOutput()` 每次 `new MediaCodec.BufferInfo()`；这是每帧调用的渲染热路径，无谓 GC 压力 |
| 15 | `AndroidAudioTrackRenderNode.kt:133-138`、`AndroidEncodedAudioTrackRenderNode.kt:132-137` | `WRITE_BLOCKING` 循环里 `if (written == 0) continue` 是无退避空转。当前生产路径只用 `writeNonBlocking`，属潜在缺陷，但一旦被接线就是 100% CPU 忙等 |

### 与既有文档的关系

`YCORE_GAP_REVIEW.md` P0-A（判据读中文文案）和 P0-B（宽限窗口不重置）针对的是
Legacy 的 `PlaybackRuntimeFaultDetector`。Core2 的对应机制是
`AndroidAdaptiveCore2YPlayer` 的路线降级，用的是结构化的 `YPlaybackFailureCategory`/`Stage`，
**那两个缺陷没有被带进 Core2**。这一点做对了。

---

## 三、怎么让自研内核比其他内核体验更好

补齐差距只能追平。真正能"更好"的差异化，来自 Core2 已经握有而 Exo/mpv 结构上拿不到的三样东西。

### 3.1 先把三条地基补上（否则谈不上"更好"）

| 顺序 | 事项 | 理由 |
| --- | --- | --- |
| 1 | `canPump` 拆分音/视频，Surface 无效时保留音频 | 后台听声音是基础预期，当前是功能缺失 |
| 2 | NativeDirect 接入 `AndroidDemuxReadAheadNode` | 复用现成组件，一次改动消除"网络抖动冻画面" |
| 3 | 网络预取按内存预算而非 12 块封顶 | 把 3 MiB 提到几十 MiB，弱网体感一次到位 |
| 4 | ABR 用播放器真实缓冲替代墙钟模型 | 消除暂停/倍速/seek 后的画质误判 |
| 5 | `MediaFormat` 写 `max-input-size` + configure 前拷贝 format | 消除高码率原盘与 DV 重配两类硬失败 |
| 6 | 纯音频路径 | 音乐/有声书当前完全不可播 |

### 3.2 三个 Exo/mpv 结构上做不到的差异化

**(a) 首帧秒开 —— 用 `AndroidRuntimeCapabilityRegistry` 把探测提前到点击之前**

Core2 已经有 `recordConfigured/recordRejected/recordRendered` 的设备本地能力事实
（`AndroidNativeDirectYPlayer.kt:1731,1733,1749`），并按"7 天窗口内 3 次观察"固化。
Exo 每次起播都要重新协商解码器；YCore 已经知道**这台设备上这个能力签名一定用哪个解码器**。

把这条记忆用在**详情页停留时**：预解析容器、预配 MediaCodec、预拉首个 GOP。
点播放时首帧已经在手。这是"比 Exo 快"而不是"追平 Exo"的唯一可信路径，
而且不需要新增网络协议，Emby/Jellyfin/Plex 三家通用。

**(b) 交接零缝 —— 把 `YPlayer` 的 handover 快照升级成"双实例交叠"**

当前换路线/换集是"释放旧实例 → 构建新实例 → seek 到位置"，
所以每次都有一次黑场 + 重缓冲。Core2 拥有完整的图（`PlaybackGraph`）所有权，
可以在旧实例还在渲染时就构建新实例、预热到首帧就绪，然后在一次
`setOutputSurface` 里切过去。

mpv 做不到（单实例强绑 vo），Exo 的 `MediaSource` 拼接也做不到跨解码器配置的无缝切换。
这直接把 1.6 的"旋转/PiP 重建"和"切集黑场"两个问题一起解决，
并且是用户每天都能感觉到的差别。

**(c) 让 QoE 记忆真正闭环 —— ABR 记住"这台设备在这个服务器上的历史吞吐"**

`YAdaptiveBandwidthEstimator` 每次开播从零（或 25 Mbps 猜测）开始。
但 `PlaybackPerformanceMemory` 已经有 30 天滚动基线的隐私干净模式。
把"服务器 + 网络类型"维度的历史吞吐分位数持久化，作为 ABR 的初值和上界，
起播档位第一次就是对的。Exo 只能用编译期常量或全局默认。

### 3.3 不建议现在做的

- **软解/GPU 路线继续加深**：`GpuEnhanced` 的 Vulkan 证据门还没过（YCORE2_ARCHITECTURE Phase 7），
  在 1.5 s 缓冲和单线程 pump 没解决之前投入这里，收益不会被用户感知。
- **直播 / DVR**：功能面扩展，优先级低于上面六条地基。
- **对外宣称"更稳/更省电/HDR 更好"**：`YCoreNativeBaselineRequirements`
  要求 1000 次 seek、1000 次 Surface 重建、4 台真机 × 3 芯片族、8 h + 24 h 长稳。
  `evaluateYCoreNativeBaseline` 是 fail-closed 的，缺证据返回 `NotMeasured`。
  这些数据没拿到之前，任何稳定性声明都不成立 —— 这正是那份门禁写下来要防的事。

---

## 四、验证缺口

以下每条都无法由单测替代，必须真机产出证据：

1. **1.1 的缓冲深度结论是量级推断**，需要在 20 Mbps 直连原盘 + 人为 2 s 网络抖动下，
   对比 YCore 与 Exo 的卡顿次数。
2. **1.2 的"阻塞读冻结画面"** 需要在 pump 线程上采样，确认 `readSample` 的最大耗时
   （`maximumPumpMs` 已经在 `publishQoeSnapshot` 里上报，只需读日志）。
3. **P0-4 的 `max-input-size`** 需要一段 4K HDR 高码率片源复现 `IllegalArgumentException`。
4. **P1-5 的 DV format 破坏** 需要一台首次配置会降级的 DV 设备，
   播放 → 旋转 → 检查诊断面板的 `dynamicRange` 是否从 DV 掉成 HDR10/SDR。
5. **1.10 的声道映射** 需要一条 7.1.4 音轨。
