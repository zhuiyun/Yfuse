# 动效审计报告复核 · 2026-09-10

结论：报告发现了真实的状态读取与重复计算问题，但严重度偏高，混有统计口径遗漏、框架行为误读和产品设计建议。可以用作优化线索，不能直接作为删除功能或全量铺动画的实施清单。

本次针对报告的主要性能项、框架结论、播放器链路、TV 与清理建议进行静态核验；没有连接手机，没有测量帧耗时、重组次数或功耗，也没有修改应用源码。本次复核不等于逐页视觉验收，未逐个重新统计全部 Motion token 和全部加载分支。

## 确认需要处理

| 项目 | 核验结果与建议 | 建议优先级 |
| --- | --- | --- |
| OrbProgress | `OrbProgress.kt:62` 前后在组合阶段求 turn/coreScale；应用内 reduceMotion 开启时仍创建两条无限动画。应把动态读取放入 Canvas，并在减弱动效时不创建动画通道。系统时长缩放为零是另一条框架处理路径，不应混为一谈。 | P1 |
| ASS 字幕 | `Core2Surface.kt:178` 附近仅在存在活动 ASS 且播放时推进帧时钟，但随后在组合阶段消费它。应隔离字幕渲染与时钟读取，保留 ASS 动画、双字幕布局和同步语义。不能简单停掉帧时钟。 | P1 |
| 主题切换 | `ThemeCrossfade.kt:44` 读取插值进度并生成 Palette，`Theme.kt:104/164/178` 使用 static local，造成大范围失效风险。应降低局部颜色变化的传播范围；把全部颜色换成 State 是公共设计系统 API 改造，不是无风险的一行修复。文本等确实依赖颜色的消费者仍可能需要重组。 | P1 |
| 详情顶栏 | `DetailHero.kt:340` 在组合阶段读取滚动渐变进度，用于图标和玻璃颜色。方向正确：把绘制状态读取下移，或缩小可重组控件范围。不能仅用 derivedStateOf 包住每帧仍变化的浮点数就期待停止重组。 | P1 |
| 页面强调色 | `DominantColor.kt` 的两个动画函数返回 Color，详情/TMDB 页实际使用 `rememberAnimatedArtworkAccent`，会订阅中间颜色。应按颜色消费者传 State/lambda；只修改返回类型、调用处仍立即 `.value`，不能解决问题。首页和库首页已有稳定目标 `rememberArtworkAccentTarget`，不应当作同一未修问题。 | P1 |
| 播放器状态消费 | PlayerRoot 仍订阅大状态；`PlayerRoot.kt:2591` 直接调用 `toEpisodeCards()`，`PlayerControls.kt:947` 直接生成进度标记。按真实输入缓存并缩小订阅范围有价值。 | P1 |
| 进度绘制 | `PlayerChromeRefined.kt` 的缓冲/播放进度仍通过 fillMaxWidth(fraction) 改布局；可改为固定轨道内绘制宽度，保留拖动、焦点、键盘和可访问语义。NextUpCard 仍直接用上游 remainingMs 绘环，局部插值可以改善阶梯感。 | P2 |
| 库首页滚动状态 | `LibraryHomeScreen.kt:267` 组合阶段直接计算 carouselVisible；可用 derivedStateOf 仅通知布尔结果变化。属于低成本优化，不能描述成每一滚动帧重组整屏。 | P2 |
| 海报 BlurEffect | `Poster.kt:145` 每次图层动画更新按新半径创建效果，有分配机会。应评估有限档位缓存或其他视觉等价方案；保留原连续模糊效果时，不能保证零分配且观感完全不变。 | P2 |
| 动效维护 | 弹窗确有 43 个枚举项、独立时长；Tab 增强路径确实使用 LiquidTabMotion，自备默认 Animatable 在该路径只 snap。可统一配置与公共计算，按启用路径延迟创建备用状态。 | P2 |

以上为静态优化优先级，不是已经证明的发布阻断级缺陷。没有证据支持报告的 P0 定级或具体整机功耗结论。

## 必须纠正的结论

1. **TV 焦点系统不是死代码。** `tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/TvUiComponents.kt:219` 调用 tvFocusTarget，`:495` 使用 RestoreTvFocusEffect，服务器及详情菜单使用 tvFocusScope。旧 composeApp 下 TV UI 删除是目录迁移的一部分；漏扫新模块会误导删除共享焦点实现。
2. **系统动画关闭不是仅开屏生效。** Android Compose 默认窗口 Recomposer 订阅系统 ANIMATOR_DURATION_SCALE；标准动画使用 MotionDurationScale，InfiniteTransition 在缩放为零时停止推进并等待恢复。当前 SearchResultsHandoff、AmbientLight、WaitingPulse 也显式处理该值。需要继续审计自写 delay/帧循环以及应用内 reduceMotion 与系统策略的一致性，不能说系统关闭后“整个应用仍满动画”。
3. **父组合失效不等于所有子树每帧执行。** PlayerRoot 行数不能换算成重组成本，Compose 可跳过满足条件的子调用。页面状态仍值得拆分，但“3400 行全部继承执行”不是实测结论。
4. **库首页不是逐帧滚动读取。** firstVisibleItemIndex 仅跨 item 时变化，isScrollInProgress 仅滚动起停时变化；代码没有在该处读取连续像素偏移。
5. **Backdrop 的 remember 不能原样修 Poster。** Backdrop 的 radiusPx 通常固定；Poster 的 radius 由 remaining 动态生成，逐帧改变 key 仍会创建新对象；把状态读取移出 graphicsLayer 还可能引入额外重组。
6. **弹幕不是每个 tick 都重启动画循环。** `LaunchedEffect(positionMs, playing, recoveryRevision)` 用于进度纠偏；持续帧循环的 key 是 playing/recoveryRevision。刚完成的版本已将逐帧位置读取移到布局/图层阶段，报告应区分这两条路径。
7. **我的页面不能仅凭 Children 判定缺转场。** ProfileTabComponent 当前只有 Home 一个 Config，没有两条内部目的地可用于该宿主转场。是否迁移应看实际导航需求，不是机械替换组件。
8. **原始计数缺少完整范围。** 当前 composeApp commonMain/androidMain 加 tvApp androidMain 共 731 个 Kotlin 源文件，报告写 709，至少应记录版本与扫描目录。TV 公共焦点组件实际使用 clickable；设计系统包装内部也有 clickable。恢复凭证页的 CircularProgressIndicator 是轻量启动门禁，不能仅因与光球风格不同判为缺陷。

## 设计建议与代码缺陷要分开

- **播放器进出转场**：未发现项目自定义 Activity 转场配置，可以设计一致的进入/退出效果。但系统默认行为依赖平台，不能仅从缺少 override 方法断言一定是某一种淡入。跨 Activity、方向变化及 Surface 的海报共享元素需要单独设计，不能直接套用普通页面共享元素。
- **43 款弹窗收敛**：数量是事实；直接删除为一两款属于产品功能取舍。先集中时长、曲线与公共实现，保留已存储偏好的兼容，不把审计文档的建议直接当成删除授权。
- **骨架交接和 animateItem**：优先统一首载、刷新、错误、空态的交接，保证稳定 key。无需给每个静态 section 都加动画；需要避免双树重叠开销、滚动位置跳动和流式数据到达时反复播放入场。
- **搜索稿补全**：吸合、分裂和雾玻璃带属于设计一致性，不是功能故障。把触发条件扩大到所有结果变化可能导致筛选和渐进结果反复扫光，应先定义一次请求的交接边界。
- **字幕淡入淡出**：普通字幕、ASS 特效和位图字幕不能统一套用同一过渡，否则可能改变时序、遮挡或作者特效。
- **死代码**：useNavigationRail 恒 false 和重复 stage 计算可列维护项；公开接口、偏好枚举及 TV 共享实现必须跨模块核验后再删。

## 建议处理顺序

1. OrbProgress、详情顶栏、库首页可见性、进度条绘制，以及依赖明确的剧集/标记缓存。
2. ASS 渲染订阅隔离、主题和页面强调色的状态传播；这些是较大的改动，应分别验证，不承诺完全零重组。
3. 补齐自写动画的系统减弱策略，整理 Motion 与 Tab/弹窗公共配置，验证旧偏好兼容。
4. 再做播放器进出、骨架交接和搜索设计补全；其他页面动效按使用频率补齐。

## 框架依据

- [Compose 状态读取与性能](https://developer.android.com/develop/ui/compose/performance/bestpractices)：状态读取阶段决定重组、布局或绘制失效的范围。
- [CompositionLocal 官方说明](https://developer.android.com/develop/ui/compose/compositionlocal)：static local 变化会使 provider 内容大范围重组。
- [Compose 生命周期与跳过](https://developer.android.com/develop/ui/compose/lifecycle)：父级重组不表示全部子调用都必须执行。
- [MotionDurationScale](https://developer.android.com/reference/kotlin/androidx/compose/ui/MotionDurationScale)：动画缩放为零时的终止语义。
- [AndroidX WindowRecomposer 源码](https://android.googlesource.com/platform/frameworks/support/+/44feb9ed430c099502dbb2d2a963ab11897ff043/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/WindowRecomposer.android.kt)：系统动画缩放观察。
- [AndroidX InfiniteTransition 源码](https://android.googlesource.com/platform/frameworks/support/+/f68402285edc35592203bcd92aaf1af3636464a4/compose/animation/animation-core/src/commonMain/kotlin/androidx/compose/animation/core/InfiniteTransition.kt)：缩放为零后等待恢复。
