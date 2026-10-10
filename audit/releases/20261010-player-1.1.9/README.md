# 1.1.9（271）本地正式签名 Full 包

交付 APK：[Yfuse-1.1.9-full-arm64.apk](../../../artifacts-local/releases/player-1.1.9-271/Yfuse-1.1.9-full-arm64.apk)。实际包名 `com.yfuse`，`arm64-v8a`，29,851,454 字节（约 28.5 MiB）。

SHA-256：`daed270d63e92ff87d919e72d6dbb22b57cde71b5630edb0040586353abac2c7`。

本次包含加载准备时保留关闭入口、缓冲时点击显示控制栏、左右各 40% 的长按快退／快进区域、长按取消与换集恢复、隐藏控制栏的点击优先级、仅手动订阅且开启提醒的剧集通知、未知播出时间显示“待公布”，以及追剧日历的独立圆角日期块。保留此前 1.1.8 的观影统计、完整后续排期、播放记录、收藏去重、扫光和缓冲优化。

## 版本及源码

- 上一实际交付 APK 经 `aapt2` 和 `apksigner` 核验为 `com.yfuse` / **1.1.8（270）**，SHA-256 `a4ab60b7f4406bfc80c30ec7de8954e772edda97df3507abcd8f9c26c98abed3`。
- 构建前已更新根目录 `version.properties`、`release-notes.txt` 首行和本次版本绑定的 MDK 确认记录。没有使用版本覆盖参数。
- 本次正式构建命令：`scripts/build-release-packages.ps1 -ConfirmMdkDistributionRights -AllowDirty`。脚本显式启用 `yfuseIncludeMdk=true`、`yfuseNativeOnlyRuntime=false`、`confirmMdkDistributionRights=true`、`kotlin.incremental=false`；命令环境关闭并行并限制 1 个 worker。构建成功，用时 **5 分 5 秒**。
- 源码为 `6a70a9fa47bd24a3997db1ce2eeb68d3a6ebaa1a` 上的未提交工作区。交付目录保存 `source.patch`、85 个改动源码文件的 `changed-source.zip` 和 `source-inputs.json`；最终检查确认这些源码在构建期间没有变化。构建差异标识见 `verification.json`。

## 实际交付副本校验

- 实际版本 **1.1.9（271）**，与项目配置、更新说明一致，且高于上一包。
- 正式 APK v2 签名通过，唯一签名者、4096 位 RSA；证书 SHA-256 `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一包一致，APK 无 debuggable 标记。
- 16 KiB 对齐检查通过。
- 最终 APK 包含非空 `libmdk.so` 和 `libyfuse-mdk-jni.so`，二者与上一包逐字节一致；MDK 主库与固定 SDK 原件一致。固定 MDK 压缩包 SHA-256 仍为 `87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba`，组件和 package-only 范围沿用用户持续授权。
- MPV、YCore 及共享依赖的 9 个原生库均与上一包逐字节一致。
- DEX：**14,082 个类、61,821 个含代码的方法，0 个问题、0 个无法分析的方法**。应用最大方法为 205 个寄存器，低于 256 上限。执行 DEX 校验的构建 APK、脚本分发 APK 和交付副本 SHA-256 一致。

## 回归证据

本次打包前播放器 1,000 项测试全部通过，手机和 TV 共用代码编译通过；圆角日期块的 12 项现有日历测试通过；此前订阅提醒修复的手机 4,755 项、TV 118 项测试通过。这些是各次修改对应的检查记录，不合计为本次重新执行的测试总数。最终核对上述 20 个相关源码文件 SHA-256 与各自验证记录一致，副本保存在交付目录 `regression-evidence/`。

APK、构建日志、R8 mapping、实际包信息、签名、对齐、DEX 输出、更新说明、MDK 确认和源码快照位于 `artifacts-local/releases/player-1.1.9-271`。完整校验元数据见 [verification.json](verification.json)，可用 `verify-package.py` 重跑检查。

本次仅本地签名打包；未进行新界面真机验收、安装、提交、推送、发布或上传。
