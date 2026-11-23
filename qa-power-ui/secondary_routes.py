"""Read-only dialog and secondary-page coverage through visible application controls."""

APPEARANCE = ['外观与交互']
MOTION = APPEARANCE + ['动画与动效']
PLAYBACK = ['播放设置']
ADVANCED = PLAYBACK + ['高级播放设置']

ROUTES = [
    ('startup-choice', '我的', APPEARANCE + ['首页与导航', '启动进入']),
    ('widget-workspace', '我的', APPEARANCE + ['首页与导航', '首页与组件管理']),
    ('motion-theme-choice', '我的', MOTION + ['动效主题']),
    ('particle-choice', '我的', MOTION + ['粒子光效']),
    ('dialog-animation-choice', '我的', MOTION + ['弹窗动画']),
    ('loading-animation-choice', '我的', MOTION + ['加载动画']),
    ('player-transition-choice', '我的', MOTION + ['播放器进出场']),
    ('portrait-choice', '我的', PLAYBACK + ['竖屏视频']),
    ('skip-settings', '我的', PLAYBACK + ['片头片尾']),
    ('playback-mode-choice', '我的', PLAYBACK + ['播放模式']),
    ('version-choice', '我的', PLAYBACK + ['视频版本偏好']),
    ('cache-size-choice', '我的', PLAYBACK + ['视频缓存大小']),
    ('preheat-choice', '我的', PLAYBACK + ['起播预热']),
    ('decoder-choice', '我的', ADVANCED + ['解码方式']),
    ('buffer-duration-choice', '我的', ADVANCED + ['YCore 前向缓存时长']),
    ('danmaku-source-list', '我的', ['弹幕设置', '弹幕来源']),
    ('danmaku-source-add', '我的', ['弹幕设置', '弹幕来源', '添加']),
    ('danmaku-word-filter', '我的', ['弹幕设置', '屏蔽词']),
    ('family-add', '我的', ['家庭资料', '新建家庭资料']),
    ('family-pin', '我的', ['家庭资料', '设置家长 PIN']),
    ('account-encryption-info', '我的', ['账号与同步', '加密说明']),
    ('stremio-add', '我的', ['Stremio 插件', '安装插件']),
    ('calendar-followed', '观看', ['追剧日历', '追剧']),
    ('calendar-resources', '观看', ['追剧日历', '资源']),
    ('calendar-settings', '观看', ['追剧日历', '设置']),
    ('statistics-history', '我的', ['观影统计', '观看历史']),
    ('file-smb-form', '服务器', ['添加文件来源', 'SMB']),
    ('file-ftp-form', '服务器', ['添加文件来源', 'FTP']),
    ('file-123-form', '服务器', ['添加文件来源', '123云盘']),
    ('file-baidu-form', '服务器', ['添加文件来源', '百度']),
    ('file-alist-form', '服务器', ['添加文件来源', 'Alist']),
]
