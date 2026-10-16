# Yfuse 1.3.6（288）本地签名交付

- 安装包：`artifacts-local/releases/cloud-playback-1.3.6-288/Yfuse-1.3.6-full-arm64.apk`。
- 包名：`com.yfuse`；手机 full ARM64 完整版，包含固定 MDK。
- 大小：29,979,219 字节（29.98 MB）。
- SHA-256：`5ee1f745725811968a5ee668ad158b2169925d75de06fc0f144fd5bbeface151`。
- 正式证书 SHA-256：`373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一实际交付包一致。
- 实际 APK 版本由 1.3.5（287）递增至 1.3.6（288），与 `version.properties`、更新说明首行及本次 MDK 确认记录一致。

本次拉取后，云端 master 与原本地提交一致；用户明确选择合入 `origin/fix/playback-stalls-20261015`，已快进至 `98366c4db071fafc297f4eccc640c5b157979cdd`，没有冲突。包含预取缓存复用、播放停滞恢复、缺失媒体信息时的限时探测，以及恢复输出判断的修复。未推送、上传、发布或部署服务端。

本次重新运行 `:phoneShared:testAndroidHostTest`：865 个测试集、4,899 项测试全部通过，零失败、错误和跳过。随后使用 `scripts/build-release-packages.ps1 -AllowDirty -ConfirmMdkDistributionRights` 构建；显式启用 `yfuseIncludeMdk=true`、`yfuseNativeOnlyRuntime=false`，关闭 Kotlin 增量编译。完整 Release 构建和关键 lint 通过；DEX 检查覆盖 14,232 个类、62,634 个含代码方法，零问题、零无法分析的方法。应用自身最大方法使用 211 个寄存器，低于检查上限 256。

最终 APK 的正式 v2 签名、单一签名者、16 KB ZIP 对齐、ARM64 ABI、MDK 运行库及 JNI、大小和 SHA-256 校验全部通过。固定 MDK SDK 和三个原生 AAR 的校验和与上一包一致。原包、分发副本及交付副本的 SHA-256 和大小一致；构建前保存的输入在构建后重新核验。

构建包含未提交的版本、更新说明和 MDK 确认记录，差异标识为 `8249e34e7f98`。交付目录保留 `source-inputs.json`、`build-metadata.patch`、`build-inputs/`、源码哈希、R8 mapping、完整测试 XML、构建日志、签名和产物检查结果；`pre-pull/` 保留本次操作前的三份版本相关文件。

本次没有进行真机安装或实际网络播放验证。构建沿用项目未配置更新清单公钥的设置，仍接受更新主机的未签名更新清单；APK 本身已通过正式签名校验。依赖锁文件和固定原生组件未变化，因此复用 1.3.5 交付中的 SPDX 依赖锁快照与许可证通知；没有重新执行在线漏洞扫描，该快照不表示其中所有服务端依赖都装入 APK。

详细结果见同目录 `verification.json`；完整交付证据位于上述安装包所在目录。
