# YCore 自研内核 · 待办审查

审查日期：2026-08-16 · 基线：`master` @ `ba9a244` + 本分支改动
配套文档：[`YCORE_ARCHITECTURE.md`](YCORE_ARCHITECTURE.md) · [`YCORE_VALIDATION_MATRIX.md`](YCORE_VALIDATION_MATRIX.md)

> **2026-08-17 状态：** 这是实施前审查记录。结构化输出就绪、静默故障检测、断网恢复、
> A/V 同步观测、客户端 ABR/DASH、离线 Widevine 生命周期和匿名 QoE 聚合现已落地；
> 当前准确状态以 `YCORE_ARCHITECTURE.md` 和 `YCORE_VALIDATION_MATRIX.md` 为准。

> 环境限制同前：本沙箱代理封禁 `dl.google.com`，Gradle 无法解析 AGP，**任何 Kotlin 代码都编译/运行不了**。
> 下面两个缺陷都用 Python 忠实复刻原逻辑做了验证，验证脚本的行为等价于源码，但不是真机结果。

---

## 一、先说结论

架构文档和实现的**对应关系是准确的**——我逐条核对了文档里的数字，全部属实：

| 文档声明 | 代码 | 结果 |
| --- | --- | --- |
| 失败/性能存储上限 96 条 | `DEFAULT_MAX_PERFORMANCE_RECORDS = 96` | ✅ |
| 深度探测 4 秒预算、24 条缓存 | `DEFAULT_MEDIA_PROBE_TIMEOUT_MS = 4_000L`、`MAX_PROBE_CACHE_ENTRIES = 24` | ✅ |
| ISO 读取 8 MiB、12 条哈希缓存 | `MAX_DISC_IMAGE_INSPECTION_BYTES = 8 MiB`、`MAX_DISC_IMAGE_CACHE_ENTRIES = 12` | ✅ |
| 失败记忆 7 天、性能基线 30 天 | `7L * 24L * ...`、`30L * 24L * ...` | ✅ |
| 两秒活跃观察间隔 | `RUNTIME_OBSERVATION_INTERVAL_MS = 2_000L` | ✅ |
| 可从诊断页清除学习数据 | `PlayerSettingsPanel.kt:501` "重置 YCore 学习数据" | ✅ |
| 光盘菜单命令明确不支持 | `sendDiscMenuCommand(): Boolean = false` | ✅ |

16 个 `core/playback` 模块里 11 个有专属单测。策略与状态机这一层做得扎实。

**问题集中在一个地方：策略层与运行时之间的那道接缝。**
纯策略被测得很好，但把真实播放状态翻译成策略输入的那一段既没有测试，也建立在一个不该用的信号上。

---

## 二、P0-A · 自动恢复的判据是本地化 UI 字符串

`YCorePlayerRuntime.android.kt:167`

```kotlin
videoReady = videoHeight > 0 || !diagnostics.videoOutput.contains("等待"),
audioReady = audioTracks.any { it.selected } || !diagnostics.audioOutput.startsWith("等待"),
```

`videoReady` / `audioReady` 是 `PlaybackRuntimeFaultDetector` 判断"要不要自动切换后端"的核心输入。
它们不是来自引擎的结构化状态，而是**对给人看的中文诊断文案做子串匹配**。

把三个引擎实际会写入的文案全部代入这两个判据，结果如下：

| 文案 | 来源 | 判为 |
| --- | --- | --- |
| `MDK 未提供可验证的视频输出状态` | MdkVideoEngine | **READY** |
| `MDK 未提供可验证的音频输出状态` | MdkVideoEngine | **READY** |
| `音频输出已释放` | ExoVideoEngine | **READY** |
| `等待转码视频首帧` | ExoVideoEngine | not ready |
| `等待转码视频输出` | MpvVideoEngine | not ready |
| `等待转码音频输出` | Exo / Mpv | not ready |

三个具体后果：

**1. MDK 上的静默输出检测完全失效。** MDK 的文案字面意思是"无法提供可验证的输出状态"，
而判据因为它不含"等待"，把它读成了"已就绪"。于是在 MDK 后端上，
`StartupTimeout`、`VideoOutputMissing`、`AudioOutputMissing` 三种故障**永远不会触发**。
（`PositionStalled` 反而会触发，因为那一支要求 `videoReady == true`。）

这一条值得特别注意：master 最近三个提交
（`f9ee5db` 静默 DTS 回退、`20d82e3` 不支持音频的静默播放、`0da1c9f` 静默音频回退）
处理的正是"有进度但没声音"这一类问题——而这类问题的自动检测，在三个后端中的一个上是关着的。

**2. `音频输出已释放` 被判为就绪。** 输出已经释放恰恰意味着不可用。

**3. 两个判据不对称**：视频用 `contains("等待")`，音频用 `startsWith("等待")`。
没有理由不同，只是留了一个日后文案改动就会踩到的坑。

**根本问题不是这三条，而是它们的成因**：一个安全攸关的自动决策挂在本地化文案上。
今天没出事只是因为字符串恰好落在了对的一边。任何人调整诊断文案——或者这个 app 将来做多语言——
自动恢复的行为都会静默改变，而且没有任何测试会发现。

**建议**：在 `VideoEngine` 契约上增加结构化的输出状态，例如

```kotlin
enum class PlaybackOutputReadiness { Unknown, Waiting, Rendering, Released }
val videoReadiness: PlaybackOutputReadiness get() = PlaybackOutputReadiness.Unknown
val audioReadiness: PlaybackOutputReadiness get() = PlaybackOutputReadiness.Unknown
```

诊断文案由状态派生，而不是反过来。`Unknown`（MDK 的真实情况）应当**明确地**让"缺输出"类故障
不参与判定，而不是靠字符串巧合达到同样效果——两者行为相同，但一个是决策，一个是意外。

---

## 三、P0-B · 宽限窗口不会重置，会导致过早切换后端

`PlaybackRuntimeFaultDetector.kt:75,88`

```kotlin
val since = missingVideoSinceEpochMs ?: now.also { missingVideoSinceEpochMs = it }
if (now - since >= MISSING_OUTPUT_GRACE_MS) { … }
```

`missingVideoSinceEpochMs` / `missingAudioSinceEpochMs` 只在
`!playbackRequested || buffering || errorPresent || ended` 这一支里被清空（第 46–48 行）。
**条件自行恢复时不清空。**

按源码逐行复刻后的实测序列：

```
t=0s    视频未就绪，已播 4s      -> null           （开始计时，正确）
t=2s    视频恢复就绪             -> null
t=20s   一直正常                 -> null
t=22s   视频再次掉出             -> VideoOutputMissing   ← 应当重新计时 4 秒
```

第四次观察**立即**报故障，因为 `missingVideoSince` 还停在 t=0。
`MISSING_OUTPUT_GRACE_MS = 4_000` 想要的是"连续 4 秒缺输出"，实际变成了
"本次绑定内曾经缺过输出，且此刻又缺了"。

由于一次故障直接触发自动换后端，后果是：**一次短暂的输出抖动之后，任何再次瞬时掉帧
都会立刻把用户切到另一个播放内核**，而不是等 4 秒确认。

**修复**：在这两支的条件不成立时把对应时间戳清回 `null`。同理，
`firstFrameWaitSinceEpochMs` 在第 59 行的 `else` 分支里也没有清空。

---

## 四、P1 · 测试缺口正好落在最需要测试的地方

16 个模块中 5 个没有同名单测：

| 模块 | 行数 | 说明 |
| --- | --- | --- |
| **`PlaybackRuntimeFaultDetector`** | 115 | **零测试**。它是唯一决定"自动切换后端"的状态机，上面两个 P0 都在它周围 |
| **`PlaybackDiscImageInspector`** | 55 | 零测试。手写的大小写不敏感字节扫描 + 10 个标记，正是该被钉住的东西 |
| `PlaybackMediaProbeService` | 79 | 主要是接口/expect，缺测试可接受 |
| `PlaybackDeviceCapabilities` | 181 | commonTest 无，但有 `PlaybackDeviceCapabilitiesAndroidTest` |
| `PlaybackDiscNavigation` | 31 | 基本是数据类 |

前两个是真缺口。特别是故障检测器——`YCORE_VALIDATION_MATRIX.md` 把
"自动恢复成功率 ≥ 95%" 列为发布门槛，而触发这套恢复的那段逻辑一行测试都没有。
它的行为完全由 6 个时间常数和几个可变时间戳决定，是最适合单测、也最容易悄悄写错的形状
（P0-B 就是证据）。

---

## 五、P2 · 其余

**1. ISO 检测的内存与耗时** — `PlaybackDiscImageResolver.android.kt:38-49`
把整整 8 MiB 读进 `ByteArrayOutputStream` 再 `toByteArray()`，
即一次约 16 MiB 的瞬时分配；随后 `detectPlaybackDiscImageKind` 对 10 个标记
各做一次 O(n·m) 的逐字节扫描，最坏情况约 8000 万次外层迭代，每次还带一对函数调用。
在中端机上这会让原盘起播明显变慢。

改法很直接：按 64 KiB 分块边读边扫，块间保留 `maxMarkerLength - 1` 字节的重叠，
命中即停。峰值内存从 16 MiB 降到 64 KiB，绝大多数 ISO 在前几个块就能判定。

**2. 纯音频内容检测不到进度停滞** — `PlaybackRuntimeFaultDetector.kt:98`
的停滞判据要求 `videoReady`。`videoExpected == false` 的内容（纯音频轨、某些片头）
不会被 `videoReady` 覆盖，因而永远不会报 `PositionStalled`。

**3. 三个后端的契约覆盖度差距很大** — `VideoEngine` 有 83 个成员，
Exo 覆盖 33、Mpv 29、MDK 18。文档说明了 MDK 是独立兜底，能力弱是设计使然，
但目前"哪些能力在哪个后端可用"只能靠读三份实现推断，没有一处集中声明。
一张能力矩阵（哪怕只是一个测试）会让 P0-A 那类问题在写的时候就暴露。

---

## 六、发布层面：验证矩阵尚未执行

`YCORE_VALIDATION_MATRIX.md` 定义了设备通道（6 个 Android 版本 × 3 类 SoC × 6 种显示 × 4 类音频 × 5 种网络）、
媒体语料通道，以及 8 条发布门槛（崩溃率、恢复成功率、A/V 同步、丢帧、卡顿比、切换保真、功耗、8/24 小时长稳）。

仓库里**找不到任何一次执行记录**——没有结果表、没有语料清单、没有 P50/P95 数据。
文档自己写着 "CI cannot replace these measurements"，这句话目前是成立的，因为测量还没开始。

这不是缺陷，是**尚未开工的那部分工作**。而且它决定了一件事：
在这些门槛拿到数据之前，"稳定""HDR 兼容""更省电"这三类说法都还不能对外讲——
这正是那份矩阵写下来要防的事。

---

## 七、建议顺序

| 顺序 | 事项 | 理由 |
| --- | --- | --- |
| 1 | P0-B 宽限窗口重置（约 3 行） | 会造成用户可感知的无谓内核切换，改动最小 |
| 2 | `PlaybackRuntimeFaultDetector` 单测 | 先有测试，再动 P0-A，否则无法证明重构没改变行为 |
| 3 | P0-A 结构化输出状态 | 修掉 MDK 静默检测失效，并断开"文案 → 决策"的耦合 |
| 4 | `PlaybackDiscImageInspector` 单测 + 分块扫描 | 测试与性能一起做，同一处代码 |
| 5 | 后端能力矩阵 | 让第 3 项这类问题在编写期暴露 |
| 6 | 启动验证矩阵的设备通道 | 决定何时能对外做稳定性声明 |

前三项是同一件事的三步：**先钉住现有行为，再修正它，最后把它建立在可靠信号上。**
