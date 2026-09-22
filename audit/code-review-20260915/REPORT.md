# Yfuse 代码审查与优化建议

审查日期：2026-09-15。基线提交：`04dc5086eccaacdf3013080b6ecc5d353f1663ee`。

## 结论与范围

主清单共 **17 项：P1 高优先级 2 项、P2 中优先级 11 项、P3 低优先级 4 项**，包含已确认逻辑缺陷、资源增长风险和维护性建议。另列 2 项尚未接入当前 Android 播放路径的 native C ABI 问题。没有发现足够证据支持 P0 定级，也没有通过真机堆分析确认 OOM、ANR 或持续内存泄漏。

审查覆盖手机端核心业务、播放和投屏调用链、TV 备份、服务端认证/房间/日历、依赖声明及扫描脚本；抽查 MDK/native 资源生命周期。生产 Kotlin 规模为 808 个受 Git 跟踪的 src 文件，无法将本轮重点审查等同逐行验证全部代码。Harmony、第三方播放器内部实现、真机性能和完整 Android 回归不在本轮已验证范围。

本轮新增此目录的报告、探针及验证记录，业务源码与版本配置未修改，未打包。下面的“验证建议”是修复时应补充的回归用例，除验证记录明确说明外，不代表已经执行。

## P1：优先修复

### 1. 房间 clientId 缺少跨账号归属检查，可错误授予房主权限

- **位置**：[Application.kt:1041](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt:1041)、[房主检查:1068](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt:1068)、[授权写入:1109](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt:1109)。
- **问题**：生产模式按认证账号查询 membership，但客户端 ID 由消息提供。新账号使用已存在的房主 ID 时，membership 为空；房主凭据校验仅对已有 membership 执行，随后新连接取得当前 hostEpoch 并覆盖原 participant。公开 ID 不应作为权限凭据。
- **影响**：知道房间码且拥有账号的其他人可能取代房主，原房主连接还会被关闭。此结论来自完整授权调用链，未对线上服务执行复现。
- **建议**：在房间锁内保证 clientId 与账号一一对应；将“创建者首次进入新房间”作为明确分支，已有房间的房主恢复必须验证归属及 capability。
- **验证建议**：开启真实认证模式，用两个不同账号验证重复 clientId 被拒绝、原房主权限与连接保持。现有非认证测试以 clientId 合成账号，覆盖不到这个边界。

### 2. 改密与旧密码登录竞态可留下有效会话

- **位置**：[AccountService.kt:293](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountService.kt:293)、[创建会话:322](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountService.kt:322)、[AccountStore.kt:577](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountStore.kt:577)。
- **问题**：登录读出旧密码摘要，在锁外完成 KDF 验证后直接创建会话；期间另一请求可完成改密并删除会话。createSession 事务未复核凭据版本，仍能在清理之后插入旧密码验证产生的会话。
- **影响**：改密时撤销既有登录状态的语义被并发登录突破。改密自身有摘要比较保护，但登录落库没有对应保护。
- **建议**：引入凭据版本，在创建会话事务内比较验证时版本；不一致则拒绝。保持耗时 KDF 在数据库锁外，避免用全程大锁修复而引入吞吐瓶颈。
- **验证建议**：用屏障暂停登录落库，完成改密，再继续旧登录；应拒绝且不新增会话。

## P2：逻辑、性能、资源和错误处理

### 3. 离线成员重连可突破 12 人在线上限

- **位置**：[Application.kt:1082](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt:1082)。
- **触发及影响**：旧成员离线后房间被其他人补满；旧 membership 仍存在，重连绕过 `!rejoining` 条件下的人数检查，在线人数超过 12。
- **建议**：仅允许“替换当前仍在线的同一 clientId 会话”跳过容量检查；离线成员重连仍须检查人数。
- **验证建议**：满员时分别测试离线重连和在线连接替换，前者拒绝、后者允许。

### 4. 旧 DLNA 轮询可覆盖或断开新会话

- **位置**：[CastManager.android.kt:1046](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastManager.android.kt:1046)、[断开处理:1299](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastManager.android.kt:1299)。
- **触发及影响**：A 设备轮询的阻塞 HTTP 尚未返回就切到 B。取消 Job 不会自动终止普通 HttpURLConnection；返回后的成功/失败分支没有检查取消或会话版本。旧位置可污染 B，旧 401/403 还会取消字段中 B 的新轮询。
- **建议**：轮询捕获会话 revision 和目标；IO 返回后检查取消；状态修改和断开操作原子核对 revision，连接绑定实际取消。
- **验证建议**：控制 A 响应延迟，切到 B 后放行旧成功/401 响应，B 状态和轮询应保持不变；补显式停止场景。

### 5. DLNA 跳转确认成功后仍继续确认，可能误报失败

- **位置**：[CastManager.android.kt:1129](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastManager.android.kt:1129)，关键语句为 1134 行 `return@repeat`。
- **触发及影响**：第一轮已经确认到目标，但 `return@repeat` 只结束本轮，后续仍请求；最多额外 4 次 SOAP。后续网络失败会把已成功跳转报告成失败。
- **建议**：确认成功直接 `return@runCatching snapshot`，或普通循环 `break`，与现有 confirmDlnaTransport 的提前返回行为一致。
- **验证建议**：第一轮成功，第二轮模拟失败；正确实现应成功返回且根本没有第二轮请求。

### 6. Exo 副字幕失败后重选无法恢复

- **位置**：[ExoSecondarySubtitleController.kt:150](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ExoSecondarySubtitleController.kt:150)、[重选判断:184](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ExoSecondarySubtitleController.kt:184)。
- **问题**：错误处理仅清 cues、记录日志，没有将 prepared 复位。相同播放列表重选时不调用 prepare，却仍返回 true；同步进度和主播放器重试也不能恢复副播放器。
- **影响**：瞬时加载错误后副字幕持续消失，界面仍显示选中，需要先关闭再启用。Media3 在失败后进入 IDLE，重试需要 prepare，见 [官方播放器事件说明](https://developer.android.com/media/media3/exoplayer/listening-to-player-events#playback-errors)。
- **建议**：错误时设置 prepared=false 并提供可见错误状态；重选时对齐主进度、重新 prepare，可增加有界重试。
- **验证建议**：注入一次副字幕加载失败，恢复网络后直接重选，验证再次 prepare/输出 cues，主视频不中断。

### 7. 海外日历发现失败被当成空结果，可能删除已有排期

- **位置**：[CalendarIngestion.kt:1303](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarIngestion.kt:1303)、[旧排期筛选:1154](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarIngestion.kt:1154)。
- **触发及影响**：海外全量发现超时、HTTP 错误或 JSON 无效后返回空列表；保留旧排期的规则只针对 Domestic。国内结果仍非空时会发布删去海外排期的新版本并记录成功。仅指依靠海外发现的条目，不泛化至所有配置源。
- **建议**：区分发现失败、成功空列表、成功非空；失败保留该来源的旧快照或放弃该部分发布，记录降级状态。
- **验证建议**：已有国内/海外各一条，国内成功、海外分别超时和无效 JSON，验证海外记录仍保留。

### 8. 资料局部更新存在丢失更新

- **位置**：[AccountService.kt:502](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountService.kt:502)、[AccountStore.kt:920](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountStore.kt:920)。
- **触发及影响**：两设备同时分别改昵称和头像，服务层用同一旧快照补齐未提交字段，SQL 每次覆盖两列；后提交请求覆盖先前已成功修改的另一字段。
- **建议**：事务中仅更新请求明确包含的字段，或引入资料版本并处理冲突。
- **验证建议**：并发局部更新交错提交，最终保留新昵称和新头像。

### 9. 日历 304 请求仍在数据库锁内执行全量 N+1 查询

- **位置**：[CalendarScheduleRoutes.kt:147](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarScheduleRoutes.kt:147)、[CalendarScheduleStore.kt:224](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarScheduleStore.kt:224)。
- **问题**：先重建 publication 再比较 ETag。每个节目分别读 evidence 和 episodes，共约 `2N+2` SQL，并在同一锁内反序列化、验证。已有签名缓存不能省掉此成本。
- **影响**：节目数/请求数增长时增加分配、查询和锁等待；已确认算法成本，尚未测出具体延迟。
- **建议**：按 revision 缓存不可变 publication 与签名响应；先查询轻量版本判断 304；冷加载用批量查询后分组。
- **验证建议**：统计重复条件请求的 SQL 数和分配量，并发读/刷新测 P95 与锁等待，验证一致性。

### 10. TV 备份选择在主线程无界读取文件

- **位置**：[TvServerBackupScreen.kt:73](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt:73)、[导入重复读取:189](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt:189)。
- **触发及影响**：备份目录接受任意非空普通文件，选中时 remember 在 UI 线程 readText 并尝试 JSON 解析。大文件先完整分配，后续加密格式的限制来不及保护；存在卡顿、ANR/OOM 风险，尚未真机复现。
- **建议**：IO 协程有界读取，既检查文件大小也限制实际读取字节；异步识别格式，选择和导入共用经过校验的 payload，超限给明确错误。
- **验证建议**：正常格式、超限、损坏及读取中变化的文件；StrictMode 验证主线程无文件读取。

### 11. TV 迁移 API 缺少明确的 HttpClient 生命周期

- **位置**：[TvServerBackupScreen.kt:49](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt:49)、[MigrationRelayApi.kt:42](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/migration/MigrationRelayApi.kt:42)、[HttpClientFactory.android.kt:47](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/network/HttpClientFactory.android.kt:47)。
- **问题**：每次页面新建 API，默认创建 HttpClient/OkHttp engine/dispatcher，API 没有 close 接口，页面也没有释放路径。
- **边界**：连接池已有共享；本项确认的是资源所有权缺失，尚未证明实例无限驻留或线程泄漏。
- **建议**：注入应用级 API 单例；或标明 client 所有权，页面销毁时只关闭自己创建的实例。
- **验证建议**：多次进入、兑换、退出，观察 client/engine/dispatcher 实例及线程数量是否稳定。

### 12. 依赖扫描混入工作树副本及生成目录

- **位置**：[supply_chain_check.py:70](D:/Demo/Yfuse/scripts/supply_chain_check.py:70)。
- **问题与证据**：`root.rglob("gradle.lockfile")` 不限 Git 跟踪文件；本轮探针发现 5 个跟踪锁之外另有 15 个副本/生成锁。有效坐标从 745 被扩大为 765，其中 20 个仅来自额外输入，污染 SBOM 和门禁结果。
- **建议**：按受跟踪锁或明确模块清单取输入，保留每个坐标的所有来源；不要通过删除合法 worktree 来掩盖扫描边界问题。
- **验证建议**：夹具分别放跟踪模块、worktree、build 锁，只扫描指定集合。证据见 [scanner-evidence.json](D:/Demo/Yfuse/audit/code-review-20260915/scanner-evidence.json)。本轮没有联网重新评价漏洞数量或最新版本。

### 13. OSV 响应缺项时扫描器静默报告无发现

- **位置**：[supply_chain_check.py:116](D:/Demo/Yfuse/scripts/supply_chain_check.py:116)。
- **问题与证据**：对 `zip(batch, result.get("results", []))` 不校验数量与结构。HTTP 成功但返回 `{}` 或不足的 results 时，未扫描项被静默跳过；本地 mock 两种响应均返回空发现列表。
- **建议**：检查顶层结构、results 数量与输入一致、各条目类型；缺项或协议异常使扫描失败并给可重试诊断，不当作安全结果。
- **验证建议**：补 `{}`、短列表、非对象条目及正常零发现用例；现有 10 个测试主要覆盖严重度计算，未覆盖响应完整性。

## P3：内存增长控制、风格与冗余整理

### 14. MPV HLS 代理保留所有历史路由

- **位置**：[AndroidPlaybackHttpProxy.kt:51](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AndroidPlaybackHttpProxy.kt:51)、[注册 manifest 子地址:347](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AndroidPlaybackHttpProxy.kt:347)。
- **问题**：routes/routeIds 仅代理关闭时清空；长直播持续出现新 segment URL 或签名 URL 时，历史路由条目随时间增长。确定增长机制，不等同已测 OOM。
- **建议**：按直播窗口和宽限期回收历史路由，保留在途请求；切源时释放无用路由。用滑动 manifest 长时压测条目数与堆占用，验证稳定上界。

### 15. Kotlin 文件含字面 NUL，破坏常规文本检索

- **位置**：[TvServerBackupScreen.kt:215](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt:215)。
- **问题**：secret.fill 的字符字面量中实际含一个 0x00，rg 默认把文件当作二进制，容易漏检。属于工具和可读性问题。
- **建议**：改用可见转义 `secret.fill('\u0000')`，与其他凭据清理代码一致；字节扫描确认不再有 NUL。

### 16. 大文件与格式历史债务增加审查成本

- **位置**：[PlayerRoot.kt:1](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:1)、[AndroidNativeDirectYPlayer.kt:1](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt:1)、[CalendarIngestion.kt:1](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarIngestion.kt:1)、[ktlint 配置:84](D:/Demo/Yfuse/build.gradle.kts:84)。
- **规模**：808 个生产 Kotlin 文件中 43 个超过 1,000 物理行、12 个超过 2,000 行；上述三个分别为 3,452、3,261、2,879 行。ktlint baseline 有 1,155 条历史记录，这不是本轮活跃违规数。
- **建议**：先围绕已发现缺陷拆出房间身份校验、登录事务、投屏会话控制、日历来源结果等可单测边界；随后按职责拆 UI/播放编排/数据转换。逐文件统一命名、异常返回与取消约定，在专门的格式债务清理中缩减 baseline。
- **边界**：文件长度不直接证明性能差或不可测试，不建议全仓机械重构或批量替换 catch/collect。

### 17. 两个版本目录 alias 未被构建使用

- **位置**：[libs.versions.toml:62](D:/Demo/Yfuse/gradle/libs.versions.toml:62)、[libs.versions.toml:107](D:/Demo/Yfuse/gradle/libs.versions.toml:107)。
- **结论**：`ktor-server-netty`、`androidx-tv-material` 在受跟踪 Gradle 脚本中未引用，可确认用途后删除或注明预留。它们只是目录条目，删除不代表 APK 体积下降。
- **核查**：Decompose、MVI、Koin、Navigation3、Coil/Ktor、Media3 UI、Cast 等均找到实际使用，未发现可高置信直接删掉的生产运行依赖。没有把版本号较旧本身作为缺陷。

## 附录：尚未接入当前 Android 路径的 native C ABI

以下问题独立定为 P2，但不并入上述当前应用主清单。仓库生产调用中未找到 `ycore_session_register_engine`；不能据此推断当前 APK 已受到影响。

### A1. 初始化步骤失败仍返回成功

[ycore.cpp:139](D:/Demo/Yfuse/ycore-native/src/ycore.cpp:139) 忽略 set_video_output、set_speed、select_track、play/pause 的错误，随后无条件 READY/playing/OK。应区分必须成功与允许降级的步骤，传播失败并保持状态一致。FakeEngine 测试补 open 成功但 play/output 失败。

### A2. 新媒体继承旧媒体轨道及诊断状态

[ycore.cpp:251](D:/Demo/Yfuse/ycore-native/src/ycore.cpp:251) 新 open 仅清部分状态，旧轨道 ID、duration/buffered position、decoder/renderer 与 output_verified 等残留；activate 可能再提交旧轨道。应区分同请求切后端与打开新媒体，后者清理媒体私有状态。测试 A 选轨后开 B，验证不继承 A 的轨道和输出证据。

## 建议实施顺序与验收

1. **权限和会话**：修复 #1、#2，增加真实认证与事务交错回归；验收旧身份无法获得新权限或残留会话。
2. **用户可见错误**：修复 #3–#8、#10；用故障注入和延迟响应验证恢复、容量及一致性。
3. **服务与扫描可靠性**：修复 #9、#11–#13；验证查询次数、资源稳定性与门禁失败语义。
4. **维护性及长期内存增长**：处理 #14–#17；先测量，再按职责小步拆分。native 附录应在接入生产前完成。

具体验证结果见 [VALIDATION.md](D:/Demo/Yfuse/audit/code-review-20260915/VALIDATION.md)。测试通过只说明现有用例通过，不覆盖本报告新增场景；源码推导的竞态、权限和性能问题应各自增加针对性回归后再声称修复。
