# Yfuse 一起看服务

轻量 WebSocket 房间服务，只维护房间的**播放时间线**，不代理视频、不读取 Emby 凭据，
也不承担转码。

## 协议 v5

协议 v5 要求先登录 Yfuse 账号才能使用一起看。客户端必须在 WebSocket Upgrade 请求中通过
`Authorization: Bearer <access-token>` 发送访问令牌；查询参数令牌不被接受。服务端在进入
房间前验证账号，并在连接存续期间定期复验；令牌过期、刷新轮换、退出登录或会话被撤销时，
当前一起看连接会以策略错误关闭。房间成员同时绑定不可变账号 ID 与房间级私有凭据，不能用
公开 `clientId` 冒充另一账号重连。

服务端是时间线权威。房主不再每秒广播当前位置，而是只在**发生事件时**（播放/暂停 /
seek / 变速 / 换片）提交一个新锚点，其他人本地按
`anchorPositionMs + (服务端当前时间 - anchorAtMs) × rate` 自行推算。这样稳态播放时几乎没有
流量，且不受 1 秒量化误差影响。

时钟以服务端为唯一基准：每条下行消息都带 `serverAtMs`，客户端用 `ping`/`pong` 按 NTP
方式把服务端时间映射到 Android 单调时钟（不信任两台设备各自的墙上时钟，也不受
运行期间 NTP 校时影响）。

消息类型：

| 方向 | type | 说明 |
| --- | --- | --- |
| C→S | `hello` | 带公开 `clientId`、资料及协议版本；重连还必须带私有 resume/host capability |
| C→S | `sync` | 当前有控制权限的成员提交新锚点 |
| C→S | `ping` | 带 `clientSentAtMs`，用于时钟对齐 |
| C→S | `chat` | 房内文字消息；最多 30 个 Unicode 字素、768 字节 |
| C→S | `playbackStatus` | 上报就绪、缓冲、媒体可用、延迟与同步偏差 |
| C→S | `updateProfile` | 更新当前成员的昵称和预设头像 |
| C→S | `requestControl` | 访客请求接管控制权 |
| C→S | `grantControl` / `denyControl` | 房主同意或拒绝控制请求 |
| C→S | `setControlMode` / `setModerator` | 房主选择仅房主、共同控制或指定管理员 |
| C→S | `kickParticipant` | 房主将指定成员移出当前房间 |
| S→C | `welcome` | 入房成功，附时间线、控制模式与成员快照 |
| S→C | `roomUpdate` | 成员或房主变化，附当前时间线 |
| S→C | `sync` | 房主提交的新锚点 |
| S→C | `pong` | 回显 `clientSentAtMs` |
| S→C | `chat` | 服务端认定发送者身份、生成序号和时间后广播 |
| S→C | `controlRequested` / `controlDenied` | 控制权协商结果 |
| S→C | `hostCapabilityGranted` | 主持权转移时私下下发新的主持凭据 |
| S→C | `kicked` | 通知被房主移出的成员并结束其当前连接 |
| S→C | `error` | 文案在 `message` |

行为要点：

- `clientId` 只用于公开成员身份；首次加入会私下签发每房间独立的
  `resumeCapability`，主持人另有 `hostCapability`。缺失或错误凭据不能替换已有会话。
- 主持权转移会立即轮换 `hostCapability`；旧主持人的凭据不能再次取得主持权限。
- 房主断线后保留 20 秒控制权；宽限期内重连仍是房主，超时才移交给房内下一位成员。
- 房间空掉后保留 5 分钟宽限期，期间可重连回同一个房间码；超时才回收。
- 单实例最多 500 个房间、每个来源 IP 默认最多 8 个仍存续的房间、每房 12 人；单连接
  每 10 秒最多 240 条消息，文本帧最大 64 KiB。空房超过 5 分钟被回收时会同时释放该
  IP 的建房额度。
- 每房在内存中保留最近 50 条聊天，跟随房间一起回收；每连接每 3 秒最多发送 3 条
  聊天消息。v5 使用 `clientMessageId` 确认并去重重试，聊天不接受图片、文件或客户端
  伪造的发送者资料。
- 昵称最多 24 个 Unicode 字素，头像是 8 个内置样式之一；一起看房间只保存房间内的
  临时成员状态。Yfuse 账号、资料与加密同步数据由独立的 `/api/v1` 账号接口持久化。
- 房主可以选择仅房主控制、全员共同控制或指定管理员；房主身份仍保持唯一，管理员
  不会影响断线后的房主迁移。
- 房主可以移出其他成员；被移出的客户端在当前房间存续期间无法再次加入。
- v5 是安全性破坏升级，服务端不接受 v2/v3/v4 或缺少 `protocolVersion` 的客户端，避免
  绕过账号鉴权，或回退到可伪造的 clientId-only 重连逻辑。

> ⚠️ **必须先部署并验证 v5 服务端，再发布 v5 App。** 房间是内存态，部署会清空旧房间；
> v5 服务端拒绝未登录连接和旧协议 App，新 App 也不会向旧协议服务端降级。服务端验证完成
> 前，Android 发布门禁会拒绝发布。

发布 App 前可请求 `GET /watch/version`，确认返回的 `protocolVersion` 与 App 要求一致；
仓库内的 Android 发布工作流已经包含这项检查。

## 本地运行

```powershell
.\gradlew.bat :watchTogetherServer:run
```

当前生产入口统一为 `https://47.112.219.60`，客户端会自动转换为 WSS 并连接
`/watch`。自建服务时也可以在「我的 → 一起看服务器」填写自己的 HTTPS/WSS 入口。
已经发布的旧客户端仍可能访问 `http://47.112.219.60`；仓库里的 Caddy 模板只为旧版更新
清单和 APK 暂时保留该明文入口。账号接口与 `/watch` 会在该入口返回 `426`，不能用于账号
凭据、一起看或新的客户端配置。

服务同时会把 `UPDATE_ROOT` 指向的目录挂载到 `/yfuse`，默认目录为
`/srv/yfuse-update/yfuse`。因此 production 可以在同一个端口提供：

- `/watch`：一起看 WebSocket
- `/health`：健康检查
- `/yfuse/Yfuse-latest.apk`：两代客户端共用的 APK
- `/yfuse/update.json`：旧客户端清单，`apkUrl` 固定为
  `http://47.112.219.60/yfuse/Yfuse-latest.apk`
- `/yfuse/update-v2.json`：新客户端清单，`apkUrl` 为
  `https://47.112.219.60/yfuse/Yfuse-latest.apk`

两份清单的版本、SHA-256、文件大小和发布说明完全相同，只有 `apkUrl` 不同。发布脚本会先把
APK 和两份清单全部暂存，再通过同文件系统重命名分别原子替换；版本门禁优先读取 v2，只有
v2 返回 404（首次启用尚未生成）时才回退旧清单。

## 生产部署（Caddy）

生产拓扑是 `Caddy :80/:443 → Ktor 127.0.0.1:8080`。Caddy 为
裸 IP 使用 Let's Encrypt 的 shortlived 配置自动申请和续期 160 小时证书，并原生处理
`/watch` 的 WebSocket Upgrade；`yfuse.zhuiyun.site` 在完成 ICP/接入备案后仍可继续使用。
Ktor 不再直接占用公网 80 端口。

当前生产服务器位于中国内地。域名必须先完成 ICP 备案；若已在其他服务商备案，还需完成
阿里云接入备案。否则阿里云会在 Caddy 之前拦截 80/443，表现为备案 403 页面或 TLS 握手
被重置。若不备案，应将生产服务部署到中国香港或海外节点，不能用明文 IP 承载账号凭据。

1. 确保公网 80、443 可达；IP 证书续期需要持续通过 HTTP-01 或 TLS-ALPN-01 验证。
2. 创建无登录权限的 `yfuse` 系统用户；把发行包解压到
   `/opt/yfuse-watch/releases/<版本>` 并令 `/opt/yfuse-watch/current` 指向它；创建
   `/srv/yfuse-update/yfuse` 作为更新文件目录。随后把 `deploy/yfuse-watch.service`
   安装为 `/etc/systemd/system/yfuse-update.service`（沿用生产现有 unit 名，禁止同时启动
   第二个 `yfuse-watch.service`），并把 `deploy/Caddyfile` 安装到
   `/etc/caddy/Caddyfile`。另按 `docs/watch-server-deploy.md` 创建 root-only 的
   `/etc/yfuse-watch/environment`（`root:root`、`0600`），写入必需的
   `MIGRATION_RELAY_MASTER_KEY`；模板会通过强制 `EnvironmentFile` 读取它，文件缺失时
   服务应拒绝启动。
3. 关闭公网 8080，只允许本机 Caddy 访问；SSH 使用标准 22 端口。
4. 校验配置后启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now yfuse-update.service
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy.service
curl --fail https://47.112.219.60/health
curl --fail https://47.112.219.60/watch/version
curl --fail http://47.112.219.60/yfuse/update.json
# 首次双清单发布完成后再校验 v2：
curl --fail https://47.112.219.60/yfuse/update-v2.json
```

首次切换的 HSTS 仅设为 `max-age=86400` 且不包含子域；确认 DNS、证书自动续期和发布链路
稳定后，再提升到一年（`31536000`）。

模板中的 `http://47.112.219.60` 站点只为历史更新版本保留，`/api/*` 和 `/watch` 均明确
返回 `426 https_required`，不会把账号令牌或一起看 WebSocket 转发至后端。`update.json` 仍
刻意返回该 HTTP 源的同源 APK 地址；新的 `update-v2.json` 和新客户端只使用 HTTPS。确认
旧版本退出使用，并停止发布旧清单后，才应删除这个站点块。

建房保护可通过以下环境变量调整：

- `WATCH_MAX_ACTIVE_ROOMS_PER_IP`：每个来源 IP 可同时保有的房间数，默认 `8`，范围
  `1..500`。
- `WATCH_TRUST_PROXY_HEADERS`：设为 `true` 后，使用 `X-Forwarded-For`（其次 RFC
  `Forwarded`）识别来源 IP；默认 `false`，防止直连客户端伪造转发头绕过限制。
- `ACCOUNT_DB_PATH`：账号 SQLite 路径，生产模板为 `/var/lib/yfuse/account.db`。
- `CALENDAR_DB_PATH`：公共播出日历 SQLite 路径，默认
  `/var/lib/yfuse/calendar.db`；不存储任何用户媒体库凭据。
- 文件数据库启用 SQLite WAL 与 `synchronous=FULL`；在线备份必须使用 SQLite
  `.backup`/backup API，不能只复制主数据库文件。完整备份与回滚步骤见
  `docs/watch-server-deploy.md`。
- `ACCOUNT_REGISTRATION_ENABLED`：是否开放新账号注册；默认 `false`，仅在创建所需账号时临时设为 `true`。
- `ACCOUNT_REGISTRATION_INVITE_CODES`：逗号分隔的一次性邀请码；可在公开注册关闭时邀请注册。请使用高熵随机值，并在兑换后从环境变量移除。
- `ACCOUNT_INVITE_ISSUER_USERNAMES`：获准生成一次性邀请码的用户名；启动时映射到数据库用户 ID。生产模板仅配置 `zhuiyun`。
- `ACCOUNT_ISSUED_INVITE_TTL_HOURS`：动态邀请码有效期，默认 24 小时，范围 1–168 小时。
- `ACCOUNT_MAX_USERS`：账号总数上限；生产模板为 `100`。
- `MIGRATION_RELAY_MASTER_KEY`：迁移中继的 32 字节随机主密钥（无填充 base64url），必须通过
  root-only `EnvironmentFile` 注入，不能提交到仓库；用于包裹随机迁移密钥，数据库中不保存
  备份内容或明文密钥。
- `MIGRATION_RELAY_DB_PATH`：一次性迁移中继 SQLite 路径，模板为
  `/var/lib/yfuse/migration-relay.db`；服务会尽力设置为 `0600`，部署时仍须校验属主和权限。
- `HOST`：Ktor 监听地址，默认 `127.0.0.1`；只有容器内部端口映射场景才应显式设为
  `0.0.0.0`。

反向代理部署通常需要开启 `WATCH_TRUST_PROXY_HEADERS=true`，否则 Ktor 看到的来源均为
代理 IP，所有公网用户会共用默认的 8 个房间额度。生产 service 模板已经启用它；其
安全前提是 Caddy 是唯一公网入口且 8080 不可从公网直连。若换用其他代理，必须确认它
会清理客户端伪造的转发头后再开启。

## Docker

```powershell
.\gradlew.bat :watchTogetherServer:distTar
docker build -t yfuse-watch .\watchTogetherServer
# 首次运行前生成；不要把实际密钥写进仓库或 shell 历史。
$relayBytes = [byte[]]::new(32)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($relayBytes)
$relayKey = [Convert]::ToBase64String($relayBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
"MIGRATION_RELAY_MASTER_KEY=$relayKey" |
  Set-Content -NoNewline .\yfuse-watch.environment
docker run -d --restart unless-stopped `
  --network host `
  -e HOST=127.0.0.1 `
  -e UPDATE_ROOT=/updates `
  -e WATCH_TRUST_PROXY_HEADERS=true `
  --env-file .\yfuse-watch.environment `
  -v /srv/yfuse-update/yfuse:/updates:ro `
  -v yfuse-account:/var/lib/yfuse `
  --name yfuse-watch `
  yfuse-watch
```

该示例使用 Linux host network，使宿主机 Caddy 仍从 loopback 访问 Ktor；同时将 Ktor
显式绑定到 `127.0.0.1`，不会把 8080 暴露到公网。若改用 bridge 网络，必须同时设计明确的
可信代理网段，否则应用会拒绝非 loopback 代理提供的 `X-Forwarded-Proto`，账号接口将返回
`426 https_required`。`yfuse-watch.environment` 含生产主密钥，Linux 上应改为仅部署账号可读的
`0600`，并纳入独立加密备份；不要随镜像、日志或源码分发。

> `/api/v1/account/*` 使用账号令牌鉴权；协议 v5 的一起看连接同样要求有效账号访问令牌，
> 未登录用户不能建房或加入房间。房间权限和频率限制不能代替账号访问控制。公网部署必须
> 使用 HTTPS/WSS，且不能把 Ktor 的 8080 明文端口直接暴露到公网。Docker 必须持久化
> `/var/lib/yfuse`，否则重建容器会丢失账号。

房间仅驻留内存；最后一名成员退出后保留 5 分钟供重连，随后回收。

## 官方追剧日历采集

服务端可以在不信任客户端、也不把 OCR 密钥打进 APK 的前提下自动生成排期快照。配置
`YFUSE_CALENDAR_INGEST_CONFIG` 指向采集清单，并保留
`YFUSE_CALENDAR_PRIVATE_KEY_PKCS8`。生产环境会把每次通过证据门控的完整 revision 事务写入
`CALENDAR_DB_PATH`（默认 `/var/lib/yfuse/calendar.db`），再切换 current 指针；接口只会读到
上一版或完整新版本，不会读到半次采集。示例见 `calendar-ingestion.example.json`。

`discoveryFeeds` 可以指向平台官方日历索引或认证官微归档页。示例已内置爱奇艺电视剧、优酷、
腾讯电视剧和芒果TV四个官方账号。采集器能同时识别普通详情链接、官微时间线中彼此分离的
永久链接/正文/图片，以及页面内嵌 JSON，不要求渲染服务预先改写成特定 `<a>` 格式；官微
搜索标签页不会被误当成官方证据详情。默认识别“《剧名》追剧日历”、`#剧名追剧日历#`、
“最新追剧日历请查收”、观看指引和通关攻略等常见写法，并自动进行严格 TMDB 身份绑定，再把
命中的详情页送入原有证据门控，因此不再要求每部新剧都手工加入 `shows`。平台出现新格式时
仍可用 `titlePattern` 提供包含一个剧名捕获组的正则。`shows` 保留给需要固定 TMDB ID、播出
时间或来源的重点剧集。归档首页滚动移除旧链接时，已经验签的排期会保留 45 天，不会瞬间消失。

`domesticCandidates` 是国内剧的宽候选层。启用后，服务端会分页读取 TMDB `on_the_air`，只保留
中国大陆、中文、剧情类型的在播条目，并并行读取爱奇艺、优酷、腾讯视频、芒果TV官方电视剧目录。
这些来源只提供剧名、TMDB ID、海报和可能的平台归属，**不能直接成为排期证据**。候选必须再次在
`verifiedAccounts` 白名单内的认证官微时间线中唯一命中具体永久帖，且正文带有日历、排期、首更、
更新或会员等信号，才会进入原有正文解析和双 OCR 流程。这样能扩大“要找哪些新剧”的范围，又不会
把 TMDB 预测日期、平台营销卡片或同名内容当成官方更新表。平台目录结构变化时可给单个
`platformCatalogs` 条目配置带一个剧名捕获组的 `titlePattern`，其余证据门控不变。

海外剧走独立的结构化链路，不使用中文 OCR。把示例中的 `overseas.enabled` 设为 `true` 后，
采集器从 TVmaze full schedule 自动发现指定国家和全球流媒体的新季，只保留 Scripted/Animation
并按 TVmaze 热度优先截取配置上限，通过 IMDb 外部 ID 严格绑定
TMDB，再并行读取 TMDB season/episode 与 TVmaze episode 数据。两边至少一个相同分集日期一致时
以 `Verified / 85` 发布；只有 TVmaze 时为 `Estimated / 70`，只有 TMDB 时为
`Estimated / 65`；任一相同分集日期冲突则拒绝更新并保留上一版。该链路需要服务端环境变量
`TMDB_TOKEN`，令牌不会进入 App。

TVmaze 提供 `airstamp` 时，快照同时保存原播日期、UTC 时刻和北京时间；全球流媒体只有发布日期
而没有时刻时，`airTime`/`timeZoneId` 保持为空，客户端只显示日期，不再伪造 00:00 或 12:00。
每条海外排期还携带 `origin`、地区、周播/批量/仅日期模式及 TMDB/TVmaze 证据摘要。采集状态接口
分别返回国内与海外自动发现数量，便于判断是官方归档还是海外 Provider 出现空数据。

微博和部分平台使用动态渲染时，可配置 `pageRenderer`。渲染桥接服务接收
`POST {"url":"https://官方页面"}` 并返回 `{"html":"..."}` 或 `{"content":"..."}`；令牌只
通过 `apiKeyEnvironment` 指定的环境变量注入。原始 HTML 和内嵌 JSON 都无法发现日历时才调用
渲染服务。渲染服务只需返回最终 DOM，不再承担平台专用解析。原始页面请求会对连接失败、429
和 5xx 做三次短退避重试，并兼容常见图片懒加载属性；确认是日历正文后，即使图片 `alt` 没有
“日历”字样，也会从可信图片 CDN 中提取正文图片并排除头像、图标和表情资源。

仓库内 `calendarRenderer` 是已经实现的 Playwright 渲染 sidecar，不需要另找第三方渲染服务。
复制 `renderer.environment.example` 为部署密钥文件，在部署机生成至少 24 位随机
`YFUSE_RENDERER_TOKEN`，然后在该目录执行 `docker compose up -d --build`。sidecar 只监听宿主机
`127.0.0.1:8091`、只接受五类白名单官方域名，并限制并发、响应体和缓存。微博当前会对纯 HTTP
采集返回访客验证页，因此生产环境还需要把已登录会话导出的 Playwright `storage-state.json`
放入 `calendarRenderer/renderer-state/` 并只读挂载到 sidecar，或通过部署密钥
`YFUSE_RENDERER_WEIBO_COOKIE` 注入 Cookie；两者都不能提交
到仓库。主服务示例已经指向 `http://127.0.0.1:8091/v1/render`，回环明文仅在本机被允许，远程
renderer 仍强制 HTTPS。
本地安装 renderer 依赖后可执行 `npm run capture-session`，在打开的浏览器中完成一次微博登录并
按回车，脚本会直接写入被 Git 忽略的 `renderer-state/storage-state.json`，无需手工整理 Cookie。

`GET /api/v1/calendar/schedules` 从 SQLite 读取 current revision，继续返回 Ed25519 签名载荷并
支持 ETag/304。App 每小时检查一次 revision，只有变化时下载并验签，随后写入已有的本地日历
缓存；用户自己的 Emby/Jellyfin 凭据、库存和观看状态仍只在客户端处理。首次升级若数据库为空，
服务会从原有 `YFUSE_CALENDAR_SCHEDULES_JSON` / `YFUSE_CALENDAR_SCHEDULES_PATH` 或内置
快照导入一次。文件路径仍可选配为额外的运维快照，但不再是线上接口的主数据源。

`GET /api/v1/calendar/ingestion/status` 返回脱敏采集健康状态，包括运行状态、开始/结束时间、
静态剧数、国内/海外自动发现剧数、TMDB 国内候选数、四大平台目录候选数、合并候选数、官微证据
命中数、发布剧数和最近错误摘要，可直接接入存活检查或监控告警；响应禁止缓存，不会返回来源正文、
OCR 密钥或 TMDB 令牌。

采集器只接受四类平台官方域名（爱奇艺、优酷、腾讯视频、芒果TV）和清单中明确白名单化的
微博认证账号。页面正文可以直接解析；图片必须经过两个独立 OCR 服务的逐集置信度门控后才
参与判定。通用 OCR bridge 接收 `POST {"imageUrl":"https://..."}`，返回
`{"text":"..."}`；也可将 provider 的 `protocol` 设为 `PaddleOcrJobs`，直接调用
PaddleOCR 官方异步任务接口并读取其 JSONL Markdown 结果。PaddleOCR Token 仅通过
`apiKeyEnvironment` 指定的环境变量注入，且实现会拒绝把该凭据发送到非官方域名。

第二路可将 `protocol` 设为 `OcrSpace`，调用 OCR.space 的 `/parse/image` POST 接口。
追剧日历默认使用 Engine 3、自动语言检测、方向检测、放大和表格模式；API Key 同样只从
环境变量读取，并且只允许发送到 `api.ocr.space`。免费端点存在每日次数和共享资源限制，
失败时采集器保留上一份签名快照，不会降级为单 OCR 发布。

允许先配置一个 OCR provider 进行接入验证，但单 OCR 结果不会进入发布数据。双 OCR 门控
支持四种可审计共识：完全一致；至少 3 个坐标一致且一方是另一方的子集；至少 3 个相同坐标
的交集；以及双方都识别出相同的“全集上线”或“首更 N 集、随后每日 M 集”语义且一方提供
完整坐标。任何相同集数对应不同日期的结果都会立即拒绝，不能靠置信度分数覆盖冲突。
生产环境推荐使用 `PP-StructureV3` 保留日历表格布局，并以 OCR.space 作为不同厂商的第二路
OCR；`PaddleOCR-VL-1.6` 和 `PP-OCRv6` 仍可在配置中选用或用于回归对比。

置信度门控固定在服务端：平台官方页、认证官微、明确分集坐标、双 OCR 共识、TMDB 严格身份
和第二官方来源分别贡献证据。OCR 完全一致加 20 分、子集或语义互证加 15 分、坐标交集加
10 分。80 分及以上发布为 `Official`，60–79 分发布为 `Estimated`，
低于 60 分、同一集出现冲突日期、TMDB 身份不唯一或只有一个 OCR 结果时均不发布。每条下发
记录保留来源 URL、采集时间、内容 SHA-256 和提取方式；上游暂时失败时继续使用最后一份已
签名快照，不会生成猜测日期。

TMDB ID 已在清单中给出时不会请求 TMDB；否则需要通过 `TMDB_TOKEN` 注入令牌，且只有标题
标准化后完全一致、首播年份相差不超过一年并且候选唯一时才自动绑定。Emby 凭据和库存不进入
该服务：客户端仍直接查询用户自己的 Emby，并独立计算真实剧集文件数。

仓库内的 `deploy/yfuse-watch.service` 是 Caddy 后端模板：Ktor 使用 8080，运行目录采用
`/opt/yfuse-watch/current`，静态文件继续放在 `/srv/yfuse-update/yfuse`。旧的
`scripts/yfuse-update.service` 仅供仍将静态更新服务拆开的安装使用，现已限制为
`127.0.0.1:8081`，默认组合部署不需要启用它。
