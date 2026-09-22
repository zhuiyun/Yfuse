# 加载动画切换

本次按已确认的 01–06 和 B1/B2/B3 接入九款加载动画：星轨呼吸、水火相融、折光风车、静水涟漪、光谱律动、轻盈三点、三点轻跃、三点呼吸、三点接力。

- 手机入口：我的 → 外观与辅助 → 加载动画。点击后立即预览并保存。
- TV 入口：设置 → 外观 → 加载动画，沿用遥控器选择行的循环切换方式。
- 设置键：`appearance.loadingAnimation`，按枚举名称保存；首次使用和未知值回退至星轨呼吸。
- 主界面、独立播放器的准备页和播放页、TV 主题均传递所选样式，现有 `OrbProgress` 调用统一使用该设置。
- 九款均使用 Compose Canvas 绘制并缓存路径和渐变，不依赖图片或网络资源。动画状态仅在绘制阶段读取。
- 减少动画或路由不可见时停止循环并显示静态帧。选择列表只播放当前选中的样式。
- 本次只修改实现并执行编译和测试，没有生成交付 APK，未修改版本配置或更新说明。

## 验证记录

`verify.log` 是首次验证；接力圆点的间距测试发现重叠后，将跨越高度从 9 调整为 12 个设计单位。

`verify-final.log` 中以下验证已通过：

- `:phoneShared:testAndroidHostTest`，筛选 `ThemePreferencesTest`、`LoadingAnimationTest`、`LoadingMotionTest`，共 14 项。
- `:tvShared:compileAndroidMain`。
- `:composeApp:verifyDesignSystemUsage`。

这次组合命令最终因格式检查失败退出：最初使用的 include 过滤未排除其他源码，检查报到未改动的 `Tokens.kt`、`EmbyDetailServiceTest.kt`、`EmbyUserDataServiceTest.kt` 和 `WatchTogetherClientTest.kt` 的既有格式问题。

随后将 `format-check.init.gradle` 改为显式排除本次范围外文件，保持原有基线不变，重新执行 `:phoneShared:ktlintCheck` 和 `:tvShared:ktlintCheck --rerun-tasks`。`lint-final.log` 记录本次改动的格式检查 **BUILD SUCCESSFUL**。未改动上述无关文件。

未执行真机 UI 验证。
