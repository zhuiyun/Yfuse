# Yfuse 项目体检报告

后续修复：会话临时读取失败误删问题已修复，并完成 1.0.38 / 200 Full 签名打包；最新结果见 [会话恢复修复报告](../session-recovery-20260907/REPORT.md)。以下保留原体检时的发现与证据，更新公钥配置仍待补齐。

检查日期：2026-09-07。检查基线：`codex/merge-all-sign-20260907`，提交 `90b5caa0755a274fe8a051a1f1f9cf8c47c6018f`，加上本次工作区修复。

结论：手机、TV、协议与服务端共 **2,240 项单元测试通过**，手机及 TV 的完整 Android Lint 均为 **0 errors**。已修复构建和取消处理问题，但项目还不能据此认定“没有问题”：**已复现安全存储临时故障导致服务器会话永久丢失**，正式包的更新校验公钥也仍为空。

## 1. 优先处理的问题

### P1：临时安全存储读取失败会永久删除服务器会话——已复现，尚未修复

位置：`composeApp/src/commonMain/kotlin/com/yfuse/core/data/ServerRegistry.kt` 的 `hydratePersisted()`，尤其是失败处理和后续元数据压缩；Android 触发路径在 `AndroidKeystoreSecureStore.get()`。

当前代码没有区分“凭据缺失/认证损坏”和“Keystore 暂时不可用”。`SecureStoreException` 一样进入 `removeSecretBestEffort()`，删除加密凭据，再从持久化服务器清单中移除记录。后续安全存储恢复正常，重新构造 Registry 也无法恢复会话。

这是一条通过故障注入确认的新风险；尚无诊断证据表明它就是此前 13 点附近库页面加载缓慢的原因。

验证方法：使用合成服务器和内存设置保存一个会话，只让 `SecureStore.get()` 抛出临时异常，写入和删除仍可用。测试确认：重新加载后列表为空、加密凭据被删除、故障解除后仍为空。

证据：本目录 `ServerRegistryTransientReadAuditTest.kt` 和 `ServerRegistryTransientReadAuditTest.xml`。这是**证明当前错误行为的审计探针**，不是期望行为的回归测试；已从正式测试源集中移出，避免把有害行为固化为正确契约。测试没有读取或修改用户真实会话。

建议修复方式：区分暂时读取失败与确定损坏；暂时失败必须保留凭据、元数据和关联缓存；提供可重试的加载状态，并防止同步把“尚未恢复”误当成空服务器清单。单纯吞掉异常或返回空列表会继续造成数据误删；直接在依赖注入初始化时抛出异常又可能阻止应用启动，因此本次没有做这种不完整替换。

### P1：正式包更新公钥为空——配置待补齐

位置：`gradle.properties:28`、`composeApp/build.gradle.kts` 的 `updateManifestPublicKey`、`AppUpdateManager.kt` 的 manifest 校验配置。

已确认当前生成的 Release `BuildConfig.UPDATE_MANIFEST_PUBLIC_KEY` 是空字符串。正式构建会拒绝无法按固定公钥验证的更新 manifest；发布流程也明确阻止在未固定公钥时发布。原有 1.0.37/199 签名 APK 因此不能视为已经具备可用的应用内更新链路。

本次修复了代码层的环境变量兜底，但没有编造或替换发布公钥。仍需配置与实际 manifest 签名私钥匹配的 Ed25519 公钥，并联调签名 manifest 的发布与客户端下载验证。APK 签名证书和 manifest 签名公钥是两个不同用途的配置。

### P2：本地原生依赖与源码的可复现构建仍依赖补充步骤

此次 Full 验证使用此前从当前源码重建、并验证 JNI 契约的载体 `build/native-20260907/libmpv-release.aar`，SHA-256 为 `45056f466dd7cae2fc87b4c87c34a1d548e4ebf4aab041064b793c1783486204`。本地常规 `composeApp/libs` 中原有载体版本较旧，不能把其文件存在和哈希通过等同于与当前 JNI 接口一致。

验证脚本临时替换载体及其校验记录，结束后恢复原文件。完整新机器构建还需要执行对应的原生重建/下载步骤；本次没有证明一个不带这些步骤的全新环境能直接生成相同产物。应持续把源码版本、JNI 签名和载体来源绑定在同一个构建入口中。

## 2. 本次已经修复的项目

| 项目 | 原来的问题 | 本次结果 |
|---|---|---|
| TV 构建配置 | 共享手机更新代码引用了不存在的 `UPDATE_MANIFEST_PUBLIC_KEY` | TV 补齐字段，编译与 51 项测试通过 |
| 更新公钥读取 | 空 Gradle 属性遮住 `YFUSE_UPDATE_MANIFEST_PUBLIC_KEY` 环境变量 | 手机与 TV 都先过滤空属性，再读取环境变量 |
| 字幕 Compose 检查 | Lint 将跨平台字幕索引/列表推断为 Unit，阻断完整检查 | 添加显式类型，保留 `remember` 和全部检查规则，手机/TV Lint 错误清零 |
| TV 通知配置 | 共享通知代码缺少清单权限声明 | 补齐 `POST_NOTIFICATIONS`；没有新增自动权限弹窗 |
| TV 资源版本标注 | 导航栏属性没有标注 API 27，刘海属性错误标为 API 27 | 标注为 API 27 / API 28；低版本忽略不支持的静态资源属性 |
| 日历取消处理 | 父协程超时被转换为日历加载失败，取消后可能继续投递结果 | 使用仅处理自身超时的 `withTimeoutOrNull`；补充父超时和显式取消测试 |
| TV Gradle 格式 | 声明间隔及调用链断行违反 ktlint | 手工修复，完整 ktlintCheck 通过，未重置基线 |

修改在工作区，未推送、未发布。未改动现有用户未跟踪目录，也未改写发布版本文件。

## 3. 验证覆盖与结果

| 检查 | 结果 |
|---|---|
| 手机 common / Android 单元测试 | 2,006 项通过，0 失败、0 跳过 |
| TV 单元测试 | 51 项通过，0 失败、0 跳过 |
| WatchTogether 协议 | 8 项通过 |
| WatchTogether 服务端 | 175 项通过 |
| 手机完整 lintDebug | 0 错误，7 警告 |
| TV 完整 lintDebug | 0 错误，30 警告；包含共享手机代码/资源的警告 |
| 全项目 ktlintCheck | 通过，沿用既有基线；不代表历史格式债务为零 |
| verifyDesignSystemUsage | 通过 |
| Macrobenchmark APK 编译 | 通过；未运行启动/帧时间测量 |
| 供应链脚本单元测试 | 10 项通过 |
| TV 产物校验脚本单元测试 | 5 项通过 |
| TV Manifest / 320×180 banner 校验 | 通过 |
| Cast Receiver JavaScript | Node 语法检查通过，未连接真实 Cast 接收端联调 |
| HDR shader 校准脚本 | 通过 |
| 原生 ARM64 算法/ABI 测试 | tone-map、overlay-plane、GPU capability、disc URI、YCore ABI 共 5 组通过 |
| Harmony 可移植代码补充验证 | 带 HUKS shim 的基础测试通过，9 个要求的共享库符号均导出 |
| Harmony 结构与契约 | 48 个仓颉文件结构、平台契约、脚手架、provider fixtures/media matrix 通过 |

原生测试使用 Android NDK 29 / API 26 编译，在已连接的三星 SM-G973U ARM64 设备执行。Harmony 的补充运行验证针对可移植代码和 shim，**不是 HarmonyOS 的 HUKS 硬件实现测试**。原 `verify-harmony-port.py` 在“没有主机 C++ 编译器”处停止，之后使用 Android 交叉编译补充了上述检查；不把它记为完整 Harmony SDK 构建通过。

已有警告经过抽查：Cast 静态 Context 的实际初始化传入 `applicationContext`，目前没有证据判定 Activity 泄漏；精确闹钟入口在旧系统的健康状态下不可点击；HDR10+ 检查使用内联字符串常量；另有 Android 16 固定方向提示、冗余 SDK 判断和 TV 未使用资源。保留原始 Lint 报告，不通过批量 suppress 隐藏警告。

证据文件：`test-summary.json`、`gradle-verification.log`、`phone-lint.txt`、`tv-lint.txt`。Release 和公钥配置复验结果见下方补充。

## 4. 依赖与供应链

OSV 查询了当前 Git 跟踪模块锁文件中的 **747 个 Maven 依赖版本**，应用了仓库现有 BouncyCastle 安全覆盖配置；扫描输入没有包含未跟踪的旧项目副本。

结果：**0 条高危/严重记录，4 条中危、4 条低危**，分布在 3 个依赖中。没有把“扫描门禁通过”写成“没有漏洞”。

| 依赖 | 记录 | 当前锁文件中的用途 |
|---|---|---|
| logback-core 1.3.14 | 2 中危、4 低危 | `ktlint` 工具配置 |
| netty-codec-http 4.1.136.Final | 1 中危 | Android unified test platform 内部工具配置 |
| opentelemetry-api 1.41.0 | 1 中危 | `swiftExportClasspathResolvable` 工具配置 |

完整编号、说明及 OSV 链接见 `osv-findings.json`。这几项尚未升级；应结合工具链兼容性安排修补。依赖锁文件覆盖构建工具，不能直接当作 APK 运行时依赖清单。此扫描也不涵盖所有 Gradle 插件、GitHub Actions、预编译原生播放器或设备厂商组件。

本目录 `sbom.spdx.json` 是现有脚本生成的依赖锁快照，包含工具依赖，**不是从正式 APK 提取的精确组件清单**。

## 5. 仍需要运行验证的内容

- 在问题发生的 OPPO 设备上回归：13 点附近冷启动/后台恢复、慢服务器/断网后恢复、HTTP 添加与同步。
- 连续进入追剧详情并返回，观察图片恢复；检查深色和浅色弹窗的展开/收起、快速重复点击与减少动态效果设置。
- Full 各内核的真实长片播放、硬软解切换、HDR/杜比、字幕和音轨切换、后台播放及内存/温度。基础算法测试与打包存在性不等于这些场景通过。
- 真实 Android TV 遥控焦点、返回、播放和 Cast Connect 联调。
- HarmonyOS SDK 完整编译、真机安装与系统 API 测试。

本次没有覆盖安装用户手机上的应用、没有清除应用数据，也没有把旧 APK 的签名结果当成本次全部修改的发布验证。体检最优先的后续工作是修复会话恢复的数据丢失路径，并补齐真实更新公钥及端到端更新验证。
## 6. 最终 Release 和配置验证

- 手机 Full 与 TV 默认系统内核版的 `assembleRelease` / R8 均通过，构建结果为 `BUILD SUCCESSFUL`。
- 两个 APK 均通过 `apksigner verify`，APK v2 签名有效，证书 SHA-256 均为 `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`。
- 手机 Full 包为 27,838,818 字节，包含 20 个 ARM64 原生库，确认 YCore demux/GPU、MPV、MDK/JNI、FFmpeg 和 dav1d 均在包内。SHA-256：`1042c2d2a78cfafc3c267421f6323e2c441ff7344e981684e922b3a6905b7966`。
- TV 包为 6,591,354 字节，通过产物级 launcher、TV 特性、banner、权限、ABI 和 16 KiB 对齐校验。该版本使用系统播放内核，4 个原生文件是 AndroidX graphics path 的四种 ABI 实现，未混入 MPV/MDK。SHA-256：`02a4afd88630308df64245c296a9b35256b0e0c48a052e0e2f0541a2592d554f`。
- 公钥配置三步验证通过：空 Gradle 属性回退到环境变量；非空 Gradle 属性优先；验证结束恢复原有空公钥配置。仅生成 BuildConfig 验证，测试占位值没有进入上述 Release APK。
- 本次构建复用测试参数 1.0.37 / 199 来验证打包路径，没有创建或发布新版本，也未替换 `artifacts` 中此前交付的 APK。正式分发本次修复前，仍应按发布流程递增版本并处理上述 P1 项。

补充证据：`release-verification.log`、`release-artifacts.json`、`phone-apk-signature.txt`、`tv-apk-signature.txt`、`public-key-verification.log`。工作区原生载体及校验文件已经恢复；最终 Git diff 不包含临时原生校验值修改。
