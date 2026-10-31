package com.yfuse.feature.profile

import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.SettingTint

internal data class SettingsControlAnchor(
    val pageTitle: String,
    val itemIndex: Int,
    val label: String,
)

/** Section indices include SettingsPage's header. Labels match the actual accessible controls. */
internal fun settingsControlDestinations(): List<SettingsSearchDestination> =
    buildList {
        fun controls(
            page: ProfilePage,
            pageTitle: String,
            section: Int,
            vararg labels: String,
        ) {
            labels.forEach { label ->
                val (icon, tint) =
                    when (label) {
                        "进度同步" -> AppIcons.Refresh to SettingTint.sync
                        "视频缓存大小", "YCore 前向缓存时长" -> AppIcons.Download to SettingTint.cache
                        "自动播放下一集" -> AppIcons.Next to SettingTint.playback
                        "竖屏视频" -> AppIcons.RotationUnlocked to SettingTint.playback
                        "默认倍速" -> AppIcons.Forward to SettingTint.playback
                        "详情页主题曲", "音频直通", "熄屏继续播放声音" -> AppIcons.Volume to SettingTint.audio
                        "智能跨服选源" -> AppIcons.PlaybackSource to SettingTint.servers
                        "片头片尾" -> AppIcons.SkipMarkers to SettingTint.playback
                        "视频版本偏好", "显示帧率" -> AppIcons.Movie to SettingTint.playback
                        "Anime4K · YCore SDR 动漫（下次播放生效）" -> AppIcons.Star to SettingTint.playback
                        "刷新率匹配" -> AppIcons.Refresh to SettingTint.advanced
                        "YCore 2.0", "纯原生模式（实验）" -> AppIcons.Play to SettingTint.playback
                        "库页轮播图" -> AppIcons.Grid to SettingTint.library
                        "自定义首页 Tab" -> AppIcons.Home to SettingTint.components
                        "首页与组件管理" -> AppIcons.Grid to SettingTint.components
                        "滚动时收起导航栏" -> AppIcons.Collapse to SettingTint.general
                        "玻璃材质", "减少透明效果" -> AppIcons.AspectFill to SettingTint.components
                        "Logo 与开屏动画" -> AppIcons.StarFilled to SettingTint.appearance
                        "背景图" -> AppIcons.AspectFit to SettingTint.library
                        "启动进入" -> AppIcons.Home to SettingTint.general
                        "动效主题" -> AppIcons.Movie to SettingTint.components
                        "搜索与导航动效" -> AppIcons.SearchTab to SettingTint.components
                        "粒子光效" -> AppIcons.StarFilled to SettingTint.components
                        "弹窗动画" -> AppIcons.Expand to SettingTint.components
                        "加载动画" -> AppIcons.Refresh to SettingTint.components
                        "播放器进出场" -> AppIcons.Play to SettingTint.components
                        "大号文字" -> AppIcons.Subtitle to SettingTint.language
                        "减少动画" -> AppIcons.Pause to SettingTint.advanced
                        "弹幕来源" -> AppIcons.Danmaku to SettingTint.danmaku
                        "屏蔽词" -> AppIcons.DanmakuOff to SettingTint.danmaku
                        else ->
                            when (page) {
                                ProfilePage.Playback -> AppIcons.Play to SettingTint.playback
                                ProfilePage.AdvancedPlayback -> AppIcons.Menu to SettingTint.advanced
                                else -> AppIcons.SearchTab to SettingTint.general
                            }
                    }
                add(
                    SettingsSearchDestination(
                        label,
                        "$pageTitle · 定位到设置项",
                        label + " " +
                            when (label) {
                                "音频直通" -> "音频输出 透传 passthrough"
                                "熄屏继续播放声音" -> "后台播放 锁屏 熄屏 音频 background audio"
                                "播放模式" -> "YCore 播放策略 自动 省电 兼容 画质"
                                "播放内核" -> "播放器 引擎 MDK MPV YCore2 YCore 2.0 Native 原生 纯内核 自动模式"
                                "YCore 2.0" -> "YCore2 YCore 2.0 自动模式"
                                "纯原生模式（实验）" -> "YCore2 Native 原生 纯内核 自动模式"
                                "Anime4K · YCore SDR 动漫（下次播放生效）" -> "anime4k 动漫 SDR 超分 画质"
                                "刷新率匹配" -> "帧率 屏幕刷新率 无缝"
                                "自定义首页 Tab" -> "首页 导航 Tab 组件 widget"
                                "首页与组件管理" -> "首页 组件 widget 布局 备份"
                                "大号文字" -> "字体 字号 字体大小"
                                "屏蔽词" -> "屏蔽关键词 弹幕过滤"
                                else -> ""
                            },
                        page,
                        anchor = SettingsControlAnchor(pageTitle, section, label),
                        icon = icon,
                        tint = tint,
                    ),
                )
            }
        }
        controls(ProfilePage.Playback, "播放", 1, "自动播放下一集", "默认倍速", "竖屏视频", "片头片尾")
        controls(ProfilePage.Playback, "播放", 2, "播放模式", "视频版本偏好", "智能跨服选源")
        controls(ProfilePage.Playback, "播放", 3, "详情页主题曲", "熄屏继续播放声音")
        controls(ProfilePage.Playback, "播放", 4, "视频缓存大小", "起播预热", "高级播放设置")
        controls(ProfilePage.Playback, "播放", 5, "进度同步")
        if (com.yfuse.backend.BackendAccess.Default.enabled) {
            controls(ProfilePage.Playback, "播放", 5, "匿名播放质量分享")
        }
        controls(
            ProfilePage.AdvancedPlayback,
            "高级播放设置",
            1,
            "播放内核",
            "YCore 2.0",
            "纯原生模式（实验）",
            "解码方式",
            "YCore 前向缓存时长",
        )
        controls(
            ProfilePage.AdvancedPlayback,
            "高级播放设置",
            2,
            "显示帧率",
            "Anime4K · YCore SDR 动漫（下次播放生效）",
            "刷新率匹配",
            "音频直通",
        )
        controls(ProfilePage.Danmaku, "弹幕", 1, "弹幕来源", "屏蔽词")
        controls(ProfilePage.Appearance, "外观与交互", 1, "主题", "视觉效果")
        controls(ProfilePage.Appearance, "外观与交互", 2, "背景图", "玻璃材质", "Logo 与开屏动画")
        controls(ProfilePage.Appearance, "外观与交互", 3, "首页与导航", "动画与动效", "辅助功能")
        controls(ProfilePage.AppearanceLayout, "首页与导航", 1, "启动进入", "自定义首页 Tab", "首页与组件管理")
        controls(ProfilePage.AppearanceLayout, "首页与导航", 2, "库页轮播图", "滚动时收起导航栏")
        controls(ProfilePage.AppearanceMotion, "动画与动效", 1, "动效主题")
        controls(
            ProfilePage.AppearanceMotion,
            "动画与动效",
            2,
            "搜索与导航动效",
            "粒子光效",
            "弹窗动画",
            "加载动画",
            "播放器进出场",
        )
        controls(ProfilePage.AppearanceAccessibility, "辅助功能", 1, "大号文字", "减少透明效果")
        controls(ProfilePage.AppearanceAccessibility, "辅助功能", 2, "减少动画")
        controls(ProfilePage.DataAndDiagnostics, "数据与诊断", 1, "自定义 User-Agent")
        controls(ProfilePage.DataAndDiagnostics, "数据与诊断", 3, "清除图片缓存", "清除视频缓存")
    }
