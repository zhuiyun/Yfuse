# 当前源码包范围复核

日期：2026-11-06。只读核查 Git 文件清单、现有来源清单与指定目录；仅新增本报告及同名 JSON。没有 fetch、stage、merge、打包、联网或读取凭据值，没有递归检查巨大 build 缓存。

## 当前工作区范围

开始检查时 HEAD 为 `8c958457bdb40190bde9fb4fc8fab79193cb29c6`；根代理同时独立整合，最终清单快照 HEAD 为 `9be95151403d13f811b4d35733ec2dbe45f840c1`。本报告不是冻结最终提交；实际导出应重新绑定根代理最终 HEAD。

- 快照时 tracked 与 staged 均无未提交变更。15,739 个 tracked 文件中，按现有 `scripts/export-source-snapshot.py` 排除规则有 3,325 个活动源码、资源、脚本、配置和文档候选。
- 可见 untracked 为 156 项：155 项均在 `audit/`，剩余仅 `.workbuddy/memory/2026-10-29.md`。没有可见未跟踪生产源码。
- 对主要模块的 `src`、scripts/config/gradle/media-tests/parity/examples 定向检查，未发现被 ignore 掩盖的新增生产源码。这个结论不包括遍历所有 build、下载依赖或历史 worktree。
- `audit/**` 默认被忽略，Markdown 例外可见。因此只看 `git status` 会漏掉诊断 Kotlin/Python/Gradle 文件；反过来 `git add .` 会收进大量旧报告副本，不能替代路径清单审阅。

## 各类内容的建议

| 内容 | 当前状态 | 新完整包建议 |
| --- | --- | --- |
| 当前活动 tracked 代码、资源、测试、构建文件与锁文件 | 正式构建来源；历史 audit 快照另行排除 | 作为主源码包，以最终提交和每文件 Git blob/SHA-256 为准 |
| 11-06 日历诊断工具 5 项 | ignored；不属于常规生产 source set | 单独诊断源码附录，明确其联网或离线重放用途，不移入生产模块或默认测试任务 |
| 日历、播放、DEX、许可测试的本轮报告和机器结果 | audit 证据 | 选择性证据附录；保留对应源码提交、测试时间、产物 SHA 和实际验证边界 |
| SenPlayer 原始运行时 5 pins | 1 项 tracked 配置 + 4 项 ignored 二进制 | 私有完整重现包可按原 5 pins 精确收录；Git 中继续保持 bridge/importer/manifest 与原始二进制分离 |
| 旧 worktree 实验与旧 `merged-yfuse` | 既有独立恢复包、差异及候选决策 | 引用原包 SHA 和逐候选决定；不能再次以目录覆盖当前实现 |
| `.workbuddy/`、`.workbuddy-ai/` | 历史工具记忆，无生产构建作用 | 排除，不把记忆文本当合并指令或业务代码 |
| 本机环境、签名、浏览器会话、构建与下载产物 | 私有或可生成材料 | 明确排除；不因“完整代码”而收进源码包 |

## 新增诊断源码

以下 5 文件应在“全部相关代码”的来源附录中可见；同名 JSON 记录路径、大小及 SHA-256。本轮未执行它们。

1. `audit/calendar-live-check-20261106/check-direct-source.py`：读取本机 `local.properties` 中的 TMDB 配置并作直连诊断。保留程序本身，不附带本机 properties 或凭据；其执行授权不能随源码包自动继承。
2. `audit/calendar-live-check-20261106/kotlin/com/yfuse/feature/calendar/CalendarLiveCheck.kt`：实际数据链路诊断入口。
3. `audit/calendar-live-check-20261106/live-check.init.gradle`：临时接入诊断 source set 的脚本。
4. `audit/calendar-live-check-20261106/direct-replay/kotlin/com/yfuse/feature/calendar/CalendarDirectCaptureReplay.kt`：对捕获的公开数据作 MockEngine 重放。
5. `audit/calendar-live-check-20261106/direct-replay/replay.init.gradle`：重放测试的临时构建接入脚本。

若要重现离线结果，还需按明确白名单附带其引用的公开响应 JSON 与摘要，并单独记录这些是测试数据而非源码。不要默认收进所有 headers、日志、`.bin` 响应、JUnit 输出或整个 network/runs 目录；先检查脱敏状态。本次仅核查源码用途和元数据，没有对全部历史日志进行内容级凭据审计。

## SenPlayer 5 pins

`senplayerBridge/src/main/assets/senplayer/runtime.json` 对应原 APK SHA-256：`45a9deb5018cb705432604b564189cf067473e0f52706544706457655ec5fe4c`。本次按该清单逐项读取字节计算哈希，5/5 的大小和 SHA-256 一致：

| 文件 | 字节数 | Git 状态 |
| --- | ---: | --- |
| `classes.dex` | 8,941,816 | ignored |
| `license.json` | 202 | tracked |
| `native/libFFmpegKit.so` | 54,435,336 | ignored |
| `native/libMLibKit.so` | 15,671,472 | ignored |
| `native/libc++_shared.so` | 1,292,904 | ignored |

每项 APK entry、SHA-256 与校验结果在 JSON 中。`runtime.json` 本身也已 tracked。二进制来源应标为 `unchanged_user_supplied_apk_entry`，不能称为已恢复、可独立编译的 native 源码；本地重现包收录也不表示已授权公开分发这些第三方输入。

## 明确排除，未读取秘密值

只核对路径存在性与 Git 状态：`local.properties`、`keystore.properties`、`signing/yfuse-release.jks`、`watchTogetherServer/calendarRenderer/renderer-state/storage-state.json` 均应排除。它们的内容、哈希及值不进入本报告或来源清单。

同时排除 `.git`、`.gradle*`、`.kotlin`、`.cxx`、build、node_modules、APK/HAP、artifacts、下载 AAR、SDK、本地代理缓存以及未选择的旧 audit。供应商二进制未收录时保留依赖锁、获取脚本、来源/校验和说明；主源码包不是离线依赖镜像。

## 复用旧方案时的调整

11-05 基线 `yfuse-merged-source.zip` 为 3,242 文件，SHA-256 `245738fbf4d8fc913c662105648ba4e728afc6acb777de5b7dc232eab209fd1a`；`worktree-recovery.zip` SHA-256 `6b726d387e3e561f2f8dcc1c0c591197b6569f262d1b5b9c530113eb47afc0e5`。旧 `merge-summary.json` 明确仍有 31 个未验证候选 blob，不能把其归档状态改称为已功能合并。

当前 exporter 已具备私有路径排除、5 pins 校验、逐文件 SHA、ZIP 回读校验和导出期间文件变化检查，适合沿用；但它排除整个 `audit/`，会漏掉上述 5 个新诊断源。建议主源码归档继续采用该规则，另生成显式选择的诊断/证据附录，不取消 audit 全局排除。旧包保持不变；新包使用新的目录、最终 HEAD 和新 SHA，不覆盖旧证据。

逐文件来源建议至少包含：`archivePath`、`originalPath`、`category`、`sourceCommit`、`baseCommit`、`gitBlob`、`gitMode`、`sha256`、`bytes`、`selectionDecision`、`decisionEvidence`、`buildRole`。原 APK 输入另加 `apkSha256`、`apkEntry`、`pinMatch`。删除、重命名和未采纳候选应在排除/决策清单中有原因，不能从结果中静默消失。

配套清单建议：snapshot-manifest、source-provenance、source-exclusions、diagnostic-provenance、runtime-provenance、legacy-candidate-decisions、verification-scope 及 SHA256SUMS。验证清单区分本次检查、继承历史证据、未测平台/部署/设备；源码可追溯不等于所有平台已编译或全部历史实验已验证。
