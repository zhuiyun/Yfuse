# 工程优化与产品能力审查（第二轮）

审查日期：2026-08-16 · 基线：`master` @ `ba9a244` + 本分支修复
承接 `docs/CLOUD_MERGE_REVIEW.md`

---

## 零、环境限制（影响本文所有 Kotlin 结论）

本环境的代理封禁 `dl.google.com`，AGP 8.11.1 无法解析，**任何 Gradle 任务都跑不起来**
——包括 `:watchTogetherProtocol:jvmTest` 这种纯 JVM 模块（根构建要配置 `:composeApp`，
而它需要 AGP）。因此：

- Python 侧的改动（供应链脚本）已在本地实测通过。
- **Kotlin 侧的改动全部未经编译验证**，必须由 CI 确认。
- 本文新提的代码问题来自静态分析，未经运行时 profiling 佐证。

---

## 一、P1 处理结果

### P1-2 图片 User-Agent —— ✅ 已修复

`YfuseApp.kt` 的 Coil fetcher 原先写死 `DEFAULT_EMBY_USER_AGENT`，
而 API 客户端走 `UserAgentPreferences`。现改为通过 `GlobalContext` 解析偏好，
并用 `defaultRequest { header(HttpHeaders.UserAgent, ...) }` **按请求读取**
——原来的 `install(UserAgent)` 插件会在 Coil 构建客户端时把字符串固化，
而 Coil 每个进程只构建一次，改设置要重启才生效。

新增 `ImageLoaderUserAgentTest`（3 个用例），沿用本模块既有的**源码扫描式契约测试**
风格（`AndroidHttpEnginePolicyTest` 也是这么做的）——图片加载器在真机上只能通过
Coil 单例访问，单测够不到。测试的 5 条断言已在本地用等价的 Python 正则逐条验证通过。

### P1-1 HTTP 栈合并 —— ⚠️ 撤回建议，不做

上一轮我把这条列为"直接改善首屏加载与资源占用"。深入之后，**两个理由让我撤回它**：

**一、它和一条刻意设置的安全测试冲突。** `AndroidHttpEnginePolicyTest.kt:45` 断言源码里
必须**逐字**存在：

```kotlin
actual fun embyHttpEngine(): HttpClientEngine = OkHttp.create()
```

并用正则禁止 `preconfigured =`、`sslSocketFactory`、`trustManager`、`hostnameVerifier`。
意图很明确：Android 引擎必须保持未配置状态，以继承平台 TLS 与 Network Security Config。
任何形式的 `OkHttp.create { config { ... } }` 都会让这条测试失败，哪怕配的是与 TLS 无关的连接池。
让共享引擎单例通过测试还要动它的 `engine.close()` 拆卸逻辑——一个我无法运行的测试。

**二、收益比我上一轮说的小。** 我当时的表述（"每张海报的首次加载都要多付一次握手"）
是错的。Coil 的所有图片请求共用它自己那一个客户端，池预热后内部复用正常；
跨池浪费只是"API 池 + 图片池"两套到同一主机，量级是几条连接，不是每请求一次握手。
空闲的 OkHttpClient 本身也很便宜——Dispatcher 线程按需创建、60 秒空闲回收。

结论：**这更像一致性/可维护性问题，不是性能问题**，不值得为它削弱一条 TLS 守卫。
真要做，应该由能跑完整测试套件的人连同那条测试一起改，并明确保留"禁止一切 TLS 相关配置"
的断言。

### P1-3 冷启动急切初始化 —— ⚠️ 大幅下调，不做

同样需要更正。逐个读了启动路径的实现之后：

| 调用 | 实际行为 |
| --- | --- |
| `AccountRepository.start()` | `scope.launch { restoreSession() }` —— **已经是异步的** |
| `PlaybackReportingCoordinator.flushPending()` | 内存态 `pendingServerIds()` + WorkManager 入队 —— **不阻塞** |
| `AppUpdateManager` 急切构造 | `restoreInterruptedDownload()` 中**昂贵的 APK 哈希已被显式挪到 `Dispatchers.IO`**，注释写明了原因；剩下的是一次 SharedPreferences 读和一次 `File.isFile` |

而且 `AppUpdateManager` **不能**推迟：它在 `init` 里注册 `ActivityLifecycleCallbacks`，
推迟就会错过 MainActivity 的首次 `onActivityResumed`，进首页自动检测更新随之失效
——代码注释已经写明"both of which have to happen before the first screen appears"。

主线程上真正剩下的同步磁盘操作只有 `DiagnosticLogStore.initialize()` → `pruneLocked()`
（一次 `listFiles()` + 每文件 `lastModified()` + 少量 `delete()`，受 `MaxFiles` 约束）。
量级是几毫秒，且它紧邻崩溃处理器的注册与 `synchronized(lock)` 协议。
**在无法运行测试的前提下，为几毫秒去动日志存储的锁不划算**，记为低优先级备选。

作者在这条路径上的处理其实是克制且正确的，我上一轮低估了。

---

## 二、本轮新增的代码发现

整体复核了数据层、缓存、Compose 列表与并发扇出。**大部分我预期会有问题的地方都没有问题**，
先记录经过验证的良好实践，再列真正的发现：

- Lazy 列表：37 处 `items(`，26 处带 `key=`；其余 11 处是枚举、固定计数或选项列表，本就不需要 key。
- 缓存：`LibraryCache`、`TmdbHomeCache` 都有明确的行数/条目/字符数上限与 7 天过期。
- 搜索：有 debounce，且**跨全部服务器聚合搜索**（`SearchStore.kt:555`）。
- 并发：数据层 31 处 `async {}` 扇出，均为并行而非串行 N+1。
- 图片 URL：按用途分档（海报 `maxHeight=450`、背景 `maxWidth=1280`），带 `tag` 做缓存失效。

### 发现 1 · 图片可以省下约三成流量（低风险、收益直接）

`core/network/EmbyImages.kt:28,65`

```kotlin
"...?${tagQuery}maxHeight=$maxHeight&quality=90"   // 海报
"...?${tagQuery}maxWidth=$maxWidth&quality=85"     // 背景
```

两点可优化：

1. **海报 `quality=90` 偏高。** 在 450px 高的海报上，90 与 82 肉眼几乎无差别，
   而 JPEG 体积差约 25–30%。首页一屏几十张海报，对自建服务器的家宽上行是实打实的负担。
2. **没有请求 WebP。** Emby 的图片接口支持 `&format=webp`，同等观感下比 JPEG 小 25–35%，
   Android 全版本原生解码。这是本项目性价比最高的单点带宽优化。

建议做成可回退的形式（`format` 失败时退回默认），并先在目标服务器上验证
——不同 Emby 版本对 `format` 参数的支持程度不完全一致。

### 发现 2 · 日历的 TMDB 扇出没有显式并发上限

`core/data/TmdbRepository.kt:600`

```kotlin
shows.map { show -> async { schedule(show.id, language) /* + season(...) */ } }
```

`CALENDAR_MAX_SHOWS = 24`，每个 show 最多再发一次 `season()`，即一次刷新最多约 48 个并发请求。
代码里没有任何 `Semaphore` 或 `limitedParallelism`。

它现在没出事，是因为 **OkHttp 的 `Dispatcher` 默认 `maxRequestsPerHost = 5`** 顺手兜住了。
但这是引擎默认值在替业务代码承担约束——一旦有人调整 dispatcher、换引擎，或 TMDB 收紧限流，
就会变成一批 429，而日历的失败表现是**行静默变空**，不易察觉。

建议加一个显式 `Semaphore(6)`，把约束写在它该在的地方。旁边那句注释
（"The extra request per show is affordable because the schedule is fetched once a day and cached"）
说的是**总量**可接受，并没有说**瞬时并发**可接受，两者是不同的事。

### 发现 3 · `CastManager.readTarget` 无上限读取 + 连接可能泄漏（沿用上轮 P2-1）

`core/cast/CastManager.android.kt:1019`。SSDP 发现出来的设备描述文档用 `readText()`
无大小上限地读，且 `disconnect()` 不在 `finally` 里。建议 `readNBytes(64 * 1024)` + `try/finally`。
上一轮已列，这里重申：它是本次复核中**唯一一个可由局域网内设备触发的健壮性问题**。

### 发现 4 · 下载只有原画，没有码率选择

`core/offline/OfflineMedia.android.kt` 中找不到任何 `bitrate` / 转码参数
——离线下载走的是原始文件。一部 4K REMUX 动辄 40–80 GB，在手机上既存不下也没必要。

竞品（Infuse、官方 Emby、Findroid）普遍提供"下载时选择清晰度"，由服务端转码后再下发。
这项同时改善**存储占用**和**下载耗时**，属于产品与工程收益都明确的一项。

---

## 三、与其他 Emby 客户端的对比

对比对象：官方 Emby for Android / Emby for Android TV、
[Yamby](https://blog.csdn.net/longzekai/article/details/144095424)、Hills（安卓第三方），
以及 iOS 侧的 [Infuse / SenPlayer / Fileball](https://catcat.blog/en/emby-server)。

### 3.1 Yfuse 已经领先的部分

先说清楚，避免把"补齐清单"读成"落后清单"。以下能力在 Emby 客户端里属于少数派：

| 能力 | 说明 |
| --- | --- |
| **三引擎自适应内核（YCore）** | ExoPlayer / mpv / MDK 三后端 + 探测、切换、故障记忆、网络自适应。Yamby Pro 只是"可切 mpv 内核"，不是有编排层的自适应 |
| **一起看** | 房间、聊天、弹幕化聊天、贴纸、表情、主持人踢人、播放列表同步。Emby 服务端有 SyncPlay，但客户端做到这个完成度的极少 |
| **弹幕** | 与 Yamby 同属第一梯队 |
| **原盘导航** | ISO / BDMV 分类与结构解析，多数客户端直接放弃 |
| **多服务器聚合搜索** | 搜索结果里明确提到 Yamby **不支持**聚合搜索 |
| **追剧日历** | TMDB 日更/周更排期，官方客户端没有 |
| **Trickplay 预览、双字幕、HDR/杜比策略、片头片尾跳过、投屏（Cast + DLNA）、断点续传下载、睡眠定时、倍速、画中画、字幕偏移** | 均已具备 |

### 3.2 真实缺口（按建议优先级）

#### A. Android TV / 电视端 —— 最大的战略缺口

全仓 **0 处** `leanback` / `TV_BANNER` / TV Manifest 声明。

Emby 的核心使用场景之一就是客厅电视，官方专门做了
[Emby for Android TV](https://emby.media/emby-for-android-tv.html)（首页续播行、直播 now-playing、
手柄、语音搜索、DTS-HD MA / TrueHD Atmos 直通）。一个播放能力已经做到三引擎自适应的 app
却上不了电视，等于把最能体现它优势的屏幕让了出去。

这也是工作量最大的一项：需要 `LEANBACK_LAUNCHER` intent-filter、D-pad 焦点体系、
10-foot UI 布局、以及播放器控件的遥控器适配。但项目已经是 Compose Multiplatform +
清晰的 feature 分层，UI 层可复用度比多数项目高。建议作为下一个大版本的主线。

#### B. 直播电视 / DVR

`LiveTv` 相关代码 0 处（搜到的"直播"全部是 `DirectPlay` 的中文名"直播放"的误命中）。
官方客户端有完整的 EPG 与录制管理，**Yamby 也支持 Emby 直播**。
对使用 IPTV / m3u 或电视卡的用户，这是硬缺失。

分阶段做的话：先支持 `/LiveTv/Channels` 频道列表与直接播放（复用现有播放内核即可），
EPG 与 DVR 排程作为第二阶段。

#### C. 音乐库

`MusicAlbum` / `MusicArtist` 0 处 —— Emby 同时也是音乐服务器，官方客户端支持
专辑、艺术家、流派与 Instant Mix。Yfuse 目前是纯视频客户端。

如果定位就是影视，这可以是明确的**不做**决定；但那样最好在 README 里写明，
否则用户会当成缺陷。

#### D. 多用户 / 账号快速切换

`switchUser` / `multiUser` 0 处。Emby 服务器天然是家庭共享的，每个成员有独立的
观看进度与收藏。目前切换用户似乎只能重新登录。这项实现成本不高
（`ServerRegistry` 已经是多服务器结构），体验收益明显。

#### E. 播放器补齐项

| 项 | 现状 | 说明 |
| --- | --- | --- |
| **音频延迟调整** | ❌ 有 `subtitleOffsetMs`，无音频偏移 | 音画不同步在转码流里很常见；mpv 原生支持 `audio-delay`，接入成本低 |
| **字幕样式** | ❌ 0 处 `subtitleStyle` / 字号设置 | ASS/SSA 渲染已具备，但内嵌 SRT/VTT 无法调字号、描边、位置。老人与大屏用户的高频诉求 |
| **外部播放器唤起** | ❌ 0 处 | Jellyfin 系客户端（Findroid）常见的"用 MX Player / VLC 打开"兜底 |
| **实时超分（Anime4K）** | ❌ 0 处 shader | Hills 已有。项目已经带 mpv 内核，加载 glsl shader 的成本远低于从零实现；对弹幕/番剧受众极契合 |
| **均衡器 / 音量增强** | ❌ 0 处 | 手机外放看片的常见诉求 |

#### F. 生态与周边

- **Bangumi / Trakt 同步**：0 处。考虑到本项目已经做了弹幕，
  Bangumi（番组计划）追番同步与受众高度重合，是差异化机会。
- **桌面小组件**：0 处 `AppWidget`。"继续观看"磁贴是低成本高感知的一项。
- **服务器管理功能**：Yamby 支持触发媒体库扫描、编辑元数据。Yfuse 无。

### 3.3 建议的取舍

不建议全做。按"投入 ÷ 收益"排，我的推荐顺序是：

| 顺序 | 项 | 理由 |
| --- | --- | --- |
| 1 | 图片 WebP + 质量下调（发现 1） | 几十行改动，全局带宽收益 |
| 2 | 音频延迟 + 字幕样式（E） | 播放器已有偏好持久化框架，是补完而非新建 |
| 3 | 多用户切换（D） | 复用 `ServerRegistry`，体验收益明显 |
| 4 | 下载码率选择（发现 4） | 存储与耗时双收益 |
| 5 | 直播电视（B）第一阶段 | 复用现有播放内核，先做频道列表 |
| 6 | Android TV（A） | 工作量最大，但战略价值最高，适合作为下一大版本主线 |
| — | 音乐库（C） | 建议明确决定"不做"并写进 README，而不是留作隐性缺口 |

---

## 四、待办清单（含上一轮未完成项）

| 优先级 | 事项 | 出处 |
| --- | --- | --- |
| ✅ 已修复 | 供应链门禁失效、发布说明校验、图片 UA | 上轮 P0-1/P0-2、本轮 P1-2 |
| 高 | 分支清理（71 个陈旧分支） | 上轮 |
| 高 | ktlint 基线 2249 条债务分模块清偿 | 上轮 P1-4 |
| 中 | 图片 WebP + 质量参数 | 本轮发现 1 |
| 中 | `CastManager.readTarget` 读取上限与连接释放 | 上轮 P2-1 / 本轮发现 3 |
| 中 | TMDB 扇出显式并发上限 | 本轮发现 2 |
| 中 | TMDB token 走服务端中转 | 上轮 P2-3 |
| 中 | CI 三处重复的 Android 环境配置抽成复合 action | 上轮 P2-4 |
| 低 | `PlayerRoot` / 服务端 `Application.kt` 拆分（编译器已需 3 GB 堆） | 上轮 P2-5 |
| 低 | Gradle 配置缓存 | 上轮 P2-6 |
| 低 | Emby 身份偏好表改为有界 LRU | 上轮 P2-2 |
| 低 | `DiagnosticLogStore` 的 prune 移出启动路径 | 本轮 P1-3 |

**已撤回**：HTTP 栈合并（P1-1）、启动急切初始化（P1-3 的主体部分）——理由见第一节。
