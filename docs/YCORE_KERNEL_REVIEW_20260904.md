# YCore 自研内核专项审查 · 2026-09-04

审查基线：`master` @ `51d3979`。范围：`com.yfuse.core2`（commonMain + androidMain）、
`ycore-native`、`scripts/native/ycore_demux_jni.cpp`。
承接 [`YCORE_KERNEL_COMPARISON_20260903.md`](YCORE_KERNEL_COMPARISON_20260903.md)，
该文列出的 4 个确定性缺陷本次复核**已全部修复**（复核表见
[`CODE_QUALITY_REVIEW_20260904.md`](CODE_QUALITY_REVIEW_20260904.md) §2.1），本文只讲新发现。

> 静态审查。Gradle 在本环境不可用；`ycore-native` 已用 cmake/ctest + ASan/UBSan 实测通过。
> 每条结论都给 `文件:行`，可直接复核。

---

## 零、结论

执行层的"大结构"是对的：四条路由都有独立读前线程、真实缓冲量反馈、内存预算封顶、
结构化失败分类、音频时钟主导的视频调度。上次审查后的修复没有引入新的架构性问题。

本轮确认的问题集中在**三个横切面**：

| # | 问题 | 性质 | 影响路由 |
| --- | --- | --- | --- |
| 1 | `updateState` 不是原子的，UI 线程与 pump 线程互相覆盖状态 | 确定性缺陷 | Direct / Enhanced / Tunnel / Adaptive |
| 2 | DRM 密钥续期的网络往返直接跑在 pump 线程上 | 确定性缺陷（仅 DRM 片源） | Direct |
| 3 | pump 采用 2 ms 轮询 + 非阻塞 dequeue，无 vsync 对齐 | 功耗与画面节奏 | Direct / Enhanced / Tunnel |

其余是拷贝链路、启动串行化、内存预算叠加和 C 协调器 ABI 的细节问题。

---

## 一、P1 · 确定性缺陷

### 1.1 状态更新不是原子的，两个线程互相覆盖

`AndroidNativeDirectYPlayer.kt:2668`

```kotlin
private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    value = transform(value)
}
```

这是 read-modify-write，不是 `MutableStateFlow.update {}` 的 CAS 循环。同一个定义在
`AndroidAdaptiveCore2YPlayer.kt:1545` 再出现一次；Enhanced/Tunnel 播放器共用同样的写法。
除此之外 `AndroidNativeDirectYPlayer` 内部还有 **25 处**裸的
`mutableState.value = mutableState.value.copy(...)`。

两个线程都在写：

- UI 线程：`play()/pause()/seekTo()/setSpeed()`（`:132-175`）先 `updateState` 再投递 Command；
- pump 线程：`publishClockPosition()`（`:1519`）先读 `currentState`，做一串快照计算，再写回
  `currentState.copy(...)`。

交错序列：

```
pump   : currentState = value            // playbackRequested=true, speed=1.0
UI     : value = value.copy(speed = 1.5) // setSpeed
pump   : value = currentState.copy(positionMs = …)   // speed 又回到 1.0
```

`play/pause/seek` 会被随后处理的 Command 再写一次状态，所以只是短暂闪回；
但 `setSpeed` 的 `updateSpeed()` 不重发状态，`state.speed` 会**一直**停在旧值，
直到下一次 UI 写入。同类问题也影响 `positionMs`（seek 后 UI 进度条回跳一帧）。

**修法**：把两处 `updateState` 改为 `update(transform)`，25 处裸写改为 `update {}`。
这是一次纯机械替换，没有语义风险。

### 1.2 DRM 密钥续期把网络往返放在 pump 线程上

调用链：`pump()`（`AndroidNativeDirectYPlayer.kt:563-568`）→
`drmSession.refreshKeysIfNeeded()`（`AndroidYCoreDrmSession.kt:124`，`@Synchronized`）→
`acquireStreamingKeys()`（`:158`）→ `postDrmRequest()`（`:231`）→ **`runBlocking { transport.open(...) }`**。

也就是说每次密钥状态检查触发续期时（`KEY_STATUS_CHECK_INTERVAL_MS` 周期或 MediaDrm 事件），
音频写入、视频 dequeue、解复用喂入全部停在一次许可证服务器往返上。
以 300–800 ms 的典型许可证延迟计，AudioTrack 会 underrun，`publishClockPosition` 随即记一次 rebuffer。

**修法**：续期在独立协程（`scope.launch(Dispatchers.IO)`）里做；pump 只检查
`pendingKeyRefresh` 的结果，失败时才进入 `fail()`。`refreshKeysIfNeeded()` 已经是
`@Synchronized`，把它拆成"是否需要"（同步、快）和"执行"（异步）两步即可。

---

## 二、P2 · 性能与体验

### 2.1 2 ms 轮询的 pump 循环

三条原生路由的驱动循环完全相同：

- `AndroidNativeDirectYPlayer.kt:297` `delay(PUMP_IDLE_DELAY_MS)`，`:2712` `PUMP_IDLE_DELAY_MS = 2L`
- `AndroidNativeEnhancedYPlayer.kt:751,902` 同上
- `AndroidNativeTunnelYPlayer.kt:466,510` 同上

配合 `MediaCodec.dequeueInputBuffer(0L)` / `dequeueOutputBuffer(info, 0L)`
（`AndroidMediaCodecVideoNode.kt:323,369,385`，`AndroidMediaCodecAudioNode.kt:89,123,138`）
和 `AudioTrack.WRITE_NON_BLOCKING`（`AndroidAudioTrackRenderNode.kt:158`），
这是一个纯轮询模型：没有工作时每 2 ms 醒一次，线程优先级是 `THREAD_PRIORITY_DISPLAY`
（`:2437`）。`delay()` 在单线程 `ExecutorCoroutineDispatcher` 上还要经过 `DefaultDelay`
线程再调度回来，每次空转是两次线程切换。

量级：24 fps 视频每 41.7 ms 才有一帧输出，其间约 20 次空转；暂停时同样 500 次/秒。
Media3 的 `ExoPlayerImplInternal` 空闲步进是 10 ms，且暂停时退到 1000 ms。

**修法**（按收益排序）：
1. 暂停 / 缓冲态把空转间隔提到 50–100 ms；
2. 有工作时用下一帧的 `desiredRenderNs` 反推可睡眠时长，而不是固定 2 ms；
3. 终极方案是 `MediaCodec.setCallback` 异步模式 + 条件变量唤醒 pump，`dequeue*` 不再空转。

### 2.2 视频释放时间不对齐 vsync

`drainVideo()`（`AndroidNativeDirectYPlayer.kt:1406-1434`）用音频时钟算出 `desiredRenderNs`，
经 `videoFrameReleaseDecision()`（`AndroidMediaCodecVideoNode.kt:51-72`）后直接
`releaseOutputBuffer(index, renderTimeNs)`。全仓 `core2` 没有 `Choreographer` / vsync 采样。

SurfaceFlinger 会把该时间戳之后的第一个 vsync 用来显示。24p 内容在 60 Hz 面板上，
理想是 3-2-3-2 的稳定拉锯；不对齐时释放时间落在 vsync 边界附近会随机变成 2-3-2-3 或
3-3-2-2，肉眼可见的节奏抖动。`AndroidFrameRateManager` 的帧率匹配能在支持的设备上消除这个问题，
但不支持无缝切换的设备（`YFrameRateSwitchMode.SeamlessOnly` 默认值）仍然靠 60 Hz 播 24p。

**修法**：仿 Media3 `VideoFrameReleaseHelper`：用 `Choreographer.FrameCallback` 采样 vsync 相位
和周期，把 `desiredRenderNs` 吸附到最近的 `vsync - vsyncOffset`。这是纯调度层改动，
不碰解码与 Surface。

### 2.3 Enhanced 路由每个压缩样本经过 3–4 次拷贝

链路：

1. FFmpeg `AVPacket` → JNI `memcpy` 到 Java direct buffer（`ycore_demux_jni.cpp:2134`）；
2. `AndroidFfmpegDemuxer.kt:148` `val data = ByteArray(size)` 再拷一次成堆数组；
3. `AndroidEnhancedPlaybackSession.kt:1088 transformVideoSample()` → `normalizeVideoSampleForMediaCodec`
   （`AndroidMediaFormatFactory.kt:214`）对 AV1 / Annex-B 转换再分配一次；
4. `queueAccessUnit` 拷进 MediaCodec 输入缓冲（不可避免）。

UHD 蓝光 remux（50–100 Mbps）下这是每秒 6–12 MB × 3 次的堆分配，每个数组 100–500 KB，
全部落入 ART 的 Large Object Space，触发的是较贵的 GC。`YCompressedSample.data: ByteArray`
（`YDemuxer.kt:125`）把这个设计定死了。

**修法**：`YCompressedSample` 改持有 `ByteBuffer`（direct）+ offset/length，
demuxer 维护一个环形 direct 缓冲池；`normalizeVideoSampleForMediaCodec` 对不需要转换的
样本原样返回（现在 AV1 和 Annex-B 之外的路径已经是 `return data`，只需把转换路径改成就地写入池）。
MediaCodec 的 `getInputBuffer().put(ByteBuffer)` 直接接受 direct buffer。

### 2.4 启动路径串行阻塞：外挂字幕

`prepareCurrent()`（`AndroidNativeDirectYPlayer.kt:604-645`）在打开解复用器后、配置解码器前，
对每个外挂字幕**顺序**调用 `externalSubtitleLoader.load()`，而
`AndroidExternalSubtitleLoader.readHttp()`（`:87`）是 `runBlocking` 的 HTTP 下载。
一集带 3 条 Plex 外挂字幕的剧，首帧要多等 3 个 RTT + 下载时间；任何一条字幕服务器慢，
整个起播就慢。

**修法**：字幕在独立协程并行加载，首帧不等它；已选中的那条到达后再挂到 `subtitleCues`。

### 2.5 传输层重试在 extractor 线程上 `Thread.sleep`

`AndroidTransportMediaDataSource.kt:337` 重试等待用 `Thread.sleep(delayMs)`，
`readAt()`（`:123`）是 `@Synchronized` 且会持锁到前台块取回为止（作者在 `:186-190`
的注释已经写明）。这意味着 seek 期间如果碰上一次 Range 失败，MediaExtractor 线程被钉在
sleep 里，`close()` 也要等它醒来。

**修法**：用 `ReentrantLock` + `Condition.awaitNanos()`，`close()` 时 `signalAll()` 立即打断。

### 2.6 每个播放器实例的内存预算是叠加的

| 组件 | 上限 | 位置 |
| --- | --- | --- |
| 传输层预取块 | 12 × 2 MiB = 24 MiB | `AndroidTransportMediaDataSource.kt:887` |
| Extractor 读前队列 | 24 MiB | `AndroidMediaExtractorReadAheadNode.kt:478` |
| 单样本容量 | 8 MiB | `:475` |
| 磁盘块缓存内存窗口 | 2 MiB | `YCoreDiscBlockSource.kt:186` |

单播放器约 50 MiB 上限；`AndroidAdaptiveCore2YPlayer` 的 `nextItemPreloadJob` /
`preloadedNextRoute`（`:342-343`）在预载下一集时会再开一份。对 2–3 GB 的电视盒子，
这与 4K 解码器自己的缓冲加起来接近 OOM 区。

**修法**：用一个进程级 `YMemoryBudget` 统一分配（传输 + 读前 + 预载共享一个上限），
预载只做探测和首块，不做完整读前。

### 2.7 软件回退路径的渲染走 `Bitmap` + `Canvas`

`AndroidSoftwareVideoRenderNode.kt:174-202`：`Bitmap.createBitmap(ARGB_8888)` →
`copyPixelsFromBuffer` → `lockHardwareCanvas` → `drawBitmap`。这是文档允许的"终极兼容路径"，
但两点值得改：

- 4K 帧 33 MB 的 `copyPixelsFromBuffer` 每帧一次，软解本已吃满 CPU；
- ARGB_8888 把 HDR 软解（10-bit HEVC 软解是这条路的主要用途）截到 8-bit。

**修法**：软解输出改走 `AndroidVulkanVideoOutput` 已有的 `ImageReader PRIVATE + HardwareBuffer`
链路（`AndroidVulkanVideoOutput.kt:28,220`），由 FFmpeg 直接写 `AHardwareBuffer`，
色深与色调映射交给现成的 Vulkan 渲染器。

---

## 三、P2 · C 协调器（`ycore-native`）

`ycore.cpp` 只有 490 行，是 Harmony 侧的后端选择/交接协调器。三个 ABI 层面的问题：

1. **监听器在持锁时回调**。`publish()`（`:97`）在所有 `std::lock_guard<std::recursive_mutex>`
   的 API 内被调用（`tick :359`、`open_next :180`…）。监听器若在另一线程等待任何 ycore API，
   就是死锁；递归锁只保护同线程重入。
2. **`ycore_session_state_engine/reason` 返回内部缓冲指针**（`:478-490`）。函数返回时锁已释放，
   调用方持有的指针可被下一次 `tick()` 覆盖，这是 ABI 级的数据竞争。应改为
   `int32_t ycore_session_state_engine(session, char *out, size_t cap)`。
3. **`tick()` 在持锁时调用引擎 `poll_state` vtable**（`:369`）。引擎实现若也要拿自己的锁，
   锁序要求就落到了文档之外。

`YCORE_ABI_VERSION 1u` + `struct_size/abi_version` 头（`ycore.h:23,92-93`）的版本化做法是对的，
以上修改可在 ABI 2 里一起做。

---

## 四、P3 · 其他

- 回环 HTTP 代理：`ServerSocket(0, 8, 127.0.0.1)`（`AndroidYCoreHttpProxy.kt:346`）+ 每路由
  UUID（`:416`）+ `newCachedThreadPool`（`:343`）。路由 id 不可猜，安全性足够；
  线程池无上限，任何本机进程扫到端口后可以用并发连接把它撑爆，加一个 `Semaphore(16)` 即可。
- `PUMP_IDLE_DELAY_MS`、`SLOW_PUMP_THRESHOLD_NS`、`LATE_FRAME_DROP_NS` 等调度常量在三个播放器里
  各自定义一份（`AndroidNativeDirectYPlayer.kt:2707-2715` 等），应集中到 `core2/render`。
- `AndroidNativeDirectYPlayer` 与 `AndroidEnhancedPlaybackSession` 有 30 个同名私有方法
  （`pump/drainAudio/drainVideo/feedInput/flushAudio/queueAudioSample/seekTo/selectAudioTrack/…`），
  与 `AndroidAdaptiveCore2YPlayer` 有 14 个；见总报告 P2-2。
- `AndroidMediaCodecVideoNode.configure()` 候选解码器失败时有 `release()`（`:157`），
  多候选尝试路径也有（`:261`）；无泄漏。
- `AndroidDemuxReadAheadNode.close()` 通过 `runOnOwner` 在唯一解复用线程上执行 `delegate.close()`
  （`:161-175`），JNI `native_close`（`ycore_demux_jni.cpp:1869`）不需要自己的锁；已核实无竞态。
- `AndroidYCoreBlockCache` 写入走 `.tmp` + `fd.sync()` + `renameTo`（`:133-137`），正确。

---

## 五、做得好的地方（不要在重构中弄丢）

- 音频时钟：`AudioTimestamp` 优先、`playbackHeadPosition` 兜底，经 `AndroidAudioClockProgressGuard`
  过滤倒退/停滞（`AndroidAudioTrackRenderNode.kt:169-195`）。
- 视频调度：`Hold / Drop / Render` 三态 + 首帧保底（`preserveFirstVideoFrame`），
  seek 预滚帧保留到目标位置（`seekPrerollVideoOutput`）。
- Surface 切换先试 `setOutputSurface`，失败才重建并等下一个 sync sample（`:853-928`），
  避免了 seek 共享 extractor 打断音频。
- 失败携带 `category/stage/safeDetail`，从不复制 `Throwable.message`（`fail()` 注释明确写了 URL 风险）。
- Tunnel 路由用 `HW_AV_SYNC` AudioTrack + `setOnFrameRenderedListener`，是正确的隧道模式做法。
- 传输层：共享 `OkHttpClient` 连接池、Cronet 单例引擎、块缓存原子提交。

---

## 六、优化顺序

| 顺序 | 项 | 工作量 | 风险 |
| --- | --- | --- | --- |
| 1 | 1.1 `updateState` → `update {}`，25 处裸写替换 | 半天 | 无 |
| 2 | 1.2 DRM 续期移出 pump 线程 | 1 天 | 低，只影响 DRM 片源 |
| 3 | 2.1 暂停/缓冲态空转间隔提高；按下一帧反推睡眠 | 1 天 | 低 |
| 4 | 2.4 外挂字幕并行、不阻塞首帧 | 1 天 | 低 |
| 5 | 2.5 传输层重试改可中断等待 | 半天 | 低 |
| 6 | 2.2 vsync 对齐 | 3 天 | 中，需真机 24p/60Hz 验证 |
| 7 | 2.3 `YCompressedSample` 改 direct buffer 池 | 1 周 | 中，触及 demux 契约 |
| 8 | 2.6 进程级内存预算 | 3 天 | 中 |
| 9 | 三条路由的 pump 循环收敛为一份（总报告 P2-2） | 2–3 周 | 高，需真机矩阵 |
| 10 | 2.7 软解输出改 HardwareBuffer | 1 周 | 中 |
| 11 | 三、C 协调器 ABI 2 | 2 天 | 低（Harmony 侧同步改） |

前 5 项加起来不到一周，全部是确定性修复或纯调度参数，建议在 CI 恢复绿色后作为第一批合入。

---

## 七、实施记录（同分支）

以下条目已在本分支实现（提交见 git log），**尚未经过 Gradle 编译与单元测试**（本环境不可用），
必须由阶段 0 恢复后的 CI 复核：

| 报告条目 | 改动 | 文件 |
| --- | --- | --- |
| 1.1 状态更新原子化 | 四个播放器的 `updateState` 改为 `MutableStateFlow.update`；NativeDirect 25 处、Enhanced/Tunnel/Adaptive 各 1 处裸 `value = value.copy()` 改为 `update { current -> … }` | `AndroidNativeDirectYPlayer.kt`、`AndroidNativeEnhancedYPlayer.kt`、`AndroidNativeTunnelYPlayer.kt`、`AndroidAdaptiveCore2YPlayer.kt` |
| 1.2 DRM 续期移出 pump | 新增 `AndroidYCoreDrmSession.pollKeyRenewal()`：回收/输出限制仍同步失败，许可证往返在 `YCore-DrmRenewal` 单线程执行器上进行，结果在下次轮询交回；pump 改调它 | `AndroidYCoreDrmSession.kt`、`AndroidNativeDirectYPlayer.kt` |
| 2.1 空转与命令唤醒 | 新增 conflated `wakeSignal`，命令入队即唤醒空闲循环；暂停态空转间隔 2 ms → 20 ms（`PUMP_PAUSED_IDLE_DELAY_MS`），播放态不变 | 三个原生播放器 |
| 2.4 外挂字幕并行加载 | `prepareCurrent` 内用 `async(Dispatchers.IO)` 并行下载全部 sidecar，失败语义不变 | `AndroidNativeDirectYPlayer.kt` |
| 2.5 传输层重试可中断 | `Thread.sleep` 改为 `CountDownLatch.await`，`close()` 立即释放 | `AndroidTransportMediaDataSource.kt` |

未实施（需要真机或更大改动）：2.2 vsync 对齐、2.3 样本缓冲池、2.6 内存预算、2.7 软解输出、三、C 协调器 ABI 2。

`Adaptive` 内两处 `mutableState.value = childState.copy(...)` 保留原样：它们是整份子播放器状态的投影，
不是 read-modify-write。

### 诊断增强（2026-09-05，针对 OPPO PLG110 诊断包）

诊断包 `Yfusediagnostics20260905073757` 显示 MKV/HEVC/Dolby Vision P5 片源在 NativeDirect
因平台解复用器未暴露音轨而失败，随后 FFmpeg 在 `avformat_open_input` 返回 `AVERROR_INVALIDDATA`，
软件回退被 Dolby 守卫拦下，native-only 制品无兼容内核，也未请求服务器转码。日志无法说明
FFmpeg 具体拒绝了什么，因此补了三处诊断：

| 改动 | 文件 |
| --- | --- |
| 原生 open 失败把阶段（disc_open / open_input / find_stream_info）与 AVERROR 幅值打包进状态码，Kotlin 解码为 `stage=… error=tag:INDA` 之类的安全标签；读/seek 路径的 -2/-3/-4 语义不变 | `scripts/native/ycore_demux_jni.cpp`、`FfmpegNativeBridge.kt`、`AndroidFfmpegDemuxerMappingTest.kt` |
| FFmpeg 深度探测失败不再静默，记 `enhanced_probe_failed`（仅类型化字段，不含 URL） | `AndroidEnhancedMediaProbe.kt` |
| NativeDirect 音轨缺失时记录服务器声明的音频编码与平台实际暴露的 MIME 列表 | `AndroidNativeDirectYPlayer.kt` |

仍待做：native-only 制品在内部路由全部失败后，缩小设备能力声明重新协商 PlaybackInfo，
让服务器只转码音频（AAC）、视频 copy，再走 NativeDirect。
