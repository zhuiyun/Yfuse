# Yfuse 两份诊断包排查报告

排查日期：2026-09-09。当前源码：`805af51c81247e87de6e1a0ee10101b97c64e990`，版本元数据 1.0.45（207）。本次完成日志分析和源码核对，未修改业务代码，未运行真机复现或重新打包。

**结论：存在取流/服务器异常，也存在客户端放大故障的代码缺口。优先处理起播探测的整体超时与取消、SoftwareFallback 路由重试的生命周期，以及样式字幕导致的重复内核重建。现有 207 本地包不能视为已解决全部问题。**

**9 月 9 日补充：** 用户另报《绿灯军团》手机中文字幕“上一句不消失，新句不断叠加”。已确认并独立复现 PGS 清屏丢失、开放结束时间误解为约 49.71 天、旧展示集持续追加的缺陷；当前 207 关键路径仍存在。日志虽有 PGS 轨，但尚不能完全确认该片实际选中的字幕格式。详见 [字幕堆叠专项报告](D:/Demo/Yfuse/audit/diagnostics-20260909/SUBTITLE_STACKING.md)。应将该项列为优先修复的独立播放问题。

## 1. 样本与版本边界

| 诊断包 | 设备 | JSONL 记录 | 日志覆盖范围（北京时间） | 实际会话版本 |
| --- | --- | ---: | --- | --- |
| `224345` | OPPO PLG110，Android 16 / API 36 | 528 | 9 月 8 日 22:35:02–22:43:48 | 三个会话均可确认 1.0.43（205） |
| `204707` | OPPO OPD2409，Android 16 / API 36 | 399 | 9 月 8 日 07:09:45–20:47:09，期间有长时间空档 | L1–250 为 1.0.41（203）；L251–399 为 1.0.43（205） |

合计 927 条 JSONL，均成功解析。日志包自报丢弃条数、写入失败数为 0。包中的文字仅作为诊断数据处理。

重要边界：

- 平板包的 `device-info.txt` 写的是导出时版本 205，不能用它覆盖所有历史事件。平板日志 L239 的更新检查明确 `currentversioncode=203`，L251 才开始 205 会话。
- `playback-report.txt` 的 timeline 从本地存储恢复，可跨进程、跨版本。平板 timeline 最早是 **9 月 7 日 21:43:01**，早于该 JSONL 的第一条。不能把 timeline 全部归入本次启动或 205。
- 仓库存在同版本 205 在 `9d565adf` 重新构建的记录。诊断包没有应用 Git 提交或 APK 哈希，不能唯一确定安装包源码；相同原生库哈希也不能证明 Kotlin 层相同。
- 以下日志行号均指原 ZIP 中 `logs/diagnostic-20260908-001.jsonl`。本目录的原文副本保留原行号。主机、服务器 ID 已脱敏，无法把不同操作可靠归并到某一台服务器。

证据：[平板版本切分](D:/Demo/Yfuse/audit/diagnostics-20260909/204707/diagnostic-20260908-001.jsonl:239)、[205 重打包记录](D:/Demo/Yfuse/artifacts/Yfuse-1.0.43-205-full-arm64-9d565adf-signed-report.md:8)、[timeline 恢复实现](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlaybackDiagnosticReportRegistry.kt:46)。

## 2. 优先修复项

### P1：平板 205 起播探测累计约 111 秒，退出后旧探测继续运行

同一 playback trace 的记录：

| 时间 | 行号 | 证据 |
| --- | ---: | --- |
| 20:45:29.407 | 329 | Range 读取 `SocketTimeoutException`，`attemptcount=3` |
| 20:45:29.466 | 330 | `platform_probe elapsedms=30123` |
| 20:45:50.200 / 20:46:02.650 | 336 / 341 | 后续代理/预取 Range 再次三次尝试后超时 |
| 20:46:13.637 | 346 | `engine_detached` |
| 20:46:50.754 | 377 | 旧 trace 的 `enhanced_probe elapsedms=81287` 才完成 |
| 20:46:50.759 | 379 | 旧 trace 继续记录 `route_selected=NativeEnhanced` |

两段串行探测累计 **30.123 + 81.287 = 111.410 秒**。旧探测完成时间比 detach 晚 **37.117 秒**。这表示旧探测的执行寿命；用户已中途退出，不能称用户连续在同一个页面等了 111 秒。

第二次进入还有 `PlaybackInfo` 约 15 秒超时并走 URL 兼容阶梯（L355、358–359），随后平台探测耗时 16.937 秒（L383）。20:46:56 已选到 `c2.qti.dv.decoder`（L389），但 L391 因页面不可见暂停，导出前未见该次首帧成功证据。不能据最终 `Waiting` 断定设备不支持杜比视界。

**源码缺口已确认：** [AndroidCore2MediaProbe.kt:514](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidCore2MediaProbe.kt:514) 的 `evaluate` 是同步调用，`resolveProbe` 先做平台探测，失败后再做增强探测（L539 起），没有贯穿两者的整体起播 deadline 或取消令牌。[AndroidEnhancedMediaProbe.kt:139](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedMediaProbe.kt:139) 同步进入 demux open。[AndroidAdaptiveCore2YPlayer.kt:334](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:334) 的 release 取消协程，但主起播路径在同步 evaluate 返回后缺少立即检查取消/代次的保护（L815）。预加载路径另有 `ensureActive`，主起播没有对应检查。

**判断：** 底层取流异常是真实触发因素；探测预算逐段叠加、阻塞探测不能及时响应取消，使等待与后台残留进一步加重。日志证明退出后旧探测仍完成，不能据此断言旧画面重新显示或必然发生内存泄漏。

**修复方向：** 将一次起播的剩余时间预算传至平台探测、增强探测、代理及实际网络请求；退出/新一代播放开始时中止旧资源；每个阻塞返回点核对取消与播放代次，过期结果只清理、不创建/发布新播放器。仅在外层加 `withTimeout` 不足以中断已有同步 native 调用。

**当前状态：** 这些主路径文件与候选 205 基线 `9d565adf` 相比未变。206 的旁路媒体信息探测优化不等于修复此处 YCore 起播探测。

### P1：手机网络恢复后解码器初始化失败；SoftwareFallback 未进入原地恢复保护

手机 205 的严重故障链：

1. 22:39:54–22:40:35，多个媒体 Range 返回 403（L274、280、293、298、305）。
2. 22:41:01 / 07 / 19，多个 Range 三次尝试后仍 `SocketTimeoutException`（L316、321、326）。
3. 22:41:28.347，在约 49 秒播放位置触发 `long_rebuffer_recovery / RebufferTimeout / attempt=1`（L330）。
4. 22:41:28.371，重建路由仍为 `SoftwareFallback / Enhanced / Hardware / SurfaceDirect`（L332）。
5. 22:41:28.640，`VideoDecoderConfigure` 失败，底层摘要为 `No local MediaCodec decoder accepted video/hevc`（L335）。随后显示“YCore 2.0 无法启动当前视频解码器”（L336）。

**不能解释为设备永久不支持 HEVC。** 该次失败前已经使用 `c2.mtk.hevc.decoder` 输出画面（L281）；后续新进程、另一媒体项/trace 又以相同解码器成功输出（L501、519）。后一次不是已证实“原片原源恢复成功”。

**源码缺口已确认：** [AndroidAdaptiveCore2YPlayer.kt:1911](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:1911) 的 `canRetryCore2RouteInPlace` 包含 NativeTunnel、NativeDirect、NativeEnhanced、GpuEnhanced，遗漏 **SoftwareFallback**。但本例该路由实际仍是硬件视频 + Enhanced 会话。

[同文件 L1710](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:1710) 的 Retry 因而进入 rebuild；[stopChild L452](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:452) 调用旧 child.release 后不等待完成。[AndroidNativeEnhancedYPlayer.kt:267](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeEnhancedYPlayer.kt:267) 的 release 是取消 worker，清理由 worker 随后执行。代码已有“避免新旧 codec 竞争、严格顺序重开”的保护注释，但未覆盖此次路由。

**判断：** “网络异常后恢复，再发生解码配置失败”由日志确认；“旧解码器尚未释放导致资源竞争”是与代码吻合的强假设，尚无设备错误码证明。Surface、具体 MediaFormat、厂商 codec 错误仍需排除。

**修复方向：** 对实际由 Enhanced child 执行的 SoftwareFallback 同样保证恢复时释放、重开、配置的顺序；避免仅凭路由名称盲目泛化所有 fallback。现有 [AndroidAdaptiveCore2RecoveryPlanTest.kt:126](D:/Demo/Yfuse/composeApp/src/androidUnitTest/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2RecoveryPlanTest.kt:126) 还明确断言 SoftwareFallback 不支持原地重试，应新增覆盖“该标签下实际为 Enhanced + 硬件视频”的场景。补充 codec 每次尝试的名称、diagnosticInfo、errorCode、recoverable、transient。相关细节已保存在 `YVideoDecoderAttemptFailure`，但 [AndroidMediaCodecVideoNode.kt:524](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidMediaCodecVideoNode.kt:524) 的汇总异常没有把它们纳入 cause，导出日志丢失了关键判据。

**当前状态：** 路由保护遗漏和关键实现仍在 HEAD，不能宣称 207 已修复。

### P2：样式字幕可触发一次无效的 MPV 切换，实际重建 YCore

手机 L108 在 22:37:11.711 记录 `from=Exo / to=Mpv`，L109 实际释放的是 `YCore2Native`，随后 L126–127 仍由 YCore 输出，重建后的首视频输出耗时约 **1.697 秒**。

[PlayerRoot.kt:2797](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerRoot.kt:2797) 在自动模式选择需要样式渲染的字幕时会调用 `switchEngine(Mpv)`，缺少其他分支已有的 `!core2NativeOnlyActive` 限制。[PlayerEngineFactory.kt:60](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerEngineFactory.kt:60) 与 L112 起会按 YCore/纯内核设置继续创建 YCore。

**判断：** 日志确认请求切换后实际仍是 YCore；源码确认样式字幕存在这一触发路径。日志没有显式记录字幕点击，不能断言该条事件一定由某个字幕点击触发。

**修复方向：** 纯 YCore 模式按 YCore 的字幕能力处理选择，不为兼容内核标签重建同一个内核；切换日志同时记录请求选择与实际实现。当前代码仍存在该路径。

### P1（影响在线升级）：安装包缺更新验签公钥

手机共 4 次 `RejectedNoKey`（L5、19、340、395）；平板共 2 次（L241 为旧 203，L264 为 205）。相邻错误明确指出未内置升级签名公钥。这不是网络重试可以解决的问题。

[AppUpdateManager.kt:211](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/update/AppUpdateManager.kt:211) 读取包内公钥，Release 缺失时拒绝更新来源。[207 本地构建报告 L18](D:/Demo/Yfuse/artifacts/Yfuse-1.0.45-207-full-arm64-805af51c-signed-report.md:18) 明确该包公钥也未配置。

**处理方向：** 后续用于在线升级的包需内置与更新清单签名匹配的公钥，并验证清单。当前缺公钥包需要先手动覆盖安装正确配置且保持 APK 签名证书一致的版本；现有 207 手动包本身不解决在线升级。

## 3. 网络与服务器侧异常

### Emby：访问拒绝、认证/权限和服务错误并存

以下只统计 `emby/request_failed` 操作失败记录，不重复计算首页/同步等上层转报，也**不等于实际 HTTP 请求数**：

| 分类 | 手机全包，均 205 | 平板全包 | 平板仅 205 |
| --- | ---: | ---: | ---: |
| Cloudflare AccessDenied | 0 | 16 | 11 |
| Unauthorized | 11 | 5 | 5 |
| Network | 36 | 10 | 6 |
| 500 | 7 | 1 | 1 |
| 502 | 0 | 1 | 1 |
| 503 | 24 | 0 | 0 |
| 504 | 9 | 0 | 0 |
| 522 | 6 | 0 | 0 |
| 合计 | 93 | 33 | 24 |

平板 20:44:42–47（L267–274）Cloudflare 拒绝波及目录、同步、健康探测、首页；20:44:48（L275–278）还有 Unauthorized。手机 22:40:18（L288–289）出现 503，其他时段还有 500/504/522。

这解释了日志中的媒体库、详情、继续观看、跨服源查找、日历目录及同步失败。但服务器标识统一脱敏，不能认定这些都是播放所用同一台服务器，也不能把全部失败归因于一条网络链路。

`Unauthorized` 在本项目可以由 401 或未识别为 WAF 的 403 产生，不能仅凭名称断言密码错误。[分类实现](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyApiCall.kt:60)；[HTTP 工厂](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/network/HttpClientFactory.kt:215) 对已拒绝 origin/token 有 5 分钟冷却，部分后续失败可能在本地直接返回。

**处理方向：** 按具体服务器分别核对访问授权、会话和接口可达性；由服务管理员针对当时 403/WAF 与 5xx 记录核查拦截和源站。需要能关联到服务器的脱敏稳定标识及响应 request-id，不能从现有包反推出真实地址。

### 媒体 Range：手机 11 条 403、5 条超时；平板 6 条超时

手机 16 条 `transport_range_failed`，其中 11 条带 403，5 条为三次尝试后的 SocketTimeoutException；平板 6 条均为超时链，旧 203 占 2 条，205 占 4 条。多数为预取或本地代理线程，不能把每条都算作用户可见卡顿。

手机 L206 的 `status=403 / failurekind=TransientIo / attemptcount=3` 不宜视为分类 bug：[AndroidAdaptiveHttpMediaTransport.kt:504](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveHttpMediaTransport.kt:504) 明确将已成功取过 Range 后发生的 403 视作可能的短时重定向失效，执行有界刷新；首次拒绝才归 Authorization。现有日志不能区分链接过期、权限、WAF 和具体 CDN 策略。

### 平板 203 的历史严重卡顿与提前结束

早上 07:10–07:22 的旧会话中，QoE 可见 demux 队列从有数据变为 0，同时源读取阻塞增加；单次记录的 `sourceblockedreadms` 最高 **82471 ms**（L124），累计最大画面输出间隔 **99500 ms**（L130）。同段 pump 单次耗时最高约 14 ms，`slowpumps=0`，支持主要瓶颈在取流供给，而不是当段播放器循环长期占用 CPU。

07:15:25.953（L131）在约 1884 秒位置已尝试提前 EOF 恢复；07:22:57.609（L238）最终以 Network 失败：“片源在声明时长前提前结束，已判定为网络传输中断”。不能把播放器分类等同于已定位服务器端根因。

`1921bda7` 后续已有连续恢复预算、稳定播放后重置及 Range watchdog 等补强；但 205 晚间仍有上文的长起播与取消缺口。旧会话的失败不能作为 205 同一缺陷已复现的证据，也不能据提交存在宣称真机已修复。

## 4. 其他功能

| 功能 | 证据与影响 | 判断与处理 |
| --- | --- | --- |
| 推荐首页 | 平板 205 L304–310 共 7 条 TMDB feed 失败，均 `answered=false/status=none`；L310 明确 20 秒超时，L311–312 最终无可用推荐 | 本包没有令牌无效的 401 证据，也无法细分 DNS/TLS/代理。207 的 `805af51c` 已修复错误分类、失败栏目缓存保留和刷新提示；[207 报告 L16](D:/Demo/Yfuse/artifacts/Yfuse-1.0.45-207-full-arm64-805af51c-signed-report.md:16) 明确未验证设备网络恢复 |
| 云端播放进度同步 | 平板 L61、81、104、210、225 是 30 秒请求超时；L370 为 `stream was reset: CANCEL`，合计 6 条延后同步记录 | 日志可见 30/60/120 秒退避；[PlaybackSyncManager.kt:553](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/sync/playback/PlaybackSyncManager.kt:553) 记录失败并维护 pending。不能说已丢失本地观看记录，也不能说已同步成功 |
| 媒体服务器进度回写 | 手机 L160、平板 L276 因访问拒绝冷却 1800000 ms，pending 分别为 1、5 | 30 分钟内对应服务器回写暂停，影响跨端续播及时性；需恢复该服务器访问。与 Yfuse 账号登录是否成功是不同层次 |
| 日历绑定 | 手机 L161、453，`calendar_binding_write_failed / Timed out waiting for 8000 ms` | [AiringCalendarRepository.kt:818](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core/data/AiringCalendarRepository.kt:818) 的 8 秒预算覆盖整个服务器查询；L998 的 `runCatching` 包住本地写入并将超时取消记作写失败。不能据此认定 SQLite 损坏。应让取消异常继续传播，区分查询预算耗尽与真实持久化失败 |
| 弹幕 | 平板旧 203 L26 HTTP 500；205 L328、381 为 15 秒请求超时 | 可导致弹幕缺失；日志未证明视频解码器因此失败。手机有 4 条 `danmaku/loaded`，不能说两设备弹幕均不可用 |
| 界面卡顿 | 手机 13 条 jank_summary，合计记录 29 个慢帧，最长 145 ms；平板 3 条、8 个慢帧，最长 144 ms | 是 UI 帧耗时，不能当作视频帧卡顿或 ANR；缺少页面标签/堆栈/总帧数，无法算可靠卡顿率或定位具体动画。需有页面上下文的帧分析再优化 |

账号兼容请求 `refresh_legacy_schema_fallback`（平板旧 203 L147）随后 L148 `outcome=success`，已通过旧格式回退完成；205 两台设备的 `restore_finished` 都是 `signed_in`。`policy_unpublished=404`（平板 L263）是按包内播放策略运行的提示。`playback_paused_for_lifecycle`（L391）明确因页面不可见暂停。这些不能单独作为登录失效、配置故障或无故暂停。

## 5. 音画与诊断口径

- 手机早先 Dolby Vision P8 媒体已有 `dolbyvisionoutput=true`（L92），音频选择 Eac3Joc 经软件解码至 PCM（L87），随后已有首音频输出（L95）。这不等于 Atmos bitstream 或已验证空间音频输出；日志没有提供“声音必然异常”的证据。
- 手机最终报告是另一媒体项的 HDR10/PQ + AAC/PCM，音视频 Rendering。不能把前后不同片源的 Dolby/HDR 字段当作同一影片丢失杜比效果。
- 平板最终报告 `video/dolby-vision / Waiting / output=unknown` 处在新起播过程；不足以证明花屏、HDR 错色、杜比输出失败或硬件不支持。
- 手机最后一个会话记录 55 个 dropped frames，但同时有 2026 个 rendered frames（L519），无 demux starvation/audio underrun；只是一段采样累计值，不是整包平均掉帧率。`av.offset.ms=unavailable` 表示没有可用测量，不能解释为音画同步完美或已经失步。
- 导出顶部 `buffer.events=0`、`source.starvations=0` 是最新快照，可因新会话/重建而清零，不能推翻 timeline 和 JSONL 中已有缓冲失败。不同层的 `buffered.ms` 与 `source.buffered.ms` 也不是同一缓存口径。
- 包内原生崩溃计数均为 0，且未见可确认的 native tombstone/ANR 证据。手机发生新进程启动，但日志不能说明是用户重开、系统终止还是其他原因；不能据此宣称“发生原生崩溃”。

**诊断补强建议：** 导出加入应用 Git SHA/构建标识；timeline 每项标注会话与版本；关键网络事件保留不可逆稳定服务器/播放源关联标识；codec failures 结构化导出；起播探测记录取消请求、取消完成、deadline 及 generation；UI 慢帧带页面/操作上下文。保持现有凭据和主机脱敏。

## 6. 建议执行顺序与验证

1. **先修 YCore 起播 deadline/取消。** 在慢首块、后续 Range 停滞时退出再重进；确认旧请求真正取消、过期探测不再发布路由、总体等待不按各层预算累加。
2. **修恢复生命周期与字幕重复重建。** 覆盖 SoftwareFallback 但硬件视频的路径，注入持续断流后恢复；记录旧 codec 释放完成与新 codec 配置顺序。纯 YCore 选择样式字幕时应保持当前播放器连续运行。
3. **补 codec 错误码后在 PLG110 重测原片原源。** 排除资源竞争之外的 Surface/格式/厂商失败。后续不同片源能播不能替代这一验证。
4. **修更新包配置并验证清单签名。** 现有 207 是手动包，缺公钥这一点仍然存在。
5. **按服务器处理访问与服务故障，复测推荐、同步和弹幕。** 使用新版本错误分类比较不同网络，配合对应服务端日志，不把各服务的错误混在一起。
6. **修日历取消误报，按实际页面补 UI 采样。** 不根据现有通用慢帧汇总直接改动画或数据库。

本次验证范围为 ZIP/JSONL 完整解析、按会话版本分组、时间差与计数复核、日志—源码调用链交叉检查、候选 205 与 HEAD 的相关文件差异检查。没有对设备、真实片源或服务器进行联网复现，也未运行针对代码修改的测试，因为此次没有业务代码修改。

## 附件索引

- [手机原始日志](D:/Demo/Yfuse/audit/diagnostics-20260909/224345/diagnostic-20260908-001.jsonl)、[手机播放快照](D:/Demo/Yfuse/audit/diagnostics-20260909/224345/playback-report.txt)
- [平板原始日志](D:/Demo/Yfuse/audit/diagnostics-20260909/204707/diagnostic-20260908-001.jsonl)、[平板播放快照](D:/Demo/Yfuse/audit/diagnostics-20260909/204707/playback-report.txt)
- [按会话版本汇总](D:/Demo/Yfuse/audit/diagnostics-20260909/session-summary.json)、[全包事件汇总](D:/Demo/Yfuse/audit/diagnostics-20260909/summary.json)
