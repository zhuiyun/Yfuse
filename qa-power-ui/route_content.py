"""Visible destination content for the two Chinese full-app capture suites."""

# A destination needs content specific to the resulting panel or page. A dock
# label or a button on the screen that led to it is insufficient.
CONTENT = {
    'servers': ('我的服务器', '浏览 NAS 与网盘里的视频'),
    'library-empty': ('还没有可用的服务器', '添加服务器'),
    'watching': ('播放记录', '追剧日历'), 'history': ('播放记录', '追剧日历'),
    'profile': ('搜索设置', '服务器与账号'),
    'add-server': ('服务器信息', '服务类型'), 'file-source': ('来源信息', 'WebDAV'),
    'iptv': ('M3U 地址', '添加订阅'), 'wifi-import': ('开启临时接收', '尚未接收视频'),
    'personal': ('我的片单', '搜索片单'), 'statistics': ('观影统计', '观看天数'),
    'family': ('管理资料', '新建家庭资料'), 'account': ('登录 Yfuse 账号', '请输入账号名'),
    'watch-together': ('登录 Yfuse 账号', '请输入账号名'),
    'playback': ('常用播放', '自动播放下一集'), 'advanced': ('内核与解码', '播放内核'),
    'engine-choice': ('高级内核选择', 'SCore（实验'), 'speed-choice': ('0.75x', '1.75x'),
    'danmaku': ('来源与内容过滤', '屏蔽词'), 'appearance': ('视觉效果', '背景与图标'),
    'appearance-layout': ('自定义首页 Tab', '首页与组件管理'),
    'appearance-motion': ('整体风格', '单项动效'),
    'appearance-accessibility': ('大号文字', '减少透明效果'),
    'glass': ('浅色玻璃', '深色玻璃'), 'splash': ('APP 图标', '原版 Logo'),
    'diagnostics': ('自定义 User-Agent', '迁移服务器'), 'downloads': ('下载中心', '下载设置'),
    'stremio': ('添加你的第一个插件', '安装插件'), 'trakt': ('连接 Trakt', '同步范围'),
    'device-handoff': ('在线设备', '使用说明'), 'sync': ('清单、历史与追剧', '跨设备播放进度'),
    'about-section': ('检测升级', '构建'), 'calendar': ('追剧日历', '回到今天'),
    'discover': ('返回媒体库', '影视推荐'), 'populated-library': ('Cloud QA Library', '我的收藏'),
    'movie-library': ('测试电影', '最近添加'), 'series-library': ('测试剧集', '最近添加'),
    'movie-detail': ('山海之间', '服务器收藏'), 'long-title-detail': ('很长电影标题', '服务器收藏'),
    'series-detail': ('城市的一天', '第一季'), 'saved-server': ('Cloud QA Library', '刷新全部服务器'),
    'startup-choice': ('启动进入', '直接进入媒体库'), 'widget-workspace': ('组件管理', 'TMDB'),
    'motion-theme-choice': ('动效主题', '静息'), 'particle-choice': ('粒子光效', '增强'),
    'dialog-animation-choice': ('柔和浮起', '中轴展开'),
    'loading-animation-choice': ('星轨呼吸', '水火相融'),
    'player-transition-choice': ('转身', '开幕'), 'portrait-choice': ('自动竖屏', '始终横屏'),
    'skip-settings': ('默认的跳过方式', '自动跳过'), 'playback-mode-choice': ('兼容优先', '省电优先'),
    'version-choice': ('杜比视界优先', '综合画质优先'), 'cache-size-choice': ('256 MB', '512 MB'),
    'preheat-choice': ('仅 Wi-Fi', 'Wi-Fi 和移动网络'), 'decoder-choice': ('软件兼容（FFmpeg）', '硬件优先'),
    'buffer-duration-choice': ('3 秒', '6 秒'), 'danmaku-source-list': ('弹幕链接', '还没有弹幕源'),
    'danmaku-source-add': ('编辑弹幕源', 'danmaku.example.com'),
    'danmaku-word-filter': ('弹幕屏蔽词', '输入一个词后按添加'),
    'family-add': ('新建家庭资料', '资料名称'), 'family-pin': ('家长 PIN', '新 PIN'),
    'account-encryption-info': ('加密说明', '服务器令牌、弹幕源链接'),
    'stremio-add': ('插件 URL', '读取插件'), 'calendar-followed': ('还没有加入追剧的剧集', '查看日历'),
    'calendar-resources': ('剧集的资源', '待补齐'), 'calendar-settings': ('排期数据源', '补充已公布的分集日期'),
    'statistics-history': ('观看历史', '暂无观看记录'),
    'file-smb-form': ('默认 445', '共享与路径'), 'file-ftp-form': ('默认 21', 'FTP 根目录'),
    'file-123-form': ('Client ID', 'Secret'), 'file-baidu-form': ('App Key', 'Secret'),
    'file-alist-form': ('默认 /dav', '默认 443'),
}

ROOT_TABS = {'servers': '服务器', 'saved-server': '服务器', 'library-empty': '媒体库',
             'populated-library': '媒体库', 'profile': '我的', 'watching': '观看', 'history': '观看'}


def destination_visible(tree, name, selected_dock):
    labels = [node.get(key, '') for node in tree.iter('node')
              if node.get('package') == 'com.yfuse' and node.get('visible') != 'false'
              for key in ('text', 'content-desc')]
    return (all(any(marker in label for label in labels) for marker in CONTENT[name]) and
            (name not in ROOT_TABS or selected_dock(tree, ROOT_TABS[name])))
