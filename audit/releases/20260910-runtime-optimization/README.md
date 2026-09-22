# Yfuse 1.0.50 (212) 正式签名包

- 安装包：[Yfuse-1.0.50-signed-arm64.apk](Yfuse-1.0.50-signed-arm64.apk)
- 包名：`com.yfuse`，ARM64 完整版，非 debuggable。
- 大小：27,993,370 字节（27.99 MB）。
- SHA-256：`a8a61accfdb52c81213e2cf88b82e8070b7bbf39aa4f948fc5252bd372a94c24`。
- 沿用项目正式证书，APK v2 签名、16 KB ZIP 对齐、ZIP CRC 校验通过。
- 20 个预期 ARM64 原生库齐全，8 个 YCore 原生库与当前 AAR 逐字节一致。
- 1,271 个源码和构建文件的哈希在打包前后保持一致。

包含本次运行时优化、选集与弹幕图标更新以及当前工作区的 Android 修改。构建使用版本参数覆盖；没有修改会触发自动发布的 version.properties。

打包前的本地回归共 2,681 项通过，图标修改后的 Android/TV 编译通过，详见 [优化记录](../../runtime-optimization-20260910/RESULTS.md)。本次另通过 release 构建及设计系统检查。没有连接手机、运行设备测试或上传发布。

证据：`build.log`、`signature.txt`、`badging.txt`、`alignment.txt`、`source-hashes.json`、`verification.json`。
