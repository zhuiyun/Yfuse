# Yfuse 代码检查与 UI 审查 · 优化项清单

审查时间：2026-09-16 至 09-17。基线：master `58ce99a9`，工作区无已跟踪文件改动，源码版本 1.0.66（228）。

**结论：没有 P0。核心层、播放器、构建链路都比 9 月初扎实，9 月 15 日那轮审查列出的缺陷在本次范围内均已修复。剩余问题集中在四类：Plex 适配器的线程安全与全库拉取、播放器引擎切换与每 500 ms 一次的系统调用风暴、设计系统"宽度失控"（43 款弹窗动效全部可选、默认主题为浅色、导航折叠成死代码），以及被常态化绕过的 MDK 分发权门禁。** UI 层最直接可见的是：首页英雄卡对从未播过的 TMDB 精选也显示"继续播放"、"推荐"徽标与浅色弹窗次级文字对比度不达标、TV 共享弹窗按手机默认浅色主题渲染。

## 1. 依据与边界

- 五条并行审查线：核心层（core/data、network、account、sync、security、offline、di）、播放器与 Android 平台层、功能页 Compose UI、设计系统 / App 壳 / TV、工程化（Gradle、CI、测试、仓库卫生）。每条线只读，未改动任何文件。
- 真机：把今早打好的 1.0.66（228）正式包装到测试机 SM-N960U（原 1.0.10），截到一张首页。随后按用户要求停止操作手机，其余页面未截图；误截的 5 张非本应用画面已删除。首页截图见 `audit/uploads/ui-review-20260916/01-launch.png`。
- 未运行构建、单元测试或 Lint；所有"已核实"指源码逐行核对，对比度数值为按 token 估算，未在设备上实测。
- 已排除 9 月 15 日报告已修复项。可能与产品对标报告（`audit/product-gap-20260915/REPORT.md`）的 O1–O8 有交集处均已注明。

## 2. 先修项（P1：用户可感知、性能或风险）

### 2.1 代码

| # | 位置 | 问题 | 后果 | 建议 |
| --- | --- | --- | --- | --- |
| C1 | `core/data/PlexMediaServerAdapter.kt:83-87`，写 :473/:539，读 :581 | `durationByItemMs` 是 access-order `LinkedHashMap`，UI 线程写、`Dispatchers.Default` 上报线程每 10 s 读，无锁；access-order 下 `get()` 也改链表 | 播放中浏览详情即可触发 `ConcurrentModificationException` 或链表损坏 | `synchronized`/`Mutex` 包裹，或改不可变快照 |
| C2 | `PlexMediaServerAdapter.kt:1036-1062`，调用方 :784/:833/:838-880/:883 | `allTopLevelMetadata` 每次按 provider id 查找都分页拉取全部分区全部条目（含 guid/userState），无缓存；详情页 `compareSources` 与云同步 fan-out 每台 Plex 每次调用一遍 | 万级库每次数 MB，详情页与同步明显变慢、耗流量 | 按服务器加 TTL 索引缓存（同 `AiringCalendarRepository.identityCatalogCache`），或改用 `/library/all?guid=` 过滤 |
| C3 | `core/data/EmbyLookupService.kt:179`、`PlexMediaServerAdapter.kt:804→1028`、`watchTogetherProtocol/.../WatchProtocol.kt:208-215` | 一起看 mediaKey 只校验前缀，`/`、`?`、`#`、`..` 均可通过，随后裸拼进请求路径 | 房间/邀请方可让受害者客户端带自己的 token 向本服务器任意路径发 GET（只读，响应不外泄） | `encodeURLPathPart()` + 字符白名单 |
| C4 | `feature/player/PlayerRoot.kt` 11 处 `engineGeneration++`，仅 631/708/905/2111 前调用 `prepareForHandover()`；1623/1659/1810/1890/1963/2177/2355 未调用 | 新引擎在 `remember`（388-433）里同步构造并 `prepare()`，旧引擎要等 `DisposableEffect.onDispose`（570）才 release | 两套解码器 + AudioTrack 并存，低端机新 codec 分配失败并被 `failureMemory` 误记为引擎故障 | 收敛为一个 `requestEngineRebuild(reason, targetKind, targetDecoder)` 入口，11 处全走它 |
| C5 | `feature/player/PlaybackReportingBinding.kt:43-49`、`PlayerActivity.kt:818-849`、`PlayerNotificationController.kt:35-100`、`PlayerActivity.kt:1539-1575` | `snapshotFlow` 收集未投影的实时状态，每个 500 ms 位置 tick 都回调 `onPlaybackState`，随之整张通知重建 + `setPlaybackState/setMetadata` + 3 个 `PendingIntent` + `setPictureInPictureParams` + `playbackItems.map{}` | 常态播放主线程每秒约 10 次 Binder IPC 与对象分配，耗电与掉帧 | 回调改用 `runtimeProjection()` 或 `distinctUntilChangedBy { playing/buffering/ended/error/currentIndex/durationMs }`；PiP 参数只在比例/播放态变化时更新 |
| C6 | `gradle.properties:22`、`composeApp/build.gradle.kts:995-1007` | `confirmMdkDistributionRights=true` 已提交入库（注释自称"一次性授权"），`verifyProductionMdkRights` 只在该属性为空时拦截 | 每次正式签名自动通过，发布记录里"MDK 权利声明校验通过"实为空转，许可合规门禁失效 | 从 `gradle.properties` 删除，改由发布脚本/CI 显式 `-P` 传入 |

### 2.2 UI

| # | 位置 | 问题 | 后果 | 建议 |
| --- | --- | --- | --- | --- |
| U1 | `core/designsystem/Hero.kt:122`；参数默认 :88 `playActionLabel = "播放影片"` | 可见文字写死"继续播放"，无障碍标签却是"播放影片"；首页 TMDB 今日精选从未播过也显示"继续播放"（真机截图已见） | 语义错误、读屏与视觉不一致 | 文字改用 `playActionLabel`，由调用方按是否有进度传"继续播放/播放" |
| U2 | `feature/detail/DetailFileSections.kt:803-810`、`detail/SourceListDialog.kt:190-197` | "推荐"徽标 `Color(0xFF9A6B12)` 压在 30% `#F5C86A` 上，深色主题估算对比度约 1.8:1；两处逐字复制 | 关键信息不可读 | 用 `Semantic.Warning` 并抽成一个 `RecommendBadge` |
| U3 | `core/designsystem/ModalGlass.kt:43-58`、`Dialogs.kt:511-515/603-608/211-217` | 浅色主题弹窗面板 `#878F9B`@0.52 叠模糊页面，正文/描述估算约 3.3:1 / 2.8:1；`Tokens.kt:204-217` 的对比度校准只针对 `#F3F5F8` 页面底；浅色遮罩仅 0.16 | 48 处 `GlassDialog` 在浅色下次级文字偏灰、模态层级弱 | 为灰玻璃单独校准 body/sub2，遮罩提高到 0.3 左右 |
| U4 | `core/data/ThemePreferences.kt:48`、`tvApp/.../TvApp.kt:83-96` 与 :147、`TvDetailMoreActions.kt:67/214/299/372`、`TvAppearanceScreens.kt:55`、`TvSettingsScreen.kt:311` | 默认主题是 `ThemeMode.Light`，与"深色为主"方向相悖；TV 壳固定深色底却按该偏好算 `dark`，四个共享弹窗与统一库页拿到浅色 token（API≥31 灰面板配深墨，估算约 2.3:1；API<31 纯白面板） | TV 端弹窗与壳割裂；手机首次安装即浅色 | TV 强制 `dark = true` 并从 TV 设置移除主题模式；手机默认改 `System` 或 `Dark` |
| U5 | `feature/servers/ServersTabScreen.kt:639-700` | 顶栏"筛选/切换布局/刷新全部"三个纯图标按钮 `contentDescription = null`，只有 `onClickLabel`，无 `stateDescription` | TalkBack 念"未加标签的按钮" | 给 `pressable` 加 `label` 参数写入 semantics，布局切换加 `stateDescription` |
| U6 | `feature/profile/AddServerDialog.kt:570-660` vs `feature/servers/ServersScreen.kt:1075-1185` | 两份私有 `FormInput/ProtocolSegment` 已漂移：ServersScreen 版有密码显示切换、IME Next/Done 与焦点下移，AddServerDialog 版全无；设计系统 `YfFormField` 已 18 处使用却未被采用 | 同一"添加服务器"两个入口体验不同（对应产品报告 O8） | 两处统一改 `YfFormField`，删除私有实现 |
| U7 | `feature/home/HomeScreen.kt:360` | `visible = heroVisible && !listState.isScrollInProgress` 在 body 组合期直接读取（`LibraryHomeScreen.kt:302` 已正确包进 `derivedStateOf`） | 每次滚动开始/结束整段 body 重组 | 包进 `derivedStateOf` |
| U8 | `feature/personal/PersonalCenterScreen.kt:344` | `items(serverState.conflicts.filter { … })` 无 key/contentType，`filter` 在 content lambda 内每次重组执行 | 解决一条冲突后位置复用，上一项的 remember 状态/动画串到下一项 | 加 key，`filter` 提到 `remember` |
| U9 | `core/designsystem/Backdrop.kt:243-244/257-272`、`feature/detail/DetailHero.kt:325-329` | 每个 blur 面先 `renderEffect` 模糊再 `saveLayer` 做饱和度，两遍离屏；顶栏 alpha=0 时详情页仍每帧录制并模糊 | 详情页常驻 GPU 开销 | 饱和度并入 `RenderEffect.createColorFilterEffect(chain(blur))`；加 `alpha: () -> Float` 早退 |
| U10 | `core/designsystem/Interaction.kt:141`、`LightParticles.kt:457-471` | 所有 `pressable` 都挂一套粒子状态，`remember` key 含 `LocalRouteVisible` 与 `isWindowFocused`；路由切换、弹窗开关那一帧页面上每个卡片/按钮同步 dispose + 重建；粒子设为 Off 仍分配 | 转场/开弹窗掉帧 | 惰性创建（首次 emit 才建），不把焦点/可见性放进 `remember` key |
| U11 | `feature/profile/ProfileScreen.kt:1244-1292`；全库 81 处 `"… ›"` | `SettingRow` 每行一个 `BoxWithConstraints`（根页 30+ 行全部子组合），value `maxLines=2` 无 overflow；`›` 直接拼进文案（ProfileScreen 33 处） | 我的页首帧慢、超长值硬切、读屏念出符号 | 去掉 `BoxWithConstraints`，chevron 由行控件绘制 |

## 3. 本季处理（P2）

### 3.1 并发、性能与正确性

- `core/data/AiringCalendarRepository.kt:79/101/115-118/451` 与 `CalendarReminderWorker.kt:143/161`：`identityCatalogCache` 无锁并发写，WorkManager 线程与主线程可同时写同一 `mutableMapOf`；同文件 `resourceDetailsCache` 已有 mutex，应对齐。
- `core/sync/playback/PlaybackSyncManager.kt:79-89/511-541`：`debounceJob/urgentJob/retryJob/cloudPlaybackEndpointUnavailable` 与 `startupPullPendingUserIds` 跨线程非 volatile；`syncMutex` 兜底不致错乱，但会重复 urgent job。
- `core/sync/playback/PlaybackSyncStore.kt:702-726`：每 10 s 进度写入都全量重排序并序列化 ≤512 份文档后落盘，长期用户每 tick 数百 KB；改脏标记 + 延迟写。
- `core/data/EmbyDetailService.kt:560-583`：「下一集」货架对每部已看完剧集顺序 `fetchSeriesEpisodes`，最多 36 次串行往返；改并发 + Semaphore 或加 `Limit/StartItemId`。
- `core/data/EmbyRepository.kt:334/340`、`PlexMediaServerAdapter.kt:686-714`：`runCatching` 吞掉取消，把取消当业务失败写入状态。
- `EmbyRepository.kt:395`、`EmbyBrowseService.kt:114/193/663`：路径段裸拼 id，同文件其他处却做了 `encodeURLPathPart()`；统一为一个 `embyPath(id)`。
- `core/sync/WatchTogetherClient.kt:53/300`：`reactionSequence` 非 volatile 自增，UI 线程与连接协程并发 `pushReaction` 可产生重复 id。
- `core/data/ServerRegistry.kt:741-807/1133-1157`：`commit` 在主线程做 Keystore AES 加密，`replaceFromSync` 对 N 台服务器 × 3 个密钥同步执行，登录/云端恢复可见掉帧（若改后台需补锁）。
- `feature/player/PlayerRoot.kt:388-433`：在组合期 `remember{ createVideoEngine() }` 启动线程/解码器，组合被放弃时无人 release；改 `RememberObserver` 持有者。
- `feature/player/MpvVideoEngine.kt:1304-1335`：mpv `stop + destroy` 在主线程 onDispose 同步执行，切引擎时卡数百毫秒。
- `core2/android/AndroidYCoreBlockCache.kt:395-401`、`AndroidNextItemPreparation.kt:202`：`Thread.sleep(1L)` 忙等最多 1 s；改 `Condition`/`Channel`。
- `PlayerRoot.kt:792-807`：`planPlayback` 未 `remember`，每次结构性重组重跑。
- `core2/android/AndroidAdaptiveCore2YPlayer.kt:367-383` 与 `PlayerRoot.kt:619-631`：Core2→Exo/mpv 回退不等待 MediaCodec 真正释放（与 C4 叠加）。
- `update/AppUpdateManager.kt:50/1702-1720`：升级清单走 IP 直连 HTTPS，`UPDATE_MANIFEST_PUBLIC_KEY` 为空时放行未签名清单（有 APK 签名者比对与防降级兜底）；CI 应强制密钥非空。
- `androidMain/res/xml/network_security_config.xml`：全局允许明文（自建 Emby 需要），升级/上报等固定域名可用 `domain-config` 单独收紧。
- `composeApp/build.gradle.kts:829`：`TMDB_TOKEN` 编入 `BuildConfig`，可被反编译提取。

### 3.2 死代码与收敛

- 导航栏折叠整条路径断线：`app/App.kt:364-366` 只剩 `.then(Modifier)`，`rememberNavCollapseConnection`（613-674）无调用者，`BottomNavigationDock` 的 `BoxWithConstraints + animateDpAsState + AnimatedContent`（714-787）、`CollapsedNavButton`（791-815）、`NavigationCollapseGuard`（576-598）及其测试全部不可达却仍付组合成本。二选一：删除或接回 `nestedScroll`。
- "弹窗实验室"开关只剩空壳：`DialogAnimation.kt:77` `LocalDialogAnimationLab` 零读取，`Theme.kt:314/335`、`App.kt:202/223`、`ThemePreferences.kt:40/129-133` 整条死链。
- 43 款弹窗动效全部生产可达：`feature/profile/DialogAnimationSheet.kt:47` 直接列 `DialogAnimation.entries`；`DialogAnimation.kt:218-224` 有 20 款几何帧恒为 Resting，仅靠遮罩区分，12 文件 1,884 行。建议只留 Lift/Slide/Touch/MagneticDrag/Cascade 五款，其余迁 debug source set 或删除并按名回退到 Lift。开屏 7 款同理只留 1+1。
- 强调色管道与文档矛盾：`Theme.kt:118-119` 写明无用户可选主题色，`ThemePreferences.kt:51-56` accent 固定无 setter，但 `YfuseTheme(accent=)`（`App.kt:186/210`、`TvApp.kt:77/87`）与 `LocalAccent/FixedBrandEmphasis/AccentColor.Blue`（`Theme.kt:180-196`，`DownloadsScreen.kt:151/708/738`、`PlayerActivity.kt:483/733`）仍在用。去掉参数与三个过渡 Local，只保留 `ArtworkAccent`。
- 玻璃入口膨胀：`Glass.kt:298-329` `glass/flatGlass/solidGlass` 只差 weight；`overlayGlass` 仅 1 处调用，`GlassCard` 零调用；Brush 版 `glass`（426-443）不走 reduceTransparency 分支；`GlassShapes` 8 个别名号称"新代码用 AppShapes"，feature 里 GlassShapes 195 次 > AppShapes 106 次。
- 字体双入口：`Tokens.kt:703-721` `Type` 与 `SemanticTypography.kt:21-48` `AppTypography` 描述同一四级，前者 feature 零调用，收为 internal。
- `core/data/TmdbRepository.kt:522-577`：`searchSeriesIdentityCandidates`、`findSeriesByExternalId` 全仓无引用，且后者裸拼 `/find/$externalId`；删除。
- `ServerRegistry.kt:514-540` 与 :658-682 迁移包序列化逐字段重复；`AccountRepository.kt:289` 编码后再解码取同一对象，:1097-1130 两个 capture 函数重复。
- `di/AppModule.kt:293`、`PlexMediaServerAdapter.kt:62` commonMain 硬编码 `"Android"`；`AppModule.kt:90-303` 五个 HttpClient 只有 `trakt-http` 有 `onClose`。
- `OfficialNavDisplay.kt:94-106`：RootTab/Search 模式下每个入口仍建 `routeReturnCorners` 动画 + `graphicsLayer`，amount 恒 0。
- 根级 backdrop 三层常驻捕获：`Theme.kt:344→ModalGlass.kt:27`、`App.kt:366`、`DetailScreen.kt:500`，API≥31 无论有无消费者每帧都 `record`；`DialogBackdropHost` 应只在有弹窗时录制。

### 3.3 一致性与可访问性

- 设计系统缺组件、feature 各自造轮子：Chip 5 套（`SearchScreen.kt:511`、`LibraryGridScreen.kt:593`、`DownloadsScreen.kt:700`、`EpisodeProgressManager.kt:155`（无 `touchTarget`，44dp）、`CalendarScreen.kt:286-310`）；Section 标题 4 套（`DetailSections.kt:212`、`LibraryHomeScreen.kt:1308`、`HomeScreen.kt:1363/1581-1600`，"更多"与"全部 ›"两种叫法）；设置行 2 套（`ProfileScreen.kt:1244` 与 `AccountSettingsScreen.kt:1101`）；六色装饰板复制（`DetailMoreActionsDialog.kt:55-60` = `SeriesAiringCalendarSheet.kt:80-85`，`DetailFileSections.kt:891-896` 另一组，`LibraryHomeScreen.kt:1091-1096` 自带 4 色而 `Tokens.kt:83` 已有 10 色）。
- `Dimens` 名存实亡：只有 `pageHorizontal`（129 次）被采用，`cardGap/small/medium/large` 全应用 5 次；feature 内 1,992 个数值 dp（7/8/9/10/11/12/13/14dp 全都出现），designsystem 内 312 个；`Dialogs.kt:110-112/360/450/481-482/582` 全是字面量。缺 4/8/12/16/20/24 间距阶梯。
- 材质常量绕过 Palette：`Glass.kt:93-98/108/176-177/232/629`、`ModalGlass.kt:45-47`、`Dialogs.kt:71`、`PageStates.kt:141`、`NavigationGlass.kt:205-206`、`WatchAvatar.kt` 8 处；`Color(0x` 在 designsystem 171 处、feature 49 处（`DetailFileSections` 9、`AiringSheet` 7、`MoreActions` 6）。
- 行高散点：`.copy(lineHeight = …sp)` 21 处 9 个不同值（`WatchInviteSheet.kt` 5 处、`ServersScreen.kt` 4 处），`AppTypography` 缺"长文正文"变体。`AccountSettingsScreen.kt:1000` 全应用唯一 `RoundedCornerShape(26.dp)`；:126 一处 `Text` 无 `style`。
- Token 注释与值漂移：`Tokens.kt:400-407` 注释说 56dp 值为 62dp、:377-378 注释 134px 值为 122dp；`TabBarInset` 在 `Tokens.kt:417` 与 `App.kt:180` 各定义一次。
- 空态缺行动：`LibraryHomeScreen.kt:367-371`、`CalendarScreen.kt:327-337` 的 `PageHint` 不传 `actionLabel/onAction`（10 次调用仅 3 次带动作）；`LibraryHomeScreen.kt:1130` 分类卡加载失败无重试入口，点击仍进空网格。
- 语义缺失：`AiringShowCalendarDialog.kt:357-385` `active` 只靠颜色，无 `selected/stateDescription`；`CalendarScreen.kt:876-881` 行有 `onClickLabel` 内部 chevron 又给 `contentDescription`，读屏重复。
- 底部 dock 固定 62dp（`App.kt:952/1058/1083`），largeText ×1.12 叠系统缩放 ≥1.7 时说明文被裁。
- 以展示字符串驱动逻辑：`HomeScreen.kt:431/486/522` `row.title == "即将上映"`、`DetailScreen.kt:150` `item.type == "Episode"`、`PersonalCenterScreen.kt:347` `kind.name == "Favorite"`。
- `SearchScreen.kt:210-228`：`SearchFilterDialog` 组合在 LazyColumn 第 2 个 item 内；5 个 `motionItem{}` 无 key 与有 key 项混排。`CalendarScreen.kt:1091-1098/620`：平板 7 列各自 `LazyColumn`，`coalesceCalendarEntries` 与 `sumOf` 在 content lambda 内每次重组重算。
- 跨 feature 依赖：library→profile、search→library、personal→profile 7 处、trakt→profile 6 处；`SettingRow/SwitchRow/Section/SettingsCard/SettingsDivider` 这些设计系统级原语是 `ProfileScreen.kt` 的 `internal fun`。
- 文案：`AccountSettingsScreen.kt:1247` 用"服务端"（全库 190 处"服务器"）。
- TV 端：`TvUiComponents.kt:84-89` 自建 6 色，错误/警告色硬编码（`TvDiscoveryCalendarScreens.kt:178`、`TvServersSettingsScreens.kt:350/431-436`）不用 `DarkPalette.error/Semantic.Warning`；`AppTypography` 零使用，98 个 `.sp` 字面量 20 档，13–14sp 共 23 处在 10 英尺距离偏小；`TvUiComponents.kt:196-208/259` 焦点缩放/描边不受 reduceMotion（`TvHomeScreen.kt:80` 却有门控）。焦点可见性本身足够。
- `RootComponent.kt:54-62`：`_activeTab` 未接 stateKeeper，进程重建后回到 `startupTab`，保存的滚动位置要等切回原 tab 才复用。
- 外观偏好键膨胀：19 个键中 appearance 12 个（粒子光/粒子样式/脉冲扫光/开屏动画/开屏变体/弹窗动画/弹窗实验室/玻璃风格/服务器布局/背景图/背景暗度/启动 tab），是 `ProfileScreen.kt` 1,673 行的主因之一。
- `Poster.kt:141-148` 每张图 reveal 期间挂 `BlurEffect`，网格快速滚动时同帧多张各自一个模糊层；LazyGrid 中应默认 `alphaOnly`。

### 3.4 工程化与测试

- ktlint 基线重复且失效：`config/ktlint/composeApp-baseline.xml`（701 条，迁移前）记录的 123 个文件已不在 composeApp source set，`phoneShared-baseline.xml`（695 条）再记一遍；压制全为格式规则。缩为仅 `build.gradle.kts` 条目。
- CI 缺口：所有 workflow 只跑 `:composeApp:lintDebug`，`:tvApp:lint*` 零引用；`androidInstrumentedTest` 26 个文件只在 `build-yfuse-mpv-dolby.yml:156` 编译，quality-gates-v2 不编译；8/21 个 workflow 绑定死分支，`ycore-audio-repair.yml:6/38`、`ycore-audit-repair.yml:8` 引用的 `.github/repairs/*` 在 HEAD 不存在。
- 本地构建性能配置全关：`gradle.properties:1-2` caching/configuration-cache=false，:11 `kotlin.incremental=false`（为签名验证却对每次 dev 构建生效），未设 parallel；把这些移到发布脚本参数。`settings.gradle.kts` 未配 foojay resolver，无本地 JDK 17 直接失败。
- 版本目录漂移：`libs.versions.toml:12 ktor=3.0.3` 但 5/6 份 lockfile 解析为 3.1.0；serialization 1.7.3 实际 1.8.0；bouncycastle 1.79 被 `scripts/security-overrides.properties:3` 强制 1.84；根 `build.gradle.kts:25-27` 又硬编码 netty/protobuf/wire，两套覆盖机制。`tvShared/build.gradle.kts:93` 引用不存在的 `../tvApp/src/test/kotlin`。
- 测试缺口：core/data 60 文件中 31 个无同名测试，9 个 `Emby*Service` 在测试代码零引用（仅经 `EmbyRepository` 间接覆盖）；`WatchTogetherClient/Transport`、`UserStateWriter` 零引用。`androidUnitTest` 9 处 `Thread.sleep`（`AndroidAdaptiveProxyTransitionTest.kt:299`、`AndroidDemuxReadAheadNodeTest.kt:84/247/260`、`AndroidMediaExtractorReadAheadNodeTest.kt:313/399`）易抖动。
- 仓库卫生：`.git` 1.7 GB（历史含约 210 MB 的 `.so`/截图已从 HEAD 删除但未重写；31 MiB 垃圾对象、18.8k 悬空对象、16 个 stash、4 个 worktree）；工作区约 13 GB 非源码数据（根目录 26 个 APK 613 MB、`build/` 1.9 GB 被当草稿区、`.gradle-tmp` 4.5 GB、`.worktrees` 3.1 GB、`artifacts/` 643 MB、`audit/` 671 MB 含 10 个签名 APK）；`.gitignore` 缺 `.workbuddy-ai/ .codex-tools/ .tmp-carousel-qa/ audit/**/*.zip`；44 个未跟踪 audit 目录让 `git status` 失去信号。
- 发布证据版本化不一致：`audit/releases/221/222-build-request.json` 停在 1.0.59/1.0.60，1.0.61–1.0.66 记录全部未跟踪；AGENTS.md 要求核对的 `artifacts/releases` 被 `.gitignore:52` 忽略。
- 文档陈旧：`NOTICE` 引用的 "Dependency snapshot and SBOM" workflow 不存在；`design-qa.md:3-6` 指向已删除的 `audit/v26-logo/*.png` 与本机路径；`docs/` 多处仍写 `:composeApp:testDebugUnitTest`；README 未提及底部 4 Tab + 独立搜索键。

## 4. 系统性重构方向

1. **`EmbyRepository` 抽 `MediaServerAdapter` 接口**：约 45 个方法都是 `if (server.kind == Plex) plex.x() else service.x()`；抽接口后 Repository 只做 `adapterFor(server).x()`。同理 `AccountRepository` 15 个方法的 `detached { runCatching { mutex.withLock { … } } finally { password.fill() } }` 模板可收敛为一个 `guarded()`。
2. **播放器引擎交接统一入口 + 引擎持有者出组合期**：`requestEngineRebuild()` 收敛 11 处重建；新增 `PlayerEngineHost : RememberObserver`，在其中用 `AndroidPlayerReleaseBarrier` 等待上一引擎 `releaseAndJoin` 后再构造下一引擎，mpv/Exo release 移到后台线程。`PlayerRoot`（3,560 行、约 60 个 `LaunchedEffect`、6 处重复 `playbackHandoverSnapshot(...)`）按引擎编排 / 投屏绑定 / 会话特性 / Chrome 宿主拆四个文件。
3. **弹窗与外观收敛**：`DialogAnimation` 拆 `Production`（5 款）与 `Lab`，Lab 连同 SciFi/Expressive/Playful/Delight/Curious 五个文件迁 debug source set 或删除；开屏只留 1+1；外观偏好键从 12 个减到 5 个以内。这一步同时解决 `ProfileScreen.kt` 体积。
4. **Token 化路线**：补 `Dimens.space` 阶梯；把 profile 里的 `SettingRow/SwitchRow/Section/SettingsCard` 升格进 `core/designsystem`，新增 `Chip(selected, role)`、`SectionHeader(title, seeAllLabel)`、`EmptyState(icon, text, action)`、`RecommendBadge`；材质常量并入 `Palette`；加 ktlint/detekt 自定义规则禁止 feature 目录出现 `[0-9]+\.dp` 与 `Color(0x` 字面量（先对新代码生效）；让 `pressable` 在 `role == Button` 且无文本子节点时要求 `label`。
5. **TV 端**：强制深色、从 `DarkPalette/Semantic` 派生 6 色、建 `TvType` 四档（≥16sp 起）、焦点动效接 `reduceMotion`。手机端默认主题改 `System`/`Dark`。
6. **工程化**：删除入库的 `confirmMdkDistributionRights`；quality-gates-v2 增加 `:tvApp:lintDebug` 与 `:composeApp:assembleDebugAndroidTest`；移出 8 个死分支 workflow；本地开 parallel/caching；`git worktree prune` + `gc`，评估 filter-repo 重写 `.so`/截图历史；根目录 APK、`build/` 备份、`artifacts/` 移出仓库目录；决定 `audit/releases` 只入库 README + verification.json。

## 5. 未覆盖与待验证

- 真机只截到首页一张；库、搜索、我的、详情、播放器的视觉与动效未在 1.0.66 装机后复核。对比度数值均为估算。
- 未审查 `watchTogetherServer`、`castReceiver`、`harmonyApp`、`ycore-native`。
- 未运行构建、测试、Lint；C1/C4/C5 的现象（异常、双解码器、Binder 频率）未在设备上复现。
- 与产品对标报告 O1（TV 设置与能力不一致）、O8（接入引导）有交集的项本报告只补充了代码层证据，未重新评估产品优先级。
