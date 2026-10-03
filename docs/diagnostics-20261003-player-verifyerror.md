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

- 升级 R8 无效，只能从源码绕开。`PlayerRoot.kt` 在这个 lambda 里设好画中画淡出时长之后，用
  `val pictureInPicture by rememberUpdatedState(inPictureInPicture)` 读一次捕获参数，后面 13 处
  改读这个 State。参数此后不再使用，R8 也就不必让它的低位副本跨过后面摆放 lambda 实参的代码。
  这 13 处都在组合期间读取，读到的值与原来相同，界面行为不变。
- 另一种改法（先把 `!inPictureInPicture` 存进局部变量，替换 8 处取反读取）同样通过了下面的验证，
  但参数在后面仍被读取，能通过只是 R8 这次恰好没有出错，所以没有采用。

## 验证及边界

- 在 CI 上按正式包的方式构建（AGP 9.1.1 / R8 9.1.31，`assembleRelease`）：
  - 两种改法的 release 包：寄存器类型检查 0 处（State 写法 58,544 个方法，局部变量写法 58,542 个）；
    Android 16 (API 36) 模拟器删除 dexopt 结果后以 `verify` 过滤器强制重新编译，dex2oat 没有拒绝
    任何方法。同一流程对 1.0.97 正式包报出 `PlayerRoot$lambda$152` 的 `Verification error`，对
    1.0.96 正式包没有报错。
  - 提交后的源码（加了说明注释，后面的行号随之后移）重新完整构建：寄存器类型检查 0 处（58,544 个
    方法），模拟器上 ART 同样没有拒绝任何方法。
  - 修改后这个方法有 98 个参数、308 个寄存器（1.0.97 为 307 个）。
- 这是绕开，不是根治：这个 lambda 仍超过 256 个寄存器，以后改动这段代码，R8 仍可能在别的参数上
  犯同样的错。出包检查会把这样的包拦下（见下节）。根治需要把它拆成若干独立的 `@Composable` 函数，
  让每个方法都在 256 个寄存器以内，参数不再落在 8 位指令够不到的寄存器上；或者等 R8 修复。
- 可以凭 R8 dump 向 Google 的 R8 issue tracker 报告这个缺陷。dump 含整个应用的程序代码，提交前
  需确认可以外发。
- 已交付的 1.0.97 无法修补，需要出新包，版本号须高于 1.0.97 (259)。本次没有打包。

## 防止再次出包

- `scripts/verify-release-dex.sh <apk>`：用 dexlib2（baksmali 的校验模型，含 ART 的 instance-of
  收窄）检查 APK 中每个方法的寄存器类型，发现对象当整数用、整数当对象用、long/double 寄存器对
  不完整或读取未赋值的寄存器即失败。对 1.0.97 正式包（58,541 个方法）只报出这一处，与 ART 的报错
  完全一致；对 1.0.96 正式包为 0；对 Kotlin、协程、序列化、OkHttp、Guava、Ktor 经 D8/R8 9.1.31
  编出的 91,000 个方法没有误报。
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
