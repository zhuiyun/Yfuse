# 播放问题复核 · 2026-09-20

核查对象：当前工作区源码，以及上一轮播放优化的已保存验证记录。本轮只做源码、调用链和制品指纹核查，增加此文档与审查记录；没有修改播放实现、重新打包或操作手机。

## 逐项结论

| 用户条目 | 当前结论 |
| --- | --- |
| 1：自研内核跳过当前影片预热 | 旧问题已改。符合条件时已调用 YCore 当前影片准备器，复用下一集的块缓存预热，并可移交平台 extractor。不是所有格式都能提前完成容器准备。 |
| 2A：详情页到播放重复读详情 | 已接入短期元数据复用和预备 Store 领取；缓存/预备失配时仍需读取，不能再描述为每次重复请求。 |
| 2 新增：裸 Series 起播串行请求 | 仍成立。Emby/Jellyfin 冷入口可出现 Series 详情 → 剧集目录 → Episode 详情 → PlaybackInfo。手机、电视首页 Hero 播放是已确认入口；继续观看卡片通常先进入详情页。 |
| 3：隧道选轨导致整段重建 | 仍成立，但“语言自动选轨导致起播成本翻倍”没有运行证据，不能一概而论。另确认了隧道轨道元数据不足导致语言偏好丢失的路径。 |
| 4：兼容内核每请求校验文件头 | 归因不准确。128 KiB 校验重复发生在 YCore 增强路径的代理中；mpv/MDK 的兼容代理没有这项预校验，Exo 不走该 loopback。`Connection: close` 是本地响应，不能等同远端 TLS 重连。 |
| 5：FFmpeg 分档、重复深探测、Exo 连接池 | 三项已接入，原生库已重建且指纹与上轮验证一致。真实多音轨/DV 播放尚未验证。 |
| 6：低延迟、mpv 缓冲、弱网 | 自研层未使用已探测的低延迟能力；mpv 确有 1 秒媒体缓存门槛；单块报错时间不是固定 8 秒。 |

## 1. 当前影片预热确实接入默认自研路径

[PlaybackPreloader.android.kt:63](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackPreloader.android.kt:63) 在非计费网络、非省电、缓存开启且预计由自研内核播放时，已调用 `AndroidCurrentItemPreparation.preload`。文件后面的 `shouldWarmPlaybackCache` 仍排除 Core2，是为了排除兼容内核的 Media3 缓存预热，不能单独据此判断 YCore 仍跳过。

- [AndroidCurrentItemPreparation.kt:63](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCurrentItemPreparation.kt:63)：选择稳定 500 ms 后启动。
- [AndroidNextItemPreparation.kt:132](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNextItemPreparation.kt:132)：从头播放复用同一块缓存读取器，头部目标上限 2 MiB、尾索引目标 512 KiB；缓存块对齐可能增加实际传输量。
- [AndroidCurrentItemPreparation.kt:83](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCurrentItemPreparation.kt:83)：平台容器探测，定位续播关键帧，保留 extractor。续播不按时间比例猜字节偏移。
- [AndroidAdaptiveCore2YPlayer.kt:952](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:952)：正式打开时领取准备资源；要求来源、鉴权、缓存配置、源提示和位置匹配。

边界：只准备当前允许缓存的静态直连来源；平台 extractor 不支持的格式不会在此提前完成 FFmpeg 增强探测。取消、未完成或过期时走正常打开。现有块缓存复用测试仍明确允许新 reader 下载一次 128 KiB 校验，预热不等于后续零回源。

## 2. 裸 Series 串行读取是明确遗留项

当前 [PlayerStore.kt:837](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt:837) 先读当前 ID 的详情；遇到 Series 后在第 893 行解析剧集目录，第 911 行读取具体 Episode，随后第 1121 行协商播放信息。目录请求见 [EmbyDetailService.kt:138](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyDetailService.kt:138)。

准确条件是“裸 Series ID、Series/Episode 详情均未命中缓存”；不是所有播放固定先发三个请求。具体 Episode 的目录补齐已经延后。Plex 有对应的 metadata/allLeaves 链路，但 PlaybackInfo 属适配器处理，不能照搬 Emby 的 HTTP 请求数。

已核实入口：

| 入口 | 结果与证据 |
| --- | --- |
| 手机首页 Hero 播放 | [HomeScreen.kt:363](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt:363) → [HomeStore.kt:614](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt:614) 将 TV 类匹配到 Series → [HomeTabComponent.kt:199](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeTabComponent.kt:199) 直接进入 Player。前面还可能有 TMDB 匹配/标题搜索。 |
| 电视首页 Hero 播放 | [TvHomeScreen.kt:103](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvHomeScreen.kt:103) 走同一 HomeIntent.Play 链路。 |
| 继续观看、下一集卡片 | 手机 [HomeScreen.kt:436](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt:436)、电视 [TvHomeScreen.kt:417](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvHomeScreen.kt:417) 走 OpenResume → OpenEmbyItem → Detail，不能认定直接传裸 Series。 |
| 媒体库 Hero / 普通详情 | 经详情解析具体目标并预建 Store，发布 `target.id`。 |
| TMDB 信息页 | [TmdbInfoComponent.kt:153](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/TmdbInfoComponent.kt:153) 在进入 Player 前解析目标，成本可能存在于页面侧。 |
| TV 深链、Cast 库内播放、桌面恢复 | 接口技术上允许任意有效 itemId；正常自产恢复数据传裸 Series 尚未证实，不能列成已确认高频入口。 |

A 项已接入 [EmbyRepository.kt:714](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt:714) 的 15 秒/16 项详情缓存以及 [PlayerComponent.kt:44](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerComponent.kt:44) 的预备 Store 领取。建议后续优化从已确认的首页 Hero 入手，保留进度投影、下一集选择、版本核对和鉴权语义。

现有 `PlayerSeriesLaunchTest` 验证最终播放 Episode，而非请求次数或耗时；`PlayerStoreTest` 的延后目录用例针对具体 Episode，不覆盖这个分支。

## 3. 隧道选轨存在无效重建和偏好丢失

[AndroidAdaptiveCore2YPlayer.kt:1740](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:1740) 收到主音轨/主字幕 `SelectTrack` 时，只要当前路由是 NativeTunnel，就禁用隧道并调用 rebuild；没有先判断目标是否已选中或字幕是否本来已关闭。rebuild 会释放旧子播放器再打开新子播放器。

但不能把这个分支等同于每次自动选语言都重建：

- [AndroidNativeTunnelSession.kt:144](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeTunnelSession.kt:144) 当前固定取容器第一音轨。
- [AndroidNativeTunnelYPlayer.kt:355](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeTunnelYPlayer.kt:355) 仅公布一个已选中的 `Primary audio`，没有语言信息。
- [PlayerRoot.kt:1465](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:1465) 一旦看到轨道就 consume 详情页语言请求。语言匹配失败时不选轨，但请求已经消耗，存在偏好丢失路径。
- 同一处明确收到“关闭字幕”时，会无条件发 SelectTrack；[PlayerRoot.kt:1552](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:1552) 的接力偏好也有相同路径。这些是可以静态追通的不必要重建入口。
- 普通记忆恢复 [PlayerTrackEffects.android.kt:38](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerTrackEffects.android.kt:38) 已有 `selected` 和空字幕列表保护，不能笼统算作必现重建。

后续应把初始轨道意图与真实轨道解析带入首次准备，使用所选音轨进行路由和解码能力判断；同时跳过无效选轨、明确隧道不支持的真实换轨如何降级。不能仅把 UI 的选择调用提前，因为此时可能尚无稳定轨道 ID，当前隧道也未完整公布轨道。需要与探测子预算、已准备源领取、多音轨/DV 和恢复偏好一起回归。源码证明有额外打开成本，不证明耗时恰好翻倍。

## 4. 重复头部校验发生在 YCore 增强代理

兼容内核当前路径：mpv 使用 `AndroidPlaybackHttpProxy`；MDK 开启缓存且来源可缓存时使用它；Exo 直接使用共享 OkHttp 的 Media3 数据源。兼容代理每次创建的是读取游标，缓存路径先使用共享 CacheDataSource，没有单独的 128 KiB 文件头预校验。

真正的遗留链路：

1. 增强探测 [AndroidEnhancedMediaProbe.kt:149](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedMediaProbe.kt:149) 和增强播放 [AndroidNativeEnhancedYPlayer.kt:455](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeEnhancedYPlayer.kt:455) 包装 `AndroidYCoreHttpProxy`。
2. [AndroidYCoreHttpProxy.kt:1518](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreHttpProxy.kt:1518) 每个 binary/Range 请求创建新 `AndroidTransportMediaDataSource`，调用 getSize，结束后关闭。
3. [AndroidTransportMediaDataSource.kt:1123](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt:1123) 新实例用开头读取探长度；[第 149 行](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt:149) 在信任持久缓存前下载最多 128 KiB 校验。关掉磁盘缓存仍有长度探测，但不应再称为缓存校验。

这会影响 NativeEnhanced、GpuEnhanced、SoftwareFallback 和增强探测，包括远程静态文件；外层静态文件绕过代理不代表增强层不会再包装。

两个代理的 `Connection: close` 都发往本机客户端：[兼容代理](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AndroidPlaybackHttpProxy.kt:455)、[YCore 代理](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreHttpProxy.kt:1768)。远端已有共享 OkHttp 连接池，不能从该响应头推导每次拖动都重新建立远端 TCP/TLS。

建议按单个播放会话复用媒体表示验证、长度和块状态，保留独立读取游标/租约。不要只按 URL 全局共用一个可关闭的数据源：readAt、取消句柄和预读窗口有实例状态，可能互相阻塞或取消。首次鉴权、强 ETag、长度、媒体版本及表示变化失效必须保留。

缺少的关键测试是：连续/并发 Range 只验证一次、身份/ETag 变化重新验证、取消一个请求不影响其他请求。现有 `NextItemRangeReuseTest` 明确保留新 reader 的 128 KiB 校验，不能拿它证明跨代理请求已经消除了校验。

## 5. C/D/E 已落实，运行验证仍有边界

- C：[YCorePlayerRuntime.android.kt:68](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/YCorePlayerRuntime.android.kt:68) 读取内核探测事实；自研路径不再另开界面深探测。兼容路径的可选探测等待真实输出和至少 8 秒缓冲（按倍速折算），错误时可立即诊断。
- D：[AndroidEnhancedMediaProbe.kt:168](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedMediaProbe.kt:168) 已启用保留播放源的短分析策略；普通 MP4/MKV、AVC/HEVC、已知音轨且没有已知 HDR/DV/DRM/原盘提示时可用。缺少必要音视频参数则完整重开。JNI 配置 2 MiB/1 秒媒体分析时长，正式播放保留分析包；这不是墙钟 1 秒保证。
- E：[ExoVideoEngine.kt:173](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ExoVideoEngine.kt:173) 已使用 [PlaybackHttpDataSource.kt:13](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackHttpDataSource.kt:13) 的共享池。

本轮核对上一轮审查的全部 49 个已记录源码/配置/文档指纹没有变化，原生 AAR 指纹也一致。上轮 2,838 项主机测试、两端编译及 Lint 通过的记录仍对应这些改动。测试包含策略排除、轨道缺失回退、探测预算和缓存取消；不等同于真实 FFmpeg 多音轨/DV 文件与硬件输出回归。本轮没有重新执行设备或主机测试。

## 6. 小项的准确表述与测试矩阵

**低延迟：**[AndroidYCapabilityProvider.kt:127](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCapabilityProvider.kt:127) 探测了能力，但自研 MediaCodec 配置没有使用 KEY_LOW_LATENCY/PARAMETER_KEY_LOW_LATENCY。不能由此断言第三方 native 库内部也从未开启。Android 11 起支持该可选模式，可能增加资源消耗，应按能力和播放策略选择；它减少解码器不必要的数据滞留，不会省掉服务器协商或网络往返。[Android 官方说明](https://developer.android.com/about/versions/11/features#media)

**mpv：**[MpvVideoEngine.kt:829](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/MpvVideoEngine.kt:829) 为远程媒体设置 cache-pause-initial=yes 和 cache-pause-wait=1。1 是媒体缓存秒数，不是固定等待 1 秒墙钟时间；seek 后也可能进入这类缓冲。[mpv 官方手册](https://mpv.io/manual/stable/#options-cache-pause-wait)

**弱网：**当前 [AndroidTransportMediaDataSource.kt:675](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt:675) 的无进展窗口按可播放缓冲折算，范围 4–12 秒；单块读取与重试共享默认 30 秒总预算，并最多有 100/300 ms 两次退避。8 秒可能是某次观测，不能作为固定上报时限。

后续矩阵应覆盖：首字节阻塞、传输中断、低速持续有进展、高 RTT、小缓冲/足缓冲、预取转前台、seek 取消、ETag 改变、总预算耗尽；用 transport_range_opened / transport_range_failed 和首输出日志分别分析。按用户建议，当前不直接修改弱网超时常量。

## 后续优先级

1. 隧道的初始轨道偏好、真实轨道公布、无效选轨去重；兼顾正确选轨和避免重复打开。
2. YCore 增强代理的会话内表示验证/长度/块状态复用，保持缓存和取消隔离。
3. 手机与电视首页 Hero 的 Series 目标提前解析或快照复用，保留正确选集语义。
4. 完善当前影片预热与原生短分析的运行回归；低延迟和 mpv 阈值属于后续可选实验。
