# 行内加载交接与海报进度 — 2026-09-10

已修改 7 个源码文件。

- 六处 Orb/Icon 切换统一使用 InlineLoadingContent 内的 AnimatedContent，120 ms 淡入淡出：详情主播放按钮、详情操作按钮、TMDB 立即播放、日历刷新、登录设备刷新、账号设置行尾。
- 图标槽按较大尺寸保留，Orb 和图标居中。账号行尾包含文字时，宽度也以 120 ms 过渡；邀请码“生成”文字保留到退出过渡结束。
- 海报观看进度使用 animateFloatAsState，180 ms。动画值仅在 graphicsLayer 读取，通过横向缩放改变可见宽度，不逐帧重新组合或测量海报布局。保留渐变、4 dp 高度、底轨、RTL 方向、0..1 边界及无正进度时隐藏行为。
- 按图片候选列表隔离动画状态；首载直接显示当前进度，同一海报后续进度变化才插值。
- 遵循减弱动效与路由可见性设置，系统动画时长缩放由 Compose 处理。

验证：

- :composeApp:testReleaseUnitTest 通过：2460 项，0 失败，0 错误，0 跳过。
- :composeApp:verifyDesignSystemUsage 通过；Release 主代码及测试代码编译通过。
- 7 个改动源码文件 ktlint 通过，git diff --check 通过。
- 未连接手机，未进行真机视觉验证；本轮未签名打包。

本轮前后差异见 diff/，源码摘要见 source-snapshot.json，构建日志见 feature-tests.log。
