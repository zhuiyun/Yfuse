# 1.3.4 / 286 — 全内核输出与运行时优化

本地正式签名 full ARM64 APK，包名 `com.yfuse`。交付文件位于：

`artifacts-local/releases/kernel-optimization-1.3.4-286/Yfuse-1.3.4-full-arm64.apk`

- 实际版本：`1.3.4 / 286`，高于读取上一交付 APK 得到的 `1.3.3 / 285`；与根目录版本配置及更新说明一致。
- 大小：29,953,339 字节；没有设置大小上限。
- SHA-256：`f037d3326613825170d855fbef0a940853420da839a55813c9c693f6054cd095`。
- 单一正式签名者，V2 签名验证通过；证书 SHA-256 为 `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一交付 APK 一致。
- 包含固定 MDK 运行库与重新构建的桥接库，新增 `nativePlaybackSnapshot` JNI 接口已在 APK 中核实。
- DEX：14,190 个类、62,364 个含代码的方法，零问题、零无法分析的方法；应用自身最大方法 210 个寄存器，低于 256 限制。
- ZIP 的 16 KB 对齐验证通过；构建输出、分发副本与交付副本 SHA-256 一致。

构建使用 `scripts/build-release-packages.ps1 -AllowDirty -ConfirmMdkDistributionRights`，脚本明确启用 `yfuseIncludeMdk=true`、`yfuseNativeOnlyRuntime=false`，关闭 Kotlin 增量编译。基于提交 `cb9e444ffd5f36184c40a2c2ed127cf580b9295c`；本次工作区包含未提交改动，交付目录的 `source-inputs.json` 与 `changed-source.zip` 记录 34 个改动文件，构建后重新比对成功。

本轮改动覆盖 Exo 实际解码器与音频输出判断、MPV 音频采样、MDK JNI 快照与窗口状态、自研音频时钟、公共资源压力恢复。手机 276 项、TV 118 项、可移植 ABI 20 项、基线脚本 2 项测试通过，详见 `audit/kernel-optimization-20261014/`。TV 与 Harmony 共用源码改动包含在源码归档中，本次交付为 Android 手机 APK，未生成 TV APK 或 Harmony HAP。

交付目录同时保留构建日志、实际 APK 元数据、签名、DEX、对齐结果、版本说明、原生组件校验和、许可说明及 SPDX 依赖锁快照。该快照包含仓库构建、测试和平台依赖，并非精确 APK 依赖清单；本次未执行新的漏洞扫描。

本次未将正式 APK 安装到设备，未执行真实片源的杜比接收端、4K 长播或功耗测试。没有推送、上传或发布。复核脚本为本目录 `verify-package.py`，完整结果为 `verification.json`。
