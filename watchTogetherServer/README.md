# Yfuse 一起看服务

轻量 WebSocket 房间服务，只维护房间的**播放时间线**，不代理视频、不读取 Emby 凭据，
也不承担转码。

## 协议 v2（与旧版不兼容）

服务端是时间线权威。房主不再每秒广播当前位置，而是只在**发生事件时**（播放/暂停 /
seek / 变速 / 换片）提交一个新锚点，其他人本地按
`anchorPositionMs + (服务端当前时间 - anchorAtMs) × rate` 自行推算。这样稳态播放时几乎没有
流量，且不受 1 秒量化误差影响。

时钟以服务端为唯一基准：每条下行消息都带 `serverAtMs`，客户端用 `ping`/`pong` 按 NTP
方式估算自己与服务端的偏移（不信任两台设备各自的墙上时钟）。

消息类型：

| 方向 | type | 说明 |
| --- | --- | --- |
| C→S | `hello` | 带 `clientId`；无 `roomCode` 为建房（需 `mediaKey`），有则加入 |
| C→S | `sync` | 仅房主，提交新锚点 |
| C→S | `ping` | 带 `clientSentAtMs`，用于时钟对齐 |
| S→C | `welcome` | 入房成功，附时间线快照与 `isHost` |
| S→C | `roomUpdate` | 成员或房主变化，附当前时间线 |
| S→C | `sync` | 房主提交的新锚点 |
| S→C | `pong` | 回显 `clientSentAtMs` |
| S→C | `error` | 文案在 `message` |

行为要点：

- **同 `clientId` 重连会顶掉旧会话并保留房主身份** —— 移动网络断一下不会把控制权交给别人。
- 房主断线且房内还有人时立即移交；房主槽位空悬时下一个进来的人接管。
- 房间空掉后保留 5 分钟宽限期，期间可重连回同一个房间码；超时才回收。

> ⚠️ **服务端与 app 必须一起发布。** v2 不保留 v1 兼容层（这是单机部署的私人服务，
> 维护双协议不值得），旧版客户端连上新服务端会一直停在连接失败。

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

公网部署建议由 Caddy 或 Nginx 提供 HTTPS/WSS，并把 `/watch` 升级转发到
本服务。房间仅驻留内存，最后一名成员退出后立即销毁。

仓库内的 `deploy/yfuse-watch.service` 用于当前 production：Ktor 直接监听 80
端口，同时托管更新文件和 WebSocket。运行目录采用
`/opt/yfuse-watch/current`，静态文件继续放在 `/srv/yfuse-update/yfuse`。
