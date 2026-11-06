# YCore 内部去旧内核与 Android 诊断适配

本次依据最新用户要求仅保留自研 YCore 与显式选择的 SenPlayer。此前 improve 分支的 MPV 合并裁定保留在同目录原报告；MPV 功能本轮按新范围移除，不视为遗漏。主任务统一处理引擎工厂、构建依赖、选项和产物检查。本报告只记录此子任务实际修改，不把静态检查称为编译或设备验证。

## 真实执行路径与移除边界

| 范围 | 保留或变更 | 真实依据 |
|---|---|---|
| NativeDirect | 保留 Android MediaExtractor、MediaCodec、AudioTrack 图 | `core2/android/AndroidNativeDirectSession.kt` 的真实平台节点；没有 Media3 import |
| NativeEnhanced | 保留自研 FFmpeg 解封装、MediaCodec/软件解码、自研渲染 | `AndroidEnhancedPlaybackSession.kt`、`FfmpegNativeBridge.kt` |
| YCore 工厂 | 删除 Trial/nativeOnly 条件与通用工厂中的 MPV 注入、旧 fallback 接口与实现 | `AndroidCore2Trial.kt`、`AndroidAdaptiveCore2YPlayer.kt`；不存在构建开关恢复旧引擎的分支 |
| 自研恢复 | 保留 enhanced/software 内部恢复、真实失败类别；删除兼容移交和依赖旧执行器的学习门禁支路 | 软件 DV/受保护媒体明确拒绝，不声称有另一内核可救援 |
| GPU 规划 | 删除 MpvGpu，未有真实 Vulkan 执行器证据时返回不可用 | `YGpuPipeline.kt` 及负例测试 |
| FTP / 云盘 | 补入口、native baseline、ISO协议白名单；增强播放与探测必须代理到现有真实 transport | `AndroidFtpMediaTransport` / `AndroidCloudDriveMediaTransport` 已存在，MediaExtractor/loopback/BluRay均有调用；原生 FFmpeg不直接接稳定云盘URI |
| 来源自定义头 | 增强源代理补 originBoundHeaderNames，下游跨origin不带私有头 | `YEnhancedDemuxSource.kt`、新增实际localhost重定向回归 |
| ISO/BDMV | 保留 FfmpegNativeBridge.registerBluRaySource 与 libycore_demux/libbluray；原始 file/content/http/smb/ftp/cloud 入自研 | `AndroidYCoreBluRaySource.kt`；SAF BDMV安全VFS保留，旧MPV classLoader/registry全部移除 |
| 菜单/角度 | 保留 YCore 自身 menuSession/overlay；删除 MPV 专用 angle 包装 | `DiscNavigationOverlay.android.kt`、`NativeBluRaySessionRegistry.android.kt`；新增旧会话回调不能清新菜单测试 |
| 产品适配器 | LegacyYPlayerAdapter 改名 VideoEngineYPlayerAdapter，保 SenPlayer共享控制 | 它只映射 VideoEngine 状态/控制，不加载第三方内核；未确认自研route使用 Unknown |
| 字幕 | 保留 native sidecar格式/语言/default及共享Compose展示；离线字幕测试改真实YCore映射 | 未识别格式保持null交内容探测，不凭未知后缀编造SRT |
| 深度探测 | 删除 MPV headless probe；平台不完整则保server/unknown | 播放阶段仍由独立YCore真实bounded probe决定解码；不伪造原生探测结果 |
| 杜比诊断 | 删除旧MPV provider/class loading和按内存/性能推断FEL；输出报告只读当前会话证据 | 新公共runtime默认false；YCore Widevine/安全解码已有路径未由此子任务移除 |
| 标签与包诊断 | nativeOnly兼容偏好不能盖过实际Sen身份；显示YCore接口是否加载，不复用旧MPV制品版本 | `PlaybackDiagnosticReportRegistry`、`DiagnosticLogTools.android` |

`YCoreNativeReadiness` 中 Exo/MPV/MDK 仍作为**禁止依赖检测枚举**保留，非实际播放入口。源码内算法来源注释或历史缓存不等于运行库依赖。旧 Legacy 失败记忆条目遇到新 Unknown 枚举会经既有runCatching安全忽略；不影响账户或媒体数据。

## 验证与边界

- 本子任务未运行 Gradle、设备、网络或许可，未打包、安装、改版本、git add/commit。
- core2/光盘与追加诊断/测试文件分别执行本地 ktlint CLI；最终均 exit 0。改动范围 `git diff --check` 通过。
- 静态检查当前源码无被删 MPV capability provider、headless probe、fallback factory 或旧光盘注册器引用。
- 设备回归已改写为3项当前YCore用例：生成AVC/AAC实际输出后幂等release、真实localhost挂起读取取消、共享slot主线程控制与串行退役。只保隔离debug包名条件，不再因旧nativeOnly开关跳过。
- AndroidPlaybackRuntimeInstrumentedTest验证旧运行库和类不存在、YCore native API可加载、SenPlayer原始资产存在；仅读资产，不初始化其原DEX/许可。**这些设备用例尚未运行。**
- 本轮取消旧 Exo/MPV/MDK 专用实现测试不等于通过当前内核测试。统一编译/HostTest和最终APK检查由主任务记录。

建议统一HostTest覆盖：`*Core2*Test`、`*YEnhancedDemuxSourceTest`、`*NativeEnhancedCommandPolicyTest`、`*WidgetHeaderScopeTransportTest`、`*NativeBluRaySessionRegistryTest`、`*NativeLocalBdmvPathTest`、`*YGpuPipelineTest`、`*YCoreNativeReadinessTest`、`*VideoEngineYPlayerAdapterTest`、`*WidgetPlaybackBindingTest`、`*OfflineSubtitleConfigurationTest`、`*PlaybackEngineBindingLabelTest`、`*NativeRemoteBluRayPlanningTest`，及既有FTP/cloud transport测试。

逐文件存在/删除状态和SHA-256见 `ycore-removal-owned-files.json`。该文件是冻结时子任务范围快照，不替代主任务最终源码快照；后续统一编译若发现真实错误，须记录对应修正并重新核对。
