# YCore 播放审查修复记录（2026-09-12）

基线：`master@52dba956bce04a6994f38452baed492c32b1572f`。
工作分支：`fix/ycore-audit-complete`。

本次落实审查中可复现或可由调用链确认的缺陷修复。HTTP、HTTP 许可证和已有
HTTPS → HTTP 播放重定向能力继续保留；没有新增 HTTPS 强制要求。
跨源子资源仅去除源站凭据，普通请求头与同源 HTTP 凭据继续正常发送。

## 源码变更

| 审查项 | 实际修改 | 验证边界 |
| --- | --- | --- |
| 1. AudioTrack 时钟与释放竞争 | PCM/encoded 节点的生命周期、时钟与状态读取使用同一实例锁；播放头和 underrun 读取容错 | Kotlin 类型检查；仍需真机切轨/换集/路由压力测试 |
| 2. 回落 PCM 后旧样本回队 | 预读样本携带队列 generation，seek/select/close 后拒收旧 generation | 实际预读节点 fake extractor 回归 |
| 3. Enhanced 切轨前跳 | 切换前保存播放位置，选轨后统一 seek/flush/reset | 类型检查与切轨恢复回归；完整会话需真机 |
| 4. 磁盘缓存旧内容 | 使用新远程范围请求验证强 ETag 和长度；无强 ETag 时不跨打开复用；失效 epoch 阻止旧实例/排队写入回填；长度变化抛可识别的 IO 错误 | 块缓存及真实 MediaDataSource + fake transport 回归，含同长度换内容和长度变化 |
| 5. 零时长清单 | DASH 按数值识别零时长，允许零 Period start/update/delay；HLS 零占位段保留为最小 1 µs 时长 | 解析器回归；HTTP 清单用例 |
| 6. Direct Ended 后播放 | 重新定位到 0 后启动 | 类型检查；真实 codec 会话重播待真机 |
| 7. 终态路由重建/重复下一集 | 最终失败或队尾结束清除播放意图；终态忽略音频路由/热压力重建；同次 EOS 只入队一次自动下一集 | 类型检查；系统事件集成待真机 |
| 8. Enhanced 截断尾音 | 解码结束后继续等待 PCM sink 排空；2 秒停滞保护避免永不结束 | EOS/Surface 完成策略回归；AudioTrack 排空待真机 |
| 9. 选中不可播音轨导致整段失败 | Direct/Enhanced 切轨失败重建上一个音轨并恢复原位置；仅回滚也失败时才继续向上报错 | 回滚与双失败异常链回归；实际设备无 DTS 用例待真机 |
| 10. encoded 时钟停滞 | 复用音频时钟进度守卫；时间戳/播放头都停滞后返回空时钟，由会话墙钟接管 | 音频时钟进度守卫回归 |
| 11. Cronet close 不能唤醒读 | 每次 open 独立 Exchange/可取消队列，close 唤醒 poll 与背压 put；旧回调不会写入新请求队列 | 阻塞读、阻塞写和旧队列隔离回归；Cronet 设备栈待真机 |
| 12. 代理污染 body/重选码率 | 记录已开始响应的 socket，断流后直接关闭；相同活动资源 URL 复用首次解析出的 ABR 资源 | 真 TCP socket + fake upstream 验证部分 body 无追加 502；ABR 型号切换待媒体套件 |
| 13. 暂停预览无 Surface 仍读 | Direct 和 Enhanced 暂停预览在 Surface 不可用时停止 pump 消费 | 类型检查；Surface 生命周期待真机 |
| 14. OEM 修改时间戳被硬失败 | 首次隔离时间戳映射失败后重建硬解并用原媒体 PTS 重试；不复用旧 decoder；兼容模式 seek 时重建硬解隔离旧回调 | 类型检查；目标 OEM 硬解待真机 |
| 15. 子资源凭据 | 清单派生 URL 保留凭据来源，实际请求按源范围过滤敏感头/账号；未显式配置许可证 URI 时不向 PSSH URL 继承凭据 | 同源 HTTP、跨源 CDN、默认许可证头策略回归 |
| 16. 请求行/路由资源 | 请求行与头部逐字符限制分配；路由达到容量时淘汰旧资源并清关联解析记录；退役播放目标同时清理解析记录 | 超长输入分配上限回归；长直播待真机 |
| 17. Native 生命周期 | Tunnel 使用独立阻塞工作线程并随 worker 释放；蓝光打开/标题扫描检查取消、取消传到 Kotlin 关闭远程 IO；JNI 回调加入 R8 保留规则；Vulkan 获取 image 后失败统一退役 swapchain 并重建同步对象 | 远程光盘取消回归；JNI/Vulkan 修改已完成 NDK 构建，设备验证待完成 |

源站更换内容时，本次打开会中止不一致的范围读取；下次打开重新验证内容，避免将旧文件
与新文件拼接。代理的资源容量和请求行长度约束是内存生命周期处理，不区分 HTTP/HTTPS。

## 验证

- 使用 Kotlin 2.2.21 编译实际 `core2` common/Android 播放源码，Android API 使用 API 36
  类库；与本次逻辑无关的 AppLog、网络环境、遗留工厂和部分光盘 UI 依赖使用边界替身。
  这不是整个 APK 的 Gradle 构建。光盘 source wrapper 本身不在这次整体编译集合中。
- 128 项 JVM 回归通过，包含新增 `PlaybackAuditRegressionTest`、
  `PlaybackTransportAuditTest`、实际预读节点、磁盘缓存、远程随机读、光盘块读、
  HTTP/重定向、代理、音轨选择、EOS/Surface 完成策略测试。
  JVM 运行时的 `MediaDataSource` 基类与 `Looper` 使用 API 替身；实际 transport/cache/
  prefetch 实现参与测试。该结果不能当作 MediaCodec、AudioTrack 或 Cronet 真机证据。
- 修改的 Kotlin 文件通过 ktlint 格式检查，`git diff --check` 无空白错误。
- 检查运行 `34685607802` 已成功完成当前 JNI/GPU 的 NDK 构建及验证，包括冷缓存依赖构建。
  完整 Android 编译、单元测试和 lint 由后续检查运行验证；R8 打包及正式签名仍暂停。
  媒体真机套件不能由编译结果代替。

## 文档与能力状态

已修正 `YCORE2_ARCHITECTURE.md` 关于默认引擎的旧叙述；将其迁移路线明确标为历史。
`UHD_BLURAY_PLAYBACK.md` 不再同时声称“未产出验证 AAR”和“发布门禁已通过”，状态必须
对应具体制品校验记录。两份 8 月缺口文档保留历史身份，并链接本记录。

本次没有伪造或放宽 FEL 合成、TrueHD Atmos 输出、光盘 corpus、4 台设备/3 芯片族、
1000 次 seek/Surface、8/24 小时长稳等物理证据。`NotMeasured` 不能由源码修复变成 READY。

原审查的“超过 8 声道一律不出声”和“核心播放器完全没有实例化测试”不应当作准确现状：
代码已有条件化多声道映射及 instrumented 播放套件；需要补的是完整硬件/状态机场景证据。
纯音频 Enhanced、软件高位深渲染/拷贝成本、offload、夜间动态压缩、直播时移及服务器音频
重协商属于独立能力或架构工作，本次缺陷修复不宣称已实现。旧文档中的同名缺口也不代表
native-only 制品的实时能力，应按当前路由与设备逐项验证。

## 打包暂停后的追加检查

用户要求先检查项目再打包。签名运行 `34684242349` 已取消；检查运行
`34684713420` 只编译、测试和 lint，不生成或签署 APK。

- 缓存元数据持久化失败曾直接中断播放。校验失败现在停用该实例的磁盘缓存并继续网络读取；
  旧块删除失败时不能确认新 epoch，避免将未清理的数据当作新内容。新增不可写缓存路径回归。
- PCM/encoded 共用的备用时钟没有扩展 AudioTrack 的 32 位播放头。现已处理计数回绕，
  configure/flush/release 清除扩展状态；新增跨回绕、暂停及重置测试。
  平台计数语义见 [Android AudioTrack API](https://developer.android.com/reference/android/media/AudioTrack#getPlaybackHeadPosition())。
- 实际 1.0.58 (220) APK 为 30,522,248 字节，超过现有 30,000,000 字节门禁。
  当前仅使用 BC 的轻量 Ed25519 验签，排除未使用的 legacy Picnic 查表资源；不提高体积预算。
  使用实际 Android 验签源码和 BC 1.84、去掉这些资源并禁用 JDK Ed25519 提供者后，
  已验证 bundled 回退接受有效签名，拒绝改动后的数据、签名和错误公钥。
  此 JVM 检查仅将 Android Base64 API 替换为等效 JDK Base64。
- 默认 Auto 启用 Core2 不等于 native-only APK。当前 Gradle 默认及已核验的 220 包均为
  full profile，文档已依据当前配置和制品更正。
- 发布脚本测试 37 项、构建/模块契约测试 16 项通过；手机/TV 模块边界及 TV 源清单检查通过。
  完整 Gradle、NDK 和 lint 以检查运行的最终结果为准，不能以局部 JVM 检查代替。

- 供应链扫描曾漏掉被 Gradle 从 lockfile 排除的安全覆盖依赖（包括实际使用的 BC provider），
  并将缺失/截断的 OSV 批量响应当作无漏洞。现扫描覆盖清单中的固定版本，响应不完整时停止
  并报告扫描未完成；3 项回归覆盖依赖清单和响应映射。批量响应约定见
  [OSV API 文档](https://google.github.io/osv.dev/post-v1-querybatch/)。

- 在线扫描了 746 个 Maven 依赖，初次结果有 1 个阻塞项：测试工具链的
  `netty-handler:4.1.136.Final` 命中 GHSA-c4c3-7fpv-j4q5。已将统一解析版本和三个
  模块锁文件更新到 [官方修复版本 4.1.137.Final](https://github.com/netty/netty/security/advisories/GHSA-c4c3-7fpv-j4q5)。
  这些 Netty 条目属于 AGP 的测试宿主配置；一起看服务使用 CIO，不据此声称线上服务存在
  同样暴露。后续复扫结果见下文，完整 Gradle 验证单独记录。

- 首次完整检查运行失败于冷缓存 MPV facade 构建：Java target 21 / Kotlin target 17。
  公共 native composite action 现在为原生依赖设置 Java 21，并在结束后恢复调用方 JAVA_HOME。
  该运行的格式/设计契约已通过，lint 因上游 AAR 未生成而失败；不将其记作通过的完整检查。

- 后续复扫的 7 条低/中等级记录来自 ktlint 的 Logback 及 Kotlin Swift 导出工具的 OpenTelemetry。
  固定 Logback 1.5.38、OpenTelemetry API/context 1.62.0 及配套 SLF4J 2.0.17 后，
  2026-09-12 的在线扫描结果为 745 个依赖、0 条活动漏洞记录。参考
  [Logback 发行记录](https://logback.qos.ch/news.html) 和
  [OpenTelemetry 公告](https://github.com/open-telemetry/opentelemetry-java/security/advisories/GHSA-rcgg-9c38-7xpx)。
- 音频回绕处理同时覆盖可能回绕的 AudioTimestamp，并在时间戳首次出现时对齐播放头周期。
  回绕、缺失时间戳后恢复、暂停及 flush/reset 回归通过。
- 检查模式保留签名工作流的配置，只禁用 native/sign 两个出包任务；
  原来直接替换签名工作流导致配置契约测试失败，已更正并验证其 8 项原有测试通过。
  TV 检查在本次签名分支只编译和测试，不产生附带 APK。

- 检查提交 `0fc7188b8c69ac2c41864b367f609bd3f65fe61b` 的独立检查任务
  `103533208871` 已通过：协议/服务端 26 个测试套件共 183 项测试，0 失败、0 错误；
  37 + 16 项 Python 脚本测试通过；在线依赖扫描为 745 项、0 条活动漏洞记录。
  TV 检查运行 `34686076101` 也已通过。本轮 native/sign 出包任务均为 skipped。

- 本轮完整 Android 源码编译通过，2523 项应用测试中有 3 项失败，lint 报 1 错误、9 警告，
  因此未恢复签名。两项 ABR 测试暴露了固定资源重连后跳过重开评估的回归：现将重开计划
  评估与已固定的媒体字节分离，重复 URL 仍可使用新吞吐/缓冲反馈，资源本身不改码率。
  下一集缓存测试更新为带强 ETag 的源；新 reader 必须只读取 128 KiB 校验范围，三处
  预热播放范围仍从磁盘读取。原有“完全没有源站读取”的断言不符合新的缓存有效性契约。
  启动动画的粒子预算改为显式类型的 remembered 局部变量，避免 infix provider 表达式的
  `RememberReturnType` 错误；不添加 lint 抑制或提高门禁阈值。

- 追加的 3 个失败场景及相关播放回归在本地重新编译后全部通过（共 128 项）。完整 CI 重跑待完成。

- 检查运行 `34687116398` 已通过全部测试：应用 2523 项、TV 59 项、协议 8 项、服务端 175 项，均无失败或跳过。格式/设计检查通过。lint 仍报粒子预算的同一错误；显式局部变量未消除该检查器的类型推断差异，进一步改为显式泛型和构造器引用，再验证 lint。
