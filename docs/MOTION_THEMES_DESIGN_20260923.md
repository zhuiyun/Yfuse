# 动效审查与动效主题设计（2026-09-23）

设计画布（8 个触点 × 5 套主题实时对比，含慢放与减弱动态效果切换）：https://claude.ai/artifact/V449oMBCzeLesHFMhkdaRN

本轮只做审查和设计，没有修改 Kotlin 代码。画布中的海报、影片画面都是占位色块。

## 一、现状审查

底子扎实：统一的 `Motion` 令牌，composeApp 中找不到 `tween(数字字面量)`；进度在绘制阶段读取，页面隐藏时停止时钟；手机端「减少动画」与系统关闭动画联动；弹窗终态绕过全部特效；可打断的交互用弹簧，过场用时长。问题集中在「组合」和「收敛」上。

| 级别 | 问题 | 依据 |
| --- | --- | --- |
| 优先 | 各选各的，没有统一语言。弹窗 43 款、加载 9 款、光粒 3 档分别在三个面板选择，导航、按压、列表、播放器控制栏不可选，任意组合彼此不呼应。开屏和光粒风格已收敛为一种，弹窗却仍在增加（9 月 8 日记录 38 款，现为 43 款）。 | `DialogAnimation.kt:23-72`、`LoadingAnimation.kt`、`Theme.kt:74`、`ThemePreferences.kt:108` |
| 优先 | TV 端几乎没有动效，也不跟随系统：只读应用内「减少动画」，没有并入 `platformAnimationsDisabled()`；路由、标签页、行切换都是直接切换；焦点缩放 4 个字面量（1.055 / 1.035 / 1.025 / 1.015）；海报 Crossfade 用 `tween()` 默认值。 | `TvApp.kt:78`、`TvHomeScreen.kt:293`、`TvTokens.kt:83-99` |
| 优先 | 默认曲线混入：约 12 处 tween 只传时长，于是用了 FastOutSlowIn 而不是 `Motion.Curve`。Toast 的淡入与位移用了两条不同曲线。播放器窗口 XML 用平台的 `fast_out_slow_in`。 | `Toast.kt:165-166`、`SkeletonHandoff.kt:93-94`、`Interaction.kt:341-362`、`PlayerRoot.kt:2743`、`PlaybackExperienceOverlay.kt:65,125` |
| 建议 | 相邻动画时长对不齐：搜索框形变 280ms，所在路由转场只有 180ms；共享海报 300ms，页面推进 280ms。 | `SearchDockMotion.kt`、`SharedMediaTransition.kt:93-107`、`OfficialNavDisplay.kt:196-266` |
| 建议 | 四套错峰入场各有时钟：`ArrivalReveal` 480ms + 0.07 步进，`SkeletonHandoff` 1000ms + 55ms，`SearchResultsHandoff` 1100ms + 55ms，`ContentHandoff` 150ms；`HANDOFF_ROW_MS` 与 `Motion.SEARCH_ROW_STAGGER` 都是 55，却各定义了一次。 | `SkeletonHandoff.kt:33-51` |
| 建议 | 错误和空状态直接硬切：`ErrorState` 和 `PageHint` 本身没有入场，只有接入 `contentHandoff` 的页面会淡入；媒体库、搜索、详情、首页直接切换。 | `PageStates.kt:49,76` |
| 建议 | 缩放与弹簧字面量散落：0.9（预测返回）、0.994、0.94、0.92、0.88、0.82、0.6；Tokens 之外唯一的弹簧是 `BurstIcon.kt:90` 的 ζ0.38。 | `OfficialNavDisplay.kt:229,268`、`PlayerChrome.kt:304`、`PlayerControls.kt:1520` |
| 建议 | 无入口代码与重复实现：`SplashChoreography.kt:46` 只映射 One / Still，另外 5 套开屏编排（Two / Aurora / Stardust / CloudDrop / CloudWell，共 1,481 行）已无入口；`Motion.ORB_COMET` 无引用。双端弹簧胶囊在 `DefaultTabMotion` 和 `SegmentIndicator` 各写一份，轮播自动播放循环在首页和媒体库各有一份，`DetailSections.kt:226` 重写了 `motionAwareAnimateContentSize`，`ServersTabScreen.kt:331` 直接调用 `animateItem`，绕过了 `motionAwareItem`。 | — |

## 二、方案：动效主题

不再往某一类里继续加款式，而是把 8 个触点打包成一套语言：页面推进、底栏切换、弹窗、按压反馈、列表入场、加载等待、完成确认、播放器控制栏。用户只在一处选择。现有动效整体保留为「经典」，作为默认值，老用户行为零变化。

弹簧参数与 Compose `spring(dampingRatio, stiffness)` 用的是同一模型（质量 1）。

| | 潮汐 Tide | 放映 Cinema | 焰霜 Ember & Frost | 弹跃 Kinetic | 静息 Calm |
| --- | --- | --- | --- | --- | --- |
| 气质 | 液态玻璃的延续：浮力、表面张力、涟漪 | 影院语汇：遮幅、对焦、放映光束、光圈；利落无回弹 | 取自水火 Logo：暖光到达，冷却为冰蓝落定 | 弹簧物理为主角：挤压、拉伸、过冲 | 只用透明度与 ≤8dp 位移；无遮罩、粒子、回弹 |
| 签名曲线 | `(.22, 1, .36, 1)` | `(.65, 0, .35, 1)` | `(.2, .8, .2, 1)` | `(.34, 1.56, .64, 1)` | `(.2, 0, 0, 1)` |
| 进入弹簧 | ζ0.72 · k380 | ζ0.9 · k600 | ζ0.8 · k420 | ζ0.55 · k360 | ζ1 · k700 |
| 按压弹簧 | ζ0.6 · k520 | ζ0.95 · k700 | ζ0.75 · k500 | ζ0.45 · k600 | ζ1 · k900 |
| 时长倍率 | 1.0× | 0.9× | 1.0× | 1.1× | 0.6× |
| 列表错峰 | 40ms | 50ms | 40ms | 45ms | 不错峰，整体淡入 |
| 光粒上限 | 轻柔 | 关闭（以光束、暗角代替） | 轻柔 · 暖冷双色 | 轻柔 | 关闭 |
| 定位 | 默认推荐 | 影视身份感最强 | 品牌表达 | 活泼取向 | TV / 低端机 / 省电自动档 |

### 各触点编排

| 触点 | 潮汐 | 放映 | 焰霜 | 弹跃 | 静息 |
| --- | --- | --- | --- | --- | --- |
| 页面推进 | 弧形水面升起：460ms，顶边椭圆裁切从底部涨满，底页缩至 0.95 并压暗 | 遮幅对焦：420ms，上下黑边收至 8%，新页 blur 8→0，黑边退回 | 暖光推入：380ms，右侧 30dp 推入，暖色蒙层随进度冷却，前沿一道冷暖光缝 | 弹簧推入：ζ0.62 k300，带过冲，底页视差后退 30% | 200ms 淡入 + 12dp 位移，返回 150ms |
| 底栏切换 | 液桥拉伸：520ms，前沿先到、后沿拖尾（复用 `LiquidTabMotion`） | 跟焦切换：胶囊匀速 360ms 不拉伸，内容失焦→对焦 | 色温落位：途中偏暖，落位冷却为冰蓝 | 弹跳落位：起跳压扁、落位过冲，图标 1.35× 弹回 | 胶囊原位淡换，内容交叉淡化 150ms |
| 弹窗 | 水面浮起：进 420 / 出 260，弧形顶边升起，落定时泛开涟漪（新增） | 放映光束：进 400 / 出 240，光束从触点投向面板，由虚到实（新增） | 余烬描边：暖光沿边框描一圈后冷却，内容随后显现（改造 Energy） | 弹跳落定：0.6→1.05→1（复用 Spring，加大过冲） | 进 200 / 出 150，透明度 + 8dp（复用 Lift） |
| 按压反馈 | 缩至 0.96，触点涟漪，ζ0.6 回弹 | 快门闪：亮度 0.75，松手轻闪回 1，不改尺寸 | 升温冷却：按下暖光，松手转冰蓝消散 | 挤压回弹：1.05 × 0.9，ζ0.45 摇两下 | 透明度 0.72，不缩放 |
| 列表入场 | 逐行上浮 16dp，ζ0.78 | 胶片推进：逐行从左向右划开 360ms | 逐行淡入并闪一次暖光 | 逐个 0.85→1 弹出 | 整体一次淡入 180ms |
| 加载等待 | 水位：波面横移、水位缓升缓降（新增） | 片头倒数：扇形扫过，3→2→1（新增） | 冷暖双焰（复用「水火相融」） | 弹跳小球：落地压扁、阴影同步（新增） | 细进度条往返 1.4s（新增） |
| 完成确认 | 注水：爱心自下而上注满 + 两圈涟漪 | 光圈聚焦：暗角收向按钮，点亮后打开 | 火星冷却：8 颗火星由暖转冷熄灭 | 果冻：0.6→1.3→晃两下落定 | 描边变实心 150ms |
| 播放器控制栏 | 浮力显隐：6dp 过冲，下栏晚 40ms | 遮幅入画：上下栏从边缘推入，中央键光圈打开 | 淡入 220ms，进度头暖光后冷却 | 从屏外弹入带过冲 | 显示 150ms / 隐藏 120ms |

### 通用约束

- 「减少动画」、系统关闭动画、路由不可见、窗口失焦、画中画时，一律按现有门控降级为瞬时切换。静息主题不能替代这一关闭档。
- 播放画面与字幕区域不绘制任何装饰，只允许控制层移动。
- 放映主题的 blur 依赖 `RenderEffect`（API 31+），低版本退化为淡入加缩放。
- 暗角、光束等全屏装饰只在转场期间存在（≤ 600ms），不做常驻动画。

## 三、实现结构

```kotlin
enum class MotionTheme(val label: String) {
    Classic("经典"), Tide("潮汐"), Cinema("放映"),
    EmberFrost("焰霜"), Kinetic("弹跃"), Calm("静息"),
}

@Immutable
data class MotionScheme(
    val curve: Easing,
    val enter: SpringParams,
    val press: SpringParams,
    val durationScale: Float,
    val route: RouteMotion,
    val tab: TabMotion,
    val dialog: DialogAnimation,
    val list: ListEntrance,
    val loading: LoadingAnimation,
    val confirm: ConfirmMotion,
    val chrome: ChromeMotion,
    val particleCap: ParticleLight,
)

val LocalMotionScheme = staticCompositionLocalOf { MotionScheme.Classic }
// 持久化键：appearance.motionTheme，按名称，只追加
```

建议顺序：

1. **先收口令牌**：修复上文默认曲线、字面量和重复实现问题；散落的时长改为读 `LocalMotionScheme`，「经典」取现值，行为零变化。TV 端同时并入 `platformAnimationsDisabled()`。
2. **设置页加一行「动效主题」**：弹窗动画和加载动画的选择器移入「高级 · 单独覆盖」，没有覆盖时跟随主题。
3. **先上静息和潮汐**：静息几乎不需要新代码，还可以作为 TV 与省电模式的自动档；潮汐大量复用液态玻璃的现有实现。
4. **再上放映、焰霜、弹跃**：每套只需新增 1–2 个弹窗样式和 1 个加载样式，其余触点只是参数不同。新增的弹窗样式追加到 `DialogAnimation` 末尾，保持持久化名称稳定。
5. **清理**：确认不再需要之后，移除无入口的 5 套开屏编排和 `Motion.ORB_COMET`。

## 验证计划

- JVM：每套 `MotionScheme` 在进度 0 / 1 时回到静止帧；减弱动效下所有触点时长为 0；新增的持久化名称可以往返读写。
- 真机：沿用 `MOTION_PERFORMANCE_20260908.md` 的 FrameMetrics 方法，对比经典与各主题的推进、弹窗、列表入场，放映主题需单独测量 blur 的开销。
- 没有取得同设备 release 前后对照数据之前，不宣称帧率改善。
