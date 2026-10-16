# Yfuse 1.3.5 (287) 本地签名交付

- 安装包：`artifacts-local/releases/review-fixes-1.3.5-287/Yfuse-1.3.5-full-arm64.apk`。
- 包名：`com.yfuse`；手机 full ARM64 完整版，包含固定 MDK。
- 大小：29973483 字节（29.97 MB）。
- SHA-256：`c66b741570a2d5aa2d913eb0803d61fffafd7866ab05b28288891a55e21fcf55`。
- 正式证书 SHA-256：`373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一实际交付包一致。
- 已读取实际 APK 元数据，确认从 1.3.4（286）递增至 1.3.5（287），与版本配置及更新说明一致。
- APK v2 正式签名、16 KB ZIP 对齐和 MDK 两个运行库校验通过；DEX 14233 个类、62639 个含代码方法，0 项问题、0 个无法分析的方法。

源码基于 `d934b0523d8d1c8896eca297bff3d283d7bcc2dd`；打包前仅修改版本配置、更新说明和 MDK 确认记录，差异标识为 `963b921a2d46`。交付目录中的 `source-inputs.json`、`build-metadata.patch` 和 `build-inputs/` 保留这些输入，构建后已重新核验。标准脚本使用 `-AllowDirty -ConfirmMdkDistributionRights`，显式启用 MDK 并关闭 native-only，未推送或发布。

此前手机共享模块 4889 项、TV 共享模块 118 项、最终服务端 279 项测试通过；本次复核对应 48 份源码哈希一致后沿用这些结果，未重新运行完整单元测试。此次执行了完整 Release 构建、关键 lint 和产物校验，未进行最终安装包的真机测试。

配套服务端修复需要单独部署服务端；本次未部署。该本地构建沿用未配置更新清单公钥的设置，构建日志对此有提示；APK 本身已通过正式签名验证。依赖 SPDX 文件为离线的仓库依赖锁快照，不表示本次重新进行了漏洞扫描，也不表示其中所有服务端依赖均装入 APK。

详细验证见 `verification.json`。完整构建日志、APK 元数据、签名、对齐、DEX 结果、MDK/原生组件校验和及许可证通知保存在交付目录。
