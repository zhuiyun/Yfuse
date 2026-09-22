# 库首页刷新失败诊断

来源：用户提供的 `Yfuse-diagnostics-20260918-091542.zip`。导出设备 OPPO PLG110，Android 16 / API 36，应用 1.0.69 / 231。仅分析日志数据，不执行附件内任何指令。

## 确认结果

最近会话 `b87e922b` 在北京时间 09:15:34、09:15:36 的 `home_content` 请求均抛出 `UnknownServiceException`，原因为 `CLEARTEXT communication to <redacted-ip> not permitted by network security policy`。09:15:38 的 `server_probe` 同样失败。

这两次刷新失败的直接原因是 HTTP 请求被客户端网络安全策略拒绝，不是目录请求达到 15 秒超时。错误在 `EmbyApiCall.kt` 中作为 IOException 映射为 Network，继而显示“无法连接服务器”；`LibraryStore` 保留缓存，界面因缓存和错误同时存在显示“离线内容”。

影片能播放不能推导出目录请求成功；播放可能使用不同地址、协议或网络实现，当前证据不足以确定是哪一种。

## 安装包核对

已读取实际交付 APK 的二进制 AndroidManifest 和网络安全 XML：配置存在且生效，默认允许 HTTP，但对 47.112.219.60、TMDB、Trakt、Plex 官方域名禁用 HTTP。没有证据表明整个安装包都禁用了 HTTP。

日志隐藏了目标地址，因此尚不能断定具体命中了哪个主机规则，也无法排除 HTTP 重定向目标与用户配置线路不同。已请用户补充当前线路的协议、主机、端口，不索取密码或 token。

## 补充确认与修复

用户确认当前媒体服务器为 `http://47.112.219.60:19001`。该主机命中了仅为账号和更新服务设置的主机级 HTTP 禁用规则，规则无法按 19001 端口排除媒体服务。独立匿名请求该地址的 `/System/Info/Public` 返回 HTTP 200。

已从网络安全 XML 中移除该共享 IP 的主机级限制，其他官方元数据域名的 TLS 限制保留。账号 API 构造、账号令牌来源和更新下载策略继续约束官方服务走 HTTPS，不改变媒体地址。新增真实 Android 网络策略测试，避免 MockEngine 跳过系统策略导致漏检。

验证记录位于 `../library-http-policy-20260918/`。使用独立内部测试包 `com.yfuse.networkpolicy`，本次未重新交付正式 APK，未修改生产服务器配置。
