# SenPlayer 与 Yfuse：全模块静态对比与改进记录

分析日期：2026-11-04 UTC。Yfuse 基线：`ebe4e9c22dc2ca6ed6c2b90a3a864c59d1c069fc`，版本配置 `1.8.5 / 337`。实现分支：`improve/senplayer-full-audit-20261105`。

## 结论与范围

Yfuse 不需要重写播放器或照搬 SenPlayer。现有多内核、播放会话、双字幕、进度投影、插件沙箱和 Trakt 检查点已有实质实现。更直接的改进点是：明确区分主动拖动和内核异常回退、持久保存本地字幕、隔离过期异步结果、保留不完整扫描前的片库、补齐文件浏览工具，以及把下载和后台刷新的失败结果交代清楚。本分支落实了这些改进，并处理低磁盘空间下的可选视频缓存写入。

本次覆盖播放输入到输出、媒体管理、搜索、文件源、云盘、下载、字幕、历史同步、首页、插件、直播、投屏、设置、安全和交付验证。这里的“全模块”指可识别业务模块的静态对比，不表示已恢复 SenPlayer 原始工程，或逐条验证所有 native 指令的运行行为。

### 样本与证据等级

- SenPlayer：`com.wuziqi.SenPlayer`，`1.0.0 / build 8`，minSDK 28、targetSDK 36，ARM64。
- APK：87,430,821 字节；SHA-256：`45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c`。
- 反编译覆盖：7,183 个 DEX 类、33,039 个方法；6,546 个 Java 输出文件；11,074 条解密字符串；9 个 native 库。Compose 跟踪字符串可映射 120 个源码文件名。
- JADX 有 207 个错误事件，95 个文件包含 119 处错误标记。原始 DEX、Dalvik、错误清单和 native 分析保留在反编译包中，不能把 Java 输出当成可直接重建的源码。
- native 线性 ARM64 反汇编约 1,016 万条记录；`libFFmpegKit.so` 和 `libMLibKit.so` 承载大量核心逻辑。线性反汇编、导出符号、字符串不等于恢复算法、控制流或测试过的功能。
- **A：可读调用/模型/条件分支**；**B：界面、资源或命令桥接证据**；**C：native 符号、字符串、表结构线索**。B/C 不能证明端到端可用性、性能优于 Yfuse 或某项协议完全兼容。
- Yfuse 结论来自本次读取的基线代码和测试；“新增”只指本分支实际改动。未进行 SenPlayer 与 Yfuse 的同机功耗、画质、首帧和丢帧对测。

## 全模块对比

| 模块 | SenPlayer 证据与边界 | Yfuse 已有实现 | 结论、本分支及后续 |
|---|---|---|---|
| 1. 工程分层与内核 | A：`com.mplaykit.Player` 经 `N.n0…n31` 进入 native；`com.mlibkit.Native` 提供业务模块命令桥。C：FFmpeg/mpv/libass/dav1d 与 Rust/C++ 业务代码 | ExoPlayer、libmpv、MDK、YCore；能力检测、故障分类与回退路由 | 保留现有架构。不能从 native 库名称推出其自研了解码器，也不把闭源实现移植进 Yfuse |
| 2. 网络读取、Range 与身份 | C：read-ahead、sourceRenewal、磁盘缓存相关线索 | HTTP Range、内容长度/强 ETag 验证、缓存身份隔离、表示版本失效 | Yfuse 已有关键正确性机制。后续补真实链路诊断和跨内核一致的配置解释 |
| 3. 缓冲与缓存进度 | A：`Playback$Progress.cacheRanges`、`cz1` 的 `cacheProgress`、`mo3` 序列化证明多段区间模型 | `VideoEngine` / `YPlayerState` 以单个缓冲终点为主；内存前后缓冲与块缓存分别存在 | 明确缺口：多段区间还未实现。不能用单个终点伪造完整区间；须扩充内核接口、seekable-ranges 采集与 UI |
| 4. 磁盘压力与写入 | C：`Diskcacheio-opt-v7`、异步批量与回退线索，未验证吞吐 | 有界异步写队列、CRC、stride、强身份校验 | **新增**：写线程检查磁盘余量；写失败暂停接纳 30 秒，读取仍可继续；恢复后重试。未统一 Media3 与 YCore 的总磁盘预算 |
| 5. 拖动、时间线与手势 | A/B：播放进度、手势与预览调用路径 | 手势 seek、长按倍速、时间线防回零、缓冲态维持 | **修复**：同一播放器的 seekRevision 变化被识别为主动跳转，向后拖动不再被防回退逻辑顶回旧位置；更换播放器不误判为 seek |
| 6. 弱网与超时 | C：缓冲、重试、heartbeat 相关线索，不足以证明无限等待策略 | mpv 启动看门狗、运行期缓冲故障检测、内核回退 | 尚需基于真实累计输入字节而非日志心跳区分慢速与卡死。不能简单去掉全部硬超时；原有策略本分支未修改 |
| 7. 首帧、预热与连播 | A：`sh3.activatePreload` 包含所有权、有效期和激活检查 | PreparedPlaybackRegistry 单次领取、首帧闸门、下一集预热、网络/电池/内存条件 | 已有同类设计，保留。下一步做前后台切换、取消预热、连续切集的真机压力测试 |
| 8. 拖动缩略图 | A：`Player.A(double)` → `N.n16`，future/RGBA 预览；`op3/do3` UI 调用 | 服务器 storyboard/trickplay；无 storyboard 时不显示预览 | 明确缺口：本地/直链通用抽帧预览。需要独立解码会话、有界队列及取消，不能拖慢正在播放的解码器 |
| 9. 音视频输出、HDR/DV | C：FP16/scRGB/PQ、Dolby 等线索；默认 FP16 关闭不能解释成画质差 | 解码路由、DV 配置选择、RPU/FEL 等运行证据门控；MDK/mpv/平台输出 | 无实测不做画质优劣结论。保留能力门控；后续同片源、同显示设备验证色彩、音画同步、直通与功耗 |
| 10. 字幕渲染与导入 | A/B：字幕设置、双层显示、字体/样式/偏移；native libass 路径 | 双字幕、轨道和语言选择、样式/偏移、真实格式解析、SAF 导入 | **新增**：已验证文本存私有副本，按服务器/影片/版本隔离，重进自动加载；去重、单片 8 条、单文件 8 MiB、总 128 MiB；支持清除当前影片副本 |
| 11. 在线字幕与 AI | B：线上字幕、AI 翻译界面；`pj3` 有会话条件；资源明确存在 native AI 未集成提示 | 服务器字幕搜索/下载及本地解析；独立第三方字幕供应商尚非统一体系 | 后续供应商适配、语言/集数/片长/版本匹配、下载后热应用。AI 翻译仅列可选项，不称 SenPlayer 已有成熟离线翻译 |
| 12. 搜索与结果隔离 | A：`pt3` 区分媒体/网盘搜索，传 sources/globalSearch/private/unlocked；`k10` 错误状态 | 多媒体服务器并发、分页、筛选、部分结果和失败源重试 | **修复**：输入变化立即废弃旧请求；分页、类型切换、人物、筛选项拒绝过期结果。后续纳入 NAS/云盘的统一搜索，当前未实现 |
| 13. 媒体首页和详情 | B：`aj2/gs5/ut2/yc0` 包含继续观看、下一集、版本、演员、类似内容和显示设置 | 缓存内容先显示、LatestWins 详情加载、播放优先级、可选区块隔离 | 这些基础行为已存在，不再次重构。后续以详情返回、横屏/平板、预测返回的仪器化测试验证观感 |
| 14. 文件浏览与操作 | A/B：`wk2` 的 FilesScreen 包含查找、名称/时间/大小/类型排序、文件夹优先、布局、批量操作与源身份检查 | WebDAV/SMB 文件夹浏览、播放队列、字幕配对 | **新增**：当前目录多关键词搜索、名称/修改时间/大小排序、升降序、文件夹优先、未知值排末。只改变展示，保持剧集自然顺序及字幕配对。重命名/移动/批量操作暂未加入 |
| 15. 文件源与云盘 | B：`os4` 可见 Emby/Jellyfin/Plex、飞牛/绿联、WebDAV/FTP/SMB 与多家云盘条目及重新授权入口 | 媒体服务器 + WebDAV/SMB；Alist/OpenList 走 WebDAV；凭据独立保存 | 原生云盘/FTP 是覆盖差异。条目存在不证明全部平台有效；应按一个供应商的登录、续期、限速、下载完整闭环推进 |
| 16. NAS 扫描与刮削 | C：jobs/directories/scan_members/checkpoint 等表与任务词汇；B：刮削入口 | 深度/目录/文件/匹配上限、限流并发、复用旧 TMDB 识别 | **修复**：部分目录失败或达上限保留未读旧文件；完整扫描可删除已消失文件。取消重启扫描不清除新任务；源连接改变使旧结果失效。持久断点、按目录检查点仍待做 |
| 17. 下载与批量任务 | A：DownloadClient 的 taskAction/taskViews/sourceRenewal/failurePolicy；B：ex1 的准备、重试、暂停、完成等状态 | Range/ETag/416、重试、源解析、暂停/恢复和稳定排序 | **改进**：批量命令去重、逐项继续，统一报告成功处理/失败数量。计数是命令处理结果，不是文件下载完成数；精确失败项选择重试仍待做 |
| 18. 进度、历史和收藏 | B：`e04/s` 有媒体/文件历史、收藏、书签、按来源过滤；C：字段时钟/同步词汇 | 本地权威进度投影、会话/用户操作/时间冲突策略，支持合理向后跳转 | 保留策略，不采用“永远取最大进度”。后续覆盖播放页→详情→历史→服务端多设备回放测试 |
| 19. Trakt 与账户同步 | A/B：`qr4/vw3/AccountClient` 的授权、账户状态、同步选项；C：outbox/schema | 双向同步、加密分块检查点、最多 1000 项、写完分块才发布头指针 | 无证据说明 SenPlayer 的冲突处理优于 Yfuse。后续补账号切换、撤销授权、断网恢复的端到端测试 |
| 20. 首页组件与模块 | B：`u9/qy2/xa2` 的首页、启动模块、标签启用/禁用 | 多种自定义布局、持久 feed 缓存、已有内容保留、前台可见时刷新 | **改进**：过期后台刷新失败保留内容，30/60/120 秒退避，至多再试 3 次；废弃临时 feed 停止任务。实际触发受页面可见/恢复和 60 秒巡检限制 |
| 21. 插件与脚本 | A：Native.callModule/openScript 与 ScriptStore 桥接；核心调度在 native | QuickJS 内存/栈/时间/请求数限制；Stremio catalog/meta/stream/subtitles；不暴露凭据 | 继续强化现有能力，不照搬 SenPlayer native 命令。后续明确插件权限和兼容协议、更新/回滚和失败诊断 |
| 22. 直播与 IPTV | A/B：`xu4/lx3` 的订阅源、本地/在线 M3U、更新、排序、频道数，操作有源版本隔离 | 能播放相关直播直链，已有直播组件；不等同完整订阅管理 | 缺订阅仓库、分组、EPG、源刷新及坏频道诊断。独立里程碑实现，不把直接播放 m3u8 算作功能齐全 |
| 23. 投屏、外部播放与 Wi-Fi 导入 | A/B：播放/设备操作与 `WebUploader` native 桥、配套 Web 资源；部分反编译方法有错误 | Google Cast、DLNA、鉴权媒体中继、会话隔离；外部播放链路 | 投屏需要多设备测试才能比较兼容性；Wi-Fi 文件上传可列低优先级，须约束局域网会话和文件生命周期 |
| 24. 片头片尾、弹幕和设置 | B：播放、手势、OSD、弹幕、列表、外观等设置入口 | TheOtherDB + 用户自定义优先级、弹幕筛选/来源/时间线；设置搜索与导航、降低动画/透明度、48dp 触控规范 | 保留已有用户自定义逻辑。文件浏览新入口复用现有组件；不为对齐功能数量增加一批无实际收益的设置 |
| 25. 安全、持久化和可观测性 | A：AccountClient 使用 AndroidKeyStore/加密/AtomicFile；C：业务数据 schema | SecureStore、凭据分离、缓存身份哈希、日志类别和诊断导出 | 本地字幕副本仅在私有目录，文件名不包含媒体 ID。后续凭据脱敏、HTTP 流量/缓存/解码阶段统一诊断仍应持续审查 |
| 26. 性能、包体与发布 | APK 的大小/ABI/库清单可确定；没有同设备 benchmark | 手机/TV 共享模块、质量门禁、R8/DEX 校验、签名和 full MDK 交付规则 | 不直接拿两个不同构建的 APK 大小评价工程质量。当前提交做编译、回归及边界校验；真机首帧、HDR、功耗和交互仍需专项验证 |

## 本分支改动与复现用例

| 改动 | 原问题触发 | 修改后行为 | 验证位置 |
|---|---|---|---|
| 主动 seek 时间线 | 从 5 分钟向后拖到 2 分钟并进入缓冲 | UI 接受主动跳转；未知时长保留可信总时长；新播放器不会误触发 | `PlaybackSeekTimelineTest` + 既有时间线用例 |
| 本地字幕保存 | SAF 导入成功，关闭页面/重启后再次打开同一版本 | 从私有副本恢复；其他服务器和版本互不污染；可清除 | `ImportedSubtitleStoreTest` + `AndroidExternalSubtitleSessionTest` |
| 搜索过期结果 | 输入新词、同词切换类型，旧分页/筛选回调晚到 | 旧结果不追加、不覆盖状态、不污染新请求 | `SearchStoreTest` |
| 部分扫描与所有权 | 子目录超时；取消扫描马上重启；修改源地址 | 保留未读片库；旧任务不能清除新进度或发布旧连接结果 | `FileSourceLibraryTest`、`FileSourceScanOwnershipTest`、`FileSourcesControllerTest` |
| 文件搜索排序 | 文件名含多关键词、集数 2/10、未知大小/时间 | 文件夹优先、自然排序、多词匹配；未知数据最后 | `FolderBrowsePolicyTest` |
| 批量下载反馈 | 部分命令成功、部分抛错、列表含重复项 | 每个唯一项尝试一次；后续项继续；一次汇总数量 | `OfflineCommandQueueTest` |
| 首页后台恢复 | 已有内容过期后刷新失败，或临时预览超出缓存上限 | 保留内容并有限重试；临时任务淘汰后取消 | `WidgetFeedRefreshTest`、`WidgetRepositoryTest` |
| 磁盘低空间 | 可选缓存写入无剩余空间，随后空间恢复 | 暂缓写入；旧缓存可读；冷却结束恢复接纳 | `AndroidYCoreBlockCacheTest` |

字幕保存使用真实解析器校验后再写入，原始文件不会被删除。批量下载反馈中的“成功处理”不表示下载已完成。部分扫描会保守保留可能已经删除的旧条目，直到一次完整扫描确认；这是防止短暂离线清空片库的明确取舍。

## 后续开发顺序与验收条件

这些项尚未在本分支完成。它们涉及内核 ABI、第三方协议或真机输出，不能用改 UI 的方式宣称完成。

| 优先级 / 工作包 | 具体方案 | 依赖与验收 |
|---|---|---|
| P0 / 真实缓存区间 | core2 增加带来源/可信度的区间模型；mpv 暴露结构化 demuxer-cache-state；Exo/YCore 逐个适配；UI 画多个不相连区间 | 当前 MPVLib AAR 只有基础 getter，先明确 node/子属性桥接。测试区间合并、断档、反向 seek、表示版本失效；不虚报可立即播放范围 |
| P0 / 弱网与超时统一 | 分别记录连接、有效输入字节、解封装输出、首帧、重缓冲；有实际进展时延后软超时，无进展保留截止与用户取消 | 限速 32/64/128 KiB/s、断流、连接成功无字节、不断日志无有效数据；验证能慢慢缓冲，也不会永久挂起 |
| P0 / 缓存预算协调 | 汇总 Media3 与 YCore 实际使用量；共享预算/保留空间；后台清理与正在使用文件的租约 | 两内核交替播放、满盘、进程重启；总量不因每套缓存各自拿满预算而翻倍 |
| P1 / 本地拖动预览 | 独立抽帧工作器、最新请求优先、采样/尺寸限制、短期内存缓存；优先复用服务器 storyboard | 快速连续拖动不回显旧缩略图，不阻塞前台播放；取消后释放解码器；测峰值内存 |
| P1 / 统一搜索 | 统一结果身份和来源接口，分别适配媒体服务器、NAS 索引、云盘、插件；保留权限、部分成功、单源重试 | 相同名字不误合并不同影片；账号/源切换后的旧结果丢弃；低效源不拖住全部结果 |
| P1 / NAS 增量扫描 | 持久任务、目录检查点、取消/恢复、错误目录列表；路径/版本变化参与身份；扫描完成后再确认删除 | 中断/重启不从头重扫；仅完整成功目录允许确认删除；重复扫描幂等；给出进度与错误原因 |
| P1 / 字幕供应商 | 供应商接口 + 影视匹配评分 + 编码/格式校验 + 安全下载 + 已下载列表；复用当前持久副本 | 中文搜索、剧集/版本匹配、错误格式、重复下载、过期授权；热切换不重置播放进度 |
| P1 / 下载任务闭环 | 结构化记录每个失败项及原因；仅重试失败项；统一排队/暂停/重试/校验展示 | 批量部分成功后不用重选成功项；进程重启恢复；HLS 与普通 HTTP 分别验证 |
| P1 / 播放与进度端到端 | 复用现有冲突策略，增加页面及双端事件序列测试 | 暂停/退出/拖回/重连后详情、历史、继续观看一致；用户主动回退不会被云端旧值覆盖 |
| P1 / 输出与性能矩阵 | 同设备比较 H.264/HEVC/AV1、HDR10/DV、字幕、音频输出、前后台与旋转 | 记录首帧 P50/P95、卡顿时间、内存、温度/耗电；硬解成功不等于 HDR/DV 正确呈现 |
| P2 / IPTV 订阅 | M3U 订阅仓库、频道分组、刷新差异、EPG、收藏、坏源诊断；播放复用既有内核 | 订阅刷新不丢收藏；失败不清空旧频道；有时移能力才显示时移入口 |
| P2 / 原生云盘与文件操作 | 选择一个供应商打通授权/续期/列目录/播放/下载，再扩展；文件操作使用能力声明与源版本校验 | 鉴权过期可恢复；不对只读源显示移动/删除；目录变化后旧菜单不能操作错误对象 |
| P2 / 插件管理和可选 AI | 插件权限/超时/版本回滚；AI 翻译独立开关、队列和成本提示 | 插件崩溃不拖垮播放；无需上传用户凭据；翻译失败保留原字幕；离线能力单独验证 |

## 证据导航

SenPlayer 的以下路径相对于已交付反编译包的 `sources_readable/`，不是 Yfuse 源码：

| 证据入口 | 可验证内容 |
|---|---|
| `com/mplaykit/Player.java`、`Playback$Progress.java`、`N.java` | JNI 入口、进度模型和预览桥接 |
| `defpackage/cz1.java`、`mo3.java`、`op3.java`、`do3.java`、`sh3.java` | 区间序列化、预览 UI、预热激活 |
| `com/mlibkit/Native.java`、`defpackage/wk2.java` | 业务命令封装、文件管理 UI 与操作条件 |
| `defpackage/pt3.java`、`k10.java`、`os4.java` | 搜索来源/过滤、错误、源类型和 sourceRevision |
| `defpackage/ut2.java`、`gs5.java`、`aj2.java`、`yc0.java` | 媒体首页、详情、配置及可选内容 |
| `com/mlibkit/download/DownloadClient.java`、`defpackage/ex1.java` | 下载命令接口及状态显示 |
| `defpackage/e04.java`、`s.java`、`qr4.java`、`vw3.java` | 历史/收藏、Trakt 授权及设置 |
| `defpackage/u9.java`、`qy2.java`、`xa2.java`、`xu4.java`、`lx3.java` | 模块导航、首页、IPTV 订阅 |
| `com/mlibkit/account/AccountClient.java`、`com/mlibkit/gcd/WebUploader.java`、`defpackage/pj3.java` | 账户持久化、上传桥、翻译会话 |
| `native_analysis/`、`buffer_evidence.tsv`、`output_evidence.tsv`、`library_evidence.tsv` | native 反汇编与线索；须保持 C 级证据边界 |

Yfuse 核对入口：

- 播放策略：[PlaybackBufferPolicy.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/playback/PlaybackBufferPolicy.kt)、[PlaybackRuntimeFaultDetector.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/playback/PlaybackRuntimeFaultDetector.kt)、[MpvFileLoadWatchdogPolicy.kt](../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/MpvFileLoadWatchdogPolicy.kt)。
- 时间线：[PlaybackTimelineKeeper.kt](../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackTimelineKeeper.kt)、[PlaybackRuntimeContent.kt](../composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlaybackRuntimeContent.kt)。
- 视频缓存：[AndroidYCoreBlockCache.kt](../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidYCoreBlockCache.kt)；预览：[PlayerRootTrickplay.kt](../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRootTrickplay.kt)。
- 字幕：[ImportedSubtitleStore.kt](../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ImportedSubtitleStore.kt)、[PlayerSubtitleLibrary.android.kt](../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerSubtitleLibrary.android.kt)。
- 搜索与文件：[SearchStore.kt](../composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchStore.kt)、[FileSourceScanner.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/filesource/FileSourceScanner.kt)、[FolderBrowsePolicy.kt](../composeApp/src/commonMain/kotlin/com/yfuse/feature/filesource/FolderBrowsePolicy.kt)。
- 下载与首页：[OfflineCommandQueue.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineCommandQueue.kt)、[WidgetFeed.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/widget/WidgetFeed.kt)、[WidgetRepository.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/widget/WidgetRepository.kt)。
- 插件与同步：[SandboxWidgetRuntime.kt](../composeApp/src/androidMain/kotlin/com/yfuse/core/widget/SandboxWidgetRuntime.kt)、[StremioRepository.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/stremio/StremioRepository.kt)、[PlaybackProgressProjection.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackProgressProjection.kt)、[TraktSyncCheckpoint.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/trakt/TraktSyncCheckpoint.kt)。
- 输出、投屏与自动跳过：[DolbyVisionEnhancementValidation.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/playback/DolbyVisionEnhancementValidation.kt)、[CastManager.android.kt](../composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastManager.android.kt)、[TheOtherDbRepository.kt](../composeApp/src/commonMain/kotlin/com/yfuse/core/data/TheOtherDbRepository.kt)。

## 验证记录

| 检查 | 实际结果 |
|---|---|
| `:phoneShared:compileAndroidMain` | 通过；包含手机业务代码与 Android 入口 |
| `:tvShared:compileAndroidMain` | 通过；包含共享业务代码及 TV Android 入口 |
| `:phoneShared:testAndroidHostTest` 定向回归 | **134 项通过，0 失败、0 错误、0 跳过，16 个测试类**；包含上述新增/修改测试及既有时间线、字幕会话和取消用例 |
| ktlint 1.3.1 | 32 个改动 Kotlin 文件通过，未新增规则豁免 |
| 设计系统、core 分层、源码体积、ServiceLocator 门禁 | `verifyDesignSystemUsage`、`verifyCoreLayering`、`verifySourceSize`、`verifyServiceLocator` 全部通过 |
| Python 边界与函数规模 | `verify-module-boundaries.py`、`check-backend-boundary.py`、`check_function_size.py` 通过 |
| 补丁与报告引用 | `git diff --check` 通过；报告中的 Yfuse 源码链接均存在 |

本地 Kotlin 验证使用 JDK 17、Gradle 9.8.2、Android 37.0，显式保留 full 配置 `yfuseNativeOnlyRuntime=false / yfuseIncludeMdk=true`，清理模块输出并关闭增量编译以排除本地编译缓存的重复符号问题。没有放宽源代码、测试或原生库校验门禁。

完整应用编译任务目前被本地缺少 `ycore-native.aar` 及其来源/校验文件阻止；尚未进行完整原生重建、R8/DEX/签名验证或设备测试。PR 使用仓库现有质量流水线继续这些检查，流水线实际状态以该提交的 GitHub Actions 为准。真机首帧、画质、音频设备兼容性及功耗不在本次已通过项内；此分支没有交付签名安装包。
