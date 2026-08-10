package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine

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
        subtitle = "播放器、解码与播放行为",
        onBack = onBack,
    ) {
        item {
            Section(title = "播放体验") {
                SettingsCard {
                    SettingRow("默认播放器内核", "${engine.label} ›", true, onEngine)
                    SettingsDivider()
                    SettingRow("解码内核", "${decoder.label} ›", true, onDecoder)
                    SettingsDivider()
                    SwitchRow("自动播放下一集", autoNext, true, onAutoNext)
                    SettingsDivider()
                    SettingRow("视频缓存大小", "${videoCacheSize.label} ›", true, onVideoCache)
                    SettingsDivider()
                    SettingRow("片头片尾", skipSegments, true, onSkipSegments)
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
    splashSummary: String,
    reduceTransparency: Boolean,
    largeText: Boolean,
    reduceMotion: Boolean,
    onThemeMode: () -> Unit,
    onAccent: () -> Unit,
    onSplash: () -> Unit,
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
                    SettingRow("主题模式", "${mode.label} ›", true, onThemeMode)
                    SettingsDivider()
                    SettingRow("强调色", "${accent.label}色 ›", true, onAccent)
                    SettingsDivider()
                    SettingRow("开屏动画", splashSummary, true, onSplash)
                }
            }
        }
        item {
            Section(title = "辅助功能") {
                SettingsCard {
                    SwitchRow("减少透明效果", reduceTransparency, true, onReduceTransparency)
                    SettingsDivider()
                    SwitchRow("大号文字", largeText, true, onLargeText)
                    SettingsDivider()
                    SwitchRow("减少动画", reduceMotion, true, onReduceMotion)
                }
            }
        }
    }
}
