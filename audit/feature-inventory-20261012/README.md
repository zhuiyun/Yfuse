# 功能梳理和日历修复记录

完整清单保存于 [功能清单](D:/Demo/Yfuse/docs/FEATURE_INVENTORY_20261012.md)，结构化记录为 `inventory.json`。共 14 组 142 项；每项有重要性、必要性、当前状态和取舍理由。没有删减产品功能。

本次仅修改日历 ICS 导出源码和测试。修复改期导致事件身份变化、没有 TMDB 的不同剧集事件冲突、URL 错误转义、换行和 UTF-8 折行、时区定义缺失及已知精确播出时间未导出的问题。8 项导出回归修改前全部失败，修改后全部通过；7 个日历测试类共 55 项通过。

使用 JDK 21 和项目 Gradle wrapper 执行：

```text
:phoneShared:testAndroidHostTest --tests com.yfuse.feature.calendar.*
:phoneShared:ktlintCommonMainSourceSetCheck
:phoneShared:ktlintCommonTestSourceSetCheck
```

`calendar-after-fix.log` 和 `ktlint.log` 均显示构建成功；`git diff --check` 通过。测试证据在 `calendar-test-results`；修改前复现保存在 `calendar-before-fix.xml`。`validation.json` 保存分类数量、测试汇总和产物校验和。

本次没有制作新 APK、推送或更新服务器。此前签名包和部署记录保持原样；这次客户端修复需下一次打包后生效。已导入外部日历的旧事件不会自动删除。
