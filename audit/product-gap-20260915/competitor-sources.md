# 同类播放器功能与体验基准：官方来源核验

核验截止：**2026-09-15**。对象：Infuse、Plex、Emby、Jellyfin；补充 Kodi 的电视遥控基准。

本文件提供后续检查 Yfuse 的比较基准，**不代表已经确认 Yfuse 缺少这些功能**。下列“建议验收”是根据用户场景提出的建议；“已核实行为”才是官方页面支持的竞品事实。未登录竞品实测，也不据文档推断具体设备的流畅度。

来源限定为官方产品、帮助、发布页面。日期注明页面的更新时间或版本发布日期；没有显示日期的页面明确记为“未标注”，访问日期统一为 2026-09-15。Plex 部分页面直接打开返回 403，使用网页搜索返回的**同一官方 URL 的正文和 Last modified 字段**核实，没有采用第三方转述。未把计划、论坛提议或待发布能力列为已发布。

## 阅读前提

- Infuse 当前是 Apple 平台产品，覆盖 iPhone、iPad、Apple TV、Mac、Vision。对标的是接入、找片和播放的用户结果；iCloud、AirDrop、Siri Remote 手势、Live Activity 不作为 Android 必须提供的实现。[What is Infuse?](https://support.firecore.com/hc/en-us/articles/360045051934-What-is-Infuse)（更新：2026-05-06）。
- Infuse 有免费版与 Pro；云盘、高级音视频及部分跨设备能力受 Pro 限制，不能由“免费下载”推定全部免费。本文件优先采用功能帮助页的具体条件。[Infuse 产品页](https://firecore.com/infuse)（日期未标注）。
- Jellyfin 需要自建或获授权访问的服务器；官方服务器和客户端免费。[Jellyfin 产品页](https://jellyfin.org/)（日期未标注）。

## B01 首次接入：让新用户完成从添加来源到首次播放

**已核实行为：** Infuse 首次启动引导添加视频来源，支持网络共享、云盘、Plex/Emby/Jellyfin；接入后整理媒体并获取海报与元数据，再进入播放。它没有要求用户先理解播放器内核和网络调优参数。

**适用前提：** Apple 平台；来源须可访问并拥有凭据。云盘等 Pro 条件见上方产品页；某种来源可添加，不代表所有格式均可免费播放。

**建议验收：** 使用空配置进入应用，沿“选来源 → 验证连接 → 选媒体库 → 播放首项”完成任务；连接失败时保留填写内容，并区分地址错误、凭据错误和无可访问媒体库。记录任务完成率与失败位置，先找出阻碍首次播放的步骤。

**直接来源：** [Quick Start Guide](https://support.firecore.com/hc/en-us/articles/4405018871575-Quick-Start-Guide)（更新：2026-05-06）。

## B02 电视接入：用已登录手机授权，减少遥控器输密码

**已核实行为：** Jellyfin Quick Connect 在新设备显示 **6 字符**临时代码，由另一台已登录客户端确认。Android 手机可以登录和授权其他设备；Android TV 可以通过代码登录，不能授权其他设备。代码无效或过期会报错。

**适用前提：** 服务器启用 Quick Connect，并有一台已认证客户端；不是无需服务器的扫码登录，也不是 Android TV 与手机功能完全对等。官方客户端无订阅门槛。

**建议验收：** TV 支持代码授权时显示有效期或刷新入口；验证过期、拒绝授权、服务器不支持、等待期间返回等分支。仍应允许手工登录，不能把手机作为唯一入口。

**直接来源：** [Quick Connect](https://jellyfin.org/docs/general/server/quick-connect/)（日期未标注）。

## B03 共享电视：快速切换使用者，尊重服务端权限

**已核实行为：** Plex Home 支持不反复退出登录的用户切换，以及受 PIN 保护的成员；官方给出 Android TV 客厅使用案例。Jellyfin 可以按用户分配媒体库及限制播放、下载等权限。

**适用前提：** Plex 需先建立 Home；邀请已有独立 Plex 账号、定制共享限制需要 Home 管理员的 Plex Pass。Plex 快速切换需要互联网认证，不能当作完全离线能力。PIN 是便利性的访问控制，不能替代真实账号认证。Jellyfin 的权限由服务器管理。

**建议验收：** 显示当前用户；切换后继续观看、收藏与下载入口重新按该账号加载。服务器拒绝下载或转码时说明权限条件，不能把所有拒绝都显示为网络错误；本地缓存须按服务器及用户隔离。

**直接来源：** [Fast User Switching](https://support.plex.tv/articles/204232453-fast-user-switching/)（更新：2023-11-19）；[Example Plex Home Setup](https://support.plex.tv/articles/204234313-example-plex-home-setup/)（更新：2024-05-08）；[Creating a Plex Home](https://support.plex.tv/articles/204234323-creating-a-plex-home/)（更新：2022-03-16）；[Internet and Network Requirements](https://support.plex.tv/articles/200484903-internet-and-network-requirements/)（更新：2025-11-10）；[Jellyfin Managing Users](https://jellyfin.org/docs/general/server/users/adding-managing-users/)（日期未标注）。

## B04 多来源找片：统一库与搜索，也控制大库同步成本

**已核实行为：** Infuse Library Mode 把多来源合入同一媒体库，提供分类、筛选和搜索；可以选择纳入的来源。Direct Mode 按需获取服务端信息，减少本地存储并更适合大库，但离线浏览能力受限。UPnP/DLNA 来源不能使用其 Library 功能。

**适用前提：** Apple 平台；所连接协议与模式决定能力。离线浏览海报与条目信息不等于视频已离线下载；不能把多来源统一库能力概括成“所有协议无差别支持”。

**建议验收：** 用两个同名影片、一个不可达来源和一个大库检查搜索；结果标明来源并能选播放版本。搜索范围可理解，单个来源失败不应让全部结果消失。首次接入大库时应允许尽早浏览，并解释后台整理进度。

**直接来源：** [Setting Up Your Library](https://support.firecore.com/hc/en-us/articles/115000074814-Setting-Up-Your-Library)（更新：2026-05-06）；[Streaming from Plex, Emby, and Jellyfin](https://support.firecore.com/hc/en-us/articles/360006462093-Streaming-from-Plex-Emby-and-Jellyfin)（更新：2026-05-29）。后者只用于模式与搜索能力，旧转码描述见文末时效说明。

## B05 离线观看：视频、字幕、下载状态形成完整任务

**已核实行为：** Plex 支持将个人服务器媒体下载到 Android 手机、iOS 和桌面客户端，可原文件下载或调整画质；其支持列表不含 Android TV。Infuse 的离线下载限 iOS/macOS，显示进度并可同时下载字幕。

**适用前提：** Plex 常规资格为下载用户拥有 Plex Pass，或属于有 Plex Pass 管理员的 Plex Home 的受管用户；访问别人的服务器还须获准下载。历史账号有 FAQ 所列资格例外。Plex 自营广告内容不适用此功能。Infuse 下载来源还受连接及格式权限约束。

**建议验收：** 下载前解释权限、画质与空间条件；覆盖中断恢复、取消、空间不足和字幕获取失败。下载完成后切到飞行模式实际播放并选择字幕；结束后联网回传观看进度。电视优先保证在线观看稳定，不因手机竞品有下载功能就要求 TV 同步实现。

**直接来源：** [Downloads Overview](https://support.plex.tv/articles/downloads-overview/)（更新：2025-01-09）；[Downloads FAQ](https://support.plex.tv/articles/downloads-sync-faq/)（更新：2025-06-16；部分内容带旧移动版限定）；[Downloading Files](https://support.firecore.com/hc/en-us/articles/215091037-Downloading-Files)（更新：2026-05-06）。

## B06 Trakt：同步方向可解释，避免不同来源争夺观看状态

**已核实行为：** Infuse 免费版可向 Trakt 单向发送，Pro 增加双向同步。需要 Trakt 账号、在设备登录并开启 Scrobbling。**连接 Emby/Jellyfin/Plex 时例外：**观看状态以媒体服务器为准，不从 Trakt 拉回观看历史。

**适用前提：** 这是 Infuse 的具体规则，不意味着所有客户端都必须采用同样付费模式。服务器连接与普通共享文件应分别讨论，不能声称购买 Pro 后任何来源都双向同步。

**建议验收：** 设置页说明上传/下载方向以及哪些来源生效；覆盖授权失效、手动改已看、离线补传和重复提交。同步失败能看见且可重试，避免静默覆盖用户刚修改的进度。

**直接来源：** [Trakt Sync](https://support.firecore.com/hc/en-us/articles/4405098078743-Trakt-Sync)（更新：2026-05-06）。

## B07 跨设备继续观看：区分播放位置、已看状态和设置同步

**已核实行为：** Infuse 连接媒体服务器时自动同步进度、观看历史等，并使用服务器而非 iCloud。普通来源的 iCloud 同步要求同一 iCloud 账号、开启 Drive 且有空间；播放位置与已看历史属于 Pro。Plex 的账号级 Watch State/Ratings 同步**不包含中途播放位置**。

**适用前提：** Plex 该功能需主动启用、服务器 1.27.2+，并使用指定电影/剧集元数据代理；跨服务器更新可能延迟约 30 分钟。Infuse 的 iCloud 机制仅适用于 Apple 生态；Android 可以沿用媒体服务器或另行设计跨平台同步。

**建议验收：** 同账号设备 A 播放后退出，设备 B 展示可继续的位置；同时测试两个设备先后写入、离线补传及切换用户。分别标识已看、进度和偏好设置，避免把“账号观看状态同步”承诺为“实时跨设备续播”。

**直接来源：** [Media Server Sync](https://support.firecore.com/hc/en-us/articles/4405110679447-Media-Server-Sync)（更新：2026-05-06）；[iCloud Sync](https://support.firecore.com/hc/en-us/articles/115000070773-iCloud-Sync)（更新：2026-05-06）；[Sync Watch State and Ratings](https://support.plex.tv/articles/sync-watch-state-and-ratings/)（更新：2025-03-24）。

## B08 片头与片尾：根据可用标记提供可控的跳过行为

**已核实行为：** Emby 支持忽略、自动跳过或显示跳过按钮。片头识别需要服务器 4.7+、Emby Premiere、电视媒体库启用检测；同季至少两集，后台分析完成后才有结果。Infuse iOS/iPadOS 8.4 已发布片头、片尾、回顾跳过；8.5.2 又提供片头与片尾独立设置。

**适用前提：** Emby 的检测属于服务器能力，客户端不能替用户绕过版本、许可或尚未生成标记的条件。Infuse 上述版本证明的是 iOS/iPadOS 已发布，不能据此假定所有 Android 服务端配置拥有相同标记。

**建议验收：** 自动跳过、手动按钮和关闭分别验证；没有标记时正常播放，不能出现无效按钮。区分片头与片尾偏好，并覆盖连续剧下一集衔接、拖动到区间内与异常区间数据。

**直接来源：** [Emby Intro Skip](https://support.emby.media/support/articles/Intro-Skip.html)（日期未标注）；[Infuse Release Notes](https://firecore.com/releases)（iOS/iPadOS 8.4：2026-03-24；8.5.2：2026-08-26）。

## B09 字幕：给用户选择结果和排查失败的途径

**已核实行为：** Emby 提供按语言搜索、选择并下载字幕的流程。前提是服务器已配置字幕插件且用户有下载权限；无结果可能来自筛选条件、作品本身无字幕或媒体识别错误。

**适用前提：** 需要 Emby 服务器与可用字幕插件；文档没有说明所有提供商均免费或无需账号，不能把“客户端支持搜索”当作已拥有可用的字幕服务。

**建议验收：** 在播放器或详情页能到达字幕操作；明确区分无权限、提供商未配置、无匹配与下载失败，并允许重试或修改语言。验收时包含错误识别作品及外挂字幕；不要只用自带字幕的正常影片验证。

**直接来源：** [Manual Subtitle Downloads](https://support.emby.media/support/articles/Manual-Subtitle-Downloads.html)（日期未标注）。

## B10 电视遥控：主要操作由方向键完成，入口能被发现

**已核实行为：** Kodi 按客厅远距离操作设计。默认 Estuary 皮肤用方向键打开侧边选项，界面以箭头提示；长按 OK 可打开当前媒体的上下文菜单，屏幕键盘也可由遥控器输入。

**适用前提：** Kodi 支持 Android 等平台，免费开源；基本本地播放不需要 Plex/Emby 服务器。上述文档针对默认皮肤且标明 v21，不能推定所有第三方皮肤和遥控器完全相同。

**建议验收：** 仅用方向键、OK、Back 完成选片、音轨/字幕切换、进度调整与恢复播放。检查弹窗关闭后焦点回到原项、列表滚动后仍看得见焦点、长按入口有提示或可见替代入口；再实测真实 TV 遥控器，不能只靠触屏模拟器。

**直接来源：** [Kodi Basic controls](https://kodi.wiki/view/Basic_controls)（最后编辑：2024-06-30；页面标记适用于 v21）；[About Kodi](https://kodi.tv/about/)（日期未标注）。

## 最新发布记录与旧帮助页的冲突处理

本轮检查了 [Infuse 官方发布记录](https://firecore.com/releases)，最新可见 iOS/iPadOS 版本为 **8.5.4，2026-09-14**，没有把未来计划列入比较。

- 2026-07-30 的 8.5 已加入 Emby/Jellyfin/Plex 转码选择，因此不能继续引用 2026-05-29 帮助页中“只尝试直接流式播放”的表述，来认定目前 Infuse 没有转码功能。
- 2026-08-11 的 8.5.1 加入原生后台下载，明确限 **iOS 26+**；2026-09-14 的 8.5.4 加入转码时切换音轨/字幕及多下载 Live Activity。这些是具体 Apple 平台的已发布能力，Android 的验收应转化为下载状态可见、生命周期可靠和选轨可用，而非复制 Apple API。
- Plex Downloads FAQ 对部分选项明确限定旧移动版本。本文件未用它推断新版 App 一定只能前台下载、一定有旧版自动剧集保留策略。
- Jellyfin Managing Users 的 “Download & Sync” 权限说明明确称该处同步/转码尚不可用；不能据栏目名称把 Jellyfin 官方客户端描述为具备与 Plex 相同的完整离线同步体系，也不能反推 Jellyfin 的在线播放不支持转码。

## 用于 Yfuse 差距评估的排序建议

1. **先验证是否阻碍核心任务：** B01 首次接入、B02 电视登录、B09 字幕、B10 遥控，以及 B07 继续观看的正确性。
2. **按实际用户需求决定范围：** B03 家庭多人、B04 多来源统一搜索、B05 手机离线。先统计使用人数与频率，再决定架构投入。
3. **作为可选增强：** B06 Trakt、B08 自动跳过；前提是基础播放稳定，并能解释服务端、账号和提供商条件。

上述排序是本次建议，不是竞品功能的严重级别，也不是对 Yfuse 已存在缺陷的判定。应与本地代码和真实用户流程的检查结果合并后形成最终差距清单。
