# Yfuse —— Emby 客户端 MVP 设计文档

- **日期**: 2026-07-22
- **状态**: 已确认,待实现
- **目标平台(第一版)**: Android 手机 / 平板
- **参考产品**: Forward(Emby/Jellyfin 客户端)

## 1. 目标与范围

做一款类似 Forward 的 **Emby 客户端**。第一版(MVP)聚焦最小可用闭环:

> 连接服务器 → 登录 → 浏览媒体库 → 查看详情 → 播放视频

### MVP 包含
1. 服务器连接(手动输入地址 + 端口)
2. 用户登录(用户名 / 密码,Emby 认证)
3. 首页 / 媒体库列表(海报墙)
4. 媒体详情页(海报、简介、播放按钮)
5. 视频播放(直连 direct play,暂不做转码)
6. 基础搜索

### MVP 明确不做(留待后续版本)
- 转码 / 码率自适应
- 剧集分季分集完整管理
- 字幕 / 音轨高级切换
- 继续观看 / 播放进度同步
- 多服务器管理
- 下载 / 离线播放
- Android TV 端
- Jellyfin 兼容(第一版只做 Emby)

## 2. 技术栈

| 关注点 | 选型 | 说明 |
|--------|------|------|
| 语言/框架 | Kotlin + **Compose Multiplatform (KMP)** | 共享 UI 与逻辑,便于后续扩 iOS/桌面 |
| 导航 | **Decompose** | 组件树 + 返回栈 + 状态保存 |
| 状态管理 | **MVIKotlin** | 严格单向数据流 MVI |
| 网络 | **Ktor Client** | KMP 原生 HTTP;需装 `ContentEncoding` 插件处理 gzip |
| 序列化 | kotlinx.serialization | JSON 解析 |
| 异步 | Coroutines + Flow | — |
| 依赖注入 | **Koin** | KMP 友好 |
| 图片加载 | **Coil 3** | 支持 Compose Multiplatform,加载海报 |
| 视频播放 | **Media3 (ExoPlayer)** | 仅 `androidMain`;common 层只暴露 `VideoPlayer` 接口 |
| 本地存储 | **multiplatform-settings** | 存服务器地址、token、userId |
| 测试 | kotlin.test + Turbine + Ktor MockEngine | 测 Store 与 Repository |

第一版只实现 Android target。播放器在 common 层定义 `interface VideoPlayer`,Android 用 Media3 实现,后续扩 iOS 换 AVPlayer 时不动上层。

## 3. 分层架构

```
UI 层 (Compose Multiplatform)
  Screen Composables + Decompose Components
        ↑ State  /  ↓ Intent
表现层 (MVIKotlin)
  Store: State / Intent / Executor / Reducer / Label
        ↓ suspend / Flow
领域·数据层 (Repository)
  EmbyRepository ← Ktor Client + 认证/会话
```

- **UI 层**:只订阅 `State` 渲染、向 `store.accept(intent)` 发意图,不含业务逻辑。
- **表现层**:每个功能一个 MVIKotlin Store,单向数据流。
- **数据层**:`EmbyRepository` 是唯一数据入口,封装 Ktor 调用与 Emby REST API。

## 4. 模块划分(MVP 阶段用包划分,不拆 Gradle module)

```
core 基础层
├── core:network      Ktor 客户端、认证拦截器、gzip 解码、EmbyError 映射
├── core:data         EmbyRepository、SessionManager、DTO
├── core:model        领域模型(Server, User, MediaItem, MediaDetail…)
├── core:designsystem 主题、颜色、通用组件(海报卡、加载态、错误态)
└── core:player       VideoPlayer 接口 (+ androidMain 的 Media3 实现)

feature 功能层(每个 = Decompose Component + Store + Compose Screen)
├── feature:server    服务器连接
├── feature:login     用户登录
├── feature:home      媒体库列表 + 海报墙
├── feature:detail    媒体详情
├── feature:player    播放页
└── feature:search    搜索

app 装配层
└── RootComponent:导航栈,串联各 feature;Koin 装配依赖
```

> **取舍**:MVP 阶段不拆独立 Gradle 子模块,改用包(package)划分并保持同样的逻辑边界,以降低起步的多模块配置开销;待模块稳定、确需并行编译时再拆真 module。

## 5. 导航流程

```
Server ──连接成功──▶ Login ──登录成功──▶ Home ⇄ Search
                                          │
                                          ▼ 点海报
                                        Detail ──点播放──▶ Player
```

- `SessionManager` 保存/读取「服务器地址 + token + userId」。
- App 启动时若已有有效会话,`RootComponent` 跳过 Server/Login 直接进 Home;否则从 Server 开始。

## 6. MVI 数据流闭环(以登录为例)

**五个角色**:State(唯一状态) / Intent(意图) / Executor(处理意图、调 Repository) / Reducer(纯函数 `(State, Msg)→State`) / Label(一次性副作用,如导航)。

```
用户点「登录」→ LoginIntent.Submit
  Executor.executeIntent():
    dispatch(Msg.Loading)              → Reducer → State(loading=true)
    repository.login(user, pwd)        → suspend 调 Ktor
    ├─ 成功 → dispatch(Msg.Success)    → State(loading=false)
    │        publish(Label.NavigateHome)
    └─ 失败 → dispatch(Msg.Error(msg)) → State(error=…)
```

示意骨架:

```kotlin
data class LoginState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

sealed interface LoginIntent {
    data class UsernameChanged(val v: String) : LoginIntent
    data class PasswordChanged(val v: String) : LoginIntent
    data object Submit : LoginIntent
}

sealed interface LoginLabel {
    data object NavigateHome : LoginLabel
}
```

UI 只做:订阅 `store.states` 渲染 + 发 `intent`;`Label` 由 Decompose Component 收集并转为导航动作。

## 7. Emby API 集成要点(已在真实服务器验证)

已验证服务器:Emby **4.9.1.90**,认证与库列表链路全部跑通。

- **认证**:`POST /Users/AuthenticateByName`,请求头 `X-Emby-Authorization: MediaBrowser Client=..., Device=..., DeviceId=..., Version=...`,body `{"Username":..,"Pw":..}`;返回 `AccessToken` 与 `User.Id`。
- **后续请求鉴权**:请求头 `X-Emby-Token: <AccessToken>`。
- **媒体库**:`GET /Users/{userId}/Views` → `Items[]`,含 `Name` / `CollectionType`(movies / tvshows)。
- **最新项目**:`GET /Users/{userId}/Items/Latest`。
- **详情**:`GET /Users/{userId}/Items/{itemId}`。
- **图片**:`GET /Items/{itemId}/Images/Primary`(海报)。
- **播放地址**:`GET /Videos/{itemId}/stream?static=true&api_key=<token>`(direct play)。
- **⚠️ 压缩**:Emby 默认返回 **gzip** 响应,Ktor 必须安装 `ContentEncoding`(gzip)插件,否则拿到乱码。

> 服务器地址与账号凭据不写入仓库;开发使用本地未提交的配置(见 §9)。

## 8. 错误处理与会话失效

- `core:network` 把异常(网络失败 / 401 / 5xx / 超时)映射为领域层 `EmbyError` 密封类。
- 各 Store 收到后转为 `State.error` 文案。
- **401(token 失效)**:清除会话并 `publish` 全局 `Label.SessionExpired`,由 `RootComponent` 踢回登录页。

## 9. 本地开发配置(不提交)

服务器地址、测试账号等敏感信息通过本地未提交文件注入(如 `local.properties` 或本地 gitignored 的常量文件),不写入版本库。仓库内只保留占位/空值。

## 10. 测试策略

| 层 | 测什么 | 工具 |
|----|--------|------|
| Store | 给定 Intent 序列,断言 State 演变 + Label 发出 | kotlin.test + Turbine |
| Repository | Mock Ktor 引擎返回假 JSON,断言解析与错误映射 | Ktor MockEngine |
| 错误处理 | 401/5xx/超时 → 正确 EmbyError | MockEngine |
| UI | MVP 阶段暂不做自动化,手动验证 | 后续补 Compose UI 测试 |

**验收标准(冒烟测试)**:在真实 Emby 服务器上跑通「连接 → 登录 → 看到库 → 进详情 → 播放」主链路。

## 11. 后续版本方向(非本次范围)

转码/自适应码率 → 继续观看进度同步 → 字幕/音轨切换 → 剧集季集管理 → 多服务器 → 离线下载 → iOS/桌面/TV 端 → Jellyfin 兼容。
