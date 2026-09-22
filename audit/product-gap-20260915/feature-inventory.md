# Yfuse 功能盘点：账号、同步、发现与追剧

日期：2026-09-15。依据当前工作区源码、实际导航/点击回调，以及主代理提供的已安装版 `1.0.54 (216)` 搜索页面 UI XML。本文不修改业务代码、不运行测试；代码存在不等于已完成双设备端到端验收。竞品能力及价格由主报告另行引用官方资料。

## 结论

Yfuse 已经具备跨服务器搜索、同作品片源合并与推荐、持久媒体身份与跨源播放进度、云端加密续播、服务器收藏和稍后观看、追剧日历及通知。应优化这些能力的完整体验，不应再次作为“缺失功能”立项。

本次最有依据的改进方向是：明确三类同步的边界并展示同步状态；补齐 Yfuse 账号级清单；在现有聚合基础上提供“全部服务器”的完整片库浏览；按需求增加独立家庭资料空间、观看历史页面及 Trakt 集成。运行中的跨设备进度刷新和家庭用户隔离需专项验证。

## 一、现有能力与真实入口

| 能力 | 已有实现与入口 | 证据 |
| --- | --- | --- |
| 账号、资料、安全管理 | “我的 → 账号与同步”支持注册/登录、昵称和头像、改密、登录设备会话、撤销会话、账号导出和删除；不是只有一个登录按钮。 | [入口与导航](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt:325)、[昵称/头像保存](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:385)、[改密](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:402)、[会话及导出入口](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:519)、[撤销会话实现](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/account/AccountRepository.kt:503)。 |
| 多个媒体服务器账号/用户 | 保存身份包含服务器地址和 `userId`。已有 Emby 公共用户选择、Plex Home 家庭用户选择与 PIN；库页可切换已保存服务器。不能泛称“没有多用户或家长控制”。 | [保存身份](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/model/SavedServer.kt:104)、[Emby 用户选择](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersScreen.kt:773)、[Plex Home/PIN 入口](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt:341)、[实际切换用户调用](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersStore.kt:790)、[库页切换](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt:617)。 |
| 加密账号设置备份 | “账号与同步”有上传、下载和清空云端操作。快照包含服务器配置、部分外观/网络/弹幕/跳过设置、同步开关、追剧关注；当前明确为手动、整体覆盖。一起看设备 ID、缓存、离线文件、日志、最近搜索不在快照内。 | [快照全部字段](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/CloudSyncSnapshot.kt:22)、[手动同步状态](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:564)、[覆盖确认](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:636)、[同步范围说明](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:1239)。 |
| 跨设备播放进度与播放偏好 | “我的 → 播放 → 进度同步”明确支持 Emby/Jellyfin 与 Yfuse 云端。播放器记录位置、完成状态、会话历史，播放文档还保存音轨/字幕/速度偏好；启动时拉取云端、播放期间上传，失败有待发送队列和重试。 | [设置入口及文案](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt:196)、[播放器接入](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackProgressReporter.kt:479)、[播放文档](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncModels.kt:79)、[启动同步](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:76)、[重试](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:589)。 |
| 持久统一媒体身份、跨源续播基础 | 已用 TMDB/IMDb/TVDB 等提供方键与别名识别作品，剧集支持“剧 ID + 季/集”；本地文档保留 canonical key 和 aliases。对于可迁移身份，收到云端状态后可查找并应用到多个服务器。缺元数据时限制到来源服务器。不能提议“从零新增统一身份/跨源进度”。 | [提供方键](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/WatchInvite.kt:215)、[剧集坐标与别名](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/WatchInvite.kt:264)、[持久主键/别名](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncStore.kt:366)、[身份匹配](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncStore.kt:661)、[跨服务器目标与查片](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:684)。 |
| 收藏、稍后观看、已看状态 | 详情页有收藏/稍后观看；首页有“我的收藏”。Emby/Jellyfin 收藏写回服务器用户数据，稍后观看使用真实服务器播放列表；Plex 稍后观看调用 Plex Watchlist。已有服务器状态同步队列及冲突处理方法。 | [详情操作](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailActions.kt:211)、[首页收藏](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt:432)、[稍后观看列表写入](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyBrowseService.kt:107)、[Plex Watchlist 分流](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt:526)、[收藏/已看同步操作](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/ServerSyncManager.kt:305)。 |
| 全局搜索、同片聚合、推荐片源 | 搜索支持跨服务器、媒体类型、服务器/库/年份/类型/观看状态/排序、人物相关搜索。同作品合成一张卡并保留多个具体片源，推荐结合健康与延迟；详情片源排序另考虑画质、版本和网络。 | [搜索状态及筛选](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchStore.kt:94)、[聚合请求结果](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchStore.kt:584)、[聚合卡 UI](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchScreen.kt:305)、[合并与排序](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/SmartSourceSelection.kt:43)、[详情推荐策略](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/SmartSourceSelection.kt:133)。 |
| 首页聚合与内容发现 | 首页汇集多服务器继续观看、下一集、最近添加、收藏；TMDB 发现包含热门、最新上线、正在上映、即将上映。它与“库”页的单服务器浏览是两种现有视图。 | [首页数据来源](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt:69)、[收藏/最近添加聚合](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt:108)、[多服务器续播加载](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt:504)、[TMDB 分类](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/TmdbRepository.kt:480)。 |
| 追剧日历、关注、更新提醒 | 首页有追剧日历/追剧中心入口；详情可追剧并配置关闭、播出时、提前与播出时、新集入库提醒；支持批量开关，媒体库剧集自动关注，排期变化及入库通知。不是只有静态日历。 | [首页入口](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeScreen.kt:919)、[详情追剧](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/SeriesAiringCalendarSheet.kt:370)、[提醒配置](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/SeriesAiringCalendarSheet.kt:436)、[自动关注](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/CalendarFollowStore.kt:108)、[批量提醒](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarScreen.kt:1275)、[入库通知](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:179)。 |
| 系统日历导出与提醒调度 | 追剧页菜单可导出 ICS；Android 同时使用 WorkManager 与下一次播出 AlarmManager，具备精确闹钟权限时走 exact alarm，否则降级；支持已发送去重。不能列“新增 ICS”或断言提醒只按 15 分钟轮询。 | [导出入口](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarScreen.kt:1726)、[实际分享回调](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarScreen.kt:357)、[ICS 构建](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarIcs.kt:5)、[闹钟策略](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:83)、[去重通知](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:274)。 |
| 同账号手机房主、平板观众 | 当前工作区服务端已按设备身份保存会员并绑定账号；权限判定按设备房主身份及 host epoch，平板不会因为同账号自动获得控制权。已有手机/平板独立权限、恢复与移出设备的回归代码。依赖本轮服务端部署；不据此声称线上已生效。 | [设备会员模型](/D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomModel.kt:121)、[房主权限判定](/D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomModel.kt:133)、[账号/设备身份验证](/D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomAdmission.kt:25)、[同账号双设备测试](/D:/Demo/Yfuse/watchTogetherServer/src/test/kotlin/com/yfuse/watch/WatchMultiDeviceTest.kt:30)。 |

实机交叉证据：[搜索 UI XML](/D:/Demo/Yfuse/audit/product-gap-20260915/screenshots/03-results.xml:1) 包含“沙丘2”“5 个片源 · 推荐 zhuiyun”“沙丘”“4 个片源 · 推荐 zhuiyun”以及“已收起 8 台无结果或不可用服务器”。它证明聚合结果已出现在已安装版界面，不能用竞品清单推定 Yfuse 尚未实现。

## 二、确认的差距与建议优先级

以下 P1/P2/P3 是产品投入顺序，不是上一轮代码缺陷的严重度。

### P1：统一同步说明、状态与恢复入口

- **差距**：目前有三套不同机制——手动加密配置快照、自动云端播放文档、媒体服务器收藏/已看同步。账号页显示“手动同步”，播放页只有进度开关；`PlaybackCloudSyncState` 已提供待同步数、最近成功时间和错误，但在手机/TV 功能页面中未检索到对该状态的消费。服务器队列也有冲突模型和 `resolveConflict`，但全项目调用检索只命中方法定义，没有用户可操作的冲突入口。
- **建议**：在“账号与同步”分清“播放进度自动同步”和“设置手动备份”；展示最近一次同步、离线待上传数、失败原因、立即重试及冲突选择。沿用现有状态与队列，先完成可见、可恢复的体验。
- **证据**：[手动同步文案](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:564)、[播放开关接线](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt:345)、[现有云端同步状态](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:29)、[冲突状态](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/ServerSyncManager.kt:115)、[冲突处理方法](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/ServerSyncManager.kt:319)。

### P1：Yfuse 账号级“想看/收藏”清单

- **差距**：已有收藏/稍后观看依附 Emby/Jellyfin 的用户数据/播放列表，或 Plex Watchlist；当前 Yfuse 账号快照和播放云文档都没有独立收藏、想看或自定义清单字段。首页汇总收藏不等于一个跨服务器长期保存的账号清单。Plex 还明确没有 Emby 式收藏能力。
- **建议**：以已有媒体身份/别名为基础增加账号清单，可保存尚未入库的 TMDB 作品，显示已入库片源，服务器移除后仍保留；区分“Yfuse 收藏”和“写回服务器收藏”。支持从现有收藏/稍后观看导入，不要求用户重建。
- **证据**：[Emby/Jellyfin 收藏写入](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt:469)、[服务器播放列表](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyBrowseService.kt:112)、[Plex Watchlist](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt:530)、[配置快照模型](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/CloudSyncSnapshot.kt:22)、[播放云文档模型](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncModels.kt:79)。

### P2：完整的“全部服务器”片库浏览

- **差距**：搜索及首页已有聚合，但“库”页依赖单一 `currentServer`，其服务器菜单只切换当前服务器。未见按统一作品身份分页浏览所有库、跨源统一筛选和总量的完整入口。
- **建议**：在现有库页增加“全部服务器”视图，复用搜索的作品聚合、推荐片源和来源展开；保留单服务器浏览。优先处理分页去重、不可用来源提示和电影/剧集筛选，避免新做另一套搜索。
- **证据**：[单服务器库状态](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryStore.kt:34)、[库页内容引用当前服务器](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt:222)、[服务器选择回调](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryHomeScreen.kt:617)、[可复用聚合模型](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/SmartSourceSelection.kt:19)。

### P2：Yfuse 家庭资料空间，而非重做媒体服务器用户

- **差距**：账号状态是单个 session，云端播放存储绑定一个 Yfuse 账号；账号切换会清理上一账号的本地播放文档。追剧关注是单一设备设置集合。现有服务器用户选择和 Plex Home 并不等于 Yfuse 层独立的家庭资料、关注/历史/推荐空间与快捷切换。
- **建议**：如家庭共用平板/TV 是主要场景，再增加资料选择器及独立历史、清单和追剧空间；明确每份资料与哪些服务器用户关联。儿童资料/PIN 应建立在隔离与服务器授权之上，不能只靠隐藏入口。
- **证据**：[单账号 session 模型](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/account/AccountModels.kt:173)、[切换账号后的播放分区策略](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncStore.kt:30)、[追剧设置键](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/CalendarFollowStore.kt:322)、[现有 Plex Home 切换](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersStore.kt:790)。

### P2：把已有观看历史数据变成可用页面

- **差距**：数据层已有播放会话历史，当前首页有继续观看，但主导航/“我的”页面未见完整历史列表、按日期查看、搜索/删除历史或观看统计入口。不能称“没有记录历史”。
- **建议**：先做最近观看/已看历史与单条清理，并解释清理范围；统计和年度回顾可后置。当前每部媒体历史有条数上限，不应直接把它当无限期、完整准确的观影统计数据源。
- **证据**：[历史结构](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncModels.kt:46)、[历史更新及截断](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncStore.kt:628)、[主导航](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/app/App.kt:178)、[我的页面枚举](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt:133)。

### P2/P3：按用户需求增加 Trakt 连接，个性化推荐后置

- **差距**：对 `composeApp/src`、`tvApp/src`、`watchTogetherServer/src` 全文（含按文本读取的文件）检索 `Trakt/Simkl/AniList/Letterboxd` 无命中，现有账号页面/导航没有连接入口。发现数据主要来自 TMDB 热门和发行日期排序，未见以用户观看偏好训练/排序的推荐管线。
- **建议**：如果目标用户已有 Trakt 历史/清单，先做官方授权、历史/想看导入、播放上报和冲突规则；其优先级可升到 P2。个性化发现先从屏蔽已看、不感兴趣、偏好语言/类型开始，减少复杂推荐投入。
- **证据**：[现有账号导航](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileScreen.kt:133)、[账号操作入口](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:519)、[播放云数据结构](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncModels.kt:79)、[TMDB 热门请求](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/TmdbRepository.kt:196)、[首页每日推荐选取](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeStore.kt:91)。第三方集成的“无命中”是本次源码审查结论，不代表远程私有分支或规划中不存在。

## 三、需要专项验证的体验，不能先判为缺失

| 验证点 | 已知边界/建议场景 | 证据 |
| --- | --- | --- |
| 两台设备都已运行时的云端续播新鲜度 | `startupPullAttemptedUserIds` 控制每账号的启动拉取，日常播放同步传 `pullRemote=false`，播放启动明确只读本地。媒体服务器本身刷新和云端写冲突可带来其他更新，故不能断言全部跨设备续播失败。验证手机播放后，已经打开的平板回前台/刷新首页/打开另一服务器同片是否得到新位置；必要时增加异步前台/手动拉取，避免阻塞开始播放。 | [启动拉取门槛](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:100)、[本地起播约束](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:179)、[云端拉取判定](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:212)、[运行期只推送调用](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:486)。 |
| 跨源身份不完整、不同剪辑/集序 | 搜索降级按类型/年份/标题合并，播放匹配有提供方别名；缺提供方 ID 时仅允许同来源服务器。验证多版本、同名翻拍、剧集特辑/绝对集数等，决定是否需要“识别错误/手动关联”入口。不要将现有身份模型再列新增。 | [搜索降级身份](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/SmartSourceSelection.kt:31)、[播放身份作用域](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncStore.kt:661)、[剧集坐标](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/WatchInvite.kt:264)。 |
| 同 Yfuse 账号下保存不同家庭媒体用户 | 服务器保存键区分 `userId`，但可迁移播放身份在 Yfuse 账号内跨服务器匹配。验证家庭不同人的观看记录是否符合产品预期；应先定义账号/资料归属规则，再宣传“独立观看历史”。 | [服务器用户身份](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/model/SavedServer.kt:104)、[Yfuse 账号绑定](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncStore.kt:38)、[跨服务器应用目标](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:684)。 |
| 收藏/追剧在多设备的“同步”含义 | 同一媒体服务器账号的收藏可由服务器共享；追剧关注通过手动配置快照传递。TV 登录文案概括“播放进度、收藏与服务器配置”，应验证用户是否会误以为全部由 Yfuse 实时双向合并。用两台设备分别添加关注再同步，确认覆盖提示是否足够清楚。 | [TV 文案](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvAccountScreens.kt:112)、[追剧快照字段](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/CloudSyncSnapshot.kt:33)、[整体替换追剧集合](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/CloudSyncSnapshot.kt:180)、[覆盖警告](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt:659)。 |
| 提醒到达率与无明确播出时间 | 已有通知权限检查、精确/非精确闹钟、周期任务和去重。仍需验证通知拒绝/恢复、系统省电、重启、时区变化、排期变更、首次关注与首次入库的行为。无具体播出时间或时区时，代码不会计算播出时提醒，应明确展示可用提醒模式。 | [权限/提醒任务](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:152)、[闹钟降级](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:100)、[首次入库基线](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:181)、[缺时间/时区过滤](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt:218)。 |

## 四、建议的交付顺序

1. **先验证和说明**：双设备续播/收藏/追剧同步矩阵，明确账号、媒体服务器用户、设备角色三者；把已有同步状态和错误恢复接到 UI。
2. **补用户资产**：Yfuse 想看/收藏清单、观看历史页、追剧关注的增量同步；继续复用现有加密云端和媒体身份。
3. **完善聚合入口**：“全部服务器”完整片库、身份纠错与版本选择体验。
4. **按人群投入**：家庭资料空间、Trakt 导入/上报、轻量个性化发现。

本报告只对当前源码和已取得的搜索 UI 证据负责；没有登录第三方服务、改动账号/资料、发送通知、运行测试、打包或部署。
