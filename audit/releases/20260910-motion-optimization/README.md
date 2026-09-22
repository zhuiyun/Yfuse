# Yfuse 1.0.51 (213) 正式签名包

- 安装包：[Yfuse-1.0.51-signed-arm64.apk](Yfuse-1.0.51-signed-arm64.apk)
- 包名：`com.yfuse`，ARM64 完整版，非 debuggable。
- 大小：27,999,510 字节（28.00 MB）。
- SHA-256：`ee51ceffa3015551699dd98f16e2ceefb12c847179966366d2b3108bc82e049a`。
- 正式证书、APK v2 签名、16 KB ZIP 对齐与 ZIP CRC 校验通过。
- 20 个预期 ARM64 原生库齐全，8 个 YCore 库与当前 AAR 逐字节一致。
- 1,273 个源码与构建文件在打包前后保持一致。

包含加载光球/详情顶栏绘制阶段读取、ASS 帧提交隔离、主题与强调色订阅缩小、剧集和进度标记缓存、固定轨道进度绘制、库首页滚动可见性优化。

打包前完成 Android/TV、协议和服务端本地回归。验证详情见 [优化记录](../../motion-optimization-20260910/RESULTS.md)。本次另通过 release 构建与设计系统检查；没有连接手机、设备测试或上传发布。

构建使用版本参数覆盖，没有修改 version.properties。证据：`build.log`、`signature.txt`、`badging.txt`、`alignment.txt`、`source-hashes.json`、`verification.json`。
