# Yfuse 一起看服务

轻量 WebSocket 房间服务，只维护房间的**播放时间线**，不代理视频、不读取 Emby 凭据，
也不承担转码。

## 协议 v3

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
| C→S | `hello` | 带 `clientId`、昵称和预设头像；无 `roomCode` 为建房（需 `mediaKey`），有则加入 |
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
| S→C | `kicked` | 通知被房主移出的成员并结束其当前连接 |
| S→C | `error` | 文案在 `message` |

行为要点：

- **同 `clientId` 重连会顶掉旧会话并保留房主身份** —— 移动网络断一下不会把控制权交给别人。
- 房主断线后保留 20 秒控制权；宽限期内重连仍是房主，超时才移交给房内下一位成员。
- 房间空掉后保留 5 分钟宽限期，期间可重连回同一个房间码；超时才回收。
- 单实例最多 500 个房间、每个来源 IP 默认最多 8 个仍存续的房间、每房 12 人；单连接
  每 10 秒最多 240 条消息，文本帧最大 64 KiB。空房超过 5 分钟被回收时会同时释放该
  IP 的建房额度。
- 每房在内存中保留最近 50 条聊天，跟随房间一起回收；每连接每 3 秒最多发送 3 条
  聊天消息。v3 使用 `clientMessageId` 确认并去重重试，聊天不接受图片、文件或客户端
  伪造的发送者资料。
- 昵称最多 24 个 Unicode 字素，头像是 8 个内置样式之一；服务端只保存当前房间内的
  成员资料，不保存账号或图片。
- 房主可以选择仅房主控制、全员共同控制或指定管理员；房主身份仍保持唯一，管理员
  不会影响断线后的房主迁移。
- 房主可以移出其他成员；被移出的客户端在当前房间存续期间无法再次加入。
- 新服务端继续接受未携带 `protocolVersion` 的 v2 客户端；v3 客户端会检查服务端返回的
  版本并明确提示 App 或服务器哪一端过旧。

> ⚠️ **涉及协议的版本仍须服务端先发布、App 后发布。** 新服务端兼容当前旧 App，
> 新 App 则拒绝连接缺少 v3 能力的旧服务端，避免功能看似可用但消息被静默忽略。

发布 App 前可请求 `GET /watch/version`，确认返回的 `protocolVersion` 与 App 要求一致；
仓库内的 Android 发布工作流已经包含这项检查。

## 本地运行

```powershell
.\gradlew.bat :watchTogetherServer:run
```

播放器默认连接 `http://47.112.219.60`，客户端会自动转换为 WebSocket 并连接
`/watch`。自建服务时也可以在「我的 → 一起看服务器」填写 `ws://服务器地址:8080`。

服务同时会把 `UPDATE_ROOT` 指向的目录挂载到 `/yfuse`，默认目录为
`/srv/yfuse-update/yfuse`。因此 production 可以在同一个端口提供：

- `/watch`：一起看 WebSocket
- `/health`：健康检查
- `/yfuse/Yfuse-latest.apk` 与 `/yfuse/update.json`：应用更新

建房保护可通过以下环境变量调整：

- `WATCH_MAX_ACTIVE_ROOMS_PER_IP`：每个来源 IP 可同时保有的房间数，默认 `8`，范围
  `1..500`。
- `WATCH_TRUST_PROXY_HEADERS`：设为 `true` 后，使用 `X-Forwarded-For`（其次 RFC
  `Forwarded`）识别来源 IP；默认 `false`，防止直连客户端伪造转发头绕过限制。

反向代理部署通常需要开启 `WATCH_TRUST_PROXY_HEADERS=true`，否则 Ktor 看到的来源均为
代理 IP，所有公网用户会共用默认的 8 个房间额度。**只有在 Caddy/Nginx 会覆盖而不是
保留客户端传入的转发头时才能开启**；否则攻击者仍可伪造 IP 绕过额度。

## Docker

```powershell
.\gradlew.bat :watchTogetherServer:installDist
docker build -t yfuse-watch .\watchTogetherServer
docker run -d --restart unless-stopped \
  -p 8080:8080 \
  -e UPDATE_ROOT=/updates \
  -v /srv/yfuse-update/yfuse:/updates:ro \
  --name yfuse-watch \
  yfuse-watch
```

> ⚠️ 本服务本身没有账号鉴权。容量、帧大小和消息频率限制只用于防止资源耗尽，
> **不能代替认证或传输加密**。公网部署必须由 Caddy 或 Nginx 提供 HTTPS/WSS，
> 并在反向代理层增加访问控制，再把 `/watch` 升级转发到本服务。不要把 Ktor 的
> 明文 WebSocket 端口直接暴露到公网。

房间仅驻留内存；最后一名成员退出后保留 5 分钟供重连，随后回收。

仓库内的 `deploy/yfuse-watch.service` 是直连 Ktor 的开发/内网部署模板；若用于公网，
必须按上一段在前面加 TLS 和访问控制。运行目录采用
`/opt/yfuse-watch/current`，静态文件继续放在 `/srv/yfuse-update/yfuse`。
