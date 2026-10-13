# 1.2.8 / 280 本地正式签名完整版交付

- 实际包名 `com.yfuse`，版本 `1.2.8` / `280`，高于上一份实际交付 APK `1.2.7` / `279`；两个 APK 均重新读取元数据并验证签名。
- 最终文件：`artifacts-local/releases/playback-buffer-1.2.8-280/Yfuse-1.2.8-full-arm64.apk`。
- 大小 29,909,695 字节；SHA-256：`90999f5ca6635d20bdb1e4f6261ecdfb62ec92562bdb62c833c08e72682ffd40`。保留实际大小记录，不设置大小上限。
- 正式签名证书 SHA-256：`373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一包相同，v2 验证通过，单一签名者。
- 最终 APK 中 `libmdk.so`、`libyfuse-mdk-jni.so` 非空，MDK 引擎与固定 SDK 的库字节一致。ABI 为 ARM64。
- DEX：14,136 个类、62,086 个带代码的方法，0 项发现、0 个不可分析的方法；应用自身最大方法使用 209 个寄存器，低于 256 的限制。
- 16 KB ZIP 对齐通过，应用不可调试，预测返回开关仍为 `false`。
- 来源提交 `ce6e6730cdc97c8738e2afd074d403b375af7db1`，构建时工作区干净；APK 内核对了来源提交，构建前后 17 份输入文件及归档校验和一致。

本次增强缓冲进度的颜色对比，移除缓冲动画造成的更新等待，修复替换状态后仍读取旧进度的问题，并提供缓冲时间的无障碍说明。源码验证为 32 项单元测试及 Android 15 模拟器 2 项屏幕测试，详见 `audit/playback-buffer-20261012/`。屏幕测试使用独立标识的验证包及固定时间轴样本，未执行各内核的真实网络播放；正式 APK 未另行安装测试。

构建前更新了 `version.properties`、`release-notes.txt` 和与版本及固定 MDK 校验和绑定的打包确认记录。使用 `scripts/build-release-packages.ps1 -ConfirmMdkDistributionRights`，显式启用 MDK、关闭 native-only，并重新编译源码。

`verification.json` 记录最终验证信息；交付目录还保留构建日志、APK 元数据、签名和对齐输出、DEX 结果、更新说明、SHA256SUMS、构建输入清单及输入归档。本次为本地交付，未发布或上传。
