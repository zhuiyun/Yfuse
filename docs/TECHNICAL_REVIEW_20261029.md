# Yfuse 全面审查报告（2026-10-29）

> 状态：以下是整合修复前的静态审查快照，行号和计数对应当时源码，不代表最终验证结果。
> 2026-10-29 整合已修复 Exo 主线程契约、YCore 学习缓存重置和并发合并、详情页网络初值重复读取、
> TMDB 无 Token 代理及显式关闭后端的配置、CI 测试路径和守门任务、密钥生成覆盖风险。
> 211 份审计文字记录已从待删除列表恢复；发布校验样本移至 scripts/tests/fixtures。
> 生成产物退出 Git 的约 1.2 万文件清理尚未获自动审批放行，需另行确认，不属于本次代码合并。
> P1 中“把整个播放器 scope 放到后台线程”的建议不适用于 Media3：构造、控制和 ticker 必须留在其 application looper。
> 其余架构与产品策略建议仍需按各自范围单独评估，本文不能作为已完成全部建议或通过动态验证的证明。

## 本轮整合复核（2026-10-29）

本节只记录待合并修改的回归问题，与下文整库静态建议分开。以下问题已修复：

1. **P1：Exo 线程错误**。`PlayerRoot.kt:164` 的引擎 scope 固定为 Main.immediate，保留父 Job，避免构造、控制与 ticker 脱离 Media3 application looper。真实播放回归见 `PlaybackEngineThreadInstrumentedTest.kt:30`。
2. **P2：YCore 重置被旧缓存覆盖及实例线程泄漏**。`AndroidYPlaybackLearningStore.kt:31` 按 SharedPreferences 当前内容和 TTL 失效缓存，所有实例读/合并/写与 reset 共用锁；移除每实例 executor。并发、重置交错和过期回归见同名 InstrumentedTest。
3. **P2：源码迁移导致图标测试失效**。`scripts/tests/test_launcher_icons.py:25` 改读 core/designsystem 的实际路径。
4. **P1：私钥容易被误提交**。`scripts/generate-update-manifest-key.sh:27` 要求显式仓库外目录，`.gitignore:35` 额外忽略私钥文件名。
5. **P2：重复生成会覆盖签名密钥**。同脚本 `:36` 拒绝已有文件与符号链接，通过临时目录和硬链接防止并发覆盖；测试覆盖已有单文件、完整密钥对和并发生成。
6. **P2：关闭后端的 release 丢失 TMDB 直连**。`composeApp/build.gradle.kts:1157` 与 `tvApp/build.gradle.kts:208` 仅在后端开启时清空 token；显式关闭后端保留用户本地 token。
7. **P2：匿名 TMDB 代理缺少组件请求**。`watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/TmdbProxyAllowlist.kt:124` 增加 trending、评分榜、在播榜以及受限地区/类型/公司/电视网筛选，补空 token 组件与服务端路由回归。
8. **P1：审计清理会删除发布测试样本**。`scripts/tests/test_publish_job_split.py:25` 改用 scripts/tests/fixtures 下两份独立签名样本，内容与历史文件一致。
9. **P1：发布工作流测试仍要求已移除的 TMDB secret**。同测试 `:37` 同步 build job 的无 secret 契约。
10. **P2：TV Kotlin 格式检查漏检**。`build.gradle.kts:141` 恢复 tvShared 所拥有 TV 生产源码的检查。
11. **P2：新增工程守门未接入 CI**。`.github/workflows/quality-gates-v2.yml:154` 显式执行 core 分层、源码体积和服务定位器三项任务。
12. **P2：审计文字说明误删**。211 份 Markdown 已从暂存删除中恢复；其他 12,189 份审计产物清理需另行确认，磁盘文件全部保留。本轮代码提交不包含 audit 删除。
13. **P2：依赖注入快照未覆盖新增关闭回调**。`AppModuleGraphTest.kt:76` 精确更新四项 onClose 契约；`PlaybackSyncManagerTest.kt:156` 增加关闭服务后不再执行排队重试且保留待同步进度的行为测试。
14. **P3：详情页网络初值仍在每次重组读取**。`DetailScreen.kt:288` 使用 remember 保存初值。
15. **验证环境：Git Bash 覆盖模拟命令 PATH**。`scripts/tests/test_publish_package_only.py:49` 在 shell 内将测试目录放到 PATH 首位，阻止模拟 curl 被系统 curl 抢先执行。

验证：手机端 5,539 项、TV 129 项、后端客户端 45 项、协议 28 项、服务端 339 项全部通过；关闭后端另跑 45 项通过；Python 脚本 120 项通过。手机/TV 编译、测试包编译、全项目 ktlint、四项工程守门及模块/后端边界检查通过。phone/TV 的 release BuildConfig 均验证了后端开启时 token 为空、关闭时与本地配置一致，验证日志未输出 token。Android 设备回归另行记录。

部署前提：新 TMDB 代理白名单必须先部署到服务端，再发布不含 token 的 App；本次只合并代码，没有部署服务端或交付新正式安装包。版本仍为 1.7.9 / 331。

变更规模：按平板首页、ONNX 移除、播放器事务/线程、共享层与生命周期、TMDB/工程守门、密钥保护分提交；包名迁移和 UI 容器重排含机械改动。最小可独立验证部分是密钥脚本或播放器事务；审计产物清理单独保留待确认。

- 审查对象：`D:/Demo/Yfuse`，`version.properties` = `1.7.9` / `VERSION_CODE=331`
- 审查维度：安全性、架构设计、性能、代码质量、可维护性、工程与发布
- 方法：静态走查（逐文件阅读 + 全仓计数）+ 构建/CI/清单配置核对 + git 仓库实测；结论均附路径:行号
- 与上一轮的关系：`docs/TECHNICAL_REVIEW_20261014.md`（v1.3.4）的结论已逐条复核，仍存在的在本文标注「上轮遗留」

---

## 0. 审查基线（实测）

| 维度 | 数值 |
|---|---|
| 生产代码 | 1,331 个 `.kt` / 310,733 行（commonMain 917 / 208,035；androidMain 414 / 103,271） |
| 测试代码 | 964 个 `.kt` / 130,582 行 / 5,533 个 `@Test`（文件比 0.72:1，行数比 0.42:1） |
| instrumented | 39 个文件 / 90 个用例，`compose-ui-test` / 截图测试依赖 **0** |
| 最大文件 | `AndroidAdaptiveCore2YPlayer.kt` 3,413 行；54 个文件 > 800 行 |
| 构建脚本 | `composeApp/build.gradle.kts` 1,424 行，内含 15 个自定义 `verify*` 任务 |
| CI | 19 个 workflow，`publish-android.yml` 1,515 行；PR 主闸单 job 串行约 300 分钟 |
| 仓库 | git 跟踪 15,550 个文件，其中 `audit/` 12,400 个（2.0 GB），`.git` pack 1.61 GiB |

**整体判断**：工程纪律高于同类项目——依赖锁定、供应链 pin、签名/版本/DEX 校验、日志脱敏、Keystore 信封加密、服务端鉴权限流都做得很扎实（见 §7 已确认良好项）。**核心风险不是"没做"，而是"边界没有结构强制、热路径上有未闭合的资源与线程契约"**。因此本报告的重点在 §2（Widget/JS 与明文传输）、§3（模块边界与依赖方向）、§4（播放热路径）。

---

## 1. 优先级总览

| ID | 问题 | 维度 | 优先级 |
|---|---|---|---|
| S1 | Widget/JS 子系统：导出入口 + 无签名校验 + 出站请求无地址白名单且跟随重定向 | 安全 | **P0** |
| S2 | 公网 `http://` 媒体服务器不阻断、不提示，密码与 `X-Emby-Token` 明文传输 | 安全 | **P0** |
| S3 | `audit/` 把构建产物与历史源码快照提交进 git（12,400 文件 / 2 GB） | 卫生/可维护 | **P0** |
| A1 | `composeApp/src` 被两个 KMP 库用 `srcDir` 复用，同一份代码编译两次、同名 namespace | 架构/工程 | **P1** |
| A2 | `core` 反向依赖 `feature`，`core` 内 24 个子包环状互引，UI 枚举进入持久化 schema | 架构 | **P1** |
| A3 | `GlobalContext` 服务定位器 48 处（主源码）+ 12 处 `runCatching{get()}` 静默降级 | 架构/质量 | **P1** |
| A4 | DI 单例无生命周期：6 个自建无主协程 scope、`init` 里 `while(true)`、监听器不注销 | 架构/稳定 | **P1** |
| A5 | 上帝类：5 个 1,600–3,400 行核心类 + 1,424 行构建脚本，职责 6–7 种 | 质量 | **P1** |
| A6 | 一屏 5–6 条独立状态流 + 26 字段 state / 409 行 reducer，状态无单点 | 架构 | **P1** |
| A7 | 服务端 `watchTogetherModule` 单函数 1,349 行，含 16 分支消息状态机 | 质量 | **P1** |
| P1 | 播放引擎内部后台协程跑在 Compose 的 UI scope 上（主线程做网络/建播放器/开磁盘缓存） | 性能/稳定 | **P1** |
| P2 | 本地播放代理 socket 只有头部超时，无正文超时 → worker 线程与内存租约可永久挂住 | 性能/稳定 | **P1** |
| P3 | 播放路由线程上做同步 SharedPreferences 读（learning store） | 性能 | **P1** |
| P4 | 日历 21 天窗口合并跑在主线程，且与后台 Worker 抢同一把 Mutex | 性能 | **P1** |
| P5 | 首页 `featuredSlides` 是 `get()` 派生属性，每次重组重跑 6 步流水线；`remember` 用巨型 state 做 key | 性能 | **P1** |
| P6 | `DetailScreen` 在函数体读健康状态 + 每次重组调 `ConnectivityManager`（2–3 次 Binder IPC） | 性能 | **P1** |
| S4 | ~~媒体客户端未限制跨主机重定向~~ **已复核不成立**：Ktor Emby client 未安装 `HttpRedirect`，3xx 直接抛错；播放链路由 `EmbyPlaybackInterceptor` 做同源校验并剥离 `X-Emby-*` | 安全 | 撤销 |
| S5 | ~~更新清单签名默认关闭~~ **已复核：代码已 fail-fast**，CI 生产签名缺公钥会 throw；真实缺口是**配置为空**（`gradle.properties:48`），需生成并配置密钥，非代码改动 | 配置 | P1 |
| M1 | UI 层零自动化回归（无 Compose UI 测试、无截图基线、API 37 只跑 5 个类） | 可维护 | P1 |
| M2 | 只有 ktlint：无 detekt、无覆盖率门槛、无复杂度阈值；架构约束靠文本正则 | 可维护 | P1 |
| P7 | 直通音频每采样 `allocateDirect`（Direct 与 Enhanced 实现已漂移） | 性能 | P2 |
| P8 | 代理路由表全局锁内 O(n²) 清理、50k 路由无内存预算、HLS 每段 SHA-256 + `String.format` | 性能 | P2 |
| P9 | `readAt` 在实例监视器内做阻塞网络读，与 QoE/码率/close 共用一把锁 | 性能 | P2 |
| P10 | 无 `baseline-prof.txt`，macrobenchmark 无基线数据 | 性能 | P2 |
| M3 | 5,533 个测试但风险最高的播放会话类 0 引用；`EmbyRepositoryTest` 单文件 1,879 行 | 质量 | P2 |
| M4 | 服务端每连接每 10s 重校验 token，压在 4 线程池 + 单 SQLite 连接上 | 性能/扩展 | P2 |
| M5 | 服务端房间索引全局 `creationLock`、每 2s 全房间序列化 + 深比较、WS 读循环无 `catch` | 稳定 | P2 |
| M6 | `expect/actual` 被当作"UI 有没有这个功能"的开关；`commonMain` 混入 58 处 `java.*` | 架构 | P2 |
| M7 | CI 单 job 串行 300 分钟、无 build cache/configuration cache、发布靠轮询另一 workflow | 工程 | P2 |
| M8 | 版本/SDK/签名在 4 处硬编码且已漂移；文档版本号与实际不符 | 工程 | P2 |
| M9 | 仓库工作树 14 GB 未跟踪产物（artifacts 3.0G + artifacts-local 9.3G + tmp 1.8G）+ 26 个 APK | 卫生 | P2 |
| S6 | SMB 未强制签名/加密；DLNA 中继对整段局域网放行 | 安全 | P2 |
| S7 | TMDb token 编进 APK（上轮遗留，已有代理通道） | 安全 | P2 |

---

## 2. P0：安全与仓库健康

### S1【严重】Widget/JS 子系统是当前最大的攻击面

**证据**
- `composeApp/src/androidMain/AndroidManifest.xml:113-157`：`WidgetImportActivity` 为 `exported="true"`，注册了 `file`/`content` 的 `.*\.js`、`text/plain`、`application/octet-stream` 过滤器，以及 BROWSABLE 的 `yfuse://add-widget?url=`。
- 该 Activity 只校验扩展名/MIME，不校验来源与完整性：`feature/widgets/WidgetImportActivity.kt:139-145`、`isWidgetDocument()` `:158-170`。
- 全仓 widget 代码中没有任何 `signature` / `verify` / `allowlist`；安装后 `enabled = previous?.enabled ?: true` 默认启用（`core/widget/WidgetRepository.kt:324-343`）。
- 出站桥 `core/widget/WidgetHttp.kt:34-73`：主 `client` **未** `followRedirects(false)`（只有 `tmdbClient` 在 `:79-84` 关了）；`:106-116` 只校验 URL 无 userinfo，**不校验 scheme 与 host**，`http://` 放行；`:127-131` JS 可自定义任意请求头；`:159-162` 响应头（含 `Set-Cookie`）原样回传 JS。
- 桥接点 `core/widget/SandboxWidgetRuntime.kt:72-97`（`__yfRequest`，仅 40 次/调用、并发 4）。

**原因**：这个子系统按"官方内置组件"的信任模型设计（安装即启用、脚本随首页自动执行），却接入了"任意 App / 任意网页可投递 JS"的外部入口，且网络桥按"通用 HTTP 客户端"实现而不是"受限数据通道"。

**影响**：一条 `adb` 命令或一个网页即可把任意 JS 塞进组件面板并默认启用；脚本随后可对 `http://127.0.0.1:<port>`、`192.168.*`、`169.254.169.254` 发起请求并读回响应（典型 SSRF 数据渗漏），重定向可绕过任何事前 host 检查。沙箱本身（32MB 堆、无文件/进程绑定、超时）是有效的，但**边界在门外**。

**建议**
1. `WidgetHttp` 内做地址分类：拒绝环回/私有/链路本地（可反向复用 `core/network/ServiceEndpointPolicy.kt:110-153` 的 `isLocalServiceHost()`），除 TMDB 外禁止明文，`followRedirects(false)` 或仅允许同源重定向，默认不回传 `Set-Cookie`/`Authorization`。
2. 组件引入 Ed25519 清单签名（可复用 `AppUpdateManager` 的 `verifyEd25519Signature`）或维护官方 host + digest 白名单。
3. `WidgetImportActivity` 改为 `exported="false"`，`file`/`content` 改走 `MainActivity` 的 `SEND` 统一分发；导入面板显式展示"该组件可向 <host> 发起请求"。

### S2【严重】公网 `http://` 媒体服务器既不阻断也不提示

**证据**
- 策略函数 `core/network/ServerEndpointPolicy.kt:23-59`：注释写明"Media servers accept HTTP and HTTPS without a transport confirmation requirement"，参数 `localCleartextConfirmed` 被 `@Suppress("UNUSED_PARAMETER")` 丢弃；任意 `http://公网域名` 直接返回 `EndpointTransportDecision.Cleartext`（`allowed=true`）。
- 全局放行明文：`composeApp/src/androidMain/res/xml/network_security_config.xml:7` `<base-config cleartextTrafficPermitted="true" />`。
- 登录即发密码：`core/data/EmbyAuthService.kt:31-42`（body 含 `Pw`）；后续 token 走自定义头 `core/network/EmbyPlaybackHeaders.kt:45`（`X-Emby-Token`）。
- UI 把公网 HTTP 也归为"本地明文"，不阻断不确认：`feature/servers/ServerListPolicy.kt:118-140`。
- 对照不一致：文件来源（WebDAV/SMB）会提示"HTTP 会以明文发送密码"（`feature/filesource/FileSourceFormDialog.kt:283`）；一起看中继则强制公网 HTTPS（`core/network/ServiceEndpointPolicy.kt:88-93`）。
- CI 层面还被静音：`lint.xml:19` 全局 `ignore` 掉 `InsecureBaseConfiguration`。

**原因**：`base-config` 为了兼容"局域网 HTTP + 外部隧道终止 TLS"的整体放行，但把"公网 http"与"局域网 http"混为一类，且确认参数实现到一半被 suppress 掉。媒体服务器的凭据价值等于该服务器的完全控制权（管理员权限、可删库），风险等级高于普通 API。

**影响**：用户（常见于自建/公网映射场景）填 `http://公网IP:8096` 时，账号密码与后续 token 全部裸传；同网段或路径上任何中间人都可完整接管其媒体库。

**建议**
1. 恢复 `validateEmbyServerEndpoint` 的传输决策：非私有地址的 `http://` 返回 `PublicCleartextConfirmationRequired`，UI 复用现有确认对话框（`FileSourceFormDialog.kt:283` 已有文案可参照），服务器列表常驻风险标记。
2. 长期把 `base-config` 收敛为 `false`，仅对私网用 `domain-config` 放行；`InsecureBaseConfiguration` 从 `ignore` 降级为 baseline 单点抑制，保证"新增明文域"必须显式评审。
3. 已存在的明文配置在升级时做一次"重新确认"。

### S3【严重·上轮遗留】`audit/` 把构建产物当发布证据提交进 git

**证据**
- `.gitignore:55-61` 只排除 `audit/**` 的图片/视频，`.kt`、`.class`、`.html`、`.aar`、`.tar.gz` 全部照常入库；`git ls-files audit` = **12,400** 个文件（全仓 15,550），`du -sh audit` = **2.0 GB**，`.git` pack 1.61 GiB。
- 已入库最大文件：`audit/handoff-deploy-20260918/…handoff.tar.gz` 21.9 MB、`…/libmpv-yfuse-bluray.aar` 12.8 MB、`bcprov-jdk18on-1.85.jar` 9.8 MB。
- 项目自己的工具被迫写特例绕开：`scripts/supply_chain_check.py:27-30` 硬编码 `EVIDENCE_EXCLUSIONS = (":(exclude)audit/**",)`，注释说明"会让 SBOM 列出已经不存在的版本"。

**原因**："可追溯的发布证据"被实现成"把当时的整棵源码树和工作树一起提交"，而不是"提交摘要 + 校验和 + 外链产物"。

**影响**：clone/CI checkout 慢；`rg`/IDE 索引/AI 检索先命中 7,679 份历史 `.kt` 副本，会把旧实现误当现状（本次审查已踩到 `.codex-*` 内的 workflow 副本）。这已经不是卫生问题，而是工具链被污染。

**建议**：`.gitignore` 改为 `audit/` 整体忽略、只保留 `audit/**/*.md` 摘要；二进制证据迁到 Release artifact/对象存储；`git filter-repo --path audit --invert-paths` 清历史；CI 加"禁止新增 >1 MB 跟踪文件"守卫。

---

## 3. P1：架构

### A1【上轮遗留】`composeApp/src` 同时是 application 源码和两个 KMP 库的 `srcDir`

**证据**
- `phoneShared/build.gradle.kts:24,36,52,62,97` 与 `tvShared/build.gradle.kts:24,37,55-57,85` 把同一份 `composeApp/src/{commonMain,androidMain,commonTest,androidUnitTest,res}` 挂进各自源集。
- 两个库用**同一个 namespace**：`phoneShared:13` 与 `tvShared:13` 都是 `com.yfuse.shared`，并各自对同一 `res` 目录 `addStaticSourceDirectory`。
- 代价被构建方自己记录在案：`gradle.properties:14-17`（需要 4 GB）；`.github/workflows/quality-gates-v2.yml:136-145`（"side by side they outgrew the 4 GB Kotlin daemon"，抬到 `-Xmx8g`）。
- 副作用：根 `build.gradle.kts:126-144` 的 ktlint 只对 `tvShared` 排除了 `composeApp/src`，**没排除 `tvApp/src`**，而 `tvShared` 已把 `../tvApp/src/androidMain/kotlin` 纳入 → tvApp 源码被两个模块各查一遍。

**原因**：历史上把"共享代码"实现在目录级而不是模块级（`srcDir` 复用），KMP 库因此成为"无源码的虚拟模块"。

**影响**：同一文件在两个 KotlinCompile 任务里分别编译，增量缓存无法复用；IDE 中一个文件属于 3 个 project；双份同名 R 类随时可能撞车；CI 内存与时间翻倍（300 分钟 job 的主因之一）。

**建议**：短期补 ktlint 排除 + namespace 拆成 `com.yfuse.shared.phone` / `.tv`；中期抽出真正的 `:shared` KMP 模块（源码只挂一次），`composeApp`/`tvApp` 只留 manifest/signing/BuildConfig/packaging，`android{}` 收进 `build-logic` convention plugin。

### A2 `core` 反向依赖 `feature`，且 `core` 内部成环

**证据**
- `core` 有 6 处 `import com.yfuse.feature.*`（主源码），例如 `core/sync/playback/PlaybackSyncManager.kt:16`（依赖 `feature.player.PlaybackLaunchTimings` 这个 `internal object`）、`core/data/PlaybackPreferences.kt:10,131`（持久化 schema 用 `feature.player.AudioEnhancementMode`）、`core/widget/WidgetPlaybackIdentity.kt:5-7`。
- `core` 子包双向 import：`account ↔ data`、`account ↔ sync`、`data ↔ sync`、`data ↔ network`、`data ↔ playback`、`sync ↔ personal`。回避手法已经出现：`core/data/ServerRegistry.kt:124` 用全限定名引用 `core.personal` 以绕开 import 环。
- UI 包充当 schema 来源：`core/data/ThemePreferences.kt:5-18` 与 `core/sync/CloudSyncSnapshot.kt:15-17` 依赖 `core/designsystem` 的 `ThemeMode/GlassStyle/MotionTheme/SplashAnimation`。

**原因**：所有 24 个 core 子包 + 18 个 feature 包编译成**一个** Gradle 模块，层与层之间没有编译期可见性约束，方向靠自觉。

**影响**：改播放器包名会改到持久化枚举名（已落盘的用户设置值）；数据层正确性依赖 UI 层一个不被 DI 管理的 `LinkedHashMap`；任何一层都无法单独测试；改动一个 core 包触发 20 万行重编译。

**建议**：新建 `core/model`（纯类型、零依赖）承载持久化枚举与 `*Snapshot` DTO，`designsystem` 只留 Compose 视觉；按 `model ← security/logging ← network ← data ← sync ← account` 重排；先加一条 `verifyLayering` 构建校验（禁止 `core/**` 出现 `import com.yfuse.feature.`），再逐步拆模块。

### A3 服务定位器仍在主路径上，且把"绑定缺失"变成"功能消失"

**证据**
- `GlobalContext.get()` 主源码 **48** 处（含测试共 104），分布 app、core/widget、feature/detail/home/library/player/handoff。
- 12 处用 `runCatching { get<T>() }.getOrNull()` 静默降级，例如 `feature/detail/DetailRatings.kt:41`（取不到 TMDB 仓库 → 评分区空白且无日志）、`feature/home/HomeComponent.kt:74,77`（取不到书架偏好 → 静默退化默认）。
- UI 直接触达数据层并联网：`feature/home/HomeScreen.kt:1546-1549` 在 composable 内 `remember { GlobalContext.get().get<EmbyRepository>() }` 后 `produceState` 里发请求。
- 上轮已提"91 处"，现降到 48 处但仍未消除。

**原因**：Koin 已装配完整依赖图（14 个 module），但为了"少传参数"保留了服务定位器后门。

**影响**：14 个 module 的绑定正确性无法从类型系统获得保证；`runCatching` 同时吞掉"没绑定"和"初始化真异常"，线上表现为"某个功能偶尔不出现"；composable 里发网络请求使 UI 成为数据层调用方，`@Preview` 与测试无法替换。

**建议**：保留 `startKoin` 作为唯一装配点，删除 `GlobalContext` 静态入口；**禁止** `runCatching { get<T>() }`，缺失绑定必须启动即崩；可选服务用真实 `NoOp` 实现替代 `getOrNull()`。

### A4 DI 单例无生命周期

**证据**
- 6 个 core 服务自建无主 scope：`core/account/AccountRepository.kt:92`、`core/account/PersonalAutoSync.kt:36`、`core/sync/playback/PlaybackSyncManager.kt:60`、`core/sync/WatchTogetherClient.kt:45`、`core/remote/PhoneRemoteClient.kt:70`、`core/remote/RemoteControlHost.kt:100`，全部 `CoroutineScope(SupervisorJob() + Dispatchers.X)` 且无 `close()`。
- `core/data/ServerRegistry.kt:185-201`：`init { scope.launch { while (true) { persistSignal.receive() … } } }` —— 对象一构造就起一个永不结束的消费者。
- 平台监听器只注册不注销：`core/playback/PlaybackDeviceCapabilities.android.kt:67,69`（`registerAudioDeviceCallback` / `registerDisplayListener`），绑定处 `di/PlaybackModule.kt:40` 无 `onClose`。
- Koin 侧只有 HTTP client 配了 `onClose`（`di/NetworkModule.kt:28,37` 等）。

**原因**：把 `single` 当成"有生命周期"，实际上它只保证"永不释放"。

**影响**：每构造一个 `ServerRegistry`（含每个单测）泄漏一个常驻协程；登出/切换账户无法停止任何后台工作；设备能力提供者永久持有 Application 级监听器；测试容器无法真正销毁。

**建议**：DI 提供**一个** application scope（`single<CoroutineScope>(named("app"))`）并注入所有 core 服务；`single` 统一加 `onClose`；`ServerRegistry` 的消费循环改 `start(scope)`/`close()` 成对，`init` 内不启动任何异步。

### A5 上帝类：5 个核心类 + 1 个构建脚本

| 文件 | 行数 | 实际承担的职责 |
|---|---|---|
| `core2/android/AndroidAdaptiveCore2YPlayer.kt` | 3,413 | 引擎选择/命令路由/学习/子会话编排/埋点 |
| `androidMain/…/AndroidNativeDirectSession.kt` | 2,936 | 解复用/解码/音频直通/缓冲计划/字幕 |
| `feature/player/PlayerActivity.kt` | 2,257 | 生命周期/UI 宿主/进度落盘/遥控/画中画 |
| `update/AppUpdateManager.kt` | 2,160 | 清单校验/下载/断点/安装/记录 |
| `core/account/AccountRepository.kt` | 1,932 | 身份会话 + 密钥派生保险库 + 云同步编排 + 播放迁移 + 恢复状态机（构造参数 14 个） |
| `core/data/ServerRegistry.kt` | 1,624 | 内存模型 + CRUD + 加密持久化事务/回滚 + 备份编解码 + 迁移 + 密钥引用 |
| `feature/home/HomeScreen.kt` | 1,996 | 页面骨架 + hero 轮播 + 书架族 + 推荐 + 日历排序规则 + DI 取值 |
| `feature/detail/DetailExecutor.kt` | 1,624 / 41 个方法 | 加载编排 + 源选择状态机 + 剧集选择 + 播放启动/failover + 用户态写入 + 片单 |
| `composeApp/build.gradle.kts` | 1,424 | 15 个 verify 任务 + 手工重打包 AAR + 自定义 `BumpVersionTask` |

**原因**：功能是"按页面/按能力追加"叠加进来的，缺少一次明确的职责切分；`DetailReducer` 409 行单个 `when`、`DetailState` 26 字段、`ServersIntent` 31 变体是同一现象。

**影响**：单点改动触发整文件重编译；无法对小职责单测；review 只能"看整体"。`config/function-size-baseline.txt` 只有 5 条白名单，说明已有量级闸但基线极窄。

**建议**（按性价比排序）
1. `AccountRepository` → `AccountAuthService` / `VaultService` / `CloudSyncCoordinator` / `PlaybackMigrationService`，门面只持 `AccountState`。
2. `ServerRegistry` → `ServerStore`（内存）/ `ServerPersistence`（事务+回滚，可测）/ `ServerBackupCodec` / `ServerSecrets`。
3. `DetailExecutor` → 抽出 `PlaybackLaunchCoordinator` 与 `SourceSelectionMachine`，Executor 只做 intent→msg 路由。
4. `HomeScreen` 的 `homeCalendarPreviews` / `homeNextUpShelf` 是纯函数，直接搬进 `HomeStore`。
5. 构建脚本迁到 `build-logic/` convention plugins（见 M2）。

### A6 状态管理分裂：一屏多条状态流

**证据**
- `feature/servers/ServersTabComponent.kt`：`listFilter`(:154)、`refreshState`(:160)、`notice`(:170)、`management`(:183)、`layout`(:125) 五条流 + Store 自己的 `ServersState`。
- 同类：`HomeComponent.kt:66`、`LibraryComponent.kt:374`、`DetailComponent.kt:566`。
- `DetailState` 26 字段（`DetailStoreContract.kt:12`），`DetailIntent` 29 变体，`DetailReducer.kt:7` 409 行单 `when`。

**原因**：MVIKotlin 只覆盖了"数据"，"视图态/瞬时提示/布局"留在了 Component 的裸 `MutableStateFlow`。

**影响**：任一字段变化复制 26 字段 state 并重跑 409 行 reducer；过滤+刷新+提示的一致性没有单点可推理；测一屏要同时驱动 Store 和 5 条流。

**建议**：确立"一个路由 = 一个 Store = 一个 UiState"，把上述流并入 `XxxState` 子结构，Component 只保留导航与副作用；先以 `ServersScreen` 与 `DetailExecutor` 做两个样板再推广。

### A7 服务端单函数 1,349 行

**证据**：`watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt:169-1518`，其中 `:403-1516` 是整个 `/watch` WebSocket 处理器，`:576-1495` 是 16 分支 `when (message.type)`，作用域内约 20 个跨分支可变局部变量（`:486-492`）。同目录已有 `WatchRoomChat/WatchRoomMessaging/WatchRoomBroadcast` 拆分，唯独最复杂的消息分发没拆。

**建议**：抽 `WatchSocketHandler` + `Map<String, suspend (MessageContext) -> Unit>`，`MessageContext` 显式持有 pacer/joinedRoom/限流计数；先抽纯函数化的 `chat`/`reaction`/`playlist*`。

---

## 4. P1：性能（按"实际会掉帧/泄漏"排序）

### P1 引擎内部后台协程跑在 Compose 的 UI scope 上

**证据**
- `feature/player/PlayerRoot.kt:163` `val scope = rememberCoroutineScope()`（调度器是 Main）；该 scope 被注入引擎与引擎槽：`:421` `createVideoEngine(..., scope = scope)`、`:445-447` `PlaybackEngineSlot(..., scope = scope)`。
- 转码回退在它上面发起网络请求：`MpvVideoEngine.kt:1524-1543`（`stopEncoding(...)` 后 `loadFileOrFail`）、`ExoVideoEngine.kt:1895-1929`（`stopEncoding` → `replaceMediaItem` → `prepare`）。
- 引擎构造也在此 scope：`PlaybackEngineSlot.kt:296-312`；Exo 构造期 `VideoCachePool.acquire` → `VideoCache.android.kt:151-156` 打开 `SimpleCache`（磁盘 + 数据库）。

**原因**：`rememberCoroutineScope` 顺手被当作"引擎的生命周期 scope"用了，但它的调度器是 UI 线程。

**影响**：转码回退时主线程串行跑网络 + `prepare()`；引擎切换时主线程新建 ExoPlayer 并打开磁盘缓存。低端机表现为开关播卡顿、掉帧，网络慢时接近 ANR。

**建议**：引擎自建 `SupervisorJob + Dispatchers.IO`（或专用单线程），只在状态发布时切回 Main；`PlaybackEngineSlot` 的构造协程显式 IO，构造完成后在主线程发布 binding。

### P2 本地播放代理没有正文阶段超时

**证据**
- `core/playback/PlaybackProxyLimits.kt:99-122`：`soTimeout` 只在读请求头时按剩余预算设置。
- `core2/android/AndroidYCoreHttpProxy.kt:1989-2008` `copySource()`：写循环无任何截止时间。
- 释放只在 `finally`（`:1837-1843`），而 `workers.shutdownNow()`（`:827`）无法中断阻塞写。
- `AndroidTransportMediaDataSource.kt:1386-1402` 的租约/缓存只在 `close()` 释放，`cancelReads()`（`:80-85`）不释放。

**原因**：超时策略只覆盖了"慢客户端不发请求"，没覆盖"客户端读到一半不读了"。

**影响**：worker 线程 + `PlaybackMemoryLease` + 块缓存永久挂住；重复几次后 16 个播放槽耗尽，新播放只能拿到 503；内存预算 `rebalance()` 会永久少分给后续会话。

**建议**：已准入连接也设 `soTimeout`，`copySource` 每次写校验 deadline，超时/异常直接 `socket.close()` 让 `finally` 落地；`cancelReads()` 做幂等租约释放。

### P3 播放路由线程上做同步 SharedPreferences 读

**证据**：路由线程 `AndroidAdaptiveCore2YPlayer.kt:183,3207`（`Dispatchers.Default.limitedParallelism(1)`）→ `onState` → `learningEngine.record`（`:1689`）→ `AndroidYPlaybackLearningStore.kt:22-40` 的 `@Synchronized load()`（同步 `getStringSet` + 逐条 Base64 解码 + 可能 `persist()`）。该类注释 `:170-182` 明写"Nothing on this view may block for long"。

**影响**：每次换集/重试在命令循环线程上做一次磁盘级读取，seek/pause 被排在后面；同时占住共享的 `Dispatchers.Default`。实现与自身约束不一致。

**建议**：`record` 投递到 IO，路由器只发消息；`load()` 加进程内缓存。

### P4 日历合并跑在主线程

**证据**：`core/data/AiringCalendarRepository.kt:353` `calendarLoadMutex.withLock { … }` 包住整个 `loadCalendar`；该文件 1,803 行内 **零** `withContext`；调用方 `core/util/ComponentScope.kt:12` 是 `Dispatchers.Main`，`HomeComponent.kt:125-127` 冷启动即触发；同一单例还被 `CalendarSyncWorker.kt:29-44` 的 30 分钟后台任务持有同一把锁。（本地 SQLite 已正确切 IO：`CalendarLocalStore.android.kt:37` 等。）

**影响**：首页/日历页冷启动时 21 天窗口的 episode 合并、排序、分组都在 UI 线程；后台 Worker 持锁刷新时前台要等整个网络往返，表现为卡片长时间 loading。

**建议**：`loadCalendar` 的纯计算段 `withContext(Dispatchers.Default)`；后台入口改走独立 Mutex 的"只读缓存"路径。

### P5 首页派生属性每次重组重算

**证据**：`feature/home/HomeStore.kt:105-107` `val featuredSlides: List<TmdbItem> get() = content.featuredSlidesForDay(today)`；`core/model/TmdbFeatured.kt:7-38` 每次调用做 `take(24) → filter → distinctBy → rotate → mapTo → distinctBy → rotate → filterNot → buildList → distinctBy → take(8)`（6 个以上中间集合）；`HomeScreen.kt:379` 在 `HomeContentBody` 函数体读它。另 `HomeScreen.kt:320,354` 的 `remember(calendarState.days, state)` 用整个 26 字段 `HomeState` 做 key，每次重组先深比较数百个 `MediaItem`；同文件 `:453` 已有正确范式（用轻量 revision 做 key）。

**影响**：首页是冷启动首屏，任何 state 变化都跑完整条流水线再丢弃结果；深比较本身也是一次全量遍历。

**建议**：在 reducer 里算好存进 state（与 `shelfRevision` 一致），或至少 `remember(state.content.revision, state.today)` 做 key。

### P6 `DetailScreen` 每次重组做 Binder IPC

**证据**：`feature/detail/DetailScreen.kt:187` 是约 1,200 行的单一 composable；`:281` 在**函数体**内 `collectAsState()` 健康状态；`:285` `currentPlaybackNetworkClass()` 每次重组执行 → `core/network/PlaybackNetwork.android.kt:12-28` 的 `ConnectivityManager` + `activeNetwork` + `getNetworkCapabilities`（2–3 次跨进程调用）。

**影响**：健康探针 60s 一轮、每次请求成败都会改 health → 整页重组 → 每次 3 次 Binder。滚动与"播放返回"时最明显。

**建议**：把 health/network 下沉到只服务资源列表的小 composable，用 `derivedStateOf`/flow 替代同步查询；`DetailScreen` 拆 `DetailHero`/`DetailBody`/`DetailOverlays`。

### P7–P9 其他热路径

- **P7 直通音频每采样 `allocateDirect`**：`AndroidNativeDirectSession.kt:2210-2214` 每 access unit 一次，而 Enhanced 已复用（`AndroidEnhancedPlaybackSession.kt:2344-2345`）→ 30+ 次/秒 native malloc。建议按 `maxInputSize` 池化或对齐 Enhanced 写法。
- **P8 代理路由表**：`AndroidYCoreHttpProxy.kt:751-767` 持 `routesLock` 遍历全表且每条删除都 `removeAll{startsWith}`（最坏 O(n²)），`:2334` `MAX_ROUTES = 50_000` 不受内存预算约束；`serveHls`（`:1184-1289`）每次重载全量拉取+解析+逐段 `localUrl`（每段取锁）与 `sha256()`（十六进制化用 `"%02x".format`，1000 段约 3.2 万次 `String.format`）。建议维护 `presentation → routeIds` 索引、十六进制查表、按内容缓存 rewrite。
- **P9 `readAt` 锁内做阻塞网络读**：`AndroidTransportMediaDataSource.kt:304-310` `@Synchronized readAt` 内含 `runBlocking` 与 30s 预算，与 `qoeSnapshot`/`setMediaBitRate`/`discardCachedBlocks` 共用监视器。当前调用点与读线程同源，暂不致命，但一旦有诊断面板从 UI 线程调 `qoeSnapshot` 就是 ANR。建议缩小临界区、QoE 改 `AtomicReference` 快照。
- **P10 无启动基线**：仓库无 `baseline-prof.txt`，`macrobenchmark` 只在 `android-performance.yml` 手工触发且必须自带 baseline，指标只有 `StartupTimingMetric + FrameTimingMetric`，`CompilationMode.None()`。上面 P1–P6 全是"每次多算一点"的累积型问题，没有基线既无法量化也无回归保护。

---

## 5. P1：安全（中危）

- **S4 已复核不成立（撤销）**：`createEmbyClient` 没有安装 `HttpRedirect`，Ktor 不会自动跟随 3xx；配合 `expectSuccess = true`，302 在第一跳就抛错，不会发生第二次 send。`composeApp/src/commonTest/kotlin/com/yfuse/core/network/HttpClientFactoryTest.kt:478,508` 的两个用例正是断言 `engineCalls == 1`。播放链路另有防护：`feature/player/EmbyPlaybackInterceptor.kt:19-24` 显式说明"OkHttp 只剥离 `Authorization`，自定义 `X-Emby-*` 需要自己处理"，并用 `mediaCredentialOriginsMatch` 判定同源后才重新注入。
  **残留的真实问题（低危，非安全）**：同源 3xx 也被一并拒绝，因此 http→https 或路径规范化的反向代理会让客户端直接报错而不是跟随。这是 fail-closed 的取舍，可以保留，但错误文案应能提示"服务器返回了重定向"。
- **S5 已复核：代码侧已 fail-fast，缺口在配置**：`composeApp/build.gradle.kts:1119-1145` 在 `releaseSigningReady && !allowDebugSigning && key.isBlank()` 时，若 `isCi && !packageOnlyRelease` 会 `throw GradleException`，本地构建才降级为 warn —— 这是有意的取舍，不是遗漏。真正的问题是 `gradle.properties:48` 的 `yfuse.updateManifestPublicKey=` 一直为空，导致**已发布的包**接受未签名清单（`AppUpdateManager.kt:216-223` 走 `UnverifiedNoKey`，只在日志记一条 `manifest_accepted_without_key`）。修复动作是生成 Ed25519 密钥对并配置 `YFUSE_UPDATE_MANIFEST_PUBLIC_KEY` / `UPDATE_MANIFEST_SIGNING_KEY`，不需要改代码。后端裸 IP（`yfuseBackendClient/…/BackendEndpoints.kt:5`）无 pin 属独立议题。
- **S6 SMB 未强制签名/加密**：`core2/android/AndroidSmbMediaTransport.kt:121-129` 只设版本与超时，未设 `signingPreferred/requireSecureNegotiate`；凭据本身是加密存储的（`FileSourceModule.kt:26`），但传输可被同网段做 NTLM 中继。DLNA 中继（`core/cast/DlnaMediaRelay.kt:85,190`）绑定局域网地址且 `isAllowedPeer()` 放行整段本地网络并注入 App 鉴权头，建议收紧为 `this == renderer` + 短期 bearer。
- **S7 TMDb token 编进 APK（上轮遗留）**：`composeApp/build.gradle.kts:948` `buildConfigField("TMDB_TOKEN")`，可从 APK 提取滥用；已有代理通道 `BackendEndpoints.TMDB_PROXY` 未被默认使用。

---

## 6. P2：代码质量、可维护性与工程

### M1 UI 层零自动化回归

**证据**：全仓 `createComposeRule|runComposeUiTest|paparazzi|roborazzi` **0 命中**；`composeApp/build.gradle.kts:1364-1366` androidTest 只有 `androidx.test.junit/runner`；`quality-gates-v2.yml:338-345` 的 API 37 只跑 5 个类，全量只在 API 35。项目为这类缺口付过代价（`quality-gates-v2.yml:224-231` 记录 1.0.97 R8 后首帧崩溃）。

**建议**：接 `compose-ui-test` + Roborazzi 截图基线，覆盖 `designsystem` + 4 个主 Tab + Player OSD；去掉 API 37 的 5 类白名单改为分片。

### M2 静态分析只有 ktlint，架构约束靠正则

**证据**：根 `build.gradle.kts:65-66` 只 apply ktlint（无 baseline、`ignoreFailures=false`，这一点很好）；无 detekt/Kover/PMD；质量闸只有 `scripts/check_function_size.py`（`MAX_LINES=1500`，基线 5 条）。架构检查靠 `composeApp/build.gradle.kts` 里的 15 个 `verify*` 任务，其中 3 个是文本正则（`verifyDesignSystemUsage:69-205` 8 条正则 + 手写括号配平、`verifyBehavioralTestBoundaries:685-718`、`verifyMediaTestManifest:596-683`），另加 5 个 Python/Shell 文本 gate。`lint.xml` 的 6 条 ignore 中 4 条合理，`UseKtx` 与 `InsecureBaseConfiguration` 建议改 baseline。

**建议**：加 detekt（`LongMethod/ComplexMethod/TooManyFunctions/MagicNumber`，先 baseline 起步），正好替代手写解析；diff 覆盖率门槛（Kover 70%）；把设计系统 token 规则写成 detekt 自定义规则，让 IDE 内就能看到反馈。

### M3 测试"多而不均"

**证据**：5,533 个 `@Test`、0 处 `@Ignore`、0 处 mockk（用 fake + turbine + ktor-mock，断言密度约 4.2/用例，质量高于平均）；但 `AndroidEnhancedPlaybackSession` 等风险最高的类测试引用 0；175 个测试文件 < 40 行，同时 `EmbyRepositoryTest.kt` 单文件 1,879 行 / 73 用例。服务端 337 个 `@Test` 里没有并发/容量维度。

**建议**：Kover 只对 diff 设门槛；把大测试文件按关注点拆分；补一个 `WatchCapacityTest`（100–200 房间 × 4 成员并发 `hello`+`sync`）与播放会话的纯逻辑抽测。

### M4 服务端吞吐上限

**证据**：`Application.kt:112` `ACCOUNT_REVALIDATION_MS = 10_000`，`:480-485` 每连接一个 `socketAuth.watch`；`AccountService.kt:66` → `AccountWorkExecutor.kt:31-36`（`newFixedThreadPool(4)` + Semaphore）→ `AccountStore.kt:192-194`（全库唯一 `Connection`，注释自述"serialized behind lock"）+ `:226 PRAGMA synchronous = FULL`；连接上限 256（`Application.kt:83`）。

**影响**：满负载 25.6 次/秒的授权查询常驻占用 4 线程池约 10%，与登录/注册/同步/健康探针抢同一把库锁。这是当前架构下最确定的吞吐上限。

**建议**：重校验结果按 `sha256(token)` 做 3–5s TTL 缓存，或改为"临近过期查一次 + 撤销事件推送"；`/health` 与授权查询共用缓存。

### M5 服务端稳定性细节

- 房间索引全局锁：`WatchRoomStore.kt:49-57` `mutateIfCurrent` 把所有房间的加入操作串行化，`:66-74` 建房时 O(rooms) 扫描，`:118-127` sweep 持锁遍历全表 → 建议配额改 `ConcurrentHashMap<String, AtomicInteger>` 增量计数。
- 每 2s 全房间序列化 + 深比较：`WatchStatePersistence.kt:381`、`:345-370` → 建议加 `persistDirty` 置位。
- WS 读循环只有 `finally` 没有 `catch`（`Application.kt:493-496`）：未预期异常直接断连，客户端无 error 帧、服务端无一条结构化日志。
- 无 `StatusPages`，各端点 500 形态不一致（`AccountRoutes.kt:367` 用 `error`，`QoeRoutes.kt:85` 用 `code`）；`ServerLog.kt:49-52` 只记 `类名: message` 不记堆栈，而启动失败这类事件恰恰需要堆栈。

### M6 `expect/actual` 被当作产品分支与 UI 开关

**证据**：97 个 `expect` / 103 个 `actual`，但 phone 与 TV 共用同一套 actual（`tvShared:37` 也挂 `composeApp/src/androidMain`）；其中 51 个 `expect` 是只接收 `Modifier` 的 Unit 组件（`AppUpdateTools`、`DiagnosticLogTools`、`PermissionHealthTools`、`ServerBackupTools`…），用来"在 TV 上隐藏整页"。`commonMain` 内 26 个文件、58 处 `java.*`（`java.time`、`java.util.zip`、`TreeMap`），另有 46 处 `System.currentTimeMillis()` 与 `core/util/CurrentDate.kt:7` 的 `expect fun currentEpochMillis()` 并存。`androidAppContext` 为 null 时静默降级为"保守画质"（`PlaybackDeviceCapabilities.android.kt:26-32`、`CurrentDate.android.kt:59`）。

**建议**：平台差异收敛为"接口 + DI 绑定"（`PlayableCapabilitiesProvider`、`SecureStore` 已是此模式）；UI 的 `expect` 改为带参普通 composable；`requireAppContext()` 缺失即崩；要么把 `commonMain` 改名承认是 JVM 源集，要么清掉 58 处 `java.*`。

### M7 CI 结构

**证据**：`quality-gates-v2.yml:80-328` 单 job 串行 ktlint → 编译+单测 → instrumented 编译 → macrobenchmark → verify* → lint×2 → R8 → DEX → APK 契约 → 锁文件 diff，每步独立 `./gradlew --no-daemon`；缓存只有 Gradle user home，`gradle.properties:8-11` 明确 `configuration-cache=false`；`publish-android.yml:387-405` 用 `gh run list` + `sleep 30` × 90 轮询另一个 workflow 的结论；19 个 workflow 里 8 个是绑定在 `probe/*` 分支的一次性探针；`tv-quality-gates.yml:107,111,130,137` 仍有 `github.ref == 'refs/heads/codex/ycore-sign-20260830'` 的过期特判，且 TV 侧无发布流水线。

**建议**：拆 compile/unit-test/lint/package 四个并行 job 各自缓存；用 `workflow_run` 替代轮询（`release.yml:5-6` 已是正确范式）；合并探针 workflow 为带 `inputs.mode` 的单个；删过期分支特判。

### M8 版本与配置漂移

**证据**：`README.md:20` "AGP 9.1.1, Kotlin 2.4.21"，实际 `libs.versions.toml:3` 是 agp 9.4.1、Gradle 9.8.2；`docs/AGP9_MIGRATION_20260916.md:5,8` 的 targetSdk 36 与实体 37 不符；`AGENTS.md:9` 写"当前 1.1.7 符合规则"，`version.properties:3` 是 1.7.9（上轮已记，仍未改）。SDK 三件套在 4 个模块各写一遍（`composeApp:924,942,943`、`tvApp:145,149,150`、`phoneShared:14-15`、`tvShared:14-15`），签名块与 `buildTypes` 逐字重复，且 `packaging.resources.excludes` 已经开始分叉（composeApp 多一条 BouncyCastle 排除）。`config/lint/*-baseline.xml:2` 记录 `lint 9.1.1`，实际 AGP 9.4.1。

**建议**：SDK/JVM/ABI 进 `libs.versions.toml`，`android{}` 收进 convention plugin；README/AGENTS 里的版本号交给 `scripts/release_metadata.py` 校验或直接删除；baseline 重新生成。

### M9 工作树卫生

**证据**：根目录 26 个历史 APK（约 600 MB，已被 `.gitignore:21` 忽略但仍在盘上）；`artifacts/` 3.0 GB、`artifacts-local/` 9.3 GB、`tmp/` 1.8 GB；`.codex-calendar-optimization/` 86 MB 内含一份完整的 `.github/workflows` 副本（11 个 yml，含另一份 `publish-android.yml`），`find` 会先命中它——改错文件不会报错。`git count-objects` 报 3 个垃圾对象。

**建议**：清 `.codex-*`、`tmp/`、历史 APK；`git gc --prune=now`；agent 临时目录统一到 `.work/` 并加体检脚本。

### 其他值得排期的小项

- **`AppDependencies` 是 26 字段 god-bag**，构造点在 `MainActivity.kt:179-206` 与 `tvApp/…/TvApplicationGraph.kt:40-68` 逐字复制两份，被 10 个组件整体持有，且 app 层反向 import 5 个 feature 类。建议改为每 feature 一个窄 `XxxDeps`。
- **`di` 模块按技术层而非领域划分，且构造带启动副作用**：`di/AccountModule.kt:65-75` `single { TraktRepository(...).also { it.start() } }`（图构造即启动后台任务）；`MediaServerModule.kt:58` 绑定 `feature/servers` 的类。
- **`feature` 之间网状依赖 + UI 驱动别的 feature 的 Intent**：`feature/profile/AddServerDialog.kt:66` 定义、唯一调用方是 `feature/servers/ServersTabScreen.kt:490`，后者 `:87,140-250` 直接发 `ServersIntent`。
- **导航有三套模型**：Decompose `childStack` 持状态、Nav3 只做渲染、`RootComponent.kt:395-445` 手算深度（Profile 有 `-1` 修正），另有 1,572 行自研 zoom-back 放在 `core/designsystem`；新增一个 tab 要改 6 处。`FileBrowserScreen.kt:87` 的路由是"按目录深度重建整条栈"，路由 = 数据快照而非事件。
- **`core/data` 71 文件 / 21,468 行是"层 + 桶"**，混装 HTTP 适配、仓储、Preferences 族、缓存、outbox、日历；`core/navigation` 只有 25 行。这是 A2 所有环的中枢。
- **`useCleartextTraffic` 的 lint 静音**（已并入 S2）。
- **`scripts/` 52 个 `.py` + 25 个 `.sh`** 与 `verify*` 任务职责重叠，无常驻入口。
- **`harmonyApp`（仓颉）是第三套 UI 实现**（上轮 E2），随主端功能演进会持续落后。

---

## 7. 已确认良好项（避免误报，供对照）

| 项 | 证据 |
|---|---|
| 凭据不落明文：token 走 Android Keystore AES-GCM，AAD 绑定 namespace+key，信封带 magic/version/限长 | `androidMain/…/AndroidKeystoreSecureStore.kt:19-95,136-162,224-269` |
| 服务器 token 与持久化 JSON 分离（只存 ref，写 `commit()` 同步落盘） | `ServerRegistry.kt:1287-1335`、`CredentialPersistingSettings.kt:23-32` |
| 账号 access token 仅内存、refresh token 加密存储；token 只发往账号源（scheme/host/port 全等） | `AccountRepository.kt:63,1487,1548`、`AccountAccessTokenSource.kt:50-67` |
| 无 trust-all：全仓无自定义 `TrustManager/HostnameVerifier/sslSocketFactory` | grep 为空；`HttpClientFactory.android.kt:73-83` |
| 日志默认不外泄：Logcat 默认关闭 + 1h 窗口，统一脱敏（host/IP/Bearer/Cookie/query） | `SafeLogcat.android.kt:27-38`、`DiagnosticRedactor.kt:108-126` |
| 无 WebView / `addJavascriptInterface`；无 `println`/`Log.d` 直写 | grep 结果仅 1 处注释与 `SafeLogcat` |
| 备份/迁移关闭（`allowBackup=false` + 全 domain 排除）、权限克制、FileProvider 仅 `cache/updates/` | `AndroidManifest.xml:5-34,51-54,66-73`、`update_paths.xml` |
| 安装前四重校验：包名 + `versionCode >` + 签名摘要 + SHA-256/大小 | `AppUpdateManager.kt:514,1754-1777,1980`、`UpdatePackageIdentity.kt:10-23` |
| QuickJS 沙箱本体：无文件/进程绑定、32MB 堆、512KB 栈、1–60s 超时、安装预览禁网 | `SandboxWidgetRuntime.kt:25,31,64-69,144-214` |
| 本地代理只绑 `127.0.0.1`、随机 122-bit 路由 id、方法白名单、准入 16+64 槽 + 三重头部限额 | `AndroidYCoreHttpProxy.kt:593,878,2351`、`PlaybackProxyLimits.kt:39-58,90-156` |
| 服务端：强制 Bearer + 账号绑定、每 IP/账号连接上限、64KiB 帧、240 msg/10s、加入失败限流 | `Application.kt:403-467,341,506,695-714`、`WatchJoinFailureLimiter.kt:113-118` |
| 供应链：集中强制版本 + 与扫描器共用一份文件、依赖锁定、OSV 门 + SBOM、原生制品双重哈希 pin | `build.gradle.kts:31-101,114-124`、`security-overrides.properties`、`supply_chain_check.py` |
| 服务端错误响应不泄漏内部信息；`/watch/metrics` 需 bearer 或仅 loopback | `AccountRoutes.kt:321`、`WatchMetrics.kt:19-33` |
| 测试纪律：0 `@Ignore`、0 mock 框架、断言密度 4.2/用例、服务端用真 WebSocket + latch 而非 sleep | `WatchAdmissionLifecycleTest.kt:32-53`、`WatchTestSupport.kt:18-40` |
| Compose 稳定性配置判断正确（只列 3 条例外，拒绝收录 `List/Set/Map`）；四个大屏 `LazyColumn` 的 `key` 无遗漏 | `config/compose-stability.conf`、`HomeScreen.kt:483-768` 等 |

---

## 8. 建议路线图

**第 1 周（阻断风险，改动小、收益确定）**
1. S1 的 `WidgetHttp` 地址分类 + 禁重定向（单文件改动，消掉最大面）。
2. S2 的公网明文确认 + `InsecureBaseConfiguration` 从 ignore 改 baseline。
3. S3 的 `audit/` 停跟踪（先改 `.gitignore` 并 `git rm -r --cached audit`，历史清理单独排期）。
4. 配置（非代码）：生成 Ed25519 更新清单密钥对，写入 CI secret 与 `gradle.properties`，让已发布的包不再接受未签名清单。

**第 2–4 周（架构止血）**
5. 删除 `GlobalContext` 与 `runCatching{get()}`（48 + 12 处，机械替换）。
6. 统一 application scope + `onClose`，先治 `ServerRegistry.init` 与 6 个无主 scope。
7. 把 `PlaybackLaunchTimings` / `PlayerMediaItem` / `AudioEnhancementMode` 下沉 `core/playback`，加 `verifyLayering` 禁止 `core → feature`。
8. P1（引擎 scope）与 P2（代理正文超时）——这两条是稳定性的直接来源。
9. 补 ktlint 对 `tvApp/src` 的排除，namespace 拆 phone/tv。

**第 2 个月（结构）**
10. 抽出真正的 `:shared` 模块，`android{}` 收进 `build-logic`（同时解 A1、A5 的脚本部分、M8 的 4 处硬编码、M7 的 configuration cache）。
11. 拆 `core/model`，打断 account/data/sync 三角环。
12. 两个样板拆分：`AccountRepository` → 4 个服务；`DetailExecutor` → 播放启动 + 源选择状态机。
13. 接 detekt + Kover diff 门槛 + Roborazzi 截图基线。
14. 服务端：`WatchSocketHandler` 拆分、token 重校验缓存、`StatusPages`、`ServerLog` 带堆栈。

**第 3 个月（性能与收尾）**
15. 首页/详情页重组治理（P5/P6）+ 提交 `baseline-prof.txt` 与 benchmark 基线。
16. 代理路由表索引化（P8）、直通音频 buffer 池化（P7）、`readAt` 临界区收缩（P9）。
17. 文档索引化：`docs/README.md` + `guides/` 与 `history/` 分离，删除重复稿（`TECHNICAL_REVIEW_20261014*.md` 两份）。
18. 工作树清理（M9）与 CI 并行化（M7）。

---

## 9. 落地进展（2026-10-29）

### 已完成

**架构 / 依赖方向（对应第 2–4 周第 5–7 项，部分）**

`core → feature` 反向依赖从 8 个文件 19 处降到 2 个文件 3 处，且全部落在 androidMain 的"启动播放器"入口，commonMain 已完全无反向依赖。

| 下沉内容 | 从 | 到 |
|---|---|---|
| `PlaybackLaunchTiming(s)`、`AudioEnhancementMode` | `feature/player` | `core/playback` |
| `PlayerCustomHeaders`、`PlaybackProxyRoute(s)`、`PlaybackProxyRequest(s)`、`WidgetPlaybackRequest`(标签类)、`PlaybackForegroundTransitionGate` | `feature/player` | `core/playback` |
| `VideoCachePool`、`SecureMediaCacheKeyFactory`、`secureMediaCacheKeyForUrl`、`SelectivePlaybackCacheDataSourceFactory`、`isPersistentPlaybackManifestUrl` | `feature/player/VideoCache.android.kt` | `core/playback/PlaybackVideoCache.android.kt` |
| `AppIconVariant`、`currentAppIconVariant`、`setAppIconVariant`、`AppIconPreview`、`watchForAppIconSwitch`、`launcherIcon`、`notificationIcon` | `feature/profile` | `core/designsystem` |
| `widgetPlaybackItem` | `core/widget`（commonMain） | `feature/player`（commonMain） |

新增 `:composeApp:verifyCoreLayering`（`composeApp/build.gradle.kts`，挂到 `check`），扫描 `core` 的 commonMain/androidMain，只放行两条已记录的 android 入口：

- `core/cast/CastManager.android.kt` → `feature.player.PlaybackHttpDataSource`
- `core/widget/WidgetPlatform.android.kt` → `feature.player.PlayerActivity`、`feature.player.widgetPlaybackItem`

这两条的正解是注入式反转（把"启动播放器"与"媒体数据源"抽成 core 侧接口，由 feature 实现并注册），改动涉及启动装配，留待下一轮。守门保证不会再有新的越界。

**播放代理正文超时（P2）**

新增 `core/playback/PlaybackProxyWriteWatchdog.android.kt`：`Socket.writeResponseBodyWithWatchdog {}` 在每次写成功时打点，120 s 无进展即从看门狗线程关闭 socket（Java socket 没有写超时，关闭是对阻塞写唯一有效的解药）。已接入三处正文拷贝：

- `AndroidYCoreHttpProxy.copySource()`（缓存源）
- `AndroidPlaybackHttpProxy.copyDataSource()`（Media3 缓存源）
- `AndroidPlaybackHttpProxy` 的 `body.copyTo(...)`（平台传输直通）

预算取 120 s 而不是更短：暂停播放时客户端本来就不读，目标只是回收被死连接占住的槽位，不是惩罚空闲播放。

**仓库卫生 / 工程（M8、M9 部分）**

- `.gitignore` 停止跟踪 `audit/` 下的二进制证据（此前 12,400 文件 / 2.0 GB）。
- ktlint 不再对 `tvShared` 重复检查 `composeApp/src`（同一来源被两模块编译）。
- `README.md`、`AGENTS.md` 的版本号与工具链版本对齐到 `1.7.9 / AGP 9.1.1 / Kotlin 2.4.21`。
- 新增 `docs/README.md` 文档索引。

**安全（S5–S7）**

- S6：SMB 传输强制签名（`AndroidSmbMediaTransport` 增加 `jcifs.smb.client.signingPreferred`）。
- S7：release 构建不再编译进 TMDb token——客户端在"无账号无内置 token"时改走服务端匿名代理（`BackendTmdbRouting`），手机/TV release 的 `buildConfigField` 置空，发布流水线不再注入 `TMDB_TOKEN`。
- DLNA 投屏中继从"整段局域网可访问"收紧为"仅当前 renderer 可访问"。
- S4 经复核是误报（媒体客户端已有跨源鉴权头剥离），报告正文已更正。

### 验证

- `:composeApp:compileDebugKotlin`、`:phoneShared:compileAndroidHostTest`、`:composeApp:compileDebugAndroidTestKotlin` 全部通过。
- `:phoneShared:ktlintCheck` 通过。
- `:composeApp:verifyCoreLayering` 通过。

### 仍未做（下一轮）

1. `GlobalContext.get()` 48 处与 12 处 `runCatching { get<T>() }` 的收口；DI 单例统一 application scope + `onClose`（含 `ServerRegistry.init` 的 `while(true)`）。
2. 播放引擎自建 IO scope（`PlayerRoot.kt:163` 的 `rememberCoroutineScope` 注入引擎）。
3. `AccountRepository`/`DetailExecutor` 拆分，`core/model` 与 account/data/sync 三角环。
4. detekt + Kover diff 门槛 + Roborazzi 截图基线 + `baseline-prof.txt`。
5. 首页/详情页重组治理（P5/P6）与服务端 `WatchSocketHandler` 拆分。

---

## 10. 第二批落地（2026-10-29 下午）

### 性能热路径（P1、P3–P6 全清）

| 项 | 改动 | 文件 |
|---|---|---|
| P1 | 播放引擎不再借用 Compose 的组合作用域：新增 `engineScope = rememberCoroutineScope { Dispatchers.Default }`，只用于 `createVideoEngine` 与 `PlaybackEngineSlot`；UI 侧仍用原 scope | `feature/player/PlayerRoot.kt` |
| P3 | 学习记录改为内存快照：构造后在专属线程预热，`load()` 只读快照（冷启动首次仍回退一次磁盘以保证不丢历史），`replace()` 先更新快照再异步落盘 | `core2/android/AndroidYPlaybackLearningStore.kt` |
| P4 | 21 天窗口合并移入 `Dispatchers.Default`（纯 CPU，不再占用调用方调度器） | `core/data/AiringCalendarRepository.kt` |
| P5 | `featuredSlides` 从 `get()` 派生改为 `by lazy`，每个 state 实例只跑一次 6 步流水线 | `feature/home/HomeStore.kt` |
| P6 | 网络类别从"每次重组调 ConnectivityManager（2–3 次 Binder IPC）"改为订阅 `playbackNetworkClasses()`；该 flow 提升为 `expect/actual` 以便 commonMain 使用 | `feature/detail/DetailScreen.kt`、`core/network/PlaybackNetwork*.kt` |

### DI 生命周期（A4，部分）

- `ServerRegistry` 的 `while(true)` 消费者改为类字段 `persistScope` + `close()`，不再是无主协程。
- `AccountRepository`、`PlaybackSyncManager`、`WatchTogetherClient` 新增 `close()`（取消自有 scope；`WatchTogetherClient` 同时取消连接 Job）。
- 四个 Koin 定义接上 `onClose { it?.close() }`：`AccountModule`（2 处）、`MediaServerModule`、`WatchTogetherModule`。
- 复核 `PlaybackDeviceCapabilities`：音频/显示回调确实从不注销，但 provider 是 Koin `single`，进程内只创建一次，属应用生命周期监听，不构成泄漏——不需要改。

### 新增依赖方向守门（无外部依赖，挂在 `check`）

| 任务 | 规则 |
|---|---|
| `verifyCoreLayering` | `core` 不得 import `feature`，只放行 2 个 android 入口的 3 条 import |
| `verifySourceSize` | 超过 1600 行的文件必须待在 allowlist 里且只能变小；新文件超限直接失败 |
| `verifyServiceLocator` | `core` 内 `GlobalContext.get()` 按文件计数，只能减少 |

三个守门在本次改动中就各自生效过一次（`AiringCalendarRepository` 因新增 2 行被 `verifySourceSize` 拦下，基线随后更新到 1811）。

### 仓库卫生（S3 完成）

`git rm -r --cached audit` 已执行，索引内 `audit` 文件从 12,400 降到 0；`.gitignore` 已覆盖 `audit/`、`artifacts/`、`artifacts-local/`、`tmp/`、`*.apk`。磁盘上的 13.9 GB 历史产物与 26 个 APK 仍未删除（不可逆，等确认）。

### 验证

`:composeApp:compileDebugKotlin`、`:phoneShared:compileAndroidHostTest`、`:phoneShared:ktlintCheck`、`:composeApp:verifyCoreLayering`、`:composeApp:verifySourceSize`、`:composeApp:verifyServiceLocator` 全绿。

### 仍待处理

| 项 | 说明 |
|---|---|
| A1 | `composeApp/src` 被 phoneShared/tvShared `srcDir` 复用，需抽真正的 `:shared` 模块 + `build-logic` |
| A3 | 全仓仍有 104 处 `GlobalContext.get()`（core 内 12 处已被守门冻结），需逐处构造注入 |
| A5 | 21 个 >1600 行类待拆（`AccountRepository`、`DetailExecutor`、`ServersStore` 等） |
| A6 | 详情页多状态流与 26 字段 state |
| A7 | 服务端 `watchTogetherModule` 1,349 行单函数 |
| P7–P10 | 直通音频 `allocateDirect`、代理路由表 O(n²)、`readAt` 锁粒度、`baseline-prof.txt` |
| M1/M2 | UI 回归（Compose 测试/Roborazzi）、detekt/Kover 门槛（需要新增构建插件与依赖下载） |
| M3–M7 | 测试分布、服务端每连接 token 重校验、房间索引锁、CI 串行、`expect/actual` 滥用 |
| S5 | 更新清单公钥属于配置项：需要在 CI 生成并写入 Ed25519 密钥 |

---

## 11. 清单收口（2026-10-29 傍晚）

### 已闭环

- **S5** 属配置项，代码侧早已 fail-fast；新增 `scripts/generate-update-manifest-key.sh`（openssl Ed25519，公钥按 `yfuse.updateManifestPublicKey` 需要的 base64 SPKI 形式输出，私钥 0600 PEM 且不落仓库），并已在 `docs/android-release.md` 的密钥章节指向它。真正生成密钥、写入 CI secret 由仓库所有者执行——私钥不该由助手代生成保存。
- **M9 索引部分**：`audit/` 12,400 个文件已出索引；`artifacts/`、`artifacts-local/`、`tmp/`、`*.apk` 均已在 `.gitignore`。
- **M9 磁盘部分（未执行，需所有者决定）**：`artifacts/` 3.0G 是历史**已签名 release APK**、`artifacts-local/` 9.3G 含集成分支备份与服务器部署包、`tmp/` 1.8G 是构建日志与诊断包。这些不可再生（不是 `build/` 产物），删除不可逆，故保留原处并等待明确确认与备份。

### A3 的剩余 104 处为什么不能"一把替换"

`core` 内的 12 处在 Android 系统组件入口里：`Service`（`CastRelayService`）、`BroadcastReceiver`（`DownloadNotificationActions`）、`WorkManager` worker（`OfflineDownloadExecution`）、OkHttp 拦截器（`HttpClientFactory`）、`actual` 顶层单例（`WidgetPlatform`、`WidgetCutoutPreferences`）。这些对象由系统或框架实例化，构造注入无处安放，定位器（或等价的显式 `AppGraph`）是唯一可选形态。

其余约 92 处在 `feature`/`app` 层，可以用构造注入替换，但要逐个改动构造签名与其 Compose 调用点；`verifyServiceLocator` 已冻结 `core` 侧不再增长，改造可以按 feature 分批推进而不回退。
