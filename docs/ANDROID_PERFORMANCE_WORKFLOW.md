# Android 性能验证与 Profile 采集

宏基准包含固定本地数据的生产首页滚动和真实 App 冷启动，分别验证渲染与初始启动。

**2026-09-08 验证状态：S10 上真实冷启动的 5 次测量已通过；首页首次真机测试失败，滚动手势已调整，尚待复测。S10 现已断开，本轮还没有完整通过的启动与首页组合结果。**

## 构建与测量范围

| 变体/测试 | 用途 | 边界 |
| --- | --- | --- |
| `composeApp:benchmark` | 继承 release 的 R8/资源压缩，供实际耗时测量 | debug 签名；非 debuggable；包名固定增加 `.benchmark` |
| `composeApp:profile` | 保留源类名，供生成可被 release R8 消费的规则 | 非 debuggable；不混淆；同样是隔离包名；不能拿它代替 R8 性能结果 |
| `macrobenchmark:benchmark/profile` | 执行仪器测试 | 两变体均使用 debug 签名；测试包允许 debuggable，且 instrumentation 运行在测试包自身 |
| `StartupBenchmark` | 真正的 `MainActivity`，5 次冷启动，`CompilationMode.None()` | TTID/初始导航可用；不声称远程推荐已加载 |
| `HomeJourneyBenchmark` | 生产 `HomeContentBody`，5 次滚动，3 次预热编译 | 8 个固定本地图、96 个条目、8 个片架；测量本地数据与预热缓存下的生产渲染，不代表联网速度 |

`HomeScreen` 仍负责真实 Store、路由可见性、刷新及导航。其状态渲染拆为 `HomeContentBody`，正常页面和测试使用同一段 LazyColumn、海报、轮播及片架代码。测试 activity、8 张可重复的 RGB PNG 和本地 URL 映射只存在于 performance 专用源目录；release/debug 不包含这个入口。测试使用生产 ImageLoader 派生实例，保留缓存预算和组件。

首页测试必须等到生产 UI 至少绘制两帧、本地图片至少成功解码一次才出现 ready 标记；随后找到生产 `home-feed` LazyColumn，并验证滚动真正到达第三个有数据的片架。图片映射缺失、空列表、错误页面或滚动未发生会让测试失败。

## 本地运行

需要 Python 3、JDK 21（另安装项目使用的 JDK 17 工具链）、Android SDK 36/NDK 29/CMake 3.22.1、已经授权且解锁的 arm64 真机。脚本使用标准库，不额外安装 Python 包。

```powershell
python scripts/android_performance.py --serial RF8M223V4MD --sdk D:/AndroidSDK --mode benchmark --output artifacts/android-performance/first-run --gradle-arg=--offline --gradle-arg=-Porg.gradle.java.installations.paths=C:/Users/app-inkbird/.jdks/corretto-17.0.15
```

默认测 native-only 包；测完整版加 `--runtime-profile full`。输出目录必须不存在，避免旧样本混入。编译目标和测试 APK 后，脚本在 connected 测试前完成以下预检：

- 目标 APK 必须为指定的 `.benchmark` 隔离包，且不可 debuggable；benchmark 变体还必须存在生产代码的 R8 mapping。
- 测试 APK 包名必须为 `com.yfuse.macrobenchmark`，且只有一个 `androidx.test.runner.AndroidJUnitRunner`，其 `targetPackage` 必须是测试包自身。这是 self-instrumenting 宏基准，不把 instrumentation 装进生产 App。
- 两份 APK 均须通过 `apksigner verify --verbose --print-certs`。Windows 使用 SDK 的 `apksigner.bat` 对应官方 JAR，通过 `JAVA_HOME` 或 PATH 中的 Java 启动；Linux 直接执行 apksigner。缺少工具、未签名或身份不匹配都会阻止 connected 测试。

预检完成后才运行 `:macrobenchmark:connectedBenchmarkAndroidTest`。脚本不会安装到 `com.yfuse`，不会修改 Wi-Fi、强制 root 或删除原 App 数据。

connected 测试明确保留本次隔离目标包和测试包，避免 AGP 卸载后删除 `Android/media` 中的原始结果，再由脚本拉取到本次输出目录；脚本不卸载其它 App。

双 APK 身份及验签通过后即保留 `target.apk`、`test.apk` 和 `apk-identity.json`；身份文件包含目标/测试包名、runner、instrumentation 目标及两份 APK 的 SHA-256。`target-signature.txt`、`test-signature.txt` 保留验签日志；之后 connected 测试失败也不会删除已经验证的构建证据。成功汇总中同样记录 `test_apk_sha256` 和测试包身份。

其余证据按实际执行进度保留：`device.json`、构建与仪器日志、R8 mapping 哈希、旅程 hash、测量前后电池信息，以及拉取成功的原始 JSON/trace。benchmark 模式的 `summary.json` 要求冷启动及首页滚动均存在，且各有 5 个非空有效测量轮次；空结果、重复结果、缺失测试和非有限数字都不能通过。只有启动测试通过不能作为完整宏基准基线。

```powershell
python scripts/android_performance.py --serial RF8M223V4MD --sdk D:/AndroidSDK --output artifacts/android-performance/second-run --baseline artifacts/android-performance/first-run/summary.json --gradle-arg=--offline
```

比较要求设备身份、运行时配置、变体、隔离包名、固定旅程 hash，以及测试的轮次、指标和 benchmark params 一致。设备身份包含序列号、型号、系统指纹/API，也包含显示尺寸、密度、字体缩放、刷新率上下限设置，以及 `animator_duration_scale`、`window_animation_scale`、`transition_animation_scale`。这些设置任一改变都会拒绝基线，即使仍是同一台手机。脚本读取并记录设置，不自动修改；字段缺失的旧基线也不能与新记录直接比较。

默认报告变化；需要门槛时明确添加 `--max-regression-percent 10` 等值。即使超出显式门槛也保留本次有效测量。冷启动比较中位数，滚动比较 CPU 帧耗时 p95；它们不是播放 FPS 或所有设备的掉帧比例。

## 生成并导出真实规则

```powershell
python scripts/android_performance.py --mode profile --serial YOUR_API33_PHONE --sdk D:/AndroidSDK --output artifacts/android-performance/profile-run --export-profiles --gradle-arg=--offline
```

脚本运行 `:macrobenchmark:connectedProfileAndroidTest`。`BaselineProfileGenerator.startup` 仅采集真实 MainActivity 启动，并输出 startup 规则；`homeJourney` 加入首页热路径，只进入 baseline 规则。成功后先验证存在有效的源名称规则和真实 MainActivity，再合并、去重并排除专用测试 activity，导出到本次 artifacts/profiles。指定 `--export-profiles` 才同时写入 `composeApp/src/main/baseline-prof.txt` 和 `startup-prof.txt`，交由 AGP 在 release 构建时消费。之后应重新构建并测量，不能预先保证 Profile 带来多少提升。

**本轮使用的 SM-G973U 是 Android 9/API 28，未授权 root，目前已断开。该设备支持普通宏基准，但不能执行真实 BaselineProfileRule 采集。应用 Profile 尚未实际采集，仓库没有占位规则。** API 33+ 或已 root 的 API 28+ 设备才满足采集条件，脚本会提前拒绝不支持的设备。

## CI 与回归

`.github/workflows/android-performance.yml` 仅支持手动触发，要求管理员先配置 `self-hosted, Linux, X64, android-physical, yfuse-performance` 标签的可信 runner、上述工具链和真机，提供 native action 使用的 sudo/apt 与网络权限。干净 checkout 会先运行已有 `fetch-engines.sh` 校验固定引擎，再运行 `build-current-ycore` 构建当前源码运行时，不依赖工作区里碰巧存在的 AAR。

首次运行不填 baseline 输入，保存真实样本。后续填写同设备成功运行的 `baseline_run_id`、精确 `baseline_artifact` 名称和明确的 `max_regression_percent`，作业下载该基线，验证身份/样本并执行实际回退门槛；缺失结果、设备不匹配或超过门槛都会让作业失败。三个输入必须成组提供，没有编造默认阈值。这个物理 runner 尚未被此改动创建，作业也未被冒充为已经运行的必过 PR 检查。成功或失败均上传已产生的证据，不自动提交采集规则。

双 APK 验签失败拦截、测试身份检查、Windows/Linux 工具调用、样本完整性、基线比较及规则导出的脚本回归可直接验证：

```text
python -m unittest discover -s scripts -p test_android_performance.py
```

测量字段采用 [Android 的 Macrobenchmark 指标定义](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)；Profile 设备要求见 [BaselineProfileRule](https://developer.android.com/reference/androidx/benchmark/macro/junit4/BaselineProfileRule)，startup 与 baseline 的边界见 [两类 Profile 的区别](https://developer.android.com/topic/performance/baselineprofiles/difference-baseline-startup)。
