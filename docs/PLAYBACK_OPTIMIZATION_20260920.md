# 播放链路优化与日志说明（2026-09-20）

本轮落实 `PLAYBACK_CHAIN_REVIEW_20260920.md` 的 A–F 六项改动。优化目标是缩短点击播放到真实音视频输出的等待；实际收益须根据同设备、同来源的日志比较，当前没有实机耗时结论。

## 已实现

| 环节 | 改动 | 边界 |
| --- | --- | --- |
| A：播放元数据 | 详情页与播放共用短期快照，并合并并发读取；Plex 协商复用原始元数据；冷启动仅请求播放需要的 Emby/Jellyfin 字段 | 15 秒、最多 16 项，按完整服务器/鉴权上下文和条目隔离；显式刷新重新读取；不复用 PlaybackInfo 会话 |
| B：当前影片预热 | 详情选择稳定 500 ms 后，在 YCore 实际缓存中预读文件头、尾索引；保留平台探测器并定位续播关键帧，正式播放可以一次性接管 | 只在允许预加载的直连来源、非计费网络、非省电状态启用；文件头目标上限 2 MiB，尾索引目标 512 KiB；实际传输按缓存块对齐；不启动转码或解码器 |
| C：探测结果共享 | 原生播放器界面直接读取当前内核的探测事实，避免另开 MediaExtractor/MPV 重复读同一影片 | 核对服务器、条目、URL、User-Agent 和播放会话；兼容内核的可选探测等待真实输出且有至少 8 秒缓冲（按倍速折算）；错误时可立即诊断 |
| D：FFmpeg 起播分析 | 新增独立 startupAnalysis JNI 入口；允许保留给播放的源使用较短分析预算，仍保留分析阶段读到的包 | 普通 MP4/MKV、AVC/HEVC、已知音轨可用；杜比/HDR/光盘/DRM 等不走此快速策略；轨道缺失或音视频参数不完整时重新完整分析 |
| E：HTTP 连接复用 | Exo、兼容预加载、兼容代理接入与 API/YCore 相同的 OkHttp 连接池；代理保留 Cookie、重定向、Range 和取消行为 | 保持平台 TLS 验证；连接能否实际复用仍取决于来源、服务器及连接状态 |
| F：请求和探测调度 | 当前 PlaybackInfo 优先；播放启动期间，剧集信息、剧集列表和备用服务器请求等到首个音视频输出或启动错误后再开始 | 无输出时最多等 30 秒，防止页面未挂载造成永久等待；平台探测最多 8 秒、增强探测最多 18 秒，并为解码器验证预留至少 2 秒；整体仍受原有 30 秒预算约束 |

预热取消后不等待完成。已完成的资源最多保留 30 秒，仅在来源、鉴权、源提示和续播位置匹配时接管；换片、换鉴权或未接管的资源会释放。失败和未完成的预热不阻止正常播放。

## 日志

日志沿用项目 `AppLog`，随现有诊断日志导出。新增日志不记录 URL、访问令牌、Cookie 或鉴权头。

### 点击到输出

事件 `playback_launch_stage`：

- `launchId`：一次播放请求的随机关联标识。
- `stage`：`play_requested`、`detail_selection_ready`、`prepared_store_claimed` / `store_created`、`item_detail_ready`、`playback_info_ready`、`current_item_ready`、`first_video_output`、`first_audio_output`、`startup_error`。
- `tapElapsedMs`：从详情页播放请求开始的单调时钟耗时。其它入口从 `player_requested` 开始，不能将其误认为详情页点击耗时。
- `background_requests_released` / `background_priority_deadline`：可选请求获得执行机会的原因。

提前完成的预加载阶段不会伪造点击后的耗时。重复播放创建独立标识；只有对应播放会话的输出才能结束该次等待。每个阶段只记录一次。

原有 `playback_preparation_stage`、`playback_startup_stage` 和 YCore 分阶段计时继续保留。输出日志新增 `generationElapsedMs`，按条目、输出代次及会话修订重新计时，便于区分首次输出和后续恢复。

### 复用与回退

| 事件 | 用途 |
| --- | --- |
| `playback_metadata_reused` | 确认命中元数据快照或共享读取 |
| `current_item_prepared` | 当前影片源和起始位置已准备好 |
| `current_item_preparation_reused` | 播放成功接管预热资源 |
| `current_item_preparation_skipped` | 可选预热未完成，仅记录异常类型 |
| `metadata_probe_budget` | 记录 platform/enhanced 阶段的耗时、上限、预留时间及结束原因 |
| `demux_analysis_expanded` | 短分析结果不完整，回退完整分析 |

排查时先比较同一 `launchId` 的输出耗时，再看协商、探测和解码阶段；不要将预加载命中与冷启动样本直接混合比较，也不要把收到解码回调当作真实首帧。

## 验证范围

本轮只做本地构建、主机自动化测试、原生库重建及静态校验。遵照用户要求，不继续操作手机、不安装测试包或应用包。具体结果记录在 `audit/playback-optimization-20260920`，最终汇总见该目录的 `verification.json`。

新增回归覆盖元数据并发、取消、过期、鉴权隔离、缓存淘汰，探测分段超时和晚到资源拒收，短分析回退条件，探测事实绑定，首帧前的可选请求调度，以及代理的 Cookie/Range/重定向与连接复用。既有范围缓存接管、预热取消、准备资源过期和播放器恢复用例一并运行。

最终主机回归：手机端 2,775 项、电视端 63 项，共 2,838 项通过，无失败或跳过；手机与电视应用编译通过。测试二进制结果保存在上述目录的 `isolated-results`，从这些结果生成的可浏览报告在 `unit-reports/phoneShared/index.html` 与 `unit-reports/tvShared/index.html`。本轮测试期间未检测到源码变化。

手机与电视 `lintDebug`、设计规范检查以及本轮 41 个 Kotlin/Gradle Kotlin 文件的格式检查通过。原生库按当前 C++ 源码重建，验证 JNI 入口、无未定义链接符号、16 KiB 对齐、共享依赖字节一致与构建来源记录；未做设备运行验证。

本次不构建交付 APK，不递增交付版本，也不推送代码。
