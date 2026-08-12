package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassStyle
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

internal data class PlaybackOptionCopy(
    val label: String,
    val summary: String,
    val description: String,
)

internal fun PlayerEngine.playbackOptionCopy(): PlaybackOptionCopy = when (this) {
    PlayerEngine.Exo -> PlaybackOptionCopy(
        label = "兼容优先（ExoPlayer）",
        summary = "兼容优先",
        description = "使用 Android Media3，适合作为默认选择",
    )
    PlayerEngine.Mpv -> PlaybackOptionCopy(
        label = "格式优先（MPV）",
        summary = "格式优先",
        description = "使用 libmpv，覆盖更多封装、编码与字幕格式",
    )
    PlayerEngine.Mdk -> PlaybackOptionCopy(
        label = "原生内核（MDK）",
        summary = "原生内核",
        description = "使用 MDK 原生播放栈，供高级兼容性选择",
    )
}

internal fun DecoderMode.playbackOptionCopy(): PlaybackOptionCopy = when (this) {
    DecoderMode.Hardware -> PlaybackOptionCopy(
        label = "硬件优先",
        summary = "硬件优先",
        description = "优先使用设备硬件解码，通常更省电",
    )
    DecoderMode.Software -> PlaybackOptionCopy(
        label = "软件兼容（FFmpeg）",
        summary = "软件兼容",
        description = "使用软件解码，更耗电，但可兼容部分硬件不支持的编码",
    )
    DecoderMode.Auto -> PlaybackOptionCopy(
        label = "自动选择",
        summary = "自动选择",
        description = "由当前播放内核根据媒体与设备自行选择",
    )
}

internal fun playbackSettingsSummary(engine: PlayerEngine, decoder: DecoderMode): String =
    "${engine.playbackOptionCopy().summary} · ${decoder.playbackOptionCopy().summary}"

@Composable
internal fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    engine: PlayerEngine,
    decoder: DecoderMode,
    autoNext: Boolean,
    videoCacheSize: VideoCacheSize,
    skipSegments: String,
    onEngine: () -> Unit,
    onDecoder: () -> Unit,
    onAutoNext: (Boolean) -> Unit,
    onVideoCache: () -> Unit,
    onSkipSegments: () -> Unit,
) {
    SettingsPage(
        title = "播放",
        subtitle = "播放行为与高级兼容选项",
        onBack = onBack,
    ) {
        item {
            Section(title = "播放行为") {
                SettingsCard {
                    SwitchRow("自动播放下一集", autoNext, true, onAutoNext)
                    SettingsDivider()
                    SettingRow("视频缓存大小", "${videoCacheSize.label} ›", true, onVideoCache)
                    SettingsDivider()
                    SettingRow("片头片尾", skipSegments, true, onSkipSegments)
                }
            }
        }
        item {
            Section(title = "高级播放内核") {
                SettingsCard {
                    SettingRow(
                        "播放内核",
                        "${engine.playbackOptionCopy().label} ›",
                        true,
                        onEngine,
                    )
                    SettingsDivider()
                    SettingRow(
                        "解码方式",
                        "${decoder.playbackOptionCopy().label} ›",
                        true,
                        onDecoder,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DanmakuSettingsScreen(
    onBack: () -> Unit,
    sourceSummary: String,
    blockedSummary: String,
    onSources: () -> Unit,
    onBlockedWords: () -> Unit,
) {
    SettingsPage(
        title = "弹幕",
        subtitle = "来源与内容过滤",
        onBack = onBack,
    ) {
        item {
            Section(title = "弹幕设置") {
                SettingsCard {
                    SettingRow("弹幕来源", sourceSummary, true, onSources)
                    SettingsDivider()
                    SettingRow("屏蔽词", blockedSummary, true, onBlockedWords)
                }
            }
        }
    }
}

@Composable
internal fun WatchTogetherSettingsScreen(
    onBack: () -> Unit,
    connected: Boolean,
    roomCode: String?,
    nickname: String,
    chatDanmaku: Boolean,
    chatPreview: Boolean,
    onJoin: () -> Unit,
    onProfile: () -> Unit,
    onChatDanmaku: (Boolean) -> Unit,
    onChatPreview: (Boolean) -> Unit,
) {
    SettingsPage(
        title = "一起看",
        subtitle = "房间、资料与聊天显示",
        onBack = onBack,
    ) {
        item {
            Section(title = "房间") {
                SettingsCard {
                    SettingRow(
                        if (connected) "当前房间" else "加入房间",
                        if (connected) "房间 ${roomCode.orEmpty()} · 查看 ›" else "输入房间码 ›",
                        true,
                        onJoin,
                    )
                    SettingsDivider()
                    SettingRow("一起看资料", "$nickname ›", true, onProfile)
                }
            }
        }
        item {
            Section(title = "聊天显示") {
                SettingsCard {
                    SwitchRow("聊天弹幕", chatDanmaku, true, onChatDanmaku)
                    SettingsDivider()
                    SwitchRow("聊天消息浮层", chatPreview, true, onChatPreview)
                }
            }
        }
    }
}

@Composable
internal fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    mode: ThemeMode,
    accent: AccentColor,
    glassStyle: GlassStyle,
    appIconSummary: String,
    backgroundSummary: String,
    splashSummary: String,
    startupSummary: String,
    reduceTransparency: Boolean,
    largeText: Boolean,
    reduceMotion: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onAccent: (AccentColor) -> Unit,
    onGlassStyle: (GlassStyle) -> Unit,
    onAppIcon: () -> Unit,
    onBackground: () -> Unit,
    onSplash: () -> Unit,
    onStartupTab: () -> Unit,
    onReduceTransparency: (Boolean) -> Unit,
    onLargeText: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
) {
    SettingsPage(
        title = "外观与辅助",
        subtitle = "主题、颜色与辅助显示",
        onBack = onBack,
    ) {
        item {
            Section(title = "外观") {
                SettingsCard {
                    // 浅色 / 跟随系统 / 深色, answered where it is asked. The enum's own order
                    // puts 跟随系统 first; the control reads light-to-dark, which is the order
                    // the two end states suggest and the one every other app uses.
                    SettingSegmentRow(
                        title = "主题",
                        options = ThemeModeOrder.map { it.label },
                        selectedIndex = ThemeModeOrder.indexOf(mode).coerceAtLeast(0),
                        onSelect = { onThemeMode(ThemeModeOrder[it]) },
                        icon = AppIcons.Cloud,
                        iconTint = SettingTint.appearance,
                    )
                    SettingsDivider()
                    AccentPickerRow(selected = accent, onSelect = onAccent)
                    SettingsDivider()
                    SettingSegmentRow(
                        title = "视觉效果",
                        options = GlassStyle.entries.map { it.label },
                        selectedIndex = GlassStyle.entries.indexOf(glassStyle),
                        onSelect = { onGlassStyle(GlassStyle.entries[it]) },
                        icon = AppIcons.Grid,
                        iconTint = SettingTint.components,
                    )
                    SettingsDivider()
                    SettingRow(
                        "APP 图标",
                        appIconSummary,
                        true,
                        onAppIcon,
                        icon = AppIcons.Bookmark,
                        iconTint = SettingTint.components,
                    )
                    SettingsDivider()
                    SettingRow(
                        "背景图",
                        backgroundSummary,
                        true,
                        onBackground,
                        icon = AppIcons.Download,
                        iconTint = SettingTint.library,
                    )
                    SettingsDivider()
                    SettingRow(
                        "开屏动画",
                        splashSummary,
                        true,
                        onSplash,
                        icon = AppIcons.Play,
                        iconTint = SettingTint.playback,
                    )
                    SettingsDivider()
                    // Which tab a cold start lands on. It sits under 外观 rather than in a
                    // section of its own because it is the same kind of choice as the
                    // theme: what the app looks like the moment it opens.
                    SettingRow(
                        "启动进入",
                        startupSummary,
                        true,
                        onStartupTab,
                        icon = AppIcons.Home,
                        iconTint = SettingTint.general,
                    )
                }
            }
        }
        item {
            Section(title = "辅助功能") {
                SettingsCard {
                    SwitchRow(
                        "减少透明效果",
                        reduceTransparency,
                        true,
                        onReduceTransparency,
                        icon = AppIcons.Subtitle,
                        iconTint = SettingTint.subtitle,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "大号文字",
                        largeText,
                        true,
                        onLargeText,
                        icon = AppIcons.Info,
                        iconTint = SettingTint.language,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "减少动画",
                        reduceMotion,
                        true,
                        onReduceMotion,
                        icon = AppIcons.Refresh,
                        iconTint = SettingTint.advanced,
                    )
                }
            }
        }
    }
}

/**
 * Light to dark, which is how the control is read left to right. [ThemeMode]'s declaration
 * order leads with 跟随系统 and cannot be changed — it is persisted by name.
 */
private val ThemeModeOrder = listOf(ThemeMode.Light, ThemeMode.System, ThemeMode.Dark)
