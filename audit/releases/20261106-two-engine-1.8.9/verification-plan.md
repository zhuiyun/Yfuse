# 1.8.9 / 341 验证计划

上次 APK 实读：`com.yfuse`，1.8.8 / 340。新配置为 YCore + SenPlayer，替代旧 full/MDK 要求。保留正式签名证书，HTTP 不受限制，不进行 SenPlayer 许可联网。

1. 严格依赖锁更新，编译手机、TV 共用生产代码及测试，执行本机 JVM 回归。
2. 编译隔离 ID `com.yfuse.validation.twoengine` 的 debug/instrumentation；只运行生成本地 AVC/AAC、回环 HTTP、包输入和 native API smoke，不使用用户账号或私人媒体。
3. Android 9 设备序列只用于本地 ADB 选择，不进入遥测。安装隔离包，不覆盖 `com.yfuse` 数据，不改设备时间/网络/全局设置。
4. 先结束本地 Git 合并并记录源码提交，再生成正式 APK。前置清单绑定源码、原生输入和 SDK android.jar。
5. 最终 APK 实读版本、包名、正式签名和 SHA-256；检查两内核边界、无旧库/旧引擎类、五项 Sen 原始资产、YCore 独立库、DEX 全入口与 16 KiB 对齐。
6. 本地交付代码归档及 APK，记录未覆盖设备/格式/DRM/许可场景，不以单元测试代替实际音视频输出验证。不推送或发布线上更新。
