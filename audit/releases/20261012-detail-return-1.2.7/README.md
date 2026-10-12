# 1.2.7（279）正式签名完整版

本地交付 APK：`artifacts-local/releases/detail-return-1.2.7-279/Yfuse-1.2.7-full-arm64.apk`。

- 实际包名：`com.yfuse`；实际版本：`1.2.7` / `279`。
- 上一份实际交付包：`1.2.6` / `278`，已重新读取 APK 元数据并验证签名。
- 新包大小：29,909,915 字节。保留实际大小记录，不设置大小上限。
- SHA-256：`732a28d8a723d71bcd19276d1ff10f5c50b7c43ad0e10849a2e6116f806a1df9`。
- 正式签名证书 SHA-256：`373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一包相同，v2 验证通过，单一签名者。
- 最终 APK 含非空 `libmdk.so`、`libyfuse-mdk-jni.so`；MDK 引擎与固定 SDK 的库字节一致。
- DEX：14,136 个类、62,088 个带代码的方法，0 项发现、0 个不可分析的方法；应用自身最大方法使用 211 个寄存器，低于 256 的限制。
- 16 KB ZIP 对齐通过；最终 APK 内的预测返回开关为 `false`，应用不可调试。
- 构建来源：本地提交 `44d782bfe6ac56f336dd79502db006f5ab4811f0`，构建时工作区干净。APK 内已核对来源提交，构建前后的 28 份输入文件及归档校验和一致。

本次包含详情页剧集与资源比较区块交换、关闭系统预测返回、共享图片圆角修复、减少整页裁剪开销，以及此前的日历导出修复和取消 APK 大小上限。

构建前已更新 `version.properties`、`release-notes.txt` 和与版本及固定 MDK 校验和绑定的本地打包确认记录。使用 `scripts/build-release-packages.ps1 -ConfirmMdkDistributionRights`，显式启用 MDK、关闭 native-only，并重新编译源码。

源码验证：72 项单元测试和 Android 15 模拟器 5 项测试通过，详见 `audit/detail-return-20261012/`。本次正式 APK 的版本、签名、MDK、DEX、对齐和来源校验通过；正式包未另外进行设备安装测试。

`verification.json` 保存最终产物信息。交付目录还保留构建日志、实际 APK 元数据、签名与对齐输出、DEX 结果、更新说明、SHA256SUMS、构建输入清单和输入归档。此记录对应本地交付，发布及上传状态为 false。
