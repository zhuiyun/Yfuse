# 设备接力服务部署准备（2026-09-18）

状态：用户明确确认上传目标、部署包与重启操作后，已完成生产部署及公网验证。

## 部署结果

- 新发布目录：`/opt/yfuse-watch/releases/20260918-011144-2eff8485-handoff`。
- 数据库备份：`/var/lib/yfuse/backups/pre-handoff-20260918-011144`，5 个数据库均通过完整性检查。
- 旧发布目录保留，可按下方回滚命令恢复。
- 公网 `/watch/version` 为 `2eff84853c06c492dd1f1431adbebada1b0a53c6`；`/health` 返回 `ok`。
- 本机可信 HTTPS 代理请求和公网 HTTPS 请求的接力查询、心跳、账号资料接口均返回 401（匿名请求正常鉴权），不再返回 404。
- 公网明文 HTTP 接力请求仍返回 426；HTTPS 防护未放宽。
- `yfuse-update` 为 active/running，`NRestarts=0`。
- 未使用用户账号做双设备实机接力；这部分仍需两台已登录客户端验证。
- 客户端源码修复尚未重新打包，既有 APK 不变。

详细执行日志见 `deploy-final.log`，独立公网复核见 `verification.json`。

回滚（仅恢复旧二进制，不恢复或覆盖数据库）：

```bash
ln -s /opt/yfuse-watch/releases/20260908-091122-9d565adf /opt/yfuse-watch/current.rollback
mv -Tf /opt/yfuse-watch/current.rollback /opt/yfuse-watch/current
systemctl restart yfuse-update
```

## 目标及现状

- 目标：`root@47.112.219.60`，生产服务 `yfuse-update.service`。
- 已通过现有 SSH 凭证读取线上状态，服务 active/running，健康检查返回 `ok`。
- 旧发布目录：`/opt/yfuse-watch/releases/20260908-091122-9d565adf`。
- 旧 `/watch/version`：`9d565adfd2b50243af46f873e8613ca7d1939046`。
- 本机 `/api/v1/account/handoff` 仍返回 404，确认不是仅公网反向代理的问题。

## 可部署产物

- 文件：`yfuse-watch-2eff8485-handoff.tar.gz`，23,008,467 字节。
- SHA-256：`a1e16f21795f67fd0b1f7512710ed784c92707a9bbd487d4c972aef590daa7f8`。
- 服务端源码提交：`2eff84853c06c492dd1f1431adbebada1b0a53c6`，已核对构建资源中的标识。
- 本地同步了 `watchTogetherServer/gradle.lockfile` 中与现有依赖声明不一致的 Ktor 及相关依赖；没有改动服务端业务源码。
- 归档共 34 项，仅包含应用启动脚本和运行时 JAR；不包含本地数据库、SSH 配置或生产环境配置文件。
- 全部 204 项服务端测试通过，0 失败、0 跳过。锁文件更新后再次以严格锁定模式构建、测试、生成 installDist 成功。日志见 `build-test-online.log`、`build-strict.log`。

## 执行方案

上传上述归档及 `deploy.sh` 到目标服务器 `/tmp/`，按归档哈希和完整提交号执行脚本。脚本会：

1. 校验归档哈希，解压到新的发布目录，保留旧目录。
2. 使用 SQLite CLI 的在线 `.backup` 备份 `/var/lib/yfuse` 下所有数据库并执行完整性检查，不读取凭证内容到日志。
3. 原子切换 `current`，重启既有 `yfuse-update` 服务。
4. 检查健康状态、提交号、本机及公网的接力查询/心跳和账号接口鉴权响应。
5. 切换后验证失败时恢复旧发布链接并重启旧版。

不修改生产环境密钥，不覆盖 APK，不推送代码；此次不是 Android 打包，版本配置保持不变。

## 部署过程记录

初次 SCP 被自动审批拒绝，用户随后明确确认将部署包和脚本上传至 `root@47.112.219.60` 并执行更新，重试上传成功。

首次备份因旧版 Python SQLite 的 Path 支持限制停止，尚未切换服务；改用 SQLite CLI 在线备份后通过。首次切换后，本机验证遗漏可信代理的 HTTPS 标记而收到 426，脚本自动恢复旧版；补齐该验证请求的代理标记后再次部署并全部验证通过。未修改应用的 HTTPS 校验逻辑或生产密钥配置。
