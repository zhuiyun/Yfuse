# 播放与文件来源的源码归属

本文件记录 2026-11-05 扩展的边界。Android 手机和 TV 的共享源码仍以
`composeApp/src/commonMain` 与 `composeApp/src/androidMain` 为唯一编辑位置；
`phoneShared`、`tvShared` 引用这些目录，不能各复制一份实现。Harmony 的仓颉实现独立维护，
Android 编译通过不能作为 Harmony 验证证据。

| 归属 | 负责 | 不负责 |
| --- | --- | --- |
| `core/filesource` | 来源身份、修订号、加密凭据访问、有限目录扫描与检查点、浏览和移动契约 | 播放器状态、全局设置、云盘协议细节 |
| `core/cloud` | 123/百度开放平台协议、授权和旋转令牌、文件身份与直链解析 | 把 ClientSecret 或 RefreshToken 交给解码器 |
| `core/iptv` | M3U 解析、订阅与收藏持久化、失败保留已成功内容 | 实现视频解码或承诺频道可用 |
| `core/subtitletranslation` | 加密接口配置、有界分批请求、条目身份校验、保持字幕时间轴 | 修改原字幕、推断模型正确率、后台自动发送字幕 |
| `core2/api` | 多后端共享播放状态与有来源的缓存区间 | 用推测值补齐内核未知能力 |
| `feature/player` 的 preview 实现 | 独立抽帧任务、读取预算、取消和旧请求淘汰 | 复用或 seek 正在播放的解码器 |
| `senplayerBridge` | 原 APK 的未修改 DEX/JNI、隔离类加载、校验和与原始许可链 | 修改原许可、复制账号 TLS helper、声称原生实现可维护 |
| `feature/*` | 用户交互、生命周期、结果展示 | 内嵌供应商 API 或保存明文密钥 |

## 生命周期约束

- 文件来源变更必须更新 revision；检查点和异步结果必须匹配来源及 revision。
- 切换影片、媒体版本或离开页面必须取消字幕导入、翻译、预览等独立任务。
- 暂时失败的目录、订阅刷新或翻译结果不得覆盖上次成功数据。
- 所有缓存区间必须标明后端能确认的范围；前向缓冲终点不等于从头到该点全部缓存。
- 云盘旋转令牌加密存储，并绑定来源和原始凭据身份；删除来源时清除对应副本。
- 新增状态先放入负责其生命周期的模型/服务，再由页面组合。不要继续把网络协议堆进
  `PlayerRoot`、`ProfileScreen` 或 `FileSourcesController`。

## 验证入口

Android 使用现有 `verifyCoreLayering`、`verifySourceSize`、`verifyServiceLocator` 与手机/TV
编译、HostTest 检查。性能采样由 `scripts/media-performance-evidence.py` 记录样片/APK 哈希、
设备指纹、网络/缓存/字幕条件和原始 meminfo；它不会将 UI 帧率冒充视频帧率。
Harmony 使用 `scripts/verify-harmony-port.py`；HAP 编译和设备证据仍独立验收。

本次新增服务已按上述边界拆分。历史大型协调文件尚不能仅凭新增模块就认定全部重构完成；
后续拆分需保留会话、取消、进度报告和自动连播契约。
