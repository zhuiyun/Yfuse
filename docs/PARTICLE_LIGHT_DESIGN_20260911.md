# 粒子光效设计（2026-09-11）

设计画布：https://claude.ai/code/artifact/5413bc12-2d2a-4051-8678-c2f502dea892

项目已有一套「光粒」反馈系统（`LightParticles.kt`：Trail / Converge / Edge / Dissolve / Node / Dust，
全局 64 粒预算，播放器 32 粒，随「减少动画」、窗口失焦、路由不可见、画中画自动关闭）。华为风格的粒子
效果不另起炉灶，而是在既有钩子上放大形态：粒子更大、更亮，并有明确的汇聚 / 环绕 / 流动方向。

## 接入位置

| 位置 | 触发时机 | 现有钩子 | 新增表现 | 预算 |
| --- | --- | --- | --- | --- |
| 启动页 | 冷启动，Logo 编排播放期间 | `AnimatedSplashApp.kt` · `drawPhaseLight` / `rememberPhaseLightCount`（仅增强档） | 新增 `SplashAnimation` 条目「光粒汇聚」：粒子从四周汇聚成 Logo 轮廓，随字标浮起消散；沿用现有时钟 | 12 粒 → 建议放宽到 48 粒，仅启动页 |
| 首页 / 媒体库轮播 | 拖动、翻页、自动轮播 | `HomeScreen.kt`、`LibraryHomeScreen.kt` · `carouselLight.emit(Dust)` | 海报上下边缘扬起光尘，切页时离开侧扫过一道汇聚光；轻柔档只保留边缘光尘 | 6 / 12 粒 |
| 详情页 Hero 与播放键 | 进入页面、按下「播放影片」、点收藏 | `DetailScreen.kt` · `lightOnAppear`、`HeroActionDock`、`BurstIcon(Converge)` | 进入时海报边缘光尘；按下播放键时光粒向按压点聚拢并沿胶囊边缘扫过；收藏时爱心外圈一环星轨 | 每次 6 / 12 粒 |
| 播放器进度条 | 拖动、松手确认、音量手势 | `PlayerChromeRefined.kt` · `emit(Trail / Converge, fractionX)` | 拖动时光粒沿轨道尾随滑块，松手瞬间聚拢到确认位置；画面区域永不绘制粒子 | 播放器 32 粒，PiP 关闭 |
| 一起看 | 成员加入、未读消息、复制房间码 | `lightOnChange(unreadChat)`、`WatchRoomInfoDialog`、`CopyableRoomCode` | 成员头像间流光相连，新成员加入时点亮；未读消息在聊天入口聚拢一圈光；复制房间码节点点亮 | 6 / 12 粒 |
| 外观 · 粒子光效面板 | 用户选择档位与风格 | `ParticleLightSheet.kt`、`ThemePreferences.KEY_PARTICLE_LIGHT` | 在关闭 / 轻柔 / 增强下方新增「风格」行：星尘 / 星轨 / 流光，卡片内实时小预览；新增 `appearance.particleStyle` 偏好 | — |

## 三套方案

- **方案 A · 星尘汇聚**：粒子从四周被吸向目标（Logo、按钮、滑块）。最贴近华为开机与语音助手的气质，
  与现有 `Converge` 效果同源，改动最小。代价：目标点必须明确，不适合大面积背景。
- **方案 B · 星轨环流**：粒子沿同心环流转，带一颗拖尾亮点；胶囊按钮用一圈旋转光缝。适合圆形与胶囊控件
  （收藏、滑块、聊天入口）。需要新增 `LightEffect.Orbit`，粒子位置按角度 + 半径推进。代价：静止页面上
  持续旋转会抢注意力，只在交互瞬间出现。
- **方案 C · 流光丝带**：粒子顺一条贝塞尔丝带流动，配色取 Logo 的薰衣草 / 冰蓝 / 长春花。最能表达连接
  与方向（一起看、进度、轮播边缘）。需要新增 `LightEffect.Flow` 并在 `emit` 时传入路径采样点。代价：每个
  控件要单独定义路径，实现成本最高。

## 通用约束

- 粒子只在操作瞬间出现并在 0.3 秒内消散，不做常驻背景动画。
- 「减少动画」开启、窗口失焦、路由不可见、画中画时一律关闭，沿用 `rememberLightFeedback` 的现有门控。
- 播放画面与字幕区域永不绘制粒子。
- 浅色主题下粒子核心色改为 `palette.text`，并降低光晕强度。
- 画布中的《深海回声》及演员、海报均为占位内容。
