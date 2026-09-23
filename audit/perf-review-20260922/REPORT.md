# 详情页与播放提速审查 · 2026-09-22

原始审查时间点：HEAD `1ee4fe28` 加当时的未提交改动；证据为用户提供的两份诊断包（`audit/diagnostics-20260921-130754`、`Yfuse-diagnostics-20260922-150836.zip`，设备 OPPO PLG110 / Android 17，导出包为 1.0.79，日志还包含旧进程，远程 HTTPS 服务器，5 GB 级 HEVC MKV）。关键事件摘录见同目录 `device-timelines.txt`。2026-09-23 的代码和原始日志复核见第 7 节；第 1–6 节保留原审查时的观察与建议，其“当前源码”“已修复”等措辞不能直接用于描述今天的安装包。

## 0. 结论

**详情页**：所测首屏为网络 2.1 s、缓存 0.03 s，"播放键就绪"更慢。剧集无可复用目标时会串行取得目录和目标集播放字段，受 5 s 预算约束。它与首页、媒体库、日历、同步、健康探测、跨服务器资源比较使用共享 Emby API 客户端；该客户端的 OkHttp Dispatcher 上限是每主机 4 / 全局 8，但这批日志没有分段数据证明具体请求在队列里等了多久。后台存在超时或被拒绝的服务器请求；当前失败路径还会追加跨服比较，点播放时可能重新解析。代表性记录：4364 ms、1351 ms、两次预解析失败（从详情加载起算为 7085 / 5108 ms）。

**播放**：点击到首帧的所测路径为 6.7 s、12.4 s、43.5 s、54.0 s。可观察分段：PlaybackInfo 在并发窗口 1.0–2.2 s、较空闲窗口 0.17–0.35 s；引擎挂接到首个媒体 Range 约 1.4–3.1 s；平台探测 3.3 s 或触及 8 s 预算，增强探测 8.8–18 s。详情页 YCore 预热在已查看样本中没有成功领取的日志，未触发或未领取的原因目前不可区分。

两条线共用一个值得优先验证的假设：**共享 API 客户端的请求竞争**。队列容量和后台请求的代码证据充分，但日志没有请求入队、出队及连接复用时长，因此还不能把每一次 PlaybackInfo 延迟定量归因于 OkHttp 排队。放宽队列、前台让路与失效服务器退避应作为同一组实验验证。

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

### 2.1 [P0] 共享 OkHttp Dispatcher 配置为每主机 4 / 全局 8；前后台 API 请求存在竞争风险

- 已证实的配置：`HttpClientFactory.android.kt` 把共享 Emby API 客户端的 Dispatcher 设为 `maxRequestsPerHost = 4`、`maxRequests = 8`；`AppModule.kt` 注入同一个 Emby 客户端。日志显示前后台请求并发、部分后台请求超时。**尚未记录 OkHttp 队列等待、DNS/TLS、服务器处理与响应体解码的分段时长**，所以“h2 已协商却被 4 并发卡死”和“本次延迟的根因就是队列”仍是待测假设；YCore 媒体 Range 也不能直接计入这个 API Dispatcher 的 8 个槽。
- 同时在跑的后台请求（全部经同一队列）：
  - `LibraryStore` 启动即 `homeContent`（内部 `Semaphore(4)`，每个媒体库 latest + count，`EmbyHomeService.kt:94, 128-238`），日志 `load_completed` 23–33 s；`HomeStore.loadResume` 对**每台**服务器再跑一次 `homeContent`（`HomeStore.kt:523-543`），默认服务器被跑两遍。
  - 首页日历 `HomeComponent.refreshCalendar` → 每台服务器 `nextUpEpisodes` + 全库 `seriesIdentityCatalog`（`EmbyLookupService.kt:32-42`）+ `providerIndex`，`SERIES_LOOKUP_TIMEOUT_MS = 8_000`（`AiringCalendarRepository.kt:844, 1303`）；详情页打开时 `DetailComponent.kt:201` 再触发 `seriesCalendar`。
  - `ServerSyncManager` 全库分页快照；`ServerHealthMonitor` 每 60 s 探测全部服务器（`ServerHealthMonitor.kt:121, 148, 387`），对 Unauthorized 服务器照探。
  - 详情页自己的跨服务器资源比较：每台服务器 1–3 个请求、单服务器预算 8 s（`EmbySourceService.kt:30`），`Semaphore(4)`（`EmbyRepository.kt:815`）。
  - 首页 Hero/日历卡片的标题匹配 `search`（`HomeStore.kt:619`、`HomeComponent.kt:211`）：两份日志共 32 次 Unauthorized。
- 方案：
  1. 先记录按请求用途区分的入队、出队、连接建立、响应头和解码耗时，再在同一弱服务器与正常服务器上分级试验 `8/16`、`16/32`，并观察 429/5xx、连接数及后台完成时间。HTTP/2 并发也受服务器流数和 CPU 限制；HTTP/1.1 连接池不等于主动限流。连接池保持共享（`HttpClientFactory.android.kt`）。
  2. 增加进程级"前台请求门"：详情页/播放器活跃时，Home/Library enrichment、ServerSync、日历目录扫描、健康探测、资源比较让路。现有 `PlayerStore.awaitForeground`（`PlayerStore.kt:1296`）是同一思路，但只在点播放后生效，应前移到页面打开。
  3. 按服务器的统一熔断：`SourceLookupCooldown`（30 s，仅资源比较）、`ServerSync` 15 min 退避、`PlaybackSync` 30 min 冷却各自为政；日历、首页 enrichment、search、健康探测没有熔断。建议共享一个"服务器状态"（Unauthorized / Cloudflare 拒绝 / 超时）供所有后台功能查询，冷却 5–30 min，只有用户显式操作才绕过。
- 预期收益待实验验证，不能把空闲样本 0.2–0.3 s 当作并发调整后的承诺值。风险是弱服务器压力、限流和连接竞争增加，门控与退避需一起测。

### 2.2 [P0] 播放目标预解析 5 s 超时后失败条目不保留，点播放可能重做

- 代码路径：剧集播放目标解析把目录和目标集 playback 详情放在同一个 `withTimeoutOrNull(PLAYBACK_RESOLUTION_TIMEOUT_MS = 5_000)` 下；超时取消的缓存所有者失败时，`PlaybackMetadataCache` 删除该条目。**成功完成的其他缓存条目未必丢失**。失败回调还追加 `loadSources` 跨服比较；点播放时若 `playTarget == null`，会再次尝试目标解析。目录是否实际重走网络取决于其独立缓存和时间窗口。
- 方案：把目录与目标集拆成独立预算，成功一半保留一半；失败路径不再立刻 `loadSources`；剧集入口先用轻请求确定目标集。若让超时后的请求继续完成，必须绑定页面/服务器/条目代次、设置独立硬上限，并限制同时在飞的任务；不能直接用无限存活的 `supervisorScope` 将失效请求留在后台。
- 收益：点播放即发布选择。风险：低；补 `DetailStoreTest` 超时用例。

### 2.3 [P1] `cachedResumeEpisode` 把轻量快照当播放目标（今日改动引入的回归）

- 静态复核：`cachedResumeEpisode` 从 `detailSnapshots` 取剧集，`resolveInitialPlaybackSelection` 命中后直接返回，不再调用 `playbackItemDetail`。当该快照来自 `includePlaybackFields = false` 且之前没有完整字段可供 `retainPlaybackMetadata` 合并时，`versions` 可以为空。原报告的“30 s 内先开某集再开该剧”是**触发条件假设**，现有诊断未证明该 UI 回归实际发生；应补针对轻量快照命中的回归测试。
- 方案：命中后仍调用 `playbackItemDetail`（15 s 缓存多半命中），或要求 `versions.isNotEmpty()`。风险：无。

### 2.4 [P1] 播放键就绪前后，本页自己追加的三类重请求

- (a) 选择发布即 `loadSources` 全服比较（`DetailExecutor.kt:784-791`），对当前服务器也做标题查找 + 整季 MediaStreams，只为"资源"区块一个规格标签。→ 当前服务器直接用已知 `playTarget.versions` 生成，其它服务器延后到首帧之后。
- (b) `playTarget` 就绪即新建预备 `PlayerStore`（`DetailComponent.kt:325-416`）：立即 PlaybackInfo，Ready 后 `awaitForeground` 直接放行（`PlaybackLaunchTiming.kt:52`），并行拉 `playbackItemDetail(seriesId)` + **全剧 `Episodes?Fields=…MediaSources,MediaStreams,Chapters`**（`PlayerStore.kt:1313-1331`），与 2.2 的目录请求不共享缓存（键含 `includePlaybackSources`，`EmbyDetailService.kt:45-46, 177`）。`PlaybackPreloadKey` 含 `startPositionTicks`（`DetailComponent.kt:332-338`），同步拉回进度会 dispose 并重建 Store。→ 预备阶段只做 PlaybackInfo，全剧目录等领取后再拉；把 `catalogEpisodes` 传给 PlayerStoreFactory 复用；预热键去掉位置。
- (c) `loadWatchLater` 每次打开做 2 次串行请求（`EmbyBrowseService.kt:464-517`），且入队顺序在 playback 请求之前（`DetailExecutor.kt:295/312` 早于 `:296/313`）。→ 播放列表 id 按服务器缓存，membership 用 `Items?Ids=` 一次查询，顺序移到 playback 之后。

### 2.5 [P1] 大 JSON 在主线程反序列化

- 代码显示 `DetailStore` 的执行器默认在 `Dispatchers.Main`，而仓库请求从这些协程发起；日志另有 `jank_summary longest_ms 80–99`。**仅凭这两项无法证明 JSON 解码恰在主线程、或该解码造成了这些卡顿**。需用主线程堆栈/trace 与解码时间戳关联后再定根因。
- 方案：先为目录响应解码和主线程帧耗时加关联追踪；若确认解码落在 Main，再把解析移至适合 CPU 工作的调度器并保持状态派发在 Main，避免盲目移动所有仓库调用。

### 2.6 [P2] 首屏期整段重组与离屏录制

- `AnimatedColorContent`（`DominantColor.kt:159-164`）在组合期读动画 State，`DetailScreen.kt` 十几处用它包住整块区域；取色完成后的强调色动画期间每帧重组这些区块。`sourcePresentation`（`DetailScreen.kt:261-270`）未 `remember`；`comparableSources` 键含 `sourceHealth`，健康探测每 60 s 触发重算。
- 顶栏透明时也在录制/模糊整页：`backdropSource(detailBackdrop)` 未传 `record`（`DetailScreen.kt:508`），`backdropBlur` 未传 `alpha`（`DetailHero.kt:326-330`）；`Backdrop.kt:183-187, 219-220` 的注释说明了应关闭的条件。
- 方案：颜色 State 下推到 `drawBehind` / `graphicsLayer` 里读（`DetailHero.kt:408-437` 已是范式）；两处 backdrop 传入进度条件。

### 2.7 [P2] 缓存策略：重开同一页除首帧外几乎全量重请求

- `detailSnapshots` 30 s 只供首帧 peek；轻量合并只看 `detailSnapshots`，不看 `playbackDetails`（`EmbyRepository.kt:739-761`）；`seasons/episodes(含 MediaSources)`、watch-later、`compareSources` 成功结果均无缓存。轻量首屏字段仍带 `People`（`EmbyDetailService.kt:343-344`）。
- 方案：`compareSources` 成功结果按 (server, item, season, episode) 缓存 60 s；季/集目录接入 `PlaybackMetadataCache` 30–60 s；轻量合并把 `playbackDetails.peek` 也作为来源；People 移到单独请求。

## 3. 播放：发现与方案

### 3.1 [P0] PlaybackInfo 与起播期请求同受 2.1 影响

日志显示：并发期 1006 / 1410 / 1810 / 2014 / 2095 ms，较空闲窗口 168–342 ms。相关性成立，排队耗时占比尚未测量；按 2.1 先加请求分段，再验证前台让路对起播阶段的效果。

### 3.2 [P0] 所测旧版本曾串行耗尽平台 8 s + 增强 18 s；现行降级条件需复测

- 流程：`AndroidCore2RouteEvaluator.resolveProbe`（`AndroidCore2MediaProbe.kt:643-700`）先 `platformProbe.probe`（限 8 s，`:187-215`），失败或 `requiresEnhancedTruthProbe` 再 `enhancedProbe.probe`（限 18 s），两者串行且同步阻塞 IO 线程。平台探测用 `MediaExtractor` 经 `AndroidTransportMediaDataSource` 读远端（`AndroidMediaExtractorDemuxNode.kt:447-480`），MKV 头 + 尾部 Cues 至少两个 2 MiB 块，普通 HEVC/AAC 也要 3.3 s；EAC3 MKV 平台 extractor 给不出可用音频，打满 8 s 后再走增强 8.8 s。
- 服务器 `MediaStreams` 已提供容器 / 视频 / 音频编码 / DV 标记，`sourceHints` 里已经带到 `YMediaItem`。旧设备样本仍进入了长探测链；当前代码还会用提示决定短分析与部分降级条件，因此“提示只用于 DV 判定”并非现行实现的完整描述。
- `AndroidYCoreVerifiedRouteMemory` 的命中依赖缓存身份，真实音视频输出后才写入。**原审查把两个样本误认成同一可复用起播**：09-21 13:15 的 53.981 s 样本是条目 `1246466`；09-22 05:15 的 12.393 s 样本是条目 `111663`。09-21 日志里另有 `111663` 的 HTTP 403 快速失败，但匿名服务器引用、进程和应用版本与 09-22 样本不同。日志未见 `verified_route_reused` 只能说明这些记录里没有命中证据，不能据此判断命中率或把不命中归因于 1.0.78 的 URL 改动。
- 方案（结构改动）：
  1. 评估依据 `sourceHints`（容器 + 音频编码 ∈ {EAC3, TrueHD, DTS…} 或 DV 非 P5/P8）选择探测器，或平台与增强并行；服务器提示可能与实际字节流不符，最终路由仍须由探测事实确认，不能简单“先返回者胜”。
  2. 评估用较小的头/尾冷读片段代替固定 2 MiB 块；需要按容器验证 Cues 和元数据完整性，不能对所有 MKV 都强制一个 128 KiB 上限。
  3. Range 4xx 需按**同一 URL / 资源 / 偏移的失败**传播，停止对该失败源的无意义重复探测；保留已有的安全候选路径和可恢复分支。09-21 13:15 的两处 403 后最终仍成功出画，不能把“任意 403 即终止整个播放”作为正确行为。
  4. 先记录路由记忆未命中的原因和匿名化身份字段，再决定是否改键；不能直接删掉会话/授权上下文，使一个身份下验证过的路由被错误复用于另一份字节流。

### 3.3 [P1] 已查看样本中没有 YCore 预热领取的证据，缺少拒绝原因日志

- 触发条件链：`canPreloadSource`、`!loading`、`error == null`（`DetailComponent.kt:271`）→ 缓存开启 + Unmetered + 非省电 + Core2 接管（`PlaybackPreloader.android.kt:59-78`）→ 500 ms 延迟 → `speculativeNextItemWork` 15 s 预算，`allowed` 里再查 Unmetered / 省电 / 内存（`AndroidNextItemPreparation.kt:62-65, 98-128`）。`allowed` 变假时 `budget.cancel` 抛 `CancellationException`，外层原样 rethrow（`AndroidCurrentItemPreparation.kt:126-128`），**不打任何日志**。
- 领取条件：`AndroidCurrentItemPreparation.claim` 要求 `matchesPreparedSource` + `sourceHints` 相等；`AndroidPreparedExtractorSlot.take` 比较 uri / headers / credentials / cacheIdentity / initialTrackSelection 等上下文，保留 30 s 租约；**`positionMs` 精确相等是在 `claim` 取出槽位之后检查**。失败原因没有完整日志。
- 续播时只做 extractor seek，不下载字节（`AndroidCurrentItemPreparation.kt:76-84` 仅 `positionMs == 0L` 才 `warmNextItemBytes`）。
- 方案：先加"未触发 / 未领取原因"日志（网络类别、省电、内存、uri 变化、位置差、租约过期）；用统计确认哪个条件主要阻止复用，再考虑位置容差或续播预热。位置不同不能直接复用旧 extractor 而跳过必要 seek；延长租约须同时限制持有的内存、连接和释放时机。

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

- 09-22 样本首个 Range 经过重定向且为 `http/1.1`；09-21 13:15 样本中段（offset 393216）与尾部 Range 返回 403，随后仍成功出画。重定向目标记忆与跨源凭据剥离都有代码证据，**但日志不含可安全比较的原始/最终源身份、重定向目标的权限策略或服务端拒绝原因**，不能把 403 诊断成"跨源凭据缺失"。先记录脱敏后的同源性、重定向次数、目标复用与失效原因、Range 偏移和状态码；只有证明是缓存重定向目标失效时，才清除目标并从原始 URL 重新协商，绝不向跨源目标补送 Emby 凭据。Cronet 路径是否复用重定向目标仍待验证。

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

**第一批（低风险，先测量再改策略）**
1. 增加 API 请求排队与响应分段；前台请求门（详情页与播放器活跃时后台让路）；在同源对照下试验 Dispatcher 8/16 与 16/32。
2. 预解析超时不丢缓存、不追加跨服比较；目录与目标集拆预算。
3. 给仅有轻量快照的 `cachedResumeEpisode` 补回归测试，再补 playback 字段。
4. 用 trace 确认详情解码是否在 Main 造成卡顿，命中后只移动相应解析工作。
5. 日志：占位引擎改名、预热未触发 / 未领取原因、引擎构建与首 Range 分段、路由记忆未命中原因。

**第二批（中等改动）**
6. 按服务器统一熔断，覆盖日历、首页 enrichment、search、健康探测、资源比较。
7. 首页对默认服务器的重复 `homeContent` 去重；预备 `PlayerStore` 惰性化，全剧目录延后到领取后；预热键去掉位置。
8. 资源比较当前服务器本地生成；成功结果缓存 60 s；watch-later 缓存。
9. 按失败源/偏移传播 Range 4xx，区分终态与可恢复候选；仅在证据表明缓存的重定向目标失效时清除并重新协商原始 URL。

**第三批（结构改动，需真机矩阵回归）**
10. 探测按 `sourceHints` 直选或平台 / 增强并行；头尾小片段读取。
11. 解码器与 seek / 首块并行配置；续播预热带位置。
12. 头块跨实例共享；Cronet / GPU runtime 进程级预热；`AndroidAdaptiveCore2YPlayer` 提前构建。

**验收指标（沿用现有 `detail_load_stage` / `playback_launch_stage`，每组 ≥ 20 次报 p50）**
- `play_target_ready`：p50 < 1.5 s，失败率 < 5%（09-22 同一会话的两次记录均失败；这不足以估算总体失败率）。
- PlaybackInfo 在页面加载并发期：< 500 ms（现状 1–2.2 s）。
- 点击到 `first_video_output`：远程 HEVC/AAC p50 < 4 s（样本 6.7 s）；EAC3 / DV < 10 s（样本 12–43 s）。Range 403 场景分开验收：不可恢复的源在 5 s 内给出可操作错误；有可用候选的源记录恢复耗时和最终首帧，不能把 54 s 后仍成功的样本直接改成失败。
- 每次起播头块网络下载次数 = 1（现状最多 4）。
- 出现 `current_item_preparation_reused` 或明确的未领取原因日志。

## 6. 边界

- 表中四条代表性起播来自多个进程、条目和服务器；样本少，网络与服务器状态未控制，不能推导固定节省秒数；结论是"哪些段可观察、哪些路径可改"，收益需按第 5 节指标复测。
- 播放链路的子代理两次因连接中断失败，第 3 节由主会话逐段读码完成，覆盖了日志能看到的每一段，未覆盖增强路径内部（FFmpeg 分析预算、软件音频初始化）的细节。
- 未在真机安装或操作；未运行 Gradle。

## 7. 2026-09-23 代码与日志复核补充

本节更新**证据解释和代码状态**，不把 1.0.79 及更早版本的真机数据当成 1.0.80 性能结果。复核基线为本地 `master` 的 `b02881412`（`version.properties` 为 1.0.80 / 242）；所附 ZIP 的 `device-info.txt` 明确写的是导出应用 1.0.79 / 241、build `1ee4fe28`，并说明历史事件保留各自进程会话。另一份 09-21 诊断的导出应用为 1.0.77 / 239。本次在该基线上修改代码与测试，但没有生成新 APK，也没有 1.0.80 真机复测。第 1–6 节的源码行号是原审查时的定位，后续实现应以当前源码和符号名重新定位。

### 7.1 证据分层和更正

| 事项 | 日志与代码能确认什么 | 仍不能推出什么 |
| --- | --- | --- |
| 详情页超时 | 09-22 05:15 会话 `8225ca87` 的系列 `111658`：`content_ready` 2081 ms，`play_target_ready` 先后在 7085 / 5108 ms 失败，点播放后 `detail_selection_ready` 1351 ms；基线代码有 5 s 超时、失败后 `loadSources`、点播放现场再解析的路径。本次移除了失败后立即跨服比较。 | 不能仅凭同窗口后台事件证明这两次超时分别在 OkHttp 队列等待了多久。7085 ms 是从本次详情加载起算，不是 5 s 超时预算本身变成了 7 s。 |
| 首帧路径 | 所附日志含 09-22 05:15 的 12,393 ms 首视频、09-21 13:15 的 53,981 ms 首视频；另份诊断含 09-21 05:04 的 6738 ms，摘录含 09-20 的 43,468 ms。 | 这四条是不同条目、服务器/进程及路径的样本，不是同一片源的四次受控重复。不能直接据此计算总体 p50 或固定收益。 |
| 403 有两种结果 | 09-21 13:06 的 `111663` 在首个 Range 403 后 338 / 432 / 537 ms 报 `startup_error`；09-21 13:15 的 `1246466` 在中段/尾部 Range 403 后仍于 53,981 ms 出画。 | 不能把所有 403 当作永久鉴权失败，也不能把这两条合并为同一条目的重复探测。所附日志没有足够的源身份及服务端拒绝详情，无法证明 403 由跨源凭据剥离引起。 |
| 探测预算 | `1246466` 的平台记录 8001 ms、增强记录 18,000 ms；`AndroidCore2RouteEvaluator.resolveProbe` 当前仍先平台后按条件增强。 | `startup_stage_timing` 的 `outcome=success` 与 `metadata_probe_budget` 的 deadline/cancelled 标签同现时，不能只看前者断言探测成功；应以实际返回的探测结果及后续路由为准。 |
| 预热 | 所附 ZIP 中没有 `current_item_prepared` 或 `current_item_preparation_reused` 事件；代码只有成功准备/领取日志，失败条件未完整记录。 | 无事件不能证明预热从未启动，更不能归因于计费网络、位置、租约或内存中的某一个条件。`prepared_store_claimed` 指详情页预备的 `PlayerStore`，**不是** YCore extractor 预热已领取。 |
| 主线程卡顿 | 日志有 80–99 ms 的最长帧，代码从 Main 启动详情请求。 | 没有 trace 或堆栈，不能把具体长帧归因于 Ktor JSON 解码。 |

### 7.2 当前代码状态与原建议的关系

- **并发策略仍待真机验证**：[`HttpClientFactory.android.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core/network/HttpClientFactory.android.kt) 仍是每主机 4 / 全局 8；新增 OkHttp `EventListener` 记录匿名请求类别、首个网络事件前耗时、响应头耗时与队列快照。首个网络事件前耗时包含调度等开销，**不等同纯队列等待**。[`PlaybackMetadataCache.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackMetadataCache.kt) 对失败的所有者仍移除条目；详情解析 UI 等待仍为 5 s，但本次已加最多 15 s 的独立底层任务和同页点击复用。调整并发前还需补解码结束时间及跨层请求关联。
- **条件性回归已修复**：`cachedResumeEpisode` 只读 `detailSnapshots`，命中**仅有轻量快照**时现在先补 `playbackItemDetail`，再发布播放目标；保留快照中的续播位置。对应的最小回归测试已补。快照已有 `versions` 时沿用现有快速路径。
- **已有部分基础，尚无真机效果数据**：当前代码把详情首屏 `includePlaybackFields=false` 与播放字段请求拆开，`EmbyDetailService` 有 30 s 剧集目录缓存，`DetailExecutor` 会把解析时已取得的目录交给后续集列表。第 2 节的“完整目录重取”与“详情首屏必带文件级字段”须按具体调用路径区分；不能概括为所有详情页每次都全量拉取。
- **要单独验证的播放建议**：当前 `AndroidMediaSourceFailure.sourceSuccessOrThrow` 已能把实际返回的 401/403 分类为 Authorization；`skipEnhancedProbeAfterDeadline` 对无 DRM 且没有杜比提示的源，已经在平台 deadline 后跳过增强探测。旧日志中的“8 + 18 s”不能不加条件地预测当前版本。对仍进入增强探测的源，先追踪失败在异步读取/探测预算里如何传播，再决定如何缩短长尾。`AndroidCurrentItemPreparation.claim` 的精确位置校验仍在，变更前须验证 seek 后复用仍安全。

### 7.3 下一轮实测与落地门槛

1. 对每个 API 请求记录**用途、匿名服务器引用、入队/开始/响应头/解码结束时刻、状态或失败类别**，按详情加载代次关联；不记录 URL、令牌、Cookie、签名查询或媒体标题。先比较 `playback_item_detail`、剧集目录、`PlaybackInfo` 与后台日历/同步的等待占比，再决定 4/8 是否主要瓶颈。
2. 给播放路径记录探测输入的匿名源身份、路由记忆命中/未命中原因、预热拒绝原因、Range 的偏移/状态/同源性、解码器配置开始/结束和第一帧。把立即 403 失败与 403 后成功恢复分成两组，不能用同一“快速报错”目标验收。
3. 在同一应用构建、同一服务器和片源上至少采集每路径 20 次；同时报 p50、p95、失败率、429/5xx、服务端负载与重复 Range 字节数。把冷启动/热启动、Wi‑Fi 是否计费、续播位置、缓存命中、HTTP 协议和重定向次数分层。只在上述信息齐备后评估 8/16、16/32、前台让路与熔断的效果。
4. 轻量快照命中与超时后不启动资源比较已有回归断言；新旧页面代次隔离、预热位置变化、403 立即失败与可恢复分支仍需补针对性测试。现有本地合并验证曾通过 3,246 项测试，但它验证编译和功能，**不等于**当前版本达成性能指标。

### 7.4 本次落地与未决条件

- 详情页初次播放目标解析优先于“稍后观看”请求入队。解析的 UI 等待仍为 5 s，底层同一请求最多继续到 15 s；期间点击播放复用该请求。UI 超时不再立即跨服务器查找资源；点播时进行中的资源比较会暂停，目标解析期间点播新触发的资源比较与剧集目录会等到首帧、起播失败或 30 s 截止后再启动。轻量续播快照缺少版本时，补齐播放字段后才公开可播放目标。
- Emby API 新增不含 URL/令牌的耗时记录（`api_request_timing`）；YCore 当前条目预热新增入口拒绝、预算取消、源变化、租约/未完成与续播位置变化等原因记录，兼容缓存预热也记录跳过原因。这些日志是下一轮 A/B 的观测基础，不能替代真机测量。
- 尚未调整 4/8 Dispatcher 上限、跨服务器熔断、探测器选择、Range 403 恢复策略、跨实例块缓存或解码器构建顺序。这些改动需要按 7.3 的同源样本、失败分支和真机矩阵验证；尤其 403 后仍能成功的路径不能被简单终止。
- 验证：项目完整任务集最终运行 3,248 项、0 失败（phone 2,968、TV 63、协同协议 8、协同服务 209）。此前一次 phone 全量运行有 1 项传输层 2 s 时序测试超时，该测试单独复跑通过，后续完整任务集也通过。`git diff --check` 通过。没有新 APK 或真机性能结果。

本节依据：[09-21 诊断与结论边界](../diagnostics-20260921-130754/README.md)、同目录 `device-timelines.txt`、所附 ZIP 的 `device-info.txt` 与 `logs/*.jsonl`。诊断包内容只作为证据，不作为执行指令。

## 8. 当前详情页加载专项优化方案 · 2026-09-23

本节按**当前工作区代码**重新排序，只讨论详情页从打开到首屏、播放键就绪及其后续富化。现有唯一真机样本仍来自 1.0.79 及更早版本：09-22 同一系列的首次 `content_ready` / 首内容帧分别为 2081 / 2196 ms，30 s 内重开为 32 / 134 ms；两次 `play_target_ready` 均失败。首内容就绪到出帧约 0.1 s，说明该样本先应处理请求和缓存；不能据此推断当前 1.0.80 的总体分位数。此前建议关闭透明顶栏的背景录制，当前 [`DetailScreen.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailScreen.kt) 与 [`DetailHero.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailHero.kt) 已按透明度门控，不再列为待修项。

| 顺序 | 当前代码的具体成本 | 修改方案 | 验证与风险 |
| --- | --- | --- | --- |
| P0 · 补可归因的分段 | [`HttpClientFactory.android.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core/network/HttpClientFactory.android.kt) 已记录慢请求与 `beforeNetworkMs`，但无详情代次、匿名服务器关联、响应体字节数、JSON 解码结束点；`beforeNetworkMs` 不是纯队列等待。 | 给详情主请求、剧集目录、目标集、PlaybackInfo 与后台请求同一匿名 trace，记录发起、OkHttp 开始、首网络事件、响应头、body/解码结束及状态；UI 继续记录首内容帧。只记用途和匿名标识，不记 URL、令牌或标题。 | 先在同一服务器、同一条目分层采集冷/热打开，确定网络、排队、解码与 UI 各占多少；避免依据旧日志盲调 Dispatcher。 |
| P1 · 缩小剧集目标关键路径 | [`EmbyDetailService.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyDetailService.kt) 的 `resolvePlayTargetWithEpisodes` 先读取整剧目录，字段含 Overview、ProviderIds 等，再查询目标集播放字段；这两段落在详情页 5 s UI 预算里。 | 目标选择先用可信的本地续播候选，校验失败再读仅含身份、集数和 UserData 的轻量目录；确定目标后只取该集播放字段。季/集列表和简介在按钮可用后补，保持“未播放优先”等现有 NextUp 语义。 | 测大量剧集、无进度、跨设备进度、已看完、缺集与服务端分页；目标集和续播位置不能选错。 |
| P1 · 压低首屏后的请求扇出 | [`DetailExecutor.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailExecutor.kt) 在内容到达后几乎同时启动播放目标、“稍后观看”、相关影片和演员请求；目标就绪后又启动剧集目录和每台服务器的资源比较。播放点击期间的让路已经有了，普通浏览时仍会并发。 | 目标请求给前台优先级；“稍后观看”与相关推荐在首内容帧后按可见性/短空闲窗口启动，演员仅缺失时请求。当前服务器的资源卡直接用已知 `playTarget.versions`，其它服务器在资源区进入视口或用户展开时查询，保留手动重试和上限时间。 | 对比首屏与播放键 p50/p95、同时在飞请求数、资源区出现时间；不要让滑到资源区的用户长时间只看到占位。 |
| P1 · 热打开少重取 | [`EmbyRepository.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt) 虽有 30 s 快照先绘制，但 `itemDetail` 每次仍用 `reuse=false` 重新请求；播放字段缓存仅 15 s。 | 将首屏快照的“可立即绘制期限”和“需后台复核期限”分开：近期重开先复用快照并避免重复请求，稍旧快照先绘制再后台刷新；手动刷新、元数据修改与服务器/账号变更强制失效，进度每次重新投影。播放字段可从 `playbackDetails.peek` 补回轻量快照。 | 验证跨账号不串数据、手动刷新生效、已播放状态即时变化；不能用长 TTL 掩盖真实服务器更新。 |
| P2 · 减少可选富化和预备 Store 的重复流量 | [`EmbyBrowseService.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyBrowseService.kt) 每次查“稍后观看”先找播放列表，再可能分页扫描；[`DetailComponent.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt) 在目标就绪时立刻建预备 `PlayerStore`；未点播时 [`PlayerStore.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt) 的 `awaitForeground` 立即返回，仍可拉全剧含 MediaSources 目录。预备键还含精确续播位置。 | 按服务器/用户缓存播放列表 ID 与短期 membership；预备 Store 先只完成选中条目的必要协商，全剧队列在领取后或空闲预算内补，优先复用详情已取目录。先记录“仅位置变化导致重建”的次数，再决定拆分元数据身份与位置相关协商；不能直接忽略位置而复用旧 PlaybackInfo/seek 状态。 | 比较详情请求数/字节数、点播放到首帧、下一集切换；预热命中率不能以播放正确性为代价。 |
| P2 · 控制后台竞争与渲染成本 | API Dispatcher 仍为每主机 4 / 全局 8；旧日志有日历、首页、媒体库及失效服务器请求同窗运行，但无法定量归因。页面仍有多处 `AnimatedColorContent` 在组合期读动画色，`sourcePresentation` 未缓存。 | 先按服务器给首页富化、同步、日历和健康探测加可取消的后台让路/退避；用同源 A/B 再评估 8/16 或 16/32。用 Perfetto/Compose trace 证实重组热点后，把纯绘制色值读取下推并缓存资源展示计算。 | 同时报 429/5xx、服务端负载、详情和后台完成时间、首内容帧；未证实主线程 JSON 或 Compose 是主因前，不做全局线程/动画重写。 |

实施顺序：先补 P0 分段与对照脚本；再依次做 P1 的目标轻量化、详情本页请求调度、快照复用；随后做 P2 的预备 Store 与跨模块后台门控，最后评估 UI 热点和 Dispatcher 上限。每一步单独比较至少 20 次同构建冷/热打开，分别报告 `content_ready`、`detail_first_content_frame`、`play_target_ready`、点播到首帧的 p50/p95、失败率、请求数和响应字节数。通过标准是详情指标改善且点播首帧、资源准确性与错误率不退化；旧版单次 2.2 s / 0.134 s 仅作案例，不作当前版本承诺。

## 9. 详情页首轮代码落地 · 2026-09-23

- [`EmbyDetailService.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyDetailService.kt) 为系列播放键单设只要求 `UserData` 的目录请求。仍按原有本地进度投影及“未看优先”规则选集；已有 30 s 完整目录时直接复用。选出目标后只补目标集播放字段，季/集列表在按钮就绪后加载。**冷系列页可能多一次目录请求**，收益应看目标就绪时间及下载字节数，不能仅凭请求数判断。
- [`DetailExecutor.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailExecutor.kt) 在目标请求发出后，最多给它 750 ms 独占窗口，再启动“稍后观看”、相关内容及缺失的演员请求；新页面代次取消旧任务。资源比较直接使用已解析目标的当前服务器版本，只查其它服务器；没有可用版本时仍走原服务器查询。手动重试保留用户对同一目标已选的版本。
- [`EmbyRepository.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt) 的 30 s 详情快照命中时，页面不再立即重复 GET；手动重试仍强制刷新。稍后观看成员状态缓存 30 s，增删成功后失效；缓存键包含服务器/账号凭据对象。进度仍在每次读取快照时重新投影。
- [`HttpClientFactory.android.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core/network/HttpClientFactory.android.kt) 的匿名 API 日志补响应体字节数与响应体耗时；详情/目标目录另外记录从发起到 `get` 返回及 `body()` 返回的时长和响应声明长度。`bodyAndDecodeMs` 包含响应体读取，**不是纯 JSON 解码时间**；`beforeNetworkMs` 也不等于纯队列等待。尚未做跨 OkHttp/Ktor 层请求 ID 关联，4/8 Dispatcher 上限未变。
- 定向回归覆盖 30 s 热缓存与强制重试、已选版本保留、轻量目录的进度重投影、当前服务器免查、稍后观看缓存失效；详情与仓库测试 110 项通过。完整项目任务集 3,250 项、0 失败（手机 2,970、电视 63、协同协议 8、协同服务 209）。真机性能复测及同源 A/B 数据仍缺；本次未打包 APK。

未落地的方案包括资源区视口触发其它服务器查询、预备播放器惰性队列、跨模块后台请求门、Dispatcher A/B 与 UI trace 定位。这些改动的收益与交互影响须以新构建的同源真机样本评估，旧版日志不能替代。

## 10. 起播速度专项代码复核与实施方案 · 2026-09-23

范围为按下播放到首个**视频**帧。现有诊断中的 09-22 `111663` 样本共 12,393 ms：详情选集 1,351 ms、`playback_info_ready` 3,592 ms、`current_item_ready` 3,676 ms、`route_selected` 约 7,783 ms、首音频 10,402 ms、首视频 12,393 ms。09-21 `1246466` 样本共 53,981 ms，其中平台/增强探测分别触及 8,001/18,000 ms 预算，且多次 Range 返回 403。这些均为 **1.0.79 及更早构建的不同片源**；第 7、9 节的改动尚无新 APK 真机数据，因此下述收益均是待检验假设。

当前已有的快速路径不能重复算作新方案：详情页在目标就绪后预备 `PlayerStore`，`PreparedPlaybackRegistry.claim` 会转移同一个 Store；选中条目先于全剧队列公布；YCore 有当前条目预热、单次会话平台探测缓存、成功出画后写入的精确片源路由记忆、进程内重定向/Cronet 主机状态。`AndroidMediaSourceFailure` 已能传播实际 401/403，平台探测超时后对无 DRM、无杜比提示的源会跳过增强探测。下一步须提高这些路径的**命中率**并缩短未命中路径，而不是再加一层无效预取。

| 优先级 | 代码证据与剩余耗时 | 具体方案 | 验收与安全边界 |
| --- | --- | --- | --- |
| P0 · 可归因的首帧流水线 | [`PlaybackLaunchTiming.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackLaunchTiming.kt) 已有单次点击 ID；[`PlayerStore.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt) 记录条目/PlaybackInfo/当前项；YCore 记录探测与路由，但引擎释放等待、构建、首次 Range、解码器配置、首样本尚无完整同一 ID 分段。API 的 `beforeNetworkMs` 不是纯排队时长。 | 把匿名 launch ID 贯穿队列、播放器引擎及 YCore；补 `old_engine_release_wait`、`engine_construct`、`first_range_headers/body`、`seek`、`codec_configure/start`、`first_sample`、`first_video_output`，记录预备 Store/当前条目预热/路由记忆的命中与未命中原因。API 记录用途和响应字节，避免 URL、令牌、标题。 | 用同一构建、服务器、片源分冷/热、续播/从头、SDR/DV、Wi‑Fi/计费网络各至少 20 次，报 p50/p95、错误率、重复 Range 字节；先定位占比，再改预算和并发。 |
| P0 · 首视频前让出后台资源 | [`PlayerStore.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt) 的 `awaitForeground()` 在预备 Store 尚未被领取时立即返回，之后全剧含 `MediaSources` 队列与备用服务器可在点播前或起播中运行。[`PlayerRoot.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt) 把首音频也作为 `output=true`，旧 DV 样本在首视频前约 2 秒即释放后台请求。 | 预备 Store 只协商当前项；在 `claim` 时建立前台门，视频内容等首视频帧/失败/有界超时再恢复全剧队列、备源和详情富化，纯音频等首音频。已开始的可选请求需可取消并在起播后安全续跑。 | 首视频 p50/p95 降低，同时核对下一集切换、资源列表与失败恢复不退化；不无限期饿死后台任务。 |
| P1 · 把播放协商尽量完成在点击前 | [`DetailComponent.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt) 已在目标就绪后建立预备 Store；[`PlayerStore.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerStore.kt) 仍串行取目标文件详情、再调用 PlaybackInfo。旧 DV 样本是在点击后约 3.6 秒才走完这段，但当时目标解析失败；当前代码已改进目标解析，尚无新样本证明仍发生。 | 记录点击时预备 Store 处于“未建/详情中/PlaybackInfo 中/当前项就绪”的比例。先提高同一 Store 的领取率；只有精确媒体源与能力参数均已知时，评估详情与 PlaybackInfo 安全重叠，服务器返回后继续核对物理源一致性。 | 对比点击前协商完成率、PlaybackInfo 响应时长和错误率；不复用跨启动的 PlaySessionId，不投机唤醒转码。 |
| P1 · 提升当前条目预热复用率 | [`DetailComponent.kt`](../../composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt) 的预备键含精确 `startPositionTicks`；[`AndroidCurrentItemPreparation.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCurrentItemPreparation.kt) 先延迟 500 ms，领取时要求源提示和毫秒位置精确一致，尚未完成的任务会被取消。预热只在符合网络/电量/内存条件时运行。 | 先统计 `source_ineligible`、`not_ready_or_expired`、`position_changed`、`source_changed` 比例；对正在执行且身份相同的预热设计有界前台接管，避免取消后重开 extractor。位置只在校验 seek/PlaybackInfo 语义后重定位，保持单次播放会话 URL 不跨启动复用。 | 预热领取率与首帧同时改善；从头播放、续播变更、切版本、账户切换均播放正确，计费网络不增加预取。 |
| P1 · 缩短冷源探测长尾 | [`AndroidCore2MediaProbe.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCore2MediaProbe.kt) 先平台探测，再按条件增强探测；DV/DRM 不能套用普通源的超时跳过。精确源的已验证路由记忆可绕过探测，但冷源仍可能串行触及 8 + 18 秒。 | 按可信的容器/媒体源提示和历史路由选择探测顺序；对冷 DV/复杂音频做受预算约束的并行或递进探测，共享已读头/尾片段与结果。路由只能在本机能力、DV 配置、DRM 与失败记忆校验后确定；保留不确定时的兼容路径。 | 分 SDR、DV、EAC3、加密/错误标注片源比较探测耗时、路由正确率、回退次数；不得为省时把 DV 错判成普通 HEVC。 |
| P1 · 减少探测到播放的重复 Range | [`AndroidTransportMediaDataSource.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt) 的内存块表属于实例；磁盘写入可异步，新的探测器/播放器仍可能重读头块。共享重定向状态只省协商，**不共享块内容**。旧样本记录同一头块多次下载。 | 在 `cacheIdentity` + 经验证的长度/强 ETag/凭据作用域下做小容量头尾块与在途请求合并，或把探测器已读块随 extractor 一次性交接；失效时丢弃整组事实。 | 起播前同一块实际网络下载趋近 1 次，同时测堆内存、缓存失效、跨账号隔离和取消后的资源释放。 |
| P1 · 路由后并行准备解码器 | 旧 DV 样本 `route_selected` 到 `video_decoder_candidates` 约 1.85 秒，到首视频约 4.61 秒；[`AndroidMediaCodecVideoNode.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidMediaCodecVideoNode.kt) 的候选枚举和 `configure/start` 在后段进行。 | 路由确认且已取得精确 `MediaFormat` 后，让 MediaCodec 配置与 seek/首块读取重叠，保持 Surface 所有权、DRM 和失败释放的单一生命周期；记录配置、首输入、首输出各段以确定实际收益。 | 以 DV 与普通 HEVC、续播/从头、切屏/取消、硬解失败回退矩阵验收，不能仅提前创建无用解码器占住资源。 |
| P2 · 定位 Range 403 与引擎冷启动 | [`AndroidMediaSourceFailure.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidMediaSourceFailure.kt) 已区分 HTTP 403；旧日志既有首 Range 403 后迅速失败，也有中段 403 后最终出画。[`PlaybackEngineSlot.kt`](../../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackEngineSlot.kt) 会等旧引擎释放再构建，GPU/Cronet 初始化耗时尚未分段。 | 先记脱敏后的原始/最终源同源性、重定向目标复用/失效、Range 偏移与失败层；仅证实缓存目标失效时从原始 URL 重新协商，跨源不附带凭据。再按引擎构建段的实测结果决定是否预热 Cronet/GPU 的进程级无状态部分。 | 把“首 Range 不可恢复”与“局部 403 后可恢复”分开验收；避免误杀可播放源，避免预热持有解码器、Surface 或过期会话。 |

建议按 P0 → 播放协商/预热 → 冷源探测/重复 Range → 解码器/403 的顺序逐批落地并单独 A/B。每批同时看点播到首视频帧、播放失败率、服务器 429/5xx、重复下载字节和下一集切换。当前没有新构建真机样本，不承诺固定节省秒数，也不把 09-21/22 不同条目合并成总体 p50。本节原为代码审查和实施计划；首轮 P0 实施状态见第 11 节。

## 11. 起播 P0 首轮落地 · 2026-09-23

- 详情页预备 `PlayerStore` 仍会提前完成**选中条目**详情和 PlaybackInfo 协商，并允许对该条目做现有的源预热。新增 `PreparedPlaybackGate`：全剧含 MediaSources 队列和备源请求在 Store 被播放器领取前不会启动；领取后等首视频帧、启动失败或 30 s 截止再启动。明确的 `Audio` 类型在首音频输出后放行。页面离开时 Store 仍按原生命周期取消。普通从播放器直接建立的 Store 沿用已有前台等待。
- 视频首音频只记录 `first_audio_output`，不再把它当成视频首帧而提前放行背景请求。`PlaybackEngineSlot` 新增 `engine_slot_requested`、`engine_retirement_done`、`engine_construct_started`、`engine_constructed`、`engine_binding_published` 等匿名点击计时点，可与已有选中条目就绪、探测、路由及首视频点相减定位等待段。引擎内部首次 Range、解码器配置与首样本仍未接入同一 ID，不能宣称 P0 全链路观测完成。
- 定向验证使用当前实际测试入口 `:phoneShared:testAndroidHostTest`：预备队列、播放计时、引擎槽、Store 和剧集启动共 52 项，0 失败；新增用例验证未领取及首帧前不会请求备源。`git diff --check` 通过。随后构建了 1.0.81（243）Full/Compact 本地正式签名 APK，包名、版本、证书与原生配置验证见 [`verification.json`](../releases/20260923-performance-1.0.81/verification.json)；尚无真机复测或可量化的首帧收益，按第 10 节同源 A/B 门槛验证后再进入探测、Range 和解码器改动。

---
可分享页面：https://claude.ai/artifact/46xzQSUEin7imeBAfE7ghX
