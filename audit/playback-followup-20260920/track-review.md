# 选轨与 NativeTunnel 复核（2026-09-20）

只读代码审查；未修改源码、未运行 Gradle、未操作手机或 ADB。以下为当前工作区快照，其他任务可能继续修改行号。

## 结论

用户条目 3 的底层分支仍然存在，但不能据此认定普通语言偏好在首启时必然使耗时翻倍。更直接的问题是 Tunnel 只公布一个无语言信息的合成音轨，详情页语言偏好会匹配失败并被消费掉。详情页明确关闭字幕、某些接力恢复和手动重复选择，确实可以触发本来不需要的全链路重建。

## 代码证据

- `D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt:1740`：处理任意 `Command.SelectTrack` 时，只判断当前路由是否 `NativeTunnel`，没有检查轨道类型、ID 是否有效或是否已经选中。进入后在 1748 行禁用 Tunnel，1750 行调用 `rebuild`，1752 行再向替代 child 发原选轨命令。
- 同文件 1607 行的 `rebuild` 新建探测预算；1612 行 `stopChild`，1635 行创建新 child，1644 行 attach。`stopChild` 在 547 行 release 旧 child，并在 551 行等待释放屏障。这是播放器图重建；不代表所有远端请求或探测必定重新执行，因为缓存仍可能命中。
- `D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeTunnelYPlayer.kt:355`：仅公布 `audio:tunnel-primary`、`Primary audio`、`selected=true`，无语言、编解码器或真实容器索引；字幕列表保留空列表。该类自身在 157–162 行会忽略已选音轨，但 Adaptive 在委派前已重建，因而无法受益。
- `D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeTunnelSession.kt:144`：实际固定 `findFirstTrack("audio/")`，156 行使用其格式，175 行选择该轨道。路由探测同样在 `AndroidCore2MediaProbe.kt:287` 取第一音轨。
- `D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core2/api/YPlayer.kt:107`：`YPlayerOpenRequest` 没有初始音频/字幕偏好字段；`YMediaItem` 也没有。Adaptive 创建单条请求处为 `AndroidAdaptiveCore2YPlayer.kt:902`。

## 首启与恢复的可达路径

1. **详情页语言偏好：匹配失败/丢失。** `DetailExecutor.kt:1209` 保存语言；`PlayerRoot.kt:1465` 在任意轨道列表非空后执行，1467 行先消费请求，1469 行随后按语言匹配。`PlayerSelectionPolicy.kt:6` 使用语言/显示语言/标签匹配，`Primary audio` 无法匹配常见中文、英文、日文语言偏好。因此通常不发选轨，且 `PlaybackTrackRequest.kt:53` 的 consume 已清掉请求。此路径不能用于证明起播翻倍。
2. **详情页显式关闭字幕：可重建。** `PlayerRoot.kt:1479` 的 `SUBTITLES_OFF` 分支在 1483 行无条件发送 `selectTrack(Subtitle, OFF)`；Tunnel 首次公布 audio 后外层条件即满足，不需要字幕列表。默认偏好为 null，不能称为所有影片默认触发。
3. **普通系列记忆恢复：通常不重建。** `PlayerTrackEffects.android.kt:38` 的音轨恢复匹配不到即 return；匹配到 `Primary audio` 时，41 行的 `!target.selected` 阻止重复选轨。字幕恢复在 50 行因为 Tunnel 字幕列表为空直接 return，因此默认/记忆的关闭字幕也不会从这一入口触发。
4. **接力恢复：存在可达重复命令。** `PlayerRoot.kt:1505` 等待 Ready；`HandoffPreferenceResolution.kt:92` 在可信索引为 0、无冲突元数据时能匹配已经选中的唯一合成音轨，`PlayerRoot.kt:1550` 仍无条件发选轨。接力偏好 `subtitlesEnabled=false` 在 1555 行也无条件发关闭字幕命令。是否在实际使用中发生取决于接力输入，未设备实测。
5. **手动选择：条件分支可达。** `PlayerRoot.kt:2859` 委派音频选择，没有过滤同轨；2943 行无条件委派关闭字幕。不能把该分支泛化成所有手动操作——独立副字幕走 `SelectSecondarySubtitle`，未经过 1740 行分支。

## 安全改造方向

- 先过滤已选轨道、字幕已经关闭以及无效 ID，避免无效操作触发 rebuild。
- 初次准备前传递稳定偏好（语言、标签、codec、同语言序号等），由真实容器轨道解析一次；不要把 Emby stream index 直接当 MediaExtractor/FFmpeg 索引。
- 路由能力检查、探测音频要求及实际 session 必须基于同一目标音轨。选中不支持 Tunnel 的 codec 时，从第一次构图就进入可执行路径，避免先起 Tunnel 再拆除。
- 不直接删除 Tunnel 回退：该 session 当前只支持第一音轨，且没有热切换图实现。
- 需要与分阶段探测预算、预热源接管、取消/重试、不同音轨 codec 和多音轨容器联合回归。单纯提高或减少预算不能修复偏好传递缺失。

## 现有测试与缺口

已检查源码中的以下测试（本次未重新运行）：

- `AudioTrackMatchingTest.chineseDisplayLanguageMatchesEngineIsoCode`、`trackTitleRemainsTheFallbackWhenLanguageIsMissing`：覆盖语言/标签函数，不覆盖 Tunnel 合成轨道与消费请求的时序。
- `PlaybackTrackRestoreTest.track_ids_may_change_but_language_and_label_restore_the_same_track`、`same_language_tracks_with_engine_labels_restore_by_ordinal_then_first`：覆盖稳定偏好匹配，不覆盖 Adaptive 的重建分支。
- `PlaybackTrackRequestTest.it_is_consumed_once_so_the_next_episode_starts_clean`：覆盖一次消费，不验证在 Tunnel 轨道信息不完整时应否消费。
- `HandoffPreferenceResolutionTest.trustedIndexDisambiguatesIdenticalLabelsButMismatchedMetadataRejectsIndex`：覆盖可信索引匹配；不验证首启已选轨道的命令去重。
- `YTunnelPolicyTest` 的四个资格判断用例：视频/音频/Surface、无音频、软件音频回退、增强解复用；不覆盖偏好轨道切换。
- `AndroidMetadataProbeStageTest.stage_deadline_cancels_io_preserves_parent_and_rejects_late_handoff`、`reserved_decoder_time_is_never_spent_on_metadata`：覆盖预算取消与保留解码时间，不覆盖选轨造成的第二次 rebuild。

未发现覆盖 `AndroidAdaptiveCore2YPlayer` 首次 Tunnel 建图后语言/字幕偏好应用、同轨无操作、跨路由轨道 ID 映射、预算耗尽时自动选轨重建的集成回归。不能把现有单元测试通过视为这些情形已经验证。
