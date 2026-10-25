# Android 离线海报抠图对照

独立诊断 App `com.yfuse.offlineprobe`，不是 Yfuse 正式版。模型、图片随 APK 提供，Manifest 不声明 INTERNET 权限。构建机联网下载模型，手机端不下载模型、不调用云 API、不依赖 Google Play 服务。

依次测试 MODNet、PP-HumanSeg V2 Lite 人像/通用人物版、Google MediaPipe Selfie Segmentation TFLite 权重、BiRefNet Lite 512 FP32，以及现有 U2NetP 基线。

## 测试方法

- 两张历史失败海报：《鲸口脱险》《野兽之心》。另外使用《星际穿越》《蜘蛛侠：平行宇宙》《海洋奇缘》《沙丘》；来源与原始字节 SHA-256 写入 model-manifest.json。
- 使用原始模型输出；不手工修图，不叠加其他模型，不使用生成式图像。原始浮点蒙版保留为小端 f32；展示图执行模型规定的 sigmoid、min-max 或概率转换，并单独记录裁剪值。
- 每个模型在单独的 Android 进程中运行；每张图预热一次、测量三次。耗时包括缩放、归一化、张量分配、CPU 推理和输出复制；不含模型加载与文件写入。报告分别记录初始化耗时。
- ONNX 模型采用两个 CPU 推理线程；Google Selfie 采用 MediaPipe CPU 默认线程配置。内核 `/proc/self/status` 的 VmHWM 是该测试进程的最高常驻内存；PSS 为采样值。二者均不是模型权重文件大小。
- Google Selfie 模型包含 `Convolution2DTransposeBias` 自定义算子，因此使用本地 MediaPipe Tasks CPU 运行时及其官方预处理，不能直接用普通 TFLite Interpreter 代替。运行时不依赖 Play 服务下载模型。
- MODNet 使用模型提供者发布的动态 ONNX 图，在本轮统一固定 512×512 输入；这是移动端有限开销的测试配置，不代表官方保持长宽比或更高分辨率配置的最佳表现。
- PP 模型由官方 Paddle softmax 推理模型转为 ONNX，先在构建机与 Paddle 原输出做误差校验。这个转换检查不算 Android 性能测试。
- PP 通用人物版采用 256×256 输入：192×192 下出现的非整除自适应池化转换误差，不能当成原模型效果。256×256 配置与原始动态 Paddle 模型在同尺寸下对照，随机输入与六张海报均要求 allclose(atol=1e-4, rtol=1e-4)，保留每项数值和轮廓一致率。人像版保持官方 144×256 输入。
- 飞行模式、关闭 Wi-Fi/移动数据，并禁用模拟器 Google Play 服务后测试。APK 中无 INTERNET 权限作为额外保证。
- 自动结果状态只说明模型是否产生输出；前景比例不作为质量准确率。复杂海报的肢体、动物、文字残留、发丝等必须视觉检查。
- 本轮使用 Android 11 / API 30 的原生 x86 KVM 模拟器，执行包含六组权重的 x86 APK。Google MediaPipe 官方 AAR 包含原生 x86 运行库。另提供同权重 ARM64 APK；原生 x86 的时间和内存不能冒充 ARM64 真机结果。每种模型的总执行上限为 240 秒，超时保留已经产生的真实输出。ARM64 转译测试另在独立工作流记录。

## 复现

由 `.github/workflows/offline-cutout-native.yml` 构建和测试。安装 ARM64 APK 后开启飞行模式，运行内置图片或选择本地图片，并将报告 ZIP 导出到本地。

模型来源与许可：MODNet（ZHKKKe/MODNet，Apache-2.0，Xenova 转换版）；PP-HumanSeg（百度 PaddleSeg，Apache-2.0）；Google MediaPipe Selfie 模型（Google AI Edge 官方模型发布）；BiRefNet Lite（ZhengPeng7/BiRefNet，Studio Ludens 转换版，MIT）；U2NetP（xuebinqin/U-2-Net，Apache-2.0，经 rembg 发布）。具体下载 URL、版本及哈希随报告保留。
