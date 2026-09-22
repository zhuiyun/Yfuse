# Yfuse 项目优化与功能增补审查 · 2026-09-10

当前最优先的工作是恢复整个工作区的编译、统一播放器行为，以及补齐真实设备性能验证。功能已经覆盖较广，新增功能应围绕观看体验补强。

本次检查以当前工作区为准，包括尚未提交的 TV、内核、日历和动画改动。统计范围包括 Android、TV、协议、服务端、宏基准、Harmony Cangjie 与 ycore-native/src 中的 Kotlin/Cangjie/C++ 文件：共 1,328 个文件、285,939 行，其中 523 个为测试文件。这是文件清单统计，不代表逐行审查或测试覆盖率。重点抽查构建、播放、字幕、下载、网络生命周期、服务端鉴权/存储、性能测试和 Harmony 可达性。本轮只新增审查记录，没有修改业务代码。

## 实际验证结果

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| 手机 `compileReleaseKotlinAndroid` | 失败 | 当前 TV 设置源码存在缺失引用，进入了手机编译源集 |
| TV `compileDebugKotlinAndroid` | 失败 | 同一组 TV 设置源码缺失引用 |
| `watchTogetherServer:test` | 通过，175 项、24 个测试类 | 本轮执行，无失败/错误/跳过 |
| `watchTogetherProtocol:jvmTest` | 通过，8 项、2 个测试类 | Gradle 判定 UP-TO-DATE，复用有效的既有结果 |
| `scripts/tests` | 13 项通过、3 项跳过 | 初次因 PATH 缺少 OpenSSL 失败；补充本进程工具路径后通过，3 项 native Bash 合约测试仍因未发现 Bash 而跳过 |
| TV manifest/banner 源码合约 | 通过 | 不等于 TV 已编译成功或遥控器体验已验证 |

构建日志：[build-review.log](D:/Demo/Yfuse/audit/project-review-20260910/build-review.log)。测试汇总：[jvm-tests.json](D:/Demo/Yfuse/audit/project-review-20260910/jvm-tests.json)、[script-tests.log](D:/Demo/Yfuse/audit/project-review-20260910/script-tests.log)。可复现命令：[verify.ps1](D:/Demo/Yfuse/audit/project-review-20260910/verify.ps1)。没有运行完整 Android 单测、真机交互、功耗、长稳或线上服务压测；此前字幕/氛围光的隔离快照测试通过，不能替代这次整个工作区的验证。

## 优先优化

### 1. P0：修复 TV 设置依赖，恢复手机和 TV 的整体编译

- [TvMediaDiscoverySettingsScreen.kt:29](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvMediaDiscoverySettingsScreen.kt:29) 引用了不存在的 `component.tgtoMediaPreferences`，后续 connection、endpoint、hasToken 等报错由此连带产生。
- [TvSettingsScreen.kt:26](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvSettingsScreen.kt:26) 导入不存在的 `canUseMediaDiscovery`，并在设置搜索及入口显示处使用。

这两个文件目前属于工作区未提交的新增源码，不能据此断言线上包存在同样问题。应确认资源发现功能的真实依赖是否已落地：已落地就补齐接口/依赖注入；未落地就让页面和入口保持明确的能力关闭状态，不能用恒真权限函数或空实现敷衍编译。验收为两端上述编译任务通过，再验证拥有/不拥有能力的账号入口行为。

### 2. P1：统一不同播放器的双字幕规则

当前代码中三种实现并不一致：

- [Core2Surface.kt:204](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/Core2Surface.kt:204) 已按本任务要求底部堆叠，主字幕在上、副字幕在下，按实际占位计算间距。
- [ExoDualSubtitleCueMerger.kt:66](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/ExoDualSubtitleCueMerger.kt:66) 仍把未定位的副字幕设为 `-3` 行；有作者坐标的字幕保留原位置。这既与新顺序不同，也不能用固定两行间隔保证所有多行内容不重叠。
- [MpvVideoEngine.kt:1130](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/MpvVideoEngine.kt:1130) 给主、副字幕写入同一个位置百分比，没有表达两个字幕块之间的实际间距。这里是策略差异，尚未用设备确认每种格式的具体表现。

建议定义公共的字幕意图：顺序、底部位置、间距、主/副字号、单轨时是否保留作者坐标；各后端按能力实现或明确限制。验收覆盖 SRT/ASS/PGS、主副混合格式、多行、字体放大、横竖屏、切内核及关闭其中一轨。

### 3. P1：把性能优化变成可以重复比较的设备结果

已有 [StartupBenchmark.kt](D:/Demo/Yfuse/macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark/StartupBenchmark.kt)、[HomeJourneyBenchmark.kt](D:/Demo/Yfuse/macrobenchmark/src/main/kotlin/com/yfuse/macrobenchmark/HomeJourneyBenchmark.kt) 和 Profile 采集工具，但当前工作流是手动触发并依赖专用物理 runner。仓库未发现应用自行采集的 baseline/startup profile 文本；[性能工作流记录](D:/Demo/Yfuse/docs/ANDROID_PERFORMANCE_WORKFLOW.md:59) 也明确记录了尚未完成的采集。

先完成真实启动、首页滚动的基线，再增加搜索切页、进入播放、切集/退出、双字幕与氛围光开关对照。对照固定设备、视频、亮度、刷新率、网络条件和构建配置，记录首帧、帧耗时分位数、退出耗时、内存与功耗；没有实测前不承诺省电百分比。采集真实 Profile 并验证发布包消费规则，可覆盖启动和常用交互热路径，具体作用参考 [Android Baseline Profiles 官方说明](https://developer.android.com/topic/performance/baselineprofiles/overview)；帧和启动指标参考 [Macrobenchmark 指标](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)。

### 4. P1：完成自研内核的发布证据

[YCoreNativeReadiness.kt:73](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core2/release/YCoreNativeReadiness.kt:73) 和 [media-tests/README.md](D:/Demo/Yfuse/media-tests/README.md) 已有设备矩阵、Seek/Surface 压力、8 小时连续和 24 小时队列门禁。下一步主要是执行并产出与同一 commit、AAR、APK 对应的真实报告，而不是再增加一套空门禁。

本次可见本地文件中没有找到满足整套发布条件的完整报告集合；这不排除外部 CI 或私有测试库保存了证据。应先汇总已有报告，再补缺失项目。优先覆盖普通 H.264/H.265、弱网、快速切集、音轨/字幕切换，再扩展高规格 HDR/Dolby/原盘。未测项继续保持 `NotMeasured`。

### 5. P2：逐步拆分共享模块与大型播放入口

[tvApp/build.gradle.kts:274](D:/Demo/Yfuse/tvApp/build.gradle.kts:274) 和第 309 行直接复用 composeApp 的 commonMain/androidMain 源树；本次 TV 页面使手机编译失败，已经体现了相互影响。按统计，PlayerRoot 3,452 行、AndroidNativeDirectYPlayer 3,206 行、CalendarIngestion 2,879 行、服务端 Application 2,302 行。

建议先划清 `core:model/data`、`player:api/runtime` 和手机/TV 各自 UI 的边界，再从 PlayerRoot 分离字幕、氛围光、播放会话和窗口生命周期。从一个可编译且有行为回归的小边界开始，不做一次性大改。文件长本身不证明运行慢；这里的收益是减少跨功能耦合与回归范围。模块化取舍可参照 [Android 模块化指南](https://developer.android.com/topic/modularization)。

### 6. P2：让 CI 更早反馈源码错误

[quality-gates-v2.yml](D:/Demo/Yfuse/.github/workflows/quality-gates-v2.yml:22) 把原生构建、格式、单测、Android lint 和 R8 包检查串在一个最长 300 分钟的作业里。原生依赖已经有缓存，不建议撤掉当前源码重建和产物校验。

可把协议/服务端测试、Python 合约、轻量源码检查作为独立快速作业；Android/native 完整检查保留。先测量 CI 各阶段耗时，再决定如何共享可信中间产物，目标是让缺失引用、脚本错误不必等完整原生流程才被发现。本次未查询远程工作流耗时或分支保护配置，不能断言线上检查未启用。

### 7. 条件优先：鸿蒙先打通一个真实使用流程

[screens.cj:45](D:/Demo/Yfuse/harmonyApp/entry/src/main/cangjie/src/ui/screens.cj:45) 仍生成固定“媒体内容”卡片；[entry_view.cj](D:/Demo/Yfuse/harmonyApp/entry/src/main/cangjie/src/entry_view.cj:80) 多个播放入口仍将 `playerSource` 设为空。与 [PORT_STATUS.md](D:/Demo/Yfuse/harmonyApp/PORT_STATUS.md) 的未接通说明一致。

如果近期要交付鸿蒙版，先完成“添加服务器 → 登录持久化 → 真实媒体库 → 详情 → 实际播放 → 保存进度”，然后验证退出重启和断网错误。SDK、NativeWindow 和真机能力限制分别处理。继续堆加占位页面的收益低于接通这条链路；如果暂不交付鸿蒙，则不应抢占 Android 稳定性工作。

## 值得增加的功能

| 功能 | 在现有能力上增加什么 | 验收重点 |
| --- | --- | --- |
| 双字幕方案 | 主/副语言组合、一键互换、独立字号、位置预览；沿用已有按剧记忆 | 换集/换内核后意图一致；只有一条轨道时有合理退化 |
| 下载预算与时段 | 总容量上限、仅充电时自动追更、允许下载时段 | 不打断用户手动播放；容量不足明确提示；未知 SAF 可用空间不伪报充足 |
| 时间点书签 | 为当前片源的播放时间加名字或简短备注，并从列表跳回 | 与已有“稍后观看”区分；服务器/媒体身份隔离；离线可用后再接同步 |

下载已经支持 Wi-Fi 限制、并发数量、自动追更、看完删除和可用空间检查；这里建议增加用户可控的预算和运行条件，而不是重复这些已有功能。依据：[OfflineDownloadPolicy](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineMedia.kt:56)。按剧字幕记忆同样已经存在，依据：[SeriesPlaybackPreference](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/PlaybackPreferences.kt:86)。时间点书签属于本次抽查未发现的产品能力；已有书签图标用于“稍后观看”，不等于片内时间标记。

推荐执行顺序：整体编译恢复 → 双字幕跨内核一致性 → 设备性能基线/现有优化复测 → 内核发布证据 → 小步模块拆分。新增功能先选双字幕方案，下载预算和时间书签可排在后面。

