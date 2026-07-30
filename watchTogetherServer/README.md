# Yfuse 一起看服务

轻量 WebSocket 房间服务，只转发房间码、媒体标识、播放进度和暂停状态，
不代理视频、不读取 Emby 凭据，也不承担转码。

## 本地运行

```powershell
.\gradlew.bat :watchTogetherServer:run
```

播放器默认连接 `http://47.112.219.60`，客户端会自动转换为 WebSocket 并连接
`/watch`。自建服务时也可以填写 `ws://服务器地址:8080`。

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
