# 本轮验证记录

基线：`04dc5086eccaacdf3013080b6ecc5d353f1663ee`。日期：2026-09-15。

## 已执行

| 验证 | 结果与证据 |
|---|---|
| 服务端现有 JVM 测试 | 175 个测试，0 failure / error / skipped，24 个 suite；本轮 `:watchTogetherServer:test` 实际执行并成功，XML 时间为 2026-09-15。 |
| 协议 JVM 测试 | 强制重跑完成，8 个测试，0 failure / error / skipped，2 个 suite；XML 时间为 2026-09-15。 |
| 五模块 ktlintCheck | composeApp、tvApp、mdkAndroid、watchTogetherProtocol、watchTogetherServer 强制重跑通过。与协议测试合计 46 个任务实际执行，BUILD SUCCESSFUL。baseline 仍启用，成功不能解释为零历史格式债务。 |
| 扫描器现有测试 | `scripts/test_supply_chain_check.py`，10/10 通过。 |
| 扫描输入边界探针 | 5 个跟踪锁之外额外读取 15 个锁；有效坐标 765 对比跟踪集合 745，多 20 个。 |
| OSV 响应完整性探针 | 本地 mock `{}` 和 `{"results":[]}`，对一个待查依赖均返回空发现，确认未校验响应完整性。未调用线上 OSV。 |
| 规模与链接检查 | 808 个生产 Kotlin 文件、43 个 >1000 行、12 个 >2000 行；baseline 1155 条历史记录；TV 备份源码含 1 个 NUL；报告源码链接均存在且行号有效。 |
| 业务改动检查 | `git diff --stat` 为空。审查脚本/报告位于新增的 audit 子目录。 |

结构化证据见 [verification-evidence.json](D:/Demo/Yfuse/audit/code-review-20260915/verification-evidence.json)、[scanner-evidence.json](D:/Demo/Yfuse/audit/code-review-20260915/scanner-evidence.json)。

## 可复跑命令

使用本机已存在的 Gradle 与 JDK，离线运行。早期尝试遇到 JVM 回环建立错误；设置下列 JVM 属性后启动成功。强制执行时必须显式提供 Java 17 的工具链路径。

```powershell
$env:GRADLE_USER_HOME='D:\Demo\Yfuse\.gradle-tmp'
$env:GRADLE_RO_DEP_CACHE='C:\Users\app-inkbird\.gradle\caches'
$env:JAVA_HOME='C:\Users\app-inkbird\.jdks\ms-21.0.9'
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=D:/Demo/Yfuse/gradle.properties'
$reviewGradle='D:\Demo\Yfuse\.gradle-tmp\wrapper\dists\gradle-9.5.0-bin\bvnork1r7n8i6kp5cnkibsc9q\gradle-9.5.0\bin\gradle.bat'
& $reviewGradle :watchTogetherProtocol:jvmTest :watchTogetherServer:test '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' --offline --console=plain --no-daemon --max-workers=2
& $reviewGradle :watchTogetherProtocol:jvmTest :composeApp:ktlintCheck :tvApp:ktlintCheck :mdkAndroid:ktlintCheck :watchTogetherProtocol:ktlintCheck :watchTogetherServer:ktlintCheck '-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15,C:/Users/app-inkbird/.jdks/ms-21.0.9' --rerun-tasks --offline --console=plain --no-daemon --max-workers=2 --continue
$reviewPython='C:\Users\app-inkbird\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
& $reviewPython 'D:\Demo\Yfuse\scripts\test_supply_chain_check.py'
& $reviewPython 'D:\Demo\Yfuse\audit\code-review-20260915\scanner_probe.py'
& $reviewPython 'D:\Demo\Yfuse\audit\code-review-20260915\summarize_evidence.py'
```

## 结论限制

- 除扫描器两项有本轮可运行探针外，报告中的业务问题依据源码和调用链确认；所建议的并发、故障注入回归尚未执行。
- 没有进行线上权限尝试、生产数据变更、依赖升级或漏洞数据库全量重扫。
- 未运行 Android 全量单测/仪器测试、宏基准、堆转储、LeakCanary 或持续直播压力测试；不据静态机制宣称已发生 OOM/ANR。
- native C ABI 仅源码审查，未作为当前 APK 执行路径证据。
- 现有测试通过不代表覆盖此次新发现的边界；修复后应加入各条所述回归，再做相关设备验证。
