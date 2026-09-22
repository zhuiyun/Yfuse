# Yfuse 项目全面体检报告

检查日期：2026-09-11
检查对象：`D:\Demo\Yfuse` 工作区，当前提交 `04dc5086`（"Refine playback and UI motion; add Aurora logos [artifact only]"）
基线对照：上次体检 [audit/project-health-20260907/REPORT.md](../project-health-20260907/REPORT.md)
本次性质：**只读诊断**。未修改任何业务代码、未改动版本配置、未打包、未发布、未推送。

---

## 0. 结论摘要

项目工程化水平明显高于同类个人项目：CI 门禁齐全（ktlint 基线、Android Lint、CodeQL、供应链扫描、来源边界校验、TV 产物校验），依赖锁定 + 安全覆盖机制、SBOM、签名校验均已落地，且**上次体检的 P1（会话恢复误删）已修复**。

但本次复检发现 **1 条新的 CRITICAL 级依赖漏洞**（上次扫描时不存在），另有若干架构性技术债与若干项待真机验证的性能风险。也存在 **3 份未纳入版本控制的完整项目副本**（约数百 MB），属于明确的仓库卫生问题。

| 维度 | 评级 | 一句话 |
|---|---|---|
| 整体结构 / 构建工程化 | 🟢 优秀 | 多模块清晰，依赖锁定、门禁、SBOM 齐全 |
| 代码质量与规范 | 🟡 中等偏上 | 无 TODO 债、无 GlobalScope，但存在超大文件/函数与 1,155 条基线豁免 |
| 性能 | 🟡 中等 | 无致命瓶颈，但存在无界通道、忙等、runBlocking 边界风险 |
| 安全 | 🟡 中等偏上 | 无 WebView/弱加密/凭据泄漏，但明文 HTTP 全局放开 + 更新公钥为空的本地默认 |
| 依赖健康 | 🔴 需立即处理 | 1 CRITICAL + 1 HIGH + 多条 MODERATE；部分依赖明显落后上游 |
| 可维护性 | 🟡 中等 | 单文件最大 3,452 行，`runLoop()` 三处疑似复制粘贴 |
| 可测试性 | 🟢 良好 | 2,240+ 测试、结构清晰；但 mdkAndroid 与 TV 覆盖薄弱 |

---

## 1. 整体结构与技术栈概览

### 1.1 模块划分（`settings.gradle.kts`）

| 模块 | 角色 | Kotlin 文件 | Kotlin 行数 |
|---|---|---|---|
| `composeApp` | Android 手机主应用（KMP，commonMain + androidMain） | 1,269 | 259,856 |
| `tvApp` | Android TV 独立应用（复用 composeApp 树） | 23 | 8,186 |
| `watchTogetherServer` | Ktor 一起看服务端（JVM） | 57 | 21,513 |
| `watchTogetherProtocol` | 客户端/服务端共享协议 | 4 | 595 |
| `macrobenchmark` | 启动与帧时间基准测试 | 5 | 212 |
| `mdkAndroid` | MDK 原生播放器封装 | 0（含 C++/JNI） | — |
| `harmonyApp` | HarmonyOS 移植（仓颉/C++） | 0（含 `.cj`/`.cpp`） | — |

源码合计约 **1,358 个 Kotlin 文件 / 约 49,000 行**（不含生成物）。另有原生层：`ycore-native/`、`mdkAndroid/src/main/cpp`、`scripts/native/`（C/C++/JNI）。

模块拆分决策在 `settings.gradle.kts:33-37` 有明确注释说明——TV 保持独立 application 而非转成 Android library，理由是 KMP 应用不可被消费、且本地 native AAR 依赖在 AAR 内会失效。**这是一个有据可依的合理取舍。**

### 1.2 技术栈

- **语言/构建**：Kotlin 2.2.21、AGP 8.11.1、Gradle Kotlin DSL、JDK 17
- **UI**：Compose Multiplatform 1.8.2、AndroidX Activity/Lifecycle、Navigation3
- **架构**：Decompose 3.5.0 + MVIKotlin 4.4.0（组件化 + 单向数据流）
- **网络**：Ktor 3.0.3（客户端 OkHttp 引擎 / 服务端 CIO）、OkHttp 4.12.0、jcifs-ng（SMB）
- **播放**：Media3/ExoPlayer 1.9.0 + 自研 YCore/MPV/MDK 多内核
- **DI/序列化**：Koin 4.0.0、kotlinx-serialization 1.7.3
- **质量工具**：ktlint 1.3.1（插件 14.2.0）、Android Lint、CodeQL、OSV 供应链扫描

### 1.3 构建与供应链工程化（亮点）

- `build.gradle.kts:36-105`：所有子模块统一施加 ktlint，**并用注释解释了为什么放在根项目而不是逐模块 apply**。
- `build.gradle.kts:40-70`：集中式依赖安全覆盖（Netty / Protobuf / Wire），并说明每一个 `because` 的原因。
- `build.gradle.kts:72-82`：`lockAllConfigurations()` + 安全覆盖依赖豁免锁定的精细处理。
- `scripts/security-overrides.properties` 被构建与扫描脚本共同读取，保证 SBOM 与实际解析一致——**设计意图正确**。

---

## 2. 代码质量与规范性问题

### 2.1 值得肯定的部分（已核实）

| 项目 | 结果 |
|---|---|
| `TODO/FIXME/HACK/XXX` 注释 | **0**（三种独立正则交叉验证，177 个 "todo" 命中均为 `.toDouble()` 误报） |
| `GlobalScope` | **0** |
| 空 `catch {}` | **0** |
| `@Deprecated` | **0** |
| 顶层可变全局状态（`MutableStateFlow/List/Map`） | **0** |
| `var` / `val` 比例 | 2,330 / 26,348 = **8.8%**，纪律良好 |
| 注释掉的死代码 | 仅 7 行 |

### 2.2 已确认问题

**[P1] 1,155 条 ktlint 违规被封存在基线中**

| 基线文件 | `<file>` 条目 | 违规条目 |
|---|---|---|
| `config/ktlint/composeApp-baseline.xml` | 124 | **701** |
| `config/ktlint/watchTogetherServer-baseline.xml` | 8 | **449** |
| `config/ktlint/tvApp-baseline.xml` | 2 | 5 |
| `mdkAndroid` / `watchTogetherProtocol` | 0 | 0（干净） |

`composeApp` 主导规则：`chain-method-continuation` 161、**`property-naming` 143**、`function-signature` 88、`argument-list-wrapping` 73、`indent` 54。

影响：`property-naming` 与 `function-signature` 这两类不是纯格式问题，它们掩盖的是**真实的命名与 API 一致性缺陷**。CI 只做"不新增违规"的非回归校验，因此这 1,155 条会永久存在。

**[P2] 超大文件与超大函数**

| 行数 | 文件 |
|---|---|
| 3,452 | `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt` |
| 3,261 | `composeApp/.../core2/android/AndroidNativeDirectYPlayer.kt` |
| 2,879 | `watchTogetherServer/.../watch/CalendarIngestion.kt` |
| 2,311 | `composeApp/.../core2/android/AndroidAdaptiveCore2YPlayer.kt` |
| 2,302 | `watchTogetherServer/.../watch/Application.kt` |
| 2,241 | `composeApp/src/commonMain/.../servers/ServersTabScreen.kt` |
| 2,187 | `composeApp/.../feature/player/MpvVideoEngine.kt` |
| 2,132 | `composeApp/.../update/AppUpdateManager.kt` |

**超过 100 行的函数共 191 个。** 最严重的是 `PlayerRoot.kt:118 internal fun PlayerRoot(`——该 composable 约 3,259 行，几乎等于整个文件，内部以嵌套闭包形式内联了 `fun stage`、`selectVersion`、`loadCastItem` 等。其余如 `AndroidAdaptiveCore2YPlayer.kt:455 runLoop()`（约 1,540 行）、`DetailScreen.kt:132 DetailScreen(...)`（约 944 行）、`PlayerStore.kt:776 load()`（约 583 行）。

影响：这些单元**无法被独立测试**，合并冲突面极大，状态耦合隐式。`PlayerRoot()` 一个 composable 承载 3,259 行是当前最大的可维护性风险点。

**[P2] 疑似三处复制粘贴的 `runLoop()`**

`AndroidAdaptiveCore2YPlayer.kt:455`、`AndroidNativeEnhancedYPlayer.kt:329`、`AndroidNativeTunnelYPlayer.kt:230` 三处存在高度相似的 `private suspend fun runLoop()`。**未逐行 diff，标记为待确认**，但从命名、签名与体量看极可能是同一逻辑的三份拷贝——意味着任何播放逻辑修复需改三处，漏改一处即产生行为不一致。

**[P2] 异常处理过于宽泛**

| 模式 | 数量 | 评价 |
|---|---|---|
| `catch (e: Exception)` | 60 | 过宽 |
| `catch (e: Throwable)` | **112** | 过宽，合计 **172** |
| 仅记日志的宽泛 catch | 31 | 吞掉异常 |
| `catch (_: Exception) { }` 完全丢弃 | 9 | 最强"静默失败"证据 |
| `runCatching` | 841 | 重度使用，部分与 `.getOrNull()`（478 次）组合会静默丢弃失败 |

典型样本：`AndroidNativeDirectYPlayer.kt:2179 } catch (_: Exception) {`（该文件内 3 处同类丢弃）。影响：**掩盖根因**，尤其在播放器这种多内核、多设备形态的场景下，异常丢失会让线上问题不可归因。

**[P3] 非空断言 `!!` 共 119 处**，非测试热点集中在 `watchTogetherServer/.../Application.kt`（11 处）、`CalendarIngestion.kt`（4 处）、`commonMain/.../sync/WatchRoomPlaylist.kt`（4 处）。样本：`Application.kt:1222 anchorPositionMs = message.positionMs!!`——若客户端发来不完整消息即抛异常。服务端应对外部输入做防御性校验，而非 `!!`。

**[P3] `@Suppress` 共 99 处**：`unused` 52、`DEPRECATION` 14，其余零散。数量本身可接受，但 14 处 `DEPRECATION` 抑制值得专项清理。

### 2.3 架构约束的执行情况

已确认在 CI 中强制执行的规则：
- `scripts/verify-module-boundaries.py`（`:quality-gates-v2.yml:54`）：禁止手机模块引用 `com.yfuse.tv.ui`
- `scripts/verify-tv-source.py` / `verify-tv-artifact.py`：TV 权限与产物级校验（防御继承手机权限）
- `composeApp:verifyDesignSystemUsage`：源码级禁止裸排版/圆角/固定色值

**评价**：规则正确且在 CI 中真实生效，但**覆盖面偏窄**——没有任何规则阻止"跨播放器复制 `runLoop()`"或"单文件超过 N 行"这类对可维护性伤害最大的模式。

---

## 3. 性能瓶颈与安全风险

### 3.1 性能（已确认）

| 级别 | 问题 | 位置 | 影响 |
|---|---|---|---|
| **P1** | **播放器命令通道无界** | `AndroidAdaptiveCore2YPlayer.kt:128`、`AndroidNativeDirectYPlayer.kt:119`、`AndroidNativeEnhancedYPlayer.kt:94`、`AndroidNativeTunnelYPlayer.kt:74`——四处均为 `Channel<Command>(Channel.UNLIMITED)` | 消费端（解码线程）若停滞，seek/setSurface 命令无背压累积，**内存无上界增长**。这是本次性能层面最明确的风险 |
| **P2** | 忙等轮询 | `AndroidYCoreBlockCache.kt:320-326` `while (nanoTime < deadline) { ... Thread.sleep(1L) }` | 1ms 轮询在缓存写线程上持续唤醒。**已确认有界且不在主线程**（经 `AndroidTransportMediaDataSource.kt:1089` 调用），代价是 CPU 唤醒而非卡顿，属中等 |
| **P2** | 同步桥接边界使用 `runBlocking` | `AndroidTransportMediaDataSource.kt:618,925,1110,1324`、`AndroidMediaExtractorDemuxNode.kt:364`、`AndroidForwardCacheWarmer.kt:40,80,101`、`AndroidRangeReadWatchdog.kt:57` | 用于把 suspend 的 close/read 桥接进同步 native 回调。**若任一路径落在 Media3/解码回调线程上会造成解码线程停顿**——需运行时线程追踪确认，**标记为待验证** |
| **P2** | 播放器特性区 6 处 `collect`、0 处 `collectLatest` | `commonMain/.../feature/player` | 快速拖动进度时，前一次状态处理不会取消，产生冗余工作。计数已确认，**实际影响待性能剖析** |
| **P2** | `Application.onCreate` 内联构建完整 Koin 图 | `YfuseApp.kt:114-139`，另有 `:67-68,105-111,157` 多处 `initialize(this)` | 每次冷启动都要付出建图成本。已有缓解：`DeferredAppStartup`（`:141-153`）延后重活，StrictMode 仅 debug 生效 |
| **P3** | `stateIn` 使用 `SharingStarted.Eagerly` | `ServersTabComponent.kt:92` | 上游在组件整个生命周期内保持活跃，即使 UI 未观察。组件作用域较窄，影响有限 |

**已确认无问题（避免过度告警）**：
- 广播接收器 `registerReceiver` 均与 `unregisterReceiver` 成对，且使用 `RECEIVER_NOT_EXPORTED`（`PlayerActivity.kt:1472,1562` / `:1000-1016`）
- 图片缓存有界：`YfuseApp.kt:265-275` 内存缓存 `maxSizePercent(0.20)` + 磁盘缓存 256MB
- 无 `GlobalScope`、无空 catch，不存在明显的协程泄漏模式

### 3.2 安全（已确认）

| 级别 | 问题 | 位置 | 说明 |
|---|---|---|---|
| **P1** | **更新清单公钥默认为空 → 签名校验在本地构建下"失败开放"** | `gradle.properties:31` `yfuse.updateManifestPublicKey=`；`AppUpdateManager.kt:213-215` `if (key.isEmpty()) return UnverifiedNoKey`；`:227` 该状态不被拒绝 | 上次体检标记的 P1 **仍未闭环**。当前设计改为"CI 从受保护 secret 派生并嵌入"，因此**正式流水线包应当有公钥**，但**仓库默认值仍为空**，本地/自建构建会静默接受未签名 manifest，完整性只剩同一 manifest 内自述的 APK 证书 + SHA-256 兜底。验证代码本身（`PlatformSignature.android.kt`）实现正确 |
| **P1** | **明文 HTTP 全局放开** | `composeApp/.../res/xml/network_security_config.xml:3` `<base-config cleartextTrafficPermitted="true" />`；并经 `tvApp/build.gradle.kts:200` 复用进 TV | 对局域网媒体服务器（Emby/Plex/WebDAV/SMB）而言是**有意的产品取舍**，但**未收敛到私有网段**：理论上任意主机的明文流量都被允许，同网段可 MITM 拦截凭据 |
| **P2** | 真实签名密钥以明文存放于工作树 | `keystore.properties`（含非占位符的真实密码）与 `signing/yfuse-release.jks` | **已确认未被 git 跟踪**：`git ls-files` 无匹配、`.gitignore:32-34` 覆盖、`git log --all` 无历史 blob。CI 从 GitHub Secret 读取。**风险限于本地磁盘明文存放**，非泄漏事件 |
| **P2** | 凭据落盘于普通 SharedPreferences | `CredentialPersistingSettings.kt:5-10`、`YfuseApp.kt:98` | 已有缓解：敏感值走 `AndroidKeystoreSecureStore`（AES-GCM + Keystore），且 `allowBackup="false"` + 完整排除规则 |
| **P3** | 导出组件与深链 | `AndroidManifest.xml:72` `MainActivity exported=true`、`:86-91` `yfuse://watch/*`、`:103-155` 五个导出 launcher 别名 | 攻击面存在但已核实缓解：`WatchInvite` 限制长度（`MAX_LINK_CHARS=1024`）、严格 `MEDIA_KEY_SHAPE` 正则、拒绝非官方端点、邀请载荷用后即清（`MainActivity.kt:288`） |

**已确认安全的部分（重要，避免误判"全是问题"）**：
- **无 WebView / JS 桥**：全项目源码 grep 无 `addJavascriptInterface`、`setAllowFileAccess`、`setJavaScriptEnabled`；`castReceiver/receiver.js` 无 `eval`/`innerHTML`
- **无弱加密**：真实源码中 grep 无 MD5/SHA1/DES/ECB，`PlatformCrypto.android.kt:17` 使用 `SecureRandom()`，Keystore 存储为 `AES/GCM`
- **无凭据日志**：无 `Log.*(token|password|secret|Bearer)` 命中，且有 `SafeLogcatOutputGate` 统一管控
- **服务端暴露面收敛**：未发现未认证的 admin 或状态变更路由；`/watch/metrics`（`Application.kt:610`）使用常量时间 Bearer 校验、无 token 时退化为仅回环；WebSocket 强制安全传输 + 认证（`:408` `requireWatchAuthentication=true`）；`staticFiles` 使用 Ktor 内建遍历防护；未安装 CORS 插件
- **SMB**：`AndroidSmbMediaTransport.kt:91-94` 已禁用 SMB1 并限制最低 SMB202

---

## 4. 依赖健康状况

### 4.1 扫描结果（本次重跑，2026-09-11）

用仓库自带 `scripts/supply_chain_check.py` 扫描提交的锁文件：**扫描 765 个 Maven 依赖坐标，OSV 返回 10 条活跃漏洞记录（0 条已撤回）**，脚本以退出码 1 失败——即**当前仓库状态无法通过自带的安全门禁**。

| 严重度 | 依赖 | 漏洞编号 | 修复版本 | 用途 |
|---|---|---|---|---|
| 🔴 **CRITICAL** | `io.netty:netty-handler:4.1.136.Final` | GHSA-c4c3-7fpv-j4q5（CVSS 4.0：AV:N/AC:L/VC:H/VI:H） | **4.1.137.Final** | 工具链（unified test platform） |
| 🟠 **HIGH** | `org.bouncycastle:bcpg-jdk18on:1.80` | GHSA-cj8j-37rh-8475（可用性 DoS） | **1.84** | 工具/测试配置 |
| 🟡 MODERATE | `io.netty:netty-handler:4.1.136.Final` | GHSA-fccg-mwvh-qqg4（二次握手重组 DoS） | 4.1.137.Final | 工具链 |
| 🟡 MODERATE | `io.netty:netty-codec-http:4.1.136.Final` | GHSA-8c42-7qj2-3j46（CORS Vary 头覆写 → 缓存投毒/信息泄露） | 4.1.137.Final | 工具链 |
| 🟡 MODERATE | `org.bouncycastle:bcpkix-jdk18on:1.80` | GHSA-wg6q-6289-32hp（使用破损/Risky 加密算法） | **1.84** | 工具/测试配置 |
| 🟡 MODERATE | `io.opentelemetry:opentelemetry-api:1.41.0` | GHSA-rcgg-9c38-7xpx（W3C Baggage 无界内存分配） | 1.62.0 | 工具配置 |
| 🟡 MODERATE ×3 | `ch.qos.logback:logback-core:1.3.14` | GHSA-25qh-j22f-pwp8、GHSA-pr98-23f8-jwxv（+ EL 注入） | 1.3.15/1.3.16 | ktlint 工具配置 |
| 🟡 LOW ×3 | `ch.qos.logback:logback-core:1.3.14` | GHSA-6v67-2wr5-gvf4、GHSA-jhq6-gfmj-v8fx、GHSA-p47f-322f-whfh、GHSA-qqpg-mvqg-649v | 1.3.16+ | ktlint 工具配置 |

### 4.2 关键判断：这条 CRITICAL 的实际暴露面（重要）

我核实了漏洞依赖的真实归属，避免夸大风险：

- `io.netty:netty-handler` **只出现在 `_internal-unified-test-platform-*` 配置中**（`composeApp/gradle.lockfile:417-418` 等），即 **Android 统一测试平台/构建工具的内部依赖**。
- `watchTogetherServer` 声明的是 `ktor-server-cio`（`watchTogetherServer/build.gradle.kts:15`），**不使用 Netty**。
- 我从实际发布包 `artifacts/releases/search-field-1.0.55-217/Yfuse-1.0.55-217-search-field-full-arm64-signed.apk` 中确认：仅 `classes.dex`/`classes2.dex` + 20 个 `.so`，**Netty 不会进入交付 APK**。

**结论**：该 CRITICAL 目前**不构成面向终端用户的远程可利用漏洞**，而是**构建/测试供应链风险**（若 CI 执行不可信测试代码或在不可信网络下解析依赖，则可能被利用）。**但门禁已红**，且修复成本极低——只需把版本从 4.1.136.Final 抬到 4.1.137.Final。

**注意**：项目在 `build.gradle.kts:47-50` 已经建立了 Netty 安全覆盖机制，其注释写着"4.1.136.Final 之前存在高危 DoS"——**这说明维护者一直在跟踪，只是固定的版本号已被新披露赶超**。这说明机制有效，需要的是刷新常量而非改架构。

### 4.3 依赖新鲜度（相对上游 Maven Central，2026-09-11 实测）

| 依赖 | 当前 | 上游最新 | 差距 |
|---|---|---|---|
| `io.ktor:ktor-client-core` | 3.0.3 | **3.5.2** | 落后 2 个小版本线（含安全修复） |
| `io.opentelemetry:opentelemetry-api` | 1.41.0 | **1.65.0** | 严重落后（工具依赖） |
| `ch.qos.logback:logback-core` | 1.3.14 | **1.6.3** | 严重落后（工具依赖） |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | 5.5.0 | 落后一个大版本（4.x 仍在维护） |
| `io.coil-kt.coil3:coil-compose` | 3.0.4 | 3.6.2 | 落后 |
| `androidx.media3:media3-exoplayer` | 1.9.0 | 1.11.1 | 落后 2 个小版本 |
| `com.russhwolf:multiplatform-settings` | 1.2.0 | 1.3.0 | 落后 1 个小版本 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.11.0 | 1.11.0 | ✅ 最新 |
| `eu.agno3.jcifs:jcifs-ng` | 2.1.10 | 2.1.10 | ✅ 最新 |

**评价**：核心依赖（Kotlin、Coroutines、AGP、Compose）版本管理得当，处于近期；**主要落后集中在工具链依赖**（logback/opentelemetry/netty），这也解释了为何漏洞集中在工具侧。

### 4.4 供应链工程的优点与缺口

**优点**（已核实）：
- 依赖锁文件按模块提交（5 个 `gradle.lockfile`）
- 安全覆盖与应用解析共用同一份 `scripts/security-overrides.properties`，SBOM 反映真实解析结果
- 扫描脚本对"无法解析严重度"的记录默认视为阻断（`UNRESOLVED` ∈ `BLOCKING`）——**安全门禁不会静默通过，设计正确**
- CodeQL 已配置（`codeql.yml`，java-kotlin，`build-mode: none`，含定时周扫）
- GitHub Actions 全部按 SHA 固定（`actions/checkout@d23441a4...`）——供应链防御到位

**缺口**：
- 扫描范围仅覆盖锁文件，**不含** GitHub Actions 依赖、预编译原生播放器产物、设备厂商组件
- `audit/project-health-20260907/sbom.spdx.json` 明确标注为"锁快照，非从正式 APK 提取的精确组件清单"——**缺少交付物级 SBOM**

---

## 5. 可维护性与可测试性评估

### 5.1 可测试性（🟢 良好）

| 模块 | 测试文件数 | 上次实测用例数 |
|---|---|---|
| `composeApp` | 523 | 2,006 通过 |
| `watchTogetherServer` | 24 | 175 通过 |
| `watchTogetherProtocol` | 2 | 8 通过 |
| `tvApp` | **1** | 51 通过 |
| `mdkAndroid` | **0** | 未覆盖 |

**合计 2,240 项用例，0 失败 0 跳过**（2026-09-07 基线）。

优点：测试基数大、`composeApp` 覆盖率可观；CI 在 `quality-gates-v2.yml:114-115` 强制跑 `testDebugUnitTest`。Macrobenchmark 模块已建立（启动/帧时间）但**上次未实际运行测量**。

**缺口（已确认）**：
- `mdkAndroid`（原生播放器封装）**零测试文件**——恰好是风险最高的 JNI/native 边界
- `tvApp` 仅 1 个测试文件，51 项用例远不足以覆盖 TV 遥控焦点/播放路径
- 播放器核心的 191 个超长函数天然不可单测，**测试只能覆盖外围**，真正的播放逻辑依赖真机回归

### 5.2 可维护性（🟡 中等）

**加分项**：
- 文档丰富（`docs/` 31 份，含架构、性能、验证矩阵、评审记录）
- 模块边界与设计令牌由脚本强制
- 关键决策在代码注释中留有"为什么"（如 `settings.gradle.kts:33-37`、`build.gradle.kts:14-23,47-50`）
- 零 TODO 债、零死代码

**减分项**：
- `PlayerRoot.kt`（3,452 行）/`AndroidNativeDirectYPlayer.kt`（3,261 行）等超大文件构成理解与修改门槛
- 疑似三处 `runLoop()` 重复
- 172 处宽泛异常捕获降低可诊断性
- 1,155 条 ktlint 基线豁免（含 143 条 `property-naming`）使命名规范事实上失效

### 5.3 仓库卫生（🔴 明确问题）

工作区存在 **3 份完整的、未被 git 跟踪的项目副本**：

- `.worktrees/`（如 `.worktrees/playback-perf/`）
- `.codex-calendar-optimization/`
- `.codex-tools/`

已确认三者**均未被 git 跟踪**（`git ls-files` 无输出）。它们各自包含完整的 `build.gradle.kts`、`gradle/libs.versions.toml`、`mdkAndroid/src/main/cpp/`、`ycore-native/` 等，**每份都是数百 MB 级的重复内容**，且是本次 grep 时"同一逻辑出现 3 次"噪音的来源。

同时 `git status` 显示 `audit/` 下 **26 个未跟踪目录**（`audit/ambient-power-20260910/`、`audit/motion-*/`、`audit/releases/` 等）——历史审计产物未纳入版本控制，属于工作区噪声。

---

## 6. 优化建议与优先级排序

### P0 — 立即处理（本周内）

| # | 事项 | 依据 | 建议动作 |
|---|---|---|---|
| 1 | **将 Netty 安全覆盖常量抬到 `4.1.137.Final`** | §4.1 CRITICAL，且 `build.gradle.kts:25` 的 `secureNettyVersion` 恰好差一个补丁版 | 修改 `build.gradle.kts:25` → `"4.1.137.Final"`，重新生成锁文件，重跑 `scripts/supply_chain_check.py` 直至退出码 0 |
| 2 | **将 BouncyCastle 覆盖抬到 1.84** | §4.1 HIGH，`security-overrides.properties` 已写 1.84 但锁文件仍解析出 1.80，**说明覆盖未生效或锁文件未刷新** | 核实覆盖是否匹配到 `bcpg-jdk18on`/`bcpkix-jdk18on`，刷新锁文件 |
| 3 | **清理 3 份未跟踪的完整项目副本** | §5.3 | 确认无未提交改动后删除 `.worktrees/`、`.codex-calendar-optimization/`、`.codex-tools/`；**删除前请人工确认**（我未执行任何删除） |

**注**：这三项都是低风险高收益，尤其 #1 只需改一行常量。

### P1 — 短期（本月内）

| # | 事项 | 依据 |
|---|---|---|
| 4 | **为 `Channel.UNLIMITED` 引入背压或容量上限**（4 处播放器命令通道） | §3.1 P1 |
| 5 | **闭环更新清单公钥**：正式流水线强制断言公钥非空并失败即停 | §3.2 P1，上次体检遗留未闭环 |
| 6 | **收敛明文 HTTP 到私有网段**（`cleartextTrafficPermitted` 改为按域白名单或 RFC1918 限制） | §3.2 P1 |
| 7 | **拆分 `PlayerRoot.kt`**：先抽出 `stage`/`selectVersion`/`loadCastItem` 为独立可测单元 | §2.2 P2 |
| 8 | **确认并消除 `runLoop()` 三处重复** | §2.2 P2 |

### P2 — 中期（季度内）

| # | 事项 | 依据 |
|---|---|---|
| 9 | 清理 ktlint 基线中的 **`property-naming`(143) 与 `function-signature`(88)** 两类真实规范问题，其余格式类可继续豁免 | §2.2 |
| 10 | 把 9 处 `catch (_: Exception) {}` 改为至少记录异常类型与上下文；收窄 172 处 `Exception/Throwable` | §2.2 |
| 11 | 为 `mdkAndroid` 补充 JNI 边界测试；扩充 `tvApp` 测试 | §5.1 |
| 12 | 真实运行 Macrobenchmark（启动 + 帧时间），把 `collect`→`collectLatest` 与 `runBlocking` 边界用剖析数据验证 | §3.1 |
| 13 | 升级工具链依赖（logback 1.3.16+、opentelemetry 1.62.0+），并评估 Ktor 3.0.3 → 3.5.x | §4.3 |
| 14 | 生成**交付物级 SBOM**（从正式 APK 提取），而非仅锁文件快照 | §4.4 |

### P3 — 长期

15. 为基础设施约束补脚本门禁：单文件行数上限、单函数行数上限，防止 `PlayerRoot.kt` 类问题复发
16. 清理 99 处 `@Suppress`，优先处理 14 处 `DEPRECATION`
17. 治理 `audit/` 未跟踪产物（归档或纳入版本控制）
18. 服务端 11 处 `!!` 改为对外部输入的防御性校验

---

## 7. 本次未能验证的内容（诚实边界）

以下项目本次**无法验证**，报告中相应结论已标注，不应视为"已通过"：

1. **`runBlocking` 的真实线程影响**——需运行时线程追踪确认 warm/cache/watchdog 路径是否落在解码回调线程
2. **`collect` vs `collectLatest` 的实际性能影响**——需真机拖动进度剖析
3. **`awaitIdle` 忙等的现场命中率**——循环有界，但真实超时发生频率未知
4. **本次未运行完整测试套件**——§1.1/§5.1 的用例数为 2026-09-07 基线，本次仅重跑依赖扫描；未执行 `assembleRelease`、未编译、未跑 lint
5. **正式/CI 构建是否必然注入非空公钥**——从 `gradle.properties:29-30` 注释看 CI 会从 secret 派生，但未实际观察流水线运行
6. **`mdkAndroid/src/main/cpp` 与 `ycore-native/` 的原生代码**——未深入审阅缓冲区大小与线程池配置
7. **HarmonyOS 移植**（`harmonyApp/`）——仅有 `.cj`/`.cpp`，未做完整 SDK 编译或真机验证
8. **`signing/yfuse-release.jks` 内容**——有意未打开，仅确认未被 git 跟踪
9. **`runLoop()` 三处是否真的重复**——未逐行 diff

---

## 8. 与上次体检（2026-09-07）的对比

| 上次结论 | 本次状态 |
|---|---|
| P1：临时安全存储读取失败误删会话 | ✅ **已修复**（会话恢复修复报告） |
| P1：正式包更新公钥为空 | ⚠️ **仍未闭环**，本地默认为空；已改为 CI 从 secret 派生 |
| P2：原生依赖可复现构建依赖补充步骤 | 未复检（超出本次范围） |
| 依赖：0 高危/严重，4 中危 4 低危 | 🔴 **已恶化**：新增 1 CRITICAL + 1 HIGH；漏洞依赖从 3 个增至 5 个 |
| 测试：2,240 项通过，lint 0 error | 未重跑（本次为只读诊断，未执行构建） |

**最重要的变化**：上次扫描后上游新披露了 Netty `GHSA-c4c3-7fpv-j4q5`（CRITICAL）。项目在 4.1.136.Final 处的安全固定**已被追平并超越**——这不是设计缺陷，而是需要定期刷新常量的运维动作。

---

## 附录：本次证据文件

| 文件 | 内容 |
|---|---|
| `audit/project-health-20260911/osv-scan.log` | 本次供应链扫描原始输出（退出码 1） |
| `audit/project-health-20260911/sbom.spdx.json` | 本次生成的锁文件 SBOM 快照 |
| `../project-health-20260907/REPORT.md` | 上次体检报告（对照基线） |
| `../project-health-20260907/osv-findings.json` | 上次漏洞记录（用于对比恶化情况） |

检查方式说明：全部结论基于**静态代码阅读 + 仓库自带脚本执行 + OSV 与 Maven Central 实时查询**。所有计数（文件行数、异常捕获数、违规条数等）均由命令实测得出，非估算。涉及"疑似""待验证"的判断已在正文显式标注。
