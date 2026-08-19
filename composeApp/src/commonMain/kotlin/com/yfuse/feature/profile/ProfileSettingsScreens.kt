package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.data.PlaybackPreferences
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.SettingTint
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackOptimizationMode
import org.koin.core.context.GlobalContext

internal data class PlaybackOptionCopy(
    val label: String,
    val summary: String,
    val description: String,
)

internal fun PlayerEngine.playbackOptionCopy(): PlaybackOptionCopy =
    when (this) {
        PlayerEngine.Exo ->
            PlaybackOptionCopy(
                label = "兼容优先（ExoPlayer）",
                summary = "兼容优先",
                description = "使用 Android Media3，适合作为默认选择",
            )
        PlayerEngine.Mpv ->
            PlaybackOptionCopy(
                label = "格式优先（MPV）",
                summary = "格式优先",
                description = "使用 libmpv，覆盖更多封装、编码与字幕格式",
            )
        PlayerEngine.Mdk ->
            PlaybackOptionCopy(
                label = "原生内核（MDK）",
                summary = "原生内核",
                description = "使用 MDK 原生播放栈，供高级兼容性选择",
            )
    }

internal fun PlaybackEngineSelection.playbackOptionCopy(): PlaybackOptionCopy =
    when (this) {
        PlaybackEngineSelection.Auto ->
            PlaybackOptionCopy(
                label = "自动选择（推荐）",
                summary = "智能自动",
                description = "按片源、设备能力、稳定性和功耗动态选择并安全切换内核",
            )
        PlaybackEngineSelection.LockExo ->
            PlayerEngine.Exo.playbackOptionCopy().copy(
                label = "系统兼容模式",
                description = "固定使用系统 Media3 内核；仅受保护内容安全规则可覆盖选择",
            )
        PlaybackEngineSelection.LockMpv ->
            PlayerEngine.Mpv.playbackOptionCopy().copy(
                label = "格式兼容模式",
                description = "固定使用 MPV 内核，适合特殊封装和字幕格式",
            )
        PlaybackEngineSelection.LockMdk ->
            PlayerEngine.Mdk.playbackOptionCopy().copy(
                label = "实验原生模式",
                description = "固定使用 MDK 原生内核，供高级兼容性排查",
            )
    }

internal fun DecoderMode.playbackOptionCopy(): PlaybackOptionCopy =
    when (this) {
        DecoderMode.Hardware ->
            PlaybackOptionCopy(
                label = "硬件优先",
                summary = "硬件优先",
                description = "优先使用设备硬件解码，通常更省电",
            )
        DecoderMode.Software ->
            PlaybackOptionCopy(
                label = "软件兼容（FFmpeg）",
                summary = "软件兼容",
                description = "使用软件解码，更耗电，但可兼容部分硬件不支持的编码",
            )
        DecoderMode.Auto ->
            PlaybackOptionCopy(
                label = "自动选择",
                summary = "自动选择",
                description = "由当前播放内核根据媒体与设备自行选择",
            )
    }

internal fun PlaybackOptimizationMode.playbackOptionCopy(): PlaybackOptionCopy =
    when (this) {
        PlaybackOptimizationMode.Balanced ->
            PlaybackOptionCopy(
                label = "自动均衡",
                summary = "自动均衡",
                description = "根据片源、设备能力和失败记录自动选择稳定省电的管线",
            )
        PlaybackOptimizationMode.PowerSaver ->
            PlaybackOptionCopy(
                label = "省电优先",
                summary = "省电优先",
                description = "优先平台硬解和直出，不支持时优先请求服务器转码",
            )
        PlaybackOptimizationMode.Quality ->
            PlaybackOptionCopy(
                label = "画质优先",
                summary = "画质优先",
                description = "优先保留 HDR，并允许原生 GPU 色调映射和高级渲染",
            )
        PlaybackOptimizationMode.Compatibility ->
            PlaybackOptionCopy(
                label = "兼容优先",
                summary = "兼容优先",
                description = "优先原生解封装和 FFmpeg 兼容管线，耗电可能增加",
            )
    }

internal fun playbackSettingsSummary(
    optimizationMode: PlaybackOptimizationMode,
    decoder: DecoderMode,
): String = "${optimizationMode.playbackOptionCopy().summary} · ${decoder.playbackOptionCopy().summary}"

internal val simplePlaybackModes =
    listOf(
        PlaybackOptimizationMode.Balanced,
        PlaybackOptimizationMode.Compatibility,
        PlaybackOptimizationMode.Quality,
    )

internal fun PlaybackOptimizationMode.simplePlaybackLabel(): String =
    when (this) {
        PlaybackOptimizationMode.Balanced -> "自动"
        PlaybackOptimizationMode.Compatibility -> "兼容优先"
        PlaybackOptimizationMode.Quality -> "画质优先"
        PlaybackOptimizationMode.PowerSaver -> "省电优先"
    }

@Composable
internal fun PlaybackSettingsScreen(
    onBack: () -> Unit,
    optimizationMode: PlaybackOptimizationMode,
    autoNext: Boolean,
    smartCrossServerSource: Boolean,
    wifiQualityCap: PlaybackQuality,
    cellularQualityCap: PlaybackQuality,
    autoQualityDowngrade: Boolean,
    qualityLocked: Boolean,
    anonymousQoeSharing: Boolean,
    resumePrompt: Boolean,
    videoCacheSize: VideoCacheSize,
    skipSegments: String,
    onPlaybackMode: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onAutoNext: (Boolean) -> Unit,
    onSmartCrossServerSource: (Boolean) -> Unit,
    onWifiQuality: () -> Unit,
    onCellularQuality: () -> Unit,
    onAutoQualityDowngrade: (Boolean) -> Unit,
    onQualityLocked: (Boolean) -> Unit,
    onAnonymousQoeSharing: (Boolean) -> Unit,
    onResumePrompt: (Boolean) -> Unit,
    onVideoCache: () -> Unit,
    onSkipSegments: () -> Unit,
) {
    SettingsPage(
        title = "播放",
        subtitle = "播放行为、网络与画质",
        onBack = onBack,
    ) {
        item {
            Section(title = "播放行为") {
                SettingsCard {
                    SwitchRow("自动播放下一集", autoNext, true, onChange = onAutoNext)
                    SettingsDivider()
                    SwitchRow(
                        "智能跨服选源",
                        smartCrossServerSource,
                        true,
                        onChange = onSmartCrossServerSource,
                    )
                    SettingsDivider()
                    SwitchRow("启动时询问继续播放", resumePrompt, true, onChange = onResumePrompt)
                    SettingsDivider()
                    SettingRow("视频缓存大小", "${videoCacheSize.label} ›", true, onVideoCache)
                    SettingsDivider()
                    SettingRow("片头片尾", skipSegments, true, onSkipSegments)
                }
            }
        }
        item {
            Section(title = "网络感知画质") {
                SettingsCard {
                    SettingRow("Wi-Fi 画质上限", "${wifiQualityCap.label} ›", true, onWifiQuality)
                    SettingsDivider()
                    SettingRow(
                        "蜂窝网络画质上限",
                        "${cellularQualityCap.label} ›",
                        true,
                        onCellularQuality,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "卡顿后自动降档",
                        autoQualityDowngrade,
                        true,
                        onChange = onAutoQualityDowngrade,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "锁定手动画质",
                        qualityLocked,
                        true,
                        onChange = onQualityLocked,
                    )
                }
            }
        }
        item {
            Section(title = "播放模式") {
                SettingsCard {
                    SettingRow(
                        "播放模式",
                        "${optimizationMode.simplePlaybackLabel()} ›",
                        true,
                        onPlaybackMode,
                    )
                }
            }
        }
        item {
            Section(title = "高级") {
                SettingsCard {
                    SettingRow(
                        "高级播放设置",
                        "内核、解码与音视频输出 ›",
                        true,
                        onOpenAdvanced,
                    )
                }
            }
        }
        item {
            Section(title = "隐私") {
                SettingsCard {
                    SwitchRow(
                        "匿名播放质量分享",
                        anonymousQoeSharing,
                        true,
                        onChange = onAnonymousQoeSharing,
                    )
                }
            }
        }
    }
}

@Composable
internal fun AdvancedPlaybackSettingsScreen(
    onBack: () -> Unit,
    optimizationMode: PlaybackOptimizationMode,
    engineSelection: PlaybackEngineSelection,
    decoder: DecoderMode,
    onOptimizationMode: () -> Unit,
    onEngine: () -> Unit,
    onDecoder: () -> Unit,
) {
    val outputPreferences = remember { GlobalContext.get().get<PlaybackPreferences>() }
    val frameRateMatch by outputPreferences.frameRateMatch.collectAsState()
    val audioPassthrough by outputPreferences.audioPassthrough.collectAsState()
    val core2TrialEnabled by outputPreferences.core2TrialEnabled.collectAsState()

    SettingsPage(
        title = "高级播放设置",
        subtitle = "内核锁定、解码与设备输出",
        onBack = onBack,
    ) {
        item {
            Section(title = "策略与内核") {
                SettingsCard {
                    SettingRow(
                        "YCore 播放策略",
                        "${optimizationMode.playbackOptionCopy().label} ›",
                        true,
                        onOptimizationMode,
                    )
                    SettingsDivider()
                    SettingRow(
                        "播放内核",
                        "${engineSelection.playbackOptionCopy().label} ›",
                        true,
                        onEngine,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "YCore 2.0 试用",
                        core2TrialEnabled,
                        true,
                        onChange = outputPreferences::setCore2TrialEnabled,
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
        item {
            Section(title = "显示与音频输出") {
                SettingsCard {
                    if (engineSelection == PlaybackEngineSelection.LockMdk) {
                        SettingRow("刷新率匹配", "MDK 暂不支持", false, {})
                    } else {
                        SettingSegmentRow(
                            title = "刷新率匹配",
                            options = listOf("关闭", "仅无缝", "始终"),
                            selectedIndex = PlaybackFrameRateMatch.entries.indexOf(frameRateMatch),
                            onSelect = { outputPreferences.setFrameRateMatch(PlaybackFrameRateMatch.entries[it]) },
                            icon = AppIcons.Refresh,
                            iconTint = SettingTint.advanced,
                        )
                    }
                    SettingsDivider()
                    if (engineSelection == PlaybackEngineSelection.LockMdk) {
                        SettingRow("音频直通", "MDK 暂不支持", false, {})
                    } else {
                        SettingSegmentRow(
                            title = "音频直通",
                            options = listOf("关闭", "兼容"),
                            selectedIndex = PlaybackAudioPassthrough.entries.indexOf(audioPassthrough),
                            onSelect = {
                                outputPreferences.setAudioPassthrough(PlaybackAudioPassthrough.entries[it])
                            },
                            icon = AppIcons.Volume,
                            iconTint = SettingTint.advanced,
                        )
                    }
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
                    SwitchRow("聊天弹幕", chatDanmaku, true, onChange = onChatDanmaku)
                    SettingsDivider()
                    SwitchRow("聊天消息浮层", chatPreview, true, onChange = onChatPreview)
                }
            }
        }
    }
}

@Composable
internal fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    brandSummary: String,
    backgroundSummary: String,
    startupSummary: String,
    reduceTransparency: Boolean,
    largeText: Boolean,
    reduceMotion: Boolean,
    onBackground: () -> Unit,
    onBrand: () -> Unit,
    onStartupTab: () -> Unit,
    onReduceTransparency: (Boolean) -> Unit,
    onLargeText: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
) {
    SettingsPage(
        title = "外观与辅助",
        subtitle = "背景、启动与辅助显示",
        onBack = onBack,
    ) {
        item {
            Section(title = "外观") {
                SettingsCard {
                    SettingRow(
                        "Logo 与开屏动画",
                        brandSummary,
                        true,
                        onBrand,
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
                        icon = AppIcons.Subtitle,
                        iconTint = SettingTint.subtitle,
                        onChange = onReduceTransparency,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "大号文字",
                        largeText,
                        true,
                        icon = AppIcons.Info,
                        iconTint = SettingTint.language,
                        onChange = onLargeText,
                    )
                    SettingsDivider()
                    SwitchRow(
                        "减少动画",
                        reduceMotion,
                        true,
                        icon = AppIcons.Refresh,
                        iconTint = SettingTint.advanced,
                        onChange = onReduceMotion,
                    )
                }
            }
        }
    }
}
