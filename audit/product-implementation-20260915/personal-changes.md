# 个人内容、家庭资料、同步与电视端接线

日期：2026-09-15。对应 O3、N1、N4；本轮仅修改代码与测试，没有播放、打包、部署或变更版本。

## 已实现

| 范围 | 实际行为 | 主要实现 |
| --- | --- | --- |
| 独立个人内容 | 想看、收藏、观看历史由 Yfuse 账号及家庭资料拥有，支持本地使用、重启恢复、检索、移除；TMDB 尚未入库作品也可加入。无外部身份的服务器条目按服务器区分，防止同编号误合并。 | `core/personal/PersonalLibraryModels.kt`、`PersonalLibraryRepository.kt`、`PersonalMediaImport.kt` |
| 合并同步 | “同步状态→合并个人数据”真实调用既有账号加密云同步；合并个人清单、历史、追剧及资料，不覆盖云端服务器配置。冲突版本重新读取并最多重试 3 次。上传期间的新本地修改保留待同步状态。 | `core/account/AccountRepository.kt`、`core/sync/CloudSyncSnapshot.kt` |
| 冲突与删除 | 使用递增逻辑版本与设备标识稳定排序；删除记录参与合并，旧离线副本和 Trakt/服务器导入不能复活已移除条目。旧同步包缺少 personal 字段仍可读取。 | `PersonalSnapshotMerge.kt`、`PersonalLibraryRepository.kt` |
| 家庭资料 | “我的→家庭资料→新建”；名称、儿童类型、关联媒体服务器用户、新建/编辑/切换/删除；默认主资料保留。清单、历史、播放同步实体、追剧与提醒去重空间分别隔离。 | `PersonalCenterScreen.kt`、`PersonalScopedSettings.kt`、`PlaybackSyncModels.kt`、`PlaybackVaultCipher.kt` |
| 家长 PIN | 随机盐 + PBKDF2-HMAC-SHA256 保存摘要；退出儿童资料或在儿童资料管理权限需要 PIN；5 次错误限制一分钟。PIN 更改使先前异步授权失效。会话到期/退出登录不会自动切回成人资料。 | `PersonalLibraryRepository.kt`、`AccountRepository.kt` |
| 中央访问策略 | `ServerRegistry.data/defaultServer/serverById` 投影当前资料允许的服务器；儿童未关联服务器时无可见内容。服务器新增、导入、导出及账号管理要求成人资料。加密云备份使用完整原始数据，避免因投影丢失隐藏服务器。 | `core/data/ServerRegistry.kt` |
| 异步隔离 | 旧资料的播放记录、服务器进度拉取、追剧自动发现与提醒结果需通过 scope token 检查；过期结果不写入当前资料、不推送其通知。非默认资料不导入共享服务器进度。云端到媒体服务器队列仅执行所属活动资料的任务，其他任务保留待切回。 | `CalendarFollowStore.kt`、`AiringCalendarRepository.kt`、`CalendarReminderWorker.kt`、`PlaybackSyncStore.kt`、`PlaybackSyncManager.kt`、`ServerSyncManager.kt` |
| 同步可见与恢复 | 显示个人数据待同步、上次完成时间及错误；播放云同步待处理/错误/重试，服务器同步状态及冲突保留本地/采用服务器；前台恢复触发节流刷新。 | `PersonalCenterScreen.kt`、`AccountSettingsScreen.kt`、`PlaybackSyncManager.kt` |
| 电视端 | “我的与设置”含个人中心、家庭资料、同步状态、设备接力、Trakt；支持设置搜索；详情区分 Yfuse 个人清单与服务器清单。媒体库增加全部服务器入口及 Unified 子页面。儿童首页使用公共发现拦截。 | `TvSettingsScaffold.kt`、`TvSettingsScreen.kt`、`TvProductSettingsPages.kt`、`TvApp.kt`、`TvLibraryScreens.kt`、`TvDetailScreen.kt` |

电视子页面首个返回行持有初始焦点，通用按钮保留键盘/方向键激活与焦点提示；媒体库入口也有独立焦点标识。实际遥控器遍历仍需设备验收。

## 与其他实现的接线

- 主代理负责 DI、我的首页、主导航、切换资料时清空返回栈/搜索/旧播放器，以及统一播放入口权限检查。中央策略为 `personal.policy`、`canAccessServer(serverId)`、`requireServerManagement()`、`scopeToken`。
- 页面入口为 `PersonalCenterScreen(personal, account, playbackSync, serverSync, servers, onBack, onOpenMedia, initialTab, repo)`；`initialTab` 支持 `WatchLater`、`Favorites`、`History`、`Profiles`、`Sync`。
- 收藏/想看：`setFavorite(ref, value)`、`setWatchLater(ref, value)`；服务器媒体与发现媒体可用 `MediaItem.toPersonalMediaRef(serverId)`、`TmdbItem.toPersonalMediaRef()` 转换。
- 播放器负责携带真实标题、类型、年份、提供方 ID 和固定的播放开始 scope token 调用 `recordHistory`；个人库不再用 `tmdb:编号` 伪造显示标题。
- Trakt 使用 `importWatchLater`/`importWatched`：本地已存在的选择和删除优先。设备接力与 Trakt 页面/协议由对应代理实现，TV 页面调用其真实服务。

## 测试与验证状态

本代理未运行 Gradle；统一编译与单测由主代理执行，最终结果以总报告为准。

- `PersonalLibraryRepositoryTest` 新增 10 项测试：双向合并与删除、账号/资料持久隔离、PIN/儿童权限、旧异步回包与通知阻断、播放实体与上传确认的资料归属、上传期间待同步及外部导入、服务器本地 ID 碰撞/无效版本、共享服务器进度隔离、后台队列待所属资料恢复、容量耗尽时保留原数据并提示。
- `PersonalCloudSyncTest` 验证两个设备实际加密 HTTP 同步、一次 409 重试、清单合并/删除传播，以及个人同步保留服务器配置；请求密文不包含作品标题或媒体 ID。
- `TvSettingsSearchTest` 增加个人功能关键词可达性回归。
- 本代理最终独立 ktlint 对全部 28 个持有文件检查通过（exit 0、无 baseline，含最后的队列与容量保护改动）。主代理首轮编译发现 `uploadNow` 的 `Result<Unit?>` 已通过显式 `Unit` 修正。第二轮手机主代码/测试编译通过；已修复旧 key-wrap 测试路径不应在未注入个人库时解密，以及新队列测试缺失 serverId 的问题。最终完整 Android/TV 编译和测试结果待统一回归，不以静态检查代替。

独立 ktlint 命令示例（不运行 Gradle）：

```powershell
& 'audit/diagnostics-20260909/fix/ktlint.ps1' 'composeApp/src/commonMain/kotlin/com/yfuse/core/personal/PersonalLibraryRepository.kt' 'tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvProductSettingsPages.kt'
```

需要格式化时增加 `-Format`；同样传入明确文件，不运行全仓格式化。

## 范围及边界

- 两个资料若关联同一个媒体服务器用户，该服务器自身的已看/进度仍天然共享；Yfuse 本地历史、个人清单及云播放实体隔离，不能据此声称服务器用户权限也已隔离。儿童内容权限由关联的媒体服务器用户继续限定，公共 TMDB 发现入口由主导航/TV guard 限制。
- 个人同步目前为明确按钮触发；播放进度保留原自动队列与前台恢复。个人库内容在设备本地设置中持久化，云传输使用账号端到端加密；不宣称本地作品元数据也使用单独数据库加密。
- 服务器清单批量导入当前支持 Emby/Jellyfin，保留每项个人选择；Plex 清单批量导入未新增。一次每种列表最多处理 500 条，触及上限会显示说明并保留已导入记录。
- 沿用现有云快照的体积上限。个人模型有 24 个资料记录、1,000 条条目记录及 1,000 条追剧记录的校验上限，删除记录包含在内。容量耗尽时按钮、播放历史记录和追剧写入保留原快照并设置 `state.error`，云合并超限明确失败而不覆盖原数据；更大长期库需要后续分页/墓碑压缩协议，当前不能宣称无限容量。
- 真实手机/电视焦点、PIN 对话框、跨设备操作与账号服务端联调仍须由统一验收完成；本轮没有下载安装新包，也没有调用播放。

## 补充：Plex Home 身份与儿童权限修复

集成审查发现 PMS 根节点的 `myPlexUsername` 是服务器所有者标签，不能用于区分当前 Home 用户。现在云连接读取活动账号 Token 对应的 `/api/v2/user` 主体；Home 切换还核对返回用户和新 Token 的身份，缺失或不匹配时拒绝连接。`PlexMediaServerAdapter` 使用验证后的用户 ID，因而同一 PMS 上孩子与家长会得到不同的保存 ID；替换为家长凭据后，原来关联孩子保存 ID 的家庭资料不会继承家长权限。

手动 Token 连接仍只访问原 PMS，不强制依赖 plex.tv 在线。无法取得云主体时使用带用途前缀的 SHA-256 Token 指纹作为隔离身份，ID 不包含 Token 明文；更换 Token 会得到新保存 ID，需要重新关联家庭资料。

新增 `PlexIdentityIsolationTest` 的 4 项 MockEngine 行为测试，覆盖相同 PMS 所有者名称下的孩子/家长替换隔离、缺失/错误 Home 身份、初始云连接身份缺失，以及手动 Token 局域网兼容；同时更新两份现有 Plex 认证测试。相关 6 个 Kotlin 文件独立 ktlint 格式化通过，针对性 Gradle 回归由主代理运行；本代理未联网或播放。
