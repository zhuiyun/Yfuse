# 首页合并到库 — 2026-10-13

1.3.0 / 282 的本地回归验证：114 项测试通过，失败、错误与跳过均为 0。源代码及测试代码格式检查通过。最终 APK 的正式签名、实际版本、MDK、DEX、16 KB 对齐、大小及 SHA-256 由打包后独立校验，见 `audit/releases/20261013-home-merge-1.3.0`。

覆盖启动偏好迁移、发现返回库、底部导航手势边界、库与推荐独立加载、跨服务器内容聚合、继续观看撤销、下一集去重、追剧打开目标、片单栏目设置、共享图片标识、播放恢复及自动播放路径。发现按需加载推荐，库刷新不请求 TMDB 推荐；库与发现重置栏目设置互不覆盖。合并时保留固定智能片单海报栏。

最终命令使用 `:phoneShared:ktlintCommonMainSourceSetFormat :phoneShared:ktlintCommonMainSourceSetCheck :phoneShared:ktlintCommonTestSourceSetCheck :phoneShared:testAndroidHostTest`，按 XML 中列出的 17 个测试类筛选，参数为 `-PyfuseIncludeMdk=true -PyfuseNativeOnlyRuntime=false -Pkotlin.incremental=false`。先前本地增量编译遇到 Kotlin 缓存异常，关闭增量编译后的完整重跑通过；版本号沿用同一次交付的 1.3.0 / 282。

用户要求不使用手机测试后已停止设备测试，临时独立验证应用及本地测试服务已清理。没有使用手机 UI 测试作为本次交付的验证依据，也没有声明 UI 实机验收通过。此记录只基于本地编译、单元测试与产物校验；没有推送、上传或发布。
