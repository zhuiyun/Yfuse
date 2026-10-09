# 自有后端通信隔离实施记录

用户确认范围：账号/云同步、一起看、日历服务、接力/遥控、远程播放策略、统计和更新服务全部隔离；用户配置的 Emby/Jellyfin/Plex 保留。迁移码与自有 Trakt 授权中介同属自有通信，一并处理。

## 已完成

1. 新建 `yfuseBackendClient` KMP/JVM 模块，统一地址、HTTP、WebSocket、文档读取与断点下载传输、传输模型、能力检查。模块只依赖通用库及 watchTogetherProtocol，无 App、Compose、Settings 或媒体引擎反向依赖。
2. 应用通过 BackendModule、平台引擎工厂和接口适配。账号状态、本地库/缓存、加密验签、房间状态机、媒体进度、APK 校验安装留在应用。
3. 根 gradle.properties 增加 `yfuseBackendEnabled=true`；`-PyfuseBackendEnabled=false` 生成禁用配置。HTTP API、专用客户端发送插件、WS 连接器、二进制传输均有能力边界。禁用构建保留依赖类型；物理删除步骤在 docs/BACKEND_ISOLATION.md。
4. 禁用时关闭在线入口、账号恢复与接力/遥控启动；旧更新记录不能继续下载或安装。保留账号凭据、成人/儿童个人资料、本地播放及媒体请求。日历 Worker 保持媒体查询/本地预热，由排期 API 单独跳过自有网络。迁移功能提供原有强口令离线文件/二维码流程。
5. 新增后端源码边界检查及 Kotlin JVM 文件门面冲突检查，纳入既有质量工作流；独立模块双配置测试进入 fast-jvm。新增模块依赖锁。

## 验证

| 配置 | 检查 | 结果 |
| --- | --- | --- |
| 开启 | 手机主机测试 | 3,654 / 3,654 通过 |
| 开启 | TV 主机测试 | 88 / 88 通过 |
| 开启 | 后端模块 JVM 测试 | 35 / 35 通过 |
| 开启 | 手机与 TV 应用壳/共享代码编译、三模块 ktlint | 通过 |
| 关闭 | 后端模块 JVM 测试 | 35 / 35 通过 |
| 关闭 | 手机专项测试（8 个测试类） | 10 / 10 通过 |
| 关闭 | 手机与 TV 应用编译 | 通过 |
| 源码 | 后端边界脚本测试 | 13 / 13 通过 |
| 源码 | 后端边界、原手机/TV边界、补丁空白检查、工作流 YAML 解析 | 通过 |

`enabled.json` 与 `disabled.json` 从 JUnit XML 汇总；对应 `.log` 保存实际 Gradle 输出。`default-restored.log` 确认验证后已恢复默认开启配置且模块测试/格式仍通过。`source-sha256.json` 记录当前工作区已修改源码指纹（包括本会话前序改动），用于关联本次验证。

已修复集成中发现的跨模块 nullable smart-cast、TV 迁移工厂构造及同包同名 AccountApi/AccountModels 文件产生重复 JVM 门面的问题。应用侧文件改为 AccountClientFactory.kt 与 AccountState.kt，测试已覆盖其运行调用。

## 验证边界

- 请求验证使用 MockEngine/本地连接桩，没有访问真实业务服务器。
- 没有进行手机/TV 真机界面或真实升级安装联调；更新签名、包体大小与断点续传判断沿用现有实现并通过现有回归。
- 没有打包 APK，没有修改 version.properties 或 release-notes.txt，没有推送、部署或上传。
