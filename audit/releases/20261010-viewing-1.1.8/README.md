# 1.1.8（270）本地正式签名 Full 包

交付 APK：[Yfuse-1.1.8-full-arm64.apk](../../../artifacts-local/releases/viewing-1.1.8-270/Yfuse-1.1.8-full-arm64.apk)。实际包名 `com.yfuse`，仅含 `arm64-v8a`，29,852,490 字节（约 28.5 MiB）。

SHA-256：`a4ab60b7f4406bfc80c30ec7de8954e772edda97df3507abcd8f9c26c98abed3`。

已从上一份实际交付 APK 核对基线为 1.1.7（269），其 SHA-256 为 `760311d02b6418c007d01c53d3464dec45da54f3f824f0936529e0a06dc6948a`。构建前已同步根目录 `version.properties`、`release-notes.txt` 和本次版本绑定的 MDK 确认记录；未使用版本覆盖参数。

本次包含本地观影统计、追更详情及所有已公布的后续排期、播放记录及时保存与刷新、收藏去重、图片加载扫光，以及此前的 1 GiB 自适应内存队列和 5 分钟磁盘预读调整。手机及 TV 源码均保留，标准打包脚本本次生成手机 APK。

正式构建命令：`scripts/build-release-packages.ps1 -ConfirmMdkDistributionRights -AllowDirty`。构建显式启用 `yfuseIncludeMdk=true`、`yfuseNativeOnlyRuntime=false`、`confirmMdkDistributionRights=true`、`kotlin.incremental=false`；通过命令环境关闭并行构建、限制一个 worker。构建成功，用时 7 分 47 秒。

源码基于 `6a70a9fa47bd24a3997db1ce2eeb68d3a6ebaa1a` 的未提交工作区，构建差异标识见 `verification.json`。交付目录保留 `source.patch`、66 个改动文件的 `changed-source.zip` 和 `source-inputs.json`，最终校验确认构建期间这些文件没有变化。

实际交付副本通过：

- 版本和包名核验：`com.yfuse` / 1.1.8 / 270，与配置及更新说明一致，高于上一包。
- 正式 APK v2 签名：唯一签名者，4096 位 RSA；证书 SHA-256 `373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一包一致。
- 16 KiB 对齐检查通过，APK 没有 debuggable 标记。
- `libmdk.so` 与固定 SDK 原件及上一包逐字节一致；`libyfuse-mdk-jni.so` 非空且与上一包一致。固定 MDK 压缩包 SHA-256 仍为 `87aa236840134fe3b5c3b08e813c2b4f0557d4c2fc7034679417bc598dd785ba`，组件和 package-only 范围沿用用户持续授权。
- MPV、YCore 及共享依赖的 9 个原生库均与上一包逐字节一致。
- DEX：14,086 个类、61,829 个含代码的方法，0 个发现、0 个无法分析的方法；应用最大方法 207 个寄存器，低于 256 上限。交付副本与执行该校验的构建 APK 哈希一致。

此前手机端 4,741 项、TV 端 118 项测试全部通过（总计 4,859，0 失败／错误／跳过）。打包前及最终校验再次核对其 47 个源码文件的 SHA-256 一致；详见 `audit/viewing-features-20261010`。交付目录保留 788 项锁定依赖的离线 SPDX 清单，本次未运行在线漏洞扫描。

APK、构建日志、R8 mapping、实际包信息、签名、对齐、DEX 输出、依赖清单和源码快照位于 `artifacts-local/releases/viewing-1.1.8-270`。完整元数据见 [verification.json](verification.json)，可用 `verify-package.py` 重跑校验。

本次仅本地签名打包，未安装或进行新界面真机验收，未提交、推送、发布或上传。
