# N3 设备接力 / N6 Trakt 实现记录

日期：2026-09-15。范围：客户端接力与 Trakt 核心、公共 Compose 页面/播放绑定、账号后端路由、共享协议和回归测试。手机、平板、TV 导航/DI、接收 Activity 和实际播放器挂载由主代理统一接线；播放器更多菜单中的设备接力页由本子任务补齐。

## N3：同账号主动设备接力

实现路径：

- `watchTogetherProtocol/.../HandoffProtocol.kt`：在线设备、密文载荷、请求、状态转换。
- `watchTogetherServer/.../account/HandoffStore.kt`、`HandoffRoutes.kt`：账号会话认证、在线心跳、授权状态机、期限和容量。
- `composeApp/.../core/handoff/`：账号 API、账号保险库加密、控制器和播放器登记桥。
- `composeApp/.../feature/handoff/DeviceHandoffScreen.kt`：设备选择、接收确认、取消及播放器状态登记 Composable。
- `composeApp/.../feature/player/PlayerSettingsPanel.kt`：更多 → 设备接力，直接在仍挂载来源的播放器 Activity 内选择在线设备、查看等待/结果/错误或取消；无 Koin 服务时显示说明。

流程：来源提交 `Requested` → 接收方手动确认 → `Preparing` → 无自动播放的队列/来源准备成功后 `Ready` → 来源暂停并重新捕获当前位置 → 用最新密文提交 `Committed` → 接收播放器实际 Ready 且 playing 后 `Completed`。来源在 Ready 之前不暂停；拒绝、准备失败、取消或超时走释放/恢复路径。Completed 响应丢失会先读取服务端状态并作幂等确认，避免把已确认的接力误判为失败。

安全与边界：

- 每个请求都经过现有账号令牌验证及撤销检查；设备 ID 使用服务端登录会话 ID。设备列表、接力读写均限制同账号，并检查来源/接收角色。
- 心跳每 10 秒一次，活动接力期间每 2 秒一次；45 秒没有心跳的设备离线。请求期限默认 60 秒，可接受范围 15–120 秒，按服务端时钟计算；退出或切换个人资料会取消当前协程树。
- 设备最多 4096、接力最多 4096，每个参与设备同时最多一项活动接力。已结束请求在期限后保留最多 2 分钟用于幂等确认，重启服务端会失效。
- 收件箱最多 64 个设备、8 个请求，活动请求优先；最大密文下仍低于现有账号响应尺寸上限，避免历史请求累积令心跳失效。
- 媒体定位与轨道偏好使用现有 AES-256-GCM 账号保险库；真实键名是 `vault_key` / `vault_user_id`，必须匹配当前账号。AAD 绑定用途、账号 ID、请求 ID，跨请求重放无法解密。
- 明文不含媒体服务器令牌或播放 URL。携带媒体键/别名、serverId/itemId、mediaSourceId/版本名、进度、主副字幕/音轨索引、语言/标题/编码、开关、主副字幕偏移、音频偏移及倍速。尺寸、索引、偏移、倍速均有边界。
- `HandoffPlaybackRegistry.pendingPreferences` 是一次性偏好通道；接收端启动前 `offerPreferences`，播放器按身份/版本匹配后应用并 `clearPreferences`。接收成功仅清本次准备句柄，失败才释放本次启动。

接口：`HandoffController.start/close/send/accept/reject/cancelTransfer`；`DeviceHandoffScreen`、`HandoffIncomingPrompt`、`HandoffPlaybackBinding`；`HandoffPlaybackRegistry.Receiver.prepare/start/release/transferCompleted`。

部署边界：需要后端新路由与新版客户端同时具备。未部署时接口失败显示可重试状态，不阻塞原有启动。这里没有部署服务、安装包或触发播放。双方同时永久断网时不能提供分布式事务的绝对原子交接保证；回滚以期限和最近确认状态为依据，断网/后台冻结/进程死亡仍需两台真实设备专项验收。

## N6：Trakt 真实接入

### 官方资料核验

以下均为 Trakt 官方来源，读取日期 2026-09-15；未提供绝对更新日期的页面按页面显示的相对日期记录，不推算发布日期。

| 行为 | 官方依据 | 页面更新信息 |
| --- | --- | --- |
| 手机/平板浏览器授权码，TV 输入受限设备使用 device code | [Authentication](https://docs.trakt.tv/reference/auth) | 页面未提供可核实的绝对更新日期 |
| OAuth 所有接口使用 `auth.trakt.tv`；授权参数包含 state 与精确 redirect_uri | [Authorize](https://docs.trakt.tv/reference/getoauthauthorize) | 页面未提供可核实的绝对更新日期 |
| 设备码包含 verification_url、user_code、expires_in、interval | [Device code](https://docs.trakt.tv/reference/postoauthdevicecode) | 页面未提供可核实的绝对更新日期 |
| 设备轮询 400 pending、409 used、410 expired、418 denied、429 slowdown | [Device token](https://docs.trakt.tv/reference/postoauthdevicetoken) | 页面未提供可核实的绝对更新日期 |
| 刷新令牌单次使用，需存储替换令牌 | [Token](https://docs.trakt.tv/reference/postoauthtoken) | 页面未提供可核实的绝对更新日期 |
| API v2、public client id、Bearer 用户令牌及 User-Agent | [Required headers](https://docs.trakt.tv/docs/required-headers) | 页面未提供可核实的绝对更新日期 |
| 写请求每秒一次；429 按 Retry-After 秒数等待 | [Rate limiting](https://docs.trakt.tv/docs/rate-limiting) | 读取时显示 Updated 25 days ago |
| 分页遵守服务端页数，服务端可缩小请求 limit | [Pagination](https://docs.trakt.tv/docs/pagination) | 读取时显示 Updated 7 days ago |
| start / pause / stop，上报真实播放转换；stop 超过 80% 进入历史、409 表示近期重复 | [Start](https://docs.trakt.tv/reference/postscrobblestart)、[Pause](https://docs.trakt.tv/reference/postscrobblepause)、[Stop](https://docs.trakt.tv/reference/postscrobblestop) | Stop 读取时显示 Updated 4 months ago |
| history/watchlist 数据结构及媒体 ID | [官方 sync API 合同](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/sync/index.ts) | 主分支内容在读取时核验；不把未发布计划当行为 |

### 实现及同步方向

- `TraktOAuthBroker` / `TraktOAuthRoutes`：鱼服后端处理授权码交换、TV device-code 轮询、token refresh / revoke。client secret 仅来自服务端环境变量，不进入 APK。浏览器 state 绑定发起登录会话、一次性消费，随机挑战 ID 的轮询也只限该会话。
- 浏览器回调不依赖账号 Cookie；短期授权结果只留在有上限的服务端内存中。固定请求目标 `https://auth.trakt.tv`，不跟随跳转，不记录 OAuth 密文/令牌，连接与读取有超时、返回体不超过 64 KiB。
- `AccountTraktAuthApi` 仅向可信鱼服 origin 发送鱼服令牌；`HttpTraktApi` 仅向 `https://api.trakt.tv` 发送 Trakt 令牌。Trakt 使用独立默认 TLS 引擎/连接池，由 DI 所有者关闭，不关闭共享账号客户端。
- `TraktRepository` 将凭据、刷新幂等 ID、分页游标及待上报队列放在平台 SecureStore，按账号 + 个人资料隔离。刷新请求 ID 在网络调用前持久化，后端短期缓存该请求的替换结果以恢复丢失响应。
- 观看历史和想看单仅由用户点按导入，方向为 **Trakt → 当前本地个人资料**。`PersonalTraktImportSink` 调用个人库 additive import，保留已有本地记录和删除墓碑，不覆盖用户之后的本地选择，不把 Trakt 状态写回 Emby/Jellyfin。
- 剧集优先采用剧集所属节目的媒体键与季集坐标，如 `tmdb:100/s2e3`；Series 不会被误当成单集上报。
- 分页最多每次 50 页，逐页保存游标；失败保留已经导入部分，再次点击继续未完成页。历史请求固定本轮结束时间。单页响应流式限制 2 MiB、最多 250 条，缺少非空分页信息时明确报错，不凭返回条数假定完成。
- 播放上报是 **当前资料真实播放 → Trakt**，每次新授权默认关闭，需用户显式开启。仅在实际播放、暂停、停止/播毕时上报；预加载、准备、寻址和 UI 轮询不会触发写入。
- `TraktPlaybackReportingEffect` 固定绑定播放会话时的资料世代。切资料、退出或切回旧资料后，旧播放器的 onDispose 等迟到事件无法写入新资料的 Trakt 队列。
- 队列最多 128 项，对同一播放会话同一媒体合并待发状态；写入间隔至少 1 秒，失败指数退避到最多 15 分钟并遵守 Retry-After，手动重试不会绕过等待时间。超过 15 分钟的 Start/Pause/未完成 Stop 丢弃，避免覆盖新设备当前状态；完成 Stop（>80%）保留待重试。离线完成重新送达的 Trakt 观看时间以服务端接收时间为准。
- stop 的 409 近期重复和 <1% stop 的 422 按官方语义处理，不无限重试。断开立即清本机授权和队列，并尝试远端 revoke；远端失败明确告知可在 Trakt 应用设置中移除授权。已经在网络中的请求可能在断开时到达远端。

### 配置与接线

维护者需注册 Trakt API 应用，然后只在**鱼服账号后端环境**中配置：

```text
TRAKT_CLIENT_ID=<Trakt public client id>
TRAKT_CLIENT_SECRET=<Trakt client secret>
TRAKT_REDIRECT_URI=https://<鱼服账号服务域名>/api/v1/account/trakt/callback
```

`TRAKT_REDIRECT_URI` 必须与 Trakt 注册页面完全一致；本实现要求 HTTPS、该固定回调路径、无 query/fragment/userinfo。TV 设备码流需 client id + secret；手机/平板浏览器流还需上述回调。公开 configuration 接口实际也要求鱼服登录，仅返回 public client id 与可用性，不返回 secret。

当前用户尚未注册，已确认无相关配置，代码没有硬编码占位密钥。缺配置时 UI 显示维护者尚未配置并禁用授权按钮。没有发起真实 OAuth、没有真实 API 写入，因此注册、回调可达性和真实帐号授权验收仍需之后补齐。

公共 API：

```kotlin
val traktHttp = createTraktHttpClient() // DI owner closes this instance.
val trakt = TraktRepository(
    api = HttpTraktApi(traktHttp),
    auth = AccountTraktAuthApi(accountHttp, accountTokens),
    secureStore = secureStore,
    owner = accountAndProfile,
    importSink = PersonalTraktImportSink(personalLibrary),
    scope = applicationScope,
)
trakt.start()
// UI: TraktSettingsScreen(trakt, onBack, television = true/false)
// Playback: TraktPlaybackReportingEffect(..., playing = actualPlaying, completed = ended)
```

## 验证

新增 30 个可独立复现的用例，均使用内存/MockEngine，不调用用户媒体或第三方服务：

| 测试文件 | 用例数 | 验证点 |
| --- | ---: | --- |
| `HandoffStoreTest` | 6 | 账号/角色隔离、提交准备顺序、期限/在线 TTL、幂等/单活动请求、密文尺寸、最大密文收件箱边界 |
| `TraktOAuthBrokerTest` | 5 | 未配置、真实 auth host/state 单次绑定、设备慢轮询/拒绝、刷新幂等、URL/期限 |
| `HandoffControllerTest` | 6 | Ready 前不暂停、最终进度、失败恢复、拒绝/资料切换、准备释放、空快照恢复、重连仅清网络错误 |
| `HandoffPlaybackRegistryTest` | 3 | 实际 Ready+playing/版本匹配、成功与失败释放差异、一次性偏好 CAS |
| `HandoffVaultCipherTest` | 1 | 真实 AccountRepository 生成保险库键与 PlaybackVaultCipher/HandoffVaultCipher 互通、账户和请求 AAD |
| `HttpTraktApiTest` | 4 | 令牌与目标、分页缩限、缺分页/大响应、409/429、剧集身份 |
| `TraktRepositoryTest` | 5 | 显式开关/限流、Retry-After/离线完成、资料世代、刷新替换、分页续传/断开清队列 |

独立 ktlint 已通过（包含公共页面、核心/协议/服务端、新测试与播放器更多菜单）。主代理首轮统一运行的上述归属测试 29/29 通过，XML 位于 composeApp/build/test-results/testDebugUnitTest 与 watchTogetherServer/build/test-results/test；新增的重连提示隔离用例与播放器入口留待下一轮统一验收。最终结果以主验证记录为准。没有运行真实播放器、打包、上传或部署。

### 最终接线复核补充：延迟轨道与索引信任

只读复核确认 legacy 适配层可能先把默认空轨状态映射为 Ready，旧的一次性消费会错过稍后发现的轨道。已把接收偏好恢复改为 `HandoffPreferenceWait`：每个请求固定 10 秒截止，音/主副字幕列表变化不会延长截止；空轨 Ready 保留请求，所需轨道可匹配时应用，期限后应用可匹配部分并明确提示缺失音轨/字幕。倍速、音频偏移及主副字幕偏移继续保留。

`AndroidHandoffReceiver` 在提交到播放器前通过 `forHandoffTarget` 重写目标身份；仅原服务器、原 item 和原非空版本均一致时保留音轨/主副字幕索引。其他副本清索引后按语言/标签/编码匹配。即使可信索引也需验证其元数据，避免重排后选错语言；它可区分同版本同标签的重复轨道。

新增 `HandoffPreferenceResolutionTest` 5 例与 `HandoffTargetIdentityTest` 1 例，覆盖空轨 Ready、晚到字幕、固定期限、缺失副字幕、可信索引消歧/元数据不符、跨服务器/版本清索引且保留偏移。涉及的 PlayerRoot、Receiver、两 helper、两测试均通过独立 ktlint；这 6 例待主代理下一轮统一回归。
