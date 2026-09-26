# UI、交互与功能设计规范审查（2026-09-25）

基线 `c051d02`（1.0.86 / 248）。本轮只做审查：没有修改 Kotlin 代码，没有构建、安装，也没有在设备上运行。

## 范围与方法

- **范围**：
  - 手机端：`composeApp` 的 commonMain 与 androidMain，包括应用壳、首页、媒体库、搜索、追剧日历、个人中心、服务器、我的与设置、详情、下载、一起看、设备接力、应用更新。
  - 播放器 Activity（手机与 TV 共用）。
  - TV 端：`tvApp`，以及 `composeApp/src/androidMain/kotlin/com/yfuse/tv/`。
  - 周边：投屏接收页 `castReceiver`、桌面小组件、通知；HarmonyOS 移植只做简评。
- **“设计规范”取三层依据**：
  1. **代码内的设计契约**：`Tokens.kt`、`SemanticTypography.kt` 注明的《设计说明文档》，包括 §3.1 转场体系、§8.2 色彩、§8.3 字体四级体系（不新增字号，中文正文下限 12.5sp）、§8.4 圆角三档与 4dp 间距阶梯；`TvTokens.kt` 的 TV 规则；`verifyDesignSystemUsage` 的门禁规则。
  2. **产品规格**：`docs/superpowers/specs/2026-07-22-yfuse-emby-client-design.md`，主链路“连接 → 登录 → 浏览 → 详情 → 播放”，401 时回到登录。
  3. **平台规范**：Material 3 与 Android 可访问性（触控目标 ≥48dp、正文对比度 ≥4.5:1、TalkBack 语义）、Android TV 10 英尺设计、WCAG 2.2。
- **方法**：
  - 按八条线并行走查：设计系统一致性、应用壳与接入、浏览页、详情与内容操作、手机播放器、TV、无障碍与文案、1.0.86 修复核验。
  - 全部 P0、P1 和量化数字，都由我回到当前 HEAD 逐行复核过。
  - P2、P3 抽样复核了约 20 条，全部属实；未复核的条目沿用审查线给出的行号证据。
- **口径**：
  - 标“静态推断”的条目需要真机确认。
  - 对比度按代码里的颜色模型估算，没有在设备上取色。
  - 行号以本基线为准。
- **与此前审查的关系**：
  - 09-24《[动效与交互体验审查](MOTION_INTERACTION_REVIEW_20260924.md)》的 P0、P1 与 23 项 P2 已逐项核验，结果见第五节。本文不再重复其中已修复的条目。
  - 09-17 代码与 UI 审查（`audit/code-ui-review-20260917/REPORT.md`）、09-15 产品对标（`audit/product-gap-20260915/REPORT.md`）中仍然存在的条目，并入本文清单，并注明来源编号。

**路径简写**：
- 设计系统：`composeApp/src/commonMain/kotlin/com/yfuse/core/designsystem/`。
- 页面：同级的 `feature/<模块>/`；应用壳：同级的 `app/`。
- 播放器共享代码：`composeApp/src/commonMain/kotlin/com/yfuse/feature/player/`；Android 部分（`PlayerActivity.kt`、`PlayerRoot.kt` 等）：`composeApp/src/androidMain/kotlin/com/yfuse/feature/player/`。
- TV 页面：`tvApp/src/androidMain/kotlin/com/yfuse/tv/ui/`；TV 播放器桥接：`composeApp/src/androidMain/kotlin/com/yfuse/tv/player/`。
- 文件名在仓库内唯一时只写文件名。

## 一、结论

1. **2 个 P0，都是输入路径上的硬故障。**
   - 儿童资料下，在「服务器」页移除、改名、改图标、编辑或添加服务器会闪退（P0-1）。
   - TV 播放准备页上按过一次方向键后，确认键和返回键都被吞掉，加载失败时只能按 Home 离开（P0-2）。

   两项都是小改动。
2. **25 个 P1，集中在五处。**
   - **接入与失败恢复**：首次接入没有引导，空态是死路（P1-1）；连接错误笼统（P1-2）；密码框用普通文本键盘（P1-3）；登录失效后没有直达的修复入口（P1-4）。产品规格主链路的第一步，也就是 09-15 O8，至今没有落地。
   - **看起来能用、实际不对**：
     - 在一起看房间里点“继续分享邀请”，会解散原房间（P1-12）。
     - 同一语言的多条字幕无法区分（P1-11）。
     - 关闭连播后，片尾仍显示“即将自动播放”（P1-17）。
     - 锁屏后仍能双击改进度（P1-14）。
     - 播出时间没有换算时区（P1-23）。
     - 智能片单留下看不见的筛选（P1-9）。
   - **高频任务步骤多、状态看不见**：详情页看不到收藏和已看状态，收藏要点 3 次（P1-10）；继续观看没有下一集，也无法管理（P1-7）；字幕轨道不在面板首屏（P1-19）；投屏状态不可见，熄屏会暂停电视（P1-16）。
   - **不可逆操作**：删除下载、移除家庭资料一点即执行（P1-5）。
   - **可访问性与 TV**：状态色直接当文字色，浅色主题下对比度只有 1.6:1（P1-20）；播放器面板对读屏不友好（P1-21）；错误不播报，异常原文外露（P1-22）；TV 播放器进度条是焦点陷阱，控件仍是手机尺寸（P1-24、P1-25）。
3. **设计规范的遵从度两极分化。**
   - 字体、圆角、颜色、动效令牌遵从度高：动效门禁在三套源码里 0 违规；feature 里 `AppTypography` 用了 702 处，只剩 2 处动态字号；`AppShapes : GlassShapes` 为 265 : 10。
   - **4dp 间距阶梯基本没有落地**：feature 里有 1,901 个 dp 字面量，其中 851 个不在阶梯上，`Dimens.space` 只用了 6 次。
   - androidMain 的工具页和 TV 圆角游离在门禁之外。
   - caption 用 11sp 的 Manrope 承载中文，与契约自定的 12.5sp 中文下限矛盾。
   - 门禁的注释遮蔽正则不识别字符串，会整段漏检。
4. **1.0.86 对 09-24 清单的修复是实打实的。**42 项里 33 项已修复、5 项基本修复、4 项部分修复，没有未修复项，也没有发现能确定的编译错误。但更新说明里有 3 句超出了代码实际做到的程度，见第五节。
5. **建议顺序**：先止血（2 个 P0 和一批 S 级的功能错误），再做接入与失败恢复，然后是高频任务和 TV 专项，最后把间距、组件和无障碍纳入门禁与截图测试，见第六节、第七节。

## 二、评估

| 维度 | 评价 | 主要依据 |
| --- | --- | --- |
| 设计规范遵从 | 中上 | **做得好**：字体、圆角、颜色、动效令牌落地扎实；feature 里 0 处直接使用 Material3 组件、0 处 `.clickable`、0 处 Material Icons。<br>**不足**：<br>• 间距阶梯未落地（P2-53）<br>• caption 中文 11sp（P2-54）<br>• 状态色当文字色（P1-20）<br>• androidMain 工具页是另一套 UI（P2-56）<br>• TV 圆角没有令牌 |
| 交互便利 | 中 | **做得好**：刷新、网格筛选、换季都有明确反馈；多服务器搜索很稳。<br>**不足**：<br>• 收藏 3 步、锁定与切版本各 4 步<br>• 继续观看长按没有可用操作<br>• 隐藏状态让结果“莫名其妙”（片单筛选、日历筛选）<br>• 首次接入没有引导 |
| 功能正确性 | 中 | 2 个 P0，另有一批“看起来能用、实际不对”的问题：<br>• 一起看重建房间<br>• 重置等于标记未看<br>• 外链类型写死<br>• 提醒分钟被改写<br>• 时区没有换算<br>• 离线播放从头开始 |
| 可访问性 | 中 | **做得好**：1.0.86 补齐了播报、标题语义、读屏感知、下拉刷新自定义动作等公共入口。<br>**不足**：<br>• 播放器面板<br>• 错误播报<br>• 状态色对比度<br>• 大字号截断<br>• TV 语义基本为零 |
| TV 体验 | 中下 | **做得好**：tvApp 自有页面已统一到 TV 令牌，焦点动画和恢复扎实。<br>**不足**：<br>• 共享播放器是短板：准备页按键被吞、进度条是焦点陷阱、控件仍是手机尺寸<br>• 登录全靠遥控器打字<br>• 网格和搜索放不下一行完整海报 |
| 一致性 | 中 | • 术语一词多义（片源 6 种叫法；“线路”三义）<br>• 私有组件：返回键 5 种、可选胶囊 6 种、徽标 5 种<br>• 播出日历两份实现已经漂移 |

## 三、设计规范符合度

| 规范（出处） | 契约 | 现状（本基线） | 结论 |
| --- | --- | --- | --- |
| 字体四级（§8.3，`SemanticTypography.kt`） | 只用 `AppTypography` 四级，不新增字号；中文正文 ≥12.5sp | • feature：`AppTypography` 702 处，`sc/mr` 仅 2 处且是动态值，`.sp` 字面量 10 处（都是 lineHeight / letterSpacing）<br>• androidMain：字面 `sc/mr` 24 处，另有 `fontSize = 30.sp` 1 处<br>• TV：字号都走 `TvType`（≥16sp）<br>• caption 为 Manrope 11sp，承载中文 ≥222 处 | 手机基本符合；androidMain 例外；caption 与中文下限矛盾（P2-54） |
| 圆角三档（§8.4） | 10 / 16 / 26dp，不允许中间值 | • feature 1 处、designsystem 0 处、androidMain 2 处（4 个字面量）<br>• TV 9 处，取值 14 / 18 / 12 / 20 / 5dp<br>• 播放器抽屉 24dp<br>• `AppShapes : GlassShapes` = 265 : 10（09-17 为 106 : 195） | 手机符合；TV 与 androidMain 不符 |
| 4dp 间距阶梯（§8.4） | 每个间距取 `Dimens.space` 阶梯值 | • feature 1,901 个 dp 字面量（09-17 为 1,992），共 115 种取值；不在阶梯上的 851 个，其中间距 577 个<br>• `Dimens.space` 6 次；`cardGap` 0 次，而 `spacedBy(14.dp)` 有 15 次 | **不符合**，令牌定义了但没人用（P2-53） |
| 色彩（§8.2） | 文字用 Palette 语义色；交互强调色走 accent 解析；不直接用 `Brand.Danger` | • `Color(0x`：feature 25（09-17 为 49）、androidMain 约 22（09-17 为 81）、TV 0（09-17 为 17）<br>• designsystem 外的 `Brand.*`：feature 18（都带 brand-identity 标记）、androidMain 14<br>• 状态色被当文字色 | 大体符合；状态色（P1-20）和 androidMain（P2-56）不符 |
| 转场与动效（§3.1） | `Motion` 令牌；`tween` 必带 easing；减少动画时降级 | 三套源码 0 违规；`spring(` 只出现在 `Tokens.kt` 和 `TvTokens.kt` | **符合** |
| 组件 | 交互用设计系统组件 | • feature：0 处 Material3 组件、0 处 `.clickable`、0 处 Material Icons<br>• 裸 `BasicTextField` 14 处<br>• 返回键、胶囊、徽标、分组标签各有多套私有实现 | 大体符合；组件能力缺口催生了私有实现（P2-57） |
| 触控目标 | ≥48dp | 204 个可点击修饰中 125 个带 `touchTarget()`，另有 14 个用 heightIn≥48、31 个是整行；残留分段控件 36dp、提醒胶囊约 29dp | 基本符合 |
| 对比度 | 正文 ≥4.5:1 | • 正文角色在页面底色上达标<br>• 深色 hint 在卡片上 4.26<br>• 状态色文字 1.5–4.1<br>• 播放器约 19 处半透明白字低于 4.5 | 部分不符（P1-20、P2-41、P2-55） |
| 底部避让 | `contentBottom` / 浮动 inset | 根 tab 已统一；子页仍有两套算法 | 部分符合（P2-58） |
| TV 10 英尺（`TvTokens.kt`） | TvType ≥16sp、焦点可见、安全区 | tvApp 自有页面符合；共享播放器、嵌入的手机页不符 | 部分符合（P1-24、P2-50） |
| 产品规格主链路 | 连接 → 登录 → 浏览 → 详情 → 播放；401 回到登录 | 功能齐全；首次接入没有引导；没有 401 的全局处理 | 部分符合（P1-1、P1-4） |
| 自动检查（`verifyDesignSystemUsage`） | 守住上述契约 | • 设计规则只扫 commonMain 的 app / designsystem / feature<br>• 没有 dp、圆角、字号、颜色字面量规则<br>• 遮蔽正则不识别字符串：`ServerBackupTools.android.kt:506` 的 `"*/*"` 被当成注释起点，第 506–569 行整段不查 | 覆盖不足（第七节） |

## 四、问题清单

**优先级口径**：
- **P0**：崩溃、卡死、输入被吞、入口不可达。立即修。
- **P1**：高频路径上用户能感知的问题、功能结果错误，或可访问性缺口。下一个版本内修。
- **P2**：一致性问题、低频页面、潜在风险。本季内分批处理。
- **P3**：打磨与清理。

**工作量**：S 约半天以内；M 约 1–3 天；L 约一周以上。

**编号**：本文编号独立于此前的审查；引用旧清单时写作“09-24 P2-7”“09-15 O8”“09-17 U6”。

### P0

#### P0-1 儿童资料下，在「服务器」页移除、改名、改图标、编辑或添加服务器会闪退

**问题**
- 家庭资料（1.0.65 起提供）规定儿童资料不能管理服务器：`PersonalLibraryRepository.kt:62-63` 是 `check(policy.value.canManageServers) { "请先使用家长 PIN 切换到成人资料" }`，`ServerRegistry.kt` 的 addOrUpdate / rename / setRoutes / setIcon / replace / remove（:222、:288、:330、:430、:459、:508）第一行都调用它。
- 但「服务器」tab 对儿童资料完全开放（`App.kt:373` 没有守卫，首页才有 `PersonalDiscoveryGuard`），`feature/servers` 里没有任何 child / canManageServers 判断。
- 调用方都不捕获异常：
  - 移除：`ServersTabComponent.kt:263-265` → `store.accept(ServersIntent.Remove(id))` → `ServersStore.kt:628` `registry.remove(intent.id)`，在点击调用栈上同步抛出 `IllegalStateException`。
  - 改名 `ServersStore.kt:1108`、改图标 `ServersTabComponent.kt:255`、添加/编辑 `ServersStore.kt:890/1063/1134`（协程内）同理。
- 同一根因的连带问题：退出 Yfuse 账号时 `AccountSettingsScreen.kt:634-636` 先提示“已退出 Yfuse 账号”，再调 `logout()`；而 `AccountRepository.kt:520-522` 在儿童资料下失败并被吞掉，提示与事实相反。

**影响**：孩子长按服务器卡片 → 移除 → 确认，应用直接闪退；添加服务器会在认证成功后闪退。

**建议**
- 服务器 tab 读取 `personal.policy`：儿童资料下隐藏或禁用管理项，给出“需要家长 PIN”的入口。
- Store / Component 对 registry 的写操作统一 `runCatching`，失败转为 `SubmitError` 或 Toast。
- `logout()` 按返回结果决定提示文案。
- 补一条 JVM 测试：儿童资料下发出 Remove / Submit 不抛异常。

工作量 S–M｜置信度 高（代码路径逐行核对；闪退为静态推断，建议真机复现一次）

#### P0-2 TV 播放准备页上按过一次方向键或确认键后，确认键和返回键都失效

**问题**
- TV 上所有按键先交给 `tvRemoteInputController?.dispatch(event)`（`PlayerActivity.kt:273-281`）；从详情页启动时，`showPendingPlayer(pending)`（:447）只渲染准备页（:528-535），这一阶段没有任何代码调用 `publishUiState`，也没人收集控制层命令（只有正式播放页的 `PlayerControls.kt:508-514` 会）。
- `TvPlayerChromeController.showControls()` 直接把状态从 Hidden 改成 Controls（`TvPlayerChromeController.kt:20-33`）。第一次方向键或确认键就会触发它。
- 之后在 `TvRemoteInputController.kt:110-128`：
  - 确认键：`!state.controlsHaveFocus` 恒为真，只执行 `togglePlayPause()` 并消费。
  - 返回键：`hasDismissibleLayer`（即 `visible`）恒为真，只发出无人接收的 `closeTop()` 并消费。

**影响**：出现“无法开始播放”时，准备页上的“重试”“返回”（`PlayerScreen.kt:153-156`）按了没反应，返回键也无效，只能按 Home；加载慢时只要按过任意方向键，也无法用返回取消。

**建议**
- 控制器区分“没有控制层”的状态：准备页上直接 `return false`，按键交还给正常分发。
- 准备页错误态默认聚焦“重试”。
- 补一条准备页按键的 JVM 用例。

工作量 S｜置信度 中高（代码路径逐行核对；需要电视真机复现）

### P1

| 编号 | 区域 | 问题 | 量 |
| --- | --- | --- | --- |
| P1-1 | 接入 | 首次接入没有引导，没有服务器时各入口都是死路 | S–M |
| P1-2 | 接入 | 连接和登录失败的提示太笼统，读屏不播报 | M |
| P1-3 | 接入 | 服务器密码框用普通文本键盘，没有自动填充 | S |
| P1-4 | 接入 | 登录失效或离线的服务器没有直达修复入口（偏离产品规格） | S–M |
| P1-5 | 数据安全 | 删除下载、移除家庭资料一点即执行 | S |
| P1-6 | 浏览 | 媒体库首页的分区标题贴着屏幕边缘（回归） | S |
| P1-7 | 浏览 | 首页“继续观看”链路不完整 | M |
| P1-8 | 浏览 | TMDB 不可用时，首页首屏约七成是空白 | S |
| P1-9 | 浏览 | 智能片单留下看不见的搜索筛选 | S |
| P1-10 | 详情 | 详情页看不到收藏、已看、下载状态，收藏要 3 步 | M |
| P1-11 | 详情 | 同一语言的多条字幕 / 音轨无法区分 | M |
| P1-12 | 详情 | 一起看“继续分享邀请”会解散原房间 | S |
| P1-13 | 详情 | 下载没有闭环：加入后无反馈，离线播放从头开始 | M |
| P1-14 | 播放器 | 锁屏后仍能双击、长按改进度，侧滑直接退出 | S |
| P1-15 | 播放器 | 第一次调亮度从 50% 起跳 | S |
| P1-16 | 播放器 | 投屏：熄屏会暂停电视，本机看不出正在投屏 | M |
| P1-17 | 播放器 | 关闭连播后片尾仍显示“即将自动播放”，结束后无出口 | S |
| P1-18 | 播放器 / TV | 跳过片头 / 片尾要先唤出控件 | S |
| P1-19 | 播放器 | 字幕轨道不在面板首屏，偏移只有粗档 | M |
| P1-20 | 规范 / 无障碍 | 状态色直接当文字色，浅色主题下几乎不可读 | S–M |
| P1-21 | 无障碍 | 播放器面板对读屏不友好 | S |
| P1-22 | 无障碍 / 文案 | 关键失败不播报，异常原文直接显示 | M |
| P1-23 | 功能 | 播出时间是产地当地时间，没有换算也没有标注 | S–M |
| P1-24 | TV | TV 播放器：进度条是焦点陷阱，控件仍是手机尺寸 | M |
| P1-25 | TV | TV 上跳片头、登录要大量遥控器操作，备选内核无效 | M |

#### P1-1 首次接入没有引导，没有服务器时各入口都是死路，连接成功也没有反馈

**问题**
- 没有服务器时冷启动落在首页（`RootComponent.kt:387-388`），`feature/home` 里没有任何“连接服务器”入口；TMDB 不可达时首屏是一张与接入无关的错误卡（见 P1-8）。
- 库页空态只有“重试”：`LibraryHomeScreen.kt:362-372` 写着“当前资料没有可用服务器，请到「服务器」添加或由家长关联”，代码注释自己承认没有跳转入口。
- 多处文案仍指向“我的”：`SearchStore.kt:590`“请先到「我的」添加服务器”、`SearchScreen.kt:811`“前往「我的」检查登录”（实际打开的是服务器 tab，`RootComponent.kt:140`）、`WatchInviteResolver.kt:42`；服务器空态写“连接一台 Emby 服务器”（`ServersTabScreen.kt:1269`），Jellyfin / Plex 用户会以为不支持。
- 添加成功后：`ServersStore.kt:1346` 写入的“服务器已添加”没有任何地方显示，`ServersLabel.ServerAdded`（:908、:1081、:1152）没有订阅者。
- 表单默认 `https = true`、端口 `443`（`ServersStore.kt:35-37`），最常见的“内网 IP + 8096”第一次必然失败；可选的“显示名称”排在“地址”前面（`AddServerDialog.kt:233-260`）。

**影响**：新用户要自己发现“服务器”tab；从安装到首次播放约 7–8 次点按加 2–3 次输入，第一次连接大概率因默认 HTTPS 失败，失败后又得不到具体原因（P1-2）。这是产品规格里“连接→登录→浏览→详情→播放”主链路的第一步，也是 09-15 O8 至今未落地的部分。

**建议**
- 没有服务器时，“自动”启动页落到服务器 tab，或在首页顶部放“连接媒体服务器”卡片；库页、搜索空态的按钮直接打开添加弹窗。
- 第一台服务器连接成功后显示 Toast 并切到库（消费 `ServerAdded`）。
- 私网地址默认 HTTP:8096，或两种协议并行探测；地址放第一位，显示名称折叠到“更多”。
- 文案统一为「服务器」页，空态写“连接 Emby、Jellyfin 或 Plex 服务器”。

工作量 S–M｜置信度 高

#### P1-2 连接和登录失败的提示太笼统，读屏不播报结果

**问题**
- `EmbyApiCall.kt:126` 把所有 `IOException` 映射为 `EmbyError.Network`，显示“无法连接服务器，请检查网络后重试”（`EmbyErrorMessages.kt:6`）：证书错误、超时、端口拒绝、DNS 失败是同一句话。
- 地址填成普通网站或路径写错时，404 显示为“服务器上找不到该内容，可能已被删除或移动”（`EmbyErrorMessages.kt:14`），其它情况是 `"出错了:$message"`（:20）。
- 登录直接 POST `/Users/AuthenticateByName`（`EmbyAuthService.kt:34-37`），`/System/Info/Public` 只在登录成功后才取。
- 错误文字（`AddServerDialog.kt:497-503`、`AccountSettingsScreen.kt:255-258`）没有使用已有的 `liveStatus()`（`Announcements.kt:18`）。

**影响**：用户无法自行判断是协议、端口、证书还是地址的问题；TalkBack 用户按下“连接”后只知道转圈停了。

**建议**
- 先探测 `/System/Info/Public`，据此判断“这不是 Emby/Jellyfin 服务器”并识别版本。
- 按 `SSLHandshakeException`、`UnknownHostException`、`SocketTimeoutException`、`ConnectException` 分类提示，并给出下一步（切 HTTP、改端口 8096/8920、检查路径）。
- 错误文字加 `liveStatus(assertive = true)`。

工作量 M｜置信度 高

#### P1-3 服务器密码框用普通文本键盘，全应用没有自动填充提示

**问题**
- `ServerFormFields.kt:91` 默认 `KeyboardType.Text`，`password = true` 只改变遮罩；添加服务器的密码框和 Plex Token 框（`AddServerDialog.kt:401-424`）都没有改键盘类型。`KeyboardType.Password` 只在 Yfuse 账号页出现（`AccountSettingsScreen.kt:224` 等）。
- 全仓没有 `ContentType.Username / Password`；`YfFormField`（`FormControls.kt:42-55`）也没有 keyboardActions 和内容类型参数。
- 密码框的“完成”只 `clearFocus()`（`ServerFormFields.kt:125`），不提交。

**影响**：输入法会联想、纠错，并可能把服务器密码和 Token 记进词库；密码管理器无法填充；按键盘“完成”不会连接。

**建议**：password 为 true 时强制 `KeyboardType.Password` 并关闭自动纠错；用户名、密码框加 `semantics { contentType = … }`；“完成”触发提交；给 `YfFormField` 补 keyboardActions / contentType / isError，服务器表单迁过去（顺带收掉 09-17 U6 遗留的私有实现）。

工作量 S｜置信度 高

#### P1-4 登录失效或离线的服务器没有“重新登录 / 换服务器”的直达入口，偏离产品规格

**问题**
- 产品规格 §8 要求 401 时“清除会话并由 RootComponent 踢回登录页”（`2026-07-22-yfuse-emby-client-design.md:155-159`），全仓没有 `SessionExpired` 一类的全局处理。
- 服务器卡片点击不看状态（`ServersTabScreen.kt:362-365`：`SelectDefault` 后直接 `onOpenLibrary()`），登录失效只显示一个“需重新登录”标签（:1243）。
- 库页错误态只有“重试”（`LibraryHomeScreen.kt:374-379`）；详情页提示“资源服务器登录已失效，请到服务器管理重新登录”（`DetailSelection.kt:62`），没有跳转。

**影响**：token 失效或服务器离线时，点卡片会进入必然失败的库页；重新登录要“长按卡片 → 编辑连接与名称 → 重新输入密码 → 保存”，全程没有提示。

**建议**：状态为需重新登录的卡片，点击直接打开预填好的编辑表单并聚焦密码框；库页、详情页错误态加“重新登录”“切换服务器”按钮；点离线卡片时先询问或只设为默认、不跳转。

工作量 S–M｜置信度 高

#### P1-5 删除下载、移除家庭资料一点即执行，没有确认也不能撤销

**问题**
- 下载中心单项删除：`DownloadsScreen.kt:629-630` `onRemove = { manager.remove(item.id) … }`；删除键是紧挨“播放/继续”文字的 28dp “✕”（:823-831），底层立即删文件（`OfflineMedia.android.kt:1012-1028`）。
- 批量删除：`:561-563`、`:578-580` 先写“已删除 N 项下载”，再 `removeMany(...)`；点“多选”默认全选（:236），两下即可清空全部下载。
- 移除家庭资料：`PersonalCenterScreen.kt:245-247` 直接 `personal.deleteProfile(profile.id)`，该资料的想看、收藏、历史、追剧一并标记删除，界面没有恢复入口；每条记录的“移除记录”（:611）同样立即执行。
- 对照：追剧页的“取消追剧”已经提供 5 秒撤销（`CalendarScreen.kt:1233-1242`），同类操作体验不一致。

**影响**：想点“播放”误触一下就丢掉几个 GB 的离线内容，离线时无法恢复；家庭成员的个人数据误触即丢。

**建议**：已完成的下载、批量删除、移除资料必须确认（写明占用空间 / 影响范围），或统一用 `ActionToast` 提供 5 秒撤销、延后真正删除；“✕”移入长按或滑动菜单；“多选”进入空选状态。其余同类问题见 P2-66。

工作量 S｜置信度 高

#### P1-6 媒体库首页的分区标题贴着屏幕边缘（09-20 合并回归）

**问题**
- 新的共享组件 `SectionHeader.kt:40` 只有 `padding(bottom = …)`，没有水平边距。
- 媒体库首页的“播放记录”（`LibraryHomeScreen.kt:1280`）和各分类货架标题（:1346）调用时没有传 padding；外层 `LazyColumn` 的 `contentPadding` 只有 bottom（:396-407），`waveItem`（:1457）也不加边距。
- 被 b83a3bf（09-20）删掉的私有版本带 `.padding(horizontal = Dimens.pageHorizontal)`；同一函数里的失败提示（:1352）仍自己加了 18dp。详情页的调用方都自带边距，不受影响。

**影响**：库 tab 每个分区标题和“全部 ›”贴在 0dp 边缘，海报却从 18dp 开始，根 tab 上一眼可见的错位。

**建议**：两处调用补 `Modifier.padding(horizontal = Dimens.pageHorizontal)`，或给 `SectionHeader` 加默认 `contentPadding`；补一张该页的截图测试（见第七节）。

工作量 S｜置信度 高

#### P1-7 首页“继续观看”链路不完整：没有下一集、长按没有可用操作、“全部”去错地方

**问题**
- **没有下一集**：`HomeStore.kt:583` 每次刷新都对每台服务器请求 `nextUpEpisodes(server, 8)`，但数据只在 `HomeScreen.kt:1446-1449` 用来给日历排序；首页只渲染“继续观看”和“我的收藏”（:474-495），`recentAdded`（`HomeStore.kt:105`）也没有用上。继续观看只收 `positionMs > 0` 的条目（`EmbyHomeService.kt:302`）。TV 首页已有“接下来 / 最近添加”（`TvHomeScreen.kt:139-175`）。
- **长按等于点按**：`HomeScreen.kt:1200-1206` 的快捷操作只有一项“查看详情”，与点按行为相同（`HomeStore.kt:435-438`）。
- **“全部”跳错**：两条货架的 `onSeeAll = onOpenLibrary`（:478、:490）最终是 `selectTab(Tab.Browse)`（`RootComponent.kt:105`）；首页货架汇总了所有服务器（`HomeStore.kt:527-559`），库页只读默认服务器，而且库页的“播放记录”没有“全部”。

**影响**：看完一集后这部剧就从首页消失，追剧用户要绕到库或详情页找下一集；标为已看、移出继续观看、从头播放都要先进详情；点“全部”落到另一个 tab 顶部，多服务器用户看到的反而比货架上少。

**建议**
- 在“继续观看”之后加“下一集”货架（数据已在拉取），并提供首页货架的显示与排序设置。
- 长按菜单改为：继续播放 / 从头播放 / 标为已看 / 从继续观看移除 / 详情（写入方式参照网格 `LibraryGridStore.kt:322-348`）。
- “全部”打开汇总全量的覆盖页（参照 `TmdbRowPage`）；“我的收藏”直达收藏网格。

工作量 M｜置信度 高

#### P1-8 TMDB 不可用时，首页首屏约七成是空白

**问题**：精选为空时仍渲染 `HeroSlide(item = null)` 并占满英雄区高度（`HomeScreen.kt:707-718`），高度规则是 `(viewportHeight * 0.72f).coerceIn(480.dp, 610.dp)`（`LivingPoster.kt:64-67`）；错误卡排在英雄区之后（`HomeScreen.kt:426-437`），文案本身就在提示“网络或代理”（`HomeStore.kt:305`）。

**影响**：国内网络直连 TMDB 经常不可用；没有代理、离线或首次安装无缓存时，首屏大半是一块空渐变，“继续观看”被挤到首屏以下。英雄区在横屏时（宽 ≥600dp 走 `coerceIn(480, 730)`）还会高过整个视口。

**建议**：精选为空且不在加载时，把英雄区折叠为紧凑页眉，或改用服务器里的背景图兜底；英雄区高度按“视口高 − 导航坞”封顶，去掉 480dp 下限。

工作量 S｜置信度 高

#### P1-9 打开旧“智能片单”后，之后的搜索都被看不见的筛选限制

**问题**
- 固定的智能片单仍显示在首页和库页（`SmartPlaylistShelf.kt:45`、`HomeScreen.kt:398-402`）。打开时 `SearchStore.kt:735-750` 把服务器、库、年份、类型、观看状态、排序写进搜索状态。
- 之后输入新词只重置 `type` 和片单名（`QueryChanged`，:752-758）；清空搜索（`Cleared`，:955-968）保留全部筛选；`SearchIntent.ClearFilters` 在界面上没有任何调用方。
- 搜索页的筛选界面已在 1.0.68 移除，页面上也不显示这些筛选。

**影响**：结果缺失、排序反常，用户找不到原因；片单指向的服务器被删后，每次搜索都报“还没有可用的服务器”，只能杀进程。

**建议**：清空或输入新词时清掉片单带入的筛选；片单结果顶部显示片单名和“退出片单”。

工作量 S｜置信度 高（触发条件：存在固定的智能片单）

#### P1-10 详情页看不到收藏、稍后看、已看、已下载状态，高频操作从 1 步变 3 步

**问题**：1.0.85 为让简介前移，把收藏、稍后看、个人清单收进“更多操作”（`DetailScreen.kt:589-590` 注释）。`detail.isFavorite`、`detail.played`、`state.watchLater` 现在只传给弹窗（:831、:840-841）；下载、标记已看、一起看也只在“…”里（`DetailHero.kt:401` 注释）。TV 详情页仍保留这些按钮和“已下载”标签。

**影响**：打开电影看不出是否已收藏或已看过；收藏要“… → 开关 → 关闭面板”三步，而弹窗里操作失败的 Toast 还会被面板挡住（P2-21）；新用户难以发现下载入口。

**建议**：标题下加一行只读状态胶囊（已收藏 / 稍后看 / 已看完 / 已下载 N 集），点按直达对应开关；播放键旁保留“下载”“收藏”两个图标键。简介前移的目标可以通过压缩元数据区实现，不必以隐藏状态为代价。

工作量 M｜置信度 高（属于 1.0.85 的有意取舍，建议产品复议）

#### P1-11 同一语言的多条字幕 / 音轨无法区分，选一条会同时高亮多条

**问题**
- 有语言码时标题被丢弃：`Dtos.kt:611-613` 是 `language = languageDisplayName(stream.Language) ?: stream.Title`；字幕标签只有“语言 · 编码 · 强制”（`MediaVersion.kt:334-338`）。音轨同理（`Dtos.kt:590-592`，标签为“语言 · 编码 · 声道”，`MediaVersion.kt:288-291`），导评音轨与正片音轨只差标题。
- 选中判定按值比较：`DetailFileSections.kt:515` `val active = option.value == selected`；传给播放器的也只有语言（`PlaybackTrackRequest.kt`，注释说明是有意按语言传递）。

**影响**：中文片源常见的“简体 / 繁體 / 简英双语”三条字幕都显示为“中文 · ASS”；点任意一条三条同时高亮，播放器可能起播另一条。

**建议**：字幕与音轨模型补上 `title` 并写进标签（如“中文 · 简英双语 · ASS”）；选中判定按索引；交给播放器的偏好改为 `YTrackPreference(language, label, codec, languageOrdinal)`——`InitialPlaybackTracks.kt:32-33` 已支持这些字段。

工作量 M｜置信度 高

#### P1-12 在一起看房间里点“继续分享邀请”，实际会解散原房间再新建

**问题**：已在房间中时，“更多操作”里的一起看行显示“房间已创建，继续分享邀请”（`DetailMoreActionsDialog.kt:386`，`watchActive = watchState.roomCode != null`，`DetailScreen.kt:834`）；但点按回调无条件调用 `watchTogether.createRoom(...)`（`DetailScreen.kt:900-906`），`WatchTogetherClient.kt:187` → `start()` 第一步就是 `leaveInternal()`（:559）。“退出房间”也没有二次确认。

**影响**：房主想再发一次邀请，房间码却变了，已加入的人被踢出，旧链接失效；作为访客点它会静默离开别人的房间。

**建议**：已在房间中（且是同一影片）时只打开分享面板、复用现有房间码；新建房间和退出房间（尤其房主）都要确认。

工作量 S｜置信度 高

#### P1-13 下载没有闭环：加入后没有反馈，离线播放总从头开始

**问题**
- **加入后无反馈**：确认下载后只调用 `component.download(selection)`（`DetailScreen.kt:924-926`），`DetailComponent.kt:151` 只 `enqueueAll(requests)`，不发任何消息；详情页不订阅离线任务状态（TV 有，`TvDetailScreen.kt:134-140`）。默认 `wifiOnly = true`（`OfflineMedia.kt:57`），下载对话框（`DetailDialogs.kt:121-131`）不提示。
- **离线播放从头开始**：下载中心拉起播放器时写死 `startPositionMs = 0L`（`ProfileScreen.kt:834-835`），`OfflineMedia` 没有进度字段。

**影响**：用移动网络点“加入下载”后毫无动静（任务在“等待 Wi-Fi”），用户会反复点；离线看了一半的电影再打开回到 00:00。

**建议**：入队后 Toast“已加入 N 集 · 等待 Wi-Fi · 查看”；播放键旁显示下载进度，已下载时提供“播放已下载版本”；离线播放读取本地同步进度或服务器续播点，行内提供“继续 42:10 / 从头”。

工作量 M｜置信度 高

#### P1-14 锁屏形同虚设：锁定后仍能双击、长按改进度，侧滑返回直接退出

**问题**
- 双击（`PlayerControls.kt:741-743`）和长按（:780-796）只检查手势起点和“房主控制”，不检查 `locked`；只有拖动检查了（:821 `canStart = { origin -> !locked && … }`）。
- 锁定层 `LockedOverlay`（`PlayerChrome.kt:808-846`）和外层 `ChromeVisibility` 都不消费触摸，点按穿透到下面的手势层；“解锁”单击即生效。
- 手机上没有锁定时的返回拦截：带 `locked -> locked = false` 的 `closeTopRemoteLayer()`（`PlayerControls.kt:434-446`）只由 TV 遥控命令调用（:502）。

**影响**：锁屏本来就是为了防误触（躺着看、给孩子看），现在误触双击会快进或暂停，侧滑直接关掉播放器。

**建议**：锁定时在最上层放一层吞掉全部触摸，单击只短暂显示解锁键；解锁改为长按；锁定时拦截返回并提示先解锁；被拒绝的手势播 `HapticSignal.Reject`。

工作量 S｜置信度 高

#### P1-15 第一次调亮度从 50% 起跳

**问题**：`PlayerSystemControls.kt:36-37` 在窗口亮度为 -1（跟随系统，默认值）时回落到 `0.5f`，拖动以此为基准（`PlayerControls.kt:866-868`）。

**影响**：夜里系统亮度 10% 时，轻拨一下屏幕就跳到约 50%，非常刺眼。

**建议**：值为 -1 时读取 `Settings.System.SCREEN_BRIGHTNESS` 并按系统亮度曲线换算为起点；退出播放器时恢复为 -1。

工作量 S｜置信度 高

#### P1-16 投屏：手机熄屏会暂停电视上的播放，本机看不出正在投屏

**问题**
- 熄屏广播走 `pausePlaybackForLifecycle("screen_off")`（`PlayerActivity.kt:255`），投屏会话中执行 `castManager.pause()`（:1895-1897）。
- 播放器不在前台时，`togglePlaybackWithFocus()` 在投屏分支之前就 `return`（:1750，条件见 :1854-1860），通知和锁屏上的“播放”无效；而通知以 `VISIBILITY_PUBLIC` 显示播放键（`PlayerNotificationController.kt:70`）。
- 开始投屏后本地暂停（`PlayerRoot.kt:2621`），投屏键没有激活态（`PlayerChromeRefined.kt:277-283`），接收端错误只在投屏面板里显示（`RemoteCastPlayback.kt:35-37`）；设备发现只由“重新扫描”触发（`PlayerSettingsPanel.kt:1125`）。

**影响**：用手机投屏到电视后一锁屏，电视就暂停；控件隐藏后手机停在定格帧或“正在准备画面”，看不出正在投屏；首次打开投屏面板列表为空。

**建议**：投屏会话不受生命周期暂停与常亮策略约束；投屏时显示常驻的投屏卡片（设备名、状态、断开、音量），投屏键加激活态；打开面板自动扫描并给出“搜索中 / 未发现”空态；后台无法恢复播放时通知隐藏播放键或点按拉起播放器。

工作量 M｜置信度 高

#### P1-17 关闭“自动播放下一集”后，片尾仍显示“即将自动播放”，结束后停在最后一帧

**问题**：下一集卡只判断 `hasNext` 和剩余 10 秒（`PlayerNextUpOverlay.kt:30-37`），播放器共享代码里没有任何 `autoNext` 引用，卡片照常显示倒计时和“即将自动播放”；引擎按设置在末尾停住（`ExoVideoEngine.kt:1291` `pauseAtEndOfMediaItems = !autoNext`）；结束按钮只在 `state.ended && !state.hasNext` 时出现（`PlayerControls.kt:1511`）。

**影响**：到点不播放也没有入口，控件隐藏时画面只剩最后一帧，像是卡住了。

**建议**：把 `autoNext` 传进控制层——关闭连播时卡片换成“下一集”按钮、不显示倒计时；结束后有下一集时显示“下一集 / 重播 / 返回”。

工作量 S｜置信度 高

#### P1-18 “跳过片头 / 片尾”按钮要先唤出控件才出现（手机与 TV 同源）

**问题**：`shouldShowManualSkipPill` 的条件是 `controlsVisible && countdownSeconds == null && segmentLabel != null`（`PlayerControls.kt:116-120`），而组件注释写的是相反意图：“Deliberately outside the show/hide of the rest of the controls … making the user summon the controls first would spend a chunk of that window”（`PlayerChrome.kt:547-553`）；`PlayerSkipCoordinatorTest.kt:38` 把现状固化为预期。TV 上隐藏态按确认是暂停（`TvRemoteInputController.kt:110-117`），自动跳过胶囊写的是“点击取消”。

**影响**：正常观看时控件是隐藏的，片头开始后没有任何提示；手机要先点屏幕再点跳过，TV 要“唤出控件 → 移焦到右下角 → 确认”，在片头时间内很难做完。

**建议**：进入片段后单独显示 5–8 秒再跟随控件显隐；TV 上此类提示出现时取得焦点，或隐藏态确认键优先作用于它；文案按设备写“点击 / 按确认键”；同步修改测试和注释。

工作量 S｜置信度 高（如果是有意改动，需要产品确认）

#### P1-19 字幕调整不顺手：字幕轨道不在面板首屏，偏移只有 ±2 / ±5 秒几档

**问题**
- 字幕面板依次是“双字幕方案”3 行、互换、副字幕字号 4 行、预览，然后才是“主字幕”（`PlayerSettingsPanel.kt:202-238`）；面板最高 308dp（`PlayerPanel.kt:77`），主字幕在首屏以外，打开时不定位到当前项。在线搜索、导入排在所有样式项之后（:418-466），下载成功后提示“重新打开影片后可选择新轨道”（`PlayerSubtitleLibrary.android.kt:101`）。
- 偏移只有预设档：主字幕 ±5 / ±2 秒（`PlayerSettingsPanel.kt:323`），副字幕最小 1 秒（:276），音频 ±500 / ±2000 毫秒（:505，另有“自动同步”）。

**影响**：打开字幕面板看不到轨道；0.3–1.5 秒的字幕错位、蓝牙 100–300 毫秒的延迟都无法校准。

**建议**：面板分“轨道 / 样式与偏移 / 在线字幕”三块，轨道放首屏并滚到当前项；偏移改为 ±0.1 / ±0.5 秒步进器并显示当前值、提供复位；下载的字幕作为外挂轨热加载。

工作量 M｜置信度 高

#### P1-20 状态色直接当文字色：浅色主题下服务器“无法连接”几乎看不见

**问题**
- `Semantic.Success / Warning / Error / Offline` 是为圆点和填充定义的固定色（`Tokens.kt:52-57`，`Semantic.Error` 就是 `Brand.Danger`）；Palette 里只有 `error` 一个按主题校准过的状态文字色。
- 服务器卡片的状态文字直接用状态色（`ServersTabScreen.kt:1098-1102`，映射见 :1222-1236），11sp。同类用法：`CalendarScreen.kt:1965`、`AccountSettingsScreen.kt:1085`、`DownloadsScreen.kt:802-803`、`GlassMaterialSettingsScreen.kt:170-175`（那句“对比度低于 4.5:1”的提示本身就用 `Semantic.Warning`）。
- 按 token 计算（浅色卡面 / 页面）：无法连接·未测速 1.61 / 1.53，在线 2.49 / 2.36，服务异常 2.70 / 2.55，需重新登录 4.08 / 3.86；深色主题下 Error 在卡片上 4.13。
- 门禁只拦 `Brand.Danger`，写 `Semantic.Error` 即可绕过。

**影响**：浅色或跟随系统的用户，在根 tab「服务器」里看不清最需要行动的状态；不满足 WCAG 1.4.3（4.5:1）。

**建议**：Palette 增加按主题校准的 `success / warning / offline` 文字色（含 container）；`Semantic.*` 只用于圆点和填充，文字用 Palette；门禁新增“状态色不得作文字色”（见第七节）。

工作量 S–M｜置信度 高（按 token 计算，未在设备上取色）

#### P1-21 播放器面板对读屏不友好：遮罩是无名称的全屏按钮，面板文字被合并成一个“按钮”

**问题**
- 播放器浮层统一用 `noRippleClickable = pressable(onClick).touchTarget()`（`PlayerControlComponents.kt:273`），带按钮语义。
- 全屏遮罩 `PlayerPanel.kt:215`（及 :412）用它来关闭面板，没有名称；面板本体用 `.noRippleClickable { }`（:248、:427）吞点击，可点击节点会合并子节点语义，面板里的分组标题、说明文字被并进一个空操作的“按钮”。对照：`GlassDialog` 用的是没有语义的 `pointerInput { detectTapGestures { } }`（`Dialogs.kt:455`）。
- 波及：播放设置、倍速、线路、弹幕、一起看聊天等面板。

**影响**（读法为推断）：打开面板后，读屏容易先落在无名称的遮罩上，双击即关闭；面板里的说明读成一个双击无反应的按钮。

**建议**：吞点击改用无语义的 `pointerInput`；遮罩不暴露语义，关闭靠返回键和显式“关闭”；面板加 `paneTitle`。

工作量 S｜置信度 中高

#### P1-22 播放中出错、登录失败、投屏失败都不播报；异常原文直接显示给用户

**问题**
- 起播失败会 Assertive 播报（`PlayerScreen.kt:143`），但播放中出现的错误层（`PlayerChrome.kt:109-134`）、添加服务器的登录错误（`AddServerDialog.kt:497-503`）、投屏失败（`PlayerSettingsPanel.kt:1117-1123`）都没有播报；全库 47 处错误色文本只有 4–5 处会播报。
- `EmbyErrorException` 的 message 就是 `error.toString()`（`EmbyError.kt:45`），界面上再读 `it.message` 就会显示“Network”“Server(code=500)”（如 `SubtitleLibraryPolicy.kt:69`、`TvDetailMoreActions.kt:157`）；解析异常原文进入 `"出错了:$message"`（`EmbyErrorMessages.kt:20`）；DLNA 失败显示“DLNA Pause 失败：设备未确认Pause”，message 为空时显示“null”（`CastManager.android.kt:1133,1145`）。粗略统计 `.message ?:` 60 处、直接拼 `${…message}` 23 处（TV 15 处），而现成的 `toUserMessage()` 已有 43 处在用。

**影响**：读屏用户在最关键的失败时刻听不到反馈；所有用户都会看到英文或内部结构名，不知道原因和下一步。

**建议**：新增 `ErrorText()`（内置 `liveStatus(assertive = true)`）替换裸 `Text(color = error)`；错误层标题设为 heading 并播报；UI 层禁止读 `Throwable.message`，统一走 `toUserMessage(fallback)`，异常原文只进诊断日志；错误文案一律写“原因 + 下一步”。

工作量 M｜置信度 高

#### P1-23 追剧日历和提醒里的播出时间是产地当地时间，没有换算也没有标注

**问题**：`airTime` 是“平台公布的当地时间 HH:mm”，时区在 `timeZoneId`（`AiringCalendar.kt:67-70`）。提醒的调度按时区换算（`CalendarReminderWorker.kt:220-223`），正文却直接拼 `airTime` 原值（:244）；日历行也直接显示 `airTime`（`CalendarScreen.kt:977-982`），另一处又写死“北京时间”（:2034，用的是服务器给的 `releaseAtBeijing`）。

**影响**：国内用户追美剧，看到“21:00 即将更新”，实际在北京时间次日 09:00 播出；提醒在正确时刻弹出，文字却与之矛盾。追剧日历的核心信息就是“什么时候更新”。

**建议**：所有播出时间按设备时区换算后显示；无法换算时标注来源时区（如“美东 21:00”）；不写死“北京时间”。

工作量 S–M｜置信度 高

#### P1-24 TV 播放器：进度条是焦点陷阱，控制层仍是手机尺寸

**问题**
- **焦点陷阱**：进度条的按键处理把上下键也当作跳转并消费（`PlayerChromeRefined.kt:993-1001`：`DirectionLeft, DirectionDown -> commit(-keyStep)`、`DirectionRight, DirectionUp -> commit(+keyStep)`）；步长 `(5_000f / durationMs).coerceIn(0.01f, 0.1f)`（:945），2 小时影片每按一次约 72 秒，与隐藏态的 10 秒不一致。
- **手机尺寸**：TV 复用手机控制层，只多传了 `remoteChrome`（`PlayerActivity.kt:920`）；底栏按钮是 `CircleControl(…, 26.dp, 12.dp)`（`PlayerChromeRefined.kt:619` 起），时间文字用 caption（11sp），焦点是 2dp 强调色环（`Interaction.kt:352-356`）。
- 控制层没有初始焦点；焦点在控件内时永不自动隐藏（`PlayerControls.kt:543-551`）；缓冲中按确认是“播放”而不是“暂停”（`PlayerActivity.kt:1762` 按 `activeState.playing` 判断）。

**影响**：控制层里上下移动必经进度条，按“下”想回按钮行却后退一分多钟，只能按返回关掉控制层才能出来；3 米外看不清 12dp 图标和 11–13sp 文字。这是 TV 上使用时长最高的界面，09-24 审查没有覆盖。

**建议**：进度条只消费左右键，TV 步长统一 10 秒（长按加速沿用 `TvRemoteInputController`）；`remoteChrome != null` 时切换到 TV 版控制层（按钮 ≥40dp、图标 ≥24dp、文字走 TvType、焦点用 TvFocusMotion）；唤出时聚焦播放键，焦点在内也按无操作 5 秒隐藏，播放/暂停按 playWhenReady 判断。

工作量 M｜置信度 高

#### P1-25 TV 上跳过片头、下一集、登录都要大量遥控器操作

**问题**
- 跳过片头同 P1-18：要“唤出控件 → 移焦到右下角 → 确认”；自动跳过胶囊写“点击取消”，下一集卡不抢焦点。
- 服务器弹窗多按一次返回即关闭并清空表单：`TvServersSettingsScreens.kt:304-308` 的 `GlassDialog` 没有 `confirmDismiss`，关闭时 `form = LoginForm()`（`ServersStore.kt:1187-1191`）；手机端已加确认（`AddServerDialog.kt:93-103`）。
- Plex 只能手敲 Token：提示写“Plex 请直接登录账号”（`TvServersSettingsScreens.kt:380`），表单却只有“Plex 用户 / Plex Token”；手机端有 Plex 账号登录（`AddServerDialog.kt:302`），TV 没接。Yfuse 账号也只能遥控器输入账号密码。
- 纯内核 TV 包里，播放器和错误页仍提供 Exo / MPV 作为“换一种方式播放”（`PlayerRoot.kt:3490-3496`，`tvApp/build.gradle.kts:156` 为 `YFUSE_NATIVE_ONLY_RUNTIME true`），选了会以同样原因失败（09-15 O1 残留）。

**影响**：TV 首次配置成本最高，而跳片头这种最常见操作在片头时间内很难做完；给出的备选方案无效。

**建议**：跳过 / 下一集提示出现时取得焦点或让隐藏态确认键作用于它；TV 服务器弹窗复用 `hasInputSince + confirmDismiss`；接入 plex.tv/link 4 位 PIN，Yfuse 账号和服务器迁移提供手机扫码或设备码；纯内核包只保留“跟随 YCore”，错误页改为提供其他版本或外部播放器。

工作量 M｜置信度 高

### P2

#### 接入、壳与设置

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-1 | **根页返回总是先回首页。**`topLevelBackStack` 以首页为根（`App.kt:557-558`，`onBack` 见 :360），而有服务器的用户冷启动默认落在库（`RootComponent.kt:387-388`），也可把起始页设为库或服务器。第一次按返回会去一个从没去过的首页（TMDB 不可达时是错误卡），第二次才退出。 | 顶层返回栈以实际起始 tab 为根，复用 `startupTab()`。 | S |
| P2-2 | **“我的”首屏仍是一张设置清单（09-15 O2 未落到这一页）。**首项是“搜索设置”输入框（`ProfileScreen.kt:620-626`），个人内容只是第二组的一行“我的内容”；页面没有标题，也没有头像、昵称、同步状态。 | 顶部账号卡（头像、昵称或“未登录”、同步状态）+ 继续观看 / 下载 / 想看快捷入口；搜索收进顶栏图标。 | M |
| P2-3 | **设置搜索只到页面级，还会指错。**只有 13 个页面级目的地（`ProfileScreen.kt:172-278`，子串匹配 :1229-1231）；搜“清除缓存”“减少动画”“大号文字”都是“没有匹配的设置项”；“字幕与弹幕”打开的是只有两行的弹幕页（`ProfileSettingsScreens.kt:422-436`），“我的”里没有字幕设置；搜索框没有清除按钮。 | 索引细到每一行并加同义词，打开后滚到该行高亮；修正“字幕”的归属；加清除按钮与 `ImeAction.Search`。 | M |
| P2-4 | **分组不符合心智。**“辅助功能”（减少透明效果 / 大号文字 / 减少动画）排在 11 行外观与动效项之后（`ProfileSettingsScreens.kt:641-675`），且三行的图标分别是字幕、信息、暂停，与含义无关（:643-670）；“启动进入”在外观组（:562）；服务器迁移与扫码导入在“高级设置”（`DataAndDiagnosticsScreen.kt:53-64`）；根页有两组各只有一行（`ProfileScreen.kt:753-787`）。 | 根页单设“辅助功能”组并换用达意的图标；“启动进入”移到通用组；迁移入口同时放在服务器 tab 与添加弹窗；合并单行分组。 | S |
| P2-5 | **同步状态（09-15 O3）看不懂、看不到。**“最近成功”显示 `Instant.toString()` 的 UTC ISO 串（`PersonalCenterScreen.kt:268-276,316-322`），国内比本地时间差 8 小时；根页入口文案写死（`ProfileScreen.kt:649,792`），不显示待同步或冲突；账号页的 `onOpenSyncStatus` 从未传入（`AccountSettingsScreen.kt:99,121-130`），手动快照与自动同步两处不相连；未登录时“立即同步”点不了也不引导登录（:279-287）。 | 本地化相对时间；入口显示“待同步 N / 冲突 N”；接上 `onOpenSyncStatus`；未登录时点击跳到账号页。 | S |
| P2-6 | **Yfuse 账号与媒体服务器账号的区别没有解释，品牌名不统一。**“鱼服账号”8 处、“Yfuse 账号”9 处（如 `TraktSettingsScreen.kt:53`、`DeviceHandoffScreen.kt:86`）；页面标题“账户与同步”与入口“账号与同步”不一致（`AccountSettingsScreen.kt:993`）；同步卡按钮“清空服务器”实际清空的是云端数据（:608-614）；账号页写“账号服务：IP 直连 · HTTPS”（:204-209）。 | 账号页顶部说明“Yfuse 账号可选，用于同步、一起看和设备接力”；统一品牌名；按钮改“清空云端数据”；服务器表单写“服务器用户名”；去掉“IP 直连”等技术措辞。 | S |
| P2-7 | **大屏与横屏。**`useNavigationRail()` 恒为 false（`WindowWidthTier.kt:33-37`），`windowWidthTier` 只在 3 处使用，首页和库页另写 `maxWidth >= 600.dp`（`HomeScreen.kt:295,299`、`LibraryHomeScreen.kt:340,345`）；`widthIn(max…)` 只用于底栏和弹窗。平板上设置行、账号卡横跨整屏；横屏手机上底栏约 76dp 加系统导航栏，占去约两成高度。 | Expanded 宽度下用 NavigationRail 或给底栏限宽；设置等列表页 `widthIn(max = 640.dp)`；“我的”在 Expanded 下双栏；断点统一走 `WindowWidthBreakpoints`。 | M |
| P2-8 | **应用更新会打断观看。**下载完成时只要有任意前台 Activity（包括播放器）就直接拉起安装器（`AppUpdateManager.kt:1689-1690`，计数见 :742-756）；后台下载结束 `stopForeground(STOP_FOREGROUND_REMOVE)` 后不补发“点按安装”通知（`UpdateDownloadService.kt:71`）；缺安装权限时直接跳系统设置、没有说明（:1771-1790）。 | 播放器在前台时只发通知，回到首页再提示；后台完成发可点按通知；跳权限设置前先说明原因。 | S |
| P2-9 | **按钮等分一行、文字只允许一行。**账号页三个 `OverlayButton` 各占 `weight(1f)`（`AccountSettingsScreen.kt:591-615`），按钮文字 `maxLines = 1` 且无省略号（`Dialogs.kt:737-742`）：360dp 宽、默认字号下“清空服务器”约需 65dp，只剩约 58dp（静态推断）。同类：`AddServerDialog.kt:340-352`。 | 窄屏或大字号时纵向排列，或允许两行 / 加省略号。 | S |
| P2-10 | **权限被永久拒绝后点击没有任何反应。**权限行直接 `requestPermission.launch(...)`（`PermissionHealthTools.android.kt:81,102,111`），系统已设为“不再询问”时什么也不会发生。 | 永久拒绝时改为打开应用详情设置页，并说明原因。 | S |

#### 浏览：首页、媒体库、搜索、追剧日历、个人中心

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-11 | **离线状态不一致。**首页对加载失败的服务器直接丢弃、不提示（`HomeStore.kt:515,537-545`）；库页英雄区的服务器胶囊永远是绿点（`LibraryHomeScreen.kt:897` 的 `Brand.Online`），即使下方写着“离线内容”。 | 首页加“N 台服务器不可用”提示条；胶囊接 `ServerHealthMonitor` 的真实状态。 | S |
| P2-12 | **聚合搜索每台服务器只显示前 50 条，没有“加载更多”。**聚合分支（`SearchScreen.kt:292-320`）没有分页，只有分服务器模式有（:690-719）；请求取 50 条（`EmbySearchService.kt:88-89`）；“共 N 条”（:482）按已加载数计算；聚合模式默认开启。 | 聚合列表末尾加“加载更多”，计数改为“已显示 50 / 共 N”。 | M |
| P2-13 | **搜索输入与空结果不顺手。**IME 搜索键只提交不收键盘（`SearchScreen.kt:429-430`）；片名无结果时人物结果仍折叠在最后（:350-366），而占位文字写着“搜索…演员”（:421）；空结果只有“试试片名的一部分”（:848-853）；清空历史一点即清（:1087）。 | 提交时收键盘、开始拖动结果时收键盘；片名无结果时人物前置；空结果提供“去 TMDB 查找 / 加入追剧”；清空历史可撤销。 | S–M |
| P2-14 | **追剧中心的筛选被记住但看不见。**平台 / 内容筛选从偏好恢复并参与 `visibleDays`（`CalendarStore.kt:293-299,125-135`），日历页只显示 `filter.label`（`CalendarScreen.kt:487-491`）；“本周 N 部更新”用未筛选的数据（:270-271,448-454）；筛空时的“查看全部”只重置一项（:320-328），成了死路。 | 顶部把所有生效筛选显示为可清除的 chip；空态按钮清除全部；计数用 `visibleDays`。 | S |
| P2-15 | **提醒设置会被静默改写（两套实现已漂移）。**单剧播出日历有两份实现：追剧中心版 `AiringShowCalendarDialog.kt`（751 行）选模式时固定写入 `DEFAULT_REMINDER_MINUTES = 30`（:204、:466），详情版 `SeriesAiringCalendarSheet.kt`（1073 行）保留用户选的分钟数（:205-216）；“切换提醒”在四种模式间盲循环（`CalendarScreen.kt:1471-1483`）；通知权限被拒时提醒任务静默不发（`CalendarReminderWorker.kt:155-158`）；说明“提前量可在追剧页调整”（`CalendarScreen.kt:1802`）与实际不符。 | 先把 `remindBeforeMinutes` 传进追剧中心版，再抽共享的 `AiringScheduleSheet`；改为单选面板；追剧页显示“通知未开启 · 去开启”；修正说明。 | S–M |
| P2-16 | **平板或宽屏上日历只显示本周，不能翻周。**`maxWidth >= 900.dp` 进入周视图，只取本周七天（`CalendarScreen.kt:530,1044-1056`），数据窗口却是前 7 天、后 14 天。 | 加上一周 / 下一周，或周视图下保留时间线。 | M |
| P2-17 | **网格看不出看没看过，大库难定位。**网格 `showProgress = false`（`LibraryGridScreen.kt:372`），`Poster` 只有评分角标和进度条，`MediaItem.played` 没有展示；排序方向写死（`MediaItem.kt:95-96`）；没有字母索引、快速滚动、回顶按钮，也不能多选。 | 已看 ✓ 与未看集数角标；排序支持升降序；按名称排序时提供 A–Z 侧栏与回顶。 | M |
| P2-18 | **首页 TMDB 精选只在默认服务器里找片。**`HomeStore.kt:613` 只匹配 `registry.defaultServer`，没匹配上就进 TMDB 信息页（:651-653），该页查找期间先显示“未加入媒体库”（`TmdbInfoScreen.kt:595`，初值见 `TmdbInfoComponent.kt:57,75`），之后才变成“立即播放”；“加入 Yfuse 收藏 / 想看”排在播放之前。 | 并发查询所有服务器；查找中显示“正在查找你的媒体库…”；播放区置顶。 | S–M |
| P2-19 | **统一媒体库（09-15 N2）手机上进不去，TV 上体验粗糙。**手机入口在 1.0.68 移除，路由仍在（`LibraryComponent.kt:150,158`，`onOpenUnified` 无调用方）；TV 上每次“加载更多”后整表按片名重排（`UnifiedLibraryPager.kt:197-203`），页面不用设计系统组件（`UnifiedLibraryScreen.kt:84-87,208-219`）。 | 决定去留：保留则改用设计系统组件、稳定顺序追加、自动加载更多；不保留就删掉手机路由。 | M |
| P2-20 | **收藏两套体系、四种叫法。**首页和库页的“我的收藏”是服务器收藏，“我的内容·收藏”是 Yfuse 清单，TMDB 页叫“加入 Yfuse 收藏”，详情页分“服务器收藏 / 收藏到个人清单”；首页英雄区的收藏按钮不显示状态、只能加不能取消（`HomeStore.kt:678-684` 固定 `value = true`）。 | 统一命名并标明来源；英雄区传入真实状态。 | M |

#### 详情与内容操作

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-21 | **“更多操作”里的反馈被面板挡住。**Toast 贴在页面底部（`Toast.kt:133`），面板是独立窗口、占屏幕底部 74%（`DetailMoreActionsDialog.kt:141-145`）；稍后看失败会回滚开关并提示，但提示被挡住；“加入追剧”是开关角色（:550、:611），点按却关闭面板，失败只写入 `airingCalendarError`（`DetailScreen.kt:868`），与 :464-465 注释“change state in place, so the sheet stays open”矛盾。 | 行内显示加载与错误；追剧行与其他开关一致，保持面板打开。 | S |
| P2-22 | **服务器管理操作混在个人操作里。**“刷新服务器元数据 / 分析 Plex 媒体”无确认、结果被丢弃（`DetailScreen.kt:882-897`），刷新是 `Recursive + FullRefresh`（`MediaServerAdapter.kt:449-450`），与“加入追剧”同组且不判断权限（`DetailMoreActionsDialog.kt:547-592`）。 | 单列“服务器管理（需管理员）”组；刷新前确认；成功提示“已提交，稍后下拉更新”，失败按类型说明。 | S |
| P2-23 | **进度操作不准确、不可逆。**剧集整部“标记已看 / 未看”一键执行、无确认，选集条和播放键不刷新（`DetailExecutor.kt:1513-1519`、`DetailReducer.kt:106-117`）；进度管理里“重置”与“标记未看”行为完全相同（`DetailExecutor.kt:1573` `played = action == MarkWatched`），TV 上同一按钮叫“清除进度”；缺“标记至此集已看”。 | 整部 / 整季操作确认并写明集数、提供撤销，成功后刷新剧集与播放目标；“重置”只清续播点；长按单集提供“标记至此集”。 | S |
| P2-24 | **“将播放什么”看不到（09-15 O5 未闭环）。**推荐理由已算好（`SourceSelectionPresentation.kt:28-43`），摘要组件 `playbackVersionSummary / PlaybackVersionSection / PlaybackVersionDialog`（`DetailPlaybackPanel.kt:48-66,122`）全仓没有调用方；同一个 `best` 标志有“推荐”（`DetailFileSections.kt:778`）与“最佳”（`DetailPlaybackPanel.kt:318-320`）两种徽标。 | 播放键下挂一行可点的摘要（服务器 · 版本 · 音轨），点开 `PlaybackVersionDialog`；点“推荐”徽标显示理由；徽标统一。 | S |
| P2-25 | **下载选择与下载中心信息不足（09-15 O6 残留）。**电影也显示“整季 / 仅未看集”（`DetailDialogs.kt:134`）；版本不匹配的集被静默跳过（`OfflineMedia.kt:372-378`），文案仍写“将加入 N 集”（`DetailDialogs.kt:191`）；对话框不写剧名、季、剩余空间；下载中心每行只有“S1 E3 名称”、不含剧名，已有的海报没用上（`DownloadsScreen.kt:785`）；“离线视频容量上限”“允许下载时段”点一下就循环切换（:289-296,321）。 | 按条目类型过滤范围；入队后回报实际入队数与跳过数；写明剧名、季与可用空间；下载中心按剧分组并显示海报；循环切换改单选。 | M |
| P2-26 | **元数据编辑错误提示与退出保护。**所有失败都提示“请检查服务器编辑权限”（`MetadataEditorDialog.kt:79`，服务直接用 HttpClient，`MetadataEditorService.kt:61-66`，异常没有映射）；编辑中只禁用拖拽，返回或点遮罩直接丢弃修改（:94，`GlassDialog` 已提供 `confirmDismiss`）。 | 服务层改走 `embyApiCall` 做错误映射；有改动时关闭先确认。 | S |
| P2-27 | **外部链接按固定类型拼接。**TMDB 恒为 `/movie/$it`、TheTVDB 恒为 `/dereferrer/series/$it`（`DetailSections.kt:205,207`），剧集点 TMDB 会打开同号电影或 404。 | 按 `detail.type` 选 `/tv/` 或 `/movie/`，TVDB 区分类型。 | S |
| P2-28 | **选中空季后季选择器随剧集区一起消失。**`if (state.episodes.isNotEmpty())` 包住了含季选择器的整个剧集区（`DetailScreen.kt:627`），空季直接 return（`DetailExecutor.kt:1180-1187`），用户无法切回其他季。 | 剧集为空时仍显示表头和季选择器，提示“本季暂无已入库剧集”。 | S |
| P2-29 | **“全部剧集”与选集卡。**首次打开不定位当前集（`SeasonEpisodesPage.kt:94-97`）；点一集只选中不播放、无提示（:211，`DetailScreen.kt:1058`）；选集卡没有长按（`DetailEpisodes.kt:575`）。 | 打开时定位当前集；当前集行显示“再点播放”；长按单集提供“标记已看 / 下载本集 / 从头播放”。 | M |
| P2-30 | **设备接力信息不足。**接收框只显示来源设备名（`DeviceHandoffScreen.kt:168`），而本地可解出片名和进度（`HandoffController.kt:246`），请求也带过期时间；接收后“正在准备接收影片”只在设置页显示（`DeviceHandoffScreen.kt:92,118`）；心跳失败会把 `incoming` 置空（`HandoffController.kt:114`），确认框闪退后重弹。 | 框内显示片名、集数、进度与剩余秒数；接收期间保留进度提示；心跳失败时保留未过期的请求。 | M |
| P2-31 | **详情页读屏语义。**已选中的版本 / 资源再点即播放，但标签仍是“选择…”（`DetailExecutor.kt:171-175`、`DetailFileSections.kt:562,728`）；资源列表行没有选中语义，选中态只有 0.75dp 细边（`SourceListDialog.kt:151,163`）；“自动下载后续新集”等开关用默认单选角色（`DetailDialogs.kt:181-185`）；顶栏完全透明时标题和已禁用的“播放”仍可被读屏聚焦（`DetailHero.kt:361-398`）。 | 已选项标签改“播放此版本”；资源行补 selected 语义并加粗选中边框；开关用 Switch 语义；顶栏未出现时从语义树清除。 | S |

#### 手机播放器

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-32 | **高频功能藏得深。**锁定、版本、睡眠定时、一起看、手势说明都在“更多 → 播放设置”里，排在光盘导航、Anime4K、帧率、氛围光之后（`PlayerSettingsPanel.kt:788-793,937-970`）；睡眠定时只记选项、不显示剩余时间（`PlayerPanelState.kt:224-226`）；“发送弹幕”在“显示设置”里（`DanmakuPanel.kt:255-263`）。锁定、切版本要点 4 次并滚动。 | 锁定键常驻屏幕侧边；版本并入“线路”键；顶栏显示睡眠定时剩余时间；按任务拆分“播放设置”。 | M |
| P2-33 | **提示“网络速度不足”却不给办法。**胶囊文案 `"网络速度不足 · 已缓冲 N 秒"`（`PlayerRoot.kt:2735`）不可点（`PlaybackExperienceOverlay.kt:202-235`）；转码在“更多 → 播放内核”，没有码率档位；错误页刻意不提供转码（`PlayerChrome.kt:103-106`），替代方案一行不换行，内核徽标取标签前 3 字、每行都显示“本视频”（`PlayerSettingsPanel.kt:1430`）。 | 胶囊加“降低画质”，提供原画 / 8 / 4 / 2 Mbps 并按服务器记忆；解码类错误提供“服务器转码”；替代方案可换行、标签缩短、修正徽标取值。 | M |
| P2-34 | **滑动快进精度随片长变化，说明与实现不符。**跳转量 `totalX / size.width * span * 0.45f`（`PlayerControls.kt:857-860`），2 小时影片滑满一屏约 54 分钟，只有文字提示；双击、按键、画中画步长写死 10 秒（:79 等），设置里没有手势项；长按按屏幕三等分且每 300ms 就真正跳转一次（:787-792,636-638），说明却写“长按左半屏 / 右半屏…松手确认位置”“横向滑动 预览并定位”（`PlayerGestureHelp.kt:40-41`）。 | 固定速率加加速度并显示 trickplay 预览；步长做成设置项；修正说明。 | M |
| P2-35 | **手势与系统边缘冲突、未处理挖孔。**只判断 `originY >= systemTopPx`（`PlayerDragGestures.kt:13-16`），全仓没有 `systemGestureExclusion`；主题没有设置挖孔模式（`themes.xml:27-31`），控件只留固定 22dp 边距（`PlayerChromeRefined.kt:154,486`）。 | 手势起点排除左、右、下三边系统手势区；进度条设手势排除区；控件边距加 `displayCutout`。 | S–M |
| P2-36 | **只能朝一个方向横屏，窄屏底栏放不下。**Manifest 是 `screenOrientation="landscape"`（`AndroidManifest.xml:158`），只有平板改为 `FULL_USER`；底栏两组按钮加边距约 684dp（`PlayerChromeRefined.kt:616-672`），640dp 宽或调大“显示大小”时会互相挤压（静态推断）。 | 改为 `userLandscape`；宽度不足时把低频按钮收进“更多”。 | S–M |
| P2-37 | **底栏图标小且无文字，画面比例状态不清。**底栏按钮都是 `CircleControl(…, 26.dp, 12.dp)`；画面比例在适应 / 裁剪 / 拉伸间循环（`VideoEngine.kt:234-239`），按键只区分 `filled = scaleMode != Fit`（`PlayerRoot.kt:2904`），反馈用系统 Toast（:3474），想从填充回到适应必须先经过变形的“拉伸”。 | 图标 ≥18dp、首次显示文字标签；画面比例改为弹出选择并用手势提示反馈。 | M |
| P2-38 | **浮层位置重叠。**跳过按钮 `bottom = 92.dp`、下一集卡 `bottom = 96.dp`（`PlayerControls.kt:1079,1622`），进度行在距底约 68–112dp；缓冲胶囊 `top = 68.dp`（`PlayerRoot.kt:2821`）与一起看房间条 `top = 74.dp`（`PlayerControls.kt:1419`）重叠（静态推断）。 | 右下浮层统一堆叠；下一集卡出现时隐藏“跳过片尾”。 | S |
| P2-39 | **二级页按返回整个面板关闭。**`onBack = { settingsPanelKind = null }`（`PlayerControls.kt:1102-1104`），二级页 `advancedPage`、弹幕“显示设置”没有自己的返回。 | 二级页先回面板首页。 | S |
| P2-40 | **普通文件没有章节。**请求带了 `Chapters`（`EmbyDetailService.kt:236`），但 `PlayerMediaItem` 没有章节字段，带章节标记的进度条函数只在测试里调用（`PlaybackProgressMarkersCompat.kt:10-14`）。 | 映射章节，进度条画刻度并提供章节列表。 | M |
| P2-41 | **半透明白字对比度不足。**约 19 处次级文字 alpha 0.40–0.55，如参数行 0.44（`PlayerChromeRefined.kt:214`，纯黑底约 4.25:1）、“选择后立即生效”0.40（`PlayerSettingsPanel.kt:1553,1582`，约 3.66:1）、“已续播”0.55（`PlayerResumeNotice.kt:94`）。 | 次级文字 alpha 不低于 0.62，并纳入对比度断言。 | S |
| P2-42 | **暂停屏保点一下就继续播放。**暂停 5 分钟出现屏保（`PlayerRoot.kt:3754`），屏保写“已进入屏幕保护 · 点击继续”，点按即 `playbackGate.play()`（`PlaybackExperienceOverlay.kt:172-186`、`PlayerRoot.kt:3717-3721`）。 | 点按只关闭屏保并显示控件。 | S |
| P2-43 | **播放器内的无障碍与大字号细节。**内核选择行无选中语义、勾选图标无描述（`PlayerSettingsPanel.kt:1434,1476`）；续播提示 3 秒即消失（`PlayerResumeNotice.kt:29,61`）；倍速文字放在 26dp 圆环里、自动跳过胶囊固定 `height(30.dp)`（`PlayerChromeRefined.kt:689,827-842`）。 | 内核行用 RadioButton 语义；读屏开启时续播提示保留到用户操作；胶囊自适应尺寸。 | S |

#### TV

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-44 | **媒体库网格与搜索结果：固定头部占掉视口。**头部、排序、类型三行在网格外不随内容滚动（`TvLibraryScreens.kt:426-558`、`TvSearchScreen.kt:141-190`），网格 `Adaptive(142.dp)`；按 960×540dp 估算，卡片约 296–316dp 高，可视区只有约 243–246dp，看不到一张完整海报。 | 头部与筛选做成网格首行随内容滚走；固定 6–7 列。 | M |
| P2-45 | **焦点丢失与不可预期的跳转。**选择“最近搜索 / 建议”后被聚焦的按钮随加载态移除（`TvSearchScreen.kt:194-195,266-268`）；切换媒体库服务器后整页换成加载态（`TvLibraryScreens.kt:97-100`）；日历、TMDB 页每行首卡按左都跳回“返回”（`TvDiscoveryCalendarScreens.kt:234,291-300`）；内容加载时焦点会被从导航栏拽走（`TvUiComponents.kt:812-823`）；详情主演、相关行的次级导航 requester 从未 attach，左键被吞（`TvDetailScreen.kt:67,352,370`）。 | 提交前移焦到稳定控件，结果到达且页内无焦点时聚焦首卡；加载态只替换内容区；去掉行内“按左回返回”；用户在导航栏移动时结束当前 tab 的恢复。 | S |
| P2-46 | **获焦时选中态消失；剧集卡确认键行为不可预期。**获焦时底板、描边插值到焦点色和白色（`TvUiComponents.kt:433,438,564`），选中标记随之消失；剧集行“按一次选择，再按一次播放”（`TvDetailScreen.kt:725-736`）。 | 获焦时保留勾选或角标；TV 剧集卡确认直接播放。 | S |
| P2-47 | **长按与菜单键几乎没用，电视上删不掉服务器。**`RemoteIntent.Menu` 在 tvApp 没有处理；只有服务器卡接了长按，提示却写“长按菜单可编辑”（`TvServersSettingsScreens.kt:225,277`）；`removeServer` 在 TV 上没有调用方，编辑弹窗只有“取消 / 快速连接 / 连接”（:451-471），设置副标题却写“添加、切换与登出媒体服务器”。 | 菜单键与长按确认统一打开卡片快捷菜单（标为已看、收藏、从继续观看移除）；编辑弹窗加“删除服务器”并确认。 | M |
| P2-48 | **常用入口路径长。**网格和日历初始焦点落在“返回”（`TvLibraryScreens.kt:413,436-446`、`TvDiscoveryCalendarScreens.kt:271`）；详情次级操作约 782dp 超出视口约 618dp，“更多”在最后且无滚动提示（`TvDetailScreen.kt:566-570,628-639`）；“查看全部”只在行尾，一行 16 项。 | 首焦点给第一张内容卡；“更多”前移或加渐隐提示；行标题可聚焦或把“查看全部”放行首。 | S |
| P2-49 | **设置交互不符合遥控器习惯。**选择型设置只能按确认单向循环（`TvSettingsScaffold.kt:270-274`），“播放器加载动画”有 9 项；所有设置行都设了“按左回导航”，没有目标时左键也被消费（:165，`TvUiComponents.kt:411-417`）；输入框用 M3 `OutlinedTextField`，静止边框约 1.45:1（`TvAccountScreens.kt:49-61`、`Theme.kt:244`）。 | 左右键在行内切换上一项 / 下一项；没有导航目标时不消费左键；封装 `TvTextField`（静止边框 ≥3:1，获焦白边加底板）。 | S |
| P2-50 | **仍嵌手机控件和手机页面（09-24 P2-31 残留）。**详情页个人清单按钮是手机组件（46dp 高、13sp，`DetailActions.kt:253,307`）；统一媒体库每张海报下多一个“N 个片源 · 选择”焦点；个人中心、家庭资料、同步状态、设备接力、Trakt 直接嵌手机页，外层没有顶部安全边距（`TvProductSettingsPages.kt:31,43-68`），字号 11–13sp。 | 换成 `TvActionButton` / `TvMediaCard`；嵌入页补安全边距并放大字号，或改写为 TV 原生页。 | M |
| P2-51 | **TV 上无效或无意义的设置。**“服务器列表布局”在 TV 无效（服务器页固定 3 列，`TvServersSettingsScreens.kt:160`）；播放器“更多”里的“锁定控制”“手势说明”是触屏功能；TV 表面按设计不透明，却提供“玻璃质感”“玻璃材质”（`TvAppearanceScreens.kt:43-71`）。 | TV 上隐藏，或写明作用范围。 | S |
| P2-52 | **TV 无障碍语义基本为零。**tvApp 里 heading、liveRegion、paneTitle 都是 0；设置行读屏描述固定为“标题，值”，副标题说明读不到（`TvSettingsScaffold.kt:156`）；6 个 TV 弹窗没有 paneTitle；“播出日历”弹窗里只有“关闭”可聚焦，列表无法滚动（`TvDetailMoreActions.kt:319-361`）。 | 复用 `liveStatus` / heading；描述拼上副标题；新增 `TvStatusLine`；日历行做成可聚焦的只读行。 | S |

#### 设计系统与规范落地

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-53 | **间距阶梯定义了但几乎没用。**`Dimens.space`（`Tokens.kt:550,582-589`）在 feature 只用了 6 次（全在 `DeviceHandoffScreen.kt`），`cardGap` 0 次而 `spacedBy(14.dp)` 有 15 次；feature 有 1,901 个 dp 字面量、115 种取值，不在阶梯上的 851 个（其中间距 577 个）；同一种行在两个文件里差 1dp（`AiringShowCalendarDialog.kt:444` 与 `SeriesAiringCalendarSheet.kt:494`）。 | 先上“每文件 dp 字面量只减不增”的棘轮，再按热点文件（ServersTabScreen、CalendarScreen、SeriesAiringCalendarSheet、SearchScreen）分批映射。 | L |
| P2-54 | **caption 用 Manrope 11sp 承载中文正文，违背设计系统自定的 12.5sp 中文下限。**`MIN_BODY_SP = 12.5f` 的注释说明 `mr` 只用于数字和短拉丁标签（`Tokens.kt:890-921`），但 `caption = mr(11f…)`，连 `reading` 也是 11sp（`SemanticTypography.kt:53-61`）；feature 里 caption 用了约 450 次，其中至少 222 个 Text 首参是中文字面量（如 `DetailDialogs.kt:155-158` 的整句说明、`SeasonEpisodesPage.kt:310-316` 的剧集简介）。 | 拆成 `caption`（sc，12.5sp 下限）与 `numeric`（mr，11sp）；`reading` 改为 sc(12.5)；配合截图测试回归。 | M |
| P2-55 | **对比度只按纯页面底色校准。**深色 `hint` 在 card 上 4.26、card3 上 3.83；减弱透明度用的不透明底比半透明合成更亮，hint 在 card3 上仅 3.38；契约测试用 `dialogTint.compositeOver(background)`，运行时的 `perceivedSurface` 会先叠遮罩，按运行时模型浅色 `dialogHint` 为 4.31（`DesignSystemContractTest.kt:51-58`、`GlassMaterial.kt:98-104`）。实例：`SearchScreen.kt:800-803` 的连接失败原因用 `hint` 放在 card3 上。 | 契约测试扩成“文字角色 × 全部表面（含 reducedFill 与 perceivedSurface）”矩阵；深色 hint 提到约 #8D94A0 或在 card3 上改用 sub2；按最弱角色设闸门。 | S–M |
| P2-56 | **androidMain 的“我的”工具页是另一套 UI，门禁看不到。**诊断日志页用 M3 `Switch` 配 `pressable`，只有按钮角色、没有开关状态语义（`DiagnosticLogTools.android.kt:271-296`），还有 `Brand.Danger`、`Text("›")`、`mr(10.5f…)`、`RoundedCornerShape(10.dp)`（:236-331）；服务器迁移用 M3 `OutlinedTextField` 与 14 / 13dp 圆角（`ServerBackupTools.android.kt:394,426,490`）；“检测升级 ›”等 12 条文案拼进“›”，而 `SettingRow` 可点击时本身就画箭头，出现双箭头（`AppUpdateTools.android.kt:44-52`、`PermissionHealthTools.android.kt:78-118`）。 | 迁到 `SwitchRow` / `SettingRow` / `YfFormField` / `palette.error`，删掉文案里的“›”；门禁扩到 androidMain（见第七节）。 | M |
| P2-57 | **缺页面级原语与组件变体，导致私有实现。**返回键至少 5 种写法（`SeasonEpisodesPage.kt:174-190`、`LibraryGridScreen.kt:186-199`、`ProfileScreen.kt:1197-1212`、`TmdbRowPage.kt:82-92`、`TmdbInfoScreen.kt:169-178`）；页面标题字号三档混用；`SettingsPage` 等仍在 feature/profile 被三个 feature 引用；私有可选胶囊至少 6 种、私有徽标 5 种、分组标签 6 种；`YfFormField` 没有 keyboardActions、错误文本、密码显隐（`FormControls.kt:42-55`），于是有 14 处裸 `BasicTextField`。 | 新增 `PageHeader(title, onBack, actions)` / `YfBackButton`、`YfChip(kind = Filter/Choice/Action)`、`YfBadge(tone)`、设计系统级 `GroupLabel`；补齐 `YfFormField` 后迁移私有输入框。 | M |
| P2-58 | **子页底部避让仍有两套算法（09-24 P2-7 只统一了根 tab）。**详情、全部剧集、TMDB 信息、TMDB 行、账号、下载页固定 `Dimens.contentBottom`（122dp，如 `DetailScreen.kt:528`），而网格、设置、日历页用 `systemNavigationContentInset()`；没有 Dock 的子页末尾因此多出约 50–80dp 空白。 | 无 Dock 页统一用 `systemNavigationContentInset()`，`TabBarInset` 标记弃用。 | S |
| P2-59 | **封面页的强调色按固定底色校验。**`resolveAccentColors` 只对 `#182235` 或白色校验（`Theme.kt:122,139`），ArtworkPageTheme 的文字角色按真实背景校正、强调色却不是（`ArtworkPageTheme.kt:97`）；详情页为此另写私有解析器（`DetailStateColors.kt:14-43`），共享控件拿到的仍是旧值。按代码模型估算 12 种封面色，强调色对页面的对比度中位约 3.0:1（静态推断）。 | `resolveAccentColors(base, surface)` 以实际背景为参照，删掉详情页私有解析器。 | M |

#### 无障碍与文案

| 编号 | 问题（位置） | 改进方向 | 量 |
| --- | --- | --- | --- |
| P2-60 | **标题与弹窗语义没覆盖全（09-24 P2-13 残留）。**paneTitle 只由 `OverlayHeader` 写入（`Dialogs.kt:443,626`），62 个 `GlassDialog` 调用点中 18 个不经过它：`ConfirmDialog`（`Dialogs.kt:773-785`，本身又有 15 处调用，多是删除、退出确认）、11 个手机端自定义标题弹窗、6 个 TV 弹窗；页面大标题、设置子页标题（`ProfileScreen.kt:1186`）、播放器分组标签（`PlayerControlComponents.kt:90`，39 处）都不是 heading。 | `GlassDialog` 增加 `title` 参数写入 paneTitle；抽 `PageTitle()` 自带 heading；`GroupLabel` 加 heading。 | S |
| P2-61 | **控件角色不准确。**`YfChip` 默认 `Role.Tab`（`Chip.kt:43`），14 个调用都没传：动作“全选 / 反选”读成“标签，未选中”（`EpisodeProgressManager.kt:96,120`）；默认单选角色的 `OverlayOptionRow` 被用作动作或开关，读成“取消收藏，单选按钮，已选中”（`LibraryGridScreen.kt:471-488`、`SmartPlaylistShelf.kt:106,124`）。 | `YfChip` 去掉默认 role；动作行用 `OverlayActionRow`；新增 `OverlayToggleRow`（Switch 角色，标签不随状态换成反义动词）。 | S |
| P2-62 | **大字号下关键文字被硬切。**弹窗标题、弹窗按钮、底栏标签、分段控件 `maxLines = 1` 无省略号（`Dialogs.kt:632-637,737-742`、`App.kt:876`、`SelectionControls.kt:189-193`），全库 90 个带 maxLines 的 Text 没设 overflow；固定高度胶囊（`LibraryGridScreen.kt:224` 34dp）；应用“大号文字”在系统缩放上再乘 1.12 且无上限（`Theme.kt:307-312`），系统 200% 时实际 224%。 | Text 默认省略号；固定 height 改 heightIn；系统字号已 ≥1.3 时不再叠加并加说明；加系统字号 2.0 的截图测试。 | S–M |
| P2-63 | **内部代号外露，同一引擎多个名字。**“YCore 2.0 …”开头的错误进入播放错误层（`AndroidNativeDirectYPlayer.kt:3336-3350`）；系统 Toast“YCore 2.0 播放失败，已切回兼容内核”等（`PlayerRoot.kt:794,2358,3207`）；ExoPlayer 有“兼容优先（ExoPlayer）/ 系统兼容模式 / 系统 Media3 内核 / 兼容内核 / Exo”五种叫法（`ProfileSettingsScreens.kt:42-80`）。 | 用户只看到角色名（兼容 / 格式 / 原生），技术名放进诊断；去掉错误文案的“YCore 2.0”前缀；播放器提示改用设计系统 Toast。 | M |
| P2-64 | **术语不统一。**“片源”有版本、片源、线路、资源、来源、媒体源 6 种叫法；“线路”一词三义（服务器备用地址、跨服务器片源、音频路由）；“稍后观看 / 稍后看 / 想看 / 片单”混用，网格空态按标题文字判断（`LibraryGridScreen.kt:785-797`）；“95分钟”与“95 分钟”、「」与“”并存。详见附录术语表。 | 定一张术语表（附录），按表统一；空态按容器枚举分支。 | M |
| P2-65 | **国际化基本为零，格式化各写各的。**界面代码 4,071 条中文字面量，strings.xml 3 条，无应用内语言设置；繁体 / 英文系统上读屏角色词随系统语言、标签却是简体；文件大小 6 套实现、相对时间 5 套、星期表 4 份。 | 最小路径：① 收拢 `Formatters`（以 Locale 为参数）；② 去掉以展示文字驱动逻辑的分支；③ 引入 `composeResources` 字符串，先抽设计系统组件与错误文案；④ 配 `localeConfig`。 | L |
| P2-66 | **其余不可逆操作也缺确认或撤销。**清空搜索历史（`SearchScreen.kt:1087`）、清除追更规则（`DownloadsScreen.kt:417-420`）、退出一起看房间（`ProfileScreen.kt:1084`、`PlayerRoot.kt:3678`）、TV 下载删除（`TvDownloadsScreen.kt:167-183`）、TV 日历“发现”卡按确认即切换追剧（`TvDiscoveryCalendarScreens.kt:399-404`）。 | 统一规则：删除用户数据或影响他人（房主退出）的操作必须确认或提供 5 秒撤销。 | S |

### P3（打磨与清理）

**接入、壳与设置**
- 服务器页指标“已连接”显示的其实是已保存服务器数（`ServersTabScreen.kt:859-865`）；“进入”按钮只有 `heightIn(min = 42.dp)`、没有 `touchTarget()`（:836-841）；“测试连接”“复制地址”没有进行中或完成反馈（:400-405）。
- `AppUpdateOverlay.kt:110,158,182` 与 `AppUpdateTools.android.kt:90,95` 写死字号 10–12sp；会话恢复页用原生 M3 `darkColorScheme()` 和 M3 Button，跟随系统而不是应用主题（`ServerSessionRecovery.android.kt:86-101`）。
- 背景图遮罩的存储范围是 0–1、滑块却是 0.3–1（`ThemePreferences.kt:215,274`、`ProfileScreen.kt:1462-1466`），亮壁纸配 30% 遮罩时页面文字对比度约 1.4–1.8:1（静态推断）。

**浏览**
- 同一类东西叫法不同：“继续观看”与“播放记录”（`HomeScreen.kt:1240`、`LibraryHomeScreen.kt:1280`），“追剧日历”与“追剧中心”；“查看全部”有时是跳转、有时是清除筛选（`LibraryGridScreen.kt:771`、`CalendarScreen.kt:324`）。
- 继续观看的来源角标写死为“Emby”（`HomeScreen.kt:1240,1315`），Jellyfin、Plex 用户也看到 Emby。
- 首页货架标题是私有组件、没有 heading，五六个“全部”按钮读起来都只是“全部”（`HomeScreen.kt:1387-1420`）；日期头的箭头重复朗读“展开”，操作标签念 ISO 日期（`CalendarScreen.kt:755,805-807`）。
- 货架间距 10 / 11 / 12dp 混用；长按菜单首页货架和网格有、库首页货架和搜索结果没有；网格没有下拉刷新；网格空态按标题字符串分支（`LibraryGridScreen.kt:785-799`）。
- Trakt 的成功和错误消息同色、追加在页面最底部、不播报；“请先登录鱼服账号”点不了（`TraktSettingsScreen.kt:53,159-166`）。

**详情**
- 码率用整数除法，800 kbps 显示为“0 Mbps”（`MediaVersion.kt:74`、`DetailFileSections.kt:251`、`Dtos.kt:488`）。
- 评分一律标“TMDB”（`DetailHero.kt:606`），Plex 取的却是 `audienceRating ?: rating`；特别篇 S0E2 显示为“第 2 集”（`DetailScreen.kt:289`）。
- 只有一台服务器时也常驻“资源比较 · 全部 · 1”（:604），把选集区往下推。
- “从头”键紧贴“继续播放”（`DetailActions.kt:184-203`），从头播放后播放器不提供“回到上次位置”，误触会覆盖续播点。

**播放器**
- 通知小图标用系统播放图标、电影也显示上一集 / 下一集（`PlayerNotificationController.kt:57,72-92`）；两处用同一渠道 ID 创建了“播放控制”“后台播放”两个名字；媒体会话只有标题和时长，没有封面和剧名；每次打开播放器都请求通知权限（`PlayerActivity.kt:696`）；画中画进入失败没有提示。
- 播放器共享代码约 162 处 `Color.White/Black.copy(alpha = …)`，`PlayerTokens` 只被引用约 20 处；侧抽屉 `RoundedCornerShape(24.dp)` 不在三档内（`PlayerPanel.kt:79-80`）；系统 Toast 14 处。

**TV**
- 启动器集成：`requestBrowsableFromForeground` 没有调用方，频道建了不会显示在主屏（`AndroidTvProviderPublisher.kt:142`）；Watch Next 没有季、集信息；深链解析失败只写日志。
- “我的与设置”缩字号到约 14sp，低于 16sp 下限（`TvUiComponents.kt:498-500`）；设置行标题没有 maxLines；服务器名称、地址截断没有省略号；详情英雄区贴顶；920dp 的大弹窗边距只有 24dp。
- “设备会话”按返回直接回设置根而不是“账号与同步”（`TvSettingsScreen.kt:58-60`）；首页根部按返回直接退出。

**设计系统与文案**
- `reading` 变体已经加了，设计系统自己仍写 `body.regular.copy(lineHeight = 21.sp)`（`PageStates.kt:70,111`、`Dialogs.kt:789`）；feature 还有 9 处散点行高。
- 残余原始色：`Color(0xFFFFC857)` 3 次绕过 `Semantic.Warning`；`SeriesAiringCalendarSheet.kt:779` 用装饰色 `DecorativeTints.coral` 作错误提示色。
- 死代码与弃用：`YfLinkButton` 零调用（`FormControls.kt:244`）、`ThemePreferences.accent` 零引用、`GlassShapes` 弃用别名 15 处、`BackOverlay.kt:38` 圆角字面量。
- 读屏细节：海报长按没有标签（`Poster.kt:245,289-295`）；杜比徽标读成“VISION”“ATMOS”（`DolbyMark.kt:116-122`）；模糊的装饰背景图带描述；`SettingsControls.kt:166` 用 `onClickLabel = title`。
- 标点与格式：引号「」与“”并存（`SearchStore.kt:590` 与 `App.kt:540`）；“服务器错误($code)”用半角括号与冒号（`EmbyErrorMessages.kt:18,20`）；“95分钟 / 95 分钟”“3天前 / 3 天前”并存。

**周边**
- 投屏接收页：`<cast-media-player>` 没有设置 splash、logo、进度样式，待机和加载沿用 CAF 默认画面；错误层底色 `rgba(8,10,14)` 与品牌色 `#080D17` 不一致；字幕样式写死，手机端的字幕设置不会传过去（`castReceiver/index.html:10-20`、`receiver.js:244-258`）。
- 桌面小组件：只有两行文字可点；背景固定深色 `#242424`；不足 1 分钟显示“已观看 0 分钟”（`ContinueWatchingWidget.kt:77`）；没有预览图与描述。
- 下载通知：失败或暂停时仍用“下载中”图标，标题只有数量。
- HarmonyOS（源码接线、未构建）：界面里有会随包出现的开发说明与假数据，如“内容简介会完整呈现，并保持 Android 的海报底部渐隐与页面动态取色。”（`harmonyApp/entry/src/main/cangjie/src/ui/secondary_screens.cj:18`）、“正在热映 · Living Poster”（`ui/screens.cj:23`）；UI 全是字面量字号，没有无障碍属性；启动窗是浅色 `#F3F5F8`，而 Android 默认深色；tabBar 高 56，Android 是 62。发布门禁应加“禁止占位文案”检查。

## 五、1.0.86 修复核验

逐项核对了 09-24 清单的 3 个 P0、16 个 P1，以及 23 个指定的 P2。下表“条目”一列是 09-24 清单的编号，与本文第四节的编号无关：

| 结果 | 数量 | 条目 |
| --- | --- | --- |
| 已修复 | 33 | P0-1、P0-2、P0-3，P1-1、P1-2、P1-3、P1-4、P1-6、P1-7、P1-9、P1-10、P1-11、P1-12、P1-13、P1-15、P1-16，P2-2、P2-5、P2-6、P2-7、P2-11、P2-12、P2-14、P2-15、P2-16、P2-17、P2-20、P2-24、P2-26、P2-30、P2-32、P2-35、P2-45 |
| 基本修复 | 5 | P1-8、P1-14、P2-1、P2-3、P2-29 |
| 部分修复 | 4 | P1-5、P2-4、P2-13、P2-25 |
| 未修复 | 0 | — |

androidMain 与 tvApp 本轮没有真编译。按以下几类交叉核对，没有发现能确定的编译错误：expect/actual 配对、新增枚举项的 `when` 穷举、改了签名的调用点、跨模块的 internal 访问、删除的 import、API 级别保护、新增资源。按 `AGENTS.md`，出包前仍需在本地完成构建与签名核验。

**残留问题**（编号沿用核验线）：

| 编号 | 级别 | 问题（位置） | 建议 |
| --- | --- | --- | --- |
| VF-1 | P2 | **进场转场期间，控制栏看不见却可操作。**控制层只设 `graphicsLayer { alpha = transition?.chromeAlpha() }`（`PlayerRoot.kt:3700`），默认的“转身”每次约 0.9–1.3 秒，这段时间里误触会点到看不见的控件，TalkBack 也会停在看不见的节点上；视频早就绪时仍等固定落点（`PlayerTransition.android.kt:241`），声音先于画面。 | `chromeAlpha() < 1` 时清空语义并拦截指针；视频就绪早于落点时压缩剩余编排。 |
| VF-2 | P2 | **弹窗标题播报只覆盖约七成的弹窗**（62 个调用点中 44 个），即本文 P2-60。 | 同 P2-60。 |
| VF-3 | P2 | **推进时两页仍同时半透明。**出场淡出只从 120ms 延到 180ms（`OfficialNavDisplay.kt:372`），入场 280ms；按 `Motion.Curve` 计算，第 33ms 新页 α≈0.33、旧页 α≈0.40。切 tab 的等长交叉淡化中段仍透出底层背景。 | 推进时旧页保持不透明、只轻微压暗（09-24 的原建议）。 |
| VF-4 | P3 | **“操作成功后再确认”没有落地。**按下时的 Confirm 改成了 Tap，但结果出来后没有补播 Confirm / Reject；危险按钮仍一按下就播 Confirm（`AccountSessionsScreen.kt:496,573`、`AccountSettingsScreen.kt:904`）。 | 在结果回调里播 Confirm / Reject；危险按钮按下时不播 Confirm。 |
| VF-5 | P3 | **所有带 key 的海报仍注册转场起点**（`Poster.kt:310`），与 `PlayerArtworkOrigin.kt:129` 的注释相矛盾；`:139` 在组合期读 `LocalRouteVisible`，路由一翻转所有海报都会重组。屏幕几何的 Binder 调用确实已经移到点击时。 | 只在英雄区和播放键注册；可见性改在 effect 或绘制阶段读取。 |
| VF-6 | P3 | **09-24 要求的回归测试没有加**：A→B→A 入栈、`onDismiss` 不移除弹窗、TV 可聚焦节点宽度 >0、入场途中路由不可见。推进的 280ms 内仍能点中旧页海报并再次入栈，只是不会再崩溃。 | 补测试；正在退出的页面在 `PointerEventPass.Initial` 消费指针。 |
| VF-7 | P3 | **轮播暂停只照顾到手机读屏。**TV 首页轮播仍是私有的 `delay(8_000L)` 循环（`TvHomeScreen.kt:83-85`）；手机上没有可见的暂停方式，不开读屏的用户仍不满足 WCAG 2.2.2。 | TV 复用 `CarouselAutoAdvance`；手动翻页后本次会话不再自动播放，或恢复暂停键。 |
| VF-8 | P3 | **关闭路径的边角。**元数据弹窗加载或保存时，关闭、返回、遮罩全部无效；接力确认框第二次拒绝失败时错误文案与上次相同，按钮会锁到 15 秒超时（`DeviceHandoffScreen.kt:150-151,198`）。 | 忙碌时关闭改为“取消请求并关闭”；失败文案带次数或时间。 |
| VF-9 | P3 | **零散遗漏。**分段控件只到 36dp、没有 `touchTarget()`（`SelectionControls.kt:178`）；`SmartPlaylistShelf.kt:97` 的命名弹窗关闭时一帧消失。 | 补 `touchTarget()`；改走 `DialogPresence`。 |
| VF-10 | P3 | **路由可见性有两套信号。**`LoadingAnimationSheet.kt:53` 只覆盖了 `LocalRouteVisible`，改用 `rememberRouteVisibility()` 的主题文字、图片揭示、光粒会读到外层的“可见”。 | 统一为一个信号。 |

**1.0.86 更新说明核对**：逐条核对 `release-notes.txt` 的 1.0.86 段落，其余声明都有对应代码。以下 3 句超出了实际：

| 更新说明原文 | 实际 | 建议 |
| --- | --- | --- |
| “推进页面和切换 tab 时不再两页同时半透明” | 仍有重叠，只是缩短了（VF-3） | 下一版修复后再写；或改为“减轻推进时两页同时半透明” |
| “长按只震动一次，操作成功后再确认” | 前半句属实，后半句没有代码（VF-4） | 同上 |
| “弹窗打开时播报标题” | 只覆盖经 `OverlayHeader` 的弹窗，`ConfirmDialog` 等 18 个调用点不播报（VF-2） | 同上 |

`AGENTS.md` 要求更新说明记录本次实际改动。建议在下一次打包时，把这三句的修正和相应修复一起写进新版本的说明，不回改已交付版本的说明。

## 六、建议实施顺序

1. **止血（1–2 天，基本是 S 级）**：
   - P0-1、P0-2。
   - 功能结果错误：P1-12（一起看重建房间）、P1-17（关闭连播后的片尾）、P1-9（片单筛选）、P1-14（锁屏）、P1-15（亮度）、P2-23（重置等于标记未看）、P2-27（外链类型）、P2-42（屏保点一下即播放）。
   - 可见回归：P1-6（库首页标题贴边）。
   - 不可逆操作：P1-5 与 P2-66，统一“确认或 5 秒撤销”。
   - TV：P1-24 的进度条焦点陷阱部分、P1-25 的服务器弹窗确认。
2. **接入与失败恢复（约 1 周）**：
   - P1-1 到 P1-4，P1-22（错误播报与原文外露），P2-1（返回栈），P2-6（账号说明）。
   - 目标：新用户从安装到首次播放不需要猜。首次连接失败时，能看到原因和下一步。
3. **高频任务（1–2 周）**：
   - P1-7（继续观看链路）、P1-8（TMDB 不可用时的首屏）、P1-10（详情状态）、P1-11（同语言字幕）、P1-13（下载闭环）、P1-16（投屏）、P1-18（跳过片头）、P1-19（字幕面板）、P1-23（时区）。
   - 播放器信息架构：P2-32、P2-33。
   - 完成后在真机上走一遍“追剧用户的一晚”：继续观看 → 下一集 → 跳片头 → 切字幕 → 投屏 → 锁屏。
4. **TV 专项（1–2 周，需要一台 Android TV 真机）**：
   - P1-24 与 P1-25 的其余部分，P2-44 到 P2-52。
   - 重点是共享播放器的 TV 版控制层，以及登录免打字（Plex PIN、手机扫码）。
5. **规范落地与守护（本季）**：
   - P1-20、P1-21 与 P2-53 到 P2-66。
   - 先扩门禁、上截图测试，挡住回归（见第七节）；再按热点文件分批收敛间距、组件与术语。

## 七、守护：自动检查与测试

**扩展 `verifyDesignSystemUsage`**（豁免统一写成同行 `// design-system: <规则> <理由>`，另附只许减少的基线文件）：
1. **修遮蔽**：先匹配字符串（`"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"`），再匹配注释，只把注释替换成空格；import 规则改扫原文。
2. **扩范围**：
   - 设计规则扩到 androidMain 的 feature / app / update / widget / core/designsystem / tv，以及 tvApp（白名单 `TvTokens.kt`）。
   - `Type` 规则改为 `(?<![\w.])Type\.(?:display|section|body|caption)\(`，避免误报 `WindowInsetsCompat.Type`。
3. **新规则**：
   - 圆角：`RoundedCornerShape(` / `ContinuousRoundedCornerShape(` 的字面量参数，白名单为定义文件，放行 `percent = 50`。
   - 字号：`fontSize = N.sp`、`TextStyle(`，字幕与弹幕用 content-scaled 豁免。
   - 颜色：`Color(0x`；把 `Semantic.Error` 并入 `Brand.Danger` 规则。
   - 状态色当文字色：`color = Semantic.*`、`color = Brand.Online/Offline`。
   - 组件：`GlassShapes.`；直接 import 的 Material3 交互组件；`.clickable(`（白名单 `Interaction.kt` 和 tvApp）；`BasicTextField(`（白名单 `FormControls.kt`）。
   - 文案：字符串里的“›”；UI 层的 `${…message}` 和 `.message ?:`；非诊断文案里的“YCore”“MDK”。
4. **dp 棘轮**：每个文件的 dp 字面量计数只许下降；新文件的间距参数里禁止 3 / 5 / 6 / 7 / 9 / 10 / 11 / 13 / 15dp。

**新增 JVM 测试**（对应本文问题）：
- 儿童资料下，服务器 Remove / Submit / Rename 不抛异常（P0-1）。
- TV 准备页上按方向键、确认键、返回键不被吞（P0-2）。
- 打开智能片单后，清空或输入新词会清掉片单筛选（P1-9）。
- 已在一起看房间时，“分享邀请”不重建房间（P1-12）。
- `autoNext = false` 时，下一集卡不显示倒计时（P1-17）。
- 同语言多条字幕的标签可区分，选中判定按索引（P1-11）。
- 追剧中心改提醒模式时保留分钟数（P2-15）。
- 播出时间按设备时区格式化（P1-23）。

**截图与 UI 测试**：
- 仓库目前没有任何截图或视觉回归测试；`androidInstrumentedTest` 以动效为主，测试集中 0 个语义断言。
- `commonMain` 已能用桌面依赖编译（1.0.86 的核验就是这样做的），可以先用 Compose Desktop 渲染设计系统组件和几张关键页面：库首页、详情、设置、服务器卡片。每张分别在深浅主题、系统字号 1.0 与 2.0 下出图比对。
- 首批就能挡住 P1-6（标题贴边）、P1-20（状态色）、P2-62（大字号截断）这类回归。
- **可访问性断言**，用 Compose UI 测试而不是正则：
  - 有点击动作的节点必须有名称。
  - `GlassDialog` 必须有 paneTitle。
  - 错误文本必须带 liveRegion。
  - 页面标题是 heading。
  - 系统字号 2.0 下关键控件不溢出。

## 八、做得好的地方

- **设计系统**：
  - 动效令牌与门禁到位，三套源码 0 违规。
  - 字体和圆角基本统一，feature 里 0 处直接使用 Material3 组件、0 处 `.clickable`、0 处 Material Icons。
  - 09-17 建议升格的 `SettingRow` / `SwitchRow` / `Section` / `SettingsCard`、`YfChip`、`SectionHeader`、`RecommendBadge` 都已进入设计系统。
  - 默认主题改为深色，TV 强制深色。
  - 封面页的文字角色按真实底色校正（`ArtworkPageTheme.kt:59-73`）。
- **反馈与状态**：
  - 网格换筛选和排序时，等待态显示在发起操作的控件上，旧页变淡并禁止点按，结果到达后回到顶部；排序按库记忆（`LibraryGridScreen.kt:129-153`、`LibrarySortMemory.kt`）。
  - 下拉刷新有读屏自定义动作，只有内容变化才重播入场。
  - 换季时季名脉冲，并播报“正在读取剧集”。
  - 取消追剧可以撤销。
- **多服务器**：
  - 搜索跳过已知离线的服务器，临时失败重试一次，并汇总“已收起 N 台”。
  - 添加服务器能从地址里解析协议、端口和路径。
  - 局域网扫描、Quick Connect、Plex PIN 集中在同一个弹窗。
  - 有内容时禁止快甩关闭表单。
- **播放器**：
  - 触控区用 `touchTarget()` 保底 48dp。
  - 缓冲时播放键仍然可按。
  - 读屏时控件不自动隐藏，点画面可以唤回控件。
  - 错误页提供重试、换版本、换内核、外部播放器和诊断导出。
  - 耳机拔出时暂停；导航播报时只压低音量、不暂停。
- **TV**：
  - 焦点的缩放、描边、底板共用一个临界阻尼时钟，只在绘制期读取。
  - 焦点恢复改为进入页面时只做一次，稳定 ID 优先。
  - 遥控器键位映射完整：隐藏态长按快进会加速，并合并跳转。
  - 下载弹窗已有范围、版本、字幕和追新选项（09-15 O6 已修）。
- **文案卫生**：
  - 4,071 条界面文案里，ASCII“...”、半角标点紧贴汉字、汉字紧贴英文或数字字面量三项都是 0。
  - 按钮用具体动词（确认清空、放弃、继续编辑），没有“确定”“好”。

## 九、未覆盖与限制

- 没有构建、安装，也没有在手机或电视上运行。所有运行时表现都是按代码路径推断的，P0-2、P1-21 等标了“静态推断”的条目需要真机复现。
- 对比度按 token 与颜色模型估算，没有在设备上取色；玻璃材质叠在不同背景上的实际对比度会有差异。
- 读屏的实际读法按 Compose 语义规则推断，没有开 TalkBack 实测。
- HarmonyOS 移植只做了简评；`watchTogetherServer` 与 `ycore-native` 不在范围内；没有与竞品同机对比。
- 审查本身没有改动代码。之后按本报告做的修复记录在第十节；修复同样没有打包，所以不涉及 `version.properties` 和 `release-notes.txt`。

## 十、实施记录（2026-09-25，同日）

按本报告的条目，在分支 `claude/ui-interaction-design-review-tu151k` 上做了修复，基线是 c051d021（1.0.86）。
工作按区域分成 7 条线并行，逐条审阅后合并。

- 本机没有 Android SDK，无法编译，也没有跑过测试。编译、ktlint 和 TV 单测由 CI 的 TV 质量门验证，结果见本节末尾；`commonTest` 只在完整质量门里跑。
- 没有打包，没有改 `version.properties` 和 `release-notes.txt`。发版说明草稿见本节后部，打包时再写入。

### 已修复

| 条目 | 改了什么 | 主要位置 |
| --- | --- | --- |
| P0-1 | 服务器登记表的所有写入（设默认、移除、改名、添加、替换、线路、图标）失败时不再崩溃，改为在表单或页面提示原因。儿童资料下隐藏添加入口、空态卡片和管理菜单，并说明“请用家长 PIN 切换到成人资料”；退出 Yfuse 账号也先检查资料策略。 | `ServersStore.writeRegistry`、`ServersTabComponent.editRegistry`、`ServersTabScreen` |
| P0-2 | 播放准备页和控制层离开组合时，遥控器桥接进入“未挂载”状态，方向键、确认键和返回键交回普通焦点分发；准备页的“重试”自动获焦。 | `TvPlayerChromeState.attached`、`TvRemoteInputController`、`PlayerActivity.showPendingPlayer`、`PlayerScreen` |
| P1-1 | 还没有服务器时，起始页“自动”进入「服务器」。空的库提供“添加服务器”入口。连接成功后提示“已连接「名」”，第一台服务器保存后切到库。私网地址默认 HTTP 和 8096（Plex 为 32400），公网默认 HTTPS 和 443；地址或端口里写明 443、8920 时用 HTTPS；用户选过的协议和端口不会被覆盖。“显示名称”移到端口之后。 | `RootComponent`、`LibraryHomeScreen`、`ServersStore`、`AddServerDialog`、`App.kt`（壳层 Toast） |
| P1-2 | 连接失败细分为证书、找不到地址、超时、连接被拒绝和“不是 Emby/Jellyfin 服务器”，每种都给出下一步。登录遇到 404 或无法解析的应答时，会补一次 `/System/Info/Public` 探测。错误文字会被读屏播报。 | `EmbyError.Unreachable`、`EmbyApiCall.transportError`、`EmbyAuthService.orNotMediaServer`、`EmbyErrorMessages`、`AddServerDialog` |
| P1-3 | 密码框用密码键盘，Plex Home PIN 用数字密码键盘，都关闭自动更正。用户名和密码标注了自动填充类型。在密码框或令牌框按“完成”会提交。 | `ServerFormFields`、`AddServerDialog` |
| P1-4 | 登录失效（AuthRequired）的服务器，点卡片会打开预填好的“重新登录”表单并聚焦密码框；这时一定重新认证，不会保存已被拒绝的令牌；Plex 服务器要填 Token 或用 Plex 账号登录，缺了会说明。登录成功后，服务器立即记为在线；如果是点卡片进来的，会接着选中这台服务器并打开库。库的错误态和缓存横幅也提供“重新登录”。 | `ServersTabScreen`、`ServersStore`、`LibraryHomeScreen` |
| P1-5 | 删除下载（显示条数和占用空间）、清除追更规则、移除家庭资料和个人记录之前，都要先确认。下载页的多选增加了全选和取消全选。 | `DownloadsScreen`、`PersonalCenterScreen` |
| P1-6 | 库首页的分区标题补上页面边距。 | `LibraryHomeScreen` |
| P1-7 | 首页新增“下一集”一行（与“继续观看”去重）。长按快捷操作改为“查看详情”和“标记已看 / 未看”，会同步到服务器和本机进度。“全部”打开该行自己的完整列表，不再跳到库。 | `HomeScreen`、`HomeStore.SetPlayed`、`TmdbRowPage.LibraryRowPage` |
| P1-8 | 没有精选内容（例如 TMDB 不可用）时，首页英雄区折叠为紧凑的页头，不再留出大块空白。横屏时英雄区高度不超过视口的 90%。 | `HomeScreen`、`LivingPoster` |
| P1-9 | 片单的筛选只在片单内有效：搜索页顶部显示片单横幅，可以一键退出；输入新的关键词或清空时，片单带来的筛选一并清除。服务器缺失时的提示改为指向「服务器」页。 | `SearchStore`、`SearchScreen` |
| P1-10 | 标题下方新增只读状态行：服务器收藏、稍后观看、已看完、个人收藏、想看。没有状态时不占位置；点击打开“更多操作”，读屏会读出各个状态。顶栏“…”左边新增收藏键，带选中状态描述和触感反馈；Plex 不支持收藏，不显示这个键。 | `DetailHero`、`DetailScreen` |
| P1-11 | 字幕显示为“中文 · 简英双语 · ASS”这样的形式，音轨在选择器里带上标题。记录用户选的是该语言的第几条，只高亮这一条。多条同语言轨道时，把标题、编码和序号作为提示传给播放器；轨道列表出来后，播放器二次选轨也按提示匹配，不会退回第一条。语言唯一时，请求和原来完全一样。 | `DetailTrackChoice`、`PlaybackTrackRequest.TrackHint`、`InitialPlaybackTracks`、`PlayerRoot`（2 行） |
| P1-12 | 当前影片所在的房间，只打开分享面板，不再解散重建。判断时匹配这部影片的所有 provider id 和服务器 id；剧集页与单集页也认得“正在放这部剧某一集”的房间（`tmdb:1399/s1e1`）。房间影片未知时，也只分享。别的影片的房间，先确认再新建，文案写出片名，并区分房主和访客。“继续分享邀请”只对当前影片所在的房间显示。 | `DetailScreen.watchRoomAction` |
| P1-13 | 加入下载后会提示实际加入了几集、几集已经下载过、几集因版本不符被跳过，以及是否在等 Wi-Fi。离线播放从上次的位置继续；看完 95% 以上或已标记为看过时，从头播放。 | `DetailComponent.download`（返回 `OfflineEnqueueResult`）、`OfflineMedia.offlineStartPositionMs`、`ProfileScreen` |
| P1-14 | 锁定后，单击只会让锁图标和“长按解锁”出现约 3 秒（读屏开启时常驻），双击和长按会被拒绝并有触感反馈，长按锁图标才解锁。手机上锁定时拦截返回并提示。出错层盖住锁定层时，不再拦截返回。 | `PlayerChrome.LockedOverlay`、`PlayerControls` |
| P1-15 | 亮度调节从系统亮度起步：按框架配置的最大值换算，兼容厂商 1023–4095 的刻度；读数超出已知范围时退回中值。在用户调节之前，起点跟随系统亮度的变化。离开播放器或进入画中画时，恢复为跟随系统。 | `PlayerSystemControls` |
| P1-16 | 投屏期间，锁屏或切到后台不再暂停电视上的播放，也不再保持屏幕常亮。新增常驻的投屏胶囊（显示设备和状态，可以断开），只在投屏连接中或进行中显示，出错时会被读屏播报；投屏键显示激活态。打开投屏面板时如果还没有发现设备，会自动扫描，并显示“正在搜索…”或“未发现设备”。 | `PlayerActivity`、`PlayerChrome.CastSessionPill`、`PlayerControls` |
| P1-17 | 关闭“自动播放下一集”后，下一集卡不再显示倒计时和“即将自动播放”。播放结束且有下一集时，提供“下一集 / 重播 / 返回”。 | `PlayerNextUp`、`PlayerNextUpOverlay`、`PlayerControls` |
| P1-18 | 进入片头或片尾片段时，跳过按钮会单独出现 6 秒（随无障碍超时设置延长），之后跟随控件显隐。TV 上控件隐藏时按确定键，直接执行跳过或取消自动跳过，倒计时文案也改成“按确定键取消”。 | `shouldShowManualSkipPill`、`PlayerControls`、`TvRemoteInputController` |
| P1-19 | 字幕面板的顺序改为：主字幕（自动滚动到当前项）→ 副字幕 → 偏移与样式 → 在线搜索。字幕偏移按 ±0.1 / ±0.5 秒步进（范围 ±60 秒），音频偏移按 ±50 / ±200 毫秒步进（范围 ±2 秒），都可以复位，保留自动校准；步进键读作“提前 0.5 秒”这类带方向和单位的说法，超出范围的已存值只会往回调。 | `PlayerSettingsPanel` |
| P1-20 | `Palette` 新增 `success`、`warning` 两个文字色角色，并提供 `statusText()`，把状态点的颜色映射为可读的文字色；状态点和填充仍然用 `Semantic`。已用于服务器卡片和当前服务器、追剧日历、账号、下载、材质设置、会话列表。契约测试会校验它们在页面、card、card2 和 sheet 上的对比度。 | `Tokens.kt`、`DesignSystemContractTest` 等 |
| P1-21 | 播放器面板的遮罩和面板本体不再暴露为按钮，并补上面板标题 `paneTitle`（字幕、音轨、弹幕、投屏、更多、播放服务器、倍速、聊天、弹幕搜索等）。 | `PlayerPanel` |
| P1-22 | “出错了：原文”和异常类名不再显示给用户，原始错误写入诊断日志。`EmbyErrorException` 的 message 改为用户文案。播放中出错（连同原因）、投屏失败、登录失败会被读屏播报。DLNA 命令失败改用中文说明。 | `EmbyErrorMessages`、`EmbyError`、`CastManager.android`、`PlayerControls` |
| P1-23 | 播出时间换算成设备时区显示，跨日时标注“次日”或“前一天”，时区用简称；排序按本地时间。提醒通知也使用本地时间。“北京时间”这类硬编码已经去掉。 | `AiringLocalTime`、`CalendarScreen`、`CalendarReminderWorker`、`SeriesAiringCalendarSheet` |
| P1-24（焦点部分） | 进度条只响应左右键，每次 10 秒；上下键用于移动焦点，不再改变进度。控件唤出时焦点落在播放键上。遥控器操作时，焦点停在控件上也不会让控件一直显示。 | `PlayerChromeRefined.StandardSeekBar`、`PlayerControls` |
| P1-25（部分） | 跳过片头见 P1-18。原生内核版（TV 包）不再提供 Exo / mpv 作为备选，错误层也不再给出“换同一条路径”的假备选。服务器表单有未保存的输入时，返回前会先确认。 | `PlayerRoot.packagedEngineStrategies`、`TvServersSettingsScreens`、`TvUiComponents.TvConfirmDialog` |
| P2-1 | 顶层返回栈以本次启动的起始页为根，在起始页按返回交给系统处理。 | `RootComponent`、`App.kt` |
| P2-6（部分） | 账号页标题改为“账号与同步”；云端重置的确认标题改为“清空云端数据？”。 | `AccountSettingsScreen` |
| P2-13（部分） | 按搜索键提交后会收起键盘。 | `SearchScreen` |
| P2-15 | 批量修改提醒方式时，保留每部剧自己的提前时间；单剧日历读取该剧已保存的提前时间，不再固定写入 30 分钟。 | `CalendarFollowStore.setReminderForAll`、`AiringShowCalendarDialog` |
| P2-23 | 整部剧和批量标记已看或未看之前，都要先确认（写明季数，并说明续播进度会一起清除）。标记后，选集和播放键立即刷新，包括不在当前季的播放目标；写入成功后，会重新向服务器询问播放键该打开哪一集。“重置”按钮已删除：仓库里没有“只清续播点”的接口，所以没有新造接口。 | `DetailExecutor`、`DetailReducer.SeriesProgressChanged`、`EpisodeProgressManager` |
| P2-25 | 电影的下载范围只剩“本片”，剧集的写作“本集”。被跳过的集会在提示里说明（见 P1-13）。 | `DetailDialogs` |
| P2-26 | 元数据编辑出错时，只有 401 和 403 才提示编辑权限；404 和 5xx 沿用统一文案，其他情况写出 HTTP 状态码，不再显示 Ktor 的原始报错。有未保存的修改时，关闭前会先确认。 | `MetadataEditorDialog` |
| P2-27 | 外部链接按类型拼接：剧集用 `/tv/`，电影用 `/movie/`，合集用 `/collection/`；TheTVDB 分别用 series、movie、episode。单集只有拿到剧集的 TMDB id 时才显示 TMDB 链接；季不再用自己的 id 拼出错误的页面。类型未知时保持原来的链接。 | `DetailSections` |
| P2-28 | 有多季时，选中空季仍显示季选择器，并提示“本季暂无已入库剧集”。 | `DetailEpisodes` |
| P2-42 | 点按暂停屏保只唤醒控件，不再恢复播放，并重新开始屏保计时。文案改为“点击唤醒”。 | `PlaybackExperienceOverlay`、`PlayerRoot` |
| P2-66（大部分） | 以下操作都加了确认：清空搜索历史、清除追更规则、在播放器和“我的”里退出一起看房间（房主的文案说明房间会转交）、TV 上删除离线下载。 | `SearchScreen`、`DownloadsScreen`、`WatchTogetherDialogs`、`ProfileScreen`、`TvDownloadsScreen` |

### 没做或只做了一部分

- **P1-24 TV 控件放大**：需要在电视上实测，确定焦点环、字号和按键尺寸，留给 TV 专项。
- **P1-25 登录免打字**（Plex PIN、手机扫码）：需要新的登录流程和接口，工作量 M–L，没有做。
- **P1-16 附加建议**：投屏卡片调音量、后台通知隐藏播放键、字幕下载后热加载，都没有做。另外，熄屏后电视会继续播放，但 DLNA 连播要等手机亮屏回到前台，才会推送下一集。
- **P1-4**：离线的服务器点击后仍进入库，只有登录失效的服务器有直达修复入口。库页是否显示“重新登录”依赖健康探测：库自己收到 401 不会写入健康状态，在下一次探测之前按钮仍显示“重试”。
- **P2-9**：账号页的三个按钮仍然等分一行。为了不被截断，“清空云端数据”按钮的文字暂时用四个字的“清空云端”。彻底解决要让按钮文字可换行，或把这个按钮单独放一行。
- **P2-23 的 TV 部分**：TV 上的“清除进度”仍然等于“标记未看”，需要先有“只清续播点”的接口。
- **P1-11 换文件时的选择**：换版本或换集时，同语言轨道按“第几条”保留。新文件同语言轨道的顺序不同时，可能落到另一条（界面高亮和传给播放器的一致，不会错配）。按轨道标题匹配留作后续。
- **P2-26 小遗漏**：元数据编辑里 Ktor 3 的解析错误会包成 `JsonConvertException`，目前落到通用提示，没有单独分支。
- **P1-10 已知副作用**：稍后观看状态是异步读取的。如果原本没有其他状态，状态行出现时标题会上移约 48dp。
- **P3**：首页书架和新的“全部”页副标题里，来源名仍然写死为“Emby”。
- 报告中其余的 P2、P3 条目本轮没有处理，仍按第六节的顺序排期。

### 行为变化（测试和发版需要知道）

- 没有服务器时，首次启动进入「服务器」页；起始页“自动”选项的说明已同步修改。
- TV 共用 `RootComponent` 和 `ServersStore`，所以：
  - TV 首启同样进入「服务器」，但 TV 上按返回键仍先回首页。
  - 私网地址同样默认 HTTP，第一台服务器保存后同样切到库。
  - TV 不显示“已连接「名」”这类提示。
  - 儿童资料在 TV 上仍能打开添加和编辑表单，认证完成后保存会被拒绝，表单显示原因，不再闪退。
- 从卡片重新登录成功后，会选中这台服务器并打开库。
- 手机锁定播放器时，返回键会被拦截；解锁要长按锁图标。
- 连接错误的提示文字全部改变；`EmbyErrorException.message` 现在是用户文案，不再是 `toString()`。
- 进度条对上下键不再响应（手机外接键盘同样受影响），键盘步进从至少 1% 改为 10 秒。
- 详情页“管理进度”里的“重置”按钮已删除（它实际等于“标记未看”）。

### 待真机验证

- **TV**：
  - 准备页和播放页之间切换时的按键归属。
  - 控件隐藏时按确定键跳过片头。
  - 唤出控件后焦点是否落在播放键上。
  - 画中画进出。
  - 第一台服务器保存后切到库时，焦点落在哪里。
- **手机**：
  - 锁屏的单击、双击和长按。
  - 首次调节亮度的起点（包括 MIUI、ColorOS 这类亮度刻度不是 255 的机型），以及退出播放器或进入画中画后是否恢复跟随系统。
  - 投屏时熄屏，电视是否继续播放。
  - 关闭连播时片尾的行为。
  - 字幕偏移步进。
  - 用 TalkBack 听面板标题和各类错误播报。
- **浅色主题**：状态文字的实际对比度（玻璃底色会随背景变化）。
- **接入**：用私网地址、公网 HTTPS、错误端口、普通网站地址、自签名证书分别走一遍首次接入。

### 发版说明草稿（打包时写入 `release-notes.txt`，版本号届时再定）

- 首次使用会直接进入「服务器」页；连接成功会有提示，第一台服务器连好后自动进入媒体库。
- 连接失败时说明具体原因（证书、地址、超时、端口、不是 Emby/Jellyfin 服务器），并提示该检查什么。
- 登录失效的服务器可以直接重新登录；密码框使用密码键盘，支持自动填充。
- 修复儿童资料下管理服务器时闪退的问题。
- 修复 TV 播放准备页上确认键和返回键失效的问题；TV 进度条不再困住焦点，控件隐藏时按确定键即可跳过片头。
- 首页新增“下一集”，长按可以标记已看或未看；离线播放会从上次的位置继续。
- 详情页标题下显示收藏、稍后观看、已看完等状态，顶栏可以一键收藏；同一语言的多条字幕和音轨可以区分，播放时用的就是选中的那一条。
- 一起看：在当前影片的房间里继续分享邀请，不再解散原房间。
- 加入下载后会提示实际加入的集数、跳过的集数，以及是否在等 Wi-Fi。
- 删除下载、移除家庭资料、清空搜索历史、退出一起看房间之前都会先确认。
- 播放器锁定后不会再被误触改变进度；首次调节亮度从当前亮度开始。
- 投屏时熄屏不再暂停电视，播放器上常驻投屏状态，可以一键断开。
- 关闭“自动播放下一集”后不再显示自动播放倒计时；播放结束后可以选择下一集、重播或返回。
- 进入片头或片尾时，跳过按钮会直接出现；字幕偏移可以按 0.1 秒微调。
- 追剧日历的播出时间按本机时区显示。
- 浅色主题下，服务器状态等文字更清楚。

### 验证

- **ktlint**（1.3.1，读仓库 `.editorconfig`）：相对 c051d021 的 123 个改动文件，没有新增违规。
- **人工编译审阅**：服务器、播放器、详情页三条大改动线各由一名审阅者逐个核对新调用的签名、可见性和调用点，没有发现阻断编译的问题。审阅发现的功能缺陷已在合并后修复：
  - 服务器：Plex 重新登录按钮无反应、重新登录后仍显示需重新登录、局域网 HTTPS 端口被改成 HTTP。
  - 播放器：投屏失败后残留胶囊、屏保唤醒后不再计时、厂商亮度刻度、画中画亮度。
  - 详情：剧集和单集页认不出自己的一起看房间、标记整部剧后播放键不重新解析。
- **CI**：TV 质量门（编译全部生产代码、ktlint、`tvShared:testAndroidHostTest`、debug 与 release 构建）正在运行，结果待补。`commonTest` 只在完整质量门里跑，本分支还没有跑过。

## 附录：术语表建议

统计范围是界面代码中的中文字符串，共 4,071 条。

| 概念 | 现有变体（次数） | 建议统一为 |
| --- | --- | --- |
| 媒体服务器 | 服务器 327 / 媒体服务器 14 / 服务端 6 / 服务器端 1 | 服务器 |
| 可播放的一份内容 | 版本 52 / 片源 40 / 线路 22 / 资源 22 / 来源 17 / 媒体源 3 | “片源”指某台服务器上的一份可播内容；“版本”指同一片源下的不同文件；服务器连接用“主地址 / 备用地址”，不再叫“线路” |
| 媒体库 | 媒体库 41 / 手机底栏“库” / TV 底栏“媒体库” | 媒体库（底栏受宽度限制时可保留“库”） |
| 稍后观看 | 稍后观看 7 / 稍后看 3 / 想看 18 / 片单 5 | 服务器侧叫“稍后观看”，个人清单叫“想看”，“片单”只用于智能片单 |
| 收藏 | 服务器收藏 / 个人收藏 / Yfuse 收藏 | 收藏，并标明“服务器收藏”或“个人收藏” |
| 播放内核 | 播放器 59 / 内核 50 / 引擎 2；ExoPlayer 有 5 种叫法 | 播放内核；界面只用角色名（兼容 / 格式 / 原生），技术名放进诊断 |
| 账号 | 账号 70 / 账户 1；鱼服账号 8 / Yfuse 账号 9 | 账号；品牌名二选一 |
| 更新 | 升级 16 / 更新 4 | 更新 |
| Token | Token 3 / 令牌 6 | 令牌 |
| 引号 | “” 10 条 / 「」 10 条 | 二选一 |
| 数字与单位 | 汉字紧贴插值变量 68 条 / 有空格 281 条 | 数字与单位之间加空格 |
