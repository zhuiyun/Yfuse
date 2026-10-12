# 详情页顺序、返回动画与圆角验证

日期：2026-10-12。

## 改动

- 详情页顺序调整为简介、剧集、资源比较、版本。完整移动 `sources` 区块，保留选集、加载、重试与切换资源的回调。
- 手机端和 TV 端应用声明 `android:enableOnBackInvokedCallback="false"`，关闭系统预测返回预览，保留普通返回处理。
- 保存被点击海报的实际轮廓，覆盖普通海报、首页轮播以及日历缩略图。嵌套详情页返回时恢复各自来源海报的轮廓。
- 共享图片在动画覆盖层直接裁剪：来源海报从第一帧保留圆角；详情大图随返回进度逐渐过渡至来源轮廓，落点使用相同的连续圆角路径。
- 圆角进度在绘制阶段读取，缓存同一帧的轮廓；共享图片动画期间省去整页圆角动画和整页裁剪层，保留原有缩放与弹簧动画。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| 返回栈、共享图片控制器、缩放返回和播放器返回相关单元测试 | 72 项通过，0 失败、0 跳过 |
| commonMain、commonTest、androidTest 的 ktlint | 通过 |
| 手机端 Debug 与 AndroidTest 编译构建 | 通过 |
| 手机端最终 Debug APK 的预测返回开关 | `false`，已读取 APK 内的 Manifest |
| TV 端 `processDebugMainManifest` 合并开关 | `false` |
| 详情页区块顺序 | `overview → episodes → sources → versions` |
| Android 15 / API 35 模拟器测试 | 5 项通过，0 跳过，耗时 29.488 秒 |

模拟器测试包括连续普通返回、嵌套海报返回、实际系统边缘手势的取消和提交。提交能正确逐层返回；取消保留当前详情；两者均未出现预测海报预览。

圆角像素测试覆盖缩略图、普通海报、首页圆角轮播及普通 `RoundedCornerShape`，分别在 1x、2x 密度下验证裁剪覆盖面积随进度缩小，最终像素与来源轮廓完全一致，并验证动画进度和尺寸变化会更新缓存轮廓。

动画导航测试使用稳定色块 fixture。物理 Android 9 设备在测试前断开，本轮没有采集物理设备的真实详情页帧耗时；功能通过与绘制开销减少不代表已有掉帧数量的实测结论。

## 复跑

```powershell
./gradlew.bat :phoneShared:testAndroidHostTest `
  --tests 'com.yfuse.core.designsystem.SharedMediaTransitionTest' `
  --tests 'com.yfuse.core.designsystem.ZoomBack*Test' `
  --tests 'com.yfuse.app.TopLevelBackStackTest' `
  --tests 'com.yfuse.feature.player.PlayerPredictiveBackTest' `
  :phoneShared:ktlintCommonMainSourceSetCheck `
  :phoneShared:ktlintCommonTestSourceSetCheck `
  :composeApp:ktlintAndroidTestSourceSetCheck `
  :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest `
  :tvApp:processDebugMainManifest `
  '-PyfuseApplicationId=com.yfuse.validation.returnmotion' `
  '-PyfuseIncludeMdk=true' '-PyfuseNativeOnlyRuntime=false'
```

安装构建出的 Debug APK 和 AndroidTest APK 后，使用 Android 13 以上的手势导航设备执行：

```powershell
adb shell am instrument -w -r -e class 'com.yfuse.core.designsystem.SharedArtworkClipInstrumentedTest,com.yfuse.app.DetailBackNavigationInstrumentedTest#activity_back_returns_through_nested_details_without_finishing_the_app,com.yfuse.app.DetailBackNavigationInstrumentedTest#ordinary_back_restores_nested_posters_without_a_predictive_preview,com.yfuse.app.DetailBackNavigationInstrumentedTest#system_edge_swipes_cancel_and_pop_nested_details_without_exiting' 'com.yfuse.validation.returnmotion.test/androidx.test.runner.AndroidJUnitRunner'
```

## 证据与交付状态

此目录保留 7 份单元测试 XML、`android15-instrumentation.txt` 和 `validation.json`。后者记录来源提交、修改文件校验和及实际 Debug APK 校验和。

本轮为源码修改与自动化验证。测试 APK 使用独立包名 `com.yfuse.validation.returnmotion`，显式启用完整 MDK，属于测试产物。下一次正式打包交付继续按根目录 AGENTS.md 递增版本、更新说明并验证正式签名、MDK 和 DEX。

相关官方说明：[预测返回的应用级关闭开关](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)、[Compose 共享图片覆盖层的裁剪](https://developer.android.com/develop/ui/compose/animation/shared-elements/customize)。
