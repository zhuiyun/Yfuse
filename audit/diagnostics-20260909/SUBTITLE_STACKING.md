# 《绿灯军团》手机中文字幕累积：补充排查

用户确认的现象：**OPPO 手机，中文；上一句不消失，新字幕不断叠上去。**

**已在当前源码中确认并最小复现一个能直接造成该现象的 PGS 图形字幕缺陷：清屏事件被丢弃，“持续到下一展示集”被解释为约 49.71 天，新字幕又不断追加到旧列表。** 这与此前的“请求切 MPV、实际重建 YCore”是两个不同问题。

当前检查基线为 `805af51c`，版本元数据 1.0.45（207）。关键 JNI、bitmap 映射、Enhanced 字幕队列、通用 timeline 文件与候选 205 构建基线 `9d565adf` 相比未变，因此不能认为现有 207 已覆盖此缺陷。

## 证据边界

手机日志 [L77](D:/Demo/Yfuse/audit/diagnostics-20260909/224345/diagnostic-20260908-001.jsonl:77)、L115 出现多个 `S_HDMV/PGS` 轨，随后确实使用 Enhanced 解复用路径。但日志没有记录片名、实际选中的字幕轨及字幕事件；用户补充“中文”也没有说明编码格式。因此：

- **可以确认：** 当前源码的 PGS 清屏/替换缺陷存在，且能复现长时间累积旧句。
- **高度吻合但尚未完全确认：** 《绿灯军团》当时选中的中文轨正是 PGS，因此触发了该缺陷。
- 本次没有读取或播放《绿灯军团》原片，也没有手机截图、逐句字幕事件或真机回归结果。

## 完整因果链

| 环节 | 当前行为 | 为什么造成堆叠 |
| --- | --- | --- |
| FFmpeg PGS 解码 | [pgssubdec.c:510](D:/Demo/Yfuse/.native-build/ffmpeg-n8.1/libavcodec/pgssubdec.c:510) 明确没有固定结束时间，由下一个展示集的开始结束；设置 `end_display_time=UINT32_MAX` | 该值表达“等下一事件”，不是普通毫秒时长 |
| PGS 清屏 | 同文件 L516–518：下一展示集可以 0 对象，仍成功返回；L659–661 使 got_subtitle 为真 | “成功解码但零矩形”是有意义的清屏，不等于没有解出数据 |
| JNI 桥接 | [ycore_demux_jni.cpp:2426](D:/Demo/Yfuse/scripts/native/ycore_demux_jni.cpp:2426) 将 `!got_subtitle` 与 `num_rects==0` 都返回 null；L2451–2452 原样传递最大无符号结束时间 | 清屏事件消失，非空字幕带着哨兵值进入 Kotlin |
| 时间映射 | [AndroidFfmpegDemuxer.kt:492](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidFfmpegDemuxer.kt:492) 将 UINT32_MAX 毫秒乘 1000；L498–502 直接选为结束时间；L495 还禁止零矩形 payload | 一句被延长至约 49.71 天，有限 packet duration 也被覆盖；协议无法传递清屏 |
| 读前缓存 | [AndroidDemuxReadAheadNode.kt:292](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidDemuxReadAheadNode.kt:292) 只携带 `List<YSubtitleCue>`，上游 null 已变 empty list | 无法区分“暂无新输出”与“清屏” |
| 会话队列 | [AndroidEnhancedPlaybackSession.kt:1856](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidEnhancedPlaybackSession.kt:1856) 只 `addAll(decodedCues)`，随后按 endUs 回收 | 新展示集不能结束同轨旧展示集，几乎永不过期的旧位图一直留着 |
| UI 绘制 | [YSubtitle.kt:168](D:/Demo/Yfuse/composeApp/src/commonMain/kotlin/com/yfuse/core2/subtitle/YSubtitle.kt:168) 筛所有尚未结束的 cue；[Core2Surface.kt:244](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/Core2Surface.kt:244) 遍历全部有效位图 | UI 收到的旧字幕仍被标为有效，于是多句同时画到屏幕上 |

这里不需要用户开启双字幕，也不需要把故障归因于字幕字号或位置。UI 有正常到期过滤，问题主要在上游展示集合的生命周期。

同时存在一个可由源码推出的资源风险：字幕历史回收条件依赖 `endUs`，这些位图在正常观影期间难以回收，像素数组和快照列表会持续增长。**尚无证据确认它造成了此前日志中的卡顿、内存故障或进程退出。**

## 最小复现结果

使用当前生产 `AndroidFfmpegDemuxer.kt` 的 bitmap 映射函数和全部相关常量（原样提取），以及完整生产 `YSubtitle.kt`，通过本机 Kotlin 编译器在 JVM 执行。测试输入是合成的已解码位图展示集；仅用数据结构替身提供 sample 的轨道、PTS、duration，不替换时间映射和 timeline 逻辑。

**这验证生产 Kotlin 逻辑的错误行为，不是 Android/FFmpeg 端到端真机测试，也不代表已修复。** native 清屏丢弃由源码确认。

| 用例 | 正确行为 | 当前代码实际结果 |
| --- | --- | --- |
| 普通有限结束时间，字幕 1–2 秒 | 2 秒时消失 | 正常消失，作为对照 |
| 同轨在 1、3、5 秒输入 PGS 展示集，每个均为 UINT32_MAX；packet duration 给有限 2 秒 | 6 秒只显示最新的第三组 | **三组同时有效** |
| 同一序列推进至 1 小时，无额外清屏 | 只能保留最后展示集，前两组早已被替换 | **三组仍同时有效** |
| 尝试传零矩形展示集 | 接收为清屏事件 | **Kotlin 抛出 `FFmpeg subtitle rectangle count is invalid`**；实际 JNI 更早就将它丢弃 |

首组字幕 startUs=1000000，endUs 被计算为 **4294968295000**。UINT32_MAX 对应时长为 **49.7102696 天**。

复现文件：

- [运行脚本](D:/Demo/Yfuse/audit/diagnostics-20260909/subtitle-repro/run-repro.ps1)
- [断言用例](D:/Demo/Yfuse/audit/diagnostics-20260909/subtitle-repro/Repro.kt)
- [结果 JSON](D:/Demo/Yfuse/audit/diagnostics-20260909/subtitle-repro/result.json)
- [生产源码 SHA-256](D:/Demo/Yfuse/audit/diagnostics-20260909/subtitle-repro/source-hashes.json)

在当前工作区重跑：`& .\audit\diagnostics-20260909\subtitle-repro\run-repro.ps1`。脚本使用本机已有的 JDK/Kotlin 缓存，只在本复现目录生成文件，不修改生产源码。

## 修复要求

1. 在 native → Kotlin → 读前队列 → 播放会话链路中明确区分 **暂无新输出、替换展示集、清空展示集**，保留事件 PTS 与轨道标识。
2. 把 UINT32_MAX 当作开放结束标记。下一同轨展示事件到来时，结束上一组所有位图；不要简单将所有字幕强制显示 5 秒。
3. 清屏和替换按媒体播放时间生效。预取线程可能提前解到数十秒后的字幕，不能在解码时立刻清掉当前画面。
4. 同一个展示集可以有多个矩形，必须整体展示和替换，不能只保留“最新一个 cue”。主副字幕分别管理，不能互相清除。
5. 保留 seek/选轨/重建后的代次隔离和历史回看需求，并确保旧位图可回收。
6. 补充字幕轨格式、清屏/替换事件数、当前有效展示集数等脱敏诊断字段；以用户原片原轨完成手机复验。

最低回归序列：1 秒 A（含两矩形）、3 秒 B、5 秒清屏；期望 1.5 秒仅 A 两矩形、3.5 秒仅 B、5.5 秒无字幕。再覆盖双字幕、连续 seek、预取提前解码、未知结束时间、有限结束时间及长时间队列回收。

现有 `AndroidFfmpegDemuxerMappingTest` 只有普通有限结束时间的位图用例，没有覆盖 PGS 哨兵、零矩形清屏或展示集替换；通用 timeline 测试能验证普通到期，无法发现上游未传入正确结束事件。

## 其他字幕发现

- [AndroidNativeDirectYPlayer.kt:2688](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt:2688) 给内嵌文本字幕统一传 `durationUs=null`，会回退到 5 秒。快速对白可能短暂叠几句，但不能单独解释旧句长时间持续累积。
- [AndroidAdaptivePresentationState.kt:19](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptivePresentationState.kt:19) 转换 DASH Period 的播放位置但未转换字幕 cue 时间，存在多 Period 字幕错时隐患，**与本次手机 MKV/PGS 假设没有已建立的关联**。
- ASS 渲染已有可见 cue、seek、generation 变化时清空旧结果的保护，本次没有足够证据认定为普通 ASS 异步残帧问题。

本次新增独立复现与排查文档，未修改业务代码，也没有生成修复安装包。
