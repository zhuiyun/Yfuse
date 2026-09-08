# 项目优化实施记录（2026-09-08）

本文对应基于 `9d565adfd2b50243af46f873e8613ca7d1939046` 的九项优化审计，记录本轮代码改动、验证证据与尚未完成的设备测量。“已实现”不表示已经测得性能收益，最终代码与之前真机测试的覆盖边界见文末。

范围包括 Android 手机/平板、自研内核与 Exo/MPV/MDK、下载、服务器管理、图片取色、弹窗及构建验证流程。改动在隔离工作树完成；原工作区未提交的 TV/Harmony 改动不属于这份实施记录。本轮尚未推送、线上发布或生成新的生产签名 APK。

## 九项审计与实施对应

| 原编号 | 审计问题 | 本轮处理 | 当前边界 |
| --- | --- | --- | --- |
| 1 | 服务器管理的异步结果与操作目标可能串台 | 用服务器身份、账号与会话序号隔离加载结果；关闭面板取消读取；动作入口校验界面所属会话和对象 | 已有乱序完成、关闭、切换及旧操作回调的回归；不代表已对真实服务器执行管理操作 |
| 2 | 下载索引、文件删除和整季加入可能阻塞 UI | 管理器持有有界串行后台命令队列；整季一次持久化与调度；补初始化、错误反馈和恢复保护 | 多选暂停/恢复/删除各占一个队列槽；130 项批量回归已通过 |
| 3 | 图片取色按原尺寸软件解码，缺少派生结果复用 | 小尺寸等比例解码；共享有界颜色缓存及同请求任务；限制并发并传播取消 | 取色真机测试已有通过记录；尚无完整堆内存或所有海报色差对照 |
| 4 | 外挂字幕取消没有贯穿网络/文件读取 | 取消绑定实际 transport/输入流关闭，解析过程检查取消；保留会话代次校验 | 增加阻塞读取取消等回归；所有网络、文件提供者和字幕格式的真机覆盖仍有限 |
| 5 | 深度探测过早标记完成，同步平台调用缺少隔离 | 仅在有效结果提交后标记完成；取消可重试；用共享单线程持有平台资源，繁忙时跳过新探测 | 平台调用若忽略中断，仍可能占用该探测通道；不会因此无限创建替代线程 |
| 6 | 地址故障切换后统计仍读取旧服务器快照 | 健康检查后按原 ID 重新取最新配置；结果回写校验账号；处理服务器已删除 | 已有新地址、删除、账号变化与取消回归；不将上一轮失败改写为成功 |
| 7 | 应用 Profile 与实际性能门禁缺少闭环 | 接通真实启动、固定本地数据的生产首页滚动、Profile 导出脚本及同设备回退比较工作流 | 冷启动 5 轮已测；首页完整采样与真实 Profile 未完成，物理 runner 未配置 |
| 8 | 签名、发布和 GitHub Release 的版本来源漂移 | 签名与自动发布读取共同版本/说明；Gradle 覆盖必须成对；手动发布后的 Release 消费本次上传元数据及 APK | 13 项 Python 回归通过；没有触发生产签名或线上发布 |
| 9 | MPV/MDK 等退出释放是否阻塞尚无测量 | 增加实际后端分阶段计时；移除两条 HTTP 代理关闭时最多 2 秒的工作线程等待，取消在途请求并延后归还缓存 | 同时修复 Exo 初始化和 MPV 两类误报；最后的代理关闭改动尚待 S10 复测，原生销毁仍有停顿 |

### 1. 服务器管理会话隔离

[`ServerManagementController`](../composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServerManagementController.kt) 持有加载任务与会话身份。打开其他服务器或关闭弹窗时，旧读取被取消；即使旧请求延后返回，也不能覆盖新会话。界面按服务器筛选状态，动作必须匹配已渲染的会话序号、服务器和当前账号，避免旧对象 ID 被提交给新的目标。

已经接受的服务端操作保持原目标；用户之后切换面板不会把它改投另一台服务器。其完成提示也只更新对应会话。账号或凭据替换会让旧管理对象失效，路由与图标编辑则不被误判成更换账号。

### 2. 下载后台化、批量操作与恢复

[`OfflineCommandQueue`](../composeApp/src/commonMain/kotlin/com/yfuse/core/offline/OfflineCommandQueue.kt) 将已接受命令交给管理器自身的后台作用域，调用页面销毁不取消已经提交的命令。队列有界，拒绝或执行失败通过下载页反馈，避免堆积无限后台任务。整季加入采用一次索引提交和一次调度；数据库写成功后才发布内存状态，同时保留 revision 与文件清理顺序。

异步初始化明确区分 `Loading`、`Ready` 和 `Failed`，避免初始空列表被显示成确定没有下载。初始化失败不能清理孤儿文件。旧 JSON 迁移采用数据库中的完成标记，避免残留旧索引在删空数据库后复活；自动追更只在批量入队持久化成功后记录已知集数，失败后仍可重试。

交叉检查补齐了多选暂停/恢复/删除：一次点击只提交一个去重后的 ID 快照，130 项选择不会受 64 槽容量截断。单项存储失败仍反馈并继续后续项，删除顺序保持不变。慢 SAF、大量下载和真实进程恢复的耗时数据仍需补充。

### 3. 小尺寸取色与派生缓存

[`DominantColor.android.kt`](../composeApp/src/androidMain/kotlin/com/yfuse/core/designsystem/DominantColor.android.kt) 为取色请求设置小尺寸软件解码，减少不必要的大位图处理。[`ArtworkColorCache`](../composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/ArtworkColorCache.kt) 缓存最多 128 个派生颜色，键包含 URL、采样用途、裁剪/渐变参数和算法版本；相同请求共享提取任务，同时最多执行两个提取。最后一个订阅者离开后取消未完成工作。

缓存只保存颜色数值；图片字节仍由生产 ImageLoader 管理。真机取色测试已通过，但本轮不据此宣称所有图片颜色完全不变，或给出尚未测得的内存节省比例。

### 4. 外挂字幕取消与切集

[`AndroidExternalSubtitleLoader`](../composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidExternalSubtitleLoader.kt) 把协程取消传播到实际网络传输及文件输入流关闭；文本解析也定期检查取消。会话原有的 generation 防护继续丢弃过期结果。相关回归覆盖旧读取被取消后释放资源，减少切集时旧请求占用字幕加载名额的风险；自研内核的 MPV 回退路径同步纳入检查。

### 5. 深度探测提交时机与平台阻塞

[`PlaybackProbeCompletionGate`](../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackProbeCompletionGate.kt) 将“开始探测”和“有效结果已提交”分开。播放缓冲导致取消后，下一次稳定时仍能补做；过期结果不覆盖当前播放源。

[`AndroidBlockingMediaProbeLane`](../composeApp/src/androidMain/kotlin/com/yfuse/core/playback/AndroidBlockingMediaProbeLane.kt) 用共享单线程持有和释放平台探测资源。调用方取消或超时可停止等待；资源仍归原执行者释放，通道繁忙时跳过后续探测。该设计控制资源数量，不能保证供应商的阻塞 API 立即响应中断。

### 6. 健康检查后的统计刷新

[`refreshCurrentServerStats`](../composeApp/src/commonMain/kotlin/com/yfuse/feature/servers/ServerStatsRefresh.kt) 在健康检查之后按 ID 查询最新 SavedServer，使统计请求使用刚切换成功的地址。回写前再次检查账号身份；服务器已删除、凭据被替换或任务取消时，不把旧结果记到新会话。全量与单卡刷新使用一致路径。

### 7. 可重复的性能验证与 Profile 采集入口

生产首页的状态渲染分离为共享 `HomeContentBody`；仅 performance 源集提供测试入口、96 个固定条目和 8 张本地图片，复用生产 ImageLoader 配置。首页滚动必须等到真实 UI 绘制、本地图成功解码，且能滚到第三个有内容的片架。真实 `MainActivity` 冷启动单独测量 TTID，不等待外部网络，也不将本地固定数据的结果解释成网络加载速度。

实际测量使用继承 release 压缩配置的 R8 `benchmark` 变体；保留源名称的 `profile` 变体用于规则采集。目标和测试 APK 均使用 debug 签名；脚本在安装前校验两者签名、包名和 instrumentation runner，保存 APK 身份证据。测量同时校验设备、显示与动画设置、构建类型及样本完整性；同设备基线比较可以执行显式配置的退化门槛，空结果不能通过。完整操作见 [Android 性能验证与 Profile 采集](ANDROID_PERFORMANCE_WORKFLOW.md)。

S10 上实际完成了 5 轮冷启动，日志报告 TTID 中位数 **649.3 ms**。同次首页测试在预热滚动断言失败，因此整轮宏基准没有通过，也没有有效的完整 `summary.json`。已修正依赖 `UiObject2.scroll()` 返回值而过早结束手势的问题，仍要求实际出现第三个有数据的片架；设备随后断开，这项修正尚待重新运行。上述启动数值只是这一次的诊断样本，没有历史基线可用于计算提升比例。

**尚未完成真实采集：**当前只有 S10（SM-G973U，Android 9/API 28），不符合本次 Profile 采集条件；脚本已实际拒绝采集且没有生成规则。Baseline Profile 仍等待 API 33+ 或既有 root 的兼容设备，没有提交占位规则。专用 self-hosted 物理 runner 尚未配置，工作流代码接通不等于已有线上性能检查。首次采样不能伪造历史基线或预先承诺性能提升百分比。

### 8. 签名与发布元数据

[`release_metadata.py`](../scripts/release_metadata.py) 校验共同的 `version.properties` 与当前版本说明，签名触发文件只保留触发用途。自动发布读取同一来源；Gradle 的版本号与版本名覆盖必须同时提供。手动发布仍可显式覆盖版本和说明。

GitHub Release 使用 [`published_release_metadata.py`](../scripts/published_release_metadata.py) 读取对应成功发布 run 上传的唯一 `update-v2.json`，核对版本、说明、精确 APK 文件名、大小和 SHA-256，再输出下游版本和独立说明文件。这样即使仓库默认版本不同，也能保留本次手动输入。commit 来源始终是 `workflow_run.head_sha`。脚本依赖成功发布 run 的既有签名检查，不宣称独立验证了 Ed25519 签名；缺失或有歧义的 artifact 会阻止 Release，不回退旧版本。

### 9. 释放测量与真实后端回归

[`PlaybackReleaseTiming`](../composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackReleaseTiming.kt) 提供释放分阶段诊断，真实后端仪器测试覆盖 Exo、MPV、MDK 本地 AVC/AAC 播放后退出，以及等待 HTTP 头时退出。回归同时检查释放次数与资源清理，避免根据空播放器的快速释放推断正常播放体验。

真实测试额外修复了三处播放问题：Exo 的自适应起播包装器显式转发 Media3 的默认接口方法；MPV 不再把复制解码路径的空窗口提示判成终止错误；当前媒体必须完成加载并收到播放首帧事件，且 Surface 有效，才能报告已出画面。仅有占位输出窗口、旧媒体 VO 或尚未收到 HTTP 数据时保持等待。

退出测量定位到等待 HTTP 头的 MPV 场景中，应用代理占用 **2001 ms**。兼容代理和 YCore 代理现已移除 `awaitTermination(2000)`：关闭入口停止接受请求，取消已登记的客户端连接及上游请求，由实际工作者完成资源清理。兼容代理的缓存 lease 保留到最后一个工作者结束；YCore transport 的阻塞关闭在 IO 清理作用域执行，原工作者等待真实关闭完成后才归还自己的资源，并保留 Range 重试重新打开的能力。新增回归覆盖关闭竞态、阻塞头/响应体、Range 取消与资源归还；这次最终修正尚未进行 S10 复测。

释放计时仍保留原线程。已确认 MPV SDK 持观察者列表锁执行回调，移除观察者可能等待正在进行的原生属性查询；MDK 的 `close()` 同时包含 Surface 解绑、停止和原生析构。直接把这些调用移到 IO 线程不能保证在途 JNI 已结束或满足 SDK 的 Surface 契约。本轮交付测量与定位，不宣称消除了退出停顿，也不把少量场景样本当作释放延迟 p95。

## 五款新弹窗

新样式在设置中归入“奇想动效”，均由 [`CuriousDialogMotion`](../composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/CuriousDialogMotion.kt) 实现，并接入统一弹窗进出场和减少动画设置。

| 设置名称 | 枚举 | 设计 | 进入 / 退出 |
| --- | --- | --- | --- |
| 信封拆启 | `Envelope` | 封口展开，信笺从下沿向上舒展，辅以轻微上移 | 420 / 260 ms |
| 星图织光 | `Constellation` | 星点与轮廓先出现，中央光面向四边铺开，切角逐渐收平 | 420 / 260 ms |
| 拼图扣合 | `PuzzleLock` | 两侧圆弧拼图相向显露，接缝扣合后归于完整面板 | 400 / 250 ms |
| 沙漏汇流 | `Hourglass` | 上下沙丘向中心汇合，窄腰逐渐舒展为完整卡片 | 420 / 260 ms |
| 风车开页 | `Pinwheel` | 四片纸叶轻转打开，边角依次舒展，结束时回正 | 420 / 260 ms |

五款样式使用共享的进度时钟，不各自运行无限动画。面板内容只绘制一次，通过裁剪轮廓和轻量装饰形成变化，不把文字拆成多份或逐字拉伸。轮廓在结束前已经覆盖完整面板，静止态绕过特殊绘制；减少动画时沿用统一降级路径。已有样式仍参与完整生命周期回归。

## 验证记录

本机证据目录为 `artifacts/project-optimization-20260908/`，下列路径均相对于该目录。源码检查使用隔离工作树；S10 测试使用 `com.yfuse.optimization`，宏基准使用 `.benchmark` 隔离包。

| 检查 | 结果与证据 |
| --- | --- |
| 最终构建 | `optimization-complete-build.log`：Debug、Debug AndroidTest、R8 benchmark、macrobenchmark 的 benchmark/profile 测试 APK 构建通过；281 个任务，耗时 4 分 58 秒 |
| JVM / Android 单元测试 | 2299 项通过，0 失败、0 错误、0 跳过；含半包 HTTP 断开阻塞的最终回归，原 1 秒退出门限未放宽；`unit-test-summary.json`、`unit-test-results.zip` |
| Android Lint | `lint-results-debug.txt`：0 错误、7 条警告；声明级误报处理说明见下文 |
| 发布与性能脚本 | 24 项 Python 测试通过，其中元数据 13 项、性能脚本 11 项；`optimization-python-delivery.log` |
| 代码格式与设计系统 | 修改文件的 ktlint 检查通过；`:composeApp:verifyDesignSystemUsage` 通过 |
| 弹窗与取色仪器测试 | `device/core-ui.log`：6 项通过，含 43 种样式共 129 个生命周期场景、3 项真实 Canvas mask、2 项图片取色测试 |
| 新弹窗视觉 | `device/curious-captures.log` 通过；`device/curious-visuals/` 保存 60 张明暗主题、横竖卡片和 25%/55%/100% 进度截图，4 张 contact 图均已检查 |
| 弹窗帧诊断 | `device/curious-with-lift-frames.log` 通过；原始指标在 `device/motion-metrics-curious-with-lift-s10.json` |
| 实际播放与字幕 | `device/playback-release-acceptance.log`：Exo/MPV/MDK 的 6 个播放/加载退出场景及 3 个 MPV 回退字幕场景通过；发生在最后的代理关闭改动之前 |
| 下载批量操作 | 单元回归覆盖 130 项暂停/恢复/删除，一次批量仅占一个队列槽；另覆盖失败反馈、FIFO 和单项持久化顺序 |
| R8 启动与首页 | `macro-journey-diagnostic.log`：启动 5 轮完成，TTID 中位数 649.3 ms；首页预热失败，手势修正尚待复测，整轮未通过 |
| 依赖扫描 | `supply-chain.log`、`yfuse.spdx.json`：扫描 745 个 Maven 依赖，8 条活跃漏洞记录，未触发既有严重度阻断条件；不表示没有漏洞 |
| Profile | `profile-s10-unavailable.log`：S10/API 28 条件不符，脚本已拒绝生成；没有提交占位规则 |

Lint 的 `RememberReturnType` 根据表达式解析类型判断是否返回 Unit，见 [AndroidX 检测器源码](https://android.googlesource.com/platform/frameworks/support/+/f2e05c341382db64d127118a13451dcaa554b702/compose/runtime/runtime-lint/src/main/java/androidx/compose/runtime/lint/RememberDetector.kt)。本轮发现两处取色键和一处字幕时钟的 commonMain 对象被误判；编译、调用处的类型使用及交叉审查确认它们返回实际对象。三处保留明确返回类型，只在对应局部声明标记误报，未改变缓存行为或全局禁用检查。本地 SDK 路径转义也已修正，机器路径文件不进入提交。

### 弹窗测量的适用范围

S10（SM-G973U/API 28）的 Debug 诊断使用 60 Hz 显示和系统 `animator_duration_scale=0.5`。每款重复 5 次，新样式的“每轮 p95 再取中位数”为：信封 19.92 ms、星图 20.85 ms、拼图 20.46 ms、沙漏 18.99 ms、风车 20.80 ms；这不是全部帧合并后的 p95。新弹窗仍有约 13.9%–24.3% 的超预算帧。上述 p95 和比例排除窗口首绘帧，首绘另行记录，约 97–150 ms。这组数据用于发现问题，不能作为 release FPS 或所有机型流畅度的承诺。

### 退出测量与后续设备验证

最后一次已通过的 S10 释放样本保存在 `device/release-summary.json`：Exo 加载/播放约 5.4/83.6 ms，MDK 约 104.2/377.0 ms，MPV 约 2127.4/61.8 ms。MPV 加载场景中的 2001 ms 来自代理等待，促成了本轮最后的修正；这些数字属于修正前，不能当成修正后的效果。

最后的单元回归还发现 JDK 的 `HttpURLConnection.disconnect()` 在半包读取时会等待内部锁。因此兼容代理将上游断开交给共享的两个后台清理线程，关闭入口不等待其完成；实际读取资源仍由原工作者清理。YCore 使用共享 IO 清理作用域，极端 IO 池饱和时实际上游关闭可能排队，尚未进行该压力场景验证。

S10 随后从 ADB 断开，本轮还缺少最后代理关闭修正的真机复测，以及首页滚动手势修正后的完整宏基准。MDK 原生销毁、MPV 原生回调等待仍有真实停顿证据，需结合 SDK 线程契约继续优化。

真实 Baseline/Startup Profile 仍需 API 33+ 或既有 root 的兼容设备；专用物理 runner 也尚未配置。当前没有可用于性能提升百分比的同设备历史基线，没有触发生产签名或线上发布。
