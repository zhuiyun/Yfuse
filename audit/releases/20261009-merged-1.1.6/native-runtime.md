# Android 原生运行库复用记录

本次 1.1.6 使用已验证的 1.1.5 正式签名 APK 中的原生二进制，经项目打包器重新生成 AAR；未重新编译原生库，未修改库的字节。完整指纹见 [native-runtime.json](native-runtime.json)。

- 来源：[CI run 37599783432](https://github.com/zhuiyun/Yfuse/actions/runs/37599783432)，提交 `335530f494ec30a22e66d21ac70d6a1e5750b861`，构建任务 `112721126716`。原 APK 为 `com.yfuse`、1.1.5（267），已验证正式证书和 v2 签名。
- 两个 YCore 库的 build ID 与该 CI 的实际编译日志一致；当前 40 个原生源码及构建输入与该提交一致，仅忽略换行格式。六个共享依赖与仓库允许的固定 MPV 载体逐字节一致。
- 使用 `scripts/package-ycore-native-aar.py` 生成依赖闭合的原生 AAR 和独立 GPU AAR。当前 `scripts/verify-ycore-native.sh` 通过：JNI/媒体符号、依赖闭合、`.gnu_debugdata`、所有八个库的 16 KiB 对齐及纯原生载体约束均符合要求。安装文件与已验证产物哈希一致。
- 来源清单明确标为 `recovered-from-verified-apk`。原 CI 的 AAR/sidecar 未保留，现有清单是根据已验证二进制、CI 日志和同提交构建规则生成的恢复记录，不冒充原始 CI 清单。
- APK 内的 `.gnu_debugdata` 被完整保留；完整未剥离调试符号无法从 APK 恢复。本次没有新增真实媒体解码或设备播放验证。
- `ycore-native/src/ycore.cpp` 属于 portable ABI，本轮该目录的修复未参与 Android AAR，不能描述为已进入此 Android 包。

已安装至 `composeApp/libs/`；旧六个 AAR/校验/清单文件备份在受忽略的 `artifacts-local/native-runtime-1.1.5-267/previous-installed/`。本记录只保留仓库相对路径和公开指纹，不包含凭据或本机用户名路径。
