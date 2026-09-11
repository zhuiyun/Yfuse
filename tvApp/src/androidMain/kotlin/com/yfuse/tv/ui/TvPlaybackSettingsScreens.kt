package com.yfuse.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.FocusRequester
import com.yfuse.core.data.MediaVersionPreference
import com.yfuse.core.data.PlaybackAudioPassthrough
import com.yfuse.core.data.PlaybackFrameRateMatch
import com.yfuse.core.data.SkipMode
import com.yfuse.core.data.VideoCacheSize
import com.yfuse.core.data.YCoreBufferDuration
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.feature.profile.ProfileComponent
import com.yfuse.feature.profile.playbackOptionCopy

/**
 * Playback behavior a viewer changes between sessions.
 *
 * Server transcoding is deliberately absent. TV playback is client-owned, and
 * `withoutServerTranscodeForTv` strips every transcode address before the player sees an item, so
 * offering a transcode preference here would present a control that cannot take effect.
 */
@Composable
internal fun TvPlaybackSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:playback"
    val syncManager = component.dependencies.serverSyncManager
    val progressSync by syncManager.syncProgress.collectAsState()
    val skipMode by component.skipSegmentPreferences.skipMode.collectAsState()
    val versionPreference by component.playbackPreferences.mediaVersionPreference.collectAsState()
    val smartSource by component.playbackPreferences.smartCrossServerSource.collectAsState()
    val qoeSharing by component.playbackPreferences.anonymousQoeSharing.collectAsState()

    TvSettingsPageScaffold(page = TvSettingsPage.Playback) {
        item(key = "playback-section-progress") { TvSettingsSectionTitle("播放进度") }
        item(key = "playback-progress-sync") {
            TvToggleRow(
                title = "进度同步",
                checked = progressSync,
                stableId = "playback:progress-sync",
                focusMemory = focusMemory,
                onToggle = syncManager::setProgress,
                icon = AppIcons.Refresh,
                focusScope = focusScope,
                subtitle =
                    if (progressSync) {
                        "同步到 Emby/Jellyfin 与 Yfuse 云端，支持跨设备续播"
                    } else {
                        "仅保留本机进度，不向服务器或云端上报"
                    },
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }

        item(key = "playback-section-behavior") { TvSettingsSectionTitle("播放行为") }
        item(key = "playback-skip-mode") {
            TvChoiceRow(
                title = "片头片尾",
                options = SkipMode.entries,
                selected = skipMode,
                label = { it.label },
                stableId = "playback:skip-mode",
                focusMemory = focusMemory,
                onSelect = component.skipSegmentPreferences::setSkipMode,
                icon = AppIcons.SkipMarkers,
                focusScope = focusScope,
                subtitle = "服务器未分析的剧集可以在播放器里手动标记时间",
                navigationRequester = navigationRequester,
            )
        }

        item(key = "playback-section-source") { TvSettingsSectionTitle("片源选择") }
        item(key = "playback-version-preference") {
            TvChoiceRow(
                title = "视频版本偏好",
                options = MediaVersionPreference.entries,
                selected = versionPreference,
                label = { it.playbackOptionCopy().label },
                stableId = "playback:version-preference",
                focusMemory = focusMemory,
                onSelect = component.playbackPreferences::setMediaVersionPreference,
                icon = AppIcons.PlaybackSource,
                focusScope = focusScope,
                subtitle = versionPreference.playbackOptionCopy().description,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "playback-smart-source") {
            TvToggleRow(
                title = "跨服务器智能选源",
                checked = smartSource,
                stableId = "playback:smart-source",
                focusMemory = focusMemory,
                onToggle = component.playbackPreferences::setSmartCrossServerSource,
                icon = AppIcons.Server,
                focusScope = focusScope,
                subtitle = "同一部片在多台服务器上时，自动选择可直连且画质更好的一台",
                navigationRequester = navigationRequester,
            )
        }

        item(key = "playback-section-privacy") { TvSettingsSectionTitle("隐私") }
        item(key = "playback-qoe") {
            TvToggleRow(
                title = "匿名播放质量反馈",
                checked = qoeSharing,
                stableId = "playback:qoe",
                focusMemory = focusMemory,
                onToggle = component.playbackPreferences::setAnonymousQoeSharing,
                icon = AppIcons.Info,
                focusScope = focusScope,
                subtitle = "只上报卡顿与解码统计，不包含片名、账号或服务器地址",
                navigationRequester = navigationRequester,
            )
        }
    }
}

/** Kernel, decoder and device-output controls. Wrong values here break playback, so each says why. */
@Composable
internal fun TvAdvancedPlaybackSettingsPage(
    component: ProfileComponent,
    focusMemory: TvUiFocusMemory,
    navigationRequester: FocusRequester,
    firstRowRequester: FocusRequester,
) {
    val focusScope = "settings:advanced-playback"
    val optimizationMode by component.playbackPreferences.optimizationMode.collectAsState()
    val engineSelection by component.playbackPreferences.engineSelection.collectAsState()
    val decoder by component.themePreferences.decoder.collectAsState()
    val bufferDuration by component.playbackPreferences.yCoreBufferDuration.collectAsState()
    val videoCacheSize by component.playbackPreferences.videoCacheSize.collectAsState()
    val frameRateMatch by component.playbackPreferences.frameRateMatch.collectAsState()
    val passthrough by component.playbackPreferences.audioPassthrough.collectAsState()

    TvSettingsPageScaffold(page = TvSettingsPage.AdvancedPlayback) {
        item(key = "advanced-section-kernel") { TvSettingsSectionTitle("策略与内核") }
        item(key = "advanced-optimization") {
            TvChoiceRow(
                title = "YCore 播放策略",
                options = PlaybackOptimizationMode.entries,
                selected = optimizationMode,
                label = { it.playbackOptionCopy().label },
                stableId = "advanced:optimization",
                focusMemory = focusMemory,
                onSelect = component.playbackPreferences::setOptimizationMode,
                icon = AppIcons.PlaybackSource,
                focusScope = focusScope,
                subtitle = optimizationMode.playbackOptionCopy().description,
                focusRequester = firstRowRequester,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "advanced-engine") {
            TvChoiceRow(
                title = "高级内核选择",
                options = PlaybackEngineSelection.entries,
                selected = engineSelection,
                label = { it.playbackOptionCopy().label },
                stableId = "advanced:engine",
                focusMemory = focusMemory,
                onSelect = component.playbackPreferences::setEngineSelection,
                icon = AppIcons.Movie,
                focusScope = focusScope,
                subtitle = engineSelection.playbackOptionCopy().description,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "advanced-decoder") {
            TvChoiceRow(
                title = "解码方式",
                options = DecoderMode.entries,
                selected = decoder,
                label = { it.playbackOptionCopy().label },
                stableId = "advanced:decoder",
                focusMemory = focusMemory,
                onSelect = component.themePreferences::setDecoder,
                icon = AppIcons.Menu,
                focusScope = focusScope,
                subtitle = decoder.playbackOptionCopy().description,
                navigationRequester = navigationRequester,
            )
        }
        item(key = "advanced-buffer") {
            TvChoiceRow(
                title = "YCore 缓冲时长",
                options = YCoreBufferDuration.entries,
                selected = bufferDuration,
                label = { it.label },
                stableId = "advanced:buffer",
                focusMemory = focusMemory,
                onSelect = component.playbackPreferences::setYCoreBufferDuration,
                icon = AppIcons.SeekForward10,
                focusScope = focusScope,
                subtitle = "更长的缓冲更抗网络抖动，但起播和拖动后的等待会变长",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "advanced-video-cache") {
            TvChoiceRow(
                title = "视频缓存大小",
                options = VideoCacheSize.entries,
                selected = videoCacheSize,
                label = { it.label },
                stableId = "advanced:video-cache",
                focusMemory = focusMemory,
                onSelect = component.playbackPreferences::setVideoCacheSize,
                icon = AppIcons.Download,
                focusScope = focusScope,
                subtitle = "缓存已播放的数据，减少回看与网络抖动造成的卡顿",
                navigationRequester = navigationRequester,
            )
        }

        item(key = "advanced-section-output") { TvSettingsSectionTitle("显示与音频输出") }
        item(key = "advanced-frame-rate") {
            TvChoiceRow(
                title = "匹配内容帧率",
                options = PlaybackFrameRateMatch.entries,
                selected = frameRateMatch,
                label = { it.tvLabel() },
                stableId = "advanced:frame-rate",
                focusMemory = focusMemory,
                onSelect = component.playbackPreferences::setFrameRateMatch,
                icon = AppIcons.Refresh,
                focusScope = focusScope,
                subtitle = "让电视切换到片源的原生帧率，消除 24p 内容的抖动",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "advanced-passthrough") {
            TvToggleRow(
                title = "兼容音频直通",
                checked = passthrough == PlaybackAudioPassthrough.Compatible,
                stableId = "advanced:passthrough",
                focusMemory = focusMemory,
                onToggle = { enabled ->
                    component.playbackPreferences.setAudioPassthrough(
                        if (enabled) PlaybackAudioPassthrough.Compatible else PlaybackAudioPassthrough.Disabled,
                    )
                },
                icon = AppIcons.AudioTrack,
                focusScope = focusScope,
                subtitle = "把编码音轨原样送给功放。功放不支持时仍会自动解码，不会静音",
                navigationRequester = navigationRequester,
            )
        }
        item(key = "advanced-output-note") {
            TvSettingsNote(
                "直通与杜比视界是否真的生效，取决于电视和功放的实际能力。播放器的诊断面板会显示本次播放的真实输出链路。",
            )
        }
    }
}

private fun PlaybackFrameRateMatch.tvLabel(): String =
    when (this) {
        PlaybackFrameRateMatch.Disabled -> "关闭"
        PlaybackFrameRateMatch.SeamlessOnly -> "仅无缝切换"
        PlaybackFrameRateMatch.Always -> "始终匹配"
    }
