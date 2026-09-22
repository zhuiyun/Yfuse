# 项目优化实施结果 — 2026-09-10

Android 和 TV 的本轮改动已完成本地编译、单元测试及针对性格式检查；影视发现已移除。鸿蒙已接入业务流程源码，但缺少配套 SDK，不能标记为完整交付。按用户最新要求，已停止手机测试。

## 已完成的改动

- 修复 Android/TV 编译，TV 界面及测试移入 tvApp，增加模块边界检查，共用诊断构建元数据脚本。
- Core2、Exo 双字幕及 MPV 双文本字幕采用底部堆叠：主字幕在上、副字幕在下；加入语言配对、交换、独立字号、位置预览和剧集偏好保存。MPV 图片/未知字幕及 MDK 保留引擎排版，界面说明限制；独立时间偏移仅在支持的内核上开放。
- 下载增加存储预算、自动下载仅充电、跨午夜时段设置；并发下载累计预留字节，活动下载遇到限制时保存断点延后，自动任务带充电约束。Android 与 TV 均有设置入口。
- 增加时间点书签，支持名称、备注、跳转、删除；按服务器/媒体/版本隔离持久化，损坏的数据保留并报错。
- 将氛围光状态、采样和前后台处理从 PlayerRoot 提取到独立绑定，降低该文件耦合。
- CI 增加独立协议/服务端测试与发布脚本/模块检查，保留完整 Android、native、lint 和 R8 门禁。
- 移除影视发现的入口、搜索项、能力声明与鸿蒙残留分支；源搜索仅剩验证旧关键词无结果的测试。
- 性能测试增加搜索/我的/首页导航旅程，并加入 Profile 生成器；修复宏基准 Java/Kotlin 目标不一致和旅程 UI 等待问题。
- 鸿蒙 Emby/Jellyfin 根页面接入登录、多服务器、分页媒体库/搜索、详情、同源鉴权播放、续播及进度上报；增加 NativePreferences 持久化和本地进度存储，保留原有分标签导航栈。覆盖表以 sourceWired 明确区分源码接通与实机实现。

## 验证证据

最终 Gradle 命令包含 `:composeApp:testReleaseUnitTest :tvApp:testDebugUnitTest :watchTogetherProtocol:jvmTest :watchTogetherServer:test :macrobenchmark:compileBenchmarkKotlin`，离线构建成功，耗时 4 分 17 秒。报告：`feature-tests.log`、`test-summary.json`。

| 本地验证 | 结果 |
| --- | --- |
| Android 单元测试 | 2,429 项通过，无失败/跳过 |
| TV 单元测试 | 59 项通过，无失败/跳过 |
| 同看协议 JVM 测试 | 8 项通过；最终一轮沿用有效缓存 |
| 服务端测试 | 175 项通过；最终一轮沿用有效缓存 |
| 发布脚本测试 | 13 项通过，3 项依赖 Bash 的 native 测试在 Windows 跳过 |
| 性能证据脚本测试 | 11 项通过，使用测试夹具，不是新真机证据 |
| 仓颉 host 测试 | 33 项通过，8 个纯逻辑包编译成功 |
| 仓颉源码/契约/脚手架/夹具 | 51 个源码文件结构检查及契约检查通过 |
| NativePreferences 桥接 | 使用已安装 OpenHarmony SDK，aarch64 目标 C++ 语法检查通过 |
| 格式、模块及 CI | 本轮 Kotlin 文件 ktlint 格式检查、Git diff 空白检查、TV manifest/banner、模块边界、发布元数据、CI YAML 通过 |

其他日志：`local-checks.log`、`harmony-host.log`、`harmony-native.log`、`format.log`。这些检查不代表 Android 完整 lint/R8 发布门禁或远端 CI 已执行。

## 未验收与排除范围

- **手机测试已停止。** 停止前宏基准启动测试通过，首页滚动和导航失败，整轮没有有效基线。相关脚本修复只经过本地编译，没有重新上手机测；没有性能提升百分比或真实 Profile 产物。已有日志在 `performance-expanded/`，不应当作验收通过。
- **鸿蒙完整构建受环境阻塞。** 已安装 Windows cjc 1.0.5 和 OpenHarmony native SDK，但缺少匹配的 DevEco Cangjie 插件/ArkUI 包及 host stdx。network、storage、support、provider 四个包仍未完整类型检查，新增 UI 未编译为 HAP。没有签名 HAP 或业务运行证据；离线进度上报重试及旧高级页面也未完整实现。详见 `harmonyApp/PORT_STATUS.md`。
- 内核设备矩阵及 8/24 小时稳定性证据由用户自行完成，本轮未执行。
- 本轮没有上传、提交或生成新的签名发布包；旧 APK 不包含本轮全部改动。
