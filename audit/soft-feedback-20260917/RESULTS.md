# 柔性反馈验证记录

日期：2026-09-17。实施范围见 `../../docs/SOFT_FEEDBACK_20260917.md`。

## 已完成检查

- `:phoneShared:testAndroidHostTest`：2700 项，失败 0、错误 0、跳过 0。统计来自测试 XML，见 `unit-summary.json`。
- 新增 `SeekFeedbackTest`：3 项，覆盖边界、方向和无效输入；现有 `PlayerMotionPhysicsTest` 5 项、`MotionAccessibilityPolicyTest` 3 项也通过。
- `:composeApp:verifyDesignSystemUsage`：通过。修正了旧弃用提示文字触发的误报，没有放宽检查规则。
- 去除深色边框后，42 项相关 JVM 回归测试全部通过，见 `unit-final-summary.json`；之前的 2700 项全量结果 XML 已保存在 `unit-full/`。
- 本轮涉及的 24 个 Kotlin 文件通过 ktlint 格式化；范围包含一份未改变的导航基线文件。已检查相关 Git diff 的空白错误。

## 回归中发现并修复

- 初次主代码编译暴露既有外观简化与回调拆分的未同步调用，已补齐；具体范围见实施记录。
- 首次全量测试为 2698/2700，通过修复停止转码 URL 归一化和更新影片索引的本地进度测试后，2700 项全部通过。新的索引测试显式给出与服务端相反的本地状态，以验证本地进度优先。

## 构建与设备验证

- 手机验证 APK 与测试 APK 均构建成功，`:tvShared:compileAndroidMain` 通过；日志见 `border-final.log`。
- SM-G973U（Android 9 / API 28）实际运行 8 项仪器测试全部通过，用时 22.97 秒，见 `device-tests-final.log`。
- 新增 3 项测试覆盖进度条取消/单次提交及减少动态效果、菜单取消/快速连点、继续播放与从头播放的独立行为及解析时禁用。另 5 项现有测试验证导航中断、搜索交接、展开反向与组件动画布局稳定。
- 首轮仪器测试在第 3 项等待持续加载动画的全局空闲状态，已终止该次运行；测试同步改为 UI 线程事件与有超时的帧等待，并重跑全部 8 项通过。未修改或绕过产品加载行为。
- 已查看 `visuals/play-pressed-light.png`、`visuals/seek-pressed.png`、`visuals/menu-selected.png`：主播放键及内部图标/时间胶囊无深色轮廓；进度条形变与轨道连续，选项文字清晰。截图来自生产组件测试场景，不是完整媒体播放截图。
- 内部验证 APK 实际包名 `com.yfuse.softfeedback`、版本 `1.0.69` / `231`，与配置及更新说明一致；正式证书与上一实际交付 APK 相同，见 `apk-signature.txt`。手机原有 `com.yfuse` 仍为 1.0.54 / 216。

## 版本基线

上一实际交付 APK：`artifacts/releases/merged-1.0.68-230/Yfuse-1.0.68-230-full-arm64-signed.apk`。
读取其 APK 元数据确认包名 `com.yfuse`、版本名 `1.0.68`、内部版本 `230`。
本轮构建前已同步配置与更新说明为 `1.0.69` / `231`。

内部验证使用独立包名 `com.yfuse.softfeedback`；设备上原有 `com.yfuse` 保留。用户随后要求正式签名打包，并明确确认本次沿用现有 MDK 授权打 Full 包；正式构建和交付校验记录在 `../releases/20260917-soft-feedback/`。未授权线上发布或推送。

## 限制

连接设备是 SM-G973U（Android 9 / API 28）。没有其他 Android 版本、平板或 TV 真机验证，也没有同条件发布构建前后帧率、耗电对照数据。

## 正式签名交付

- 已生成 `artifacts/releases/soft-feedback-1.0.69-231/Yfuse-1.0.69-231-full-arm64-signed.apk`，29,440,681 字节。
- 实际包名 `com.yfuse`、版本 `1.0.69` / `231`；版本配置和更新说明一致，且高于上一实际交付的 `1.0.68` / `230`。
- 正式签名与上一交付一致；ARM64 完整原生库、YCore 字节一致性、ZIP CRC、ZIP/ELF 16 KB 对齐全部通过。
- SHA-256：`f0035f08ed56d6ce483498da666d73fc8c4c71b5ce854d0a24c3271925c85bff`。
- 首次 Release 构建同步了两份依赖锁，随后以严格锁定配置再次构建通过；最终构建前后 1736 份源码及构建输入一致。
- 正式 APK 未安装到设备，未上传、发布或推送。机器校验记录见 `../releases/20260917-soft-feedback/verification.json`。

## 复查入口

- `baseline/`：本轮改动前的文件副本，用来区分原工作区已有改动。
- `changed-files.json`：已有文件的前后 SHA-256；新增实现和测试见实施记录。
- `validation.log`：首次全量测试的两个失败。
- `validation-final.log`：2700 项测试通过，随后设计规范误报中止构建。
- `build-final.log`：修正提示文字后的构建日志。
- `format.ps1`、`verify.ps1`：本次验证用脚本。
