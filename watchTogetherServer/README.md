# Yfuse 一起看服务

轻量 WebSocket 房间服务，只维护房间的**播放时间线**，不代理视频、不读取 Emby 凭据，
也不承担转码。

## 协议 v4

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
  聊天消息。v4 使用 `clientMessageId` 确认并去重重试，聊天不接受图片、文件或客户端
  伪造的发送者资料。
- 昵称最多 24 个 Unicode 字素，头像是 8 个内置样式之一；一起看房间只保存房间内的
  临时成员状态。Yfuse 账号、资料与加密同步数据由独立的 `/api/v1` 账号接口持久化。
- 房主可以选择仅房主控制、全员共同控制或指定管理员；房主身份仍保持唯一，管理员
  不会影响断线后的房主迁移。
- 房主可以移出其他成员；被移出的客户端在当前房间存续期间无法再次加入。
- v4 是安全性破坏升级，服务端不接受 v2/v3 或缺少 `protocolVersion` 的客户端，避免
  回退到可伪造的 clientId-only 重连逻辑。

> ⚠️ **v4 服务端与 App 必须作为一次维护窗口发布。** 房间是内存态，部署会清空旧房间；
> 新 App 会拒绝缺少 `authenticatedResume`、`hostCapability`、`strictWireValidation` 能力的
> 旧服务端，服务端也会明确拒绝旧 App。

发布 App 前可请求 `GET /watch/version`，确认返回的 `protocolVersion` 与 App 要求一致；
仓库内的 Android 发布工作流已经包含这项检查。

## 本地运行

```powershell
.\gradlew.bat :watchTogetherServer:run
```

当前生产入口统一为 `https://47.112.219.60`，客户端会自动转换为 WSS 并连接
`/watch`。自建服务时也可以在「我的 → 一起看服务器」填写自己的 HTTPS/WSS 入口。
已经发布的旧客户端仍可能访问 `http://47.112.219.60`；仓库里的 Caddy 模板暂时保留
这个明文入口作为迁移兼容，不能用于账号凭据或新的客户端配置。

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
   安装为 `/etc/systemd/system/yfuse-watch.service`，并把 `deploy/Caddyfile` 安装到
   `/etc/caddy/Caddyfile`。
3. 关闭公网 8080，只允许本机 Caddy 访问；SSH 使用标准 22 端口。
4. 校验配置后启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now yfuse-watch.service
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

模板中的 `http://47.112.219.60` 站点只为历史版本保留，仍然反代同一个 8080 后端，
因此旧更新地址和旧 `ws://` 房间入口在切换后不会立即失效。`update.json` 仍刻意返回该
HTTP 源的同源 APK 地址；新的 `update-v2.json` 和新客户端只使用 HTTPS。该连接没有传输
加密；确认旧版本退出使用，并停止发布旧清单后，才应删除这个站点块。

建房保护可通过以下环境变量调整：

- `WATCH_MAX_ACTIVE_ROOMS_PER_IP`：每个来源 IP 可同时保有的房间数，默认 `8`，范围
  `1..500`。
- `WATCH_TRUST_PROXY_HEADERS`：设为 `true` 后，使用 `X-Forwarded-For`（其次 RFC
  `Forwarded`）识别来源 IP；默认 `false`，防止直连客户端伪造转发头绕过限制。
- `ACCOUNT_DB_PATH`：账号 SQLite 路径，生产模板为 `/var/lib/yfuse/account.db`。
- 文件数据库启用 SQLite WAL 与 `synchronous=FULL`；在线备份必须使用 SQLite
  `.backup`/backup API，不能只复制主数据库文件。完整备份与回滚步骤见
  `docs/watch-server-deploy.md`。
- `ACCOUNT_REGISTRATION_ENABLED`：是否开放新账号注册；默认 `false`，仅在创建所需账号时临时设为 `true`。
- `ACCOUNT_REGISTRATION_INVITE_CODES`：逗号分隔的一次性邀请码；可在公开注册关闭时邀请注册。请使用高熵随机值，并在兑换后从环境变量移除。
- `ACCOUNT_MAX_USERS`：账号总数上限；生产模板为 `100`。
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
docker run -d --restart unless-stopped \
  --network host \
  -e HOST=127.0.0.1 \
  -e UPDATE_ROOT=/updates \
  -e WATCH_TRUST_PROXY_HEADERS=true \
  -v /srv/yfuse-update/yfuse:/updates:ro \
  -v yfuse-account:/var/lib/yfuse \
  --name yfuse-watch \
  yfuse-watch
```

该示例使用 Linux host network，使宿主机 Caddy 仍从 loopback 访问 Ktor；同时将 Ktor
显式绑定到 `127.0.0.1`，不会把 8080 暴露到公网。若改用 bridge 网络，必须同时设计明确的
可信代理网段，否则应用会拒绝非 loopback 代理提供的 `X-Forwarded-Proto`，账号接口将返回
`426 https_required`。

> `/api/v1/account/*` 使用账号令牌鉴权；一起看房间仍允许未登录用户加入，房间权限和
> 频率限制不能代替账号访问控制。公网部署必须使用 HTTPS/WSS，且不能把 Ktor 的 8080
> 明文端口直接暴露到公网。Docker 必须持久化 `/var/lib/yfuse`，否则重建容器会丢失账号。

房间仅驻留内存；最后一名成员退出后保留 5 分钟供重连，随后回收。

仓库内的 `deploy/yfuse-watch.service` 是 Caddy 后端模板：Ktor 使用 8080，运行目录采用
`/opt/yfuse-watch/current`，静态文件继续放在 `/srv/yfuse-update/yfuse`。旧的
`scripts/yfuse-update.service` 仅供仍将静态更新服务拆开的安装使用，现已限制为
`127.0.0.1:8081`，默认组合部署不需要启用它。
