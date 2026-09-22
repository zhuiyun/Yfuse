# 氛围光功耗检查

日期：2026-09-10。检查当前工作区源码；未修改应用代码。

结论：代码已有后台停采、暂停成功后停采、小缩略图、颜色差异过滤和无模糊绘制等措施。但仍存在无可见用途时采样、动画持续逐帧更新、控制栏逐帧重组及重试不充分退避等优化空间。下面的优先级是根据执行路径判断的优化顺序，不是功耗实测排名。

ADB 在普通和宿主权限下均未发现设备。本次没有真机 CPU/GPU、帧时间、电流或能耗数据，不能报告每小时耗电、额外毫瓦或整机节电百分比。

## 已有措施

- AmbientFrameSampler.Collect 受页面可见性及 Lifecycle STARTED 控制；退出前台后取消实时采样。
- PlayerRoot 对关闭开关、减少动态效果、DRM、画中画和未渲染状态禁用实时采样。
- 正常暂停且位置不变时，成功采样一次即停止循环；失败时仍会重试。因此源码中“暂停成本为零”只适用于停止采样，不包含静态发光的屏幕功耗。
- PixelCopy 目标为 32×18，图像均值只处理 576 个像素及边缘桶；每次请求独占目标位图，完成回调后释放，取消不会提前回收。
- 相近颜色不发布新目标；绘制主要在 drawBehind 中，不使用模糊或显式离屏层。

## 优先优化

### 1. 没有黑边且控制栏不可见时停止采样和动画

位置：PlayerRoot.kt:2478、2569；AmbientFrameSampler.kt:158；PlayerControls.kt:869、898。

ambientLive 没有使用实际黑边面积或控制栏可见性。Fill、Stretch 或画面恰好铺满时，drawAmbientLight 的四个黑边分支都不绘制；控制栏隐藏后也没有颜色消费者。但采样和 rememberAmbientLight 的动画仍可运行。

建议把控制栏可见性向上反馈，用“存在可见黑边 OR 有可见控制栏”作为需求条件，并覆盖首次布局、缩放和旋转。没有消费者时跳过 PixelCopy 和插值；重新有消费者时立即取一次样。这个分支可以消除对应的实时取色任务，但不代表整个播放器功耗下降相同比例。

### 2. 控制栏只因颜色变化就重组，并逐帧重设第二层动画

位置：PlayerChromeRefined.kt:267；AmbientLight.kt:147；DominantColor.kt:157。

RefinedBottomBar 在组合阶段读取 ambientLight.value，再调用 ambientLightAccent/harmonizeArtworkAccent，后者最多执行五轮亮度校正。结果又成为 animateColorAsState 的目标。第一层氛围光每帧变化时，会反复使底部控制栏的组合范围失效，并重新调整第二层动画的目标；这里只涉及控制栏显示期间，不能描述为整个应用每帧全部重组。

建议在收到新的采样目标时计算一次最终强调色，然后只插值最终颜色；向进度条传 State 或读取 lambda，将颜色读取下移到绘制阶段。避免先对原始颜色插值、每帧调色，再启动另一层追赶动画。仅用 remember 或 derivedStateOf 包住不断变化的颜色不能消除这些更新。

依据：[Compose 延迟状态读取](https://developer.android.com/develop/ui/compose/performance/bestpractices#defer-reads)。

### 3. 区分采样频率与动画更新频率，增加自适应节流

位置：AmbientLight.kt:70、73、176；AmbientFrameSampler.kt:134、159、174。

稳定运行的采样循环是“复制耗时 + 250 ms 延迟”，名义上接近但不超过每秒四次。假设复制耗时可忽略、循环不重启，一小时约 14,400 次。600 ms 过渡长于采样间隔，动态片段中若每次颜色变化都超过阈值，过渡会被持续重新设定目标，UI 动画可能持续按帧时钟更新；并非每秒仅绘制四次。相同颜色会停止重设目标，因此静态片段不是必然持续动画。

每次插值计算 26 个边缘颜色及一个均色；按 60 次动画更新/秒估算是 1,620 次颜色插值/秒，120 次则为 3,240 次。这些是按源码计算的操作次数，不是设备实测。显示刷新率也不等于实际每帧都会产生新颜色或完整 GPU 绘制。

建议先尝试普通模式 500 ms 采样，连续稳定后退到 1–2 秒，出现变化后恢复；减少采样次数并不自动降低动画频率。柔和渐变可试验 24/30 次每秒发布可视颜色，达到目标或无消费者时停止时钟。在确实保持连续更新的情况下，120→30 可以减少 75% 的动画更新机会，不能声称减少 75% 整机功耗。需要真机观察步进、延迟和场景切换效果。

暂停拖动、播放状态和 Surface 变化会重启 LaunchedEffect，并立即取样，所以 250 ms 不是跨重启请求的全局上限。建议使用统一的单调时钟限速，合并快速拖动的中间位置，只保留最终位置，并允许结束拖动立即刷新。

### 4. 失败逐步退避，普通暂停/恢复避免无意义清空

位置：AmbientFrameSampler.kt:159–174；AmbientCopy.kt:9。

三次失败后固定每两秒重试，对长期不支持取色的输出仍可能每小时唤醒约 1,800 次。sample 返回 null 也可能发生在提交 PixelCopy 之前，因此不能把每一次重试都计算为 GPU 复制。当前请求回调把错误码压成 Boolean，不利于区分处理。

建议保留错误码，对无数据/超时短暂重试，对反复失败使用 2、5、10、30 秒退避；切源或 Surface 重建时恢复快速探测。ERROR_SOURCE_INVALID 既可能来自保护内容，也可能来自表面销毁，不应无条件永久禁用。

每次 Effect 重启都会 publish(null)，会把当前颜色切成海报回退，再恢复实时颜色。建议切源、开关关闭和失去有效输出时清空；同一有效 Surface 上普通暂停/继续、拖动合并期间保留最后颜色，避免不必要的双向过渡。

依据：[PixelCopy 状态及异步回调](https://developer.android.com/reference/android/view/PixelCopy)。

### 5. 缓存不变绘制对象，然后再考虑资源池

位置：AmbientLight.kt:102、124、200；AmbientFrameSampler.kt:116、124。

lerpAmbientLight 每次生成一个 AmbientLight 和四个 List；ambientLightDiffers 通过列表相加与 zip 构造临时集合。黑色场景的 isDark 也会拼接列表。drawAmbientLight 每次绘制重新构造渐变对象，典型上下或左右黑边需要四次矩形绘制。

建议差异比较直接逐桶提前返回；用 drawWithCache 缓存只依赖几何的裁剪范围和黑色衰减 Brush。动态颜色在 onDraw 中读取，避免把它读进缓存构建块而导致缓存每帧失效。变色渐变不能仅靠套一层 drawWithCache 就永久缓存。

每次成功取样，Bitmap 像素区约 2,304 字节，IntArray 数据区约 2,304 字节，合计 4.5 KiB；四次/秒约 18 KiB/s，未计对象和颜色列表。这个体量小，资源池应低于前四项。若引入有界池，必须等 PixelCopy 完成回调后归还位图；被取消但未回调的请求仍独占资源，不能恢复旧版无保护共享位图。

依据：[Compose 绘制缓存](https://developer.android.com/develop/ui/compose/graphics/draw/modifiers#drawwithcache)。

### 6. 省电降级和海报按需取色

位置：AmbientLight.kt:79；PlayerRoot.kt:2478、2492；DominantColor.android.kt:35、69、77。

没有接入系统省电或热状态降级；海报取色也在开关关闭时调用，缓存未命中可能发生图片获取及 256×256 解码、Palette 计算。已有共享缓存，不能把这部分描述为持续网络请求。建议关闭氛围光时不给海报提色入口传入 URL，其他确有需要的控制栏颜色消费者照常使用缓存。

OLED 上把原来的黑边点亮可能增加屏幕发光功耗，这是硬件机制的推断，并未测量本应用的增加量。静态海报模式减少采样/动画，仍保留这部分发光开销；真正的省电模式需要同时降低亮度或保持黑边。0.42 是 HSL 明度上限，并不是屏幕亮度或电功率上限。

建议提供动态、静态、关闭模式；省电或过热时降低更新频率并变暗，必要时关闭，具体视觉取舍需实测。

依据：[Material 深色主题与 OLED 功耗](https://github.com/material-components/material-components-android/blob/master/docs/theming/Dark.md)。

## 真机验证方案

对同一 Release 构建比较关闭、静态颜色、当前动态以及优化后的动态。固定设备亮度、刷新率、音量、播放引擎、素材和起播位置，优先用本地素材避免网络差异；预热后交错重复各组，不用单次电量百分比判断收益。

分别覆盖有/无黑边、控制栏显示/隐藏、暂停、拖动、后台、稳定和快速变色片段，以及可获得的 HDR/受限输出。记录实际采样次数和失败原因、PixelCopy 延迟、在途请求峰值、可视颜色更新数、底栏重组次数、CPU/RenderThread 时间、GC、帧时间及整机能耗。ODPM 是否可用取决于设备，不能将 app 帧率或 CPU 占用直接换算成电量。

验收重点：无黑边且控制栏隐藏、后台应无持续实时采样；正常暂停应在成功读一次后停止；拖动请求应受统一上限约束；控制栏颜色不再导致逐帧重组；优化后视觉变化和能耗差异均须记录。

参考：[Android Power Profiler](https://developer.android.com/studio/profile/power-profiler)。
