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
                add(
                    SettingsSearchDestination(
                        label,
                        "$pageTitle · 定位到设置项",
                        label + " " +
                            when (label) {
                                "音频直通" -> "音频输出 透传 passthrough"
                                "播放内核" -> "播放器 引擎 MDK MPV"
                                "大号文字" -> "字体 字号 字体大小"
                                "屏蔽词" -> "屏蔽关键词 弹幕过滤"
                                else -> ""
                            },
                        page,
                        anchor = SettingsControlAnchor(pageTitle, section, label),
                        icon = AppIcons.SearchTab,
                        tint = SettingTint.general,
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
        controls(ProfilePage.AdvancedPlayback, "高级播放设置", 1, "YCore 播放策略", "播放内核", "解码方式", "YCore 前向缓存时长")
        controls(ProfilePage.AdvancedPlayback, "高级播放设置", 2, "显示帧率", "音频直通")
        controls(ProfilePage.Danmaku, "弹幕", 1, "弹幕来源", "屏蔽词")
        controls(ProfilePage.Appearance, "外观与辅助", 1, "库页轮播图", "滚动时收起导航栏", "玻璃材质", "Logo 与开屏动画", "背景图", "启动进入")
        controls(ProfilePage.Appearance, "外观与辅助", 2, "动效主题")
        controls(ProfilePage.Appearance, "外观与辅助", 3, "搜索与导航动效", "粒子光效", "弹窗动画", "加载动画", "播放器进出场")
        controls(ProfilePage.Appearance, "外观与辅助", 4, "减少透明效果", "大号文字", "减少动画")
    }
