# YCore / SenPlayer 合并说明（2026-11-06）

本轮基于正式主线 `8c958457bdb40190bde9fb4fc8fab79193cb29c6`，纳入远端 CI 修复 `3df0447b7b65a2e6c80f76db760ed93ca3e89b7d`、视频输出修复 `9be95151403d13f811b4d35733ec2dbe45f840c1` 和实验分支 `4c38e8cfb9020321841af892956ba2cce9c32c24`。前两个提交通过快进纳入，实验分支通过保留双亲的合并收敛。以当前用户要求为准：仅保留 YCore 自研内核与 SenPlayer。

## 合并取舍

- 保留正式 `senplayerBridge` 原始五项资产、隔离加载和原始许可流程，保留当前 Surface/会话隔离、真实输出证据和封面处理。实验分支重复的 `senPlayerAndroid` 不进入项目。
- 多段缓冲继续采用正式 `YBufferSnapshot` 的 session/generation/coverage 契约，保留空档和未知覆盖，整合独立 seek rail 绘制函数。未使用实验分支的替代 nullable 区间契约。
- 导入器在写入前对整包 SHA、五项资产、重复/越界路径与 ELF 做完整检查，保留可复现测试。
- 删除独立 ExoPlayer、MPV、MDK 实现、模块与内部兼容回退。共享 Media3 common/database/datasource 是非播放器基础组件；YCore 使用独立原生 AAR。SenPlayer 原始资产内部的实现未经修改，不将其误计为宿主独立内核。
- 旧 Exo/MPV/MDK 保存偏好迁移为 YCore；Auto 仅启用 YCore。SenPlayer 必须明确选择。依赖 YCore 凭据传输的队列在 SenPlayer 下明确报告不支持，不暗换内核。
- 保留 YCore 原生平台 DRM、真实 libbluray/VFS、HTTP、FTP/云盘、字幕与缓存路径。TV 静音预告片使用 NativeDirect，在建立音频解码器前禁用音轨。
- 当前管线不支持的控制项禁用或恢复默认；恢复限定于当前内核的有界重试。服务器转码须满足服务器批准、媒体许可与用户选择条件。
- 已批准的 YCore 转码使用实际输出 URI 重新探测，不沿用原片容器、尺寸、光盘信息或持久缓存身份；原文件凭据不转交转码服务器。SenPlayer 同步实际打开地址与转码状态，接力入口按明确选择的内核执行前置检查。
- Dolby Vision/FEL/透传状态只使用当前实际输出证据，不再从已移除 MPV 标记或设备性能猜测。较旧后端未验证的新 QoE 内核标识暂停上传，保留本地观察，不能将 YCore 报为 Exo。

## 来源与归档

- `codex/pre-merge-all-20261106` 保留正式基线；`codex/archive-senplayer-engine-plan-20261106` 保留实验分支原提交。
- `audit/merge-all-20261106/incoming-engine-plan-source.zip`：49 个原分支变更文件及 Git blob/SHA 清单，SHA-256 `b175bed0cdbf4d727af401e9e1917fcc9c200d71b8566a2f53c665542a1903b5`。
- `audit/two-engine-20261106/removed-independent-engines-source.zip`：移除前独立内核工作文件归档，SHA-256 `1d0805a8077539152ade6fae44b2b0e87a5609a86b4405bdb4337d3cabbd3ea4`。其后删除的旧辅助文件仍可由上述基线 Git 提交追溯。
- 14 个工作树已盘点。九月旧实验按用户决定保留原归档和差异，不重新注入正式产品；工作树核查见 `audit/merge-all-20261106/worktree-review.md`。
- 分支逐文件取舍与对照记录见 `audit/merge-all-20261106/improve-branch-review.md` 及 JSON 清单。

## 构建输入与验证边界

标准配置 `ycore-senplayer`，`yfuseNativeOnlyRuntime=true`（无旧内核回退）与 `yfuseIncludeMdk=false`。YCore 输入固定 SHA-256 `10873c704ecd5f9d7ddd555492e2e78661cc4548d59bc8dac374984249a41575`；SenPlayer 原 APK 固定 SHA-256 `45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c`。

冷 CI 需要预置匹配输入，或配置 `YCORE_NATIVE_AAR_URL` / `SENPLAYER_SOURCE_APK_URL`；未配置即失败，不自动下载旧内核替代，也不声称已经部署。标准应用构建未重编原生 JNI，使用带来源记录的固定独立 AAR。

实际编译、测试数量、最终 APK 版本/签名/DEX/16 KiB/内核纯度结果由 `audit/releases/20261106-two-engine-1.8.9/` 及交付目录记录。源代码测试不能替代 Android 设备的真实画面、音频、许可和弱网验证；检测到 Android 9 测试手机后，使用独立应用 ID 执行生成媒体的自研内核测试，实际结果以交付记录为准；不触发新增 SenPlayer 许可请求或在线发布。
