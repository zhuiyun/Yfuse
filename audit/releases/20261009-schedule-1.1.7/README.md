# 1.1.7（269）本地签名 Full 包

本次从干净的本地提交 `aeb27fbf81b1e0610f5ddfb6d5695a2efbbe0cf6` 构建。提交含 `[artifact only]`，本次未推送、上传或发布。根目录 `version.properties` 与 `release-notes.txt` 已在构建前同步为 1.1.7 / 269。上一份实际交付的 Full APK 经文件哈希和 `aapt` 核对为 1.1.6 / 268。

构建显式使用 `yfuseNativeOnlyRuntime=false`、`yfuseIncludeMdk=true`、`confirmMdkDistributionRights=true` 和 `kotlin.incremental=false`，未通过 Gradle 参数覆盖版本。`.github/mdk-distribution-approval.json` 已绑定本次版本、原有固定 MDK 组件 SHA-256 和 package-only 范围。

最终 APK 为 `artifacts-local/releases/schedule-1.1.7-269/Yfuse-1.1.7-full-arm64.apk`，仅含 arm64-v8a，29,822,778 字节，SHA-256 `760311d02b6418c007d01c53d3464dec45da54f3f824f0936529e0a06dc6948a`。从交付副本读取的实际包名为 `com.yfuse`，版本为 1.1.7 / 269；唯一签名者的正式证书 SHA-256 与上一包一致，APK v2 签名和 16 KiB 对齐通过。

APK 内的 `libmdk.so` 与固定 SDK 原件及上一份已验证的 Full APK 逐字节一致，`libyfuse-mdk-jni.so` 非空且与上一包一致。8 个 YCore 原生库均与上一包逐字节一致。DEX 验证覆盖 14,059 个类、61,719 个有代码的方法，0 个发现、0 个未能分析的方法。

本次实际源码改动包括播出日历显示跳号和跨季的具体集号、库首页重复书架内容类型、首页海报加载完成后再取主题色，以及性能基准的弹幕测试夹具。`ShowScheduleTest` 11/11 通过，版本、MDK 确认与性能脚本的离线测试 20/20 通过。按用户要求未使用手机测试；库页掉帧仍待用户日志分析，弹幕真机帧率未测。

完整元数据见 [verification.json](verification.json)。APK、R8 mapping、构建日志、包信息、签名、对齐、DEX 输出、测试报告以及 788 项锁文件依赖的 SPDX 快照保存在上述受忽略的本地交付目录。SPDX 快照为离线清单，本次未做在线漏洞扫描。
