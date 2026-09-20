# 播放内核问题修复记录（2026-09-20）

本轮修复基于当前自研内核及 Exo／MPV／MDK 调用路径的审查，重点是回退正确性、资源交接、阻塞读取和内存边界。没有构建交付 APK、修改交付版本、推送或发布。

## 行为变化

| 问题 | 已落实的修改 | 回归入口 |
| --- | --- | --- |
| 原盘软件回退仍走硬件、重复回退 | 原盘工厂传递软件请求，以实际探测的视频能力生成软件方案；不支持的 DRM／安全解码／DV 组合明确失败；记录已经尝试软件路径，避免反复重建同一路径 | 软件方案矩阵、恢复策略测试 |
| 原盘包装层丢失释放与角度控制能力 | 包装层传递异步释放完成状态，自适应层转发原盘角度选择；清理失败不伪装成已完成 | 原盘包装层释放／失败／重试测试 |
| 切换时新旧实例争用资源 | `PlaybackEngineSlot` 在创建新实例前等待旧实例实际释放；屏障跨页面关闭保留，未完成的构建也占有创建许可；迟到实例先退休再放行 | `PlaybackEngineSlotTest` |
| 等待切换期间用户操作丢失 | 保留完整播放器组合及占位控制状态，接收队列、选集、进度、倍速和播放意图；播放／暂停按请求意图翻转；同内核、同解码模式的策略切换也先保存最新意图 | `PreparingPlaybackGateTest`、slot 测试、策略调用路径复核 |
| 旧实例清理误删新实例的崩溃记录 | 每次构建有独立 owner，清理只能作用于自身记录；释放完成后才清除，成功播放证据在退休时冻结 | `NativeCrashContextOwnershipTest`、slot 测试 |
| 网络读取阻塞时 Seek／选轨卡住 | 临时读取中断与永久取消分离；同一 demux owner 上恢复 AVIO 状态并执行 Seek／选轨；控制等待有预算，旧读异常及过期命令不能污染新状态 | `AndroidDemuxReadAheadNodeTest`、native interrupt helper、原盘 transport 测试 |
| 原盘中断同步关闭网络而阻塞调用者 | 先捕获当次 transport，再异步关闭；新窗口创建新 transport，旧 close 不能影响它；永久关闭仍禁止恢复 | 原盘 transport latch 测试 |
| MDK 新流尚未生效就被旧 INVALID 终止 | 使用加载代次和单调时钟窗口；窗口从 `setMedia` 提交后开始，旧异步回调按代次失效；JNI 音频证据对应所选音轨 | `MdkLoadStateGateTest`、MDK 原生编译 |
| 任意附件复制、元数据分配缺乏总量边界 | Kotlin 先过滤轨道类型；JNI 在 Java 数组分配前检查 codec metadata 和可选字体预算，不截断必要 codec 配置 | extradata native helper |
| 代理连接和请求头可无限占用资源 | 两个代理共享进程准入，单实例 16、进程 64 活动连接；无 socket 等待队列；请求头从 accept 起共用 10 秒期限 | `PlaybackProxyLimitsTest`、`AndroidPlaybackProxyAdmissionTest` |
| 暂存及软件渲染内存未参与预算 | 按需分配 direct buffer；必需暂存及最多两张 Bitmap 优先记账，然后缩减可丢弃缓存 | `AndroidPlaybackStagingBufferTest` |

具体资源限额及适用范围见 [资源边界说明](ycore-resource-bounds.md)。这些代理限制不限制媒体响应大小、Range 偏移或播放时长，也没有禁止用户配置的 HTTP 媒体服务器。

## 释放与恢复语义

释放失败不能作为创建下一内核的许可。超时后显示可重试状态，旧资源仍由原 owner 持有；实际释放完成后才允许继续。清理前面的阶段失败，仍会尝试后续核心资源清理，并保留异常。自研增强内核区分“worker 结束”与“资源确实清理完成”。

Seek／选轨控制默认等待预算为 1.5 秒。这是控制操作的等待边界，不是所有驱动及网络调用的硬执行时限。失败先发布到界面，再等待清理，避免释放阻塞掩盖控制超时。超时后不并发销毁 `AVFormatContext` 或提前启动替代 decoder，释放路径继续等待 owner 到达安全点。

新 JNI 通过 `nativeDemuxReadControlApiVersion` 检查能力；旧 AAR 不再等到第一次选轨才暴露缺失符号。正式原生构建及校验脚本同步检查 API 和预算标记。当前本地 `ycore-native.aar` 已重建，源码与二进制哈希、共享依赖一致性、JNI 方法和 16 KiB 对齐记录在 `audit/kernel-fixes-20260920/native/result.json`。

## 验证范围

统一验证记录在 `audit/kernel-fixes-20260920`。最终手机 host 测试 **2860 项**、TV host 测试 **63 项**，均为零失败、零错误、零跳过。手机／TV 应用 Kotlin、手机 instrumentation 测试 Kotlin、MDK arm64 JNI 编译以及两端 Lint、设计系统检查通过；instrumentation 测试只编译，未在设备执行。

测试任务使用各自现有的源集：TV 的 63 项是 TV 专用 host 测试，共享内核的新增 Android 回归在手机 host 源集中执行。局部独立 Kotlin 测试与这次 Gradle 运行有重叠，不能相加作为覆盖率。最终源码指纹、实际运行的回归类和原生库构建门禁结果见该目录的 `verification.json`。第一轮代理 fixture 缺少缓存目录的失败及随后修正保留在 `preliminary`，不作为通过证据。

本机没有可执行 Android 原生测试二进制的设备环境。两个 C++ helper 已完成 Android NDK 语法检查，其执行入口接入现有原生构建和 CI；本次未声称已运行这些二进制，也未声称远端 CI 已通过。

## 尚需设备验证及后续优化

- 实机反复切换 Exo／MPV／MDK／自研内核，覆盖退出后立即重进、网络超时和释放失败，记录 decoder／Surface／AudioTrack 生命周期。
- 网络 ISO／BDMV、HTTP Range、慢速读取下 Seek／选轨／多角度切换，验证 FFmpeg 与 libbluray 中断后的实际恢复。
- 4K／8K 软件回退、HDR 色调映射、音轨切换与 native heap 峰值。当前内存记账会压缩缓存，但不是进程硬内存上限，也不保证 direct buffer 立即回收。
- MDK 新流失败存在最多约 3 秒的旧状态隔离窗口；需用真实服务器和不同失败时机确认体验。
- 多内核协同目前落实的是有序交接；本轮没有实现同时解码、热备或多窗口独立播放。真正并行仍需逐实例隔离全局输出证据、分配 codec／音频焦点／Surface 预算，并做设备能力验证。
- MPV 缓存阈值、低延迟参数及更激进的预加载保留为测量后优化项。本次没有产出首帧、吞吐或帧率提升数据。
