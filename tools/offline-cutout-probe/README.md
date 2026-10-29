# Android 离线双模型优化验证 0.0.2

独立测试包 com.yfuse.offlineprobe.v2，可与 0.0.1 并存。只保留 ONNX Runtime，不包含 MediaPipe、MODNet、U2NetP。模型及六张原始海报来自上一轮已验签并校验哈希的固定 APK，构建机提取后内置，无 INTERNET 权限。

本轮比较 PP 通用人物版、BiRef FP32 原始分配、同权重关闭 CPU arena/memory pattern、BiRef 仅常量 MatMul/Gemm 权重 INT8 且关闭上述内存复用。INT8 不等于全模型 INT8，卷积和多数激活仍为 FP32。

每个配置一个新进程；每张图预热一次再测三次。时间包含预处理、张量分配、CPU 推理和输出复制，不含初始化与存图。VmHWM 为测试进程最高 RSS，不等同模型权重或手机内存。原生 Android 11 x86 KVM 测试不能冒充 ARM64 真机。

逐张比较 Android 原始输出：相对同轮 FP32 基线的 alpha MAE<=0.01，0.5 阈值蒙版 IoU>=0.98，六张均完整输出；这只是输出一致性，不是标注准确率。自动候选还要求峰值内存<=原始90%、处理耗时<=原始2倍；INT8 候选权重<=原始75%。未通过者不作为默认候选交付。效果本身的漏分/片名问题仍须检查，不因数值接近自动判为可用素材。

最终 ARM64 包只内置 PP 通用人物版与本轮选中的 BiRef 权重/内存配置。最终 APK 的包名、版本、签名、DEX、无网络权限、模型哈希与无多余模型均单独检查。每次独立诊断工具出包版本递增，此包不是 Yfuse 正式播放器。

选择交付模型前，PP 与 BiRef FP32 基线必须各自具有与当前模型配置匹配的六张完整结果；缺图、重复样本、推理失败或权重不一致均拒绝选择。原图、蒙版、前景和报告保存在各模型自己的目录中，重新测试一个模型不会覆盖其他模型的原图证据。

`offline-cutout-probe.yml`、`offline-cutout-native.yml` 和 `offline-cutout-selfie.yml` 用于重放历史六模型/Selfie 实验，固定检出各自匹配的历史提交。当前双模型流程使用 `offline-cutout-optimize.yml` 和 `offline-cutout-package.yml`。
