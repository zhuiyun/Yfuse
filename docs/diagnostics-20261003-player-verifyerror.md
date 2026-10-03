# 2026-10-03 打开播放器即闪退（VerifyError）

依据：`Yfuse-diagnostics-20261003-100314.zip`，设备 OPPO PLG110 / Android 17 (API 37)，
闪退版本 1.0.97 (259)，北京时间 2026-10-03 10:03:05。

## 现象

- 点播放 0.15 秒后主线程抛出 `java.lang.VerifyError`，进程退出（`crash/uncaught_exception`）。
  起播日志停在 `prepared_store_claimed`：`PlayerActivity` 第一次组合 `PlayerRoot`，加载
  `PlayerRootKt` 类时被 ART 拒绝。
- 不是偶发：类校验失败是确定的，1.0.97 在任何设备上每次打开播放器都会这样闪退。诊断包里
  1.0.97 只有这一次播放尝试；同一设备上 1.0.96 多次正常播放。
- ART 的报错：`PlayerRootKt.PlayerRoot$lambda$152 … [0x23EB] register v1 has type Reference: dv7
  but expected Integer`。

## 根因

- 被拒绝的方法是 `PlayerRoot` 里 `PlaybackRuntimeContent(...) { localState, liveLocalState -> … }`
  这段约 3,150 行的组合 lambda。编译后它有 98 个参数（捕获的变量）、307 个寄存器，超过多数
  DEX 指令能寻址的 256 个。
- 偏移 0x23EB 是 `visible = !inPictureInPicture`（1.0.97 `PlayerRoot.kt` 第 2970 行）编出的
  `xor-int/lit8 v110, v1, 0x1`。`inPictureInPicture` 是捕获参数，寄存器号在 255 以上，R8 先把它
  复制到 v1 给 8 位寄存器指令用。随后在 0x2364–0x2387，R8 为构造一个捕获 12 个值的 lambda 对象，
  把实参依次放进 v0–v12（`invoke-direct/range {v0 .. v12}`），其中 `move-object v1, v7` 把一个
  `dv7` 对象写进 v1，没有先保存 v1 里仍要用的布尔值。之后 0x23A0
  （`PlatformPredictiveBackHandler` 的 `enabled = … && !inPictureInPicture`，`if-nez` 接受对象，
  所以校验不报错）和 0x23EB 读到的都是对象引用；ART 在 0x23EB 判定类型不符，整个类被拒绝。
- 这是 R8 的寄存器分配缺陷，与 Android 17、OPPO 无关：Android 16 (API 36) 模拟器上的 ART 对同一
  APK 报同一方法的 `Verification error`，对 1.0.96 没有任何报错。
- 升级 R8 不能解决。用 AGP 交给 R8 的原始输入（R8 dump）重放：R8 9.1.31 原样复现正式包的错误；
  9.1.56、9.2.38、9.3.31、9.4.20（目前最新的正式版）全部在同一方法生成同样的错误指令
  （偏移分别为 0x23EB、0x23C4、0x22F4、0x22ED）。在构建脚本里把 R8 固定为 9.1.56 实际构建，
  结果也一样。
- 1.0.97 在这个 lambda 里唯一的源码改动，是 `rememberTabletopHinge(…, inPictureInPicture =
  inPictureInPicture)` 多用了一次 `inPictureInPicture`，寄存器分配随之改变；源码本身没有错误。
  1.0.96 的同一方法有 308 个寄存器，碰巧分配正确。
- 1.0.91 的同类闪退（R8 把这个 lambda 移进别的类后校验失败）是用
  `-keep class com.yfuse.feature.player.PlayerRootKt { *; }` 规避的。它只阻止 R8 移动方法，不影响
  方法体内的寄存器分配，所以 1.0.92–1.0.96 是侥幸通过，1.0.97 复发。

## 为什么出包前没有发现

- R8 不校验自己的输出；JVM 单元测试不经过 R8；云端 UI 冒烟测试只走未登录页面，从不打开播放器；
  `PlayerRootKt` 只在第一次打开播放器时加载。

## 修复

- 升级 R8 无效，根治的办法是让应用自己的代码不再走 R8 出错的那条路径，也就是寄存器超过 256 个的
  方法。这样的方法有一部分参数落在 v255 之后，多数指令的 8 位寄存器操作数够不到，R8 要先把它们
  复制到低位寄存器，1.0.97 就坏在这一步。
- 先给出包检查加上寄存器数统计。按 1.0.97 的源码构建，应用自己有三个方法超过 256 个寄存器，手机包
  和电视包都一样：
  - `PlayerRoot` 的运行时 lambda，正式包里 307 个（98 个参数）。电视包没有 `PlayerRootKt` 的 keep
    规则，R8 把它挪进了 `PlayerAmbientBindingKt`，305 个。这正是 1.0.90 那次闪退的情形，只是这次
    碰巧校验通过。
  - `PlayerControls`，272 个（91 个参数；电视包 274 个）。
  - `AndroidAdaptiveCore2YPlayer.runLoop`，即 YCore 播放调度的命令循环，289 个。
  库代码里最大的方法只有 181 个寄存器。
- 三处都已拆开。代码原样搬移，只改缩进（`runLoop` 有 4 行因行宽重新折行），行为不变：
  - `PlayerRoot` 的运行时 lambda 改为 `PlayerRuntimeSession` 的扩展函数。会话对象携带原来被这个
    lambda 捕获的值（1.0.97 的代码是 94 个，合入短剧后 95 个）：`PlayerRoot` 的参数、派生的值，以及
    各个 `var` 背后的 State，名字都不变。函数里的 lambda 只捕获会话这一个对象，不再各自捕获几十个值。
    `AnimatedVisibility` 的内容作用域自带一个 `transition`，在扩展函数里会盖过会话的同名成员，所以
    函数开头用一个局部变量保住播放器自己的 `transition`。
  - `PlayerControls` 的函数体改为 `PlayerControlsInputs` 的扩展函数，签名和默认值不变。
  - `runLoop` 的命令循环放进一个局部类。被局部函数捕获的变量（Kotlin 的 `Ref` 对象）作为字段保存，
    不再在挂起函数的每个挂起点前后都占着寄存器。
- 先前的 `rememberUpdatedState` 绕开改动已撤回。
- 1.0.98 同时合入了短剧分支（`ccr-9a50929d-07i3hs`），它也改了这三个文件。合并时先取短剧的版本，
  再按同样的搬移重新拆分，函数体仍是短剧的原文，只改缩进：
  - 会话对象多带 `themePreferences` 和 `autoNextSetting`（播放中可切换的自动播放下一集）背后的
    State；`autoNext` 只剩一个同名的具名参数标签，不再携带。
  - `PlayerControlsInputs` 多了短剧的 3 个参数（`onToggleAutoNext`、`shortDramaMode`、
    `onSelectShortDramaMode`）。
  - `runLoop` 的命令循环照旧放进局部类，其中调用短剧新增的局部函数 `stopNextPreparationForSeek`。
  - 新增的名字都不是函数体里各个接收者（`BoxScope`、`AnimatedVisibilityScope`、`PointerInputScope`、
    `CoroutineScope`）的成员，也不与任何导入同名，所以每个名字指向的仍是短剧代码里的那个。

## 验证及边界

- 在 CI 上按正式包的方式构建（AGP 9.1.1 / R8 9.1.31）：
  - 只含闪退修复时（运行 37104094707），两个包都没有超过 256 个寄存器的应用方法，寄存器类型检查都是
    0 处（手机包 58,498 个方法，电视包 44,543 个）。手机包最大的应用方法 188 个寄存器，是
    `PlayerControls` 本身（它接住 91 个参数再转交）；它的函数体 172 个。电视包最大 189 个，是没有
    改动的 `AndroidNativeEnhancedYPlayer` 里的 `prepareCurrent`。`PlayerRoot` 的运行时、`runLoop`
    和原来 239 个寄存器的控件 lambda 都降到 160 个以下。
  - 合入短剧并重新拆分后（运行 37107401799），两个包仍没有超过 256 个寄存器的应用方法，寄存器类型
    检查都是 0 处（手机包 58,752 个方法，电视包 44,855 个）。两个包最大的应用方法都是
    `PlayerControls` 本身，194 个寄存器（94 个参数）；它的函数体手机包 182 个、电视包 181 个；
    运行时里最大的 lambda 165 个，`PlayerRoot` 本身 162 个。`runLoop` 仍不超过 160 个。
  - Android 16 (API 36) 模拟器删除 dexopt 结果后，以 `verify` 过滤器让 ART 从头校验手机包，合入短剧
    前后两次都没有拒绝任何方法。同一流程对 1.0.97 正式包报出 `PlayerRoot$lambda$152` 的
    `Verification error`，对 1.0.96 正式包没有报错。
- 短剧的改动本身只经过 ktlint、设计规范检查、主机单测和上述构建与校验，没有在真机上验证过
  （`docs/SHORT_DRAMA_SUPPORT_REVIEW_20261002.md`）。
- R8 的缺陷本身还在。以后代码里再长出超过 256 个寄存器的方法，出包检查会直接失败（见下节），
  需要按同样的办法拆开。
- 可以凭 R8 dump 向 Google 的 R8 issue tracker 报告这个缺陷。dump 含整个应用的程序代码，提交前
  需确认可以外发。
- 已交付的 1.0.97 无法修补，修复随 1.0.98 (260) 交付。

## 防止再次出包

- `scripts/verify-release-dex.sh --mapping <mapping.txt> <apk>`：用 dexlib2（baksmali 的校验模型，
  含 ART 的 instance-of 收窄）检查 APK 中每个方法的寄存器类型，发现对象当整数用、整数当对象用、
  long/double 寄存器对不完整或读取未赋值的寄存器即失败。对 1.0.97 正式包（58,541 个方法）只报出这一处，与 ART 的报错
  完全一致；对 1.0.96 正式包为 0；对 Kotlin、协程、序列化、OkHttp、Guava、Ktor 经 D8/R8 9.1.31
  编出的 91,000 个方法没有误报。
- 同一个检查还借助 R8 的 mapping 找出应用自己（`com.yfuse`）的方法，超过 256 个寄存器即失败，
  不等 R8 真的编错。`--list-registers-over N` 列出所有超过 N 个寄存器的方法，用来看离上限多远。
- 已接入质量门禁、TV 门禁、正式出包 workflow（构建后、上传前）和本地
  `build-release-packages.ps1`。依赖的三个 jar 在 `scripts/dex-verify/tools.sha256` 中以 SHA-256 固定。
- `scripts/diagnostics/inspect_dex_method.py`：拿 ART 报错里的类、方法、偏移和寄存器，打印被拒绝
  的指令、各操作数的类型、之前的指令和到达该处的每个寄存器定义。`docs/android-release.md` 的
  DEX verification 一节说明了它的用法，以及如何导出 R8 的输入、用其他 R8 版本重放，和用模拟器让
  ART 从头校验（`scripts/diagnostics/replay_r8_dump.py`、`art_verify_on_emulator.sh`）。

## 诊断包里的另一次闪退

- 2026-09-30 23:36（1.0.96）：开始播放一个约 20 GB 的杜比视界 HEVC MP4（HTTP）时，
  `YCore2Native` 接管约 1 秒后进程发生原生崩溃，下次启动归类为 `YCoreDemux`（计数 1）。
  诊断包只记录了归类结果，没有崩溃栈，无法据此定位；与本次 VerifyError 无关。
  `AndroidNativeCrashMonitor` 按组件、引擎、解码方式和设备能力计数，同一路径累计崩溃 2 次后
  自动换用另一种解码方式或停用该路径。
