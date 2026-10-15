# Yfuse 深度技术审查报告

- 审查日期：2026-10-14
- 审查对象：`D:/Demo/Yfuse`（版本 `1.3.4` / `VERSION_CODE=286`）
- 审查范围：代码质量、架构设计、性能瓶颈、安全隐患、可维护性、扩展性
- 方法：静态代码走查 + 构建/CI 配置核对 + Git 仓库实测 + 既有 docs 复核；所有结论均给出文件位置或可复核计数

## 0. 项目画像（审查基线）

| 维度 | 实测值 |
|---|---|
| Kotlin 源码（`composeApp/src`） | 2,061 文件 / **404,125 行** |
| 原生 C/C++（`ycore-native`） | 5 文件 / 1,551 行 |
| 服务端 Kotlin（`watchTogetherServer`） | 105 文件 / ~27k 行（另有 Node/Playwright 渲染器） |
| HarmonyOS（`harmonyApp`，仓颉 Cangjie） | 55 文件 / 5,270 行 |
| Gradle 模块 | 10 个（`composeApp` 为应用壳，`phoneShared`/`tvShared` 无源码） |
| 测试 | 5,268 个 `@Test`（601 个 commonTest 文件、262 个 androidUnitTest 文件、33 个 androidInstrumentedTest 文件） |
| Git | 15,062 个跟踪文件，`.git` **1.7 GB** |
| CI | 13 个 workflow，`publish-android.yml` 单文件 **1,512 行** |

整体判断：这是一个**工程纪律明显高于平均水平**的项目——依赖锁定、供应链 pin、签名/版本/DEX 校验、日志脱敏、服务端 systemd 加固都做得比多数同类项目扎实。**主要风险不在"没做"，而在"边界没有被结构强制"**：共享层是虚拟的、播放内核是两套并行的、仓库里沉淀了大量本不该进版本库的历史产物。

---

## 1. 结论摘要（按优先级）

### P0 — 应立即处理（安全 + 仓库健康）

| ID | 问题 | 位置 | 影响 |
|---|---|---|---|
| S1 | TMDb Bearer Token 被编译进 APK | `composeApp/build.gradle.kts:948` | 凭据泄露，与既有代理设计自相矛盾 |
| S2 | 全局允许明文流量 | `composeApp/src/androidMain/res/xml/network_security_config.xml:7` | 账号 token 可被中间人截获 |
| M1 | `audit/` 历史快照入库（7,664 份 `.kt` 副本） | `git ls-files audit` = 12,296 | `.git` 1.7 GB、grep/重构被污染 |
| A1 | `phoneShared`/`tvShared` 是无源码的"虚拟模块" | `phoneShared/build.gradle.kts:24`、`tvShared/build.gradle.kts:24,37` | 边界不被强制、全量重复编译、同名 namespace |
| A2 | 播放内核双契约 + 双向适配器 | `feature/player/contract/VideoEngine.kt:375` ↔ `core2/api/YPlayer.kt:13` | 双份真相，翻译有损 |

### P1 — 近期处理（架构 + 性能 + 服务端）

| ID | 问题 | 位置 |
|---|---|---|
| A3 | `core`(789 文件) 与 `core2`(401 文件) 并行内核 | `composeApp/src/*/kotlin/com/yfuse/core{,2}` |
| P1 | 解复用读路径"每包一次堆分配 + 全量拷贝" | `core2/android/AndroidFfmpegDemuxer.kt:200-222` |
| P2 | 音频透传每样本分配 `ByteBuffer.allocateDirect` | `AndroidNativeDirectYPlayer.kt:2627-2631` |
| P3 | 三个播放器各有一个 2 ms 轮询泵 | `PUMP_IDLE_DELAY_MS`（3 处，`AndroidNativeDirectYPlayer.kt:3425` 等） |
| P4 | HTTP 代理 serve 路径使用 `runBlocking` | `AndroidYCoreHttpProxy.kt:1810,1905` |
| M2 | 上帝类：单文件 3,000+ 行，`runLoop` 755 行 | `AndroidNativeDirectYPlayer.kt` 等；`config/function-size-baseline.txt` |
| M3 | 手机/TV UI 是"分叉"而非"共享" | `tvApp/src` 重写 11 个手机页面 |
| SV1 | 房间状态纯内存 + 全局锁 + 单 SQLite 连接 | `WatchRoomStore.kt:36,44,109`、`AccountStore.kt:132-140` |
| SV2 | Playwright 渲染器 SSRF 面 + `--no-sandbox` + 示例 token 可用 | `network-policy.mjs:24-53`、`server.mjs:99,25` |

### P2 — 排期改进（可维护性 + 工程）

| ID | 问题 | 位置 |
|---|---|---|
| S3 | 裸 IP 后端无证书 pin | `yfuseBackendClient/.../BackendEndpoints.kt:5` |
| S4 | 更新清单签名默认关闭（公钥为空） | `gradle.properties`（`yfuse.updateManifestPublicKey=`）、`AppUpdateManager.kt:1729-1752` |
| M4 | 风险最高的类几乎没有单测覆盖 | `AndroidEnhancedPlaybackSession` 0 处测试引用 |
| M5 | 版本同步仅靠人工，无 CI/pre-commit 闸门 | `AGENTS.md:7-13`、`scripts/release_metadata.py` |
| M6 | 文档漂移：README 与实际导航不一致 | `README.md:5` vs `app/RootComponent.kt:35,51` |
| E1 | 构建脚本与 CI 逻辑复杂且重复 | `composeApp/build.gradle.kts`(1,424 行)、`publish-android.yml`(1,512 行) |
| E2 | 第三套 UI 重实现（鸿蒙/仓颉） | `harmonyApp/entry/src/main/cangjie/**`（55 文件） |

### P3 — 低优先级 / 技术债清理

- 稳定性配置过窄且编译报告未生成（`config/compose-stability.conf`）
- Macrobenchmark 指标单薄（仅 Startup/FrameTiming，无 Memory/Power）
- 105 处 `@Suppress`（54 处 `unused`）掩盖死代码
- `scripts/` 脚本堆积（52 `.py` + 25 `.sh`），原生脚本命名重叠
- `lint.xml` 关闭依赖新鲜度检查且无复核日期
- `watchTogetherServer/build.gradle.kts:17` 内联硬编码依赖版本
- 根目录 26 个未跟踪 APK 约 613 MB；分支 `agent/*`、`codex/*` 堆积

---

## 2. 安全（Security）

### S1【严重】TMDb Bearer Token 被编译进 APK

- **现状**：`composeApp/build.gradle.kts:767-773` 从 `local.properties` 读取 `tmdb.token`，`:948` 通过 `buildConfigField("String","TMDB_TOKEN", ...)` 写入 `BuildConfig`；`core/network/Tmdb.android.kt:5` 直接返回该值。当前 `local.properties` 中确实存在一个真实 JWT。
- **影响**：任何用该工作区构建的 APK 都内嵌可用凭据，`strings`/反编译即可提取；而项目本身已部署服务端 TMDb 代理（`TmdbProxy.kt`），注释明确写着"read token 应留在服务端而不是每个 APK"。客户端内嵌直接抵消了该设计。
- **建议**：删除 `TMDB_TOKEN` 这个 `buildConfigField`，所有 TMDb 读取统一走账号侧代理；确需客户端 token 时按应用维度申请、可轮换、并禁止在 release 构建中注入。

### S2【高】全局允许明文流量

- **现状**：`composeApp/src/androidMain/res/xml/network_security_config.xml:7` 为 `<base-config cleartextTrafficPermitted="true"/>`，仅对 `themoviedb.org`/`tmdb.org`/`trakt.tv`/`plex.tv` 关闭（`:15`）。
- **影响**：用户自填的 Emby/Plex/WebDAV 源、以及随之发送的 Emby access token，可以走明文 HTTP；同网段可被嗅探。注释解释了兼容动机（自建服务器常用 HTTP），但把"允许"设成全局默认而非按域白名单，风险外溢到所有主机。
- **建议**：`base-config` 改为 `false`，为局域网媒体源显式添加 `domain-config`（或仅在 debug 变体放开）；任何携带凭据的请求强制 HTTPS。

### S3【中】账号/更新后端使用裸 IP 且无证书 pin

- **现状**：`yfuseBackendClient/src/commonMain/kotlin/com/yfuse/backend/BackendEndpoints.kt:5` 硬编码 `ORIGIN = "https://47.112.219.60"`，同时承载账号 API、TMDB 代理与更新清单。客户端无 SPKI pin，也无 `default_sni`。部署侧存在域名 `yfuse.zhuiyun.site`（`deploy/Caddyfile`），但客户端不使用。
- **影响**：IP 字面量证书短周期（Caddy `shortlived`），任何受信 CA 误签发/被攻破即形成完整 MITM，可篡改账号 token 与更新清单。
- **建议**：改为域名优先、IP 兜底；在 Network Security Config 中为该主机加 `<pin-set>`（release 强制）；建立证书轮换与 pin 更新流程。

### S4【中】更新清单签名默认关闭

- **现状**：`gradle.properties` 中 `yfuse.updateManifestPublicKey=` 为空，`AppUpdateManager.requireTrusted()`（`:1729-1752`）在无公钥时按 `UnverifiedNoKey` 放行；构建期 `verifyReleaseSigning` 仅在本地告警。CI 只在"公钥为空且为生产签名"时报错。
- **影响**：分布式构建若漏配公钥，更新完整性只剩 HTTPS + Android APK 签名双重保障；对"回滚/降级"之外的清单篡改防护缺失。
- **建议**：所有可分发构建强制要求非空 Ed25519 pin，堵住 `packageOnly`/本地构建的口子。

### S5【中】更新产物存在明文 HTTP 通道

- **现状**：`watchTogetherServer/deploy/Caddyfile` 保留 `http://47.112.219.60` 块，`Application.kt:362` 的 `staticFiles("/yfuse", updateRoot)` 会把 `update-v2.json` 与 APK 暴露在 HTTP 上；`README` 提到旧版 `update.json` 携带 `http://` 的 `apkUrl`。
- **影响**：应用侧虽有 HTTPS-only 校验（`AppUpdateManager.kt:283-318`）与 APK 签名校验兜底，但旧客户端可被降级/篡改。
- **建议**：HTTP 虚主机整体 301 到 HTTPS（仅保留最小引导），或只从 HTTP 提供非敏感文件。

### S6【中】解复用 JNI 大面是"取回的二进制"而非源码

- **现状**：`FfmpegNativeBridge.kt:17` `System.loadLibrary("ycore_demux")`，大量 `external fun` 接收 Kotlin 传入的 handle/`ByteBuffer`/索引；该库不在 `ycore-native/`（后者只有小巧且防御性良好的 `ycore.cpp` 会话路由）。取回过程经 HTTPS + `engine-checksums.sha256` 校验。
- **影响**：属于"信任预编译产物"，无法审计、无法复现构建，也无法对原生侧做漏洞响应。
- **建议**：vendored 或可复现构建解复用源码并纳入 CI；至少把校验和与来源固定到 release 流程的产物校验中。

### S7【中】代理头信任 + 容器 0.0.0.0 暴露的组合风险

- **现状**：`WatchTransport.isSecureServiceTransport`（`:45-52`）对 loopback 对端接受 `X-Forwarded-Proto: https`；`resolveClientIp` 仅在 loopback 时信任 `X-Forwarded-For`（`:26-43`）。生产 systemd 绑定 127.0.0.1（安全），但 `Dockerfile:11` 设 `HOST=0.0.0.0` 且 `EXPOSE 8080`。
- **影响**：一旦容器端口被发布，远端可伪造这两个头绕过 TLS 强制与限流身份识别。
- **建议**：容器内也绑定 loopback；补充 `HEALTHCHECK`；文档明确 8080 永不对外。

### 已确认做得好的安全控制（无需改动）

- **更新路径无法安装未验证字节**：`AppUpdateManager.install()` 重跑 `validateForUpdateSource().requireTrusted()`（`:1758`）与 `apkMatchesInstalledUpdate`（`:1767`、`:2087-2120`），校验包名/versionCode/签名摘要包含关系、SHA-256 与大小，且不可降级（`:92`、`:894`）。
- **无应用内 WebView / JS 桥**：全仓 0 处 `addJavascriptInterface`；Cast 接收器仅用 `textContent`，无 `eval`/`innerHTML` sink。
- **原生会话路由防御性良好**：`ycore.cpp:13-19,55-63` 边界拷贝、`:266,299,477` 空指针与 ABI/结构体大小校验、`:362` 速度有限边界。
- **服务端**：TLS/WSS 强制、Bearer 鉴权、按账号/IP 限流、PBKDF2-HMAC-SHA256 600k + 常量时间比较、TMDB 严格白名单（无 SSRF）、消息/帧大小上限、systemd 加固。
- **供应链**：19 个 `gradle.lockfile`；`scripts/security-overrides.properties` 统一 pin BouncyCastle/Netty/protobuf/Wire；`package-lock.json` 入库、`node_modules` 未跟踪。
- **日志**：`SafeLogcatOutputGate` 默认关闭 logcat，`DiagnosticRedactor` 掩码 token/IP/主机/Bearer/URL；服务端请求日志刻意省略 query string。

---

## 3. 架构（Architecture）

### A1【严重】`phoneShared` / `tvShared` 是无源码的"虚拟模块"

- **现状**：`phoneShared/build.gradle.kts:24,36` 与 `tvShared/build.gradle.kts:24,37` 用 `srcDir("../composeApp/src/...")` 把 `composeApp` 的 `commonMain`/`androidMain` 挂进来；两个模块目录下只有 `build.gradle.kts` + `gradle.lockfile`，**没有 `src/`**。`tvShared` 额外挂了 `../tvApp/src/androidMain/kotlin`，而 `tvApp` 又 `dependsOn(:tvShared)`。两者 `namespace` 都是 `com.yfuse.shared`。
- **影响**：
  1. 模块边界不被物理强制——共享代码可以悄悄依赖 TV 专属类型（`tvShared:37`）；
  2. 任何共享改动都要被两个 Android library 目标各编译一次，编译成本近乎翻倍；
  3. 同名 namespace 带来 R 类/DEX 重复名风险；
  4. 依赖方向倒置：library 反向编译 app 的源码。
- **建议**：抽成物理 `src/` 的真实模块，或至少物理拆分 `commonMain`/平台目录并区分 namespace；把 TV 源码下沉到 `tvShared`，消除反向依赖。

### A2【严重】播放内核双契约 + 双向适配器

- **现状**：`feature/player/contract/VideoEngine.kt:375` 的 `VideoEngine` 与 `core2/api/YPlayer.kt:13` 的 `YPlayer` 是两套并行抽象，各自带一份状态模型（`PlaybackState` vs `YPlayerState`）。`core2/legacy/LegacyYPlayerAdapter.kt:33` 把 `VideoEngine` 包成 `YPlayer`，`YPlayerVideoEngineAdapter.kt:38` 又把 `YPlayer` 包回 `VideoEngine`。
- **影响**：两个真相源，双向翻译有损；抽象天然泄漏；生命周期/状态修复要在两条路径各做一遍。
- **建议**：收敛到 `YPlayer` 单一契约，迁移 `PlayerRoot` 展示层，删除反向适配器，`core2/legacy` 设为冻结待删。

### A3【高】`core` 与 `core2` 并行内核，非干净迁移

- **现状**：`core` 789 个 `.kt`，`core2` 401 个 `.kt`；路由/规划逻辑重复：`core/playback/PlaybackPlanner.kt`(403 行) vs `core2/strategy/YPlaybackStrategy.kt`(435 行)；`core2/legacy/` 有 9 个桥接文件。`docs/YCORE2_ARCHITECTURE.md` 描述了并行迁移模型，但代码中没有"退役闸门"。
- **影响**：两套路由/规划必须保持行为一致，迁移风险高且无界；DTO/网络关注点也重复（`core/data/dto/` vs `core2/network/`）。
- **建议**：明确 `core2` 为唯一内核，发布 Legacy 退役清单（含删除标准与时间点），把路由策略收敛到单一 owner。

### A4【高】四个播放引擎各 1,300–3,428 行，编排逻辑重复

- **现状**：`AndroidNativeDirectYPlayer.kt`(3,428)、`AndroidAdaptiveCore2YPlayer.kt`(3,297)、`AndroidEnhancedPlaybackSession.kt`(2,683)、`AndroidNativeEnhancedYPlayer.kt`(1,299)、`AndroidNativeTunnelYPlayer.kt`(647)。`config/function-size-baseline.txt` 已记录 `runLoop` 755/358 行、`prepareCurrent` 在 Direct/Enhanced 中重复。`AndroidAdaptiveCore2YPlayer.kt:1076,1405,2860` 会构造其它引擎。
- **影响**：prepare/runLoop/fallback 编排按引擎复制改造；每个生命周期修复要改 N 处，行为易分叉。
- **建议**：抽出共享 session loop 与 fallback policy，引擎在其上组合（见第 4 节 P3 的同源问题）。

### A5【高】服务定位器被 UI 与引擎直接调用（91 处）

- **现状**：91 处 `GlobalContext.get()`，例如 `HomeScreen.kt:1549` 在 composable 内解析 `EmbyRepository`/`ServerRegistry`；`ExoVideoEngine.kt:106`、`MpvVideoEngine.kt:156`、`PlayerRoot.kt:167-1005` 亦然。
- **影响**：依赖隐式化、难测、易出初始化顺序 bug。
- **建议**：在 ViewModel/executor 边界用 Koin 构造注入；`GlobalContext` 仅保留给 Android 入口（Worker、Receiver）。

### A6【中】UI 直接触达 repository/网络

- **现状**：20 个 `*Screen*.kt` 直接 import `com.yfuse.core.data`/`core.network`；`DetailScreen.kt:283,311` 在组合期调用 `currentPlaybackNetworkClass()`、`rankServerSources()`；`HomeScreen.kt:1549-1560` 在 `produceState` 中调 `repository.libraryItems(...)`。
- **建议**：读取统一走 MVI store/executor，UI 只做渲染与事件上报。

### A7【中】"可选"后端仅运行时开关，编译期从未可选

- **现状**：`docs/BACKEND_ISOLATION.md:34` 明确关闭后端不移除编译依赖；`phoneShared:27`/`tvShared:27` 无条件 `api(project(":yfuseBackendClient"))`；21 个共享文件 import `com.yfuse.backend`。
- **影响**：模块始终参与编译与打包，"隔离"名不副实。
- **建议**：若目标是可移除，用 source-set 级接口 + 条件依赖替换实现；否则修正文档表述。

### A8【中】`DetailExecutor` 上帝对象

- **现状**：`feature/detail/DetailExecutor.kt` 1,854 行，12 个构造依赖（`:43-58`），多个 `LatestWins` 字段。
- **建议**：拆分为 source/catalog/playback 三个 loader。

### A9【中】状态层：MVI store + 19 个 Koin 单例，无 ViewModel

- **现状**：`*Store.kt` 合计 12,223 行（`PlayerStore.kt` 1,782、`ServersStore.kt` 1,621）；`di/` 中 19 个 `single(...)`；`ProductSession.kt:14-28` 持有进程级 `CoroutineScope`/`MutableStateFlow`。
- **影响**：模式一致是优点，但 store 过大、进程级单例在切换资料时存在泄漏/串状态风险。
- **建议**：保留 MVI，拆分大 store，审计单例生命周期与资料切换时的清理。

---

## 4. 性能（Performance）

### P1【高】解复用读路径：每包一次堆分配 + 全量拷贝

- **现状**：`core2/android/AndroidFfmpegDemuxer.kt:200-222` 每个包执行 `val data = ByteArray(size)`、`source.get(data)`（一次完整 memcpy），并在 `:218` 用 `buildSet { … }`；`readPacket` 还每包返回新的 `LongArray`（`FfmpegNativeBridge.kt:334-335`，原生 `nativeReadPacket` 在 `:534`）。
- **影响**：按 24 fps 视频 + 音频计，读线程每秒产生 50–100+ 次分配与一次整包拷贝，造成 GC 抖动与帧时间尖刺。
- **建议**：读线程复用一个定容 `direct ByteBuffer`；用复用的基本类型数组/handle 回传字段；用原始 flag 位掩码替换 `buildSet`。

### P2【高】音频透传每样本分配直接缓冲区

- **现状**：`AndroidNativeDirectYPlayer.kt:2627-2631` 对每个编码音频访问单元执行 `ByteBuffer.allocateDirect(...).put(...)`（48 kHz/1024 ≈ 每 21 ms 一次）。
- **影响**：直接缓冲区触发 `malloc` 并向 GC 注册，稳定制造 GC 压力，杜比透传下可能引起音频欠载。
- **建议**：池化少量直接缓冲区，或从复用缓冲区写出。

### P3【中高】三个播放器各有一个 2 ms 轮询泵

- **现状**：`PUMP_IDLE_DELAY_MS = 2L` 出现 3 处（`AndroidNativeDirectYPlayer.kt:3425`、`AndroidNativeEnhancedYPlayer.kt:1298`、`AndroidNativeTunnelYPlayer.kt:645`）；循环（如 `:359-409`）在 `didWork` 时立即重循环，否则等 2 ms，空转时可达 ~500 次唤醒/秒，每次都跑 `refreshOutputGate()`/`publishClockPosition()`。
- **影响**：播放期间 CPU/功耗与锁竞争。
- **建议**：由解码器输出/渲染回调驱动等待，取代固定超时轮询。

### P4【中】Cronet 传输每块一次数组拷贝

- **现状**：`core2/android/AndroidCronetMediaTransport.kt:143-144` 在每次 `onReadCompleted` 中 `ByteArray(byteBuffer.remaining())` 再 `get(bytes)`（直接缓冲在 `:134,147` 已复用，堆拷贝未复用）。
- **影响**：高码率 4K 流每秒数千块，产生持续堆分配。
- **建议**：把复用的 `ByteBuffer` 切片交给消费方，或使用池化数组。

### P5【中】代理 serve 路径 `runBlocking`

- **现状**：`AndroidYCoreHttpProxy.kt:1810`（及 `:1905`）把整个 manifest/segment 服务包在 `runBlocking` 中，每请求阻塞一个 worker 线程；上游慢即占住该线程。
- **建议**：`serve` 改为在结构化作用域上 suspend。

### P6【中】`Dispatchers.IO` 从未限并发

- **现状**：全仓未见 `limitedParallelism`；媒体通道共用默认（64 线程）池（`AndroidAdaptiveCore2YPlayer.kt:878`、`AndroidCurrentItemPreparation.kt:43`、`AndroidCronetMediaTransport.kt:68`）。
- **影响**：高负载下可能饿死其它 IO。
- **建议**：为媒体传输分配专用 `limitedParallelism(n)` dispatcher。

### P7【中】`PlayerActivity.onCreate` 首帧前做重同步初始化

- **现状**：`feature/player/PlayerActivity.kt:405-534` 构造多个控制器、`AppJankMonitor.attach`(`:482`)、`ServerSessionRecovery.showIfNeeded(this)`(`:423`) 可能阻塞首帧。
- **建议**：非可视初始化延后到首帧之后。

### P8【中】Compose 稳定性配置过窄，编译报告未生成

- **现状**：`config/compose-stability.conf` 仅列 `java.time.*`、`kotlin.time.Instant`、`java.util.UUID`；`build.gradle.kts:70-73` 已接 `reportsDestination`/`metricsDestination`，但 `composeApp/build/compose-compiler/reports` 为空——没有可入库的稳定性证据。列表 key 使用良好（`HomeScreen.kt:1413,1594,1953` 等），`@Stable`/`@Immutable` 用了 112 处、`derivedStateOf` 93 处。
- **建议**：生成并入库稳定性报告；为持有 `List` 字段的状态类补注解（配置文件刻意不含 `List`）。

### P9【中】Macrobenchmark 指标单薄

- **现状**：`macrobenchmark/.../StartupBenchmark.kt:22-30` 仅 `StartupTimingMetric` + `FrameTimingMetric`；journey 基准只有帧时间。无 `MemoryMetric`/`PowerMetric`/`TraceSectionMetric`。
- **建议**：冷启动与进入播放器补充内存/功耗/关键 trace 段指标。

### 已确认做得好的性能实践

- **MDK JNI 快照已批量化**：`feature/player/MdkPlaybackSnapshot.kt:13` 一次 JNI 调用解出 6 个字段（`nativePlaybackSnapshot`），与 `docs/PLAYBACK_OPTIMIZATION_20261014.md` 一致；稳态 JNI 频率约"每包 1 次"（视频解码走 MediaCodec，非 JNI）。
- **图片加载与缓存调优良好**：`YfuseApp.kt:272-283` 内存缓存 20%、磁盘 256 MB、关闭 crossfade；取色有界（`DominantColor.android.kt:81-89` 请求 256×256、`allowHardware(false)`，Palette 限 24 色/72×48 采样）；`ArtworkColorCache.kt:37-41` 上限 128 条、2 并发、仅存 ARGB。未发现全尺寸 `Bitmap` 解码。
- **线程模型合理**：播放 worker 为单线程 DISPLAY 优先级 daemon（`AndroidNativeDirectYPlayer.kt:3387-3397`），路由为 `Dispatchers.Default.limitedParallelism(1)`（`AndroidAdaptiveCore2YPlayer.kt:3091`），热路径无 `GlobalScope`/`runBlocking`。
- **资源释放有序**：`AndroidNativeDirectYPlayer.kt:3214-3240` 释放输出/解码器/渲染器；代理在 `finally` 关闭传输（`AndroidYCoreHttpProxy.kt:1863`）。未发现泄漏的文件句柄。

---

## 5. 可维护性与代码质量（Maintainability）

### M1【严重】`audit/` 历史快照入库，仓库被历史产物淹没

- **现状（实测）**：`git ls-files audit` = **12,296** 个文件，其中 **7,664 个是 `composeApp/src/**/*.kt` 的副本**；`audit/` 下还有 15 个 `.so/.aar/.jar/.tar.gz`（最大 `handoff-deploy-20260918/...tar.gz` 23 MB）。`.git` 实测 **1.7 GB**。`.gitignore:55-92` 已忽略 audit 下的图片/zip/jar，但这些文件在规则生效前就已被跟踪（grandfathered）。
- **影响**：克隆极慢；陈旧重复源码污染 grep、重构与 AI 上下文；历史中一旦混入密钥无法靠 `.gitignore` 挽回。
- **建议**：用 `git filter-repo` 从历史清除 audit 二进制与源码树；审计证据改存 CI/release 产物；补 `.gitignore` 规则（`audit/**/*.kt`、`*.tar.gz`、`*.aar`、`*.so`）。

### M2【高】上帝类（单文件 2,000–3,428 行）

| 文件 | 行数 | 函数数 |
|---|---|---|
| `core2/android/AndroidNativeDirectYPlayer.kt` | 3,428 | 86 |
| `core2/android/AndroidAdaptiveCore2YPlayer.kt` | 3,297 | 65 |
| `core2/android/AndroidEnhancedPlaybackSession.kt` | 2,683 | 69 |
| `core2/android/AndroidYCoreHttpProxy.kt` | 2,347 | 67 |
| `feature/player/MpvVideoEngine.kt` | 2,220 | 38 |
| `update/AppUpdateManager.kt` | 2,160 | 55 |
| `feature/player/ExoVideoEngine.kt` | 2,036 | 21 |

- **影响**：单元不可测、合并冲突、R8/DEX verify 风险（`composeApp/build.gradle.kts` 的函数尺寸闸门文档明确提到 3,700 行 `PlayerRoot` 曾使 Android 17 崩溃）。`AndroidAdaptiveCore2YPlayer.kt:2848-3297` 约 24 个自由函数（路由选择、探测预算、学习记忆、软件回退、杜比守卫）；`AndroidYCoreHttpProxy.kt` 混入 `YAdaptiveBufferModel`(`:86`)、代理(`:167`)与 20+ range/HLS/DASH/缓存身份助手(`:2055-2347`)。
- **建议**：从 adaptive player 抽出 `YCoreRoutePlanner`/`YCoreRecoveryPolicy`/`YCoreProbeBudget`/`YCoreLearningMemory`；把 proxy 拆成 range/HLS/DASH/cache-identity 模块。团队已有 `scripts/check_function_size.py` 与 `config/function-size-baseline.txt`——应"拆分后下调阈值"，而不是不断加基线。

### M3【高】手机/TV UI 是分叉，不是共享层

- **现状**：`tvApp/src` 重写了 11 个手机页面（`TvHomeScreen`、`TvServersScreen`、`TvDetailScreen`、`TvLibraryHomeScreen`、`TvSearchHomeScreen`、`TvCalendarScreen`、`TvSettingsScreen`、`TvPersonScreen`、`TvDownloadsScreen` 等；14,091 行），会 import 手机组件（`TvHomeScreen.kt:49-56`）但重建每个 composable；`ServersTabScreen.kt`(2,382) vs `TvServersSettingsScreens.kt`(598)，`HomeScreen.kt`(2,000) vs `TvHomeScreen.kt`(481)。
- **影响**：每个 UX 改动都要双份维护。
- **建议**：把页面骨架/状态渲染下沉到 `commonMain` 的共享 composable，`tvApp` 只保留焦点/DPAD 覆盖。

### M4【高】测试很多，但最危险的代码几乎没被覆盖

- **现状**：5,268 个 `@Test`（601 个 commonTest、262 个 androidUnitTest、33 个 androidInstrumentedTest）；但 `AndroidEnhancedPlaybackSession` 在 **0** 个测试文件中被引用，`AndroidNativeDirectYPlayer` 仅 2 处（且为插桩或已抽出的策略），`AndroidYCoreHttpProxy` 8 处（较好——针对纯函数）。3,000 行级类体因 Android/JNI 依赖无法直接单测，只有抽出的自由函数可测（如 `androidUnitTest/.../NativeDirectCommandPolicyTest.kt`）。33 个插桩测试需真机。
- **建议**：持续抽取纯策略函数并补 JVM 测试；对 `core2/android` 逻辑设覆盖率闸门。

### M5【高】版本同步靠人工，无 CI/pre-commit 闸门

- **现状**：`AGENTS.md:7-13` 要求 `version.properties` 与 `release-notes.txt` 首行同步；`scripts/release_metadata.py:10-29` 只在构建期校验首行相等。`AGENTS.md:9` 写"当前 1.1.7"，而 `version.properties` 是 `VERSION_NAME=1.3.4`。另 `tvApp/build.gradle.kts:63` 用 `tvVersionCode = phoneVersionCode * 10 + 1` 耦合两个壳。
- **影响**：每次发布都有人为失误面；正文/版本漂移与"是否真的更新了说明"无校验；文档内硬编码版本号必漂。
- **建议**：加 CI/pre-commit 调用 `release_metadata.py`；校验每个版本存在对应说明条目；让 `bumpVersion` 成为唯一写入者；从 `AGENTS.md` 移除硬编码版本。

### M6【中】文档漂移，无权威架构文档

- **现状**：`docs/` 59 个带日期 `.md` 无索引；两份并存架构文档（`YCORE_ARCHITECTURE.md` 8/17 vs `YCORE2_ARCHITECTURE.md` 9/17）；`README.md:5` 说"底部四个 tab"，而 `app/RootComponent.kt:35` 说"五个常驻 tab"、`:51` 枚举 `Tab { Home, Browse, Watching, Servers, Search, Profile }`（6 值，1.3.3 新增"观看"）。
- **建议**：确立 `YCORE2_ARCHITECTURE.md` 为记录，加 `docs/README.md` 索引，修正 README。

### M7【中】技术债是"沉默"的，没有标注

- **现状**：应用代码中仅 7 处 `TODO` + 1 处 `FIXME`（本仓实测 0 处 TODO/FIXME 于 `composeApp/src`+`tvApp/src` 的检索结果），但有 **105 处 `@Suppress`**（54 处 `unused`、18 处 `DEPRECATION`）、11 处 `@Deprecated`。`@Suppress("unused")` 在掩盖死代码。
- **建议**：把 `unused` 抑制转为删除或带移除目标的 `@Deprecated(ReplaceWith=...)`。

### M8【中】其他上帝文件（UI/状态层）

- `feature/servers/ServersTabScreen.kt` 2,382（25+ 私有 composable，对话框内联 `:1361-2382`）、`feature/calendar/CalendarScreen.kt` 2,158、`feature/home/HomeScreen.kt` 2,000、`DetailExecutor.kt` 1,854、`PlayerStore.kt` 1,782、`ProfileScreen.kt` 1,763、`SearchScreen.kt` 1,611。
- **建议**：一个对话框/区块一个文件。

### M9【低】`lint.xml` 关闭新鲜度检查且无复核日期

- **现状**：忽略 `AndroidGradlePluginVersion`、`GradleDependency`、`NewerVersionAvailable`、`UseKtx`、`ChromeOsAbiSupport`、`InsecureBaseConfiguration`。前几项由供应链流程覆盖，尚可；`InsecureBaseConfiguration` 与 S2 直接相关。
- **建议**：补日期注释与到期复核；与 S2 一起处理。

---

## 6. 服务端与后端（watchTogetherServer / protocol / backendClient）

### SV1【高】房间状态纯内存、无定时清理、全局锁、单 SQLite 连接

- **现状**：`RoomStore`(`WatchRoomStore.kt:35`) 与 `RemoteControlRelay`(`RemoteControlRelay.kt:236-239`) 为进程内 `ConcurrentHashMap`/`mutableMap`；过期空房仅在 `hello`/`createRoom` 时机会式清扫（`WatchRoomStore.kt:58`、`Application.kt:565`），无调度器。每次加入都取同一把 `creationLock`(`WatchRoomStore.kt:36,44,57`) 并先 `sweepExpiredRooms()` 遍历全部房间（`:109-118`），使每次加入为 O(房间数) 且全局串行。账号侧全部操作共用一把 `lock` 包一个 JDBC `Connection`（`AccountStore.kt:132-140`）。
- **影响**：单节点硬上限（`MAX_ROOMS=500`、`ACCOUNT_MAX_USERS=100`）；任何慢查询阻塞登录；无法水平扩展（迁移中继为单写 SQLite，`MigrationRelayBackend.kt:359-364`）。
- **建议**：加周期性 `appScope` 清扫并明确单节点约束；去掉全局锁、以 `rooms.remove(code, room)` 原子回收 + 房间级监视器；账号侧用小连接池或 WAL 只读连接。

### SV2【中】Playwright 渲染器：SSRF 面 + `--no-sandbox` + 示例 token 可用

- **现状**：`network-policy.mjs:24-53` 仅拦截**字面**私网主机，域名解析到 `10.x`/元数据地址可绕过，且允许 `http:` 子资源；Chromium 以 `--no-sandbox` 运行（`server.mjs:99`）。`renderer.environment.example:1` 是 44 字符占位串，而 `server.mjs:25-27` 接受任意 ≥24 字符 token——照抄示例即得到公开已知 bearer。
- **影响**：允许主机上的恶意帖子可驱动对内请求；示例 token 若未替换即形成鉴权旁路。
- **建议**：请求时解析并校验目标 IP（或出口防火墙）、子资源强制 `https`；启动时拒绝已知占位 token、要求生成随机 token。

### SV3【中】同步锚点使用墙钟时间

- **现状**：`anchorAtServerMs = System.currentTimeMillis()`（`Application.kt:775`、`WatchRoomModel.kt:14`）。
- **影响**：NTP 校时会平移所有客户端的推演。
- **建议**：增量用单调时钟基准，墙钟仅用于显示。

### SV4【低-中】待认证槽位可被慢连接占用约 40 秒

- **现状**：`WatchAccountAuthentication.kt:110-119` 最多重试 8 次瞬时失败，抖动退避至 5 s（`:19-21`），全程持有租约；`WatchConnectionGate.kt:20-21` 的 pending 池很小。
- **建议**：限制握手总墙钟时间（如 10 s）而非重试次数。

### SV5【低】幂等缺口

- `Application.kt:1287-1299` 聊天去重依赖 `clientMessageId` 仍在 50 条 `chatHistory` 内，淘汰后重试会重复；`Application.kt:650` 在任何已解析房间上 `joinFailureLimiter.clear`（先于 `validateJoin`），一个已知 code 即可重置惩罚。

### 已确认做得好的服务端实践

- 观看向 WebSocket 需账号 Bearer（`Application.kt:397-425`）；成员资格绑定不可变账号 id（`WatchRoomAdmission.kt:25-48`）；host/resume 用 32 字节能力值 + `MessageDigest.isEqual`（`WatchRoomModel.kt:174-201`）；`X-Forwarded-*` 仅信任 loopback（`WatchTransport.kt:18-42`）；无 CORS 插件；逐消息强校验（`WatchProtocol.kt:365-556`）；消息/聊天/反应/加入/连接均有限流；metrics token 常量时间比较（`WatchMetrics.kt:30`）。
- 协议版本化健全（`WatchProtocol.VERSION=6`、`MIN_SUPPORTED_VERSION=5`、`:169-231`）；账号侧刷新令牌族吊销与重放防护到位（`AccountStore.kt:771-795`）。
- 客户端 `AccountApi` 强制 `https://` 与同源校验（`AccountApi.kt:73-76`），设置超时，失败以 `AccountApiException` 上抛并有 legacy 刷新回退；refresh token 经 Android Keystore 支撑的 `SecureStore` 持久化（`AccountRepository.kt:903`）。
- systemd 加固优秀（`yfuse-watch.service:36-52`）；备份为在线 `.backup` + 完整性检查 + 异地 rsync + 14 天保留（`yfuse-backup.sh`）。
- 37 个服务端测试不浅：覆盖连接闸门限流与幂等释放（`WatchTogetherServerTest.kt:92-170`）、吊销看门狗、瞬时过载重试退避（`:218-499`）、畸形/未知/二进制载荷（`:1372-1446`）、代理头信任（`:2020`）、房间/IP 配额（`:1603-2019`）、并发邀请/刷新胜者、628 行遥控套件。缺口：无多实例测试、无慢接收方广播超时测试、渲染器 SSRF 仅单测无集成测试。

---

## 7. 工程、构建与仓库卫生

### E1【高】构建脚本与 CI 复杂且逻辑重复

- **现状**：`composeApp/build.gradle.kts` 1,424 行，含 8 个自定义校验任务（`verifyDesignSystemUsage:69-205`、`verifyCustomMpvArtifact`、`verifyStandaloneYCoreArtifact`、`verifyYCoreGpuCompanionArtifact`、`verifyMdkArtifact`、`verifyMediaTestManifest`、`verifyBehavioralTestBoundaries`），多个用正则扫源码。`publish-android.yml` **1,512 行/65 KB**（13 个 workflow 共 3,695 行）。发布逻辑（读版本、dirty/diff 哈希、签名、SHA-256）在 `scripts/build-release-packages.ps1:10-40` 与 `publish-update.ps1:54-56` 中重实现。
- **影响**：脆弱、难演进；本地与 CI 发布逻辑易漂移。
- **建议**：抽到 `buildSrc`/convention plugin；发布步骤收敛为一个脚本，本地与 CI 共用。

### E2【中】第三套 UI 重实现（HarmonyOS / 仓颉）

- **现状（实测修正）**：`harmonyApp` 并非空壳——含 **55 个 `.cj` 文件、5,270 行**仓颉源码，覆盖 player/network/storage/sync/ui/watch 等，另有 4 个测试文件与 `harmony-source-gates.yml`。此前有材料称其为"空壳"，实测不成立。
- **影响**：这是继手机 Compose、TV Compose 之后的**第三套并行 UI/播放实现**，且与 Kotlin 侧无共享代码；长期维护成本与行为分叉风险显著。
- **建议**：明确其定位（实验/战略），设定与主线的同步机制与验收标准，或明确冻结，避免三线并行而无治理。

### E3【中】仓库其他卫生问题

- 根目录 26 个 `Yfuse-*.apk`（约 613 MB）——未跟踪（`.gitignore:21` 已覆盖），但仍占磁盘，建议移出树。
- `artifacts/`、`artifacts-local/`、`build/`、`tmp/`、`audit/`（数千文件）散落根目录；`.gitignore:52-53` 已忽略 `artifacts*`，但 audit 的 `.kt`/二进制仍在库内（见 M1）。
- 分支堆积：`agent/*`、`codex/*` 大量长期分支。
- `watchTogetherServer/calendarRenderer/node_modules/` 未跟踪（正确），但 `calendarRenderer` 目录本体 19 MB 在磁盘。
- `watchTogetherServer/build.gradle.kts:17` 内联硬编码 `org.xerial:sqlite-jdbc:3.53.3.0`，未走 version catalog。

### E4【低】脚本堆积

- `scripts/` 有 52 `.py` + 25 `.sh` + 11 `.cpp`（+13 python 测试）；原生脚本命名重叠：`build-ycore-native.sh`、`install-ycore-native.sh`、`verify-ycore-native.sh`、`verify-ycore-native-apk.sh`、`verify-ycore-native-aar`。
- **建议**：合并为带子命令的单一 CLI。

---

## 8. 建议改进路线图

### 立即（0–2 周）— 安全与仓库健康
1. **S1** 移除 APK 内嵌 TMDb token，统一走服务端代理。
2. **S2** `network_security_config.xml` 默认改为禁止明文，媒体源走按域白名单。
3. **M1** `git filter-repo` 清除 `audit/` 中的源码副本与二进制，补 `.gitignore`。
4. **S4** 强制非空 Ed25519 更新清单公钥；**S5** HTTP 虚主机 301 到 HTTPS。
5. **S7** 容器绑定 loopback + `HEALTHCHECK`。

### 短期（1–2 月）— 架构收敛与性能
6. **A1** 把 `phoneShared`/`tvShared` 变成有物理 `src/` 的真实模块，区分 namespace，消除反向依赖。
7. **A2/A3** 收敛到单一 `YPlayer` 契约；发布 `core`/`core2` Legacy 退役清单并删除反向适配器。
8. **A4/M2** 抽取共享 session loop 与 fallback policy，拆分四个大引擎；拆分 `AndroidYCoreHttpProxy`；**拆分后下调** `function-size-baseline.txt` 阈值。
9. **P1/P2/P3/P4** 修读路径/音频/传输的分配热点，替换 2 ms 轮询泵，`runBlocking` 改 suspend，媒体 IO 用受限 dispatcher。
10. **SV1/SV2** 服务端加周期清扫、去全局锁、连接池；渲染器补 SSRF 解析校验与 token 强校验。
11. **M5** 加版本同步 CI/pre-commit 闸门，移除 `AGENTS.md` 硬编码版本。

### 中期（1–2 季度）— 可维护性与扩展性
12. **M3/E2** 统一手机/TV（并评估鸿蒙）共享层，减少并行 UI 实现。
13. **A5/A6/A7/A9** 依赖注入下沉、UI 去业务逻辑、后端真隔离、store 拆分与单例生命周期审计。
14. **M4** 为 `core2/android` 抽纯策略函数并设覆盖率闸门；**P8/P9** 生成稳定性报告、扩充基准指标。
15. **E1/E3/E4** 构建脚本抽 `buildSrc`、发布逻辑单一化、脚本合并、产物移出树、清理长期分支。

---

## 9. 亮点（应保持）

- 供应链：依赖锁定 + 统一 `security-overrides.properties` + 原生 AAR SHA-256 校验。
- 更新链路：无未验证安装路径、不可降级、签名/包名/哈希多重校验。
- 无应用内 WebView/JS 桥，Cast 接收器无 XSS sink。
- 原生会话路由边界拷贝、空指针与 ABI 校验、速度有限边界。
- 日志脱敏 + 默认关闭 logcat + 服务端请求日志省略 query。
- 服务端鉴权/限流/PBKDF2/常量时间比较/systemd 加固/在线备份。
- 图片加载与取色有界、列表 key 规范、播放线程模型合理。
- 设计系统与行为测试边界的构建期自动校验（理念正确，只是实现过重）。

---

## 附录：关键证据索引

| 项 | 位置 / 命令 |
|---|---|
| TMDb token 内嵌 | `composeApp/build.gradle.kts:767-773,948`；`core/network/Tmdb.android.kt:5` |
| 明文流量 | `composeApp/src/androidMain/res/xml/network_security_config.xml:7,15` |
| 裸 IP 后端 | `yfuseBackendClient/.../BackendEndpoints.kt:5` |
| 虚拟共享模块 | `phoneShared/build.gradle.kts:24,36`；`tvShared/build.gradle.kts:24,37` |
| 双播放契约 | `feature/player/contract/VideoEngine.kt:375`；`core2/api/YPlayer.kt:13`；`core2/legacy/*Adapter.kt:33,38` |
| 函数尺寸基线 | `config/function-size-baseline.txt`（`runLoop 755/358`、`prepareCurrent` 重复） |
| 2 ms 轮询泵 | `AndroidNativeDirectYPlayer.kt:3425`；`AndroidNativeEnhancedYPlayer.kt:1298`；`AndroidNativeTunnelYPlayer.kt:645` |
| 代理 runBlocking | `AndroidYCoreHttpProxy.kt:1810,1905` |
| 读路径分配 | `AndroidFfmpegDemuxer.kt:200-222`；`FfmpegNativeBridge.kt:334-335,534` |
| 音频直缓冲 | `AndroidNativeDirectYPlayer.kt:2627-2631` |
| 服务端锁/单连接 | `WatchRoomStore.kt:36,44,109`；`AccountStore.kt:132-140` |
| 渲染器 SSRF | `network-policy.mjs:24-53`；`server.mjs:25,99`；`renderer.environment.example:1` |
| 仓库体量 | `du -sh .git` = 1.7G；`git ls-files audit` = 12,296（7,664 `.kt`） |
| CI 体量 | `.github/workflows/publish-android.yml` 1,512 行；`composeApp/build.gradle.kts` 1,424 行 |
| 鸿蒙实现 | `harmonyApp/entry/src/main/cangjie/**`（55 `.cj` / 5,270 行） |

> 说明：本报告结论基于静态走查与仓库实测，未执行真机播放/功耗验证。涉及运行时性能的条目（P1–P7）建议在 `macrobenchmark` 与真机 trace 上复测后再确认收益量级。
