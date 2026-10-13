# 1.2.9 / 281 — 默认水火既济 Logo

本地正式签名 full ARM64 包，包含固定 MDK。实际 APK 版本与 `version.properties`、更新说明首行一致，高于已交付的 1.2.8 / 280。版本、正式签名、MDK、DEX、16 KB 对齐、大小和 SHA-256 验证结果见 `verification.json`。

APK 内编译后的默认图标背景、前景和单色层已直接读取，并确认与水火既济图案一致；原版图标资源及旧水火既济别名保留。源代码通过 16 项启动及主题偏好测试、4 项图标约束检查和相关代码格式检查，报告位于 `audit/default-logo-20261013`。

产物：`artifacts-local/releases/default-water-fire-1.2.9-281/Yfuse-1.2.9-full-arm64.apk`。同目录保留实际 APK 信息、签名和图标层输出、构建日志、更新说明、SHA-256 清单，以及构建前后的源文件哈希和源码归档。

本次没有真机或模拟器 UI 验证；没有执行上传或发布。
