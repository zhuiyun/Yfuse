# AGP 9／API 37 升级与分支整合

## 构建版本

- AGP 9.1.1、Gradle 9.5.0、Kotlin 2.4.20。
- Compose Multiplatform 1.12.0、Coil 3.6.2、OkHttp 5.5.0、Koin 4.2.2。
- 编译 SDK 为 Android 37.0，SDK Manager 包名是 `platforms;android-37.0`。
- `targetSdk` 维持 36，手机和 TV 的 `minSdk` 维持 26。本次没有启用 target 37 的行为变化。

AGP 9.1.1 支持 API 37，要求 Gradle 至少 9.3.1、JDK 至少 17，见
[官方兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。

## 模块与启动配置

AGP 9 不支持在同一模块组合 Android application 与 Kotlin Multiplatform 插件。
本次采用[官方应用／共享库拆分方式](https://developer.android.com/kotlin/multiplatform/plugin)：

| 模块 | 职责 |
| --- | --- |
| `composeApp` | 手机应用壳、清单、签名、BuildConfig、原生包选择与验证、设备／性能测试 |
| `phoneShared` | 编译原有手机 commonMain／androidMain 源码和单元测试 |
| `tvApp` | TV 应用壳、TV 清单、签名、BuildConfig、TV 运行库选择 |
| `tvShared` | 编译原有共享源码与 TV 专属源码、TV 单元测试 |
| `mdkAndroid` | 保留 Android library 插件和 CMake／JNI，关闭未使用的内置 Kotlin |
| `macrobenchmark` | 使用 AGP 内置 Kotlin，继续以 composeApp 的性能变体为测试目标 |

源码目录保留原位置，由共享模块显式引用。手机与 TV 继续分别编译，TV 专属界面不会进入手机共享库。
Android 依赖和本地 AAR 在共享库中作为编译接口，最终运行依赖仍由各应用壳选择，避免共享库引入额外播放器或手机权限。

共享资源使用 `com.yfuse.shared.R`。应用壳在 `attachBaseContext` 中注入自己的
`AppBuildValues`，早于 ContentProvider 和 Application.onCreate；共享代码不再引用应用模块生成的 BuildConfig。
未注入时的可选 MDK 能力查询返回 false，其余应用元数据要求先完成注入。
设备和性能测试通过 Kotlin 的 `friendPaths` 保留对实现内部的测试访问权限，不把内部 API 改为公开接口。

## 测试与流水线迁移

- 主代码编译入口变为 `:composeApp:compileDebugKotlin` 和 `:tvApp:compileDebugKotlin`。
- 单元测试入口变为 `:phoneShared:testAndroidHostTest` 和 `:tvShared:testAndroidHostTest`。
- 原测试文件和测试语义保留；共享资源 R.jar 加入 host test 运行类路径。
- HTTPS 负向测试绑定 IPv4 回环地址，继续分别断言不受信任证书和主机名不匹配被拒绝。
- 使用 OkHttp BOM 对齐核心、SSE、TLS 与 MockWebServer 组件，避免旧 SSE 实现引用新版已移除的内部类。
- 共享模块的 ktlint 基线仅从原有基线迁移文件路径，不增加被忽略的规则或新违规项。
- CI 同步 SDK 安装包名、编译／测试任务、测试报告和依赖锁解析入口。
- API 37 将旧 Display HDR 能力标记为可空，能力查询在缺失时返回空集合。

## 原生制品的两个固定哈希

下载与本地安装后的 carrier 不能共用一个哈希：

- GitHub 发布资产 `527260538` 的 SHA-256 为 `074121c3fbbfd32fad74b14400c0a4c2678235f5dc818f9e4a79699ee77f3ecb`，
  与 2026-09-16 读取的发布资产 digest 一致，继续作为 `fetch-engines.sh` 下载 pin。
- 已审计安装后的 carrier 为 `db5f9f2f81d42457e2c6dfa84c6645ee4e140473c8a862a85699aaa02522c75a`，
  单独登记为 `libmpv-dolby-installed.aar`。来源为原 `signing-native-8dc1e5b22fbd74f014670bf07c1773891f719191`
  制品，经仓库安装脚本拆出重复 GPU 库；原生库字节已与交付 APK 核对。

Gradle 对两者继续执行内容哈希、sidecar 和 Dolby 来源标记校验；下载脚本不把安装后的哈希当作远程下载地址。

## 本地验证

- 全量 Gradle 验证通过：手机／TV 主代码、设备测试和性能测试编译、ktlint、Android Lint、两端 Release R8。
- JVM 测试 2,904 项全部通过：手机 2,629、TV 63、协议 8、服务端 204；无失败或跳过。
- Android Lint 为 0 个错误、2 个现存警告；21 个工作流 YAML 解析通过。
- 发布／原生脚本测试 16 项与依赖检查脚本测试 18 项通过。
- 八个模块的依赖锁已重新生成，清除旧 AGP 配置；离线解析无失败，锁文件哈希保持不变。
- OSV 扫描最终 800 个 Maven 坐标，安全门禁通过。AGP 内部设备测试结果监听器的
  `commons-lang3:3.16.0` 和 `httpclient:4.5.6` 各有一项 MEDIUM 记录；两者不在应用运行类路径中。
- 本地没有连接 Android 设备，本次没有真机 UI、播放或 Macrobenchmark 性能结果。
- Harmony 的源码和 host 测试仅完成可用工具支持的部分；完整验证受缺失的 stdx 和 host C++ 工具链限制。

## 合并范围与交付边界

本次已整合 product-completion、ycore-audit-http 两个功能／修复分支，以及获取远程分支时尚未合入 master 的十个 Dependabot 分支。
此前 UI 调整、轮播图开关和 1.0.66（228）签名记录一并保留。
1.0.66（228）已交付 APK 早于这次功能整合和 AGP 升级，不能用于验证升级后的代码。
源码提交使用 `[artifact only]`，不会触发正式更新发布；下一次交付 APK 仍须按 AGENTS.md 递增版本并重新验证签名。
