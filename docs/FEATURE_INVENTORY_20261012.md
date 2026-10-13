# Yfuse 功能清单和取舍建议

Yfuse 的主要价值是让用户在手机和电视上连接自己的媒体来源，快速找到影片并稳定观看，同时保留进度。应优先保障连接、浏览、播放、续播和数据安全，再维护追剧、离线和跨设备体验。外观细节和实验性平台适合控制投入。

本清单以 2026 年 10 月 12 日的源码为范围，覆盖 Android 手机、Android TV、文件来源、Cast 接收端、项目后端、鸿蒙移植和工程保障。按用户任务归并功能；同一功能的多个按钮、实现类和测试不重复计数。源码基线为 `b172c7e9b1dad94871166b7545a94f9b9bf08f38`，另包含本次日历导出修复。

## 判断标准

| 标记 | 含义 |
| --- | --- |
| P0 | 核心体验或数据安全。失败会阻止看视频、丢失数据或破坏访问边界。 |
| P1 | 对明确用户场景有较高价值，应持续维护。 |
| P2 | 有价值的辅助能力，适合放在二级页面或默认关闭。 |
| P3 | 投入与当前确定收益不匹配，建议暂停扩展。 |
| 必须 | 产品基本闭环不可缺少，或已有明确交付要求。 |
| 场景必要 | 对相应用户必要，对所有用户不必强制启用。 |
| 可选 | 移除或简化通常不影响核心闭环，先评估实际使用情况。 |
| 暂缓 | 保留研究代码或受限入口，不应作为完成的主要卖点。 |

“已接入”表示当前源码有实际业务调用，不等于所有设备、媒体格式和第三方服务都经过实机验收。取舍基于私人媒体库、家庭观看和手机电视协同的产品定位；没有用估计值冒充真实使用频率。P0 到 P3 是功能维护优先级，不是缺陷等级。

## 分类结果

共 14 组、142 项功能。重要性和必要性分别评估，不能把低频安全能力直接视为不必要。

| 重要性 | 数量 | 建议 |
| --- | --- | --- |
| P0 | 21 | 优先保障可用性、数据和安全边界。 |
| P1 | 76 | 持续维护，按目标人群开放入口。 |
| P2 | 40 | 维持轻量实现，减少独立开关和重复页面。 |
| P3 | 5 | 暂停扩展或保留受限研究范围。 |

必要性分类为必须 42 项、场景必要 71 项、可选 26 项、暂缓 3 项。场景必要不表示可以忽略：例如儿童资料访问控制，一旦启用，其可靠性仍是 P0。

## 连接媒体来源

依据：[媒体服务器适配接口](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/MediaServerAdapter.kt)、[服务器管理](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServersTabComponent.kt)、[文件来源控制器](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/filesource/FileSourcesController.kt)、[文件来源协议定义](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/filesource/FileSource.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| A01 | Emby 登录、鉴权、媒体和播放接口 | P0 必须 | 已接入 | 主要媒体入口，应优先保持兼容。 |
| A02 | Jellyfin 登录、鉴权及专属能力适配 | P0 必须 | 已接入 | 主要开源媒体入口，不能只按 Emby 接口假定兼容。 |
| A03 | Plex 连接、令牌及 PIN 授权 | P1 场景必要 | 已接入 | 对 Plex 用户必要；三个服务端的差异需单独维护。 |
| A04 | 多服务器、多账户、默认服务器、重命名和移除 | P0 必须 | 已接入 | 支撑个人 NAS、家庭和多来源观看。 |
| A05 | Quick Connect 等免键盘登录流程 | P1 场景必要 | 依服务端支持 | TV 输入困难时价值较高。 |
| A06 | 局域网服务器发现 | P2 可选 | 已接入 | 减少首次配置步骤；手动输入地址仍应完整可用。 |
| A07 | 内外网多线路、线路切换和失败恢复 | P1 场景必要 | 已接入 | 常在家庭 Wi-Fi 与外网之间切换的用户需要。 |
| A08 | 服务器在线状态、延迟、数量统计和排序筛选 | P2 可选 | 已接入 | 状态与错误提示有用；复杂统计展示可简化。 |
| A09 | 服务器配置加密备份、恢复和换机迁移 | P1 场景必要 | 已接入 | 多账户配置成本高，凭据必须安全保存。 |
| A10 | SMB 文件共享 | P1 场景必要 | 手机有文件来源入口 | 直接访问 NAS，适合没有媒体服务的用户。 |
| A11 | WebDAV 文件来源 | P1 场景必要 | 手机有文件来源入口 | 扩大 NAS 和远程文件覆盖。 |
| A12 | Alist 或 OpenList 文件来源 | P1 场景必要 | 通过 WebDAV 接入 | 复用 `/dav`，不是直接集成每家网盘 API。 |
| A13 | 文件夹浏览、按文件夹连播、同名外挂字幕配对 | P1 场景必要 | 文件来源已接入 | 直接文件观看的基础闭环。 |
| A14 | 文件来源扫描、片名识别和 TMDB 刮削片库 | P2 可选 | 已接入 | 让文件更易浏览，错误匹配应允许纠正；依赖外部元数据。 |
| A15 | 刷新媒体库、运行服务器任务、切换 Plex 家庭用户 | P2 场景必要 | 按服务端能力和权限开放 | 属于管理员工具，不应占普通观看的主要入口。 |
| A16 | 自定义 User-Agent 和 HTTPS 连接诊断 | P2 场景必要 | 已接入 | 解决特定反代和媒体服务兼容问题，保留在高级设置。 |

## 找片和浏览

依据：[首页](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/home/HomeComponent.kt)、[搜索状态和操作](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchStore.kt)、[媒体库](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/library/LibraryGridComponent.kt)、[智能片单](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/SmartPlaylistStore.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| B01 | 媒体库继续观看、下一集和最近入库 | P0 必须 | 已接入；2026-10-13 从独立首页合并到库 | 最短观看路径，优先于库轮播和推荐展示。 |
| B02 | 按媒体库浏览影片、剧集和其他视频 | P0 必须 | 已接入 | 核心找片流程。 |
| B03 | 分页、排序、年份、风格、标签和已看筛选 | P1 必须 | 已接入；条件依页面和服务端不同 | 大型媒体库需要可控检索。 |
| B04 | 收藏夹、服务端合集和播放列表 | P1 场景必要 | 依服务端能力开放 | 保留已有整理成果，不必为所有服务端伪造一致权限。 |
| B05 | 统一媒体库和跨服务器内容聚合 | P1 场景必要 | 已接入 | 多来源用户收益明显，单服务器用户可不展示。 |
| B06 | 全局搜索、多服务器并行查询及失败提示 | P0 必须 | 已接入 | 找片入口，部分服务器失败不能掩盖成功结果。 |
| B07 | 搜索按服务器、媒体库、类型、年份、风格和观看状态筛选 | P1 必须 | 已接入 | 提升定位效率，筛选项目无需重复到每个页面。 |
| B08 | 演员搜索和按人物找作品 | P2 可选 | 已接入 | 发现用途明确，但不影响基本播放。 |
| B09 | 搜索历史、搜索结果密度和简介显示 | P2 可选 | 已接入 | 历史便利，显示开关宜保持少而清楚。 |
| B10 | 保存智能片单并固定到媒体库 | P1 场景必要 | 已接入；上限 40 条；原首页海报栏迁移到库 | 经常重复筛选的用户需要；复杂规则扩展可延后。 |
| B11 | TMDB 热门推荐、国内外内容发现和短剧发现 | P2 可选 | 已接入；2026-10-13 移至库顶部发现入口，进入时加载 | 私人媒体客户端优先展示可播放内容，控制外部请求成本。 |
| B12 | 库及推荐栏目排序隐藏、卡片密度、网格缩放和快速索引 | P2 可选 | 已接入；库与推荐的栏目调整分别保留设置 | 大库用户有收益，复杂手势应保留直观替代入口。 |

## 详情和媒体整理

依据：[详情组件](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailComponent.kt)、[元数据编辑服务](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/MetadataEditorService.kt)、[人物页](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/person/PersonComponent.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| C01 | 海报、简介、评分、年份、时长和媒体技术信息 | P1 必须 | 已接入 | 为是否观看及选版本提供信息。 |
| C02 | 季和集浏览、百集网格、当前集定位和 TV 数字跳集 | P1 必须 | 已接入 | 长剧和短剧共同需要。 |
| C03 | 多服务器片源比较、版本选择和质量优先策略 | P1 场景必要 | 已接入 | 内容有多个副本时价值高，匹配需用可靠身份。 |
| C04 | 单片、整季或此前剧集标记已看及撤销 | P1 必须 | 已接入 | 维护观看状态；批量操作必须避免误操作。 |
| C05 | 元数据刷新和媒体分析 | P2 场景必要 | 按权限开放 | 管理员辅助，可放到更多菜单。 |
| C06 | 编辑标题、简介、TMDB 标识及更换图片 | P2 场景必要 | 三类服务端能力不同 | 修正媒体库很有用，但不必扩展为完整管理后台。 |
| C07 | 演员资料、库内作品和 TMDB 其他作品 | P2 可选 | 已接入 | 完善浏览，不属于观看闭环。 |
| C08 | 本地或外部预告片、电视静音预览 | P2 可选 | 已接入；接收端和外部应用另需验证 | 消耗带宽与焦点时间，应允许关闭自动预览。 |
| C09 | 主题曲和海报卡片分享 | P2 可选 | 已接入；主题曲默认关闭 | 保留轻量入口，优先控制后台音频和外部请求。 |

## 基础播放和控制

依据：[播放器状态](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/contract/VideoEngine.kt)、[播放控件](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt)、[手势配置](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlayerGestureSettings.kt)、[进度同步](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| D01 | 播放暂停、定位、重播和加载失败重试 | P0 必须 | 已接入 | 观看基本操作。 |
| D02 | 本机续播、服务端进度回写和进度冲突处理 | P0 必须 | 已接入 | 数据可靠性与用户信任直接相关。 |
| D03 | 音轨与字幕选择、关闭字幕及轨道记忆 | P0 必须 | 已接入；内核能力不同 | 多音轨与外语媒体的基本可用性。 |
| D04 | 双字幕、字幕样式位置、延迟和简繁转换 | P1 场景必要 | 已接入；按内核支持 | 字幕需求高，但能力必须如实显示。 |
| D05 | 倍速、全局默认速度和按剧记忆 | P1 场景必要 | 已接入；最高 3 倍 | 剧集和短剧用户常用。 |
| D06 | 连播、下一集预热、取消连播和还在看吗 | P1 必须 | 已接入 | 提升连续观看；必须正确尊重停止意图。 |
| D07 | 片头片尾、前情提要、自动跳过及撤销 | P1 场景必要 | 已接入；读取 Jellyfin 片段 | 剧集高价值，标记不准时要允许关闭或调整。 |
| D08 | 竖屏识别、短剧手动覆盖、滑动切集和旋转适配 | P1 场景必要 | 已接入；部分内核显示边界待设备验证 | 手机短剧观看需要，平板电视的布局需分别处理。 |
| D09 | 音量亮度、双击定位、长按变速、缩放和锁屏 | P1 场景必要 | 已接入 | 常用操作便利；过多可调手势宜收进高级设置。 |
| D10 | 键盘快捷键、逐帧和遥控器播放控制 | P1 场景必要 | 已接入 | TV 控制必须保留；逐帧属高级用途。 |
| D11 | 画中画、后台媒体会话和播放通知 | P1 场景必要 | Android 已接入 | 手机多任务需要，后台播放应遵循系统能力。 |
| D12 | 睡眠定时、本集结束停止和时间书签 | P2 可选 | 已接入 | 明确但相对专门的需求，放在更多菜单。 |
| D13 | 外部视频打开、流媒体链接和外部播放器交接 | P1 场景必要 | Android 已接入 | 可拓展片源并为兼容问题提供出口。 |
| D14 | 搜索和下载服务器远端字幕 | P1 场景必要 | Android 已接入；按服务端能力 | 字幕缺失时提供直接补救，不代表应用自带字幕内容。 |

## 高级播放能力

依据：[播放规划](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/playback/PlaybackPlanner.kt)、[高级设置](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt)、[原生架构现状](D:/Demo/Yfuse/docs/YCORE2_ARCHITECTURE.md)、[光盘导航](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/DiscNavigationOverlay.android.kt)、[BD-J 可行性](D:/Demo/Yfuse/docs/BDJ_ANDROID_FEASIBILITY.md)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| E01 | 自动播放路由、设备能力探测和安全降级 | P0 必须 | 已接入 | 格式差异应由系统处理，避免普通用户反复换内核。 |
| E02 | Media3、MPV 和 MDK 兼容播放栈 | P1 必须 | 已接入；MDK 固定包含于标准 full 包 | MDK 是已明确的交付要求，不建议因精简功能移除。 |
| E03 | YCore 原生播放、自有解封装和渲染链路 | P1 场景必要 | 有生产接入；完整矩阵依设备和媒体验证 | 是较高维护成本的能力，重点放在稳定性和兼容证据。 |
| E04 | 内核锁定、硬软解码、均衡省电画质策略 | P2 场景必要 | 已接入 | 支持排障和特殊机型；日常默认自动即可。 |
| E05 | 直放、重封装、服务端转码及权限感知 | P0 必须 | 已接入；转码由媒体服务器承担 | 不能把未授权转码当成可靠降级。 |
| E06 | HLS 和 DASH 自适应、分片恢复 | P1 场景必要 | 已接入；依内核和片源 | 弱网流媒体必要，不能保证所有清单和编码都兼容。 |
| E07 | 前向缓冲、视频缓存、预读取和断网恢复 | P0 必须 | 已接入 | 直接影响起播、卡顿和定位，应优先压低复杂度。 |
| E08 | HDR10、HDR10+、HLG、杜比视界及 SDR 回退 | P1 场景必要 | 有路由实现；输出依设备链路 | 高端媒体用户需要，不能用片源标签代替实际输出。 |
| E09 | 音频直通、Atmos 等输出证据和刷新率匹配 | P1 场景必要 | 按内核和设备能力；MDK 直通设置受限 | 对 TV 与影院设备价值高，实际链路验收不可省略。 |
| E10 | 音量增强、响度均衡和夜间人声 | P2 场景必要 | 按音频处理能力开放 | 夜间观看有用；增强与直通的互斥需要清楚说明。 |
| E11 | ISO、BDMV 和 DVD 正片、标题章节和 HDMV 导航 | P2 场景必要 | 有实现；依内核和原盘能力 | 面向原盘用户，验收成本高；不等于所有交互菜单可用。 |
| E12 | BD-J Java 菜单运行时 | P3 暂缓 | 有契约和后端边界，完整 Android 运行时未交付 | 不是已完成的菜单功能，避免继续堆入口。 |
| E13 | Anime4K 动漫增强 | P2 可选 | 限支持的 SDR 动漫路线 | 适合特定片源，优先验证收益和功耗，不作默认增强。 |
| E14 | 画面边缘氛围光 | P2 可选 | 已接入；依画面采样路线 | 展示性辅助，低性能设备应允许关闭。 |
| E15 | 实验纯原生播放模式 | P3 暂缓 | 有受限模式和校验门禁 | 需完整设备与长稳证据，不得替代标准 MDK full 交付。 |
| E16 | DRM 受保护内容路由和离线许可证生命周期 | P2 场景必要 | 有实现；商业服务和硬件验证未完成 | 仅在确有授权媒体需求时维护，不能声称通用 DRM 兼容。 |

## 弹幕

依据：[弹幕来源设置](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileDialogs.kt)、[弹幕仓库](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/DanmakuRepository.kt)、[播放器弹幕面板](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/player/DanmakuPanel.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| F01 | 添加弹幕服务或模板来源 | P1 场景必要 | 已接入；应用不自带来源 | 弹幕用户必要，普通用户不用先配置。 |
| F02 | 影片集数匹配、手动搜索、绑定和时间偏移 | P1 场景必要 | 已接入 | 错集错时间会破坏体验，优先于装饰性弹幕效果。 |
| F03 | 开关、字体、透明度、密度、速度和区域 | P2 场景必要 | 已接入 | 基础可读性需要；细参数宜合并成少量预设。 |
| F04 | 屏蔽词、合并重复、复制和单条屏蔽 | P1 场景必要 | 已接入 | 避免刷屏与干扰。 |
| F05 | 发送弹幕、失败反馈和保留输入 | P2 可选 | 按来源支持 | 播放弹幕的读取与互动发送可分别开关。 |
| F06 | 弹幕来源和绑定随账号同步 | P2 场景必要 | 在加密同步范围内 | 换机便利，但基础播放不能依赖云端。 |

## 下载和离线

依据：[离线模型](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineMedia.kt)、[下载页](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/DownloadsScreen.kt)、[容量和时段](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineDownloadBudget.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| G01 | 单片、单集和整季下载 | P1 场景必要 | 已接入 | 通勤、旅行和不稳定网络需要。 |
| G02 | 队列、暂停、继续、重试、取消及批量操作 | P1 必须 | 已接入 | 只要提供下载，这些操作就属于基本闭环。 |
| G03 | 断点续传、Range 校验和下载完成检查 | P0 必须 | 已接入 | 防止损坏文件被当作完成。 |
| G04 | 离线媒体库、轨道保存和本地续播 | P1 场景必要 | 已接入 | 下载必须能真正离线观看。 |
| G05 | 容量上限、存储目录、下载时段和网络条件 | P1 场景必要 | Android 已接入 | 防止占满设备和无意消耗流量。 |
| G06 | 追更新集自动下载和已看文件腾挪 | P2 场景必要 | 已接入；跨季覆盖有限 | 对固定追剧用户有用，其他用户默认关闭。 |
| G07 | 后台工作、通知控制和实况进度 | P1 必须 | Android 已接入 | App 离开前台后应保持可理解、可控制。 |
| G08 | 下载索引恢复、旧记录迁移和安全删除 | P0 必须 | 已接入 | 保住已有下载，不能因索引异常误删文件。 |

## 追剧日历和提醒

依据：[日历仓库](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/AiringCalendarRepository.kt)、[官方排期](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/OfficialAiringScheduleCatalog.kt)、[提醒任务](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/calendar/CalendarReminderWorker.kt)、[日历导出](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarIcs.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| H01 | 月历、按天排期、国内外与电影剧集区分 | P1 场景必要 | 已接入 | 追剧用户需要；电影上映是发现，不代表库内可播。 |
| H02 | 已播未入库、已入库、未看、观看中和已看区分 | P1 场景必要 | 已接入 | 日历的实际价值来自与私人媒体库连接。 |
| H03 | 关注取消、排期卡片、刷新和撤销 | P1 场景必要 | 已接入 | 应优先展示用户关注内容。 |
| H04 | 详情绑定正确剧集及严格媒体身份匹配 | P1 必须 | 已接入 | 防止同名剧和没有 TMDB 的短剧被认错。 |
| H05 | 官方签名排期、TMDB 补充和冲突合并 | P1 场景必要 | 客户端和服务端已接入 | 提高可信度，缺失排期不能靠生成假日期补齐。 |
| H06 | 播出提醒、入库提醒和时区转换 | P1 场景必要 | Android 已接入 | 时间准确与避免重复通知比提醒形式更重要。 |
| H07 | 本地缓存、后台刷新和可用性重新核对 | P1 场景必要 | 已接入 | 防止每次打开都慢，也应标明旧数据。 |
| H08 | 分享排期和导出 ICS 到系统日历 | P2 可选 | 已接入；本次修复导出缺陷 | 用系统日历管理的用户需要，但可作为次级入口。 |
| H09 | 后端抓取、OCR、浏览器渲染和排期发布 | P2 场景必要 | 已部署；来源覆盖不等于完整 | 维护成本高，应按实际追剧覆盖收益限制采集范围。 |

## 个人内容和账号

依据：[个人媒体模型](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/personal/PersonalLibraryModels.kt)、[账号设置](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/AccountSettingsScreen.kt)、[加密同步文档](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/CloudSyncSnapshot.kt)、[本机观看统计](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/personal/LocalViewingStore.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| I01 | 本地想看、收藏、历史和追剧列表 | P1 必须 | 已接入；不要求媒体服务器提供同一能力 | 保留个人选择，跨服务器时尤其有用。 |
| I02 | Yfuse 账号注册邀请、登录、刷新和退出 | P1 场景必要 | 云功能使用；本地播放可独立工作 | 不应让查看私人媒体必须先注册项目账号。 |
| I03 | 修改资料密码、会话列表和撤销设备访问 | P1 必须 | 使用账号时已接入 | 账号体系的基本安全闭环。 |
| I04 | 端到端加密云同步、压缩、冲突处理和手动恢复 | P1 场景必要 | 已接入 | 多设备用户需要，维护与故障排查成本较高。 |
| I05 | 个人内容自动合并、进度带入同步 | P1 场景必要 | 已接入；首次云数据和配置有不同流程 | 减少忘记同步，服务器和设置并非全部自动上传。 |
| I06 | 家庭资料、按资料隔离和服务器访问范围 | P1 场景必要 | 已接入 | 共用设备用户需要，不应做成复杂社交账户系统。 |
| I07 | 儿童资料、家长 PIN 和管理边界 | P0 场景必要 | 已接入；不等于完整内容分级服务 | 开启儿童模式后访问边界必须可靠。 |
| I08 | 观看时长、月历、趋势和回顾 | P2 可选 | 本机统计；不属于同步文档 | 提升留存的辅助功能，可减少展示与统计细项。 |
| I09 | 数据安全导出、清空云端和删除账号 | P1 必须 | 使用账号时已接入 | 提供可控退出路径，不能只有注册和上传。 |

## 一起看

依据：[同步模型](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/WatchTogetherModels.kt)、[房间片单](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/WatchRoomPlaylist.kt)、[协议](D:/Demo/Yfuse/watchTogetherProtocol/src/commonMain/kotlin/com/yfuse/watch/protocol/WatchProtocol.kt)、[中继服务](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| J01 | 建房、房间码、邀请分享、加入与退出 | P1 场景必要 | 已接入；官方安全账号服务 | 若保留一起看，这是必要闭环。 |
| J02 | 同步播放暂停定位、漂移校正和重连 | P1 必须 | 已接入；仅同步控制与状态 | 不传输影片本体，各参与者需有可用片源。 |
| J03 | 跨服务器媒体匹配和版本时长差异提示 | P1 场景必要 | 已接入 | 不同剪辑版本不能盲目同步。 |
| J04 | 房主、管理员、控制申请和移出成员 | P1 必须 | 已接入 | 防止多人争抢控制或绕过权限。 |
| J05 | 房间播放列表、添加删除排序和选片 | P2 场景必要 | 已接入 | 连续一起看需要，单片场景可不用。 |
| J06 | 文本聊天、确认重试和聊天浮层或弹幕 | P2 场景必要 | 已接入 | 一起看交流便利，显示层可简化。 |
| J07 | 表情反应、贴纸及昵称头像 | P2 可选 | 已接入 | 互动装饰，建议停止不断增加效果与素材。 |
| J08 | 成员就绪缓冲、网络状态、房主接续和房间回收 | P1 必须 | 已接入 | 保障失败恢复和后台资源边界。 |

## 跨设备和第三方记录

依据：[设备接力](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/handoff/DeviceHandoffScreen.kt)、[电视手机遥控](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/remote/TvPhoneRemote.kt)、[投屏状态](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/cast/CastManager.kt)、[Cast 接收端](D:/Demo/Yfuse/castReceiver/README.md)、[Trakt](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/trakt/TraktRepository.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| K01 | 在线设备列表、播放状态和接收确认后继续观看 | P1 场景必要 | 已接入；完整设备联调另需验收 | 手机电视协同有明显价值，不能未经确认打断当前设备。 |
| K02 | 手机遥控 TV、配对确认、按键和文本输入 | P1 场景必要 | 手机电视和中继已接入 | 提升 TV 搜索与控制体验；敏感输入边界必须保留。 |
| K03 | 手机向 TV 传递 Emby 或 Jellyfin 登录 | P1 场景必要 | 已接入；Plex 走自身 PIN | 显著降低电视配置成本，凭据不应持久留在中继。 |
| K04 | DLNA 发现、投屏和手机转发片源 | P1 场景必要 | Android 已接入；依接收设备 | 广泛兼容的目标，HTTPS 和外网来源需要本机转发。 |
| K05 | Google Cast、队列、轨道和音量控制 | P1 场景必要 | 有发送与接收实现；真实设备与发布状态需验证 | 面向相应设备，不能用网页可访问代替接收端验收。 |
| K06 | 投屏失败恢复、停止后本机接力和 Dolby 输出确认 | P1 必须 | 按接收端反馈实现 | 只要支持投屏，就需要可靠控制与恢复。 |
| K07 | Trakt 授权、观影上报、想看历史导入和待发重试 | P2 场景必要 | 已接入；依 OAuth 服务配置 | 特定用户需要，普通用户保持关闭。 |

## 外观和系统使用体验

依据：[外观设置](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/profile/ProfileSettingsScreens.kt)、[主题存储](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/ThemePreferences.kt)、[桌面组件](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/widget/ContinueWatchingWidget.kt)、[桌面快捷方式](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/shortcuts/AppShortcuts.kt)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| L01 | 手机导航、返回栈、页面状态恢复和 TV 焦点 | P0 必须 | 已接入 | 不能用动画效果掩盖返回错误或焦点丢失。 |
| L02 | 手机平板折叠布局、竖屏和窗口系统适配 | P1 必须 | Android 已接入；需对应设备验收 | 保证文字控件可见可操作。 |
| L03 | 明暗主题、基础颜色和文字可读性 | P1 必须 | 已接入 | 基本阅读和观看体验。 |
| L04 | 减少动态效果、辅助显示、触觉和语义可访问性 | P1 必须 | 已接入 | 必须提供舒适且可访问的操作方式。 |
| L05 | 动效主题和性能预算自动降级 | P2 可选 | 已接入 | 保留少量风格和低成本默认值即可。 |
| L06 | 玻璃材质、背景图和细粒度参数 | P2 可选 | 已接入 | 设置与调试面很大，建议保留预设、收起细项。 |
| L07 | 多种弹窗、加载和播放器进出动画 | P3 可选 | 已接入 | 边际价值有限，组合数量会放大回归成本。 |
| L08 | 粒子光、扫光、轮播和海报特效 | P3 可选 | 已接入 | 建议暂停增加效果，避免与播放资源竞争。 |
| L09 | 多套 App 图标、开屏动画及图标同步 | P2 可选 | Android 已接入 | 维持现有兼容即可，新增套数优先级低。 |
| L10 | 桌面续播追剧组件、快捷方式和深链 | P2 场景必要 | Android 已接入 | 常用用户便利，TV 无需机械复制手机组件。 |
| L11 | 下载投屏房间状态胶囊、使用提示和设置搜索 | P2 可选 | 已接入 | 汇总操作有用，应控制信息量和提示频率。 |
| L12 | 启动页选择和滚动时收起导航栏 | P2 可选 | 已接入 | 个人使用习惯有差异，保留简单开关即可。 |

## 服务端和工程保障

依据：[后端隔离](D:/Demo/Yfuse/docs/BACKEND_ISOLATION.md)、[服务器接口](D:/Demo/Yfuse/watchTogetherServer/ACCOUNT_API.md)、[发布流程](D:/Demo/Yfuse/docs/android-release.md)、[本次服务器上线记录](D:/Demo/Yfuse/audit/server-deploy-20261012/README.md)、[项目操作要求](D:/Demo/Yfuse/AGENTS.md)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| M01 | 账号、同步、房间、设备遥控和接力的后端服务 | P1 场景必要 | 已部署 | 对开启云功能的用户必要；不能影响本地功能独立性。 |
| M02 | TMDB 代理、鉴权、缓存和上游凭据隔离 | P1 场景必要 | 已部署并接入 | 减少客户端分发凭据与直接连接问题，仍依赖上游可用性。 |
| M03 | 可关闭项目自有后端的构建边界 | P1 场景必要 | 已接入 | 降低云服务故障影响，保留媒体服务和本地播放。 |
| M04 | TLS、同源凭据、安全存储、限速和请求大小边界 | P0 必须 | 客户端和服务端已接入 | 所有联网和账户能力的基础，不能按低使用率删除。 |
| M05 | 应用更新、哈希包名版本签名校验和安装 | P1 必须 | Android 已接入 | 更新必须可信，旧新客户端清单需保持兼容。 |
| M06 | 正式签名、递增版本、更新说明、MDK 和 DEX 产物校验 | P0 必须 | 有构建和检查脚本 | 用户明确要求；功能精简不改变标准 full 包要求。 |
| M07 | 结构化播放错误、本地日志和诊断导出 | P1 必须 | 已接入 | 能否快速定位不能播，决定维护效率。 |
| M08 | 用户选择的匿名播放质量聚合 | P2 可选 | 有客户端和服务器实现 | 没有用户同意不能自动上报；本地诊断优先。 |
| M09 | 单元测试、设计检查、基准测试和设备长稳门禁 | P0 必须 | 有工程设施；各门禁证据需分别满足 | 保障复杂播放链路，不代表已有全格式全设备通过。 |
| M10 | 后端健康检查、数据库备份、保留旧版本和回滚 | P0 必须 | 本次部署已执行 | 有生产后端就必须能恢复，不能只会更新。 |
| M11 | 远端播放策略获取、缓存和路由辅助 | P2 可选 | Android 有调用和功能边界 | 只有可验证的兼容收益才值得持续运营；本地默认策略必须可用。 |
| M12 | 清理图片视频缓存及权限后台能力诊断 | P1 场景必要 | Android 已接入 | 节省空间和解决系统限制，清缓存不能删离线下载。 |

## 平台和当前不交付的范围

依据：[根项目模块](D:/Demo/Yfuse/settings.gradle.kts)、[Android 支持范围](D:/Demo/Yfuse/README.md)、[鸿蒙移植状态](D:/Demo/Yfuse/harmonyApp/PORT_STATUS.md)、[鸿蒙发布清单](D:/Demo/Yfuse/harmonyApp/RELEASE_CHECKLIST.md)。

| 编号 | 功能与范围 | 重要性和必要性 | 当前状态 | 取舍理由 |
| --- | --- | --- | --- | --- |
| N01 | Android 手机应用 | P0 必须 | 主交付平台；目前标准 APK 为 ARM64 | 保持稳定播放和基础闭环。 |
| N02 | Android TV 独立壳、遥控器导航和播放 | P1 场景必要 | 有独立模块和实际界面 | 家庭影音方向的重要平台，应专门验收焦点与输出。 |
| N03 | HarmonyOS Cangjie 移植 | P3 暂缓 | 基础流程有源码接线；没有完整 HAP 发布和设备证据 | 下载、DLNA、后台、原生渲染等适配缺口仍在。 |
| N04 | 原生宿主验证、媒体测试资产和性能测试 App | P1 场景必要 | 工程工具 | 支撑播放器验收，不应列作普通用户产品功能。 |

当前没有完整的直播电视和 DVR 产品闭环，也没有桌面或 iOS 发布模块。Alist 入口不是网盘下载器；一起看不是影片分发服务；BD-J 接口和鸿蒙高级面板不是已完成的运行能力。

## 筛选结果和投入顺序

最需要持续保障的是 A01 A02 A04、B01 B02 B06、D01 D02 D03、E01 E05 E07、G03 G08、L01、M04 M06 M09 M10。它们共同决定能否连接、找到、看完、保存和安全恢复。

第一批产品投入建议放在起播和失败恢复、进度可信、搜索分页、TV 焦点、普通字幕和下载可靠性。第二批维护追剧提醒、手机 TV 控制和接力、投屏恢复、跨服务器选源与配置迁移。一起看若作为主卖点，可提高到第二批前部，否则维持现有能力并控制扩展。

建议简化的是玻璃细参数、多套弹窗加载转场、粒子扫光、重复的播放策略说明、统计展示和互动素材。优先合并入口与预设，避免直接删除用户已保存的设置。建议暂停扩大 BD-J、鸿蒙移植和更多实验性播放组合；先明确目标设备、可运行产物和验收矩阵。

维护成本较高的部分是多内核与原生音视频链路、三类媒体服务器、原盘和 DRM、云同步冲突、各类投屏接收端、排期采集及鸿蒙适配。应为每类能力明确支持矩阵，避免把所有配置组合都做成独立承诺。主题预设和统一错误说明的成本通常更可控。

MDK full 包继续保留。基础字幕、数据安全、故障诊断、版本签名和必要的回滚材料不因界面精简而降低优先级。当前没有执行任何功能删除。

后续决定实际下线前，应统计功能进入、操作完成、失败率及相关维护工时；只收集必要且获得授权的数据。没有使用数据时，优先简化展示和停止扩展，不给出虚构的使用占比。

## 本次发现和修复

追剧日历 ICS 导出原来将排期日期放进事件 ID，改期后会变成另一个事件；无 TMDB 的不同剧集也可能共用 ID。文本和 URL 共用转义会改写链接；文件采用 LF 且没有按 UTF-8 字节折行，标题含回车时可能破坏结构。定时事件使用 TZID 却没有时区定义，且没有使用已知的精确上线时间。

修复保持媒体身份稳定并区分电影和剧集；文本换行规范化，URL 保持 URI 内容且拒绝嵌入换行；输出 CRLF 和安全的 75 字节折行；已知时间转为 UTC，无法确认时区时保持日期事件。事件时间戳使用本次导出时间。格式依据：[RFC 5545 内容行](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.1)、[时区参数](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.2.19)、[URL](https://www.rfc-editor.org/rfc/rfc5545.html#section-3.8.4.6)。

代码和测试：[CalendarIcs.kt](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/feature/calendar/CalendarIcs.kt)、[CalendarIcsTest.kt](D:/Demo/Yfuse/composeApp/src/commonTest/kotlin/com/yfuse/feature/calendar/CalendarIcsTest.kt)。测试和校验结果保存于 [审计目录](D:/Demo/Yfuse/audit/feature-inventory-20261012)。新的事件 ID 不会自动清理以前已经导入外部日历的旧事件，外部日历的重复处理也取决于对应应用。

验证结果：新增导出回归在修复前 8 项全部失败，修复后 8 项全部通过；7 个日历测试类共 55 项通过，没有失败或跳过。清单的 142 个编号及 59 处本地引用已检查。源码修复需要下次打包后才进入交付 APK；本次未改变已部署服务器或已交付 APK。
