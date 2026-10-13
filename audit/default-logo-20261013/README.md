# 默认 Logo：水火既济

本次复用项目已有的水火既济图案，将默认桌面图标的背景、前景和单色层同步到该图案。应用内 Logo、图标预览、快捷方式与通知小图标使用同一默认身份；启动动画按所选 Logo 匹配。

设置继续提供三种不同图案：默认水火既济、原版水火折带、云朵播放器。原版图案通过默认关闭的新别名提供；原水火既济别名继续保留，升级后先显示相同图案，再由既有的离开应用时切换机制迁移到默认入口。主启动入口及云朵别名保持原有身份。

验证范围：

- `python -m unittest scripts.tests.test_launcher_icons -v`：四项现有图标约束检查，核对每个别名、图标资源、快捷方式与通知映射，以及矢量路径限制和居中。
- `:phoneShared:ktlintCommonMainSourceSetCheck`、`:phoneShared:ktlintCommonTestSourceSetCheck`、`:phoneShared:ktlintAndroidMainSourceSetCheck`：代码格式检查。
- `:phoneShared:testAndroidHostTest --tests '*SplashSelectionTest' --tests '*ThemePreferencesTest'`：Logo 与动画匹配、旧别名归一化、保存的动画和主题偏好。结果见本目录 JUnit XML 和 `validation.json`。
- 正式包另行读取实际 APK 的版本、签名、MDK、DEX、16 KB 对齐和图标资源，记录于 `audit/releases/20261013-default-water-fire-1.2.9`。

本次图案未重新绘制，未进行真机或模拟器 UI 验证。APK 仅作本地交付。
