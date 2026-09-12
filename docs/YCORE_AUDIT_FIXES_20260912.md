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
| 17. Native 生命周期 | Tunnel 使用独立阻塞工作线程并随 worker 释放；蓝光打开/标题扫描检查取消、取消传到 Kotlin 关闭远程 IO；JNI 回调加入 R8 保留规则；Vulkan 获取 image 后失败统一退役 swapchain 并重建同步对象 | 远程光盘取消回归；JNI/Vulkan 修改尚未完成 NDK 构建与设备验证 |

源站更换内容时，本次打开会中止不一致的范围读取；下次打开重新验证内容，避免将旧文件
与新文件拼接。代理的资源容量和请求行长度约束是内存生命周期处理，不区分 HTTP/HTTPS。

## 验证

- 使用 Kotlin 2.2.21 编译实际 `core2` common/Android 播放源码，Android API 使用 API 36
  类库；与本次逻辑无关的 AppLog、网络环境、遗留工厂和部分光盘 UI 依赖使用边界替身。
  这不是整个 APK 的 Gradle 构建。光盘 source wrapper 本身不在这次整体编译集合中。
- 117 项 JVM 回归通过，包含新增 `PlaybackAuditRegressionTest`、
  `PlaybackTransportAuditTest`、实际预读节点、磁盘缓存、远程随机读、光盘块读、
  HTTP/重定向、代理、音轨选择、EOS/Surface 完成策略测试。
  JVM 运行时的 `MediaDataSource` 基类与 `Looper` 使用 API 替身；实际 transport/cache/
  prefetch 实现参与测试。该结果不能当作 MediaCodec、AudioTrack 或 Cronet 真机证据。
- 修改的 Kotlin 文件通过 ktlint 格式检查，`git diff --check` 无空白错误。
- 当前环境未完成 Gradle/AGP、NDK/FFmpeg/libbluray/Vulkan 完整构建、R8 APK 打包或签名验证。
  最终集成仍应运行 Android unit tests、release 编译和媒体真机套件。

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
