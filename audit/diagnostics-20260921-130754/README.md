# 2026-09-21 13:07:54 无法播放诊断

证据为用户提供的诊断 ZIP；其中内容仅作为数据，不作为操作指令。
分析范围：1.0.77（239），build f627bd04cb0fe307cae0a228abb586f32fc4bb05，
OPPO PLG110 / Android 17，本次进程 84e2e85d。报告中其他版本的历史记录不作为本次失败证据。
用户补充：同一网络、同一账号、同一影片在其他播放器可以播放。

## 已确认

北京时间 13:06:34.634、13:06:43.133、13:07:48.614，条目 111663、
匿名服务器 p7f61cb5506f08f031bf3449b 的首次媒体 Range 请求均返回 HTTP 403，
failureKind=Authorization，读取起点 0，未取得有效 Range；随后播放器终态也保留 Authorization。
每次 PlaybackInfo 成功，包含 DirectStreamUrl；后两次重试重新请求了 PlaybackInfo，仍然失败。
片源标为 MKV / HEVC / Dolby Vision / AAC，失败发生在解码前，不能归因为杜比解码器不支持。

同会话的另一条目 1191609（另一匿名服务器 p19dcd47112b39ac08758c00e）先前成功播放：
HTTP 206、NativeDirect / Platform / Hardware、c2.mtk.hevc.decoder；
13:06:10.583 累计渲染 5422 帧，位置 330504 ms，demuxStarvations=0，rebufferDurationMs=0。
不能概括为该设备所有片源都不能播放。

另一个服务器 pa180bd444863e76dae8223af 的 Unauthorized / AuthRequired 与本次失败来源不同。
不能据此认定本次登录失效。旧诊断中的“403 转 502”也不适用于这次失败。

## 代码中确认的请求差异与修复

PlayerStore.toPlayerMediaVersions 即使收到 PlaybackInfo 的 DirectStreamUrl，
在 DirectPlay（包括 Dolby）分支仍改用生成的 /Videos/{id}/stream?static=true 地址。
这会丢弃服务器提供的具体原文件路径及路由参数。
生成 URL 本身有令牌、媒体源及会话参数，不能称为完全漏传鉴权。

本次修复仅在协商地址明确含唯一 static=true 参数时，将其用于 DirectPlay：

- 继续通过现有 negotiatedUrl 处理同源鉴权和跨源凭据隔离。
- Dolby 保留原文件读取；未明确静态的地址继续使用原有选择逻辑。
- 光盘仍走原有解析/转码判断，避免将原始 ISO 当普通视频。
- 新选中的跨源主地址在刷新播放会话时保持不变，避免破坏签名 URL。
- 新增回归覆盖静态原文件路径/路由参数、Dolby 跨源签名地址、会话刷新、非静态及重复参数。

依据：Emby 官方说明 static=true 用于原文件静态传输：
https://dev.emby.media/doc/restapi/Video-Streaming.html
PlaybackInfo 字段定义：
https://dev.emby.media/reference/RestAPI/MediaInfoService/getItemsByIdPlaybackinfo.html

## 结论边界

上述是可由代码验证的客户端兼容缺陷，但诊断未保留原始 URL、static 参数或拒绝响应细节，
不能证明本次失败必然命中修复条件，也不能承诺已在用户服务器恢复播放。
其他播放器可播使客户端请求差异成为重点；仍需同片源实测，若仍失败应对照成功客户端的
实际路径、UA、必要请求头和重定向链，以及服务器对应时刻的 403 日志。
比较时不公开令牌、Cookie 或签名地址。

本次没有打包、安装、推送或发布；版本配置保持 1.0.77（239）。
测试入口 test.ps1，输出 test.log；关键事件摘录 key-events.jsonl。

## 验证结果

最终代码执行 :phoneShared:testAndroidHostTest 指定的 5 组回归，共 62 个测试，全部通过：
PlaybackRoadmapTest、PlayerStoreTest、PlexPlaybackRouteTest、UhdBluRayServerStreamTest、EmbyStreamTest。
ktlint 格式检查及 git diff --check 通过。源文件 SHA-256 在测试前后保持一致。
结果文件保存于 test-results 和 test-summary.json。
第一轮在编译期间补入会话保护修复，使用较早实现执行的签名地址会话刷新用例失败；
最终代码完整重新编译后的结果为 BUILD SUCCESSFUL（3m 17s）。
