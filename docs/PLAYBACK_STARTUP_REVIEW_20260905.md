# 播放链路起播速度审查 · 2026-09-05

基线：`master` @ `51d3979`（1.0.28 制品）+ 分支 `claude/code-quality-review-wdl12g`。
证据：诊断包 `Yfusediagnostics20260905073757`（OPPO PLG110 / Android 16）中 17:07 那次
**成功**播放的事件时间线，与 `com.yfuse.core2` / `feature.player` 源码逐段对照。

---

## 一、实测起播时间线

片源：MKV / HEVC / Dolby Vision P5 / 3 条 AAC 音轨 / 20.8 Mbps / 6.8 GB，DirectPlay。
从进入播放页到画面动起来 **15.5 秒**，其中引擎侧（`engine_attached` → `position_verified`）
12.3 秒，与 `runtime_health.startuptimems = 10004` 一致。

| 相对时刻 | 事件 | 段耗时 | 成因（代码） |
| --- | --- | --- | --- |
| 0.00 | `activity_launched` | | |
| 0.12 → 1.83 | `playback_info_request` → `response` | **1.7 s** | 服务器 RTT（该服务器整晚 530/401 抖动） |
| 1.83 → 3.05 | `playback_negotiated` → `playback_route_selected` / `queue_ready` | **1.2 s** | `PlayerStore` 在出队列前串行加载剧集目录、系列 provider id，并 `awaitServerFallbacks()`（跨服务器资源对比） |
| 3.18 | `engine_attached`（YCore2Native） | | |
| 3.18 → 4.23 | 平台探测首个 range 打开（Cronet，2 次重定向） | **1.0 s** | 新建 `AndroidTransportMediaDataSource` + 新建 `AndroidAdaptiveHttpRouteState`，重定向结果不跨实例复用 |
| 4.23 → 6.46 | MediaExtractor 解析 + `readSourcePrefix` 512 KiB→4 MiB 找 dvcC + 24 个样本 NAL 扫描 → `matroska_dolby_config_recovered` | **2.2 s** | `AndroidCore2MediaProbe.probe`、`matroskaDolbyVisionConfigOrNull`、`probeDolbyVisionNals` |
| 6.46 → 11.57 | FFmpeg 深度探测（直连 URL 重新打开 + `avformat_find_stream_info` + 24 样本）+ 首次见到该解码器时的 `AndroidCodecSampleProbe`（2 s 预算） | **5.1 s** | `requiresEnhancedTruthProbe()` → `shouldRequestEnhancedProbe()` 对 **Matroska + HEVC 恒为 true** |
| 11.57 → 11.94 | `route_selected = NativeDirect` | 0.4 s | |
| 11.94 → 12.78 | 子播放器再次打开源（OkHttp，又 2 次重定向） | **0.85 s** | `NativeSession.prepareCurrent` 新建 demux 与 transport，不复用探测实例 |
| 12.78 → 14.58 | MediaExtractor 第二次解析（头 + 尾部 Cues）+ 解码器枚举 | **1.8 s** | 同上；MKV 的 Cues 在文件尾，每次解析都要一次尾部 range |
| 14.58 → 15.49 | 解码器启动 → 音频 PCM → `position_verified` | 0.9 s | 正常 |
| 15.98 | 第三次 `matroska_dolby_config_recovered` | | 下一集预载（`nextItemPreloadJob`）在起播 0.5 s 后开始，与正在播放的 20 Mbps 流争抢带宽 |

第二个片源（失败的那次）引擎侧从 attach 到 `route_selected` 同样是 7.9 秒，说明这是结构性的，
不是单次网络抖动。

---

## 二、结构性问题

### 2.1 同一个源被打开 3 到 4 次，每次都从零开始

平台探测、FFmpeg 深度探测、子播放器、下一集预载各自新建 transport 与 MediaExtractor /
FFmpeg 上下文。每次打开都付：TCP/TLS + 2 次重定向（~1 s）、头部解析、MKV 尾部 Cues 读取。
`AndroidAdaptiveHttpRouteState`（含 `redirectState`）在
`AndroidMediaExtractorDemuxNode.kt:360` 每次 `setDataSource` 内部新建，所以重定向缓存
从未跨实例生效，日志里每次 `transport_range_opened` 都是 `redirectcount = 2`。
磁盘块缓存（`AndroidYCoreBlockCache`）能省掉重复的字节下载，省不掉连接与解析。

### 2.2 FFmpeg 深度探测对每个 MKV/HEVC 无条件执行

`YProbeTruthPolicy.shouldRequestEnhancedProbe()`：`container ∈ {Matroska, MpegTs, M2ts}` 且
`videoCodec ∈ {H265, Av1}` 即返回 true。本例平台探测已经给出完整答案（AAC 音轨、dvcC 恢复出
P5 配置、NAL 扫描有 RPU），深度探测没有带来任何新事实，却花了整个起播的三分之一。
FFmpeg 侧还用默认 `probesize`/`analyzeduration`（5 MB / 5 s），并再读 24 个样本重复做
平台探测已经做过的 NAL 扫描。

### 2.3 探测与真正播放之间没有交接

`YCore2RouteDecision.probe` 只把"事实"（轨道、DV 配置）交给子播放器，MediaExtractor 实例、
已读的头/尾字节、已解析的轨道格式全部丢弃，子播放器重新来一遍（2.6 s）。

### 2.4 应用层把不必要的工作放在起播关键路径上

`PlayerStore` 在 `queue_ready` 之前串行等待：剧集目录、系列 provider id、
`awaitServerFallbacks()`。这些只服务于"下一集/换服务器"，当前集的引擎完全可以先启动。
PlaybackInfo 在进入播放页后才发，详情页停留期间没有预取。

### 2.5 下一集预载与当前播放争抢带宽

`nextItemPreloadJob` 在首帧后 0.5 s 启动，对下一集再做一整套探测（含 4 MiB 前缀 +
24 样本 + FFmpeg 打开）。在 20 Mbps 直播流刚开始填充读前缓冲时，这是最不该发生网络竞争的时刻。

### 2.6 已验证的路由没有被记住

`AndroidRuntimeCapabilityRegistry` 只记"解码器是否渲染过"（持久化），
`YPlaybackLearningEngine` 只记失败与性能。同一集再播、同一系列下一集，
所有探测重做；进程内 `probeCache`/`MAX_CACHED_ENHANCED_PROBES` 只有 4 条且不落盘。

---

## 三、优化方案（按收益 / 风险排序）

| # | 改动 | 预计节省 | 风险 |
| --- | --- | --- | --- |
| 1 | `shouldRequestEnhancedProbe` 改为"平台探测缺事实才探"：音轨已知、DV 配置已恢复、NAL 有 RPU 时跳过；保留 `audio == null`、`DolbyVision && config == null`、TS/M2TS 三种触发 | **~5 s** | 低。可用 `YCORE_VALIDATION_MATRIX` 的 MKV 语料回归 |
| 2 | 深度探测必须跑时：`probesize=2 MiB`、`analyzeduration=1 s`、`fflags=nobuffer`，复用平台探测的 NAL 证据而不再读 24 样本，并与 `AndroidCodecSampleProbe` 并行 | 1–3 s | 低 |
| 3 | 每个媒体身份一个 `YSourceSession`：共享 `redirectState`、块缓存与已知长度，探测、子播放器、代理都从它拿 transport；MKV 首次打开时顺手预取尾块 | ~2 s（两次重定向 + 一次尾部 range） | 中，触及 transport 生命周期 |
| 4 | 平台探测的 MediaExtractor 直接移交给 NativeDirect（或至少移交轨道格式 + 头/尾块），子播放器不再二次解析 | ~1.8 s | 中，需要处理探测与播放的线程归属 |
| 5 | `PlayerStore`：当前集 negotiated 后立即 `queue_ready`，剧集目录 / provider id / 跨服务器回退改为后台补齐；详情页进入即预取 PlaybackInfo（带 `PlaySessionId` 复用） | 1–3 s（取决于服务器） | 低 |
| 6 | 下一集预载延后到读前缓冲达标（`YBufferController` 健康）之后，且只做元数据探测，不读样本 | 不减少起播，但消除首 10 秒的 rebuffer 风险 | 低 |
| 7 | 把"媒体身份 → 已验证路由 + 解码器 + DV 配置"落盘（沿用 `AndroidRuntimeCapabilityRegistry` 的方式，只存能力签名），命中即跳过全部探测 | 重播 / 同系列 ~8 s | 低 |

前两项加起来能把这次 15.5 秒的起播压到 8 秒左右，只改策略层，不碰传输与解码；
第 3、4 项再把探测与播放合并为一次打开，目标是引擎侧 3 秒以内。

---

## 四、不建议做的

- 降低 `MINIMUM_SAMPLES_BEFORE_TIME_LIMIT`（8）或 3 s 初始读前：解码器选定到首帧只有 0.9 s，
  不是瓶颈，改它只会换来起播后立刻 rebuffer。
- 去掉 Dolby Vision 的 dvcC 恢复扫描：它是 P5/P8 走原生 DV 解码器的前提；应该做的是只扫一次并交接。

---

## 五、实施记录（2026-09-05，同分支）

| 方案 | 改动 | 文件 |
| --- | --- | --- |
| 1 深度探测只在缺事实时跑 | `shouldRequestEnhancedProbe` 增加 `hdrType/bitDepth/hintedHighDynamicRange`：Matroska 的 HEVC/AV1 只有在动态范围未定（10-bit 却报 SDR，或服务器标 HDR 而平台报 SDR）时才探；TS/M2TS、音轨缺失/未知、DV 配置缺失的触发保留 | `YProbeTruthPolicy.kt`、`AndroidEnhancedMediaProbe.kt`、`YProbeTruthPolicyTest.kt` |
| 2 深度探测限界并复用证据 | `YDemuxSource.probeOnly` → `FfmpegNativeBridge.open(probeOnly)` → 新 JNI 入口 `nativeOpenProbe`（`probesize=2 MiB`、`analyzeduration=1 s`、`nobuffer`；旧原生制品缺符号时回退到普通 open）；平台探测已见 RPU 时深度探测不再重读 24 个样本 | `ycore_demux_jni.cpp`、`FfmpegNativeBridge.kt`、`AndroidFfmpegDemuxer.kt`、`YDemuxer.kt`、`AndroidCore2MediaProbe.kt` |
| 6 下一集预载延后 | 首帧后至少等 15 s，且当前项不在缓冲、前向缓冲 ≥ 8 s 才开始评估下一集 | `AndroidAdaptiveCore2YPlayer.kt` |

未实施：3（共享源会话）、4（探测实例移交）、5（`PlayerStore` 出队列解耦 / PlaybackInfo 预取）、7（已验证路由落盘）。
`nativeOpenProbe` 的限界只有在重新打包 `ycore-native.aar` 后才生效，之前 Kotlin 侧会自动回退到旧入口。
