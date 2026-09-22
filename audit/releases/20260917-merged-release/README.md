# Yfuse 1.0.68（230）正式签名包

- APK：Yfuse-1.0.68-230-full-arm64-signed.apk
- 包名：com.yfuse；手机／平板 Full ARM64，包含 YCore、MPV 与 MDK。
- 大小：29,446,037 字节。
- SHA256：`7a17ca19cd315a7ad18ccdd91f57b5c8cbb6a67b4ac07e84d128fb725ef6836d`。
- 正式证书 SHA256：`373e36d363965b6c1ae0a68c3db9537831d137ea6c38f094608bf243e7be3e84`，与上一实际交付包一致。

## 本次改动

合并云端 Anime4K、Jellyfin 12 兼容、播放探测与预缓存、跨服务器会话、播放记录同步修复，并精简搜索筛选入口。完整更新记录见 release-notes.txt。

## 验证

- 读取上一份 APK，确认从 1.0.67（229）递增为 1.0.68（230），项目版本配置与更新说明一致。
- 正式 Release 构建及内置签名、原生运行库、MDK 权利声明、GPU 和设计系统校验通过，未排除校验任务。
- 本次手机端 2,653 项 JVM 测试全部通过。
- 实际 APK 的生产签名、版本、应用入口、ARM64 库集合、ZIP CRC 与 ZIP／ELF 16 KB 对齐验证通过。
- 播放器原生库与上一份交付包一致；构建前后 1728 份源码和构建输入哈希一致。
- compileSdk 37，targetSdk 36，minSdk 26。
- 未执行安装或真机播放验证，未发布 APK。

机器验证记录见 verification.json、signature.txt、badging.txt、manifest.txt、alignment.txt 和 SHA256.txt。
