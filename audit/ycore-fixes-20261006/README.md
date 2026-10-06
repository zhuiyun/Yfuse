# YCore 审计修复（2026-10-06）

对应云端整合 PR #221 之后的 10 项审计发现，修复在 PR #222 中提交。基线为 `a10eaaed778389f512ed0f16583caff82d176e8b`。本次没有制作新的正式 APK 交付，版本配置保持原有发布版本。

| 发现 | 修复行为 | 回归证据 |
| --- | --- | --- |
| PCM 部分写入误校时／拼接提前生效 | 缓冲区游标保留原始 PTS；仅记录 AudioTrack 接受的字节。按实际播放帧应用分段时钟；音频服务重建保留剩余 DSP 数据 | `YPcmTimelineTest`、`PcmWriteCursorTest`、原有 PCM effect 部分写入测试 |
| 软解采样率、声道改变不重配 | 比较每帧实际格式，非阻塞排空旧 PCM 后重新协商输出，恢复倍速和播放意图；排空有 5 秒有效播放上限 | `SoftwarePcmOutputTest` |
| Surface 卡住导致退出无界等待 | 清空最多等待 2 秒；代次隔离旧任务，超时隔离执行线程；在 Canvas 调用退出之前保留位图和内存配额 | `BoundedRenderFenceTest`、原有帧租约池测试 |
| TTML 时序／命名空间错误 | 有界 XML 解析，按命名空间 URI 识别；父级时间、顺序容器、裁剪、有效帧率、ticks、定时 span、xml:space | `YTtmlTimingTest`、`YExtraTextSubtitleFormatsTest` |
| SAMI 平方级分配／取消缺口 | 逆序计算下一个时间点；分阶段检查取消和文件／cue 上限 | `YSamiBoundsTest` 的重复 SYNC、20,000 条字幕与末段取消 |
| 分离 rendition HLS 固定初始画质 | VOD 使用共享 ABR 与需要新初始化时的重开控制；保留音频／字幕组、headers、身份；兼容的直播 ladder 可按分片切换并刷新窗口 | `AndroidAdaptiveProxyTransitionTest` 的升降档、初始化隔离、组保留及直播刷新 |
| 晚到 HDR10+ 元数据被忽略 | 所有 HEVC 包可提取 SEI；GPU 按精确 PTS 取元数据，缺失／seek／未知时间回退静态参数；软解读取 AVFrame 动态 HDR、逐帧 MaxCLL／mastering 信息 | `Hdr10PlusFrameMetadataTest`、`YHdr10PlusParserTest`、原生 tone-map 测试 |
| 软解无去隔行 | 对标记隔行的帧逐平面做 spatial bob，保留场序和高位深，再进行色彩转换；有有效 PTS／时长时按场速率输出，缓冲区扩容不丢场，flush 清除待输出场 | 原生去隔行 Release／ASan／UBSan 测试；`SoftwareOutputContractInstrumentedTest` 的 25i 顶场与 29.97i 底场样本 |
| MKV 远端／嵌套章节丢失 | 首次输出后独立连接按 SeekHead 偏移读取有界 Chapters 元素；支持受限深度的嵌套章节；退出／超时关闭连接 | `YMatroskaChaptersTest` 的嵌套章节、定向 Range 与大小边界 |
| PCM 强制压到 S16 | 软件解码 API 4 优先交付 float；AudioTrack 优先 Float32，回退时尝试 packed 24，再 S16；仅量化回退加 TPDF 抖动，保留旧 JNI 接口 | `SoftwarePcmOutputTest`；实际 JNI 的 24 位 WAV 低位精度测试 |

另外修正了构建 provenance 对新 API 的版本校验。缓存协议测试改为每个连接独立游标，并显式模拟有效播放状态，避免把被取消的推测性缓存写入当成必达结果。

## 验证与边界

- 本地直接编译实际生产 Kotlin 逻辑；字幕 13 项、音频时钟／游标／有界 fence 5 项、章节 7 项、HDR 元数据 4 项通过。
- 原生 tone-map 与 deinterlace 测试同时使用 `-O2 -DNDEBUG` 和 AddressSanitizer／UndefinedBehaviorSanitizer；不是只靠 Debug assert。
- 完整 Android／TV 单元测试、lint、R8、DEX 校验、模拟器 JNI 与 CodeQL 的最终状态以 [PR #222](https://github.com/zhuiyun/Yfuse/pull/222) 对应提交的检查记录为准。
- 去隔行为空间 bob，尚不是运动自适应滤镜；重复场／胶片反胶转不是本次实现目标。没有有效时间信息时只输出一个去隔行画面，避免伪造额外 PTS。
- TTML 保留明确的媒体时间子集：不支持的 SMPTE／clock／drop-frame 时序会显式拒绝，不能据此宣称完整 TTML2 样式或布局支持。
- HLS 原地切换要求初始化、密钥及分片边界兼容；VOD 可在兼容时间轴内重开以替换初始化。不同初始化的直播切换与 LL-HLS PART 仍沿用已有受限路径。
- 软件 HDR10+ 使用全画面窗口 MaxSCL 调整场景峰值，不把为其他 HDR 目标屏幕创作的曲线直接当作 SDR 曲线。模拟器和合成样本不能代替真实 HDR 屏、HDMI 音频、4K 热功耗及长时间播放验收。
