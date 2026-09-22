# REPORT.md 问题复核记录

复核日期：2026-09-11。基线提交：`04dc5086eccaacdf3013080b6ecc5d353f1663ee`，与原报告相同。用户授权范围为核查问题；原报告中的升级、删除、改造建议未作为执行指令。

结论：报告包含真实的依赖门禁问题、无界命令队列和维护性债务，但混入多项误报及过度推断，不能直接作为修复清单。最明显的错误是 BouncyCastle HIGH、服务端缺字段崩溃、凭据明文存储，以及“三份完整副本”。本次仅新增本目录的核查脚本和证据，未修改业务代码、版本或原报告，未打包、删除、推送或发布。

## 依赖与扫描范围

本次从 `git ls-files` 获取五个受跟踪的 `gradle.lockfile`，应用仓库现有安全覆盖，再调用原扫描器的 OSV 查询与严重度判定函数。共 **745 个有效 Maven 坐标、10 条活跃记录：1 CRITICAL、0 HIGH、5 MEDIUM、4 LOW**。按现有阻断规则判定为退出码 1。核查脚本本身正常执行返回 0，输出 `verified_gate_exit=1`。

| 原报告判断 | 复核结果 | 证据与实际影响 |
|---|---|---|
| Netty 4.1.136.Final 存在 CRITICAL | 成立 | `build.gradle.kts:25` 固定该版本；`composeApp/gradle.lockfile:418` 属于 UTP 内部测试配置。OSV 重查命中 GHSA-c4c3-7fpv-j4q5。维护者公告给出的 4.1 修复版本为 4.1.137.Final。 |
| CRITICAL 是上次扫描之后才披露 | 时间表述不准确 | 维护者公告发布于 2026-08-07，GitHub Advisory Database 收录于 09-08。“上次扫描之后收录”比“新披露”准确。 |
| 不可信网络解析依赖即可触发该 Netty 漏洞 | 缺少依据 | 公告要求使用 SNI 选择 TLS 上下文、只在特定 SNI 上要求客户端证书、默认上下文宽松、没有额外证书校验。本项目未建立这样的可利用路径。应表述为工具依赖命中安全门禁，不能直接推断构建机或 APK 可远程利用。 |
| BouncyCastle 1.80 HIGH，覆盖未生效 | 不成立 | `scripts/security-overrides.properties:3` 起已覆盖相关模块至 1.84；`build.gradle.kts:40` 起使用覆盖，`:74` 起将覆盖模块排除旧锁约束；扫描器 `:40-84` 也应用覆盖。原报告自己的 SBOM 已记录 bcpg/bcpkix **1.84、无已知漏洞**，原日志只打印 Netty CRITICAL。本次重扫无 BouncyCastle 漏洞。尚未运行 Gradle dependencyInsight，不把源码配置冒充本次实际解析产物。 |
| 扫描 765 个“提交的锁文件依赖” | 范围有误 | `scripts/supply_chain_check.py:65` 使用 `root.rglob("gradle.lockfile")`，未按 Git 跟踪状态或模块过滤。原 SBOM 含 `.codex-calendar-optimization` 来源；与当前跟踪锁集合相比多 **20 个坐标**。这是真实的扫描可靠性缺陷。 |
| 看 APK 中仅两个 dex 和若干 so 即证明不含 Netty | 证据不足 | DEX 文件数量不能证明包内不存在某 Java 库。锁文件支持 Netty 仅在工具配置的判断；本次未做 APK 类清单或 R8 映射分析，不能背书原文的产物验证说法。 |

上游依据：[Netty 维护者公告](https://github.com/netty/netty/security/advisories/GHSA-c4c3-7fpv-j4q5)、[GitHub 收录日期与修复范围](https://github.com/advisories/GHSA-c4c3-7fpv-j4q5)。公告的严重度属于依赖漏洞评级，不等同于本项目运行环境的风险评级。

10 条重扫记录包括：logback-core 的 2 MEDIUM + 4 LOW，Netty 的 1 CRITICAL + 2 MEDIUM，OpenTelemetry 的 1 MEDIUM。原文 logback 的倍数与枚举数也不一致。完整记录见 `verified-osv-findings.json`。

Netty 应先刷新覆盖版本并同步锁，再复扫。单改 `secureNettyVersion` 不会自动使仅读取锁文件的扫描器看到新版本。BouncyCastle 无需再次“升级到 1.84”；旧锁条目可以整理，但不是证明覆盖失效的依据。原报告“最新版本”表本次未逐个重新查询 Maven 元数据，不据此批准任何跨版本升级。

## 安全与输入校验

| 原报告判断 | 复核结果 | 证据与修正 |
|---|---|---|
| 更新公钥为空时接受未签名清单 | 成立，但范围和影响均需修正 | `AppUpdateManager.kt:213-227` 确实接受；正式 `publish-android.yml:399-402` 在无 key 时同样 `exit 0`，且 `scripts/tests/test_update_manifest_key.py:74` 明确测试这个行为。不是只有本地包允许，也不是正式流程必然注入 key。 |
| 更新包信任只剩清单自述证书及 SHA-256 | 不成立 | `AppUpdateManager.kt:50` 默认清单使用 HTTPS；`:276` 起限制下载来源；`:1742` 调用 `apkMatchesInstalledUpdate`，`:2085-2102` 从**已安装应用**读取包名、实际版本和签名，与候选 APK 比较。公钥为空缺失的是独立清单认证，不能推导为任意 APK 可冒充安装。`:1711-1715` 还有无 key 接受日志，并非完全静默。 |
| 全局允许明文 HTTP | 配置成立，P1 定级缺少具体路径证据 | `network_security_config.xml:3` 全局允许。若向 HTTP 服务发送凭据，传输风险真实；但不能仅凭开关认定所有服务都在泄露。需要确认用户自建公网/局域网 HTTP 兼容需求后设计策略。 |
| XML 直接按 RFC1918 私网范围放开 HTTP | 建议不完整 | Android 官方配置提供域规则和子域匹配，并非 CIDR 私网策略。若按实际 IP/网段限制，需额外应用逻辑，并处理解析结果和重定向。也不能把私网 HTTP 当作具备加密保护。 |
| CredentialPersistingSettings 将凭据明文落盘 | 不成立 | 该类只控制 SharedPreferences 提交时机。`AndroidKeystoreSecureStore.kt:77-98` 先 AES-GCM 加密再写 Settings；`AppModule.kt:93-101` 为服务器会话注入 secure store，`:190` 为账户注入。引用的链路没有证明明文凭据问题。 |
| 本地签名材料存放于工作树 | 存在本地材料不等于泄漏 | 本次 `git ls-files signing keystore.properties` 无输出，忽略规则存在。未读取密码或密钥内容，也未重复全历史检查；无法独立背书“真实密码”“所有历史均无泄漏”等扩大结论。 |
| Application.kt:1222 缺 positionMs 即崩溃 | 不成立 | `Application.kt:1203` 起先执行 `WatchProtocol.isValidTimeline`；协议 `:243-254` 要求 positionMs、paused、rate 均非空并校验范围，失败先返回。其余 playlist 分支中的 8 个 `!!` 也有 entry/revision 前置校验。本文件 11 个断言不能直接算作 11 个输入漏洞。 |

HTTP 配置依据：[Android 官方 Network security configuration](https://developer.android.com/privacy-and-security/security-config)。本次未进行更新服务在线证书检查、篡改测试或设备安装测试。

## 性能、代码质量与测试

| 原报告判断 | 复核结果 | 证据与修正 |
|---|---|---|
| 四处 Channel.UNLIMITED | 成立，属于有条件的堆积风险 | Adaptive:128、Direct:119、Enhanced:94、Tunnel:74。发送端主要使用 trySend，消费者停滞且持续收命令时仍可累积。尚无真机峰值、OOM 或队列长度证据，不能等同已复现 P1 故障。 |
| 快速 seek 全部无限堆积、没有缓解 | 不准确 | Adaptive:435 的 `queuePendingSeek` 用原子标记合并待处理 seek；Direct:3020、Enhanced:1077 有消费端命令合并。后者不能在消费者停滞时限制入队内存，但能减少恢复后的重复工作。不能简单改成有界 Channel 后忽略 trySend 失败，否则可能丢失暂停、切片等命令。 |
| 三处 runLoop 是同逻辑拷贝 | 不成立为整体判断 | Adaptive 1540 行，管理子播放器探测、选择与降级；Enhanced 678 行，使用增强会话和代理；Tunnel 349 行，使用隧道会话。剔除空行、去掉首尾空白后，行级 SequenceMatcher 相似度约 6.4%、5.2%、26.0%；这是文本对比而非语义证明，但结合具体职责足以撤回“同一逻辑三份拷贝”。公共统计、命令及错误处理仍可局部复用。 |
| awaitIdle 是忙等导致中等性能问题 | 机制存在，严重度未证实 | `AndroidYCoreBlockCache.kt:320` 为 sleep(1ms) 的有界轮询，非纯自旋。生产调用在 `AndroidNextItemPreparation.kt:202`，预算最多 1000ms，经 `:118` 的 `runInterruptible(Dispatchers.IO)` 执行；并非证据所称“缓存写线程持续忙等”。可优化，但需频率与耗电数据。 |
| runBlocking 导致解码停顿 | 同步阻塞存在，是否缺陷待测 | 这些桥接点确实存在。同步 read 回调本就需等待数据；需要超时、取消、耗时和线程轨迹判断是否超预算。本次未把静态命中计作 ANR。 |
| 6 collect、0 collectLatest 意味冗余 seek 工作 | 计数成立，因果不成立 | 包含 `PlaybackRuntimeContent.kt:37` 的简单状态归约、设置开关、遥控命令、聊天滚动等不同职责。不是 6 个进度拖动耗时任务。`PlayerChromeRefined.kt:990` 的 animateTo 可以单独剖析，不能全部替换为取消旧任务。 |
| 冷启动构建完整 Koin 图是瓶颈 | 待性能测量 | YfuseApp 确实在 onCreate 注册定义，AppModule 使用 single 声明。没有证据证明注册时实例化所有对象或已超启动预算。已有 startupTrace 和延迟初始化；需实际冷启动数据。 |
| SharingStarted.Eagerly | 成立，低优先级 | `ServersTabComponent.kt:92` 存在。组件作用域有限，没有测得无观察者期间的昂贵工作。 |
| ktlint 基线 1155 个条目 | 成立，原 P1 过高 | 701 + 449 + 5。是基线记录数，不是本次执行 ktlint 后确认仍存活的违规数。`function-signature` 计数不能证明 API 语义缺陷，property-naming 也不能直接等同运行时错误。“会永久存在”不是必然结果。 |
| 超大文件 | 成立 | PlayerRoot 3452 行、Direct 3261 行等复现。应该作为维护性债务；“191 个长函数天然不可测试”不成立，长度不能证明可测性，也未提供 Kotlin 语法级统计方法。本次未复现该函数总数。 |
| 172 个宽泛 catch、9 个完全空 catch | 前者复现，后者错误 | 60 Exception + 112 Throwable。Direct:2179、2236 等捕获体执行 PCM 降级、重试及 seek，不为空；可以讨论缺失原始异常日志，但不是完全不处理。生产源码未找到纯空 catch；测试源码有 3 个空 InterruptedException catch。`catch (_: Exception)` 仅表示不使用异常变量。 |
| 119 个 !!、99 个 @Suppress 等 | 统计口径不清 | 本次 Git 跟踪 src Kotlin 的词法统计为 !! 120、@Suppress 77、runCatching 842；词法计数也可能含字符串或注释，不作为缺陷数。原报告应附命令及包含/排除规则。 |
| Kotlin 总量约 49000 行 | 错误 | 相同模块复算为 **1358 个文件、290362 物理行**（含空行、注释和测试）；原表分模块行数正确，总计不一致。不能把不同口径相加。 |
| TV 1 个测试文件、MDK 本模块无测试 | 基本成立 | TV、Compose、Server、Protocol 测试文件数与原表一致；mdkAndroid 本模块未发现测试文件。其他 native 目录存在测试，不能外推整个原生栈零测试。历史 2240 用例未重跑，测试数量也不是覆盖率。 |

本次运行已有 `scripts/test_supply_chain_check.py`：**10/10 通过**。未新增业务测试、未执行 Android 构建、Lint、完整 JVM 测试、宏基准或真机回归；未把历史测试结果称为本次通过。

## 工作区副本与其余边界

原文“三份完整副本、每份数百 MB，应列 P0 清理”不成立：

- `.worktrees/playback-perf` 是 `git worktree list --porcelain` 登记的正式工作树，分支 `codex/recommendation-refresh-20260908`，不是可以凭主仓未跟踪状态判断的垃圾目录。
- `.codex-calendar-optimization` 确实包含项目内容，但已被根 `.gitignore:75` 忽略。是否可删需独立核查该副本的改动及用途，本次不做删除判定。
- `.codex-tools` 本次是**空目录，0 个直接子项**，不是完整项目副本。
- `audit/` 中未跟踪的验证记录是本地工作状态，是否纳入版本控制取决于保留策略，不构成运行时缺陷，也不应因扫描脚本未过滤而先删除证据。

原报告关于“无泄漏”“无弱加密”“没有未认证状态变更路由”等全局否定断言，本次没有开展独立完整安全审计，不予扩大背书；原生实现、Harmony 编译、历史签名包及交付物 SBOM 仍属于未验证范围。模块边界和既有 CI 的存在，不等于当前全部门禁执行通过。

建议后续顺序：先更新 Netty 并修正扫描范围；再明确更新清单签名策略；针对命令堆积做压力验证并设计有序合并/容量策略；其后处理 HTTP 兼容策略、文件拆分、基线和测试补强。撤回 BouncyCastle 再升级、11 处断言漏洞修复、三份副本直接清理、全部 collect 替换这几项缺乏依据的动作。

证据文件：`verification-evidence.json`（范围、计数、20 个额外坐标）、`verified-osv-findings.json`（逐条漏洞）、`verified-sbom.spdx.json`（跟踪锁与覆盖后的快照）、`verify_report.py`（可复跑核查）。该 SBOM 仍是锁与覆盖配置快照，不冒充实际 APK 组件清单。
