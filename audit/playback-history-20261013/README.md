# 播放记录页面本地验证

52 项单元测试通过，8 个测试类均无失败、错误或跳过。源码编译、主源码及测试源码格式检查通过。未使用手机或 ADB 测试。

覆盖同剧跨季和跨来源去重、最新单集与进度保留、提供方身份关联、同名不同剧区分、资料权限和删除记录过滤、简介与海报来源、服务器改名别名、Plex 图片端点、详情跳转及可见卡片简介请求合并缓存。保留播放记录、观影统计、剧集元数据及库导航相关回归检查。

命令：`:phoneShared:ktlintCommonMainSourceSetCheck :phoneShared:ktlintCommonTestSourceSetCheck :phoneShared:testAndroidHostTest`，按 validation.json 中列出的测试类筛选；参数为 `-PyfuseIncludeMdk=true -PyfuseNativeOnlyRuntime=false -Pkotlin.incremental=false`。原始本地日志保留在同目录的 local-validation.log；测试 XML 与汇总随源码归档。

界面入口位于媒体库顶部的“播放记录”。卡片使用纵向列表，包含圆角海报、作品简介、最近观看季集、进度、观看时间及来源；进入对应媒体详情后返回保留滚动位置。未更改 Logo 或现有图标资源。
