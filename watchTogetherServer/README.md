# Yfuse 一起看服务

轻量 WebSocket 房间服务，只转发房间码、媒体标识、播放进度和暂停状态，
不代理视频、不读取 Emby 凭据，也不承担转码。

## 本地运行

```powershell
.\gradlew.bat :watchTogetherServer:run
```

播放器里填写 `ws://服务器地址:8080`，客户端会连接 `/watch`。

## Docker

```powershell
.\gradlew.bat :watchTogetherServer:installDist
docker build -t yfuse-watch .\watchTogetherServer
docker run -d --restart unless-stopped -p 8080:8080 --name yfuse-watch yfuse-watch
```

公网部署建议由 Caddy 或 Nginx 提供 HTTPS/WSS，并把 `/watch` 升级转发到
本服务。房间仅驻留内存，最后一名成员退出后立即销毁。
