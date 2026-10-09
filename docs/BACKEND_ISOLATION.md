# 自有后端通信隔离

本次将项目自有服务器的协议、地址和实际网络请求集中到 `yfuseBackendClient` Gradle 模块。手机与 TV 共用此模块。账号页面、播放器、个人资料、日历缓存等本地业务仍在应用侧，通过适配器调用模块。

## 结构和责任

| 位置 | 责任 |
| --- | --- |
| `yfuseBackendClient/src/{commonMain,jvmMain}/kotlin/com/yfuse/backend` | 构建开关、能力、部署地址、禁用异常、日历、统计、更新与播放策略通信 |
| 模块内 `core/account`、`core/sync/playback` | 账号 API、令牌提供接口、云播放记录协议 |
| 模块内 `core/handoff`、`core/remote`、`core/sync` | 接力 API、遥控与一起看 WebSocket 连接器 |
| 模块内 `core/trakt`、`core/migration` | 自有 Trakt 授权中介、迁移码服务 |
| 模块内 `core/network/BackendTmdbRouting.kt` | 自有 TMDB 代理路由、令牌刷新与回退；诊断以事件回传 |
| `composeApp/.../di/BackendModule.kt` | 应用装配入口，把平台引擎、日志适配和后端实现交给 Koin |
| 应用侧 `createAccountClient`、`MigrationRelayClientFactory` 等 | 提供平台引擎与明确资源所有权；不定义后端请求路径 |
| `watchTogetherProtocol` | 客户端和服务端共用的纯协议，保持现有服务端兼容 |

模块保留既有包名与公开协议，减少业务层无关变更。模块不依赖 Compose、Android UI、Koin、Settings、应用日志或媒体引擎；诊断信息通过接口回传，平台引擎由应用注入。合并 1.1.5 后保留按功能拆分的 DI 模块，`BackendModule` 集中装配账号及自有 API，TMDB 网络模块仅接入独立路由契约；手机登录与遥控共用后端连接器。

`AccountRepository`、本地个人库、日历验证和缓存、播放调度等业务状态没有被机械搬入网络模块。它们与本地存储和媒体服务有关，完整移除在线产品时仍应根据实际产品范围清理。

## 统一禁用

构建参数：

```powershell
.\gradlew.bat :phoneShared:compileAndroidMain :tvShared:compileAndroidMain -PyfuseBackendEnabled=false
```

根目录 `gradle.properties` 中的 `yfuseBackendEnabled=true` 是统一默认配置；改为 `false` 即长期关闭，命令行参数可覆盖。参数仅接受 `true` 或 `false`，生成 `BackendBuildConfig.ENABLED`，由 `BackendAccess.Default` 消费。修改开关不要求编辑 IP、逐处注释网络代码或清空用户数据。

禁用后，后端 API 在获取凭据或发起请求前失败；WebSocket 连接器在创建引擎、获取令牌或打开会话前失败。模块还在共享账号客户端及迁移客户端的实际发送入口检查能力。应用侧使用同一状态停止或隐藏对应在线入口与后台生命周期，避免账号一直停留在恢复状态。TMDB 在关闭模式下只使用内置 token 直连第三方服务；没有 token 时立即返回不可用，不读取账号令牌、不等待恢复也不访问自有代理。设置页的打开和已保存导航栈恢复都过滤不可用云页面。

此构建模式用于关闭通信，**不会把全部后端类型与产品界面从编译依赖中删除**。已保存凭据和本地数据不应因临时禁用而自动删除。应用集成和验证结果见 [实施记录](../audit/backend-isolation-20261008/IMPLEMENTATION.md)。

## 保留的连接

- 用户自行添加的 Emby、Jellyfin、Plex，以及对应认证、扫描、媒体请求和直接进度同步。
- 媒体局域网发现、投屏设备通信和本地播放。
- 直接第三方 TMDB、弹幕、Trakt 数据 API。Trakt 的自有服务器授权中介受开关控制，新的授权、续期和撤销需要该中介可用。

禁止在全局网络引擎按服务器 IP 或 host 屏蔽。自有后端与用户媒体服务可能在同一台服务器；隔离应针对功能和通信实现，不能误伤媒体请求。

## 后续彻底移除

1. 先使用 `-PyfuseBackendEnabled=false` 验证产品的无后端模式，明确需要保留的本地个人库、日历缓存、媒体同步及第三方集成。
2. 从 `BackendModule` 和手机/TV 装配点取消在线服务绑定；将仍需保留的纯数据契约移至本地领域模块，必要时替换为明确的离线实现。
3. 删除已不用的账号云同步、一起看、接力、遥控、迁移码、更新、远端播放策略和统计入口及生命周期绑定。纯媒体功能不按 `sync`、`server` 等包名整包删除。
4. 在源码不再引用后端模块后，移除 phoneShared/tvShared 的模块依赖和 settings 中的 include，再删除模块目录。若不再使用自托管一起看服务，可另行移除服务端模块；此次客户端拆分不改动已部署服务。
5. 编译手机与 TV，并回归媒体登录、浏览、播放、恢复进度、离线与投屏。确认没有在线入口残留或账户恢复等待。

## 验证入口

```powershell
python scripts/check-backend-boundary.py
python -m unittest discover -s scripts/tests -p test_backend_boundary.py
.\gradlew.bat :yfuseBackendClient:jvmTest :yfuseBackendClient:ktlintCheck
.\gradlew.bat :yfuseBackendClient:jvmTest -PyfuseBackendEnabled=false
```

质量工作流已纳入双配置模块测试及边界扫描。静态检查覆盖独立模块反向依赖、应用内自有部署地址/API 路径，以及已拆出的通信入口重新创建 HTTP/WS 请求。它也禁止模块与应用出现同包同名 Kotlin 文件，避免重复 JVM 文件门面。它允许平台引擎薄适配器及第三方/媒体请求，扫描生产 Kotlin 源码而不把测试地址当成应用请求。

行为测试覆盖关闭后不触网、不取令牌，启用后令牌刷新、有界响应、错误信息、迁移密钥解码与客户端资源所有权。启用用例显式配置 `BackendAccess(true)`；另有真实生产默认构造的集成用例随生成的开关验证请求是否被执行。静态扫描不是网络防火墙，最终仍需双配置编译和关键用户流程验收。

## 本次验证（2026-10-08）

- 默认开启：手机 3,654 项、TV 88 项、独立客户端 35 项测试通过；手机/TV 应用壳及共享源码编译通过；三个模块 ktlint 通过。
- 关闭配置：独立客户端 35 项和应用专项 10 项测试通过；手机/TV 应用编译通过。覆盖旧账号与成人个人资料保留、旧更新记录与动作、日历离线刷新、一起看/遥控不取令牌、匿名统计队列、同 IP 用户媒体请求。
- 后端边界检查与现有手机/TV模块边界检查通过；边界脚本 13 项测试通过。
- 日历后台任务继续负责用户媒体可用性与本地索引预热，仅跳过自有排期网络请求；关闭模式保留强口令离线备份导入导出。

验证使用 MockEngine 和本地连接桩，未进行真机或真实后端联调。本次没有生成 APK、修改版本或上传发布。
