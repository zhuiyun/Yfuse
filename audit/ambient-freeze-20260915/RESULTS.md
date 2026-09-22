# 氛围光不变化修复

日期：2026-09-15。用户反馈 YCore／原生内核的氛围光似乎不再变化，未确认视频是否为 HDR。

本轮从代码与模拟数据中确认并修复以下缺陷。没有实际播放，因此不能认定某一项就是用户设备上此次现象的唯一原因。

## 修复项

| 严重程度 | 问题与触发条件 | 修改后的行为 |
| --- | --- | --- |
| P2 | 控制栏隐藏且视频尺寸铺满容器时，只检查布局黑边，忽略影片像素中自带的黑边，导致停止取色。 | 将已采样到的内嵌黑边纳入可见区域判断。没有可见颜色用途时每 2 秒低频探测一次，发现内嵌黑边后恢复正常取色；真正裁出屏幕的黑边不会触发快速取色。 |
| P2 | 取色请求被暂停、切源或 Surface 更新取消后，若系统完成回调丢失，下一次请求会卡在超时保护之外的队列等待。 | 将队列等待纳入 3 秒超时。旧请求丢失回调时后续取色可恢复，迟到的旧回调只释放旧位图和旧队列，不影响新请求。 |
| P2 | 已确认的杜比视界／HDR10 直出一律被设为静态海报色，Android 14 及以上也没有尝试动态取色。 | Android 14 / API 34 起允许通过系统 HDR 色调映射尝试动态取色；已知 HDR 直出在旧系统上维持原有回退。厂商输出不可读时仍按失败次数退避并使用海报色。 |

## 保留的运行约束

- 氛围光关闭、页面不可见、后台、画中画、减少动态效果、DRM 或功耗受限时，不启动动态取色。
- 暂停后成功取色一次即停止；静止画面自适应降频；连续失败逐步退避。
- 仅可见区域需要光效时运行动画；视频本身的内容区域仍被裁剪排除。
- Android 14 的支持依据为官方 PixelCopy → HWUI Readback → Tonemapper 源码，不代表每家厂商的杜比视界或隧道输出均可读取。详见 [HDR-READBACK.md](HDR-READBACK.md)。

## 修改范围

- `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/PlayerAmbientBinding.kt`
- `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AmbientSamplingPolicy.kt`
- `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AmbientFrameSampler.kt`
- `composeApp/src/androidMain/kotlin/com/yfuse/feature/player/AmbientCopy.kt`
- `composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/AmbientSamplingPolicyTest.kt`
- `composeApp/src/androidUnitTest/kotlin/com/yfuse/feature/player/AmbientCopyTest.kt`

## 验证

已新增 8 项回归测试；模拟像素及异步回调，不启动播放。构建结果：**BUILD SUCCESSFUL in 5m 56s**。

| 模块 | 测试数 | 失败 / 错误 / 跳过 |
| --- | ---: | --- |
| composeApp（手机／平板共享模块） | 2,576 | 0 / 0 / 0 |
| tvApp | 63 | 0 / 0 / 0 |
| 合计 | **2,639** | **0 / 0 / 0** |

氛围光专属 4 个测试套件共 39 项全部通过。两个模块的 Kotlin 编译和 ktlint 均通过，`git diff --check` 通过。验证前后 6 个修改源码／测试文件的 SHA-256 一致，记录见 [source-hashes.json](source-hashes.json)。两轮独立复核未发现新增的明确行为回归。

脚本：[run-verification.ps1](run-verification.ps1)。构建日志：[verification.log](verification.log)。统计：[test-summary.json](test-summary.json)。氛围光与 TV 测试 XML 保存在 `test-results/`。

未播放、未生成或安装新 APK、未部署。版本配置未变更，修复尚未交付到用户设备。

后续：用户随后要求签名打包，已于同日生成并验证 **1.0.56（218）** 本地交付包，见[签名打包记录](../releases/20260915-ambient-family/README.md)。未安装或播放。
