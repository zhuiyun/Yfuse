# 动效与交互体验审查（2026-09-24）

基线 `4531632`（1.0.85 / 247）。本轮只做审查：没有修改 Kotlin 代码，没有构建、安装，也没有测量帧率。

## 范围与方法

- **范围**：手机端（`composeApp` 的 commonMain 与 androidMain）、播放器 Activity、TV 端（`tvApp` 与 `composeApp/src/androidMain/kotlin/com/yfuse/tv/focus`）。HarmonyOS 移植只有源码接线、未构建未运行，投屏接收页没有动效，两者不在范围内。
- **方法**：按八条线分别走查——导航转场、加载与状态、按压/悬停/焦点、弹窗与 Toast、播放器、TV、全局一致性与可访问性、性能——再把每条结论回到源码复核。P0、P1 的证据都在当前 HEAD 逐行核对过。第三方行为（Decompose 对重复配置的检查）对照了 Maven Central 上 3.5.0 的源码包。
- **口径**：
  - 文中“掉帧”“耗时”都是按代码路径推断的，不是实测。在同一设备上用 release 包按第七节方案测量之前，不宣称帧率变化。
  - 标“静态推断”的条目需要真机确认。
  - 对比度数值按代码中的颜色模型估算。
- **与此前审查的关系**：逐条核实了 09-23《[动效审查与动效主题设计](MOTION_THEMES_DESIGN_20260923.md)》所列问题的现状（见第九节）。仍存在的并入本清单，已修复的不再重复。

**路径简写**：
- 设计系统：`composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/`
- 页面：同级的 `feature/<模块>/`
- 壳：同级的 `app/`
- `PlayerActivity.kt`、`PlayerRoot.kt`、`PlayerTransition.android.kt`、`PlayerActivityMotion.android.kt`、`AnimatedSplashApp.kt`、`MainActivity.kt`：都在 `composeApp/src/androidMain/kotlin/com/yfuse/` 下
- TV 页面：`tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/`

## 一、结论

1. **3 个 P0，都是交互路径上的硬故障，不是观感问题。**
   - 在首页和搜索里，同一个详情页第二次入栈会崩溃。
   - 调用方拒绝关闭时，弹窗会留下一个看不见的窗口，之后所有点击和返回都被吞掉。
   - TV 详情页的“更多”被挤成 0 宽，这个入口到不了。

   三项都是小改动。
2. **16 个 P1，集中在四处。**
   - **可访问性**：
     - 减少动画时，按压没有任何视觉反馈。
     - 播放器丢掉了三项无障碍偏好。
     - TalkBack 下，播放控制栏 5 秒后隐藏，而且很难唤回。
     - 加载和错误状态不播报。
     - 轮播无法暂停。
   - **1.0.85 新上线的播放器进出场**：
     - 只把“视频首帧”当作就绪：纯音频会一直显示“正在准备画面”，慢网提示被遮住。
     - 关闭后约 1.3–1.8 秒内不能操作。
   - **启动与刷新**：
     - 系统浅色、应用深色（默认值）时，冷启动会闪白。
     - 开屏期间的点击会落到看不见的首页上。
     - 刷新入场动画被打断后，首页货架海报可能停在透明状态。
   - **TV 焦点**：
     - 焦点恢复在重组时反复重跑。
     - 返回后焦点不回原位。
     - 系统“移除动画”不生效。
3. **底子是好的。**
   - 手机端的门控很完整：
     - 5 个无限过渡全部受门控。
     - 5 个自写帧循环里有 4 个受门控。
     - 全部 49 个 `AnimatedVisibility`/`AnimatedContent`/`Crossfade` 都显式传了 spec，并受减少动画控制。
   - 09-10、09-17 列出的性能项基本修完。
   - 没有发现频率超过 3 Hz 的闪烁。
4. **结构性问题在“收敛”。**
   - 动效还在继续增加：弹窗有 43 款；今天又新增 6 款播放器进出场，其编排里约有 135 处字面量时长。
   - 32 处 `tween` 没有传曲线。
   - 错峰入场有 4 套时钟；开关有 3 套实现；展开箭头有 4 种写法。
   - 负责守住这些规则的 `verifyDesignSystemUsage` 只扫描了约三分之一的源码。

   建议先收口令牌和自动检查，再推进 09-23 设计的“动效主题”。

## 二、五维评估

| 维度 | 评价 | 主要依据 |
| --- | --- | --- |
| 流畅度 | 中上 | 做得好：动画值大多在绘制阶段读取，隐藏页面会停表。<br>剩余风险：<br>• 滚动的每一帧，每张可见海报都查询一次屏幕几何（P1-8）<br>• 播放器 hold 阶段页面逐帧重绘，并每帧新建模糊（P2-33）<br>• 搜索输入时，可见结果行随每次按键重组（P2-37） |
| 一致性 | 中 | 做得好：路由、底栏、tab 的时长与 PUSH/POP 对齐。<br>问题：<br>• 32 处缺省曲线<br>• 错峰入场 4 套<br>• 弹窗关闭有“带退场”和“硬切”两种观感<br>• 播放器面板两层动画叠加<br>• TV 与手机是两套焦点语言 |
| 响应速度 | 中 | 做得好：按下 90 ms，松手走弹簧，可打断。<br>问题：<br>• 关闭播放器后 1.3–1.8 s 不可操作<br>• 弹窗退场期间吞输入<br>• 换季和网格筛选没有忙碌态<br>• 再次启动仍有 1.2 s 开屏 |
| 可访问性 | 中下 | 做得好：手机端已并入系统“移除动画”。<br>问题：<br>• 减少动画时按压零反馈<br>• 播放器丢失三项无障碍偏好<br>• TalkBack 下控制栏定时隐藏<br>• 全应用只有 3 处 `liveRegion`、1 处 `heading()`<br>• 轮播不可暂停<br>• TV 不跟随系统设置 |
| 视觉协调性 | 中上 | 做得好：玻璃材质与深浅色的衔接很细致。<br>问题：<br>• 冷启动闪白<br>• 推进时两页同时半透明，露出底层<br>• 同一屏出现多种加载指示<br>• “查看全部”“全部剧集”覆盖页硬切 |

## 三、现状盘点

### 3.1 页面转场（手机）

| 转场 | 参数（ms；曲线均为 `Motion.Curve`，另注除外） | 位置 |
| --- | --- | --- |
| 推进 | 入场：淡入 280 + 自右 30 dp<br>出场：淡出 120 + 左移 15 dp | `OfficialNavDisplay.kt:273-281` |
| 返回 | 淡入淡出 260，位移 ∓22 dp | `OfficialNavDisplay.kt:259-271` |
| 预测性返回 | 同返回；退出页缩到 0.9，圆角 24 dp | `OfficialNavDisplay.kt:155-165,244-246` |
| 切 tab | 入场：淡入 + 0.97→1，共 180<br>出场：120，缩到 0.994 | `OfficialNavDisplay.kt:195-206` |
| 搜索 | 打开：0.97 + 自下 14 dp，180<br>关闭：120<br>搜索框形变：280 | `OfficialNavDisplay.kt:212-236`、`SearchDockMotion.kt:44-63` |
| 共享海报 | 300；按固定延时 420 清除 key | `SharedMediaTransition.kt:99-106`、`OfficialNavDisplay.kt:80-84` |
| 底栏 | 入场 260 / 出场 280 | `App.kt:396-412` |
| tab 胶囊 | 双端弹簧 ζ0.78 / ζ0.88<br>液态：ζ0.92，k300/155，扫光 90+520 | `Tokens.kt:711-744`、`LiquidTabMotion.kt:66-88` |
| 开屏 → 首页 | 开屏 1080+120，首次与再次启动相同<br>首页：淡入 + 0.98→1，280 | `AnimatedSplashApp.kt:122-136,420-423` |
| 潮涌（冷启动落在库） | 约 1445，24 dp 三次阻尼摆，底栏也跟着摆 | `LaunchWave.kt:45-69`、`App.kt:420` |
| 覆盖页（全部剧集、查看全部） | 无进出场 | `DetailScreen.kt:1042`、`HomeScreen.kt:558`、`BackOverlay.kt` |

减少动画时以上全部瞬切，只有开屏保留 260 ms 静帧。

### 3.2 加载与状态交接

| 页面 | 首次加载 | 刷新 | 错误 / 空 | 内容到达 |
| --- | --- | --- | --- | --- |
| 首页 | 英雄区占位 + 2 条骨架行 + 页面扫光 | 下拉刷新；完成后海报重新升起，不分成败 | 列表内 `ErrorState`；推荐和日历可能各出一块 | 错峰入场；海报 180，英雄区 400 + 模糊 |
| 媒体库首页 | 页眉 + 1 条骨架 | 下拉刷新；完成后扫光 | `when` 硬切到 `ErrorState` | 英雄区插入，整页下推 |
| 媒体库网格 | 骨架交接 + 12 格骨架 | 排序、筛选时旧网格不动，没有指示 | `ErrorState` / `PageHint` | 错峰入场；分页底部只有文字 |
| 统一媒体库 | 纯文字 | 文字按钮 | 红字 + 重试 | 无动效 |
| 详情 | 骨架 + heroBloom（6 s 周期） | — | `ErrorState` | 错峰 + 英雄区 1.08→1 + 400；换季无反馈 |
| 搜索 | 输入框 orb + 字段、图标、整页三种脉冲 + 骨架 + 扫光 | 只有重试 | `ErrorState` 外再包一层 150 交接；空结果没有操作 | 每行 480，步进 55，上浮 18 dp；落地光带 1100 |
| 服务器 | 骨架 | 下拉 orb + 顶栏旋转图标 + 结果图标 + Toast | 无页面级错误 | 整页 `contentHandoff`，包括页头 |
| 追剧中心 | 骨架，整列 `contentHandoff` | 顶栏 orb | `ErrorState` / `PageHint` | 整列交接；切换分区也会重播 |
| 下载 / 个人中心 | 文字 / 本地数据 | — | 文字，没有重试 | `animateItem` / 无 |
| TV | 静止圆点 + 文字 | — | 硬切 | 图片硬切出现 |

### 3.3 按压、悬停、焦点（手机）

| 控件 | 按下 | 松手 / 状态 | 触感 | 减少动画时 |
| --- | --- | --- | --- | --- |
| 玻璃按钮 `YfButton` / `OverlayButton` | 缩到 0.97，tween 90 | 弹簧 ζ0.6 | 主要、危险按钮 Confirm | 无任何反馈 |
| 海报卡（网格、货架） | 整卡 0.97 | 同上 | 长按震两次 | 无任何反馈 |
| 继续观看卡 | 0.97 + 7° 倾斜（在裁剪框内） | 同上 | 长按震两次 | 无任何反馈 |
| 列表行 / 设置行 | 全宽 0.97，无底色 | 弹簧 | 无 | 无任何反馈 |
| Chip `YfChip` | 0.97 | 颜色 settle | Select | 颜色瞬切 |
| 开关 `SwitchRow` | 整行 0.97 | 旋钮、轨道 settle | Select | 瞬切 |
| 私有开关（更多操作、播出提醒） | 0.97 | 无动画 | 无 / Confirm | — |
| 分段控件 | 0.97 | 双端弹簧 | Select | 瞬切 |
| 滑块 | 无按下态 | 旋钮用弹簧追手指 | 跨过刻度时 Select | 瞬切 |
| 底栏 tab | 0.96 | 图标放大到 1.10 再弹回 + 液态胶囊 | Select（拖动切换无触感） | 瞬切 |
| 详情主播放 | 0.985 + 8% 底色 | 弹簧 | 无 | 保留底色 |
| 收藏（BurstIcon） | 0.97 | 0.6→1，ζ0.38 | Confirm | 瞬切 |
| 悬停 / 键盘焦点 | 1.02 + 焦点环（悬停时透明度 56%） | tween 120 | — | 保留焦点环，不缩放 |

依据：`Interaction.kt:127-312`、`SoftFeedback.kt:78-108`、`BurstIcon.kt:86-99`、`SelectionControls.kt`。

### 3.4 弹窗、面板与 Toast

- **弹窗**：
  - 共 43 款，默认“柔和浮起”（Lift）。
  - 进入 360–420 ms（`Motion.Dialog.EnterCurve`），退出 240–300 ms（`ExitCurve`）。
  - 遮罩与面板共用一个进度；进入途中关闭时，按剩余距离缩短退场；重复关闭只回调一次。
  - 两端进度绕过全部特效；减少动画时直接到终态（`Dialogs.kt:313-345`）。
- **拖拽关闭**：拖过 96 dp，或移动 ≥17 dp 且速度 >864 dp/s 即关闭；松手回弹用 `Motion.settle`（`DialogDragMotion.kt:32-37`）。
- **播放器面板**：
  - 设置、音轨、倍速：在所选弹窗动画外再套一层 180 ms 淡入 + 位移。
  - 聊天、弹幕搜索：抽屉弹簧外同样再套 180 ms。
  - 选集：只有外层 180 ms。
- **Toast**：
  - 淡入是 `fadeIn(tween(d))`，用的是缺省曲线；位移是 `slideInVertically(tween(d, Motion.Curve))`。
  - 固定显示 2600 ms，Polite 播报（`Toast.kt:45,165-166,198`）。

### 3.5 播放器

| 样式 | 页面离场 | 画面最早可见\* | 关闭到可操作\*\* |
| --- | --- | --- | --- |
| 转身（默认） | 压暗 220；英雄区缩 4%，圆角 22 dp | ≥940 ms | ≈1.5 s |
| 开幕 | 光圈收成光点 440；等转到横屏再开闸 | ≥880 ms，最晚约 1.9 s | ≈1.6 s |
| 玻璃舱 | 页面缩到 0.94，模糊 4 dp | ≥1020 ms | ≈1.4 s |
| 推近 | 英雄区放大到铺满，340 | ≥1280 ms | ≈1.7 s |
| 潮汐 | API 33+ 用 AGSL 波纹，更低版本整页位移 | ≥1140 ms | ≈1.5 s |
| 虚焦 | 英雄区模糊 26 dp | ≥1280 ms | ≈1.8 s |
| 无转场（非衔接入口、TV） | — | 就绪即可见 | ≈0.25 s |
| 减少动画 / 系统关闭动画 | 窗口 0 ms 硬切 | 就绪即可见 | 即时 |

\* max(视频就绪, 落点) + 40 ms。\*\* 截帧（≤160 ms）+ 退出半程 + 页面返程 + 40 ms。数据来自 `PlayerHandoffGeometry.kt:300-306` 与 `res/anim/player_*.xml`。

控制层时序：
- 显隐 180 ms，下栏晚 40 ms 入场。
- 自动隐藏 5 s（经 `calculateRecommendedTimeoutMillis`）。
- 手势提示条：120 ms 淡入 + 0.88→1 弹簧，1.6 s 后消失。
- 双击快进：触点处 420 ms 光环。
- 进度条：位置在布局和绘制阶段读取，跟手。
- 缓冲：播放键 250 ms 后换成转圈；顶部状态胶囊 0 ms。
- 下一集卡：最后 10 s 出现。

### 3.6 TV 焦点

| 元素 | 缩放 | 描边 | 底板 / 文字 | 时钟 |
| --- | --- | --- | --- | --- |
| 海报卡 | 1.055 | 1 dp → 3 dp 白 | card2 → card3 | 临界阻尼弹簧 k1500，约 170 ms 落定 |
| 动作按钮 | 1.035 | 同上 | 获焦变纯白、文字变黑（组合期硬切） | 缩放走弹簧，填充瞬切 |
| 导航项 | 1.025 | 同上 | 获焦白 96%，选中白 12%（硬切） | 同上 |
| 设置行 | 1.015 | 同上 | card2 → card3；图标强调色 → 白（硬切） | 同上 |
| 嵌入的手机控件（统一库、个人清单按钮） | 1.02 | 2 dp 强调色环 | 11–13 sp 文字；获焦即发光粒 | 手机令牌 |

4 个缩放值写死在调用处：`TvUiComponents.kt:178,307`、`TvApp.kt:224`、`TvSettingsScaffold.kt:159`。路由、tab、设置子页全部硬切。

## 四、优化项清单

**优先级口径**：
- **P0**：崩溃、卡死、输入被吞、入口不可达。立即修。
- **P1**：高频路径上用户能感知的问题，或可访问性缺口。下一个版本内修。
- **P2**：一致性问题、低频页面、潜在性能风险。本季内分批处理。
- **P3**：打磨与清理。

**工作量**：S 约半天以内；M 约 1–3 天；L 约一周以上。

### P0

#### P0-1 首页、搜索里同一详情页第二次入栈会崩溃

**问题**

`HomeTabComponent.kt:134,198,224,262` 和 `SearchComponent.kt:169,185` 用的是 `navigation.push(Config.Detail(...))`，而 `Config.Detail` 是 data class（`HomeTabComponent.kt:58-61`）。Decompose 3.5.0 的 `ChildrenNavigator` 在没有开启 `duplicateConfigurationsEnabled` 时会执行 `check(...) { "Configurations must be unique" }`，仓库没有开启这个开关。下面三条路径都会抛 `IllegalStateException`：
- 详情 A → 相关推荐 B → 在 B 的相关推荐里再点 A。
- 详情 A 还在首页栈里时，从播出提醒再次打开 A（`RootComponent.kt:262-268` → `HomeTabComponent.openCalendarItem`，`:130-135`）。
- 推进的 280 ms 内连点同一张海报：第二下会落在正在退出的页面上。这一条置信度中。

TV 共用同一套 `RootComponent` 栈，同样受影响。媒体库 tab 已经改用 `pushToFront`，注释也写明了原因（`LibraryComponent.kt:120-137`）。

**改进**
- 首页、搜索的 Detail / Info / Calendar 入栈与媒体库保持一致，改为 `pushToFront`；栈顶相同时也可以用 `pushNew`。
- 补一个 A→B→A 的 JVM 测试。
- 正在退出的页面在 `PointerEventPass.Initial` 阶段消费指针。

工作量 S｜置信度 高（代码路径 + 库源码）

#### P0-2 调用方拒绝关闭时，弹窗留下隐形窗口，吞掉之后所有输入

**问题**

`GlassDialog` 收到关闭请求时只把 `leaving` 置为 true，退场结束后调用一次 `onDismiss`，之后再也不复位（`Dialogs.kt:131-143`、`:313-345`）。它默认调用方会在 `onDismiss` 里同步移除弹窗。有两处调用方没有这样做：
- **元数据编辑**：`MetadataEditorDialog.kt:91` 写的是 `GlassDialog(onDismiss = { if (!busy) onDismiss() })`。弹窗一打开就开始加载，`busy` 为 true（`:83-89`）；保存时也会 busy。这时点遮罩、按返回或下拉，退场照常播完，但 `onDismiss` 被忽略。弹窗停在进度 0：面板和遮罩全透明，Dialog 窗口却还在，全屏的点按捕获层（`Dialogs.kt:224`）继续拦截输入。之后再点、再按返回，都只是把已经为 true 的 `leaving` 再设一次，什么也不会发生。页面实际卡死，只能从多任务里结束应用。
- **接力确认框**：`DeviceHandoffScreen.kt:107-114` 在点遮罩或返回时调用异步的 `controller.reject(request)`（`HandoffController.kt:315-327`）。请求失败时 `incoming` 不会清空，同样卡在隐形窗口上，而且看不到失败提示。

元数据编辑是低频入口，但一旦触发就无法恢复。

**改进**
- `MetadataEditorDialog` 改成 `GlassDialog(onDismiss = onDismiss, dismissEnabled = !busy)`，`EpisodeProgressManager.kt:73` 已经是这种写法。接力确认框在请求期间设 `dismissEnabled = false`，失败时复位并显示错误。
- `GlassDialog` 自身加兜底：回调 `onDismiss` 后的下一帧如果弹窗仍在组合中，就复位 `leaving` 并重放入场，避免其他调用方再造出隐形窗口。
- 补一条仪器测试，覆盖“`onDismiss` 不移除弹窗”的情况。

工作量 S｜置信度 高（静态推断，建议真机复现一次）

#### P0-3 TV 详情页的“更多”被挤成 0 宽，入口不可达

**问题**
- **尺寸溢出**：英雄区列宽 650 dp，减去左安全边距 48 dp 后只剩 602 dp（`TvDetailScreen.kt:444-448`、`TvUiComponents.kt:82`）。次级操作行有 5 个定宽按钮：142、150、168、160、118 dp，再加 4 个 11 dp 间距，共 782 dp（`TvDetailScreen.kt:531-590`）。“下载”按钮的文案永不为空，所以它总会显示（`:127-134`）。
- **结果**：Row 按剩余宽度依次测量，“下载”只剩约 109 dp，“更多”为 0。“更多”里的播出日历、追剧、进度管理、合集、刷新元数据因此都到不了。0 宽节点仍可能拿到焦点，而屏幕上看不到任何焦点指示（这一点置信度中）。
- **文字截断**：按钮文字是 `maxLines = 1` 且没有省略号（`TvUiComponents.kt:351`），“服务器已收藏”“下载失败”会被截成“服务器”“下载”，状态会被读错。`:529-530` 的注释说明，作者本来就想避免这种截断。

**改进**
- 次级操作改用 `LazyRow`，或者把两个“服务器…”按钮并入“更多”。
- `TvActionButton` 改为按内容定宽，并加 `TextOverflow.Ellipsis`。
- 加一条 UI 测试，断言所有可聚焦节点的宽度都大于 0。
- 同类问题：媒体库服务器选择器有 5 台服务器时，第 5 个按钮也是 0 宽（`TvLibraryScreens.kt:202-217`）。

工作量 S｜置信度 尺寸计算高

### P1

#### P1-1 刷新入场被打断后，首页货架海报停在透明状态

**问题**
- **机制**：`rememberRefreshReveal`（`ArrivalReveal.kt:86-98`）用 `LaunchedEffect(refreshing, moving)` 播放 480 ms 的入场。`moving` 取自 `LocalRouteVisible`，推入新页面的那一帧就会翻转（`OfficialNavDisplay.kt:105-106`）。
- **触发**：刷新落地后 480 ms 内点开任何海报或切换 tab，协程被取消，进度停在中间。回到首页时 `landed` 已经是 false，进度会一直停在那里。
- **后果**：首页 TMDB 货架的每张海报都用 `arrival.item(index)`（`HomeScreen.kt:512,1594`）。按 `REVEAL_STEP = 0.07`、`REVEAL_MAX_INDEX = 5` 计算，进度停在 0.35 以下时，第 6 张起透明度为 0：看不见，却仍然可以点击。要等下一次刷新才恢复。
- **附带**：刷新失败或内容没有变化时，也会重播整段入场。

**改进**
- 在 effect 里遇到 `!moving` 或被取消时执行 `snapTo(1f)`，写成 `try { … } finally { progress.snapTo(1f) }`。
- 改为 `rememberRefreshReveal(refreshing, revision)`，只在内容真的变化时重播。
- 补一条“入场途中路由变为不可见”的 JVM 测试。
- 同一文件的 `attentionSweep` 也加上 finally。

工作量 S｜置信度 高（代码路径）；需要特定时序才会触发，建议真机复现

#### P1-2 减少动画时，按压没有任何视觉反馈

**问题**
- `pressable` 关掉了涟漪（`indication = null`，`Interaction.kt:278,289`），按压反馈只靠缩放。
- 减少动画时缩放恒为 1（`pressScaleTarget`，`:299-312`），光粒也关闭。
- 结果是约 180 处控件（按钮、海报、列表行、图标键）按下时没有任何“已按到”的确认；焦点环只在键盘或鼠标操作时出现。
- 系统“移除动画”也并入了这个开关（`App.kt:181-200`），所以受影响的用户比只打开应用内开关的更多。
- 减少动画要去掉的是运动，不是反馈。但 `MotionAccessibilityPolicyTest.kt:24-36` 把现在的行为固化成了测试。

**改进**
- 在 `pressable` 现有的 `drawWithCache`（`:241-270`）里，沿 `focusShape` 叠一层按下状态层，颜色取 `palette.text` 的 8–12%；减少动画时保留这一层，只是瞬切。也可以采用 09-23“静息”方案里的透明度 0.72。
- `softSelectionSurface`（`SoftFeedback.kt:78-108`）已经是状态层做法，可以直接并入。
- 同时更新上述测试的期望。

工作量 M｜置信度 高

#### P1-3 播放器丢掉三项无障碍偏好

**问题**

两处 `setContent`（`PlayerActivity.kt:505`、`:795`）只传了 `AccessibilityOptions(reduceMotion = reduceMotion || systemMotionOff)`。`reduceTransparency`、`largeText`、`reduceMotionByUser` 和 `glassStyle` 都没有传，对照 `App.kt:196-205`。结果：
- **减少透明效果**在播放器里失效：控制层 38 处 `.glass(` 和 5 个 `GlassDialog` 仍是半透明模糊。
- **大号文字**（×1.12）在播放器里失效。
- **拖拽关闭**：`reduceMotionByUser` 缺省等于合并后的值（`Theme.kt:222`），所以系统关闭动画时，播放器面板的拖拽关闭被禁用。这与 `PlayerPanel.kt:282-291` 的设计说明相反。

**改进**
- 抽一个 `rememberAccessibilityOptions(prefs)`，App、播放器两处和 TV 共用。
- 加一条测试，比对三处构造出的结果一致。

工作量 S｜置信度 高

#### P1-4 TalkBack 下，播放控制栏 5 秒后隐藏，而且很难唤回

**问题**
- **计时**：自动隐藏用 `calculateRecommendedTimeoutMillis(5000)`（`PlayerControls.kt:539-548`）。只有 Android 9 及以下在开启触摸浏览时，这个函数才返回无限。Android 10 起它只看系统的“操作时长”设置，默认值下仍是 5 秒。
- **唤回**：隐藏后整层被 `clearAndSetSemantics {}` 清空（`PlayerChromeTransition.kt:112-118`）。视频区的点按捕获层只有 `pointerInput`，没有任何语义（`PlayerControls.kt:685-697`）。读屏用户找不到可聚焦的节点来唤回控件。
- **下一集**：下一集卡片 10 秒后自动连播，既不播报，也没有倒计时语义（`PlayerNextUp.kt:98-141`）。

**改进**
- `isTouchExplorationEnabled` 时不自动隐藏。
- 捕获层加 `contentDescription` 和 `onClick(label = "显示播放控件")`。
- 下一集卡片出现时 Polite 播报，并提供“N 秒后播放”的 `stateDescription`。
- 如果真机确认无法唤回，升为 P0。

工作量 S–M｜置信度 计时高，“唤不回”中

#### P1-5 播放器进场转场只认视频首帧

**问题**

进场替身层的就绪条件是 `state.error != null || effectiveVideoReadiness == Rendering`（`PlayerRoot.kt:2842-2848`），没有续播层那样的纯音频豁免（`:2832-2836`）。这个转场默认开启（“转身”，`ThemePreferences.kt:183-184`），设置里也没有“关闭”选项。于是：
- **纯音频永远不就绪**：替身海报和“正在准备画面”一直盖着，帧循环整场不停（`PlayerTransition.android.kt:177-185`、`:334-338`）。
- **视频慢**：替身盖住画面期间，续播层被屏蔽（`PlayerRoot.kt:2828`）。1.0.85 新增的“网速低于片源码率”提示（`:2709-2735`）在主要入口看不到。
- **视频快**：画面仍要等到固定落点（880–1280 ms）才露出，而起播并不等转场，所以声音先于画面。此时控制栏透明度为 0，但仍然可以点，也仍在无障碍树里。

**改进**
- 就绪条件并入音频判定。
- 到落点仍未就绪时 settle，把替身淡出交给 `PlaybackContinuityOverlay`，让慢网提示可见。
- 视频早就绪时压缩剩余编排，或者把 `playWhenReady` 推迟到 `handoffAt`。
- 设置里增加“无（标准淡入）”。

工作量 M｜置信度 时序高，体感中

#### P1-6 关闭播放器后约 1.3–1.8 秒不能操作

**问题**
- 页面侧的 `playerHandoffStage` 只要阶段不是 Idle，就在 `PointerEventPass.Initial` 阶段消费全部触摸（`PlayerHandoffStage.kt:107-113`），包括返程（Returning）和释放（Releasing）两个阶段。
- 关闭链路 = 截帧（≤160 ms）+ 退出半程（440–920 ms）+ 页面返程（520–900 ms），整段都点不动。
- 注释给的理由是防止二次启动，但这只对离场阶段成立。

**改进**
- 返程、释放阶段不拦截触摸。
- 退出半程压到 `Motion.CONTINUITY_EXIT`（320 ms）附近，并与页面返程并行。
- 截帧直接用 `AmbientFrameSampler` 最近一次的采样，省掉等待。

工作量 S｜置信度 高

#### P1-7 冷启动：闪白、开屏期间点击穿透

**问题**
- **闪白**：
  - 开屏开启时，窗口底色按系统主题取色（`MainActivity.kt:111-117`，`launchWindowDarkMode` 见 `AnimatedSplashApp.kt:354-358`）。
  - 开屏自己在 300 ms 内渐变到应用主题色；开屏移除后，应用层从透明度 0 开始淡入（`AnimatedSplashApp.kt:122-136,148-156`）。
  - 应用默认是深色（`ThemePreferences.kt:74`），开屏默认开启（`:136`）。
  - 所以在很常见的“系统浅色 + 应用深色”组合下，每次冷启动：开屏最后一帧是深色，下一帧露出浅色窗口，再淡回深色。
- **点击穿透**：
  - 应用在开屏下面已经组合并布局，只是透明（`:141-160`）；开屏层只有 `drawBehind`，不消费指针（`:244-253`）。
  - 约 1.2 秒的开屏期间点屏幕，会点中看不见的英雄区按钮或底栏。
  - 应用内的 `GlassDialog` 是独立窗口，不受开屏推迟：冷启动时的“回到上次的一起看房间？”（`App.kt:479`）和邀请面板（`App.kt:505`）会盖在品牌动画上面。
  - 开屏也不能点按跳过。

**改进**
- 开屏结束时，把窗口背景改成应用主题色；或者在应用层下面垫一层 `splashBackground(dark)`，直到淡入完成。
- 开屏层消费全部指针，并把点按当作“跳过”。
- 应用内弹窗推迟到开屏结束后再显示。

工作量 S｜置信度 高（闪白幅度建议录屏确认）

#### P1-8 海报在滚动的每一帧查询屏幕几何

**问题**
- 为了给播放器转场找起点，所有带 key 的海报都挂了 `playerArtworkSource`（`Poster.kt:300`）。
- 它在 `onGloballyPositioned` 里算两次坐标、过滤 URL，并调用 `screen.current()`（`PlayerArtworkOrigin.kt:101-117`）。
- `screen.current()` 没有缓存，每次都读 `display.rotation`、`maximumWindowMetrics`（API 30+）或 `getRealSize`（API 29 及以下），以及 `getLocationOnScreen`（`PlayerHandoff.android.kt:20-48`）。
- 列表滚动、轮播翻页的每一帧，每张可见海报都会触发一次。按 AOSP 实现，API 30–32 取窗口 insets、API 29 及以下取 DisplayInfo，都是主线程上的同步 Binder 调用（调用次数需要用 Perfetto 确认）。
- 实际只有英雄区和详情播放键会发起转场。货架和网格里的注册纯属开销，还造成了同 key 冲突（P2-34）。

**改进**
- 只在英雄区和播放键上注册。
- 布局回调只记录 bounds，或者用 Modifier.Node 持有坐标句柄；等 `playerArtworkOnClick` 时再算屏幕几何。
- `ScreenGeometry` 按 View 和 Configuration 缓存。

工作量 S–M｜置信度 每帧回调高，Binder 成本中

#### P1-9 TV 焦点恢复反复重跑，返回后不回原位，操作后焦点丢失

**问题**
- **反复重跑**：
  - `TvRestoreRouteFocusEffect` 在组合期读取“最后一次焦点”，并据此构造恢复请求（`TvUiComponents.kt:476-512`）；请求一变，`RestoreTvFocusEffect` 就重新滚动并 `requestFocus`（`TvFocusCompose.kt:127-140`）。
  - 焦点移动过、页面又因为别的原因重组时（网格加载下一页、首页轮播 8 秒换页、下载进度每 512 KB 更新一次），当前行会被 `scrollToItem(index, 0)` 瞬移到行首。
  - 英雄区换页时，兜底逻辑还会把焦点从导航栏拽回“播放”。
- **返回后不回原位**：
  - 设置子页与根页共用 `settings` 路由的焦点记录（`TvSettingsScreen.kt:48-56`）。
  - 详情 A → 相关详情 B → 返回时，B 的焦点记录覆盖了 A 的。
  - 二级页用裸 `when` 切换，不保存状态（`TvApp.kt:381-403`）。
  - `FocusRepository.lastInSection` 已经实现，但生产代码没有调用。
- **操作后焦点丢失**：下载页的“暂停”和“继续”写在两个分支里，删除后节点直接消失（`TvDownloadsScreen.kt:141-172`）；弹幕屏蔽词删除也一样（`TvDanmakuWatchScreens.kt:172`）。这两页都没有接焦点恢复，操作后屏幕上看不到焦点。

**改进**
- 只在“进入页面”时恢复，用导航 entry 计数作 key；本区已经有焦点时跳过。
- 目标已在可见区时不滚动；需要滚动时使用真实的滚动偏移。
- 焦点锚点按页面实例区分，例如 `detail:$itemId`。
- 二级页用 `rememberSaveableStateHolder` 保存状态。
- 暂停和继续做成同一位置、同一 ID 的按钮，只换文案；删除后把焦点交给相邻项。

工作量 M｜置信度 中高（静态推断，需要在电视上确认）

#### P1-10 TV D-pad 方向逻辑缺陷

**问题**
- 搜索结果按左回导航栏的判断写死为 `index % 6 == 0`（`TvSearchScreen.kt:316`）。但网格是 `GridCells.Adaptive(142.dp)`，按标准 960 dp 宽实际只有 4 列。结果：第 3 列的部分结果按左会直接跳到导航栏；第 1 列反而靠几何搜索，落到任意一个导航项上。
- 在未选中的导航项上按右，按键被消费，却没有任何动作（`TvApp.kt:228-240`）。

**改进**
- 在内容容器的 `focusGroup` 上用 `focusProperties { exit = … }` 把左键出口指向导航栏，删掉取模判断。
- 在未选中的导航项上按右时，先切换 tab，或者不消费这个按键。

工作量 S｜置信度 高

#### P1-11 TV 不跟随系统“移除动画”

**问题**

`TvApp.kt:90-94` 只传了用户开关，没有并入 `platformAnimationsDisabled()`。09-23 已经列出，至今未修。系统关闭动画后：
- 首页轮播仍每 8 秒换页（`TvHomeScreen.kt:78-83`）。
- 焦点的 1.055 缩放在一帧内跳变。
- 共享的 `pressable` 控件仍会发光粒。

**改进**：照搬手机写法，与 P1-3 共用同一个构造函数，并传 `reduceMotionByUser`。

工作量 S｜置信度 高

#### P1-12 加载、错误、状态提示对读屏静默

**问题**
- 全应用只有 3 处 `liveRegion`：`Toast.kt:198`、`PlayerScreen.kt:144`、`PlayerControls.kt:1547`。
- 下面这些都不播报：`ErrorState` 和 `PageHint`（`PageStates.kt:48-113`）、续播提示与“网速低于片源”提示（`PlaybackExperienceOverlay.kt:108,180`）、“已续播”（`PlayerResumeNotice.kt:92`）、自动跳过倒计时（`PlayerChromeRefined.kt:686`）、“一起看 · 重连中”（`ActivityStatusCapsule.kt:115`）。
- 骨架的“正在加载”挂在一个非叶子节点上，而骨架层自己又用 `clearAndSetSemantics` 清空了子树（`SkeletonHandoff.kt:84-97`），读屏很难聚焦到它。
- 首页和媒体库首次加载用的 `SkeletonBlock` 没有语义。
- 下拉刷新没有 `CustomAccessibilityAction`，只能靠手势触发。

**改进**
- 加一个 `Modifier.statusAnnouncement(stableText)`，只给稳定的阶段文案设 Polite；缓冲秒数这类跳动的文字放到不播报的节点上。
- `ErrorState` 默认 Assertive，`PageHint` 默认 Polite。
- 骨架层改为 `clearAndSetSemantics { contentDescription = "正在加载"; progressBarRangeInfo = Indeterminate; liveRegion = Polite }`。
- 下拉刷新容器补一个“刷新”自定义动作。

工作量 S–M｜置信度 缺失本身高，聚焦行为中

#### P1-13 操作后没有响应反馈：换季、网格排序与筛选

**问题**
- **详情页换季**：
  - 执行顺序是先发 `SeasonsLoaded(新季)`，再发 `EpisodesLoading`（`DetailExecutor.kt:1146-1148`）。
  - 手机端没有任何界面读 `episodesLoading`，它只出现在 `DetailReducer` 和状态契约里；`DetailScreen.kt:624` 只判断 `episodes.isNotEmpty()`。
  - 请求期间，新季名下显示的是旧季剧集，而且还能点。TV 端已经处理（`TvDetailScreen.kt:200`）。
- **媒体库网格**：
  - 切换排序、类型、分辨率后，旧 items 保留（`LibraryGridStore.kt:283-309,609`）。
  - 骨架只在 `loadedCount == 0` 时出现（`LibraryGridScreen.kt:238`），没有别的忙碌指示。
  - 在慢服务器上像是没点中；结果到达后也不回到顶部。

**改进**
- **换季**：`EpisodeSection` 接入 `episodesLoading`：旧列表降低透明度并禁止点击，季名胶囊加 `waitingPulse`，数据到达后用 `contentHandoff(selectedSeasonId)` 交接。
- **网格**：在 `loading && loadedCount > 0` 时，排序胶囊和筛选行加 `waitingPulse`，网格降到 0.6 透明度；结果到达后调用 `motionAwareScrollToItem(0)`。

工作量 S｜置信度 高

#### P1-14 轮播无法暂停，读屏时仍每 6 秒翻页

**问题**
- 首页和媒体库各有一份 `while (true) { delay(6_000); animateScrollToPage(...) }`（`HomeScreen.kt:630-653`、`LibraryHomeScreen.kt:318-351`）。
- 只有手指按住、拖动、路由不可见或减少动画时才会停。
- “按住”靠 `awaitFirstDown` 判断（`CarouselEffects.kt:59-72`），而 TalkBack 的触摸浏览不产生按下事件，所以读屏焦点停在轮播上时，页面照样翻走。
- 暂停键已被移除，理由是“每次交互都会自己暂停”（`Indicators.kt:46-48`），但这个理由不覆盖读屏用户；全仓也没有检测读屏状态。
- WCAG 2.2.2 要求超过 5 秒的自动移动内容可以暂停，目前只能靠全局开关。

**改进**
- 在设计系统里抽一个 `Modifier.autoAdvance(pager, Motion.CAROUSEL_DWELL)`，首页、媒体库、TV 共用。
- 读屏开启（`isTouchExplorationEnabled`，用 expect/actual 实现）时不自动推进。
- 用户手动翻过页后，本次会话不再自动播放；或者恢复一个可见的暂停键。

工作量 S–M｜置信度 代码层面高，用户影响中

#### P1-15 “添加服务器”快甩或点遮罩就会关闭，并清空已填内容

**问题**
- `AddServerDialog.kt:83` 用的是默认的 `GlassDialog(onDismiss = onDismiss, scrollable = false)`，而拖拽关闭对所有弹窗都启用（`Dialogs.kt:178-193`）。
- 移动 ≥17 dp 且速度 >864 dp/s 就会关闭（`DialogDragMotion.kt:32-37`）。快甩不经过 96 dp 阈值，也没有触感预警。
- 一关闭就是 `DialogClose`，表单被重置为 `LoginForm()`（`ServersStore.kt:1179-1182`），地址、账号、密码全部丢失。点遮罩也一样。
- 元数据编辑弹窗（P0-2）同样可能被甩掉。

**改进**
- `GlassDialog` 增加 `dragToDismiss` 参数。
- 表单有改动时，关闭拖拽和遮罩关闭，改为弹出“放弃更改？”确认；或者关闭时保留草稿。

工作量 S｜置信度 中（手势路径为静态推断）

#### P1-16 播放器缓冲指示：每次跳转闪一下，缓冲时不能暂停

**问题**
- 顶部状态胶囊直接由 `state.buffering` 驱动，没有延迟（`PlayerRoot.kt:2850-2853`）。而播放键有 250 ms 延迟（`PlayerMicroMotion.kt:8`），续播提示有 550 ms 延迟（`PlaybackExperienceOverlay.kt:185`），一共三套阈值。
- 结果是每次跳转都会闪出“正在重新缓冲”。
- 缓冲时，播放键被转圈替换并禁用（`PlayerChrome.kt:321-337`，`enabled = !locked && !state.buffering`）：用户无法在缓冲时暂停，读屏焦点所在的节点也被移除。

**改进**
- 胶囊复用 250 ms 延迟，文案交叉淡化并加迟滞。
- 播放键保持可按，转圈作为外环叠加，并加上“缓冲中”的 `stateDescription`。

工作量 S｜置信度 高

### P2

#### 转场与导航

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-1 | **主操作关闭跳过退场。**<br>• 关闭按钮、返回键有 240–300 ms 退场；但选中一项、确认、提交后，父状态直接移除弹窗，面板和遮罩在一帧内消失（如 `ProfileScreen.kt:877`、`LibraryGridScreen.kt:389,434`、`HomeScreen.kt:1194`、`ServersStore.kt:1334`）。<br>• 从“更多”跳到“下载”时，前一个遮罩瞬间撤掉、后一个从 0 淡入，页面亮度跳一下（`DetailScreen.kt:857-858`）。<br>• TV 弹窗的“关闭”按钮也是硬切（`TvDetailMoreActions.kt:192` 等）。 | • `GlassDialog` 增加 `visible` 参数或 `rememberGlassDialogState()`，父状态置 false 时先退场再移除。<br>• `OverlayOptionRow` 默认把 onClick 包进 `overlayAction`。<br>• 连续弹窗用 `afterExit` 串起来。 | M |
| P2-2 | **全屏覆盖页没有进出场。**<br>• “全部剧集”（`DetailScreen.kt:1042`）、“查看全部”（`HomeScreen.kt:558`）一帧出现、一帧消失。<br>• 预测返回拖到 0.9 后松手，页面直接消失（`BackOverlay.kt`）。<br>• 覆盖期间首页轮播仍每 6 秒翻页。 | • 用 `AnimatedVisibility` 复用 `Motion.PUSH/POP` 和位移令牌，或者改成路由。<br>• 松手后从当前进度继续动画，再移除。<br>• 覆盖期间向下层提供 `LocalRouteVisible = false`。 | M |
| P2-3 | **预测性返回。**<br>• `MainActivity.kt:77-98` 常驻一个“再按一次退出”回调，拦截了根页返回，Android 14+ 的返回桌面预览因此失效。<br>• 预览进度复用 `tween(POP, Motion.Curve)` 加 `scaleOut(0.9f)`（`OfficialNavDisplay.kt:155-165,244-246`），手指滑到 30% 时退出页已淡掉大半。<br>• 在搜索根部侧滑，沿用的是上一次的 SearchEnter 动效（`App.kt:357`）。<br>• 底栏不跟手势。 | • 去掉根部拦截，或只在不支持预测返回的系统上启用。<br>• 预测返回用线性进度，退出页只缩放和位移、不淡出。<br>• 根级返回按“当前 tab → 首页”计算动效。 | M |
| P2-4 | **推进和切 tab 时两页同时半透明。**<br>• 推进：入场淡入 280、出场淡出 120（`OfficialNavDisplay.kt:273-281`）；切 tab：180 / 120（`:195-206`）。前几十毫秒两页都半透明，露出底层背景或壁纸。<br>• 入场页也从 24 dp 圆角开始（`:111-123`），与“只在离开时圆角”的注释不符。 | • 推进时旧页保持不透明，只轻微压暗。<br>• 圆角只给预测返回的退出页。 | S |
| P2-5 | **液态胶囊横穿底栏。**<br>搜索期间 `selected = -1`，胶囊目标变成 0（`LiquidTabMotion.kt:56`）；离开搜索时，胶囊从“首页”位置横穿整条底栏。 | • 索引小于 0 时保持上一个有效值。<br>• 从不可见状态恢复时直接跳到新位置，只做透明度动画。 | S |
| P2-6 | **冷启动编排偏长。**<br>• 再次启动与首次相同，都是 1080+120 ms（`AnimatedSplashApp.kt:420-423`），与 `:381-384` 注释说的“紧凑节奏”不符。<br>• 冷启动落在库时，还要再播约 1.4 s 的潮涌：24 dp、三次阻尼摆，底栏也跟着摆（`LaunchWave.kt:45-69`、`App.kt:420`），这与“底栏是全应用唯一静止的元素”（`App.kt:386-389`）相悖。<br>• 内容在 15 秒内到达都会补播潮涌。 | • 再次启动的开屏缩到 600 ms 以内。<br>• 潮涌振幅不超过 8 dp、只过冲一次、底栏不参与；补播窗口缩到约 3 秒。<br>• 参数并入 `Motion`。 | M |
| P2-7 | **底部避让有两套算法。**<br>• 首页、搜索、服务器用固定的 `TabBarInset = 122dp`（`HomeScreen.kt:357`、`SearchScreen.kt:188`、`ServersTabScreen.kt:259`）。<br>• 库和我的用 `floatingNavigationContentInset()`（`FloatingNavigationInsets.kt:27-30`）。<br>• 三键导航或大号文字时，前三页的最后一行会压在玻璃底栏下面。 | 统一用 `floatingNavigationContentInset()`。 | S |
| P2-8 | **相邻动画时长不齐。**<br>• 共享海报 300 ms（`SharedMediaTransition.kt:103`），页面推进 280 ms。<br>• 共享 key 用固定的 `delay(EXPAND + QUICK)` 清除（`OfficialNavDisplay.kt:80-84`），低端机首帧慢时，会在形变途中被移除。<br>• `popSuppressed` 是普通 var（`SharedMediaTransition.kt:42`），420 ms 内返回时可能先反向形变再跳。<br>• 搜索框形变 280 ms，而搜索路由只有 180 ms（`SearchDockMotion.kt:46`）。 | • 时长统一取 `Motion.PUSH`。<br>• 等过渡结束再清 key。<br>• `popSuppressed` 改为 `mutableStateOf`。 | S |
| P2-9 | **TV 转场。**<br>• 路由、tab、设置子页全部硬切（`TvApp.kt:157-189,301`、`TvSettingsScreen.kt:58`）。<br>• 首页大图的 `Crossfade` 用 `tween()` 默认值（300 ms、FastOutSlowIn）；淡入的是还没解码的新图，所以先露出灰底再硬切（`TvHomeScreen.kt:291-301`）。<br>• 图片加载器没有开启淡入（`TvApplication.kt:185-224`）。 | • 三处包 `AnimatedContent`：淡入 `Motion.STANDARD`、淡出 `Motion.QUICK`、位移不超过 8 dp（即“静息”档）；减少动画时 snap。<br>• 大图预取成功后再切换，用 `tween(Motion.AMBIENT, easing = Motion.Curve)`。<br>• 图片加载器加 `crossfade(Motion.POSTER_FADE)`。 | S–M |

#### 弹窗、面板与 Toast

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-10 | **播放器面板动画叠加。**<br>• 外层 `ChromeContent` 做 180 ms 淡入 + 1/6 屏宽位移；内层又套了所选弹窗样式或抽屉弹簧（`PlayerControls.kt:1060,1179,1261,1341`、`PlayerPanel.kt:113-116,273`）。<br>• 选中条目时只走外层退场；点外部时先走内层退场，再跑一段看不见的外层退场，这段时间吞触摸。<br>• 抽屉和拖拽关闭都没有把松手速度带进后续动画（`PlayerPanel.kt:113-116`、`DialogAnimation.kt:303-304`），甩出后面板先停一下再加速离开。 | • 自带动画的面板，外层只负责保活（`EnterTransition.None`）。<br>• 所有关闭统一走 `LocalOverlayComplete`。<br>• 抽屉和拖拽关闭改用带 `initialVelocity` 的 `Motion.drawer()`。 | M |
| P2-11 | **退场期间吞输入，底栏分两段回场。**<br>• 退场期间整个弹窗窗口仍拦截触摸，关闭后立刻点列表会丢一次（`Dialogs.kt:149`）。<br>• 在根页打开任何弹窗都会收起底栏；关闭后底栏再以 260 ms 回场，前后约 0.5 s 分成两段动效（`App.kt:392`）。 | • 退场开始时通过 `DialogWindowProvider` 给窗口加 `FLAG_NOT_TOUCHABLE`。<br>• 把 `OverlayVisibility` 拆成“隐藏底栏”和“需要背景录制”两个含义。 | M |
| P2-12 | **Toast。**<br>• 淡入用缺省曲线，位移用 `Motion.Curve`（`Toast.kt:165-166`）。<br>• 固定显示 2600 ms（`:45`），20 字以上、需要用户行动的提示读不完。<br>• 底部间距各页自定，而且不算导航栏（`DetailScreen.kt:1133` 28 dp、`LibraryGridScreen.kt:474` 24 dp、`HomeScreen.kt:547` 固定 122 dp）。<br>• 横滑关闭后却向下退场（`Toast.kt:186-192`）。<br>• 超过 3 条时，最旧的一条直接删除，没有退场（`:69`）。 | • 淡入也用 `Motion.Curve`。<br>• 时长按字数计算：2600 ms，每超出 12 字加 80 ms，封顶 7 s，再与 `calculateRecommendedTimeoutMillis` 取大。<br>• 底部间距由 `ActionToast` 自己读取 inset。<br>• 退出方向跟随横滑方向。 | S |
| P2-13 | **弹窗与页面语义。**<br>• `GlassDialog` 没有 `paneTitle`，全库只有 `PlayerGestureHelp.kt:26` 设了。<br>• `heading()` 全应用只有 `PlayerGestureHelp.kt:70` 一处；`SectionHeader`、`OverlayHeader`（`Dialogs.kt:378`）、设置里的 `Section`（`SettingsControls.kt:66`）都是普通 Text。<br>• `OverlayOptionRow` 固定为 `Role.RadioButton`（`Dialogs.kt:574`），约 17 处动作行（如“关闭”）被读成“单选按钮，未选中”。 | • `GlassDialog` 增加 `title` 参数，写入 `paneTitle`。<br>• 三个标题组件加 `heading()`，可覆盖约 99 个调用点。<br>• 新增 `OverlayActionRow`，角色为 `Role.Button`。 | S |
| P2-14 | **浅色弹窗次级文字对比度不足。**只重映射了 `body/sub2`（`Dialogs.kt:157-161`）；估算 `sub` 约 4.2:1，`hint` 约 3.8:1。 | • 同时映射 `sub` 和 `hint`。<br>• 在合约测试里用 perceivedSurface 断言对比度 ≥4.5:1。 | S |

#### 加载与状态

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-15 | **同一屏出现多个加载指示。**<br>• 详情页点播放：按钮上有 `waitingPulse`，图标位换成 orb，页面中央还硬切出一个 32 dp orb（`DetailActions.kt:95,124`、`DetailScreen.kt:819-821`）。<br>• 搜索首次加载同时有六种：输入框 orb、字段辉光、图标脉冲、整页 bloom、骨架呼吸、扫光（`SearchScreen.kt:182,217,428`、`SearchResultsHandoff.kt:283-357`）。<br>• 服务器页刷新时，下拉 orb 和顶栏旋转图标同时出现。<br>• 首页离线时，推荐和日历各出一张错误卡。 | • 一次请求只用一个指示：触发控件已经显示内联加载时，不再画中央 orb。<br>• 搜索骨架可见时，关掉 bloom 和图标脉冲。<br>• 首页合并成一张错误卡。 | S |
| P2-16 | **加载指示没有“延迟出现 / 最短显示”。**<br>• `WaitingPulse` 延迟 180 ms 才出现（`WaitingPulse.kt:53`），而同一个按钮里的 `InlineLoadingContent` 第 0 帧就换成 orb（`InlineLoadingContent.kt:28-39`）。<br>• `SkeletonHandoff` 第一帧就显示骨架，任何一次加载结束都要跑 1000 ms 的错峰入场（`SkeletonHandoff.kt:64,91-97`）。<br>• 详情页缓存命中时，也可能先闪一下骨架。 | • 加一个 `rememberDelayedBusy(active, showAfter = Motion.STANDARD, minVisible ≈ 400ms)`，三者共用。<br>• 加载时间不到 STANDARD 时，跳过骨架和入场动画。 | M |
| P2-17 | **骨架与真实布局不一致。**<br>• 媒体库首页加载时没有英雄区占位，内容到达后插入整块轮播，整页下推；骨架行在分类卡之上，真实海报行却在分类卡之下（`LibraryHomeScreen.kt:426,443,621,630`）。<br>• 详情骨架有一块 96×142 的海报，真实页面没有（`DetailLoading.kt:60-64`）。<br>• 首页骨架海报 150 dp、间距 10，真实海报 156 dp、间距 12（`PageStates.kt:295,323`、`HomeScreen.kt:1564,1595`）。 | • 媒体库首页按英雄区高度占位，骨架行移到分类卡之后。<br>• 骨架行高度取 `posterRailWidth × 1.5`。<br>• 去掉详情骨架里的海报块。 | M |
| P2-18 | **`contentHandoff` 包住了整页。**追剧中心（`CalendarScreen.kt:172-173`）和服务器页（`ServersTabScreen.kt:251`）切换分区时，返回键、刷新键、分段控件自己也从透明淡入。 | 只包结果区，按分区单独交接。 | S |
| P2-19 | **刷新完成反馈不分成败，各页做法不一。**<br>• 首页刷新失败时保留旧内容，但海报仍会重新升起。<br>• 服务器页用旋转图标 + 结果图标 + Toast；追剧中心用 orb 交接。<br>• 首页、媒体库刷新成功后没有任何提示。 | • 与 P1-1 一起改成按内容变化重播。<br>• 刷新失败统一走 `ActionToast`。<br>• 服务器页顶栏改用 `InlineLoadingContent`。 | S |
| P2-20 | **空态、错误态各页自己实现，个别失败完全不提示。**<br>• TMDB 元数据加载失败只执行 `copy(loading = false)`，没有提示也没有重试（`TmdbInfoComponent.kt:74-82`）。<br>• 下载页失败时没有重试（`DownloadsScreen.kt:599`）。<br>• 个人中心的失败信息用说明文字的颜色显示在顶部（`PersonalCenterScreen.kt:111,271`）。<br>• 搜索筛选结果为空时没有操作（`SearchScreen.kt:806`）。<br>• 统一媒体库的状态全是纯文字（`UnifiedLibraryScreen.kt:110-151`）。<br>• 日历 2 处、网格 1 处空态没有操作（`CalendarScreen.kt:1256-1259,1588`、`LibraryGridScreen.kt:687`）。 | • 统一用带操作按钮的 `ErrorState` / `PageHint`。<br>• 一次性结果走 `ActionToast`。 | M |
| P2-21 | **图片淡入与占位不统一。**<br>• `FallbackImage` 默认淡入 400 ms（`Poster.kt:86`）；日历缩略图、TMDB 演员头像、分类卡、选集条这些密集小图都没传 180 ms（`CalendarScreen.kt:911,1401`、`TmdbInfoScreen.kt:411`、`LibraryHomeScreen.kt:1190`、`EpisodeStrip.kt:159`）。<br>• `progressive = false` 会把淡入一起关掉，所以搜索页的演员头像是硬切（`ImageRevealMotion.kt:19-24`、`SearchScreen.kt:535`）。<br>• 占位色有三套：`Poster.kt:266-271` 的硬编码渐变、`skeletonFill()`、`palette.card`。从骨架到占位再到图片，颜色要换两次。 | • 默认改为 `POSTER_FADE`，英雄图显式传 `ARTWORK_REVEAL`。<br>• `progressive` 只控制模糊，不影响淡入。<br>• 占位色统一取骨架色。 | S |
| P2-22 | **`OrbProgress(color = …)` 基本不起作用。**<br>• 只有 `blue` 以 12% 混入传入的颜色，其余颜色写死（`LoadingArtwork.kt:24-30`）；调用方传的单色（按钮墨色、白色、错误色）拿到的仍是彩色。<br>• 深浅变体按 `palette.isDark` 选，而不是按实际底色（`OrbProgress.kt:51`）。<br>• 在 10 dp 尺寸下，描边只有约 0.27 dp（`AddServerDialog.kt:116`）。 | • color 不是强调色时按单色渲染。<br>• 16 dp 及以下时，描边设 1 px 下限。 | M |
| P2-23 | **TV 加载态。**<br>• 加载提示是一个静止的 12 dp 圆点加文字（`TvUiComponents.kt:674-687`），看不出是否还在加载。<br>• 设置里的“加载动画”“背景图”对 TV 原生页面不生效（`TvAppearanceScreens.kt:97-145`）。<br>• 加载完成后整页硬切。 | • 复用轻量的加载动画（遵循减少动画），或做一个 1.2 s 的透明度呼吸。<br>• 内容用 `Motion.STATE_HANDOFF` 淡入。<br>• TV 上隐藏这两项设置，或者把它们接通。 | S |

#### 按压、控件与触感

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-24 | **滚动容器里快速轻点看不到按压反馈。**`clickable` 在可滚动的父级里会把 Press 延后约 100 ms。不到 100 ms 就抬手时，Press 和 Release 在同一帧连发，基于 `collectIsPressedAsState` 的缩放和底色都不会出现（`Interaction.kt:150`、`SoftFeedback.kt:86`）。 | • 自己收集 interactions，Press 之后至少保持 `Motion.PRESS_IN` 再释放。<br>• 或者改为 `IndicationNodeFactory`。 | M |
| P2-25 | **长按震两次，Confirm 震得太早。**<br>• 长按时 `pressable` 先播 Confirm（`Interaction.kt:216`），`combinedClickable` 默认 `hapticFeedbackEnabled = true`，又播一次 LongPress；全仓没有关掉。<br>• 表单按钮和危险按钮一按下就播 Confirm，登录或删除即使失败，也先震一次“成功”（`FormControls.kt:180`、`Dialogs.kt:450,573`）。 | • 长按关掉 Compose 自带的触感，统一为 LongPress 语义。<br>• 按下时用 Tap 或不震，操作结果出来后再播 Confirm/Reject。 | S |
| P2-26 | **同类控件有多套实现。**<br>• 开关有三套：“更多操作”和播出提醒里的私有开关，旋钮瞬跳、没有触感（`DetailMoreActionsDialog.kt:688`、`SeriesAiringCalendarSheet.kt:476`）；播放器的 `PopupToggleHeader` 没有开关语义（`PlayerControlComponents.kt:172-176`）。<br>• 展开箭头有四种写法：`DisclosureMotion.kt:29`（tween，转 90°）、`MotionAware.kt:18-21`（settle）、`DetailEpisodes.kt:114-119`（settle，转 180°）、`SeriesAiringCalendarSheet.kt:194,453`（无动画）。 | • `PillSwitch` 增加 `activeColor` 参数，替换两份私有实现；开关行统一为 `Role.Switch` + `toggleableState` + Select 触感。<br>• 抽一个共享的箭头组件，基于 `Motion.settle(reduceMotion)`。 | S |
| P2-27 | **继续观看卡的倾斜被裁剪框截住。**修饰顺序是 `.clip(shape).background(…).pressable(tilt = true)`（`Poster.kt:261-288`），7° 倾斜发生在静止的裁剪框里：四边露出占位渐变，标题在卡外也不跟着动。 | • 把 `pressable` 移到 clip 之前，并上移到整张卡。<br>• 或者取消倾斜，统一用 0.97。 | S |
| P2-28 | **列表行按压只缩放，悬停与焦点用同一套语言。**<br>• 全宽行用默认的 0.97（`SettingsControls.kt:148-159`），360 dp 宽的行两侧各内缩约 5 dp，只看到文字往中间缩。<br>• 悬停也放大到 1.02，并画 56% 透明度的强调色环（`Interaction.kt:310,321`）；首页大图在鼠标悬停时仍会放大。<br>• 详情主播放键的两半共用一个 interactionSource，一半获焦时两半同时出环（`DetailActions.kt:78,112,187`）。 | • 行类控件改用 0.99 + 按下状态层；播放器菜单行已经是这种做法（`PlayerControlComponents.kt:279-299`）。<br>• 悬停改用 4–6% 的中性底色，不画环；缩放只留给卡片类。<br>• 焦点环改为双色描边。<br>• 复合按键的两半各用独立的 interactionSource。 | S–M |
| P2-29 | **触控目标不足或被挤压。**<br>• 设置页分段控件是 `heightIn(min = 30.dp)`，且没有 `touchTarget()`（`SelectionControls.kt:161`）。<br>• 剧集头的三个动作只有 44 dp（`DetailEpisodes.kt:134,164,183`）。<br>• 首页指示器每个点固定 48 dp（`Indicators.kt:88-101`），8 张共 384 dp（`HomeScreen.kt:365`）；在 360 dp 宽的竖屏上，第 8 个点被挤到 24 dp。<br>• 倍速键读屏时只念“1.25×”（`PlayerChromeRefined.kt:797-802`）。 | • 补上 `touchTarget()`。<br>• 指示器每个点的宽度取 `min(48dp, 可用宽 / 页数)`。<br>• 倍速键加 `label = "播放速度"` 和 `stateDescription`。 | S |
| P2-30 | **滑块拖动不跟手。**<br>• 拖动过程中旋钮仍走 `Motion.settle` 弹簧，大约滞后“0.08 s × 拖动速度”（`SelectionControls.kt:307-311`）。<br>• 背景暗度滑块不能获得焦点，也不响应方向键（`ProfileScreen.kt:1441`）。 | • 拖动时用 `snapTo` 1:1 跟手，只有点按和按键才走弹簧。<br>• 把焦点和方向键处理收进 `GlassSlider`。 | S |
| P2-31 | **TV 选中态与读屏语义。**<br>• 选中时只有 1 dp 的强调色描边（`TvUiComponents.kt:201`），3 米外很难分辨。<br>• 所有可聚焦表面都设了 `selected`，卡片和按钮都被读成“未选择”；卡片片名读两遍（`:266-270,373,397`）。<br>• 禁用的设置行仍可聚焦、可点击（`TvSettingsScaffold.kt:152`）。<br>• 嵌入的手机控件用 11–13 sp 字号、2 dp 焦点环，获焦就发光粒（`UnifiedLibraryScreen.kt:179-181`、`DetailActions.kt:294`）。<br>• 按钮的填充色和文字色在组合期硬切（`TvUiComponents.kt:313-318`）。 | • 选中态改为约 20% 的强调色填充 + 2 dp 描边，或者加勾选图标。<br>• `selected` 只设在确实可选的控件上；卡片合成一句完整描述。<br>• 禁用行加 `disabled()`。<br>• 统一媒体库和个人清单按钮换成 `TvMediaCard` / `TvActionButton`。<br>• 颜色由焦点动画进度在绘制阶段插值。 | M |

#### 播放器

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-32 | **减少动画时播放器窗口硬切。**<br>• 窗口动画直接是 0 ms（`PlayerActivityMotion.android.kt:4-13,55-75`），与设置页写的“播放器统一使用淡入淡出”（`PlayerTransitionSheet.kt:49`）以及 `PlayerTransitionStyle` 的注释不符；从浅色页面切到黑色横屏，亮度骤跳。<br>• 动画缩放设为 0.5× 或 2× 时，窗口 XML 动画被系统缩放，而手绘编排仍按真实时间走，接缝会错位。 | • 减少动画时改用 150 ms 的纯透明度淡入淡出，与控制栏“减少动画时保留淡入”的原则一致。<br>• 缩放不等于 1 时，按比例换算编排时钟，或者回退到普通淡入。 | S |
| P2-33 | **转场每帧的开销。**<br>• 页面离场结束后，仍在 Leaving 阶段逐帧写时钟，一直到页面 onStop（`PlayerHandoffStage.kt:141-151`）。<br>• 每帧新建的对象：玻璃舱的整页 `BlurEffect`（`:217`）；虚焦两个全屏层的模糊，并重新录制（`:521-538`）；潮汐的 `createRuntimeShaderEffect`（`PlayerHandoff.android.kt:116`）；转身的 Path（`PlayerHandoffDrawing.kt:77`）。<br>• 替身图另发一个 1920×1080 的解码请求（`PlayerHandoffDrawing.kt:44-58`）。<br>• 这段时间正好与 PlayerRoot 首次组合、解码器初始化重叠。 | • 离场结束后写入终值，再挂起等待阶段变化。<br>• RenderEffect / ColorFilter 按量化参数缓存。<br>• 替身用 `placeholderMemoryCacheKey` 复用页面上已有的位图。<br>• 用 `first_video_output` 做 A/B 对比。 | S–M |
| P2-34 | **转场起点、终点几何不可靠。**<br>• 按 key 取“最后注册的”来源，而英雄区和同 key 的行内海报会冲突，可能从行内小海报起飞（`PlayerArtworkOrigin.kt:46`、`LibraryHomeScreen.kt:843,1302`）。<br>• 返程用的是启动时的快照，播放中旋转过屏幕后，会落回旧位置（`PlayerHandoffStage.kt:186`、`PlayerTransition.android.kt:538`）。<br>• 发起转场时不检查源图是否已加载完。 | • 由被点击的元素直接提交自己的 bounds，与 P1-8 一起做。<br>• 返程时重新解析当前位置，对不上就释放。 | M |
| P2-35 | **拖动进度停住会丢失拖动。**<br>• 计时靠拖动采样重置（`onScrub = { interactions++ }`，`PlayerControls.kt:977`）。手指停住 ≥5 s 看预览图时，控制栏自动隐藏，进度条被移除，这次拖动不会提交。<br>• 亮度、音量的每个采样都会重组整个 PlayerControls（`PlayerRoot.kt:3479,3488`）。 | • 加一个 `scrubbing` 标志，并入暂停自动隐藏的条件，松手再 `poke()`。<br>• 音量和亮度以 State 或 lambda 的形式下传。 | M |
| P2-36 | **媒体弹幕不响应减少动画。**`DanmakuOverlay.kt:254-270` 的帧循环所在文件完全不读 `reduceMotion`，而一起看的聊天弹幕会停（`WatchChatDanmakuOverlay.kt:127`）。 | 减少动画时改为顶部或底部固定显示，加淡入淡出；或者降速。需要产品决定。 | S |

#### 性能（均为静态推断，先测后改）

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-37 | **搜索输入时，每按一个键都重组可见结果行。**<br>• 整屏读取 `state`（`SearchScreen.kt:148-160`）。<br>• `visibleAggregated` 每次访问都要过滤、排序（`SearchStore.kt:134-143`），每次重组要算 3 遍。<br>• `item()` 每次都返回新的 graphicsLayer lambda，结果行因此无法跳过重组（`SearchResultsHandoff.kt:198-208`）。 | • 派生列表用 `remember` 只算一次。<br>• `item()` 按 key 缓存 Modifier。<br>• 输入框单独收集 query。 | S–M |
| P2-38 | **详情页季选择锚点每帧写状态。**`onGloballyPositioned { onPickerAnchor(boundsInRoot()) }`（`DetailEpisodes.kt:128`）会写入 `seasonPickerAnchor`（`DetailScreen.kt:208,643`）；多季剧集滚动时每帧都有新的 Rect，导致覆盖层作用域重组。 | 锚点存成普通字段，打开季列表时再读。 | S |
| P2-39 | **ASS 字幕每帧全轨扫描。**ASS 字幕在屏且在播放时，每个 vsync 都在主线程对整条字幕轨做约 3 次 O(N) 过滤（`Core2Surface.kt:319`、`YSubtitle.kt:174`、`AndroidAssSubtitleRenderer.kt:136-137`）。 | • 每条轨预建一份按起点排序的 ASS 索引。<br>• 差分计算移到渲染线程。 | M |
| P2-40 | **路由切换那一帧整页重组。**<br>• 路由可见性在切换那一帧翻转（`OfficialNavDisplay.kt:104-106`）；`ThemeColorConsumer.kt:44`、`LightParticles.kt:499`、`ImageRevealMotion.kt:23` 都在组合期读它，所以被覆盖或被揭开的页面上，所有 `ThemeText`、`pressable`、`FallbackImage` 会同时重组。<br>• 点一张海报，会让所有海报重组（`SharedMediaTransition.kt:71-72`）。<br>• `pressable` 仍是组合式 Modifier，网格每格约有 7 个协程和 3 个动画状态（`Interaction.kt:148-206`）。 | • 用 static local 下发一个稳定的可见性 State 持有者，只在 effect、绘制、emit 时读取。<br>• 海报改用 `derivedStateOf { activeKey == key }`。<br>• 长期把 `pressable` 改写成 Modifier.Node。 | M–L |
| P2-41 | **详情页取色后逐帧重组。**取色结果到达后的 500 ms 里，约 11 处常驻的 `AnimatedColorContent` 在组合期读 `.value`；标题、简介、片源、剧集、相关推荐等逐帧重组（`DetailScreen.kt:545-803`、`DominantColor.kt:51-60`）。 | • 文字改用 `ColorProducer`。<br>• 形状在绘制阶段读取 State。 | M |
| P2-42 | **TV 没有对模糊、粒子和复杂弹窗降级。**<br>• `TvApp` 没传 `particleLight`，于是用默认的 Gentle，TV 设置里也没有这一项。<br>• 弹窗期间整页录制并模糊（`ModalGlass.kt:36-76`）。<br>• 43 款弹窗在 TV 上全部可选（`TvAppearanceScreens.kt:82-95`）。<br>这些都与 `TvTokens.kt:21-23` 写明的“机顶盒 GPU 不做模糊”相悖。 | • TV 传 `ParticleLight.Off`。<br>• 弹窗改用不透明底色。<br>• TV 上只开放“柔和浮起”“底部升起”或静息档。 | S |

#### 令牌、设置与自动检查

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-43 | **令牌漂移。**<br>• 32 处 `tween` 没传 easing。影响最广的是 `Interaction.kt:341,345,358,362`，经 `motionItem` 影响约 132 个列表项。其余：`PlaybackExperienceOverlay.kt:65,66,125,126,165,166`、`AppUpdateOverlay.kt:161-198`、`PlayerRoot.kt:2863,2864,2893`、`Toast.kt:165-166`、`SkeletonHandoff.kt:93-94`、`MotionPolish.kt:35`、`SubtitleHandoff.kt:25`、`SearchScreen.kt:425-426`、`ServersTabScreen.kt:332,334`、`TvHomeScreen.kt:293`。<br>• `player_enter.xml` / `player_exit.xml` 用的是平台的 `fast_out_slow_in`。<br>• 缩放字面量：`OfficialNavDisplay.kt:245,284,285`、`PlayerChrome.kt:304,311`、`PlayerControls.kt:1520,1526`。<br>• Tokens 之外的弹簧：`BurstIcon.kt:90-93`（ζ0.38）、`PlayerHandoffStage.kt:283,556`。<br>• 错峰入场有四套参数：上浮 6/9/12/18 dp，步进 34/55 ms；`HANDOFF_ROW_MS` 与 `SEARCH_ROW_STAGGER` 重复定义。<br>• 新编排约有 135 处字面量时长（`PlayerHandoffGeometry.kt:300-306`、`PlayerTransition.android.kt` 46 行、`PlayerHandoffStage.kt` 23 行）。<br>• 6 处 `AnimatedContent` 没有 `.using(...)`，默认的 `SizeTransform` 弹簧不受应用内减少动画控制（`SelectionControls.kt:115-116`、`PlayerControls.kt:1515-1516`、`PlayerChrome.kt:297-298`、`PlayerScreen.kt:69-70`、`AppUpdateOverlay.kt:152-153,228-229`）。 | • 提供 `Motion.tween(ms)`、`Motion.fade(ms)`（默认 `Motion.Curve`）、`Motion.swap(reduce)`（带 `.using`）和 `StaggerSpec`。<br>• 新增 `res/interpolator/yfuse_curve.xml`。<br>• 编排时长移进 `HandoffTiming` 的命名字段，并乘统一倍率。<br>• 把 `settle` 的阻尼和刚度暴露为常量，供 `handoffSpring` 复用。 | M |
| P2-44 | **自动检查覆盖太窄。**<br>• `verifyDesignSystemUsage`（`composeApp/build.gradle.kts:69-138`）只扫 commonMain 下的 `app/App.kt`、`core/designsystem/**` 和 `feature/**`，约占 902 个文件中的 298 个。<br>• androidMain 已有真实违规：字面量字号 24 处、`Brand.Danger` 3 处、`Brand.Primary` 9 处。<br>• `tween(durationMillis = 300)` 这类写法可以绕过规则。<br>• 没有任何动效质量或可访问性规则。 | 见第六节：扫描范围扩到 androidMain 和 tvApp；新增 easing、spring 白名单、帧循环门控、`.using` 等规则；可访问性用 Compose UI 测试来守。 | S–M |
| P2-45 | **动效设置分散。**<br>• “外观”里有 6 处动效选择：搜索与导航动效、粒子光效、弹窗动画（43 款）、加载动画（9 款）、播放器进出场（6 款）、开屏；“减少动画”却在另一个分组。<br>• 其中 5 行共用同一个刷新图标（`ProfileSettingsScreens.kt:525-560`）。<br>• “减少动画”开关只显示用户自己的值，系统已经关闭动画时没有任何提示（`ProfileScreen.kt:282`）。<br>• 开关叫“减少动画”，开屏页和 TV 却叫“减少动态效果”（`ProfileScreen.kt:1273`、`TvAppearanceScreens.kt:199`）。 | • 落地 09-23 的“动效主题”：只在一处选择主题，单项选择移入“高级 · 单独覆盖”；先上“经典”（即现状）和“静息”。<br>• 开关下加说明：“系统已关闭动画，应用已自动减少”。<br>• 统一命名。<br>• 各行换用区分度高的图标。 | M–L |

### P3（打磨与清理）

**交互细节**
- 重复点当前 tab 会触感两次（`App.kt:858-864` 与 `ScrollToTop.kt:73`）。底栏退出的 280 ms 内仍可点击，这时点当前 tab 会把刚推进的页面弹回（`App.kt:344-349`）。
- `overlayAction` 要等弹窗退场（240–300 ms）后才执行动作，“选源 → 播放”因此多等一段（`Dialogs.kt:305-311`）。可以改成动作先执行，退场并行。

**弹窗**
- 拖拽关闭拖到 3 倍阈值就硬停，没有橡皮筋阻尼（`DialogDragMotion.kt:82`）。
- 触点坐标只在按下时写入，从不清除（`DialogAnimation.kt:97-102`）。TalkBack 或程序打开的弹窗会从上一次的触点展开。建议超过 500 ms 就视为没有锚点。
- 贴底显示的面板（`DetailMoreActionsDialog.kt:140`、`SeriesAiringCalendarSheet.kt:153`）沿用所选的弹窗样式，而不是从底部滑入。
- 弹幕搜索面板一打开就请求焦点，IME 上推和入场动画同时发生（`DanmakuPanel.kt:545,579-581`）。

**动效参数**
- 潮涌结束后，库页每行、每张海报仍挂着定位和图层修饰（`LibraryHomeScreen.kt:1436`、`Poster.kt:298`），只在 `wave.animating` 时提供即可。
- BurstIcon 用 ζ0.38 从 0.6 弹回，峰值约 1.11，摆动多次，还叠加了按压缩放、光粒和 Confirm 触感。每次切换都 `snapTo`，快速连点会跳变（`BurstIcon.kt:86-99`）。
- 音量滑块在组合期读动画值，并用 `fillMaxHeight(fraction)`（`PlayerChrome.kt:387-392,424`）。
- 首页的 `skeletonSweep()` 挂在整个 LazyColumn 上：推荐已加载、日历还在加载时，光带会扫过已经加载的内容（`HomeScreen.kt:353`）。
- 分页底部只有文字提示（`LibraryGridScreen.kt:625`、`SearchScreen.kt:688`）。

**触感与焦点**
- 触感映射：Threshold 映射到 `GESTURE_START`，API 34+ 应该用 `GESTURE_THRESHOLD_ACTIVATE`（`Haptics.android.kt:40-44`）。拖动底栏切换 tab 没有触感（`LiquidTabMotion.kt:102-109`）。
- 焦点环画在 48 dp 的触控区上，而不是可见形状上（`Dialogs.kt:398-401`，关闭键本身只有 28 dp）。
- 获焦就发光粒（`Interaction.kt:194-197`）。
- `YfChip` 固定为 `Role.Tab`（`Chip.kt:24-26`）。

**文案**
- 加载动画的说明外露了内部代号“B1 ·”“B2 ·”“B3 ·”（`LoadingAnimation.kt:20-22`，显示在 `LoadingAnimationSheet.kt:47-49`）。

**TV**
- 4 个焦点缩放值是字面量。
- `.clickable` 在 MaterialTheme 下带默认涟漪（`TvUiComponents.kt:264`）。
- 没有 contentPadding 的容器会裁掉放大后的焦点描边（如 `TvSearchScreen.kt:300`、`TvDetailScreen.kt:178,235,266`）。
- TMDB 信息页的首个焦点落在“返回”上（`TvDiscoveryCalendarScreens.kt:57,111`）。
- 导航项“我的与设置”在约 71 dp 宽的位置里折成两行。

**死代码**
- 5 套开屏编排没有入口（`SplashChoreography.kt:46-50`，共 1,481 行）。
- `Motion.ORB_COMET`（`Tokens.kt:619`）没有引用。
- `LocalDialogAnimationLab` 只剩声明，零读取（`DialogAnimation.kt:77`）。
- `SearchFilters.kt:43,156`、`CenterHint`（`LibraryHomeScreen.kt:1385`）没有调用方。
- TV 的 `FocusScopeStateMachine` 和 `lastInSection` 只有测试引用。
- `AnimatedSplashApp.kt:432` 的注释还写着 tab 缩放 0.986。

## 五、建议实施顺序

1. **止血（1–2 天，全部是 S 级）**：
   - P0-1、P0-2、P0-3
   - P1-1、P1-3、P1-6、P1-10、P1-11、P1-15
   - 同时把 `verifyDesignSystemUsage` 扩到 androidMain 和 tvApp，并加上“`tween` 必须带 easing”这一条，先挡住回归。
2. **可访问性与启动 / 播放（约 1 周）**：
   - P1-2、P1-4、P1-12、P1-14（与 P2-45 共用一个 `autoAdvance`）
   - P1-5、P1-7、P1-8、P1-13、P1-16
   - 完成后按第七节的 TalkBack 清单走一遍。
3. **TV 焦点专项（约 1 周）**：P1-9，以及 P2-9、P2-23、P2-31、P2-42。需要一台 Android TV 真机。
4. **收敛（本季）**：
   - 先做令牌与检查：P2-43、P2-44。
   - 再统一弹窗关闭（P2-1、P2-10、P2-11）和加载节奏（P2-15、P2-16、P2-17）。
   - 最后按 09-23 的方案落地“动效主题”（P2-45）：先上“经典”和“静息”，43 款弹窗和 6 款播放器进出场都收进主题。新增的弹窗样式和转场款式，等主题框架落地后再加。

## 六、守护：测试与自动检查

**扩展 `verifyDesignSystemUsage`**
- 扫描范围扩到 `composeApp/src/androidMain` 和 `tvApp`。`Type.` 规则改为 `(?<![\w.])Type\.`，避免误报 `WindowInsetsCompat.Type`。
- 任何 `tween(` 或 `tween<…>(` 都必须带 `easing =`；拒绝 `durationMillis/delayMillis = 数字`，以及 UI 代码里的 `delay(三位以上数字)`。
- `spring(` 只允许出现在 `Tokens.kt`、`TvTokens.kt`；`.animateItem(` 只允许在 `Interaction.kt`；`animateContentSize(` 只允许在 `MotionAware.kt`。
- `rememberInfiniteTransition`、`withFrameNanos/Millis`、`withInfiniteAnimationFrame*` 只能出现在白名单文件里，或者所在文件必须读取 `reduceMotion`。
- `AnimatedContent` 的 transitionSpec 必须带 `.using(`。
- `res/anim` 里禁用 `@android:interpolator/fast_out_slow_in`，时长必须引用 `@integer/`。

**新增测试**
- **JVM**：
  - 详情 A→B→A 入栈不抛异常（P0-1）。
  - 刷新入场途中路由变为不可见后，进度回到 1（P1-1）。
  - 转场在纯音频时能落定（P1-5）。
  - 三处 `AccessibilityOptions` 的构造结果一致（P1-3、P1-11）。
- **仪器测试**：
  - `onDismiss` 不移除弹窗时，窗口能恢复（P0-2）。
  - 减少动画时按下有状态层（P1-2）。
  - 读屏开启时控制栏不自动隐藏（P1-4）。
  - 所有可聚焦节点宽度 > 0（P0-3）。
- **可访问性**用 Compose UI 测试来守，不用正则：
  - 有点击动作的节点必须有文字或 `contentDescription`。
  - 标题组件带 `heading()`。
  - `ErrorState` 和播放状态提示带 `liveRegion`。
  - 系统字号 2.0 时，关键控件不被裁切。

## 七、验证与测量

- **宏基准**：现有 `macrobenchmark` 只覆盖启动、首页滚动、搜索与 tab 切换。在 performance 源集的 fixture 上新增四条旅程：
  - 媒体库网格快速滑动
  - 详情打开再返回 5 次
  - 搜索框逐字输入（100 条以上聚合结果）
  - 播放器打开再关闭：转身、虚焦、潮汐、减少动画各一组

  指标看 `FrameTimingMetric` 的 `frameDurationCpuMs` 和 `frameOverrunMs`，取 p50、p90、p99。
- **Perfetto**：开启 binder_driver、gfx、view、wm、am、sched。
  - P1-8：统计滚动时主线程上 `IDisplayManager` / `IWindowManager` 的调用次数。
  - P2-33：看 hold 阶段页面窗口还画了多少帧。
  - 在 API 31+ 设备上看底栏与搜索键两个模糊面的 GPU 完成时间。
- **重组计数**：在 benchmark 或 profile 变体里接入 `runtime-tracing`，用 `TraceSectionMetric` 对比 P2-37、P2-38、P2-40 修改前后的重组次数。
- **窗口帧指标**：沿用 [动画性能验证](MOTION_PERFORMANCE_20260908.md) 的 FrameMetrics 方法，同时监听 MainActivity 和 PlayerActivity 两个窗口，配合 `first_video_output` 做 P1-5、P2-33 的 A/B。
- **TalkBack 走查清单**：
  - 冷启动 → 首页轮播停留 10 秒，确认不翻页
  - 加载失败，确认有播报
  - 打开弹窗，确认播报标题
  - 播放 → 停留 10 秒，确认控件仍可达
  - 下一集倒计时，确认有播报
  - 设置里“减少动画”开关的说明
- **设备**：
  - Android 9 / 60 Hz（验证 Binder 假设）
  - API 31+ / 120 Hz（验证 RenderEffect 成本）
  - 一台 Android TV（P0-3、P1-9、P1-10）

  每台设备上用同一 release 包做前后对照，并记录动画缩放和刷新率设置。

## 八、已修复与做得好的地方

**门控**
- 5 个无限过渡、全部 `animate*AsState`、49 个 `AnimatedVisibility`/`AnimatedContent`/`Crossfade`，都显式传了 spec，并受减少动画和路由可见性门控。
- 5 个自写帧循环里有 4 个受门控（例外是弹幕，见 P2-36）。
- 没有发现频率超过 3 Hz 的闪烁：加载动画最短周期 1250 ms。
- 系统“移除动画”已并入手机端和播放器（`App.kt:181-200`、`PlayerActivity.kt:497`）。

**09-10、09-17 的性能项已修**
- `OrbProgress` 只在需要动时创建无限过渡，并在 Canvas 里读取（`OrbProgress.kt:37-50,61-67`）。
- 详情顶栏的进度只在图层和绘制阶段读取（`DetailHero.kt:327-330`）。
- 进度条改为固定轨道、在绘制阶段读取（`PlayerChromeRefined.kt:1030-1061`）。
- 下一集倒计时环改为插值（`NextUpRingState.kt:20-33`）。
- PlayerRoot 改为订阅投影状态（`PlayerRoot.kt:1100,2905`）。
- ASS 字幕只在字幕集合变化时写状态（`Core2Surface.kt:306`）。
- 网格海报只做透明度淡入；模糊半径按 0.5 px 量化缓存（`Poster.kt:85,302`、`ArtworkBlurCache.kt:17-25`）。
- Backdrop 的饱和度已并入同一个效果链（`Backdrop.kt:244-261`）。
- `pressable` 的粒子池在首次发射时才创建，关闭粒子时共用 `DisabledLightFeedback`（`LightParticles.kt:472-504`）。
- 圆角返回动画只在页面栈上创建（`OfficialNavDisplay.kt:90-93`）。
- 背景录制按有无消费者门控（`ModalGlass.kt:40`、`App.kt:354`、`DetailScreen.kt:516`）。

**交互**
- 按压：按下 90 ms，松手走弹簧，可打断（`Tokens.kt:682-690`）。
- feature 和 app 目录里已经没有绕开 `pressable` 的 `.clickable`；Chip 已统一为 `YfChip`；图标按钮补了 `label`。
- 触感走 `performHapticFeedback`，会遵守系统的触摸反馈设置。
- 弹窗：进入途中关闭按剩余距离缩短，重复关闭只回调一次；有 43 款 × 3 种模式的仪器测试。用 D-pad 打开时清除触点，回退到居中展开。
- 图片：每个请求有独立进度；内存缓存命中、隐藏页面、减少动画时直接显示。Coil 自带的 crossfade 已关闭，避免重复淡入。

**播放器**
- PixelCopy 异步截帧，带超时回退。
- 画中画、重建、页面销毁时都会释放页面；启动卡住 3 秒后自动释放。
- 浮动提示与控制栏共用同一个 180 ms 时钟。
- 进度条有完整的无障碍语义。

**TV**
- 焦点缩放、描边、底板共用一个临界阻尼弹簧，只在图层阶段读取。
- 字号四级都不小于 16 sp；强制深色。
- 焦点恢复以稳定 ID 优先。
- 长按确认键会吞掉配对的抬起事件。

## 九、此前审查条目的现状

| 来源与条目 | 现状 | 当前位置 |
| --- | --- | --- |
| 09-23 默认曲线（“约 12 处”） | 仍在，实际有 32 处 | 见 P2-43 |
| 09-23 播放器窗口 XML 用 `fast_out_slow_in` | 仍在 | `res/anim/player_enter.xml`、`player_exit.xml` |
| 09-23 相邻动画时长不齐 | 仍在 | P2-8 |
| 09-23 四套错峰时钟、`HANDOFF_ROW_MS` 重复 | 仍在 | P2-43 |
| 09-23 错误 / 空状态硬切 | 描述已不准：组件已有入场（`PageStates.kt:56,86`），但媒体库首页的 `when` 退场仍是硬切 | `LibraryHomeScreen.kt:379-413` |
| 09-23 缩放 / 弹簧字面量 | 仍在 | P2-43 |
| 09-23 5 套开屏无入口、`ORB_COMET` 无引用 | 仍在 | P3 |
| 09-23 双端胶囊、两份轮播循环、`DetailSections.kt:226`、`ServersTabScreen.kt:331` | 仍在 | `DefaultTabMotion.kt:26-62` / `SegmentIndicator.kt:46-69`；P1-14 |
| 09-23 TV 不跟随系统、焦点缩放字面量、Crossfade 默认 `tween()` | 仍在 | P1-11、P2-9、P3 |
| 09-23 弹窗 43 款 | 仍是 43 款，另新增 6 款播放器进出场 | P2-45 |
| 09-17 U1（英雄区“继续播放”文案）、U2（推荐徽标）、U5（图标按钮无名称）、U6（两份表单）、U7（首页滚动状态）、U9（Backdrop 两遍离屏）、U10（pressable 粒子） | 已修复 | — |
| 09-17 U3 浅色弹窗对比度 | 部分修复：遮罩 0.30、面板 0.82；`sub`/`hint` 未处理 | P2-14 |
| 09-17 导航折叠死代码、弹窗实验室 | 折叠路径已删；实验室只剩 `LocalDialogAnimationLab` 一个零读取的声明 | P3 |
| 09-17 底栏 62 dp 固定高度 | 已修复，会随字号增高（`App.kt:570-581`） | — |
| 09-17 TV 弹窗浅色、TV 字号偏小 | 已修复（强制深色、TvType ≥16 sp）；嵌入的手机控件仍是 11–13 sp | P2-31 |
| 09-10 OrbProgress、详情顶栏、页面强调色、PlayerRoot 订阅、进度条、Poster 模糊、ASS 时钟 | 已修复；页面强调色部分修复 | P2-41 |

## 十、未覆盖与限制

- 没有构建、安装，也没有测量帧率；性能相关结论都是代码路径推断，需要按第七节实测。
- TV 的播放器控制层没有逐项看；也没有在电视真机上验证焦点行为。
- 对比度数值按代码里的颜色模型估算，没有在设备上取色。
- HarmonyOS 移植、投屏接收页、`watchTogetherServer` 不在范围内。
- 本轮没有改动代码，也没有打包，所以不涉及 `version.properties` 和 `release-notes.txt`。

## 十一、实施记录（1.0.86 / 248）

本清单的 P0–P3 已在分支 `claude/animation-interaction-review-vhabgo` 上实施，版本为 1.0.86（248），更新说明见 `release-notes.txt`。以下几处做法与建议不同，或者只做了一部分：

- **P2-40**：`ThemeText`、图片揭示和光粒已不在组合期读取路由可见性，海报改用 `derivedStateOf`。`pressable` 改写成 `Modifier.Node` 属于长期项，本轮没有做。
- **P2-41**：没有把各分区改成 `ColorProducer`。现在详情页各分区和浮层直接使用取色后的结果，取色到达时只重组一次。评分数字和“展开”链接在绘制阶段读取过渡中的颜色；播放键和顶栏仍在各自的小作用域里跟随过渡。
- **P2-42**：TV 不再使用粒子，弹窗不采样页面，也就不再模糊；弹窗动画只提供“柔和浮起”“底部升起”，之前选过的其他款式按“柔和浮起”播放。
- **P2-44**：`verifyDesignSystemUsage` 新增三条规则，覆盖手机共享代码、Android 代码和 TV 代码：`tween` 没有 easing、`Motion.tween` 用了字面量时长、`AnimatedContent` 没有尺寸决策。一个名称以 `…Transform(` 结尾的辅助函数若自己决定了尺寸，也算通过。现有代码全部通过这三条规则。
- **P3 TV**：焦点放大的留白由新的 `tvFocusBleed()` 提供。它把 `contentPadding` 还给外部布局，所以周围元素的位置不变。“我的与设置”在宽度不够时先缩小字号，最多缩到 80%，仍放不下才显示省略号。

验证范围：本环境没有 Android SDK，`dl.google.com` 也无法访问，所以没有构建 APK。已做的验证如下：

- **共享代码编译**：用 Kotlin 2.4.20 加 Compose、序列化编译器插件，对共享代码（`composeApp/src/commonMain` 567 个文件，外加 `watchTogetherProtocol`）完整编译到字节码。桌面端依赖取自 Maven Central；Google Maven 独有的库从 compose-multiplatform-core 1.12.0 源码构建。
  - 编译发现 2 个错误，已修复：`PlayerChrome.kt` 的 `AnimatedVisibility` 作用域错误，以及 `SelectionControls.kt` 中无法解析的 `using` 导入。同样的导入也出现在 `AppUpdateOverlay.kt`，一并删除。
  - 修复后 0 错误。
- **附加检查**，均为 0 发现：
  - Android API 符合性检查。
  - expect/actual 配对：77 对。
  - Android 与 TV 调用点对照变更后签名：22 处。
- **测试**：`commonTest` 全部可以编译，并在 JVM 上运行了 2105 个用例。与改动前的基线逐条对比，新增失败只有 3 个，都是本环境造成的：缺少 Skia 原生库，以及平台函数被替换成了桩实现（字体、内置播放器）。
- **Android、TV 专属代码**：没有真编译，靠以下手段覆盖：
  - ktlint（按模块基线）；
  - 上述动效规则的等价脚本；
  - 导入、命名参数和令牌引用的静态检查；
  - 播放器与 TV 两轮人工审阅，发现的问题已修复。

打包、签名和 APK 核验需要在本地按 `AGENTS.md` 完成。
