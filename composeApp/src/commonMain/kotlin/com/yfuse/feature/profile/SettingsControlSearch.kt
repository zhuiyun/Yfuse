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
                        "详情页主题曲", "音频直通" -> AppIcons.Volume to SettingTint.audio
                        "智能跨服选源" -> AppIcons.PlaybackSource to SettingTint.servers
                        "片头片尾" -> AppIcons.SkipMarkers to SettingTint.playback
                        "视频版本偏好", "显示帧率" -> AppIcons.Movie to SettingTint.playback
                        "Anime4K · YCore SDR 动漫（下次播放生效）" -> AppIcons.Star to SettingTint.playback
                        "刷新率匹配" -> AppIcons.Refresh to SettingTint.advanced
                        "YCore 2.0 播放内核（仅自动模式）", "YCore Native 纯内核（自动模式/开发中）" -> AppIcons.Play to SettingTint.playback
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
                                "播放内核" -> "播放器 引擎 MDK MPV YCore2 YCore 2.0 Native 原生 纯内核 自动模式"
                                "YCore 2.0 播放内核（仅自动模式）" -> "YCore2 YCore 2.0 自动模式"
                                "YCore Native 纯内核（自动模式/开发中）" -> "YCore2 Native 原生 纯内核 自动模式"
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
        controls(ProfilePage.Playback, "播放", 1, "进度同步")
        controls(
            ProfilePage.Playback,
            "播放",
            2,
            "自动播放下一集",
            "竖屏视频",
            "默认倍速",
            "详情页主题曲",
            "智能跨服选源",
            "视频缓存大小",
            "起播预热",
            "片头片尾",
        )
        controls(ProfilePage.Playback, "播放", 3, "视频版本偏好", "播放模式")
        controls(
            ProfilePage.AdvancedPlayback,
            "高级播放设置",
            1,
            "YCore 播放策略",
            "播放内核",
            "YCore 2.0 播放内核（仅自动模式）",
            "YCore Native 纯内核（自动模式/开发中）",
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
        controls(
            ProfilePage.Appearance,
            "外观与辅助",
            1,
            "库页轮播图",
            "自定义首页 Tab",
            "首页与组件管理",
            "滚动时收起导航栏",
            "玻璃材质",
            "Logo 与开屏动画",
            "背景图",
            "启动进入",
        )
        controls(ProfilePage.Appearance, "外观与辅助", 2, "动效主题")
        controls(ProfilePage.Appearance, "外观与辅助", 3, "搜索与导航动效", "粒子光效", "弹窗动画", "加载动画", "播放器进出场")
        controls(ProfilePage.Appearance, "外观与辅助", 4, "减少透明效果", "大号文字", "减少动画")
    }
