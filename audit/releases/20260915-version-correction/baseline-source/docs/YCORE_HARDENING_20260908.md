# Android 自研内核补强（2026-09-08）

接续 `6e7db80b` 的起播和卡顿优化。代码位于独立分支 `codex/playback-performance-20260908`，没有改动原工作区尚未提交的 TV / Harmony 代码。本轮范围为 NativeDirect、NativeEnhanced、NativeTunnel 及它们共享的传输、字幕、音频和自适应播放能力。

## 实现内容

| 项目 | 行为与边界 |
| --- | --- |
| 真实视频输出证据 | Direct / Enhanced / Tunnel 分离已提交和真实显示的帧。Direct / Enhanced SurfaceDirect 给 codec 输入分配跨 flush 不重复的帧标识，再映回原始媒体时间，以真实回调身份过滤旧帧；不可信的厂商时间戳不能用于音画同步测量。Tunnel 保留原始音视频时间。Enhanced 不再把排入 Surface 当作真实首帧。Vulkan 重置时重建 ImageReader，并隔离旧 reader 的回调。 |
| 暂停拖动 | 音频保持暂停，解码到目标位置后提交预览帧；短暂等待后仍无回调时停止主动轮询，保持“已提交但未确认”的诊断，迟到的真实回调仍可确认。恢复播放时重新对齐音视频时钟。正常播放和 Tunnel 首输出 watchdog 保留。 |
| 片尾跳转 | Direct / Enhanced SurfaceDirect 在短尾段尚未确认首帧时，最多延迟发送视频 EOS 一秒，继续排空和显示输出；真实帧回调到达立即放行。实际解码输出 EOS、既有音频输出结束条件满足、无暂停预览待恢复且 Surface 定时释放期限已过时进入 Ended；可缺失的显示回调不会永久阻塞终态，也不会被伪造成已验证首帧。Direct 对外进度限制在媒体时长内。 |
| Tunnel 读取 | 使用自研传输和独立、可取消的解复用预读，按实际缓冲决定等待；跳转取消当前网络操作，但数据源仍可复用。 |
| HLS 起播 | 优先解析已选择的码率，备用码率在后台最多并发两个；总时限、取消和晚到结果都有约束。 |
| 设备音频能力 | 跟踪 AudioTrack 实际路由的设备、编码、采样率和声道变化；不因无关已连接设备变更而重建当前播放。 |
| 音频延迟 | 原生 PCM 路径支持 ±5 秒调整；以输出设备产品配置及设备类别保存偏好。Tunnel 的非零延迟请求受控切回可调整的原生路径。时钟诊断保留未经人为偏移的实际测量。 |
| 动态 ASS | 独立 libass renderer 渲染完整内嵌 / 外挂脚本，保留样式、字体、位置、移动、淡入淡出和动画；主副字幕分别管理。渲染任务合并，seek / 字幕 / 外观变更隔离旧结果。 |
| 播放循环和刷新率 | 根据队列进度调整 pump 等待，减少无进展轮询；稀疏采样实际显示刷新相位，对定时视频释放做相位对齐，空闲后停止采样。 |
| 统一缓冲预算 | 按设备内存和应用 heap 分配传输、预读、渲染、字幕、缓存写入、预加载预算。内存压力和后台状态关闭推测性工作，回收已完成预取及起播切片；系统 trim 后保留恢复冷却期。 |
| 软件渲染 | 复用有限数量 Bitmap，从 native buffer 直接复制到可复用 Bitmap，减少逐帧 ByteArray 和中间副本。没有增加新的 GPU 上传通道。 |
| 不同 init 的码率切换 | 兼容性检查后执行受控重开，保存整体位置；用冷却时间及滚动次数上限避免反复重开。并非无缝切换。 |
| DASH 多 Period | 静态 SegmentTemplate / SegmentTimeline 映射整体时间与 Period 本地时间，支持跨 Period 跳转和结束后接续，并重建不同初始化段对应的解码状态。可精确换算的静态零 PTO 定长模板输出有限 Timeline，避免额外请求不存在的尾段。没有扩大到 SegmentList、SegmentBase 或直播 DVR。 |
| 回归输出目标 | 媒体套件默认使用真实 SurfaceView；设备上生成 AVC、AVC + AAC 静音及分段 MP4，覆盖输出、seek、暂停、切换和生命周期。无需用户媒体。 |

缓冲预算约束的是应用持有的播放缓冲，不能解释为整个进程的严格峰值上限。平台 codec / GPU 驱动、FFmpeg 内部解码和其他 UI 内存不全在池内；已提交的缓存写入也需要完成或退出后释放。

Android 的 [MediaCodec 输出回调](https://developer.android.com/reference/android/media/MediaCodec#setOnFrameRenderedListener(android.media.MediaCodec.OnFrameRenderedListener,android.os.Handler)) 可能延迟、批量发送或缺失，不能把暂停后没有单帧回调直接解释为画面未显示。暂停回归使用设备生成的帧号条码和 [PixelCopy](https://developer.android.com/reference/android/view/PixelCopy) 对比 Surface 实际像素；这证明 Surface 内容已更新并保持静止，不等同于精确屏幕扫描时刻，也不会写入假的 `videoOutputVerified`。起播和恢复回归仍要求真实输出回调。

## 原生库与打包

libass JNI 接口升级为 API 2。当前 JNI 使用固定 FFmpeg 提交 `b79d4c4c0a160fc46988e98505af6039a53ad53e` 的头文件，以及已验证 MPV 发布包中的共享库重新链接；没有使用版本不匹配的本地 FFmpeg 缓存。

本次 ARM64 `libycore_demux.so` SHA-256：`3183aacc1f75e21dda76e9c713857b048633b90616933ae17f30fc15fd34b542`。链接核验检查 Android API 26 可用符号及发布包依赖，使用 AVUTIL 61 / AVCODEC 63 / AVFORMAT 63 的一致 ABI。

完整包和自研专用包均使用同一份当前 YCore AAR。完整包构建时生成裁剪后的 MPV carrier，移除由 YCore 提供的原生库；同名 FFmpeg / C++ 依赖必须逐字节一致。只有当前 YCore demux / GPU 可以替换旧版本。原 MPV 发布 AAR 和校验固定值不变。

新增 CI 准备步骤只缓存固定版本依赖和头文件，每次重新编译当前 JNI / GPU 源码。冷缓存需要完整准备依赖，可能显著延长 CI；云端流水线需在提交推送后才能验证。本轮没有把本地构建成功当作云端 CI 已通过。

## 验证记录

最终冻结代码的 Android 单元测试共 431 个套件、2,207 项，失败、错误及跳过均为 0。本轮修改的 Kotlin 文件通过格式检查；自研专用包、包含其他内核的完整包及各自仪器测试包均编译成功。原生构建脚本三项行为测试及 Bash 语法检查通过。

设备为 Samsung SM-G973U、Android 9 / API 28、ARM64。自研专用包的 11 项设备测试全部通过，无跳过：动态 ASS、JNI 契约与生成文件解复用、运行时资源与打包检查、真实 Surface 首帧及跳转、生命周期、暂停预览、静态 DASH 多 Period。生命周期用例完成 10 次 seek、两次 Surface 重建、前后台切换、上下集往返及片尾 Ended。DASH 用例完成正反向跨 Period 跳转、自动接续、片尾跳转及最终 Ended，确认不同初始化段和视频尺寸。

完整包另外通过四项手机运行时检查。两种 APK 中的当前 demux 哈希一致，六份 FFmpeg / C++ 共享依赖与 MPV 发布包逐字节一致，没有重复 ZIP 条目。完整包检查验证的是原生库共存与运行时契约，不代表已逐一实测其他内核的全部播放格式。

日志、单元测试 XML、核验 JSON 和两种 APK 保存在未纳入 Git 的 `artifacts/playback-perf/hardening/`：`device-final.log`、`full-runtime-device-final.log`、`build-final.log`、`build-full-final.log`、`verification.json`、`unit-results/`、`native/`、`full/`。早期失败日志保留，最终结果以上述文件为准。测试使用独立包 `com.yfuse.playbackperf` 和 `com.yfuse.playbackperf.full`，完成后清理这些临时应用，不读取主应用数据。

## 尚需实测的覆盖

没有实测平板、蓝牙 / HDMI 延迟切换、4K HDR / Dolby Vision、完整 GPU 渲染路径、长时间弱网及 8 / 24 小时播放。暂停测试实际覆盖 Direct、Enhanced，以及请求 Tunnel 后由自适应层选择 Direct 的路径；日志中的 `requested=NativeTunnel actual=NativeDirect` 不代表硬件 Tunnel 真机验证通过。

本轮是功能与可靠性回归；尚未取得同设备同资源的前后性能对照，不能承诺起播减少多少毫秒、卡顿降低多少百分比或具体省电收益。
