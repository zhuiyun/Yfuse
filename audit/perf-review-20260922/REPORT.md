# 详情页与播放提速审查 · 2026-09-22

只读审查。对象：工作区当前源码（HEAD `1ee4fe28`，含未提交改动，版本 1.0.80）；证据：用户提供的两份诊断包（`audit/diagnostics-20260921-130754`、`Yfuse-diagnostics-20260922-150836.zip`，设备 OPPO PLG110 / Android 17，应用 1.0.75–1.0.79，远程 HTTPS 服务器，5 GB 级 HEVC MKV）。关键事件摘录见同目录 `device-timelines.txt`。没有改代码、没有打包、没有操作手机。

## 0. 结论

**详情页**：首屏本身不慢（网络 2.1 s、缓存 0.03 s），慢的是"播放键就绪"。它需要两次串行请求，被包在 5 s 预算里，而这两次请求和首页、媒体库、日历、同步、健康探测、跨服务器资源比较的几十个后台请求排在**同一个 4 并发 / 全局 8 并发、先进先出、无优先级**的 OkHttp 队列里。后台里有多台已失效或被 Cloudflare 拦截的服务器，每个请求可占槽位 8–30 s。结果是：预解析超时 → 缓存被丢 → 追加跨服比较 → 用户点播放后从头再跑一遍。真机四次样本：4364 ms、1351 ms、两次直接超时（7085 / 5108 ms）。

**播放**：点击到首帧 6.7 s（顺利路径）、12.4 s（杜比视界续播）、43–54 s（EAC3 或 Range 403 时两级探测全部打满）。可归因的分段：PlaybackInfo 并发期 1.0–2.2 s（空闲 0.17–0.35 s）；引擎构建到第一个媒体 Range 1.4–3.1 s；平台探测 3.3 s（普通片）或 8 s 封顶；增强探测 8.8–18 s；路由决定到解码器选择 1.85 s；详情页 YCore 预热在所有样本里都没有生效，且没有留下任何日志。

两条线共用同一个最高优先级修法：**放宽并分级网络队列 + 给失效服务器加统一熔断**。

## 1. 真机数据

### 1.1 详情页与播放键（1.0.79，09-22 05:15，剧集 111658 / 111663）

| 阶段 | 首次打开 | 重开（30 s 内） |
| --- | --- | --- |
| `content_ready` | 2081 ms（网络） | 32 ms（缓存） |
| `detail_first_content_frame` | 2196 ms | 134 ms |
| `refresh_ready` | — | 4697 ms |
| `play_target_ready` | **失败 7085 ms**（playback resolution timed out） | **失败 5108 ms** |
| 点播放 → `detail_selection_ready` | 1351 ms（现场重解析） | |

同窗口内的后台事件：日历 `library_lookup_timed_out` 8000 ms × 2、`source_lookup_timed_out` 8000 ms × 2、`home_enrichment_timeout`、媒体库 `load_completed` 30722 ms、`server_probe` 522 / 超时、`report_playback`/`next_up`/`series_identity_catalog` Unauthorized。09-20 15:28 的样本同理：点播放到 `detail_selection_ready` 4364 ms，同一时刻 `source_lookup_timed_out` 对象正是主服务器。

### 1.2 点击到首帧

| 会话 | 片源 | 预备 Store | PlaybackInfo | 引擎→首 Range | 平台探测 | 增强探测 | 路由→首帧 | 点击→首帧 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 09-21 05:04（1.0.77） | HEVC+AAC | 已领取 | 1410 ms（预备期） | 1.9 s | 3365 ms | — | 3.1 s | **6738 ms** |
| 09-22 05:15（1.0.79） | DV P5 HEVC+AAC，续播 7253 s | 已领取 | **2095 ms** | 1.4 s | 3292 ms + 样本探测 638 ms | — | 4.6 s（含 seek，位置误差 1723 ms） | **12393 ms** |
| 09-20 15:28 | HEVC+EAC3 | 现场解析 4.4 s | 1006 ms | 3.1 s | **8001 ms 打满** | 8817 ms | 21 s，输出代次 3→5 重启 | **43468 ms** |
| 09-21 13:15 | HEVC MKV，Range 403 | 已领取 | 2014 ms | 5.5 s | **8004 ms 打满** | **18000 ms 打满** | — | **53981 ms** |

空闲时 PlaybackInfo：177 ms、342 ms、168 ms（09-21 05:06–05:07 三次）。`engine_attached Exo` 在所有样本中出现后 60–100 ms 即 `engine_detached`，见 3.4。

## 2. 详情页：发现与方案

### 2.1 [P0] 单一 OkHttp 队列，4 并发 / 8 全局，后台流量与前台请求同队列

- 根因：`HttpClientFactory.android.kt:15-16, 41-45` 把 Dispatcher 设为 `maxRequestsPerHost = 4`、`maxRequests = 8`；`AppModule.kt:163-169` 全应用只有这一个 Emby 客户端，所有服务器共用 8 个槽。请求超时 30 s、连接 10 s（`HttpClientFactory.kt:120-124`）。h2 已协商却被 4 并发卡死。
- 同时在跑的后台请求（全部经同一队列）：
  - `LibraryStore` 启动即 `homeContent`（内部 `Semaphore(4)`，每个媒体库 latest + count，`EmbyHomeService.kt:94, 128-238`），日志 `load_completed` 23–33 s；`HomeStore.loadResume` 对**每台**服务器再跑一次 `homeContent`（`HomeStore.kt:523-543`），默认服务器被跑两遍。
  - 首页日历 `HomeComponent.refreshCalendar` → 每台服务器 `nextUpEpisodes` + 全库 `seriesIdentityCatalog`（`EmbyLookupService.kt:32-42`）+ `providerIndex`，`SERIES_LOOKUP_TIMEOUT_MS = 8_000`（`AiringCalendarRepository.kt:844, 1303`）；详情页打开时 `DetailComponent.kt:201` 再触发 `seriesCalendar`。
  - `ServerSyncManager` 全库分页快照；`ServerHealthMonitor` 每 60 s 探测全部服务器（`ServerHealthMonitor.kt:121, 148, 387`），对 Unauthorized 服务器照探。
  - 详情页自己的跨服务器资源比较：每台服务器 1–3 个请求、单服务器预算 8 s（`EmbySourceService.kt:30`），`Semaphore(4)`（`EmbyRepository.kt:815`）。
  - 首页 Hero/日历卡片的标题匹配 `search`（`HomeStore.kt:619`、`HomeComponent.kt:211`）：两份日志共 32 次 Unauthorized。
- 方案：
  1. `maxRequestsPerHost` ≥ 16、`maxRequests` ≥ 32（h2 下几乎零成本；HTTP/1.1 由连接池自限）。连接池保持共享（`HttpClientFactory.android.kt:30-36`）。
  2. 增加进程级"前台请求门"：详情页/播放器活跃时，Home/Library enrichment、ServerSync、日历目录扫描、健康探测、资源比较让路。现有 `PlayerStore.awaitForeground`（`PlayerStore.kt:1296`）是同一思路，但只在点播放后生效，应前移到页面打开。
  3. 按服务器的统一熔断：`SourceLookupCooldown`（30 s，仅资源比较）、`ServerSync` 15 min 退避、`PlaybackSync` 30 min 冷却各自为政；日历、首页 enrichment、search、健康探测没有熔断。建议共享一个"服务器状态"（Unauthorized / Cloudflare 拒绝 / 超时）供所有后台功能查询，冷却 5–30 min，只有用户显式操作才绕过。
- 收益：这是把 PlaybackInfo 从 1–2 s 拉回 0.2–0.3 s、让 5 s 预解析稳定成功的根本手段。风险：放宽并发对弱服务器压力变大，必须与门控、熔断一起上。

### 2.2 [P0] 播放目标预解析 5 s 超时后全部丢弃，点播放从头再跑

- 根因：`DetailExecutor.kt:378-380` 把"全剧目录 + 目标集 playback 详情"两次串行请求包在 `withTimeoutOrNull(PLAYBACK_RESOLUTION_TIMEOUT_MS = 5_000)`（`DetailStoreExecutionSupport.kt:48`）里，排队时间计入；超时取消后 `PlaybackMetadataCache.kt:54-58` 删除失败条目，已经排到队头的响应作废；失败回调 `DetailExecutor.kt:398` **追加** `loadSources` 跨服比较，当前服务器又多 2–3 个请求（含 `Season=N&Limit=10000&Fields=MediaSources,MediaStreams`，`EmbySourceService.kt:301-309`）；点播放时 `playTarget == null` 走 `DetailExecutor.kt:1170-1199`，同样的两次请求 + 5 s 预算再来一遍。
- 方案：超时不取消底层请求，让它完成并写入缓存（`supervisorScope` 或分离所有者）；目录与目标集拆成独立预算，成功一半保留一半；失败路径不再立刻 `loadSources`；剧集入口先用一条轻请求拿目标集（`NextUp` 或 `Limit` 目录），全目录只服务集列表。
- 收益：点播放即发布选择。风险：低；补 `DetailStoreTest` 超时用例。

### 2.3 [P1] `cachedResumeEpisode` 把轻量快照当播放目标（今日改动引入的回归）

- 根因：`EmbyRepository.kt:782-787` 只读 `detailSnapshots`，而该缓存现在存的是 `includePlaybackFields = false` 的结果；`DetailExecutor.kt:448-452` 命中后直接返回，不再 `playbackItemDetail`。30 s 内先开某集再开该剧 → 播放键就绪但 `versions` 为空，杜比角标 / 版本 / 媒体信息全部消失。
- 方案：命中后仍调用 `playbackItemDetail`（15 s 缓存多半命中），或要求 `versions.isNotEmpty()`。风险：无。

### 2.4 [P1] 播放键就绪前后，本页自己追加的三类重请求

- (a) 选择发布即 `loadSources` 全服比较（`DetailExecutor.kt:784-791`），对当前服务器也做标题查找 + 整季 MediaStreams，只为"资源"区块一个规格标签。→ 当前服务器直接用已知 `playTarget.versions` 生成，其它服务器延后到首帧之后。
- (b) `playTarget` 就绪即新建预备 `PlayerStore`（`DetailComponent.kt:325-416`）：立即 PlaybackInfo，Ready 后 `awaitForeground` 直接放行（`PlaybackLaunchTiming.kt:52`），并行拉 `playbackItemDetail(seriesId)` + **全剧 `Episodes?Fields=…MediaSources,MediaStreams,Chapters`**（`PlayerStore.kt:1313-1331`），与 2.2 的目录请求不共享缓存（键含 `includePlaybackSources`，`EmbyDetailService.kt:45-46, 177`）。`PlaybackPreloadKey` 含 `startPositionTicks`（`DetailComponent.kt:332-338`），同步拉回进度会 dispose 并重建 Store。→ 预备阶段只做 PlaybackInfo，全剧目录等领取后再拉；把 `catalogEpisodes` 传给 PlayerStoreFactory 复用；预热键去掉位置。
- (c) `loadWatchLater` 每次打开做 2 次串行请求（`EmbyBrowseService.kt:464-517`），且入队顺序在 playback 请求之前（`DetailExecutor.kt:295/312` 早于 `:296/313`）。→ 播放列表 id 按服务器缓存，membership 用 `Items?Ids=` 一次查询，顺序移到 playback 之后。

### 2.5 [P1] 大 JSON 在主线程反序列化

- 根因：`DetailStore.kt:25` 默认 `mainContext = Dispatchers.Main`，`DetailExecutor.kt:55-57` 的所有 `repo.*` 调用在其中执行，Ktor 在调用协程里解码；全剧目录（Overview / MediaStreams / Chapters）可达数 MB。日志 `jank_summary longest_ms 80–99`。`ServerSyncManager.kt:499-500`、`LibraryStore.kt:233` 已注意到这一点并切了线程。
- 方案：`DetailExecutor` / `PlayerStore` 的仓库调用包 `withContext(Dispatchers.Default)`，`dispatch` 留在 Main。

### 2.6 [P2] 首屏期整段重组与离屏录制

- `AnimatedColorContent`（`DominantColor.kt:159-164`）在组合期读动画 State，`DetailScreen.kt` 十几处用它包住整块区域；取色完成后的强调色动画期间每帧重组这些区块。`sourcePresentation`（`DetailScreen.kt:261-270`）未 `remember`；`comparableSources` 键含 `sourceHealth`，健康探测每 60 s 触发重算。
- 顶栏透明时也在录制/模糊整页：`backdropSource(detailBackdrop)` 未传 `record`（`DetailScreen.kt:508`），`backdropBlur` 未传 `alpha`（`DetailHero.kt:326-330`）；`Backdrop.kt:183-187, 219-220` 的注释说明了应关闭的条件。
- 方案：颜色 State 下推到 `drawBehind` / `graphicsLayer` 里读（`DetailHero.kt:408-437` 已是范式）；两处 backdrop 传入进度条件。

### 2.7 [P2] 缓存策略：重开同一页除首帧外几乎全量重请求

- `detailSnapshots` 30 s 只供首帧 peek；轻量合并只看 `detailSnapshots`，不看 `playbackDetails`（`EmbyRepository.kt:739-761`）；`seasons/episodes(含 MediaSources)`、watch-later、`compareSources` 成功结果均无缓存。轻量首屏字段仍带 `People`（`EmbyDetailService.kt:343-344`）。
- 方案：`compareSources` 成功结果按 (server, item, season, episode) 缓存 60 s；季/集目录接入 `PlaybackMetadataCache` 30–60 s；轻量合并把 `playbackDetails.peek` 也作为来源；People 移到单独请求。

## 3. 播放：发现与方案

### 3.1 [P0] PlaybackInfo 与起播期请求同受 2.1 影响

日志证明：并发期 1006 / 1410 / 1810 / 2014 / 2095 ms，空闲 168–342 ms。修法同 2.1；起播期间（`play_requested` 到首输出）应是前台门的最高优先级。

### 3.2 [P0] 探测按最坏情况串行：平台 8 s + 增强 18 s，服务器已知的编码信息没有用来跳过

- 流程：`AndroidCore2RouteEvaluator.resolveProbe`（`AndroidCore2MediaProbe.kt:643-700`）先 `platformProbe.probe`（限 8 s，`:187-215`），失败或 `requiresEnhancedTruthProbe` 再 `enhancedProbe.probe`（限 18 s），两者串行且同步阻塞 IO 线程。平台探测用 `MediaExtractor` 经 `AndroidTransportMediaDataSource` 读远端（`AndroidMediaExtractorDemuxNode.kt:447-480`），MKV 头 + 尾部 Cues 至少两个 2 MiB 块，普通 HEVC/AAC 也要 3.3 s；EAC3 MKV 平台 extractor 给不出可用音频，打满 8 s 后再走增强 8.8 s。
- 服务器 `MediaStreams` 已提供容器 / 视频 / 音频编码 / DV 标记，`sourceHints` 里已经带到 `YMediaItem`，但只用于 DV 判定，不用于选探测路径。
- 已验证路由记忆 `AndroidYCoreVerifiedRouteMemory` 需要 `cacheIdentity`、真实音视频输出后才写入、身份含初始选轨（`:100-108`，`AndroidAdaptiveCore2YPlayer.kt:1481-1492`）；日志里没有 `verified_route_reused`，同一条目第二次播放（09-22 05:15 与 09-21 13:15 同为 111663）仍全量探测——1.0.78 改用服务器 DirectStreamUrl 后缓存身份变化是最可能原因，需加日志核实。
- 方案（结构改动）：
  1. 依据 `sourceHints`（容器 + 音频编码 ∈ {EAC3, TrueHD, DTS…} 或 DV 非 P5/P8）直接进入增强探测，或平台与增强**并行**、先出结果者胜，另一个取消。
  2. 平台探测的头/尾读取用现有冷读小片段（`STARTUP_RANGE_BYTES` 128 KiB）而非 2 MiB 块；尾部 Cues 单独一个小 Range。
  3. Range 返回 403/4xx 时立即终止整个探测预算（09-21 13:15：403 后仍等满 8 + 18 s）。
  4. 路由记忆的身份改用与凭据、会话、重定向无关的媒体身份（item id + MediaSource id + 文件大小），并在不命中时打日志说明原因。

### 3.3 [P1] 详情页 YCore 预热从未生效，且无声

- 触发条件链：`canPreloadSource`、`!loading`、`error == null`（`DetailComponent.kt:271`）→ 缓存开启 + Unmetered + 非省电 + Core2 接管（`PlaybackPreloader.android.kt:59-78`）→ 500 ms 延迟 → `speculativeNextItemWork` 15 s 预算，`allowed` 里再查 Unmetered / 省电 / 内存（`AndroidNextItemPreparation.kt:62-65, 98-128`）。`allowed` 变假时 `budget.cancel` 抛 `CancellationException`，外层原样 rethrow（`AndroidCurrentItemPreparation.kt:126-128`），**不打任何日志**。
- 领取条件：`AndroidCurrentItemPreparation.claim`（`:170-195`）要求 `matchesPreparedSource` + `sourceHints` 相等、`AndroidPreparedExtractorSlot.take`（`:36-52`）要求 uri / headers / credentials / cacheIdentity / initialTrackSelection 全等、**`positionMs` 精确相等**、30 s 租约未过期。领取失败同样无日志。
- 续播时只做 extractor seek，不下载字节（`AndroidCurrentItemPreparation.kt:76-84` 仅 `positionMs == 0L` 才 `warmNextItemBytes`）。
- 方案：先加"未触发 / 未领取原因"日志（网络类别、省电、内存、uri 变化、位置差、租约过期）；`positionMs` 允许容差或以已准备位置为准；租约延长到详情页存活期；续播也预热目标位置附近的块；若 Wi‑Fi 被系统标记为计费网络则在设置里给出提示（当前完全静默）。

### 3.4 [P1] 引擎构建到第一个 Range 1.4–3.1 s，且日志误导

- `engine_attached Exo` 实际是占位引擎 `PreparingVideoEngine`（`PlaybackEngineSlot.kt:141, 237-255`），`kind == null` 时标签回退到用户偏好名（`PlayerRoot.kt:611-616`）；并不存在真实 Exo 打开。真正的耗时在 `reserveConstruction` 等上一个引擎释放（`PlaybackEngineSlot.kt:172`）+ `AndroidCore2TrialFactory` 构建（GPU runtime probe、能力探测、代理、`AndroidAdaptiveCore2YPlayer` + `prepare()`，`AndroidCore2Trial.kt:162-203`）+ Cronet 引擎首次初始化 + 数据源创建。当前没有分段计时。
- 方案：占位引擎日志改为 `Preparing`；`YCoreStartupTiming` 增加 `engine_construct`、`transport_first_open` 两段；Cronet 引擎与 GPU runtime probe 在应用启动或详情页阶段进程级预热；`AndroidAdaptiveCore2YPlayer` 构建放到点播放前（详情页预备 Store 已经存在，可一并构建未开源的播放器）。

### 3.5 [P1] 路由决定后 3–4.6 s 才出画：解码器创建晚、续播 seek 精度差

- 09-22 样本：`route_selected` 37.674 → `native_direct_seek` 37.777 → `video_decoder_candidates` 39.523（`AndroidMediaCodecVideoNode.kt:243`）→ 首音频 40.294 → 首视频 42.285；`position_corrected` 误差 1723 ms。探测阶段已拿到 `MediaFormat`，解码器却等 seek 与首样本之后才选择并配置。起播门槛 500 ms（`YBufferController.kt:25, 183`）合理，不是瓶颈。
- 方案：路由决定后立即用探测得到的格式并行 `configure` 解码器（DV 走 `c2.dolby` 时同理），与 seek / 首块下载重叠；续播 seek 使用 Cues 精确定位并复用预热的 extractor 位置。

### 3.6 [P1] 头块被不同读者重复下载

- 09-20 会话：block 0 在 Cronet 直读、OkHttp 代理、增强探测、正式播放里 `loadordinal 1 / 3 / 7` 至少下载 4 次。每个 `AndroidTransportMediaDataSource` 实例有自己的内存块表（`:108`），磁盘缓存异步写入；探测器实例关闭后播放实例重新走网络。`proxy_media_validation_reused` 只覆盖代理内的表示校验，不覆盖块数据。
- 方案：按 `cacheIdentity` 的进程级小容量共享块表（仅头 / 尾块，几个块即可）；或探测完成时把已下载块与 extractor 一起交接（现有 `adopt` 仅 NativeDirect 路径）。

### 3.7 [P2] 跨源重定向后的 403 与 http/1.1 回退

- 09-22 样本首个 Range `redirectcount = 2`、`http/1.1`；09-21 13:15 样本中段（offset 393216）与尾部 Range 返回 403（`exceptionchain w5b`），随后探测全部打满、53 s 才出画。重定向目标记忆已有（`AndroidHttpMediaRedirectState`，`AndroidHttpMediaTransport.kt:268-303`），`EmbyPlaybackInterceptor` 跨源剥离 `X-Emby-*`（`EmbyPlaybackInterceptor.kt`）是正确的安全行为，但由此产生的 403 应被立即识别为"跨源凭据缺失"并回退原始 URL，而不是消耗探测预算。Cronet 路径是否复用重定向目标未证实。

### 3.8 [P2] 输出代次重启

09-20 会话 `first_video_output` 在 generation 3 与 5 各出现一次（相隔 10 s），SoftwareFallback / Enhanced 路径的 Surface 或 GPU 输出重建（`AndroidEnhancedPlaybackSession.kt:918`、`AndroidVulkanVideoOutput.kt:109`）。需要在该路径加重建原因日志后再定位。

## 4. 已经做对、不要改

- 首屏只等一个轻量请求或 30 s 快照，骨架屏不等待富化（`DetailScreen.kt:488-491`）；轻量 / playback 字段拆分与 `retainPlaybackMetadata`（`EmbyRepository.kt:731-776`）。
- 剧集目录 30 s 原始 DTO 缓存 + 进度重投影（`EmbyDetailService.kt:45-46, 98`）；季列表复用目录。
- 播放路径 `dispatchPlaybackSelection(compareSources = false)` 与 `awaitForeground` 让后台目录给首帧让路——机制正确，只是触发太晚。
- 共享连接池让 YCore 复用 API 的 TLS 连接（`HttpClientFactory.android.kt:18-36`）；只调 Dispatcher，不拆池。
- 9 月 20 日已落地的 A–F：元数据快照、探测事实共享、FFmpeg 短分析、OkHttp 连接池、首输出前延后可选请求、剧集目录一次读取、初始选轨、代理校验复用。
- 起播缓冲门槛 500 ms；`SourceLookupCooldown`、ServerSync 15 min 退避、PlaybackSync 30 min 冷却；取色在 `Dispatchers.Default`；Coil 内存 / 磁盘缓存配置。
- `EmbyPlaybackInterceptor` 跨源剥离凭据、`static=true` 直读原文件。

## 5. 实施顺序与验收

**第一批（低风险，改常量与策略）**
1. Dispatcher 放宽到 16 / 32；前台请求门（详情页与播放器活跃时后台让路）。
2. 预解析超时不丢缓存、不追加跨服比较；目录与目标集拆预算。
3. `cachedResumeEpisode` 补 playback 字段。
4. `DetailExecutor` / `PlayerStore` 仓库调用切到 `Dispatchers.Default`。
5. 日志：占位引擎改名、预热未触发 / 未领取原因、引擎构建与首 Range 分段、路由记忆未命中原因。

**第二批（中等改动）**
6. 按服务器统一熔断，覆盖日历、首页 enrichment、search、健康探测、资源比较。
7. 首页对默认服务器的重复 `homeContent` 去重；预备 `PlayerStore` 惰性化，全剧目录延后到领取后；预热键去掉位置。
8. 资源比较当前服务器本地生成；成功结果缓存 60 s；watch-later 缓存。
9. 探测预算在 Range 4xx 时立即终止；跨源 403 回退原始 URL。

**第三批（结构改动，需真机矩阵回归）**
10. 探测按 `sourceHints` 直选或平台 / 增强并行；头尾小片段读取。
11. 解码器与 seek / 首块并行配置；续播预热带位置。
12. 头块跨实例共享；Cronet / GPU runtime 进程级预热；`AndroidAdaptiveCore2YPlayer` 提前构建。

**验收指标（沿用现有 `detail_load_stage` / `playback_launch_stage`，每组 ≥ 20 次报 p50）**
- `play_target_ready`：p50 < 1.5 s，失败率 < 5%（现状：两次全失败）。
- PlaybackInfo 在页面加载并发期：< 500 ms（现状 1–2.2 s）。
- 点击到 `first_video_output`：远程 HEVC/AAC p50 < 4 s（现状 6.7 s）；EAC3 / DV < 10 s（现状 12–43 s）；Range 403 场景 < 5 s 内报错而非 54 s。
- 每次起播头块网络下载次数 = 1（现状最多 4）。
- 出现 `current_item_preparation_reused` 或明确的未领取原因日志。

## 6. 边界

- 所有耗时来自用户设备的四个进程会话，样本少、网络与服务器状态未控制，不能推导固定节省秒数；结论是"哪些段可归因、可改"，收益需按第 5 节指标复测。
- 播放链路的子代理两次因连接中断失败，第 3 节由主会话逐段读码完成，覆盖了日志能看到的每一段，未覆盖增强路径内部（FFmpeg 分析预算、软件音频初始化）的细节。
- 未在真机安装或操作；未运行 Gradle。

---
可分享页面：https://claude.ai/artifact/46xzQSUEin7imeBAfE7ghX
