# 氛围光检查与修复

检查范围：逐帧取色、黑边绘制、颜色过渡、四种播放器 Surface 接入、开关偏好及控制栏着色。

## 已修复

1. **取消后共用位图的竞争**：原实现把单个 Bitmap 用于所有 PixelCopy 请求。暂停、缓冲或跳转导致协程取消时，已提交的复制仍持有该位图，新请求可能同时写入。现在每次复制独立拥有位图，在回调完成后释放；取消的请求不读取结果。回归测试模拟旧请求取消后，新请求先完成、旧回调后到达的顺序。
2. **暂停后画面重建仍保留旧取色**：原暂停采样成功后退出，Surface 重建、尺寸变化及切换内容不在任务重启条件中。现在监听 Surface 和布局变化，用修订号丢弃过期结果；内容键包含引擎、服务器、媒体和版本，变化时重新取色。
3. **后台及隐藏页面继续采样**：新增 Lifecycle STARTED 和页面可见性条件；不可见时取消任务、清空实时颜色，恢复时重新采样。
4. **取色坐标语义不明确**：原来靠矩形大小猜测是否裁剪。现在 MPV/MDK 明确声明 Surface 内有黑边，Core2/Media3 使用完整画面 Surface；裁剪坐标按 Surface 缓冲区尺寸换算，并排除无效及越界区域。

另外移除了“单次复制远低于一毫秒”的未测量性能断言。

## 验证范围

新增 AmbientCopyTest：取消后资源生命周期、重叠请求、同步拒绝、失败回调、完整画面 Surface 不裁剪、缓冲区坐标转换、填充模式越界和空区域，共 8 项。

打包同时执行 AmbientLightTest、PlaybackPreferencesTest、Core2SurfaceTest，以及上次搜索/导航动画相关回归测试。实际结果、总数、签名、文件哈希及原生库核对记录见同目录 verification.json。

本次为代码检查和 Release 单元测试；没有进行真机氛围光视觉、HDR 输出或性能测量。

## 构建范围

版本 1.0.49（211），ARM64 Full，使用原有正式签名配置。移动端 Kotlin 使用 source/composeApp/src 的固定源码快照；TV UI 延续前几个移动端签名包的 805af51c 基线。版本号通过 Gradle 参数传入，未修改工作区 version.properties。

## API 依据

- [Android PixelCopy](https://developer.android.google.cn/reference/android/view/PixelCopy)：异步完成回调、来源矩形按 Surface 边界裁剪，目标位图不可在复制完成前销毁。
- [Kotlin suspendCancellableCoroutine](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/suspend-cancellable-coroutine.html)：协程取消和外部回调资源释放需要分别处理。
