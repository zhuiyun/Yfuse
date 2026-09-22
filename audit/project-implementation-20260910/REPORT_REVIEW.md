# 《Yfuse 九月体检》复核

复核日期：2026-09-10。对象为用户粘贴的报告，与当前 D:/Demo/Yfuse 工作区源码、现有构建日志及官方文档对照。只审阅报告，没有修改产品代码、工作流或操作手机，没有新跑真机测试。原报告明确对应当天上午的工作区，因此已经解决的问题属于状态过时，不能反推原报告当时错误。

结论：报告有价值，但不能直接作为修复清单。性能热点和若干正确性问题有源码依据；部分风险定级偏重，数处技术判断错误，把源码推断写成了运行事实。优先纠正下面的条目，再排期。

## 必须纠正的结论与建议

### 1. TV 编译阻塞已经解决

媒体发现页已从活动源码移除，TV UI 和测试已迁入 tvApp，并添加 scripts/verify-module-boundaries.py。最近本地构建成功，Android 2,429、TV 59、协议 8、服务端 175 项单元测试无失败，总计 2,671 项。证据为同目录 feature-tests.log、test-summary.json。协议和服务端在最后一轮沿用有效缓存。

不能再列为当前 P0，也不应按原建议重新 stub 或补回媒体发现。用户已经要求移除该功能。其他编译变体、远端 CI 是否通过不能从这些测试外推。

### 2. “所有带 List 的状态都不能跳过、整个树每 tick 重组”不成立

项目 gradle/libs.versions.toml:2 使用 Kotlin 2.2.21，未发现显式关闭 Strong Skipping。该模式自 Kotlin 2.0.20 默认开启，允许具有不稳定参数的可重启 Composable 跳过；不稳定参数通常按实例比较。

源码确实在 App.kt:221 订阅 ActivePlayback、PlayerRoot.kt:566 订阅播放状态，因此频繁更新会使读取状态的组合范围失效。但这不等于全部子组件重组、所有 LaunchedEffect 重启，更不等于已证明卡顿来自 Compose。report 应改为“高层状态订阅可能扩大重组范围，需查看编译器报告和具体读点”。拆分 position 状态有价值；直接把 PlaybackState/HomeState 写进 stability 配置不是无风险修复，必须满足稳定性约定。

依据：[Strong Skipping](https://developer.android.com/develop/ui/compose/performance/stability/strongskipping)、[稳定性修复与契约](https://developer.android.com/develop/ui/compose/performance/stability/fix)。

### 3. 三处 11–16 dp 点击目标是误报

报告列出的 EpisodeStrip.kt:115、DanmakuPanel.kt:332、563 使用 noRippleClickable。其实现位于 PlayerControlComponents.kt:290，实际组合为 pressable(...).touchTarget()；Interaction.kt:58、75 将目标扩展到 48 dp。11–16 dp 是图标尺寸，不能作为点击目标尺寸。

这不等于全部无障碍已经达标。contentDescription=null 可用于装饰图标，不能按 null 数量计算缺陷；PlayerPanel 自身没有 semantics，也不能忽略子组件、Text、Button 的语义。仍需定位具体缺失的操作名称、焦点或语义边界。

### 4. 更新清单签名“可选”属实，“正式包实际关闭”尚未证实

AppUpdateManager.kt:209、225 在空公钥时放行；composeApp/build.gradle.kts:1076 不拒绝空公钥；publish-android.yml:399 允许未配置清单密钥继续发布。这是可确认的发布策略缺口。

但是 build.gradle.kts:783 还读取环境变量，工作流 :391 会从发布私钥派生并注入公钥。仅看 gradle.properties:31 为空，不能知道已发布 APK 最终嵌入了什么。没有读取发布 Secrets，也没有检查目标发布 APK 的 BuildConfig；应将这部分标为待核实。

“劫持清单就能降级”也需改写。AppUpdateManager.kt:86、878 只提供比已安装版本更高的版本，安装前还核对包名、版本及签名者。攻击者可能抑制更新或在可接受版本范围内回放旧发布信息，但这不同于任意回退到低于已安装版本。即使清单签名开启，没有防回放机制也不能单靠签名保证最新。

HTTPS 的 IP 地址并不自动意味着证书不可信；当前 HttpClientFactory.android.kt 保留平台证书及主机名校验。迁移域名有运维价值，但“无 pinning”本身不足以判为 P0。优先确认发布产物、公钥配置及回放策略，再实施兼容现有客户端的门禁。

### 5. HTTP 风险属实，但“NSC 改 false 后动态白名单放行”方案不完整

ServerEndpointPolicy.kt:23–60 确实忽略确认参数，允许 HTTP；network_security_config.xml:3 全局允许明文。凭证经公网 HTTP 传输的风险成立。

Android NSC 是随包发布的 XML 策略。应用层允许名单不会自动覆盖平台的拒绝策略；对任意用户自定义服务器，直接把 base-config 改 false 会导致使用该平台策略的客户端被拦截。还必须考虑 AndroidYCoreHttpProxy.kt:1944 的 127.0.0.1 HTTP 播放代理。

建议先完整定义用户确认、服务器未加密标识、重定向与凭证发送边界，并验证所有媒体传输路径，再调整平台配置；不能直接照抄报告中的两行建议。依据：[Android NSC 配置](https://developer.android.com/privacy-and-security/security-config)。

### 6. CI 风险段落混淆了权限与发布行为

- repackage-android-signed.yml:13 是 contents:write，签名任务使用 production environment。
- sign-android-branch.yml:20 是 contents:read，另有 statuses:write；签名任务 :178 也使用 production environment。
- tv-release.yml:61 是 contents:read，:72 使用 production environment；分支 push 路径 :110 强制 PUBLISH_TO_PLAY=false，真正上传 :413 受手动输入限制。

长期分支仍能触发接触生产密钥的签名流程，值得核查。但“这三个都拥有 contents:write、推送就签名发布”不是准确描述。production 环境是否配置审批和分支限制，要查 GitHub 设置，不能从 YAML 推断已经有或没有保护。

### 7. 没有应用 Profile 属实，缺少插件不是不能打包 Profile 的原因

当前没有已采集的应用 Profile，也没有成功的完整宏基准基线。已增加导航旅程和导出校验脚本。最后一轮设备旅程没有全部通过，随后用户要求停止手机测试，不能把构建成功当作性能通过。

AGP 可以消费 src/main/baseline-prof.txt，androidx.baselineprofile 插件用于自动化生成与管理，不是手工规则入包的必要条件。不能用占位规则补“完成”。依据：[手工创建与测量 Profile](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure)。

## 有源码依据、应保留的条目

| 报告条目 | 复核结果与边界 |
| --- | --- |
| 临时目录忽略规则不足 | .gitignore 未整体忽略 .worktrees/、.gradle-tmp/、.codex-calendar-optimization/，有误提交风险。audit/ 中含应保留的文本报告，不宜无差别全忽略。磁盘大小不是会进入 Git 的体积，嵌套仓库也不会必然按普通目录整体展开。原来的 142 文件、4.8 GB 是旧快照，没有复现它的统计口径。 |
| 弹幕逐帧组合阶段读时间 | DanmakuOverlay.kt:274 写 renderedPositionMs，:330、389 在组合阶段读取。offset lambda 捕获的是已经算出的 x，确实没有把时间读取推迟到布局阶段。应保留。 |
| 状态桥反复分配 | YPlayerVideoEngineAdapter.kt:135、145 每次 value 访问转换对象并 map 两个 track 列表。成立，但调用频率和耗时未测；不能单凭此给出掉帧结论。 |
| 诊断脱敏先于去重 | PlayerRoot.kt:1706 的 SideEffect 调用注册表，PlaybackDiagnosticReportRegistry.kt:107–188 在构造和脱敏后才比较旧内容。成立。应在结构化诊断输入上去重，并保持敏感数据进入任何日志出口前脱敏；简单裸文本 hash 有碰撞和敏感信息驻留问题。 |
| 主线程启动工作 | YfuseApp.kt:95 起打开偏好、初始化诊断；ServerSessionRecovery.android.kt:39 同步 start；MainActivity.kt:106 起在 setContent 前解析依赖。存在同步链路。耗时和“最多 100×3”不能当实测；SharedPreferences 的底层加载与首次读取等待也应区分。 |
| Library 缓存重复解析 | LibraryStore.kt:167 在默认 Main 执行器内同步读缓存；LibraryCache.kt:70 起先 parseToJsonElement，再 decodeFromString。成立，是比“八个客户端一定慢”更直接的优化目标。 |
| Emby 数据转换线程 | EmbyBrowseService.kt:299 的 body 及后续 DTO 映射没有本地切换，EmbyApiCall.kt 也不切线程，Main 调用路径存在。网络 I/O 本身不能因此称为主线程网络操作；反序列化需结合实际 Ktor converter 路径确认。 |
| 首页强制日历刷新 | HomeScreen.kt:203–209 路由重新可见时强制刷新成立。请求数量取决于关注条目、缓存和身份解析命中，报告中的“几十个/16 并发”不是本次运行记录。 |
| 多个客户端 | AppModule.kt:125、171、188、206 四处创建 account client；HttpClientFactory.android.kt:40 起创建独立 dispatcher，共享连接池。成立。多个客户端有配置隔离用途，必须以线程数和请求调度为依据决定共享范围，不能只按数量判缺陷。 |
| 没有 Ktor HttpCache | 未见安装该插件，但已有 Library、TMDB、Calendar 等业务缓存；不能等同于“没有缓存”。增加 HTTP 缓存需保留用户/服务器隔离、认证及失效语义。 |
| Trickplay 拼图 | PlayerChrome.kt:974 起按网格大小布局整张 AsyncImage，且 Refined 路径仍调用它。存在过大解码请求风险；4560 px 是示例推算，实际 bitmap 大小需看源图和 Coil 请求/解码结果。 |
| 海报与玻璃效果 | Poster.kt:142 与 Backdrop.kt:183 存在 blur/layer record 路径。需要补上 API、alphaOnly、内存缓存命中、reduce-transparency 等开关；层绘制命令记录不等于每帧把全部内容重新 CPU 光栅化，不能仅靠调用数推断 GPU 成本。 |
| 增量构建验证 | composeApp/build.gradle.kts:144、235、337、479 的验证任务未声明输出，常规情况下会重新执行。可改为具有完整输入声明的验证输出；直接限制只在 release/CI 执行会削弱本地真实性校验。单纯加 stamp 也要覆盖验证逻辑、源码与依赖变化。 |
| 字幕旧编码 | YSubtitle.kt:215 起只处理 UTF-16 BOM 和 UTF-8，旧编码乱码风险成立。“30 行按顺序尝试就能检测”不可靠：多种字符集可能都合法解码，需要置信度与手动选择，且文件在 commonMain，不能直接引入 JVM Charset。 |
| 自动恢复计数 | PlayerRoot.kt:1994–2009 在健康评价恢复、playing 且非 buffering 时清零。长时间会话可以多次重新获得重试预算。窗口计数建议合理；报告漏掉了健康评价条件，不能声称所有瞬时恢复都会清零。 |
| Outbox 丢终态 | PlaybackEventOutbox.kt:337 起优先删 Progress、Started，极端满载只剩终态时才删 Stopped 并记录错误。成立但须保留触发条件。重试 Started 会复用 event.sessionId（PlaybackReportingCoordinator.kt:231），是否服务端产生第二会话需要服务端幂等语义证据，报告没有证明。 |
| 代理 accept 静默退出 | AndroidYCoreHttpProxy.kt:788 起 accept 失败会 break，未区分预期关闭与异常退出。成立。应对非关闭错误补观测；不能把正常连接取消的 catch 全当缺陷。 |
| PlayerChrome 旧代码与结构债 | TopBar、BottomBar 等在当前源码未见外部调用；TrickplayPreview 等仍有活跃调用，不能整文件删除。精确“1100 行死代码”仍需依赖闭包核对。PlayerRoot 当前文件 3,478 行，已提取氛围光绑定但仍很大；文件行数与函数行数不要混称。 |

## 功能与统计部分需要降格为建议

平板专属布局、主题媒体、直播、OpenSubtitles 等属于产品选择，不是仅凭服务端存在接口就必须补齐的缺陷。WindowWidthTier 使用少可说明集中式适配有限，不能证明其他页面完全没有响应式处理；DetailScreen.kt:366 已有宽度分级逻辑。

“家长控制”文案存在于 ServersScreen.kt:729，但访问内容可能由 Emby/Jellyfin 服务端账号权限控制。客户端没有管理入口，不等于服务端限制失效；应明确区分“遵守服务端限制”和“客户端可配置家长控制”。

测试报告应区分单元、UI、仪器和性能测试。缺少 Compose UI/截图用例可以保留，但已有宏基准仪器工作流；不能写成所有 connected 测试都无 CI。feature/watch 目录无测试，也不等于同看协议、服务端和播放器同看逻辑零覆盖。

1,210 文件、1,573 提交、730 次 runCatching、3167 处中文等数量缺少可复现命令、纳入目录及排除测试/工作树的规则。本轮未追溯上午的精确快照，不把这些数字当当前指标，也不据此判断质量。未独立验证“四条并行审计”的执行过程。

## 修订后的处理顺序

1. 移除已经解决的 TV P0，补充当前测试证据；明确临时目录与应留档文本的提交边界。
2. 优先解决可确认的明文凭证交互策略、更新签名配置门禁、字幕编码与异常恢复/代理观测问题。发布 APK 的公钥与 GitHub 环境保护状态单独核验。
3. 优化确定存在的重复工作：诊断输入去重、Library 缓存重复解析、弹幕时钟读点、状态桥分配。用本地单元测试和编译器报告验证正确性；性能收益保持“未实测”。
4. 再处理启动调度、图片解码、验证任务增量化、死代码及大文件拆分；新功能另按产品需求排期。

没有足够证据支持把报告所有“P0/P1”按原级别照单执行。用户已要求不使用手机测试，真实 Profile、掉帧和启动收益在本次复核中均不补测、不推定通过。
