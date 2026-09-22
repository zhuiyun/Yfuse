# Android 起播速度与播放卡顿审计

检查日期：2026-09-08。源码基线：远程 master `0544e4da42968f95585943b282381421fe0acba4`。
范围：Android 手机/平板，自研 YCore（NativeDirect / NativeEnhanced）、ExoPlayer、MPV、可选 MDK。
方法：并行静态代码审计、调用链与已有测试核对、官方文档核对。未修改播放实现，未连接真机测速；下面的收益方向不是已经测得的性能提升。本地工作区落后基线，故所有源码链接固定到被检查的提交。

最值得先做的是移走首帧前的非必要等待、消除同步缓存写入、解除增强路径字幕对网络读线程的等待，并补齐 pump 异常处理。自研起播门槛已经约为 500 ms；继续压低它之前，应先处理这些额外等待。

## 优先处理的代码问题

| 优先级 | 位置与条件 | 已确认行为 | 建议 |
| --- | --- | --- | --- |
| P1 | 所有内核；存在备用服务器/剧集队列 | 当前源已准备后，发布 Ready 之前仍等待备用服务器；剧集还等待节目详情与完整剧集列表 | 先发布当前播放项，后台补齐队列和备用资源 |
| P1 | 自研；启用磁盘缓存 | 返回视频块前同步写盘、fsync、遍历并排序缓存；磁盘命中也可能重写 | 有界异步落盘，缓存命中不重写，增量维护淘汰索引 |
| P1 | 自研增强路径；原生内嵌字幕 | A/V pump 同步等待解复用线程处理字幕，而该线程可能正在阻塞网络读 | 在解复用线程预解字幕或异步交付字幕结果 |
| P1 | 自研增强路径；运行中出现异常 | session.pump 不在命令异常捕获范围内，异常可能终止工作协程且不发布 Failed | 对 pump 统一分类异常并进入现有恢复通路 |
| P2 | 自研；冷读/拖到未缓存位置 | 小范围读取也必须等待完整 2 MiB 块 | 元数据小探测或可读前缀机制，稳态继续大块预取 |
| P2 | 自研增强路径；再次播放已验证媒体 | 外层复用路由后，子播放器又不带缓存结果重新 evaluate | 一次性传递初始 decision/probe，恢复时再评估 |
| P2 | 自研；弱网断流/带宽骤降 | OkHttp 重试整块重下；带宽中位数下降响应偏慢 | 块内余量续传；低缓冲状态增加短窗下降信号 |

### 1. 当前项先播，辅助信息后台补齐

[PlayerStore.kt:1176](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt#L1176) 创建备用服务器解析任务，但 [PlayerStore.kt:1271](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt#L1271) / [PlayerStore.kt:1308](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt#L1308) 仍在 Ready 之前 await。电影路径几乎没有可供重叠的后续准备工作。单备用候选预算为 1,200 ms，总预算为 1,500 ms（[PlayerStore.kt:1556](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt#L1556)）；实际等待取决于备用源响应和取消是否及时。

剧集额外 await series detail 与 episode queue（[PlayerStore.kt:1212](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt#L1212)、[PlayerStore.kt:1223](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt#L1223)）。应在当前资源的必要鉴权、版本核对和 PlaybackInfo 完成后先发布当前项；后续队列采用稳定媒体 ID 增量补齐。若首播失败，可继续等待同一备用解析任务。

验证：当前服务器正常、备用服务器超时；大剧集列表；自动下一集、选集、同看队列以及版本切换。确认首帧不再受辅助请求拖延，增量更新不重建当前播放器。

### 2. 缓存正在拖住视频数据返回

[AndroidTransportMediaDataSource.kt:320](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt#L320) 每次 resolveBlock 都在返回前调用 writeBlock，包括从磁盘读出的块。[AndroidYCoreBlockCache.kt:100](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreBlockCache.kt#L100) 在全局 CACHE_LOCK 内写块和长度文件，[AndroidYCoreBlockCache.kt:142](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreBlockCache.kt#L142) 调用 fd.sync；长度已知时通常两次强制落盘。[AndroidYCoreBlockCache.kt:114](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreBlockCache.kt#L114) 每次遍历整个缓存、排序、淘汰，再遍历目录清空。

这能直接延迟解复用拿到数据；慢存储、接近满额缓存、前向预取与前台读取并行时更值得测量。修复应保留 CRC、块步长校验和原子提交，使用有界后台写队列，队列满时可以跳过可再生成的缓存写，避免无限占用内存；长度不变不反复写入，磁盘错误不应让可正常播放的网络块失效。

现有 maximumResolveWaitMs 在写盘前更新，maximumCacheLoadMs 只覆盖读盘，两个字段都没有完整覆盖这段同步写耗时。需要单独记录锁等待、写入、fsync 和清理时间。

验证：冷/热缓存、空/满缓存、存储慢和磁盘满场景；检查首帧 p50/p95、解复用等待与掉帧/卡顿，以及缓存校验和损坏恢复。

### 3. 小请求等待大块下载

[AndroidTransportMediaDataSource.kt:499](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt#L499) 申请完整块并读取到完成才返回。默认块为 2 MiB；当 knownSize < 0 时，[AndroidTransportMediaDataSource.kt:833](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt#L833) 的 getSize 虽只请求 1 字节，也走这条链路。即使大小已知，首次读取未缓存块仍有完整块等待。

仅按传输量计算，2 MiB 在 10 Mbps 链路上约需 1.68 秒，不包括 RTT、服务器等待和其他请求竞争。这是理论计算，不是实测起播时间，也不能等同于能直接节省的时间，因为收到的字节还可能用于正式播放。

可先做独立的小范围元数据探测；进一步设计“已下载前缀可读、同一块后台补齐”。不要全局缩小块：会增加高码率播放的 Range 请求数量。缓存键、块 stride、部分块有效长度、取消和并发必须保持一致。

验证：MP4 前置/尾置 moov、MKV、未知 Content-Length、随机 seek、Range 返回错误；同时比较请求数与总流量，防止优化首帧却损害持续吞吐。

### 4. 已有探测复用仍有遗漏

外层 [AndroidAdaptiveCore2YPlayer.kt:715](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt#L715) 可用 rememberedProbe 复用已验证路由；创建 Enhanced 时只传 evaluator（[AndroidAdaptiveCore2YPlayer.kt:847](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt#L847)），子播放器 [AndroidNativeEnhancedYPlayer.kt:344](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeEnhancedYPlayer.kt#L344) 再 evaluate(item)，未带 rememberedProbe。新会话 evaluator 缓存为空时，会再次探测。Tunnel 有相同问题，但手机/平板应优先 Enhanced；NativeDirect 已传递计划与探测信息。

先让子播放器消费一次 initialDecision/probe，媒体、输出设备或恢复状态变化后再重新评估。验证第二次播放 open/probe 次数，测试缓存失效与恢复，不能将历史成功当作当前实际渲染成功。

另两项需要计量后再投入：

- [AndroidCore2MediaProbe.kt:617](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCore2MediaProbe.kt#L617) 冷启动会进行样本解码验证；[AndroidCodecSampleProbe.kt:35](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCodecSampleProbe.kt#L35) 再打开 extractor、配置私有 Surface 和 codec，直到 [AndroidCodecSampleProbe.kt:65](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCodecSampleProbe.kt#L65) 才开始 2 秒解码 deadline。这个数字不是整个网络打开和解码流程的超时上限。优先复用已打开源，对已知可靠的普通格式评估在实际首帧路径验证；保留必要的 Dolby/厂商兼容性检查。
- [AndroidEnhancedMediaProbe.kt:127](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedMediaProbe.kt#L127) FFmpeg 探测后在 [AndroidEnhancedMediaProbe.kt:240](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedMediaProbe.kt#L240) 关闭；正式播放 [AndroidEnhancedPlaybackSession.kt:229](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedPlaybackSession.kt#L229) 又打开。prepared demux/proxy 转交可能避免重复解析，但必须解决 probe-only 模式、轨道完整性、游标归位与生命周期，风险高于 initialDecision 传递。

### 5. 增强路径字幕与异常处理

[AndroidEnhancedPlaybackSession.kt:1692](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedPlaybackSession.kt#L1692) 的 queueSubtitleSample 调用原生 subtitle decode；[AndroidDemuxReadAheadNode.kt:144](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNode.kt#L144) 将它同步提交至 demux owner。这个 owner 同时执行 [AndroidDemuxReadAheadNode.kt:226](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNode.kt#L226) 的 fillToHighWatermark，其 readSample 可能等网络。结果是字幕让原本隔离的网络读取再次阻塞 A/V pump。

可在 owner 读字幕包时解码并附带 cues，或使用异步请求返回；保留字幕解码器线程约束，不能直接跨线程访问同一个 FFmpeg demuxer。需要 seek generation 丢弃旧字幕、双字幕顺序和有界队列。

[AndroidNativeEnhancedYPlayer.kt:825](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeEnhancedYPlayer.kt#L825) 的 session.pump 在内部命令 try/catch 之外，仅由外层 finally 清理；运行中的 demux/codec/render 异常可能退出工作协程却不发布 Failed。应统一处理非取消异常，保留取消传播，并按网络、容器、解码、输出阶段进入已有恢复机制。

验证：播放期间网络挂起且字幕包进入队列，检查已缓冲音视频能否继续输出；注入网络/解码异常，确认发布失败或恢复状态、清理一次且用户仍可退出/切集。

### 6. 断流续传与弱网反应

[AndroidTransportMediaDataSource.kt:395](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt#L395) 重试再次进入 loadRemoteBlock，[AndroidTransportMediaDataSource.kt:453](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt#L453) 从块起点重新请求，[AndroidTransportMediaDataSource.kt:499](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt#L499) 新建数组并将 total 清零。[AndroidAdaptiveHttpMediaTransport.kt:159](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveHttpMediaTransport.kt#L159) 的余量续传仅覆盖 Cronet 切换到 OkHttp；普通 OkHttp 断流仍整块重下。

应跨重试保留已接收前缀，只取剩余范围，并严格验证资源身份/版本、206、Content-Range 和总长，沿用原总时间预算。测试下载到 75% 和 95% 时断流的恢复时间、请求起点与最终校验。

[YAggregateBandwidthMeter.kt:145](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/core2/network/YAggregateBandwidthMeter.kt#L145) 使用按 sqrt(bytes) 加权的中位数，默认总权重 32768。按生产公式的理想化推演，稳定 40 Mbps 后降至 4 Mbps、每 500 ms 采样时，中位数可能约 16 秒才下降；不是实际卡顿时长，watchdog 会另行干预。建议保持稳态估计，同时在低缓冲/持续无进度时采用最近 1–2 秒信号快速减少投机预取。使用默认权重测试带宽阶跃，防止一次短抖动造成策略震荡。

### 7. 解码背压与条件性 CPU 热点

增强路径还只保留一个 pendingSample；视频 codec 返回 TryAgain 时会再次优先处理同一个视频包，后面的音频包无法继续供给。位置：[AndroidEnhancedPlaybackSession.kt:990](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedPlaybackSession.kt#L990)。NativeDirect 已有一个轨道背压时继续喂另一个轨道的机制，可考虑移植到 Enhanced；必须保持各轨道内部顺序，避免重复提交，验证视频 TryAgain 时音频仍推进。

HDR10+ 路径在尝试向 codec 提交包之前解析元数据，TryAgain 后相同包可能再次整包复制和解析；Enhanced 也会重复做 bitstream normalization。位置：[AndroidNativeDirectYPlayer.kt:1355](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt#L1355)、[AndroidEnhancedPlaybackSession.kt:1038](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedPlaybackSession.kt#L1038)。可以按访问单元缓存解析和转换结果；这是条件性热点，应先量测分配率、GC 与 pump p95/p99，并验证转换后的字节和 HDR 信息一致。

## 其他内核

| 内核 | 代码事实 | 建议与限制 |
| --- | --- | --- |
| ExoPlayer | [PlaybackBufferPolicy.kt:15](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/commonMain/kotlin/com/yfuse/core/playback/PlaybackBufferPolicy.kt#L15) Balanced 起播 1,500 ms、重缓冲 3,500 ms；[ExoVideoEngine.kt:231](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ExoVideoEngine.kt#L231) 传入 LoadControl；HTTP 连接和读取超时各 20 秒 | 良好网络/可信缓存尝试 500–800 ms 起播门槛，作为实验参数；重缓冲仍保留安全余量。若以更低门槛频繁停顿则无收益。不能假定配置了 90 秒上限就一定有 90 秒缓存，字节上限也会约束它 |
| MPV | [MpvVideoEngine.kt:779](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/MpvVideoEngine.kt#L779) 只有列表中存在已知至少 64 GiB 的远程媒体时才应用 mpvBufferProfile；[MpvVideoEngine.kt:833](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/MpvVideoEngine.kt#L833) 普通情形启用 hdr-compute-peak、ewa_lanczossharp、deband，降级条件仅限强制软解的高分辨率 Dolby | 对普通远程媒体也应用按码率/设备内存制定的缓存策略；均衡/省电/发热状态采用较轻的画质档，画质模式保持用户选择。必须用 GPU 耗时、掉帧和温度验证，不能将所有卡顿归因于网络 |
| MDK（包内包含时） | [MdkVideoEngine.kt:621](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/MdkVideoEngine.kt#L621) 初始化设置解码、UA、倍速和字幕，构造参数未接入 optimizationMode；[MDK JNI](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/mdkAndroid/src/main/cpp/MDKPlayerJNI.cpp#L1) 配置 setTimeout(20,000)，没有显式统一缓冲策略 | 在确认所用 SDK 缓冲接口后接入统一策略并测首帧/重缓冲；当前无法仅凭 SDK 默认值判断其是否构成瓶颈，不应盲目减超时 |

ExoPlayer 的 LoadControl 用于决定何时继续加载及缓冲多少；异步 codec queue 在较新 Android 上已经默认启用，不应笼统再加一遍“开启异步解码”。参考 [Android 官方定制文档](https://developer.android.com/media/media3/exoplayer/customization)。

MPV 的缓存等待与 GPU 画质配置确实存在延迟/性能权衡；建议的具体档位仍需匹配项目打包的 libmpv 版本。参考 [MPV 官方手册](https://mpv.io/manual/stable/)。

## 已有优化与适用范围

应保留已实现的 500 ms 自研起播门槛、起播/重缓冲分离、码率和倍速关联预取、共享连接池、重定向复用、独立解复用读前瞻、NativeDirect extractor 转交、聚合并发带宽估计、前台请求优先、低缓冲抑制投机请求和稳定播放后的下一集预热。

生产签名重打包工作流 [repackage-android-signed.yml:191](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/.github/workflows/repackage-android-signed.yml#L191) 强制 nativeOnly，静态 MP4/MKV/ISO 已绕过旧 HTTP 代理。“两层缓存”仅在 nativeOnly=false 且启用缓存的兼容构建成立，不能当成所有生产包的问题。

## 如何验证确实更快、更稳

先补齐每个会话的单调时钟时间戳：点击播放、当前项 Ready、必要 PlaybackInfo 完成、首次请求首字节、探测结束、解码器就绪、真实首视频帧、首次音频输出。标注实际运行内核/route，不能仅用用户选择的内核名称。已有启动与诊断指标不等于完整的点击至首帧分阶段计时。

同时记录首帧 p50/p95、seek 恢复时间、卡顿次数与总卡顿时长、音频 underrun、掉帧率、缓存写/锁等待、重试浪费字节、CPU/GPU 与峰值内存。

尤其需要修正 Enhanced 的计数：其 [publishSnapshot:497](https://github.com/zhuiyun/Yfuse/blob/0544e4da42968f95585943b282381421fe0acba4/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeEnhancedYPlayer.kt#L497) 未累加 diagnostics.bufferEvents，适配器直接透传默认 0；这会让卡顿健康评估过于乐观。应统计首帧之后进入缓冲的边沿、累计时长及最长一次停顿，区分主动暂停与 seek。

测试矩阵：冷/热缓存；普通 H.264 MP4、HEVC MKV、4K HDR/DV、内嵌/双字幕；正常网络、高 RTT、带宽从富余骤降、短时断流；至少一台中端手机和一台平板。模拟网络仍能长期承载媒体码率时，应重点验证减少额外等待和抖动；若长期吞吐低于媒体码率乘倍速，需允许用户选择低码率版本或服务器转码，缓存只能暂时延后耗尽。

建议分两批：先完成 Ready 解耦、缓存写入、字幕阻塞与 pump 异常处理及计时；再做小范围首读、probe 转交、块内续传和各内核自适应参数。每批独立比较，避免同时改动后无法判断收益来源。
