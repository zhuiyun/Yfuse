# YCore 播放资源边界

两个 Android loopback 播放代理共用进程连接准入：每个代理最多 16 个活动连接，进程最多 64 个。工作池没有待处理 socket 队列；超额连接立即关闭。准入许可保留到请求的工作线程完成清理，关闭代理不会提前释放仍在清理的许可。

请求行及全部请求头共用从 accept 开始计时的 10 秒期限，单行最多 8 KiB、最多 64 个字段、字段合计最多 64 KiB。读取时拒绝超限或不完整的头，不先读完再截断。这些限制不限制媒体响应大小、Range 偏移或媒体传输时长。

FFmpeg 只为视频、音频和字幕轨读取 codec extradata；普通 Data／附件轨不会在元数据探测时复制附件。JNI 在分配 Java 数组前分别检查每个 demux 会话的 32 MiB codec metadata 和 32 MiB 可选字体累计预算，字体最多 128 个。必要 codec metadata 超限明确失败，可选字体超限跳过；不截断 codec 配置。原生防护需要重新构建并更新 `libycore_demux.so`，保留旧 JNI 签名不表示旧二进制自动获得新防护。

demux 和软件解码的 direct buffer 按需分配，关闭时撤销持有和预算占用。必需的 packet／frame 暂存与软件渲染最多两张 Bitmap 按固定占用计入预算，再分配可丢弃的传输缓存、demux 预读、缓存写队列和预加载份额。变更渲染尺寸前必须等旧的在途帧归还。

这里的预算用于缓存与必需缓冲之间的内存分配，不是整个进程的硬内存上限。单个合法大帧可能超过缓存预算；预算收缩不能截断该帧。现有 packet 64 MiB、软件视频单帧 128 MiB、软件音频单帧 8 MiB 的安全限额仍保留。FFmpeg 内部、codec／驱动和应用其他部分的内存另计，撤销 direct buffer 引用也不保证 VM 立即回收原生内存。

回归入口：

- `PlaybackProxyLimitsTest`：进程／实例限额、工作池拒绝、慢速头总期限、头大小与完整性。
- `AndroidPlaybackProxyAdmissionTest`：两个实际代理的跨实例 socket 准入和不完整头到期关闭。
- `AndroidPlaybackStagingBufferTest`：按需分配、扩容失败回滚、释放、4K 暂存与双 Bitmap 共存时缓存收缩。
- `bash scripts/test-ycore-extradata-budget.sh`：分配前的单项／累计 metadata 和字体数量边界。

这些单元测试不能替代实机多内核切换、4K／8K 软件回退及 native heap 峰值验证。
