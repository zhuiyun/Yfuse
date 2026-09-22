# PixelCopy HDR 色调映射：版本证据与门控复核

获取时间：**2026-09-15 04:22:22–04:22:23 UTC**（北京时间 12:22:22–12:22:23）。

方式：Python 标准库 `urllib.request` 只读获取 Android 官方 Gitiles 的固定发布标签，使用 `?format=TEXT`，Base64 解码后核对源码与计算 SHA-256。未保存完整源码，未访问播放内容，未运行 Gradle。

## 结论

**Android 14 / API 34 是此次核查中，能够由首个正式发布标签明确证明包含 Surface PixelCopy HDR 色调映射调用的系统版本。** Android 12、12L、Android 13 首版及抽查的 Android 13 后期标签中，`Readback.cpp` 均没有 `tonemapPaint` 调用。因此，针对已知 HDR 输出，API 34 可作为尝试动态取色的保守版本边界；旧版本继续原有策略。

这证明 AOSP 存在相应转换路径，不等于所有厂商的 Dolby Vision、Tunnel 或受保护 Surface 都可复制。保留 DRM、功耗/减弱动态效果限制及 PixelCopy 失败后的静态回退仍有必要；也不据此断言所有旧版本和厂商回移版本完全没有 HDR 转换能力。

## 固定发布标签对照

| 发布标签 / 官方文件 | 行数 | `tonemapPaint` 调用 |
| --- | ---: | --- |
| [android-12.0.0_r1 / Readback.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/libs/hwui/Readback.cpp) | 384 | 无 |
| [android-12.1.0_r1 / Readback.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.1.0_r1/libs/hwui/Readback.cpp) | 384 | 无 |
| [android-13.0.0_r1 / Readback.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r1/libs/hwui/Readback.cpp) | 371 | 无 |
| [android-13.0.0_r75 / Readback.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-13.0.0_r75/libs/hwui/Readback.cpp) | 396 | 无 |
| [android-14.0.0_r1 / Readback.cpp](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/libs/hwui/Readback.cpp#246) | 395 | 有，246–249 行 |

Android 14 `Readback.cpp` 的关键行为：

- 98–109 行读取 Surface dataspace；为零时改读 `AHardwareBuffer_getDataSpace`，再构造源色彩空间。
- 246–249 行在复制绘制之前调用色调映射，目标信息来自目标画布。

短片段（246–247 行）：

```cpp
static constexpr float kMaxLuminanceNits = 4000.f;
tonemapPaint(image->imageInfo(), canvas->imageInfo(), kMaxLuminanceNits, paint);
```

## HDR 条件与 PixelCopy 调用链

以下均固定为 `android-14.0.0_r1`，没有以持续变化的 `main` 分支代替发布依据。

1. [Tonemapper.cpp，71–79、87–108 行](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/libs/hwui/Tonemapper.cpp#71)：HDR 检测包含 ST2084/PQ 与 HLG。源和目标的 transfer 不同、且任一方属于 HDR 时，创建并附加颜色映射滤镜。
2. [PixelCopy.java，133–137、190–195 行](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/graphics/java/android/view/PixelCopy.java#133)：项目使用的带 `srcRect` 的 SurfaceView 重载先获取 Surface，再调用 `HardwareRenderer.copySurfaceInto`。
3. [HardwareRenderer.java，1130–1133 行](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/graphics/java/android/graphics/HardwareRenderer.java#1130) 与 [JNI，684–694 行](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/libs/hwui/jni/android_graphics_HardwareRenderer.cpp#684)：复制请求经 native 方法传入 `RenderProxy`。
4. [RenderProxy.cpp，419–424 行](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/libs/hwui/renderthread/RenderProxy.cpp#419)：在 RenderThread 上调用 `thread.readback().copySurfaceInto`，到达前述色调映射位置。

## SHA-256 校验

哈希针对 `?format=TEXT` 响应 Base64 解码后的完整原始字节，未经换行转换。前五项和其余三项分别在 04:22:22、04:22:23 UTC 获取。

| 文件 / 标签 | SHA-256 |
| --- | --- |
| Readback.cpp / android-12.0.0_r1 | `973798e4e37d538ac246009cc8ccb6dee13d55cd3d1438cc213960dfba535b92` |
| Readback.cpp / android-12.1.0_r1 | `973798e4e37d538ac246009cc8ccb6dee13d55cd3d1438cc213960dfba535b92` |
| Readback.cpp / android-13.0.0_r1 | `a4e4ec00b9ae0b78bb29eef126f67f4568a06835fd215c4a6ecd95eb6dfbc547` |
| Readback.cpp / android-13.0.0_r75 | `93f476a5310cd81d03337bf68835d56b8ebf5c5e150e2d4a3759e0cae53122c0` |
| Readback.cpp / android-14.0.0_r1 | `9dab7bd0d9243d98375d32c7effeed035879e4f4613a54e3999e7f7363a3e7e9` |
| Tonemapper.cpp / android-14.0.0_r1 | `b5b3ccfb0f3985fb0c93f72812647b517c10006769c94a48cc92cdbd05354def` |
| PixelCopy.java / android-14.0.0_r1 | `3851b5f9a65d13210793bd4c97666de07f514494e9badd3481ccf6d5f68220b7` |
| RenderProxy.cpp / android-14.0.0_r1 | `55d1b54eed3c59a2f6ba9762e95acee83e36de535cb75e8e49a49e5b4f0a4aee` |

## 本地门控只读复核

复核对象：[PlayerAmbientBinding.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerAmbientBinding.kt)、[AmbientSamplingPolicy.kt](D:/Demo/Yfuse/composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AmbientSamplingPolicy.kt)，并读取相邻采样调用与现有测试确认接线。

**未发现本次门控改动的明显回归：**

- `ambientOutputSupportsLiveSampling` 仅在 API ≥ 34 放开已知 DV/HDR10 模式；API ≤ 33 对这两类输出仍返回 false。Unknown 与内核已映射到 SDR 的模式保留旧行为。
- `ambientLive` 仍要求开关开启、页面可见、非画中画、非省电/压力限制、未开启减弱动态效果、非 DRM、视频高度有效且输出已确认 Rendering。HDR 放宽没有绕过这些条件。
- 不再以布局黑边作为“能否采样”的前提；无明显黑边且控制层隐藏时按 2 秒下限探测。采样得到内嵌黑边后，`ambientNeeded` 和绘制恢复，因此没有依赖“先开灯才看得到黑边”的闭环。
- 可见黑边判断把画面裁剪偏移与采样内嵌边距一起计算；完全落在裁剪视口外的黑边不会要求快速采样。
- `waitMs` 使用自适应间隔与探测下限的较大值，正常循环保留失败退避。重启采样也保留上次请求时间；最低请求间隔没有因新参数被绕过。暂停仍由采样器在一次成功读取后停止。
- 不可读输出仍通过现有错误计数与静态回退处理；本次检查没有证明具体设备的 HDR 色彩或 PixelCopy 成功率。

已有针对测试覆盖 API 33/34 边界、内嵌黑边、裁剪黑边、慢速探测及失败退避。本代理未执行这些测试；实际 Gradle 结果由主代理记录。
