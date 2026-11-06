# 工作树未提交代码盘点与旧实验归档复核

日期：2026-11-06。正式基线为 `8c958457bdb40190bde9fb4fc8fab79193cb29c6`（master）。本次只读检查 Git 元数据、改动文件名与普通源码字节哈希，未修改任何工作树源码、Git refs 或索引，未提交、构建、联网；未读取或输出密钥文件内容。本盘点是 root 后续快进及新分支合并前的状态快照，不表示其正在进行的合并状态。

## 结论

未发现新增或发生变化的未提交产品源码候选。14 个登记工作树均可访问，共 929 项 tracked 改动、510 条非忽略 untracked 路径；没有未解决 Git 合并条目。当前正式工作树 tracked diff 为空；其 156 个 untracked 路径属于 audit/.workbuddy 记录，不是待合并产品代码。

旧清单的 1,135 个活动代码候选全部维持原字节或原删除状态：1,101 个文件逐项哈希与归档/来源清单一致，34 个删除候选仍被删除且原基线与补丁均在 ZIP 内。旧工作树 HEAD 除正式分支前进外均未变化。按用户已确认的范围，继续完整归档并列差异，不把这些历史实验回灌正式实现。

另有一个早已存在、工作树干净的 7 月 detached 提交不在正式 HEAD 祖先链中。进一步核对发现它与已合入同题提交不是 patch-id 等价，但唯一附加的偏好即时生效逻辑已由当前共享跳过控制器承接，证据见下文；无需重放旧播放器文件。不能把这一语义审阅写成所有提交均为祖先。

## 逐工作树状态

| 工作树 | tracked 改动 | untracked 路径 | 活动源码/配置/文档候选 | 相对旧候选清单 |
| --- | ---: | ---: | ---: | --- |
| `D:/Demo/Yfuse` | 0 | 156 | 0 | 正式基线前进；无产品未提交变更 |
| `C:/Users/app-inkbird/.codex/visualizations/2026/07/30/019fb26f-4b0a-7a12-bea8-69eb929037bc/Yfuse-release-validation-0.2.05` | 0 | 0 | 0 | 无未提交候选 |
| `C:/Users/app-inkbird/.codex/visualizations/2026/07/30/019fb26f-4b0a-7a12-bea8-69eb929037bc/Yfuse-release-validation-0.2.05-a06f4e0` | 1 | 0 | 1 | 所有候选字节/删除状态与旧归档一致 |
| `D:/Demo/Yfuse/.worktrees/playback-perf` | 0 | 0 | 0 | 无未提交候选 |
| `D:/tmp/yfuse-b1` | 159 | 69 | 191 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-b2` | 178 | 92 | 233 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-diag0924` | 171 | 78 | 212 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-fix0924` | 97 | 21 | 118 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-impl0928` | 0 | 0 | 0 | 无未提交候选 |
| `D:/tmp/yfuse-merge` | 116 | 0 | 116 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-perf0923` | 20 | 5 | 25 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-review` | 0 | 0 | 0 | 无未提交候选 |
| `D:/tmp/yfuse-transition` | 160 | 82 | 205 | 所有候选字节/删除状态与旧归档一致 |
| `D:/tmp/yfuse-wave` | 27 | 7 | 34 | 所有候选字节/删除状态与旧归档一致 |

表中候选包含普通源码、构建配置和文档；untracked 数量还包含被分类排除的 audit/生成物路径，因此不能把路径总数当作需要合并的代码数量。完整路径、状态、SHA、来源判定见 [worktree-inventory.json](D:/Demo/Yfuse/audit/merge-all-20261106/worktree-inventory.json)。

## 旧归档完整性

- [worktree-recovery.zip](D:/Demo/Yfuse/audit/senplayer-comparison-20261105/worktree-recovery.zip) SHA-256 仍为 `6b726d387e3e561f2f8dcc1c0c591197b6569f262d1b5b9c530113eb47afc0e5`，与上次交付记录完全一致。
- ZIP 中 1,274 个条目存在；1,135 个候选的恢复文件或删除基线/补丁全部存在，逐文件哈希不一致为 0。
- 当前候选相对旧归档发生字节或删除状态变化的数量为 0；新增普通源码/配置/文档候选为 0。
- 原判定仍为：310 项当时与正式文件相同、657 项历史 blob、134 项独有脏候选、34 项删除候选。134 项独有候选此前去重为 81 个 blob，其中 31 个未验证/部分未确认实验；此次仅确认完整保留，不把它们改称已实现或已验证。

原处理依据：[merge-report.md](D:/Demo/Yfuse/audit/senplayer-comparison-20261105/merge-report.md)、[worktree-source-decisions.json](D:/Demo/Yfuse/audit/senplayer-comparison-20261105/worktree-source-decisions.json)、[candidate-decisions.json](D:/Demo/Yfuse/audit/senplayer-comparison-20261105/candidate-decisions.json)。

## 当前会话的新诊断代码

普通 git untracked 列表不会显示忽略的 audit Kotlin/脚本。本次另检查了已知当前任务 `audit/calendar-live-check-20261106` 的 5 个源码/入口文件，均在旧归档范围外，属于本会话诊断而非正式应用输入：

- [check-direct-source.py](D:/Demo/Yfuse/audit/calendar-live-check-20261106/check-direct-source.py)：`ff0740a740794567a023b690445b86716fc0e0224c096f2251df47fa242a793d`。
- [live-check.init.gradle](D:/Demo/Yfuse/audit/calendar-live-check-20261106/live-check.init.gradle)：`4eca7366cc161cb02c15f63981a8fbd3e421c20f69fe33586fee2d495e99b248`。
- [replay.init.gradle](D:/Demo/Yfuse/audit/calendar-live-check-20261106/direct-replay/replay.init.gradle)：`3a28486292c360d996ff235bedc97d51287547a1868fc0a645d965dfcd291798`。
- [CalendarDirectCaptureReplay.kt](D:/Demo/Yfuse/audit/calendar-live-check-20261106/direct-replay/kotlin/com/yfuse/feature/calendar/CalendarDirectCaptureReplay.kt)：`ce42c2d9985d1af52ab99d1145a8919b273267ad90884c83813371e3b39825ab`。
- [CalendarLiveCheck.kt](D:/Demo/Yfuse/audit/calendar-live-check-20261106/kotlin/com/yfuse/feature/calendar/CalendarLiveCheck.kt)：`c267b682a4f56f74431250140ff7a744f4fb3cdff558ef2f25abb65678243161`。

它们包含匿名真实 HTTP harness、真实响应捕获脚本和完全离线的数据回放；未接入常规 sourceSet，只在显式 init/opt-in 调用时使用。建议作为此次整合的诊断附件保留。不要自动把联网诊断加入常规测试，也不要把捕获数据或诊断成功误报为用户已登录会话验收。

## 已定位的非祖先历史提交

`Yfuse-release-validation-0.2.05` 保留 detached HEAD `c58906188bd92614ea4cde600ef064ca95e1caa4`，日期 2026-07-31，标题 `fix: restore player audio and mpv playback for 0.2.05`。该 HEAD 自旧 inventory 未变，工作树干净，相对当前正式基线存在 1 个非祖先提交。root 指出的同题提交 `a06f4e05208584eb006de4e5787434db40aa8ecc` 已是正式基线祖先。

只读 `git cherry` 与 stable patch-id 结果：

| 检查 | 结果 |
| --- | --- |
| `git cherry 8c958… c589…` | `+ c58906188bd92614ea4cde600ef064ca95e1caa4` |
| c589 stable patch-id | `ad7929dd5dfa21f064fdae38829d6398ee96fbdb` |
| a06 stable patch-id | `4262b81833b54ee0cc62b13e1ac30e640e87d584` |
| 两提交父节点 | 均为 `8fd753f901a1182f227e01594562c0f32782211c` |
| 两提交最终树差异 | 仅 `PlayerActivity.kt`，增加 17 行、删除 1 行 |

因此不能称两提交 patch-id 等价。唯一额外差异是 c589 将 `SkipSegmentPreferences` 注入旧 PlayerRoot，使用 `collectAsState` 观察手动片头片尾值，再通过 `applyTo` 重新计算当前分段；其他音量/MPV 修复、测试与版本文件在这两棵树之间相同。

正式基线 `8c958…` 的对应实现已拆分：`PlayerRoot.kt:1045-1062` 把偏好传给 `rememberPlayerSkipController`；`PlayerSkipCoordinator.kt:134-136` 观察 `bySeries`、`skipMode`、`onlineEnabled`，`:175-190` 将偏好状态作为 `remember` 输入，调用 `preferences.applyTo` 并计算 active segment。这是该附加行为已有正式实现的具体源码证据，不是因提交标题相同而假定等价。

处置建议：保留当前共享控制器，保留旧提交来源记录，不 cherry-pick 7 月单体 PlayerActivity，不倒退版本。该项不构成新的未合并产品逻辑；结论限于静态源码对应关系，不新增运行时验收声明。详细比较结果存于 inventory 的 `historicalCommittedTipBoundary`。

除此之外，本盘点未发现需要把旧工作树未提交代码再合入正式分支的项目。主线后续改动若发生，应在最终整合快照前重新核对其自身状态；此报告是所记检查时间的只读快照。

## 范围与限制

- Git 操作仅为 worktree list、diff 文件状态、ls-files、祖先判定及该旧提交的标题/文件名查询，使用 `--no-optional-locks`，没有 checkout/reset/add/commit/fetch 等操作。
- 敏感文件名、环境与签名配置不读内容；生成目录、下载二进制和 Git 忽略路径不做全盘递归，仅额外清点已知当前日历 audit 源码。详细分类写入 JSON。
- 归档相同证明来源材料仍在，不证明旧实验的功能已合并或正确。本次不编译、不跑测试、不联网，不新增任何产品可运行性结论。
