# Yfuse 播放与接入能力盘点

日期：2026-09-15。范围：当前工作区源码、用户可达入口、手机最近已交付 APK 的原生库清单。只读检查生产代码，未启动应用、未跑测试、未验证电视/功放/投屏接收器实机表现。本文的优先级是产品改进顺序，不是缺陷严重等级；“未见入口”不等于底层协议完全未实现。

## 一、必须先区分的运行边界

1. **手机与平板当前不能一概当作纯内核产品。** `composeApp/build.gradle.kts:25` 的 `yfuseNativeOnlyRuntime` 默认 false。`PlaybackPreferences.kt:241` 的 YCore 试用默认 true，但 `:251` 的纯内核偏好默认 false，且只在试用开启、自动模式下生效。最近的 `artifacts/releases/search-field-1.0.55-217/verification.json` 记录正式包 1.0.55/217；直接读取该 APK 的 ZIP 清单可见 `libmpv.so`、`libmdk.so`、`libavcodec.so`、`libycore_demux.so`。因此，不能把可选 native-only 的限制写成所有手机用户的默认限制。
2. **独立 TV 应用确实走纯内核。** [tvApp/build.gradle.kts](/D:/Demo/Yfuse/tvApp/build.gradle.kts:169) 硬编码 `YFUSE_NATIVE_ONLY_RUNTIME=true`，且 MDK_INCLUDED=false。播放器工厂优先按该标记构造 YCore；纯内核失败不会偷偷切换兼容播放器。见 [PlayerEngineFactory.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerEngineFactory.kt:60)、[PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:585)。此处是源码配置结论，未检查本轮 TV 实际 APK。
3. **“禁止转码”需要限定适用范围。** 纯内核基线明确拒绝 serverTranscode；Dolby 非光盘片源默认保留本地处理，只有用户手动选择才允许协商转码。普通 full 手机路径对服务器已批准、具有具体 URL 的转码回退仍有实现。因此，建议遵守既有直连产品约束，但不能声称仓库完全没有服务器转码代码。见 [YCoreNativeSourceGate.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core2/release/YCoreNativeSourceGate.kt:38)、[PlaybackTruth.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackTruth.kt:18)。
4. **纯内核也不是只会播放普通 MP4。** 生产适配会给 HLS/DASH、已支持的 Blu-ray、Widevine、DV Profile、外挂字幕设置支持标志；不能仅凭 gate 的枚举名断言这些模块缺失。见 [AndroidCore2Trial.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCore2Trial.kt:262)。实际格式、容器、DRM、设备输出仍有条件，应使用能力矩阵而非“全部支持”的宣传。

## 二、已经实现且有入口的强项

| 领域 | 已实现/可达能力 | 证据 |
| --- | --- | --- |
| 手机/平板播放 | 手机 full 配置具有 YCore 与兼容引擎；音轨、字幕、系列偏好、倍速、睡眠定时、投屏控制集成于播放器。支持 PiP。平板不是固定三列手机页面：媒体库用自适应列，详情头图按窗口宽度档位调整。 | [PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:1197)、[PlayerActivity.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerActivity.kt:927)、[LibraryGridScreen.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryGridScreen.kt:268)、[DetailScreen.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailScreen.kt:365) |
| TV 播放 | 有独立 TV 首页、详情、遥控器焦点、播放设置、下载管理。可设置缓冲、回看缓存、内容帧率匹配、兼容音频直通；片头片尾配置已有入口。 | [TvPlaybackSettingsScreens.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvPlaybackSettingsScreens.kt:76)、[TvPlaybackSettingsScreens.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvPlaybackSettingsScreens.kt:195)、[TvDownloadsScreen.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvDownloadsScreen.kt:48) |
| 离线下载 | 手机详情有本集/整季/未看集、版本、画质、单条外挂字幕和追更新集选择。管理页具有 Wi-Fi/并发/容量预算/充电/下载时间窗/已看自动删除/追更规则和数量限制。TV 复用同一引擎，有播放、暂停、续传、重试、删除与策略设置，不能写“TV 没有离线”。 | [DetailDialogs.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailDialogs.kt:131)、[OfflineMedia.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineMedia.kt:56)、[DownloadsScreen.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/DownloadsScreen.kt:343)、[TvDownloadsScreen.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvDownloadsScreen.kt:219) |
| 投屏和续播 | DLNA/Chromecast 发现、投放、远端暂停/进度/音量/能力显示；队列切换可回落到逐项加载；投屏终止回到本机进度。另有加密云进度同步以及 TV Cast Connect 接收 Load 的代码。不能写“没有跨设备续播/手机投 TV”。 | [PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:2664)、[PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:3218)、[CastManager.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/cast/CastManager.kt:305)、[PlaybackSyncManager.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:37)、[TvMainActivity.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/TvMainActivity.kt:67) |
| 字幕 | 内封及外挂字幕、主副双字幕、偏移、独立字号、位置/样式、按剧记忆已有；原生 Direct 后端明确支持副字幕与独立偏移。在线中文字幕搜索/服务器安装已有。 | [PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:1251)、[PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:2852)、[AndroidNativeDirectYPlayer.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt:247)、[PlayerSettingsPanel.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt:411) |
| 网络和首次接入 | Emby/Jellyfin/Plex 三类接入；同网扫描、账号登录、Plex 云账号授权/Home 用户/云服务器选择、TV 快速连接；服务器多地址、测速、自动切备用、HTTPS 诊断已有；备份迁移也已有。不能建议“新增 Plex/服务器扫描/测速/备用地址”作为新模块。 | [AddServerDialog.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt:119)、[AddServerDialog.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt:256)、[TvServersSettingsScreens.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServersSettingsScreens.kt:426)、[ServersTabScreen.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersTabScreen.kt:1624)、[ServersTabScreen.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersTabScreen.kt:1773)、[TvServerBackupScreen.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt:166) |

## 三、建议优先比较和改进的 6 个方向

### 1. 优先修正 TV 内核设置与真实运行能力不一致

- **现状/证据：** TV 高级播放页可从设置进入；[TvPlaybackSettingsScreens.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvPlaybackSettingsScreens.kt:163) 把全部 `PlaybackEngineSelection.entries` 显示为可选项，共用文案承诺“固定使用 Media3/MPV/MDK”（[ProfileSettingsScreens.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt:58)）。但 TV 的纯内核标志为 true，工厂的 `packagedNativeOnly` 优先于这些选择（[PlayerEngineFactory.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerEngineFactory.kt:78)）。MDK 未打包时，偏好 setter 又把它归回 Auto（[PlaybackPreferences.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackPreferences.kt:221)）。
- **用户价值：** 用户遇到黑屏/无声时会尝试“兼容模式”，当前界面容易制造已更换引擎的错误预期。这比继续增加开关更值得先修。
- **建议：** 由实际包能力生成选项；TV 只显示确实生效的本地解码/输出策略，对不可用选项给出简短原因。保留直连、纯内核约束，不建议为兑现旧文案而重新引入兼容回退。
- **判断强度：** 高；生产配置→入口→偏好写入→工厂分支已闭合。后续验证应包含 TV 设置选择后实际引擎名称和行为，而不仅检查选中状态。

### 2. 把字幕“已有功能”做成可解释、可补救的完整流程

- **现状/证据：** [PlayerSettingsPanel.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt:411) 固定显示“搜索中文字幕”；调用未传语言，仓库默认 `zh`（[EmbyRepository.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt:1043)）。Plex 不支持该商店接口时返回成功空列表，最终被显示为“未找到字幕”（[PlayerRoot.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:1371)），未区分服务端不支持和真的无匹配结果。播放器及 settings 的可达 actions 中未见系统文件选择器导入本地字幕的入口；已有 external subtitle 数据和原生加载器不等于已提供该入口。
- **建议：** 先按服务器能力展示搜索状态和替代方法；增加语言选择与本地 SRT/ASS/VTT 文件导入，复用现有字幕加载、双字幕、系列记忆能力。Plex 可先明确解释接口限制，再评估独立字幕供应商，不应假装调用 Emby 商店即可适配。离线模型现在只保存一条 sidecar（[OfflineMedia.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineMedia.kt:96)），若主打学习/双语需求，可再补双字幕离线保存。
- **判断强度：** 固定语言、Plex 误导性空状态为高；本地字幕“未见 UI 入口”为当前生产 actions 检索结论，不宣称底层完全不支持外挂。

### 3. 补齐 TV 下载创建入口，复用已经成熟的下载引擎

- **现状/证据：** [TvDetailScreen.kt](/D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvDetailScreen.kt:129) 的下载直接创建当前 item 请求，仅传服务器/媒体源/剧/季等，不提供批量范围、外挂字幕、追更新集选择；手机 [DetailDialogs.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailDialogs.kt:131) 已有这些决策。
- **建议：** 为遥控器做简短的“本集/未看集/整季＋字幕＋自动追新”确认页，显示现有容量估算并复用相同 enqueueBatch 逻辑。不要在纯内核 TV 上机械复制依赖服务器转码的低画质选项；原画和已存在的可直连多版本选择即可。
- **判断强度：** 高；TV 单项生产调用与手机选择流程可直接对照。不是建议重写下载服务。

### 4. 将已有网络诊断和格式能力前置为“首次成功播放”引导

- **现状/证据：** 添加服务器流程已有扫描/登录/错误提示；错误固定显示在连接按钮上方（[AddServerDialog.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AddServerDialog.kt:459)）。HTTPS 诊断、线路测速在服务器管理中；原生源格式拒绝在播放器工厂创建阶段才形成错误（[PlayerEngineFactory.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerEngineFactory.kt:112)）。未见把登录成功、媒体可见、播放 URL 可达、直连能力和输出检查串成首播向导的产品入口。
- **建议：** 第一次连接后提供一张可跳过的准备状态卡：账号/媒体库→当前线路→可直连版本→音频/字幕准备；失败时给“测速备用线路/选择另一原画版本/下载后播放/更换输出策略”等实际可行操作。原画码率高于网络能力时明确说明限制，避免用自动服务器转码作为对直连产品的默认建议。
- **判断强度：** 产品机会；不是“没有错误处理/没有网络诊断”的缺陷判断。应先通过首播漏斗和真实用户访谈验证优先级。

### 5. 若要正面覆盖 NAS 播放器市场，应把 SMB/WebDAV 能力变成可达产品入口

- **现状/证据：** 已有 [AndroidSmbMediaTransport.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidSmbMediaTransport.kt:20)、[AndroidMediaExtractorDemuxNode.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidMediaExtractorDemuxNode.kt:500) 的 WebDAV/SMB 读取，以及 Blu-ray transport；不是零协议能力。但服务器模型和添加页只提供 Emby/Jellyfin/Plex（[SavedServer.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/model/SavedServer.kt:7)）。`PlayerMediaItem.transportCredentials` 仅见向下游传递，未见用户输入链；手机 PlayerActivity 不导出（[AndroidManifest.xml](/D:/Demo/Yfuse/composeApp/src/androidMain/AndroidManifest.xml:158)）。纯内核前置 gate 的 scheme 集合也尚未包含 smb/webdav（[YCoreNativeSourceGate.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core2/release/YCoreNativeSourceGate.kt:89)）。
- **建议：** 这是中期产品方向：单独的网络共享来源、凭据、目录浏览/收藏、字幕同目录匹配；把已有 transport 接到真实入口并逐格式补准入验证。不要只把 scheme 加进白名单就宣称 NAS 播放完成；无需建设服务器转码。
- **判断强度：** 底层已有/入口未产品化为中高；未进行所有外部 intent 或第三方调用环境验证。若当前定位仅为媒体服务器客户端，可明确暂缓。

### 6. 在进度续播和局域网投屏之上，评估同账号主动接力

- **现状/证据：** 加密云进度同步已有，投屏→本机恢复已有，TV Cast Connect Load 也已接入。账户“设备会话”页目前可刷新和撤销登录（[AccountSessionsScreen.kt](/D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSessionsScreen.kt:100)），未见可把当前播放主动交给任意同账号在线设备的控制入口。
- **建议：** 如竞品验证确有价值，增加“在这台设备继续”动作，明确携带媒体身份/版本、进度、主副字幕偏好，接收方先确认直连能力，成功接管后再暂停原设备。可跨网络工作；局域网继续优先用已有 Cast/DLNA。不要把已有云续播重新包装为全新模块。
- **约束：** Cast Connect 的代码接线不等于部署完成。[CastConnectReceiverBridge.kt](/D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/tv/integration/CastConnectReceiverBridge.kt:248) 把本地 ready 与 Google Console 关联验证分开；此盘点没有外部配置或实际投屏端到端证据。
- **判断强度：** 产品机会；“未见主动接力 UI”范围有限，不能表述成“Yfuse 没有跨设备播放”。

## 四、不宜直接列成缺口的项目

- 自动追更下载、容量预算、充电/时间窗、整季下载、已看自动删除：已有数据模型、管理入口和执行调度。
- 双字幕、字幕位置/样式/偏移、外部字幕解析：已实现，NativeDirect 也支持双字幕。应讨论来源接入、Plex 能力说明和离线双字幕保存。
- HLS/DASH、Widevine、Blu-ray、Dolby：有条件实现，不能从拒绝原因枚举推断全部缺失，也不能未经实机测试宣称无条件覆盖。
- 备用地址、自动线路切换、网络测速、HTTPS 诊断、局域网扫描、Plex 云登录：已有。
- TV、平板适配：TV 有独立应用与遥控器页面；平板有自适应网格和详情尺寸。平板双栏/导航形态还可做专门体验评估，但本轮没有截图与设备交互证据，不将其列为已证体验缺陷。
- Cast Connect 注册 ID、receiver handler 存在：是实现证据，不是已完成 Google Console 关联和实机可投的证明。

## 五、建议落地次序

先做 1、2、3：入口真实、影响明确，主要复用现有能力。随后用首播失败率/错误类别验证 4。5、6 属于需结合目标用户与竞品定位取舍的中期功能；不应挤占直连稳定性、设备输出验证和现有功能可发现性的投入。
