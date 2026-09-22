# Yfuse 代码审查修复结果

日期：2026-09-15。对应[初始审查报告](D:/Demo/Yfuse/audit/code-review-20260915/REPORT.md)的 #1–17，以及附录 A1、A2。

## 结论与交付状态

原报告中的具体缺陷和冗余项已落实代码修复或清理；#16 已完成围绕本次问题的职责拆分及部分历史格式清理，超大 UI、播放器类和剩余 baseline 仍属于维护债。**最终 2,774 项服务端、协议、手机端及 TV 单测全部通过，五个模块的 ktlint 检查通过**；另有 15 项扫描脚本测试和 15 个 native 测试函数执行通过。

本轮同时支持了用户要求的**同账号手机作为房主、平板作为观众加入同一房间**。本轮没有打包、部署、发布或上传应用版本，`version.properties`、`release-notes.txt` 未变更。多设备房间及其他服务端修复需要部署更新后的服务端才会在线上生效；客户端修复需要后续构建和交付新客户端。

## 同账号多设备的实际行为

- 手机创建房间，平板使用房间码加入。每个安装实例使用自己的 `clientId` 和重连凭据；相同账号不会直接授予另一设备房主或管理员权限。默认控制模式下，平板跟随手机播放。
- 房间成员按 `clientId` 保存，并绑定认证账号；恢复成员先验证账号归属，再验证该设备的 resume capability。恢复房主还需要 host capability。
- 同一设备在线连接替换不会增加在线人数，旧连接的清理也不会移除新连接。离线设备恢复需要空余席位，不能突破 12 人上限。
- 房主移出自己的平板，仅限制该设备在当前房间重新加入，手机账号和连接继续有效。移出其他账号时，移除其全部在线设备、离线成员记录和管理员权限，并禁止该账号换设备 ID 重入。
- 原有房主离线宽限期后的自动交接规则保留；这与使用相同账号直接获得权限是不同的触发条件。

实现见 [WatchRoomAdmission.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomAdmission.kt)、[WatchRoomModel.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomModel.kt)、[Application.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/Application.kt)。回归覆盖见 [WatchMultiDeviceTest.kt](D:/Demo/Yfuse/watchTogetherServer/src/test/kotlin/com/yfuse/watch/WatchMultiDeviceTest.kt)、[WatchRoomAdmissionTest.kt](D:/Demo/Yfuse/watchTogetherServer/src/test/kotlin/com/yfuse/watch/WatchRoomAdmissionTest.kt)和 [WatchTogetherServerTest.kt](D:/Demo/Yfuse/watchTogetherServer/src/test/kotlin/com/yfuse/watch/WatchTogetherServerTest.kt)。

## 逐项修复对照

以下编号和严重程度沿用初始报告。“已修复”描述代码处理状态，不替代真机或线上验证。

### P1：权限与登录会话

| 编号 | 原问题 | 实际处理与位置 |
| --- | --- | --- |
| #1 | 其他账号复用房主 `clientId` 可能获得权限 | **已修复。** 入房检查统一放入房间锁内；设备身份绑定账号，明确区分首次创建和恢复房主。支持同账号不同设备的独立成员身份。见 [WatchRoomAdmission.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomAdmission.kt)。 |
| #2 | 改密后，旧密码并发登录仍可能插入有效会话 | **已修复。** KDF 仍在数据库锁外；创建会话事务内复核验证时的密码盐、摘要和迭代数。改密或删号后才提交的旧登录被拒绝；先提交的登录由改密事务撤销。摘要在 `finally` 清零。见 [AccountService.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountService.kt)、[AccountStore.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountStore.kt)。 |

#2 的交错测试同时验证访问令牌、刷新令牌、删除账号和登录返回最新资料，见 [AccountIdentityRaceTest.kt](D:/Demo/Yfuse/watchTogetherServer/src/test/kotlin/com/yfuse/watch/account/AccountIdentityRaceTest.kt)。

### P2：逻辑、资源与错误处理

| 编号 | 原问题 | 实际处理与位置 |
| --- | --- | --- |
| #3 | 离线恢复突破 12 人上限 | **已修复。** 只有仍在线的同 ID 连接替换复用席位；离线恢复重新检查容量。见 [WatchRoomAdmission.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/WatchRoomAdmission.kt)。 |
| #4 | 旧 DLNA 请求覆盖或断开新会话 | **已修复。** 请求携带设备 ID 和会话版本；IO 前后检查取消与归属，状态更新、断开处理拒绝过期结果。失败回退也产生新版本，清理过期输出确认，避免重用旧请求身份。见 [CastManager.android.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastManager.android.kt)、[DlnaOperations.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/DlnaOperations.kt)。 |
| #5 | DLNA 跳转已确认仍继续请求 | **已修复。** 确认循环首次满足条件立即返回，未确认时才继续有限重试。见 [DlnaOperations.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/DlnaOperations.kt)。 |
| #6 | 副字幕失败后重选不恢复 | **已修复。** 播放错误将 `prepared` 复位；重选相同队列也对齐主播放位置并重新 `prepare`，保留错误日志。见 [ExoSecondarySubtitleController.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ExoSecondarySubtitleController.kt)。 |
| #7 | 海外发现失败被当成空结果，删除已有排期 | **已修复。** 网络不可用、JSON 无效或基本结构损坏都会抛出失败；`runOnce` 在写数据库、文件前终止，外层记录失败并等待下一次重试，旧 publication 保留。合法 `[]`、被业务规则排除的节目、特别集和未知日期仍可正常过滤。见 [OverseasCalendarDiscovery.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/OverseasCalendarDiscovery.kt)、[OverseasCalendarIngestion.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/OverseasCalendarIngestion.kt)、[CalendarIngestion.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarIngestion.kt)。 |
| #8 | 并发改昵称、头像互相覆盖 | **已修复。** 服务层保留未提交字段的 `null`，SQL 仅修改请求提供的字段，避免用旧资料回填另一列。两种写入次序均有交错测试。见 [AccountStore.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/account/AccountStore.kt)。 |
| #9 | 日历条件请求仍执行全量 N+1 查询 | **已修复。** 按 publication header 缓存深度不可变快照；热读只查询一次轻量 header，冷读固定为 header、series、episodes、evidence 共 4 次 SELECT，再按剧集和季分组。读事务保持快照一致，每次查 header 感知其他连接的发布；输入和返回值的可变 List 均不会污染缓存。见 [CalendarScheduleStore.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarScheduleStore.kt)、[CalendarPublicationSnapshot.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarPublicationSnapshot.kt)。 |
| #10 | TV 备份在主线程无界读取并重复解析 | **已修复。** 目录和文件读取放入 IO 协程，预检大小并限制实际读取至 768 KiB；选中时保存 payload 快照，导入复用该内容，超限、空文件和读取失败显示错误。见 [TvServerBackupFiles.kt](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupFiles.kt)、[TvServerBackupScreen.kt](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupScreen.kt)。 |
| #11 | TV 迁移客户端缺少释放路径 | **已修复。** API 明确区分自建和外部注入客户端；页面销毁关闭自建客户端及 engine，外部注入资源不由 API 关闭。Android 迁移 engine 使用独立连接池，避免关闭时影响应用共享池。见 [MigrationRelayApi.kt](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/migration/MigrationRelayApi.kt)、[MigrationRelayHttpEngine.android.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/migration/MigrationRelayHttpEngine.android.kt)。 |
| #12 | 扫描输入混入副本和生成目录 | **已修复。** 通过 Git 跟踪清单选择锁文件，保留坐标的全部输入来源，缺失跟踪锁使扫描失败。当前输入恢复为 5 个跟踪锁、745 个坐标。见 [supply_chain_check.py](D:/Demo/Yfuse/scripts/supply_chain_check.py)。 |
| #13 | OSV 响应缺项被当成零发现 | **已修复。** 校验响应顶层、批次数量、条目和漏洞结构，并核对详情 ID；不完整或错误响应使扫描失败，只有完整的零发现响应才通过。见 [supply_chain_check.py](D:/Demo/Yfuse/scripts/supply_chain_check.py)、[test_supply_chain_check.py](D:/Demo/Yfuse/scripts/test_supply_chain_check.py)。 |

客户端针对性用例包括 [DlnaOperationsTest.kt](D:/Demo/Yfuse/composeApp/src/androidUnitTest/kotlin/com/yfuse/core/cast/DlnaOperationsTest.kt)、[ExoSecondarySubtitleRecoveryTest.kt](D:/Demo/Yfuse/composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/ExoSecondarySubtitleRecoveryTest.kt)、[TvServerBackupFilesTest.kt](D:/Demo/Yfuse/tvApp/src/androidUnitTest/kotlin/com/yfuse/tv/ui/TvServerBackupFilesTest.kt)、[MigrationRelayApiTest.kt](D:/Demo/Yfuse/composeApp/src/commonTest/kotlin/com/yfuse/core/migration/MigrationRelayApiTest.kt)及 [MigrationRelayHttpEngineTest.kt](D:/Demo/Yfuse/composeApp/src/androidUnitTest/kotlin/com/yfuse/core/migration/MigrationRelayHttpEngineTest.kt)。其本轮最终执行状态以下方验证表为准。

**附加修复：投屏设备发现的线程与取消处理。** [CastManager.android.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core/cast/CastManager.android.kt) 的 IO 阶段只扫描设备；目标集合和界面状态统一在 Main 线程发布，并核对该扫描的 owner token。取消或结束时，仅当前扫描在 `finally` 复位 `discovering`，避免后台写目标集合与主线程读列表并发，以及旧扫描清理覆盖新扫描状态。此项的最终客户端回归结果以下方验证表为准。

### P3：增长控制、可读性与冗余

| 编号 | 原问题 | 实际处理与位置 |
| --- | --- | --- |
| #14 | HLS 历史代理路由持续积累 | **已修复增长机制。** 提取路由生命周期管理，保留当前 manifest 可达路由、在途请求及宽限期内历史；过期历史回收，历史条目上限默认 4,096、宽限期 120 秒。VOD 当前清单内早期片段仍可回看，不能把该历史上限理解为所有路由的总上限。见 [PlaybackProxyRoutes.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackProxyRoutes.kt)、[AndroidPlaybackHttpProxy.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AndroidPlaybackHttpProxy.kt)。 |
| #15 | TV Kotlin 源码包含字面 NUL | **已清理。** 凭据清理代码使用可见的 `'\u0000'` 字符转义，原页面的相关导入逻辑移入 [TvServerBackupFiles.kt](D:/Demo/Yfuse/tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvServerBackupFiles.kt)。 |
| #16 | 大文件与历史格式债务 | **已完成针对性拆分，仍有维护债。** 拆出房间准入、DLNA 操作、代理路由、TV 备份 IO、日历健康状态、中文排期解析和不可变快照等边界。对本轮修改文件单独检查格式，并清除已修好文件对应的历史 baseline；未宣称全仓大类、格式债务全部消除。详见下一节。 |
| #17 | 两个版本目录别名未被使用 | **已清理。** 删除 `ktor-server-netty`、`androidx-tv-material` 两个未引用 alias。见 [libs.versions.toml](D:/Demo/Yfuse/gradle/libs.versions.toml)。这不等于移除实际打包依赖，也不构成 APK 体积下降的证据。 |

路由用例覆盖滑动直播、VOD 回看、在途读取和切源，见 [PlaybackProxyRoutesTest.kt](D:/Demo/Yfuse/composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/PlaybackProxyRoutesTest.kt)。

### 附录 P2：native C ABI

| 编号 | 原问题 | 实际处理 |
| --- | --- | --- |
| A1 | 后端初始化步骤失败仍发布 READY/成功 | **已修复。** 视频输出、速度、轨道恢复、播放/暂停等步骤检查返回值；失败不发布 READY，记录对应失败，允许的情形继续尝试下一后端。 |
| A2 | 打开新媒体继承旧轨道与输出诊断 | **已修复。** 新媒体请求重置媒体私有状态，保留宿主输出配置；同请求后端切换仍恢复有效轨道与播放意图。另补拒绝速度值/NaN 不覆盖已接受值的保护。 |

实现见 [ycore.cpp](D:/Demo/Yfuse/ycore-native/src/ycore.cpp)，回归见 [ycore_test.cpp](D:/Demo/Yfuse/ycore-native/tests/ycore_test.cpp)。这两项仍以独立 ABI 范围报告，不据此声称当前 Android 播放路径已使用该接口。

## 格式与维护性处理

本轮 **34 个修改或新增 Kotlin 文件**通过不使用 baseline 的独立 ktlint 检查，清单见 [changed-kotlin.txt](D:/Demo/Yfuse/audit/code-review-fixes-20260915/changed-kotlin.txt)。

只删除已完成修正的三个服务端文件所对应的历史记录：

| 文件 | 移除 baseline 记录 |
| --- | ---: |
| `CalendarIngestion.kt` | 206 |
| `CalendarScheduleStore.kt` | 14 |
| `OverseasCalendarIngestion.kt` | 71 |
| **合计** | **291** |

全仓历史 baseline 记录由 **1,155 降至 864**，移除明细见 [baseline-removals.json](D:/Demo/Yfuse/audit/code-review-fixes-20260915/baseline-removals.json)。这些数字表示历史豁免记录，不能视为当前运行缺陷数量。本轮新增的 [CalendarIngestionHealth.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/CalendarIngestionHealth.kt)、[ChineseScheduleParser.kt](D:/Demo/Yfuse/watchTogetherServer/src/main/kotlin/com/yfuse/watch/ChineseScheduleParser.kt)等拆分减少相关大文件的职责混杂；`PlayerRoot.kt`、`AndroidNativeDirectYPlayer.kt` 等超大 UI/播放器编排类和剩余 baseline 仍需后续按职责逐步处理。

## 实际验证结果

| 验证项目 | 本轮已确认结果 | 证据与范围 |
| --- | --- | --- |
| 服务端 | **193 项通过，0 失败/错误/跳过** | 包含账号事务交错、多设备房间、踢人、容量、海外结果校验和日历缓存回归。 |
| 协议 | **8 项通过，0 失败/错误/跳过** | 验证现有协议兼容；本次多设备支持未要求客户端同步身份凭据。 |
| 依赖扫描脚本 | **15 项通过**；输入为 **5 个跟踪锁、745 个坐标** | [scanner-tests.log](D:/Demo/Yfuse/audit/code-review-fixes-20260915/scanner-tests.log)。本轮未联网重新运行漏洞数据库扫描，不宣称全部依赖无已知漏洞。 |
| Native ABI | **15 个测试函数在连接的 Android 设备执行通过** | 使用 NDK Clang 构建独立 C++ 测试程序，断言启用，退出码 0；临时设备程序已清理。详见 [native-validation.md](D:/Demo/Yfuse/audit/code-review-fixes-20260915/native-validation.md)。 |
| 本轮 Kotlin 格式 | **34 个文件独立 ktlint 通过** | 不使用历史 baseline，见文件清单及 [changed-ktlint.log](D:/Demo/Yfuse/audit/code-review-fixes-20260915/changed-ktlint.log)。 |
| Android 客户端最终回归 | **2,511 项通过，0 失败/错误/跳过** | 包含新增 DLNA、字幕恢复、HLS 路由及迁移资源清理测试；连接池测试检查实际线程池关闭、独立池清空后共享 TCP 仍复用。 |
| TV 最终回归 | **62 项通过，0 失败/错误/跳过** | 包含新增备份文件读取与大小边界测试。 |
| 模块级格式检查 | **5 个模块全部通过** | `composeApp`、`tvApp`、`watchTogetherServer`、`watchTogetherProtocol`、`mdkAndroid` 的 `ktlintCheck`。历史 baseline 仍适用于未清理的其他文件。 |

最终统一命令见 [run-verification.ps1](D:/Demo/Yfuse/audit/code-review-fixes-20260915/run-verification.ps1)，完整日志见 [verification-final.log](D:/Demo/Yfuse/audit/code-review-fixes-20260915/verification-final.log)：`BUILD SUCCESSFUL in 4m 47s`，退出码 0，121 个任务中 56 个执行、65 个保持最新。服务端和协议测试在前一轮已通过，最终统一运行复用其有效结果；Android/TV 最终测试重新执行。

结构化结果见 [validation-summary.json](D:/Demo/Yfuse/audit/code-review-fixes-20260915/validation-summary.json)，由最终 XML 汇总；本次修改的源文件无字面 NUL，`git diff --check` 通过，版本配置和更新说明的 diff 为空。

构建环境记录：初次 Android 验证遇到只读缓存中 ZXing JAR 的 JDK zipfs 访问错误。最终运行通过仅供验证的 [init 脚本](D:/Demo/Yfuse/audit/code-review-fixes-20260915/javac-workspace-cache.init.gradle)使用工作区副本，SHA-256 与原依赖一致，未修改项目依赖、构建配置或 JAR 内容。中途新增迁移测试曾错误等待不保证结束的 Ktor 父 Job；最终用实际资源关闭状态验证，2 个相关测试均通过。中间失败日志保留用于追溯，以最终统一日志为验收结果。

## 验证边界与后续生效条件

- 账号、房间、数据库一致性和错误传播已有针对性自动化覆盖；DLNA、字幕、备份和代理路由主要通过可控替身、边界输入验证，不能替代实际接收器、媒体服务器和 UI 的组合回归。
- 手机房主、平板观众场景通过服务端多连接回归验证，尚未完成两台实体设备联调。
- 未完成真机长时间直播堆分析、反复开关页面的资源计数、StrictMode 全流程核查或日历负载下 P95/锁等待测量。查询复杂度和资源生命周期已改进，不报告未经测量的性能提升百分比，也不把风险修复说成已经复现并消除了 OOM/ANR。
- Native 测试运行了便携协调层及 fake backend，未验证真实解码、HDR 输出或当前 APK 接入该 ABI 的情况。
- 生产服务尚未部署本轮修复，应用没有生成新 APK；本地代码和测试状态不等同线上行为。后续打包须按项目要求核对上一次实际交付版本，同步更新版本配置与更新说明，并验证最终包版本和正式签名。
