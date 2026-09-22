# Android 播放性能优化（2026-09-08）

基线：`0544e4da42968f95585943b282381421fe0acba4`。范围包括手机、平板上的自研 NativeDirect / NativeEnhanced / NativeTunnel，以及 ExoPlayer、MPV、可选 MDK。

## 实现内容

| 环节 | 修改后的行为 | 约束 |
| --- | --- | --- |
| 当前项准备 | 必要的当前资源请求完成即发布 Ready，节目详情、全剧集列表和备用服务器后台补齐；播放专用详情查询关闭隐式演员继承 | 保留当前源鉴权和版本核对；旧启动任务会取消 |
| 队列补齐 | 以服务器和媒体 ID 匹配，补入前后剧集，同时保持当前播放器、解码器和已缓冲数据 | 当前 URL、版本和会话必须一致；切换中延后更新 |
| 自研冷读 | 首次及大跳转后的冷读允许最多 128 KiB 小范围请求，后续继续 2 MiB 块预取 | 接近完成的预取保留；小片段不冒充完整缓存块 |
| 磁盘缓存 | 有界后台写入、去重、增量 LRU；播放读取不等待全局写锁 | 保留 CRC、原子提交和容量限制；队列满可跳过可再生成缓存 |
| 缓存失效 | 读失败的清理在后台持锁重新校验，外部删除的文件立即失去命中资格 | 避免旧读取删除新提交的块；缓存错误不使可用网络数据失败 |
| HTTP 重试 | 保留已下载部分，只续传剩余字节 | 仅强 ETag 和已知总长可跨响应拼接，验证 If-Range/206/Content-Range；其余重新读块 |
| Range 元数据 | 206 的未知总长保持未知 | 不把 Content-Length 的切片长度当作整个文件长度 |
| 弱网预取 | 低缓冲时采用短窗下降信号，网络读取阻塞时仍可取得实时吞吐证据 | 稳态仍采用原加权中位数，区分尚未测得和已确认无进度 |
| 探测复用 | 子播放器消费一次初始路由决策，复用已打开 extractor 或完整 FFmpeg demux/proxy | 单次领取、鉴权匹配、过期释放；恢复时可重新评估 |
| Codec 预检 | 源打开、configure/start、样本验证纳入调用者 2 秒预算 | 共用单 worker，卡住时返回 Inconclusive，不累积 codec 线程；原生资源由所有者清理 |
| 预检输出 | 私有 ImageReader 及时消费并释放图像，避免验证 Surface 的队列产生背压 | 仍仅以 OnFrameRendered 验证真实输出；图像可用不替代输出证据 |
| 增强字幕 | 在 demux owner 读字幕包时解码，播放器只消费完成结果 | 保留 seek generation、线程所有权与字幕位图内存计数 |
| 解码背压 | Enhanced 按轨道保留待提交包；Direct/Enhanced 对同一访问单元复用 HDR 与码流处理结果 | 保持轨道内顺序；一个轨道 TryAgain 不阻塞另一轨道 |
| 运行异常 | Enhanced 的 pump 和快照异常进入统一失败/恢复通路，使用独立播放线程 | 取消异常仍传播；资源只由持有者释放 |
| 故障恢复 | 确认远程数据饥饿且无真实进度时，启用 30 秒恢复窗口 | 保留原 60/120/180 秒硬上限；首帧前 synthetic clock 不算输出 |
| 卡顿统计 | 原生路径记录次数、总时长和最长一次，接入统一诊断导出 | 起播、主动 seek、暂停、失败后等待重试与释放耗时不计入 |
| 分阶段计时 | 详情、PlaybackInfo、Ready、探测、Codec、路由和真实音视频首输出使用加盐 SHA-256 的匿名标识串联 | 标识仅在当前进程内稳定，保留会话原值脱敏；阶段和匿名标识进入去重键，首输出记录实际内核与路由 |
| ExoPlayer | 缓存证据充足时可用 500 ms 起播；实测吞吐至少为消耗的 1.8 倍时可用 750 ms | 网络证据 500 ms 失效，估计随静默时间衰减；直播、兼容模式和重缓冲仍服从原策略 |
| MPV | 所有远程资源使用受设备内存限制的前后缓存；均衡/省电降低缩放与去色带开销 | 画质模式保留高质量设置；资源紧张时使用轻量档 |
| MDK | 接入 optimizationMode，使用已打包 SDK 的 buffer 属性区分起播和重缓冲，并按码率约束缓存时间 | 不改变 JNI 协议，不依赖未打包的新 SDK 接口 |

## 验证口径

自动化验证覆盖慢备用源和剧集列表不阻塞 Ready、队列重排、缓存有界写与淘汰竞态、未知总长 Range、强弱 ETag 续传、带宽阶跃、阻塞字幕交付、Codec 预检超时及取消、起播策略与卡顿计时。

实际收益必须用同一设备和资源做前后对照。不能把配置中的 500/750 ms 当作点击到首帧的保证，也不能把模拟测试时延当作真机 p50/p95。

建议真机矩阵：中端手机和平板；冷/热缓存；H.264 MP4、HEVC MKV、4K HDR/DV、内嵌及双字幕；正常网络、高 RTT、带宽骤降和短时断流。比较点击到真实首帧、seek 恢复、卡顿总时长、掉帧、音频 underrun、CPU/GPU 和峰值内存。

## 本次执行结果

代码保存在独立工作树 `.worktrees/playback-perf`，分支为 `codex/playback-performance-20260908`。

- Android 单元回归：408 个测试套件、2,123 项测试全部通过，失败、错误和跳过均为 0。包含 Android 上运行的 commonTest。
- Android 构建：自研专用包和包含 ExoPlayer、MPV、MDK 的完整包均已编译成功。FFmpeg、MPV、MDK SDK 本体沿用项目已验证的打包产物，Android 构建照常编译 JNI 桥接模块。
- 代码格式：仅对本次修改的 Kotlin 文件执行 ktlint 格式化与检查；另执行 `git diff --check`。
- 真机：Samsung SM-G973U，Android 9 / API 28，arm64。最终一次运行 8 项测试全部通过。独立包 `com.yfuse.playbackperf`，不使用主应用数据；结束后已卸载本任务的应用及测试 APK。
- 真机运行时 / JNI 契约：6 项测试通过。
- 真机真实 SurfaceView：生成 AVC 视频的首帧及一次 seek 后的新帧回调验证通过，实际路由为 NativeDirect，解码器为 `OMX.qcom.video.decoder.avc`。
- 完整生命周期回归：通过。真实 SurfaceView 上完成 10 次 seek、2 次横竖比例画面重建、输出分离/重连、前后集往返和自然结束；每次 seek、重建及切集都等待新的真实视频输出证据。

真机视频在设备缓存中由 MediaCodec 编码生成（320 × 180、10 fps、100 帧、10 秒、无音轨），测试结束删除。没有传输历史录屏或用户媒体。

离屏验证曾失败：同一视频的 ImageReader 已消费 100 帧、消费错误为 0，但没有真实渲染回调。真实 SurfaceView 对照通过，表明两种输出目标的回调证据不同；完整生成视频回归改用真实 SurfaceView，保留真实首帧断言，没有以图像到达或解码到 EOF 代替渲染验证。私有 Codec 预检仍允许无回调时返回 Configured/Inconclusive。

本次没有完成平板、弱网、高码率/HDR、音频输出和长时间播放的性能对照，也没有测得点击至首帧或卡顿的 p50/p95 改善。上述通过结果用于功能回归，不能作为各内核性能收益的实测结论。

本地输出（不纳入 Git）：

- `artifacts/playback-perf/native-only/app.apk`：自研专用隔离测试包。
- `artifacts/playback-perf/native-only/test.apk`：设备测试 APK。
- `artifacts/playback-perf/full/app.apk`：包含所有可选内核的隔离测试包（`com.yfuse.playbackperf.full`）。
- `artifacts/playback-perf/verification/native-unit-tests/`：单元测试 XML。
- `artifacts/playback-perf/native-only/*tests*.log`：真机测试日志，包括离屏诊断与真实显示对照。
