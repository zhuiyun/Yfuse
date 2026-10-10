# 播放报错后后续视频全部无法播放（2026-10-10）

## 日志结论

输入：`Yfuse-diagnostics-20261010-235730.zip`。导出版本为 1.2.3 (275)，构建标识
`ab36742935a2a8c2fd6373c8890d97a0ca85adef-dirty-54a4d9d77c3f`。
当前进程为 `a503f69b`，设备为 OPPO OPD2409，Android 17 / API 37。
诊断包还包含旧版本和历史进程，以下仅使用当前进程，时间为北京时间。

| 时间 | 事件 | 判断 |
| --- | --- | --- |
| 23:49:35 | `engine_retirement_done` → `engine_construct_started` → `engine_constructed` → `engine_binding_published` | 正常创建 YCore2Native |
| 23:49:42 起 | 音视频进入 Rendering，后续队列切换也有输出 | 同一进程曾成功播放 |
| 23:53:43.907 | 已播放的 YCore2Native 退出，记录 `engine_detached` | 进入旧引擎释放路径 |
| 23:55:49.865–49.903 | 播放准备完成；`engine_slot_requested` 后约 38 ms 出现 `startup_error` | 释放等待未完成，新引擎未开始构建 |
| 23:56:01.716–01.749 | 重试约 32 ms 后再次失败 | 仍停在释放等待阶段 |
| 23:56:30.673–30.723 | 更换服务器/片源，约 50 ms 后失败 | 影响其他片源和新播放页面 |
| 23:57:20.489–20.546 | 另一请求约 57 ms 后失败 | 共享播放状态仍无法恢复 |

四次失败的新请求均没有 `engine_retirement_done` 或 `engine_construct_started`，
而正常播放的第一次请求明确包含这些事件。相应路径的 PlaybackInfo 和媒体详情成功返回。
包中还存在其他服务器的 Cloudflare 拦截、认证失败和同步超时，但这些错误不能解释已经
完成播放准备的请求为何一致在创建引擎前失败。

持续无法播放的问题由此定位到旧引擎释放等待/资源预留阶段。旧日志只有
`startup_error`，没有该阶段的异常消息和堆栈，无法进一步确认首次释放异常来自
join 超时、解码器销毁、传输取消还是其他清理操作。

## 确认的代码缺陷

`AndroidPlaybackEngineRetirements.registry` 跨播放页面共用。原实现为每个旧引擎
只保存一个 `CompletableDeferred`。释放任务失败且 `releaseCompleted` 仍为 false 时，
新请求重复 await 同一个已异常完成的结果，立即失败，不再调用 release/join。
关闭页面、切换片源或切换引擎都会受到该记录影响。

修复为每次释放尝试保留独立结果：

- 有明确串行释放完成信号的引擎，下一次播放请求重新执行幂等的 release/join；
  并发等待者共用一次在途重试。
- 原引擎、资源预留和完成回调持续保留到真实清理完成。清理未完成不允许创建下一
  解码器；未知释放状态的异常仍保留阻断。
- 页面等待超时或关闭不取消独立生命周期的清理任务。迟到的清理成功可以由手动
  重试或新的播放页面接续。
- 新增 `engine_release_failed`、`engine_release_retried`、`engine_slot_failed`，
  记录完整异常堆栈和 retirement/construction/handover 失败阶段。

本次处理的是已确认的旧异常持续复用缺陷。若设备上的原生线程或厂商解码器一直
无法退出，资源预留仍会生效；现有证据不能证明本次修复能够恢复真正未完成的清理。

## 验证与边界

- 使用项目版本 Kotlin 2.4.20、kotlinx.coroutines 1.11.0、JUnit 4.13.2，直接编译
  生产 `PlaybackEngineSlot.kt` 与完整 `PlaybackEngineSlotTest.kt`。
- 隔离环境替换 Android 适配器、日志和外围数据契约；被测资源等待、重试、构建预留、
  页面生命周期和 PreparingVideoEngine 均来自实际生产源码。
- 修改前 14 项测试中 3 项失败，复现释放异常后新页面无法恢复和清理无法重试。
- 最终 15 项全部通过。覆盖新播放页面、未完成清理时阻止新解码器、并发重试、
  迟到完成、等待超时后手动重试，以及非协作构建、页面关闭、队列/控制变化和
  crash owner 完成回调顺序。
- ktlint 1.3.1、`git diff --check` 通过。
- 完整 Android Gradle 构建未完成：环境没有 Android SDK/NDK 缓存，Gradle wrapper
  网络请求失败。未制作新 APK，未在用户设备验证。

## 当前包的临时恢复

Android 系统设置 → 应用 → Yfuse → 强制停止，然后重新打开。
这会重建进程内共享的释放等待器，仅退出播放页不能清除它。
