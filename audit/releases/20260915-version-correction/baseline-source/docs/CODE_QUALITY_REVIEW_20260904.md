# 全项目代码质量审查与优化计划 · 2026-09-04

审查基线：`master` @ `51d3979`（分支 `claude/code-quality-review-wdl12g`）。
承接 `docs/YCORE_KERNEL_COMPARISON_20260903.md`、`docs/YCORE_GAP_REVIEW.md`、
`docs/YCORE_MATURITY_GAP.md`、`docs/PRODUCT_AND_CODE_REVIEW.md`。

> **环境限制**：本沙箱代理封禁 `dl.google.com`，AGP 无法解析，Gradle/Kotlin 任务不能运行。
> 本文的 Kotlin 结论来自静态阅读；**可执行的部分**（ktlint CLI 1.3.1 对照仓库基线、
> `ycore-native` cmake/ctest、`scripts/native` 头文件单测、ASan/UBSan、GitHub Actions 运行记录）
> 都已实际运行，结果见第一章。

---

## 零、总体判断

| 维度 | 结论 |
| --- | --- |
| 工程流程 | **主线 CI 已连续红 5 天**（9 月 1 日起 25 次推送全部失败），单元测试、Android lint、编译门禁在此期间一次都没跑到。这是本轮最需要先处理的问题。 |
| 自研内核 Core2 | 上次审查列出的 4 个确定性缺陷已全部修复；执行层代码质量高于应用层。剩余问题集中在测试覆盖（androidMain 57 个文件中 38 个没有对应单测）与三个播放器实现之间的重复。 |
| Legacy 播放层 | `GAP_REVIEW` 的 P0-A/P0-B 与失败分类三个历史缺陷已修复并结构化。`PlayerRoot` 单个 Composable 3033 行仍是全仓最大的技术债。 |
| 应用层 | 状态管理规范（215 处 `update {}` 对 10 处非原子 `value = copy()`），安全边界基本清楚。硬编码中文文案 4038 处、0 个字符串资源，国际化为零。 |
| 服务端 | 鉴权、口令散列（PBKDF2 600k）、限流、容器非 root 都做对了。`Application.watchTogetherModule` 单函数 1341 行、`CalendarIngestion.kt` 2869 行需要拆分。 |
| 原生层 | `ycore-native` 在 `-Wall -Wextra -Wshadow -Wconversion` 下 0 警告，ASan/UBSan 通过；4 个头文件单测全部通过。JNI 桥接生命周期由 Kotlin 侧单线程串行化，未发现竞态。 |

---

## 一、实测结果

### 1.1 GitHub Actions（`quality-gates-v2.yml`，master）

| 项目 | 数据 |
| --- | --- |
| 最近 100 次 master 运行 | 83 失败 / 10 成功 / 7 取消 |
| 最后一次绿色 | run #906 · 2026-08-30 13:03 · `f817a6b` |
| 首次连续失败 | run #917 · 2026-09-01 08:26 · `a6f9f4e` |
| 失败阶段 | 第一步 "Reject new ktlint violations" 即退出；后续 "Compile and run unit tests"、`lintDebug`、`assembleRelease` 从未执行 |
| 附带失败 | SBOM 上传因 "Artifact storage quota has been hit" 失败 |
| 其他工作流 | `tv-quality-gates.yml`、`codeql.yml` 最近 3 次 master 运行同样全部失败；`publish-android.yml` 最近 3 次失败 |

含义：9 月 1 日以来合入 master 的约 25 个提交（包括 1.0.28 打包所基于的树）**没有任何一个通过过单元测试或编译门禁**。README 声明的"新违规即失败"策略在事实上已经失效，因为团队已经习惯了红灯。

### 1.2 ktlint 1.3.1 本地对照基线

| 模块 | 基线条数 | 基线外违规 | 说明 |
| --- | --- | --- | --- |
| composeApp | 1505 | **702** | 463 条在基线已有的 70 个文件里（其中 63 个文件违规数 ≤ 基线数 → 基线按行号匹配，代码上移后失配，即"基线漂移"）；233 条在 53 个基线未收录的新文件（`tv/ui/TvUiComponents.kt` 22、`TvDiscoveryCalendarScreens.kt` 20、`TvApp.kt` 14 …）；6 条在 `build.gradle.kts` |
| watchTogetherServer | 1536 | **456** | `CalendarOcrCache.kt`（新文件）84 条；`CalendarIngestion.kt` 213 条对基线 147 条 |
| tvApp | 无基线 | 5 | `config/ktlint/` 里没有 `tvApp-baseline.xml`，任何违规都直接失败 |
| mdkAndroid / watchTogetherProtocol / macrobenchmark | 0 | 0 | 干净 |

规则分布（基线外）：`chain-method-continuation` 162+172、`property-naming` 143、`function-signature` 88+49、`argument-list-wrapping` 73+84。全部是可用 `ktlint --format` 自动修复的排版规则，没有一条是语义性的。

### 1.3 原生层

| 项目 | 结果 |
| --- | --- |
| `cmake -S ycore-native` + `ctest` | 1/1 通过 |
| `ycore.cpp` `-Wall -Wextra -Wshadow -Wconversion` | 0 警告 |
| `-fsanitize=address,undefined` 运行 `ycore_test` | 退出码 0，无报告 |
| `scripts/native/ycore_{disc_uri,gpu_capability,overlay_plane,tone_map}_test.cpp` | 4/4 编译通过、运行通过、0 警告 |
| `ycore_demux_jni.cpp`（2780 行，无法在此编译） | 17 处 JNI 局部引用创建对 14 处 `DeleteLocalRef`、25 处 `ExceptionCheck`、15 处互斥；`native_close` 本身无锁，但 Kotlin 侧 `AndroidDemuxReadAheadNode.close()` 通过 `runOnOwner` 在唯一的解复用线程上执行 `delegate.close()`，读与关闭天然串行，**未发现竞态** |

### 1.4 全仓静态指标（main 源集，不含测试）

| 指标 | 数值 |
| --- | --- |
| Kotlin 文件 / 行数 | 647 文件；composeApp 221k 行、server 20.5k、harmony 4.4k（Cangjie） |
| >1000 行文件 / >1500 行文件 | 39 / 21 |
| 单函数 >150 行 / >300 行（花括号精确匹配复核前 15 名） | 约 110 / 39 |
| `!!` | 32 |
| `catch (_: Throwable)` | 88 处；含 `catch Throwable` 的 54 个文件里只有 20 个同时处理 `CancellationException` |
| `runBlocking` | 21 处，全部在 `core2/android`（MediaDataSource/DRM/字幕/HTTP 代理同步契约处） |
| `GlobalScope` / `TODO` / 通配符 import | 0 / 0 / 0 |
| `@Suppress` | 83 |
| 硬编码中文字符串字面量 | **4038 处，241 个文件**；`res/values/` 没有 `strings.xml` |
| 测试 | 2077 个 `@Test`（commonTest 262 文件、androidUnitTest 114、instrumented 2、server 23 文件 163 用例、protocol 0、tvApp 0） |
| 源码扫描式测试（把 .kt 当文本断言） | 6 个文件 |

---

## 二、上次审查问题复核

### 2.1 Core2（`YCORE_KERNEL_COMPARISON_20260903.md`）

| 问题 | 状态 | 证据 |
| --- | --- | --- |
| Dolby Vision 配置就地破坏共享 `MediaFormat` | ✅ 已修复 | `AndroidMediaCodecVideoNode.kt:477` `copyForCodecAttempt()` 在 API 29+ 走 `MediaFormat(this)` 拷贝；变体 `applyTo` 只作用于拷贝 |
| >8 声道回落 stereo mask 不 downmix | ✅ 已修复 | `AndroidAudioTrackRenderNode.kt:271` `check(channelMask != CHANNEL_INVALID)` 显式失败并交给下一个后端；10/12 声道走 API 32 高度声道掩码 |
| ABR 用墙钟估算缓冲 | ✅ 已修复 | `AndroidAdaptiveCore2YPlayer.kt:925` `bufferedDurationUs` 来自 `AndroidMediaExtractorReadAheadNode.bufferedDurationUsLocked()` 真实队列 |
| NativeDirect 解复用同步跑在 pump 上 | ✅ 已修复 | `AndroidNativeDirectYPlayer.kt:385` 改用 `AndroidMediaExtractorReadAheadNode`（独立线程） |
| 纯音频媒体 | ✅ 已落地 | `YPlaybackStrategy.kt`、`AndroidCore2MediaProbe.kt` 有 audio-only 路径；release-notes 1.0.24 亦记录 |
| 预取窗口 12 块封顶（高码率原盘 2–5 s） | ⏸ 未动 | 上次已建议等真机 rebuffer 数据再定，维持 |

### 2.2 Legacy（`YCORE_GAP_REVIEW.md` / `YCORE_MATURITY_GAP.md`）

| 问题 | 状态 | 证据 |
| --- | --- | --- |
| P0-A 就绪判据读中文文案 `contains("等待")` | ✅ 已修复 | `YCorePlayerRuntime.android.kt:242,248` 改用 `PlaybackOutputReadiness.Rendering` 枚举 |
| P0-B 宽限窗口不重置 | ✅ 已修复 | `PlaybackRuntimeFaultDetector.kt:131-132` 条件恢复即清空 |
| 失败分类靠中文句子子串 | ✅ 已修复 | `ExoVideoEngine.kt:1111-1172` `failPlayback(..., kind = PlaybackFailureKind.X)` 结构化携带；`classifyPlaybackFailure` 仅作 `PlayerRoot.kt:2121` 的 `?:` 兜底 |
| 无 A/V 同步测量 | ✅ 已落地 | 架构文档记录 Exo/mpv 时钟差采样 |

### 2.3 应用层（`PRODUCT_AND_CODE_REVIEW.md`）

| 问题 | 状态 |
| --- | --- |
| 图片 `quality=90` / 未用 WebP | 未核到改动，维持为 P2 建议（需在目标 Emby 版本验证 `format=webp`） |
| TMDB 日历扇出无并发上限 | 未复核到 Semaphore；维持 P2 |

---

## 三、新发现

### P0 · 流程与发布完整性

**P0-1 主线质量门禁失效 5 天，测试从未执行。** 见 1.1。`quality-gates-v2.yml:53-66` 在 ktlint 失败后 `exit`，后面的编译、单测、lint、assembleRelease 全部被跳过。同时 `repackage-android-signed.yml:35` 已把 `VERSION_NAME` 写成 `1.0.27`，master 提交标题为 "package merged master as 1.0.28"，而 `version.properties` 仍是 `1.0.24 / 186`，`release-notes.txt` 最新条目也是 1.0.24。也就是说 1.0.27/1.0.28 的打包**没有对应的版本记录、发布说明和绿色门禁**。
建议：(a) 用一次显式的"格式化债务清理"提交跑 `ktlint --format` 并重新生成四个基线（README 允许这样做，且这批违规全是自动可修的排版规则），补 `tvApp-baseline.xml`；(b) 把 ktlint 步骤改成 `continue-on-error` 并在 job 末尾统一判定，让编译和单测在排版失败时也能给出信号；(c) 清理 Actions artifact 配额；(d) 为 1.0.27/1.0.28 补 `version.properties` 与 release-notes 或撤回预发布。

### P1 · 功能与安全

**P1-1 全局放开明文流量。** `network_security_config.xml:3` `<base-config cleartextTrafficPermitted="true" />`。自建 Emby 走 HTTP 是产品需求，但这条配置同时放开了更新清单、TMDB、弹幕源、Plex 云端等所有域。建议：`base-config` 改为 `false`，用户自建服务器通过 `<domain-config cleartextTrafficPermitted="true">` 无法动态配置，因此改为在应用层允许 http 的 Emby URL 而在 NSC 层只放开必要的域；至少把 `AppUpdateManager`、账号服务、TMDB 这三类固定域列入 `cleartextTrafficPermitted="false"` 的 `domain-config`。

**P1-2 更新源是裸 IP 的 HTTPS，且保留 HTTP 遗留清单。** `AppUpdateManager.kt:45` `https://47.112.219.60/yfuse/update-v2.json`；`publish-android.yml:57` 仍发布 `http://47.112.219.60/yfuse/update.json`。代码里没有任何证书固定或自定义信任（这是对的），因此它依赖系统 CA 对 IP SAN 证书的验证；若线上证书是自签，更新会静默失败或依赖 P1-1 的明文放行。APK 完整性仅靠清单里的 SHA-256（`:426`），没有校验 APK 签名者与当前安装签名一致（Android 安装器会做，但在下载完成前不能提前拒绝）。建议：迁到域名 + 公共 CA；在下载完成后、调起安装前用 `PackageManager.getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` 比对签名者；删除遗留 HTTP 清单发布。

**P1-3 `catch (_: Throwable)` 吞掉协程取消。** 88 处 `catch Throwable`，54 个文件中 34 个没有 `CancellationException` 的再抛出。在 `Store`/`Repository` 的 `launch` 体内这会把取消变成"失败并继续"，典型后果是切页后旧请求的结果仍写回状态。建议：引入统一的 `runCatchingCancellable {}` / `Throwable.rethrowIfCancellation()` 扩展，并在 ktlint 自定义规则或 detekt 里禁止裸 `catch (Throwable)`。

**P1-4 三个非原子状态更新点。** `PlaybackSyncManager.kt`（4 处）、`ServersTabComponent.kt`（3 处）用 `x.value = x.value.copy(...)`，其余 215 处都用 `update {}`。同步管理器正是多协程并发写入的地方，读-改-写会丢更新。建议统一改 `update {}`。

**P1-5 服务端日历抓取把截图发给第三方 OCR。** `CalendarIngestion.kt:1799,2808` 把渲染图 POST 到 `api.ocr.space`。图片内容是公开的播出日历，隐私风险低，但 (a) 该请求没有走统一的出站超时/重试策略之外的熔断，(b) 失败路径用 `System.err.println`（全文件 8 处）而不是结构化日志，(c) 2 处 `Thread.sleep`（`:1831,1877`）在 Ktor 协程上下文里阻塞线程。建议：接入 Ktor 的日志器，`delay()` 替换 `Thread.sleep`，OCR 提供方抽成接口便于自托管 PaddleOCR 时替换。

### P2 · 结构与可维护性

**P2-1 巨型函数 / 巨型文件（花括号精确匹配）。**

| 位置 | 行数 |
| --- | --- |
| `feature/player/PlayerRoot.kt:106` `PlayerRoot()` 单个 Composable | **3033** |
| `watchTogetherServer/.../Application.kt:343` `watchTogetherModule()` | 1341 |
| `core2/android/AndroidAdaptiveCore2YPlayer.kt:327` `runLoop()` | 1105 |
| `feature/detail/DetailScreen.kt:124` `DetailScreen()` | 870 |
| `feature/player/PlayerSettingsPanel.kt:72` `SettingsPanel()` | 826 |
| `feature/profile/ProfileScreen.kt:234` `ProfileScreen()` | 752 |

`gradle.properties` 已经为此把 Kotlin daemon 堆提到 4 GB 并写明原因是 `PlayerActivity`/三后端编排。这不是风格问题：3000 行的 Composable 意味着任何状态变化都重组整棵树，也是 `YCORE_ARCHITECTURE.md` "新探针或后端实现接口而不是往 `PlayerRoot` 加条件"这条规则失守的直接证据。

**P2-2 Core2 三个播放器实现之间的重复。** `AndroidNativeDirectYPlayer`（124 KB）与 `AndroidEnhancedPlaybackSession`（82 KB）共享 30 个同名私有方法（`pump/drainAudio/drainVideo/feedInput/flushAudio/queueAudioSample/seekTo/selectAudioTrack/refreshAdaptiveBufferPlan/…`），与 `AndroidAdaptiveCore2YPlayer` 共享 14 个。这些是同一条"音频时钟驱动的 pump 循环"的三份拷贝。建议抽 `YPumpLoop`/`YAudioClockDriver` 作为共享节点，三个 session 只保留路由差异。

**P2-3 国际化为零。** 4038 处中文字面量、无 `strings.xml`。Compose Multiplatform 的 `stringResource` 已可用；不做多语言也应该先把文案集中，否则 P0-A 那类"逻辑挂在文案上"的缺陷会再出现。前 6 个文件（`PlayerSettingsPanel` 141、`CalendarScreen` 138、`ServersTabScreen` 124、`ProfileSettingsScreens` 117、`ProfileScreen` 107、`AccountSettingsScreen` 100）占 18%。

**P2-4 LazyList 缺 `key`。** 50 处 `items(` 只有 22 处带 `key =`；上一轮统计是 37/26，新增的 13 处（主要在 `tv/ui`）全部没有 key。

**P2-5 `runBlocking` 集中在同步 IO 契约边界。** 21 处全部在 `core2/android`：`AndroidTransportMediaDataSource.kt:351,684`（`MediaDataSource.readAt` 是同步 API，合理）、`AndroidYCoreHttpProxy.kt:1093,1154`、`AndroidYCoreDrmSession.kt:237`、`AndroidExternalSubtitleLoader.kt:87`、`YCoreDiscBlockSource.kt:90`。`AndroidTransportMediaDataSource.kt:337` 在重试路径里 `Thread.sleep(delayMs)`，这会把 MediaExtractor 线程钉住，seek 期间用户感知为"拖不动"。建议：重试等待改为可被 `closed` 打断的 `Condition.await(timeout)`；HTTP 代理与字幕加载改为在各自的协程作用域内完成，不用 `runBlocking`。

**P2-6 源码扫描式测试。** 6 个测试（`AndroidHttpEnginePolicyTest`、`UpdateDownloadBoundaryTest`、`UpdateResumePolicyTest`、`OfflineMediaSecurityTest`、`RoomPlaylistTest`、`WatchTogetherServerTest` 部分）用正则断言 `.kt` 源码文本。它们对重构极其脆弱，而且 `PRODUCT_AND_CODE_REVIEW.md` 已记录它阻止了一次合理的 HTTP 栈合并。建议逐个替换为行为测试（如用 MockEngine 断言请求头，而不是断言源码里出现 `OkHttp.create()`）。

**P2-7 测试覆盖空洞集中在最厚的执行层。**

| 包 | 文件数 | 无同名测试 | 最大的未测文件 |
| --- | --- | --- | --- |
| `core2/android` | 57 | 38 | `AndroidNativeDirectYPlayer` 124k、`AndroidEnhancedPlaybackSession` 82k、`AndroidAdaptiveCore2YPlayer` 78k |
| `feature/player`（androidMain） | 57 | 37 | `PlayerRoot` 149k、`MpvVideoEngine` 94k、`ExoVideoEngine` 83k |
| `core/data` | 53 | 29 | `AiringCalendarRepository` 72k、`EmbyBrowseService` 31k |
| `tv/` | 28 | 16 | 全部 UI |
| `update/` | 3 | 3 | `AppUpdateManager` 78k（安全关键） |
| `feature/*Screen` | 21 | 21 | 无任何 Compose UI 测试；instrumented 仅 2 个文件 |

相对地，`core2` commonMain 50 个文件只有 7 个没测、`core/playback` 24 个只有 1 个没测——策略层测试很扎实，缺的是 Android 执行层和 UI。

**P2-8 文档冗余。** `docs/` 下 6 份 YCore 审查/差距文档（`YCORE_GAP_REVIEW`、`YCORE_MATURITY_GAP`、`YCORE_KERNEL_COMPARISON_20260903`、`PRODUCT_AND_CODE_REVIEW`、`CLOUD_MERGE_REVIEW`、`ycore-22-verification-20260820`）大多已在文首标注"历史依据，不代表当前状态"。建议保留 `YCORE_ARCHITECTURE`/`YCORE2_ARCHITECTURE`/`YCORE_VALIDATION_MATRIX` 三份作为现状文档，其余移入 `docs/history/`。`docs/superpowers/` 只剩 7 月的一份计划和一份设计稿，已过时。

### P3 · 规范

- `build.gradle.kts`（composeApp 6 处、tvApp 4 处）有 ktlint 违规；Gradle 脚本也在 `ktlintCheck` 范围内。
- `@Suppress` 83 处，建议在基线清理时逐一复核是否仍需要。
- `!!` 32 处，数量可控，集中在 Android 平台回调处。
- CI 工作流 14 个全部用 40 位 SHA 固定 action、全部声明 `permissions`、无 `pull_request_target`、`github.event.*` 只进入 `env` 而不是内联到 `run`——这部分做得好。

---

## 四、自研内核（YCore / Core2）专项

### 4.1 现状

- 策略层（`strategy/ capability/ adaptive/ bitstream/ dolby/`）纯函数、可测、边界干净，测试覆盖近乎完整。
- 执行层四条路由（Tunnel / Direct / Enhanced / Adaptive）都已具备独立读前线程、真实缓冲量反馈、内存预算封顶、结构化失败种类。
- 原生层 ABI 头稳定，主机可编译可测，警告与 sanitizer 干净。
- 上次审查的全部确定性缺陷已关闭（见 2.1）。

### 4.2 仍需处理

1. **执行层单测缺失**（P2-7）。`pump()` 循环、`seekTo` 的位置保持、EOS 处理、音频路由切换都只能靠真机验证。建议为 `AndroidNativeDirectYPlayer` 引入可注入的 `YCodecNode`/`YAudioSink` 假实现，把 pump 循环变成可在 JVM 上按帧步进的状态机测试。
2. **三份 pump 循环的重复**（P2-2）。修一个缺陷要同步改三处；本周 `fix(core2)` 提交中有四次是"同一问题在另一条路由复现"。
3. **同步 IO 边界的阻塞**（P2-5）。`Thread.sleep` 重试与 `runBlocking` 在 extractor 线程上，seek 体验与关闭时延受影响。
4. **高码率预取封顶**仍待真机 rebuffer 数据决策，不要先验修改。
5. **`YCORE_VALIDATION_MATRIX` 中"真机矩阵 / 8h 连续 / 24h 队列长稳"仍是 `NotMeasured`。** 门禁评估器已存在，但证据没有产出；在 CI 红灯期间这条更无从谈起。

---

## 五、优化计划

按"先恢复信号，再还债，再重构"的顺序。每一阶段可独立合并。

### 阶段 0 · 恢复 CI 信号（1–2 天，最高优先级）

1. 显式的格式化债务清理提交：`./gradlew ktlintFormat`，重新生成 4 个基线，新增 `tvApp-baseline.xml`（或让 tvApp 零违规不需要基线）。
2. `quality-gates-v2.yml` 的 ktlint 步骤改为 `continue-on-error: true` + 末尾汇总失败，保证编译/单测/lint 每次都跑。
3. 清理 Actions artifact 存储；SBOM 上传改 `retention-days: 14`。
4. 补 1.0.27/1.0.28 的 `version.properties` 与 `release-notes.txt`，或撤回预发布资产。
5. 在分支保护上要求 `Android quality gates` 通过才能合入 master。

验收：master 连续 3 次推送绿色；`testDebugUnitTest` 与 `:watchTogetherServer:test` 的报告出现在 Actions 里。

### 阶段 1 · 安全与正确性（1 周）

1. P1-1 收紧明文流量到用户自建服务器域。
2. P1-2 更新源迁域名 + 公共 CA；安装前签名者比对；下线 HTTP 遗留清单。
3. P1-3 引入 `rethrowIfCancellation()`，脚本批量改 88 处 `catch Throwable`；加 detekt `TooGenericExceptionCaught` 规则。
4. P1-4 `PlaybackSyncManager`/`ServersTabComponent` 改 `update {}`。
5. P1-5 服务端 `Thread.sleep` → `delay`，`System.err.println` → Ktor logger。

### 阶段 2 · 执行层可测性（2–3 周）

1. 抽取共享 pump 循环（`YPumpLoop` + `YAudioClockDriver`），三条路由收敛到一份实现；每合并一条路由跑一次现有真机脚本 `scripts/run-ycore-device-gates.sh`。
2. 为 pump 循环写 JVM 状态机测试（可注入假 codec/audio sink），覆盖：首帧、EOS、seek 位置保持、音频路由变化、pause/resume 时钟冻结。
3. `AndroidTransportMediaDataSource` 重试等待改为可中断等待；`runBlocking` 边界逐个评估。
4. 替换 6 个源码扫描式测试为行为测试。
5. 产出 `YCORE_VALIDATION_MATRIX` 要求的真机与长稳证据（这依赖阶段 0 的绿色 CI）。

### 阶段 3 · 应用层结构（4–6 周，可与阶段 2 并行）

1. `PlayerRoot` 拆分：按 `YCORE_ARCHITECTURE.md` 的边界表，把探针、交接、故障检测、Cast、弹幕分别下沉到已有的 `core/playback` 组件，`PlayerRoot` 只保留组合与生命周期；目标 < 600 行。同步下调 `kotlin.daemon.jvmargs`。
2. `Application.watchTogetherModule` 按路由/房间/账号/日历拆成 `Routing` 扩展；`CalendarIngestion.kt` 拆成 fetch / render / OCR / parse 四个文件。
3. 文案集中：先建 `strings.xml`（或 CMP `Res.string`），按文件从 P2-3 的前 6 个开始迁移；禁止新增中文字面量的 lint 规则。
4. LazyList 补 `key`（28 处）。
5. `core/data` 29 个未测文件里先补 `AiringCalendarRepository`、`EmbyBrowseService`、`DanmakuRepository` 三个最大的。
6. 文档归档（P2-8）。

### 阶段 4 · 持续项

- 每月一次真机矩阵 + 长稳，结果回填 `YCORE_VALIDATION_MATRIX`。
- 基线只减不增：CI 增加"基线条数不得上升"的检查。
- 高码率预取封顶、图片 WebP、TMDB 并发上限：等数据再决定。

---

## 六、本次未能覆盖的范围

- Kotlin 编译与单元测试未运行（环境限制）；所有 Kotlin 结论需由阶段 0 之后的绿色 CI 复核。
- `harmonyApp`（48 个 Cangjie 文件、72 处 FFI 引用）只做了规模与真实性判断，未逐文件审查。
- `ycore_demux_jni.cpp`、`ycore_vulkan_renderer.cpp`、`stream_yfuse_bluray.c` 无法编译，仅做了 JNI 引用/异常/互斥计数与关闭路径的静态核对，未逐函数审查内存安全。
- 真机行为（Dolby Vision、蓝光原盘、Cast）未验证。
