# 2026-10-12 服务器部署记录

已在 `47.112.219.60` 上完成主后端和日历渲染服务部署，源码提交为 `b172c7e9b1dad94871166b7545a94f9b9bf08f38`。上线检查于北京时间 2026-10-12 10:03:44 通过，浏览器渲染与运行状态复查于 10:05:21 通过。

## 部署结果

- `yfuse-update.service` 当前目录：`/opt/yfuse-watch/releases/20261012-013420-b172c7e9b-backend`。
- 日历渲染当前目录：`/opt/yfuse-calendar-renderer/releases/20261012-013420-b172c7e9b`。
- 渲染镜像：`yfuse-calendar-renderer:20261012-b172c7e9b`，镜像 ID `sha256:656787e565714369ab022ca44ba2ec67911c2af7d99d2043cb1e6299b0eb22ba`。
- Playwright 为 `1.63.0`，Chromium 为 `153.0.8010.12`。官方基础镜像的配置、镜像层及 Docker 解压校验全部通过。
- 服务器原有环境配置、渲染会话和密钥继续使用；审计记录没有保存秘密值。
- APK 更新清单校验和保持不变，本次没有发布 APK。

主后端包含手机遥控、设备配对与远程登录、播放接力、TMDB 代理、日历合并和同步等本次拉取的服务端改动。渲染服务包含调度取消、网络访问限制和浏览器依赖更新。

## 验证

构建及测试通过：协议模块 25 项、服务器模块 260 项、渲染器 12 项，共 297 项，零失败。

公网和本机 `/health` 正常，`/watch/version` 返回目标提交、协议版本 6 及最低协议版本 5。TMDB、播放接力和账号接口对未登录请求返回 401，HTTP 明文账号接口返回 426；日历返回 200 及带签名的有效响应，修订号为 `2026-10-12-r2`。渲染器认证与私网 URL 拒绝检查通过，真实 Chromium 离线页面渲染通过，主服务及渲染器均无自动重启，渲染器没有 OOM。

手机与电视之间的完整设备联调以及登录后 TMDB 代理请求未在本次服务器部署中执行。

验证证据：[上线检查](verification.json)、[运行状态复查](post-deploy-verification.json)、[构建清单](manifest.json)、[官方镜像校验](official-image-verification.json)。

## 备份与回滚材料

五个 SQLite 数据库均采用在线备份并完成 `integrity_check`：`account.db`、`calendar.db`、`migration-relay.db`、`calendar-ocr-cache.db`、`qoe.db`。

备份路径：`/var/lib/yfuse/backups/deploy-20261012-013420-b172c7e9b/database-backups/20261012-014346`。

旧主后端保留在 `/opt/yfuse-watch/releases/20260918-011144-2eff8485-handoff`；旧渲染器保留在 `/opt/yfuse-calendar-renderer/releases/20260827-084122`，原镜像 ID 为 `sha256:200bea2696632124754016dc069527014ed879d0f1afb6265b148a6fffb1bd54`。如需回滚，切回旧目录并重启对应服务；数据库恢复需另外核对上线后的写入。

首次主后端切换因验证脚本使用了服务器 Python 3.6 不支持的参数而自动回滚。修复脚本后重新部署并通过检查，详见 `deploy-attempt1-rolled-back.log` 与 `deploy-backend.log`。官方镜像的普通下载较慢，改为同一官方源并行下载并校验后导入；取消的旧构建没有切换线上渲染器。
