# Yfuse 1.0.52 (214) 正式签名包

- 安装包：[Yfuse-1.0.52-signed-arm64.apk](Yfuse-1.0.52-signed-arm64.apk)
- 包名：`com.yfuse`，ARM64 完整版，非 debuggable。
- 大小：28,092,992 字节（28.09 MB）。
- SHA-256：`2feffccd6972ac3bbc071bcd470053a7ad0e5cf14719855392560ca01233d794`。
- 正式证书、APK v2 签名、16 KB ZIP 对齐与 ZIP CRC 校验通过。
- 20 个预期 ARM64 原生库齐全，8 个 YCore 库与当前 AAR 逐字节一致。
- 1,420 个源码与构建文件在打包前后保持一致。

包含播放控制栏电量与充电标记、主题消费点颜色过渡、播放运行区实时状态隔离、ASS 绘制隔离，以及上一安装包之后的动效收尾和下一集预加载／片尾适配改动。

打包前本地手机与 TV 单元测试合计 2548 项通过。验证详情见 [优化记录](../../render-isolation-20260910/RESULTS.md)。本次另通过 release 构建与设计系统检查；没有连接手机、设备测试或上传发布。

构建使用版本参数覆盖，没有修改 version.properties。证据：`build.log`、`signature.txt`、`badging.txt`、`alignment.txt`、`source-hashes.json`、`verification.json`。
