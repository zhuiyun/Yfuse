# 观看标签与媒体库布局本地验证

94 项单元测试通过，14 个测试类均无失败、错误或跳过。源码编译、主源码和测试源码格式检查通过。未使用手机、模拟器或 ADB 测试。

用户指定媒体库布局以首页合并前 1.2.9 为基准。LibraryHomeScreen.kt 和 LibraryHomeComponent.kt 与该版源码提交 9d5dd5297 逐字节一致（只统一换行后比较）。保留顶部轮播、历史横栏、分类和内容顺序。

底部新增“观看”标签，主体为纵向播放记录卡片，顶部用一排紧凑卡片提供观看中心、发现、追剧日历入口。记录保留同剧去重、圆角海报、简介、季集、进度、来源和详情跳转。观看中心承接继续观看、下一集、最近入库、收藏和固定智能片单，保留栏目调整与续播管理；发现和日历返回进入它们的来源页面。记录列表为根页面保留底部导航所需留白，详情返回时保持原列表位置。

修复独立观看中心的服务器加载和下拉刷新状态，手动刷新不受回访冷却限制，也不请求推荐内容。没有连接成功的来源服务器时提供重试提示。测试覆盖加载状态直到请求完成、刷新不请求推荐、导航返回来源、记录去重与目标服务器、栏目与播放操作等。

命令：`:phoneShared:ktlintCommonMainSourceSetCheck :phoneShared:ktlintCommonTestSourceSetCheck :phoneShared:testAndroidHostTest`，按 validation.json 中列出的测试类筛选；参数为 `-PyfuseIncludeMdk=true -PyfuseNativeOnlyRuntime=false -Pkotlin.incremental=false`。原始本地日志保留在 local-validation.log；XML 与汇总随源码归档。

复用已有图标，没有修改 Logo、图标源码或资源。正式签名的 full MDK 安装包仅作本地交付，不推送、不发布。
