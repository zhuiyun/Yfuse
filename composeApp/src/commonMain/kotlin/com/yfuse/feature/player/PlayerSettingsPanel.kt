package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yfuse.core.data.SkipMode
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.motionAwareAnimateContentSize
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Single-purpose playback popups. The playback page chooses one kind per button; there is
 * deliberately no tab strip that can turn the popup back into a combined settings drawer.
 *
 * Split out of `PlayerControls` because it is a different kind of thing: the controls are a
 * layer over the picture that has to stay out of the way, this is a list of choices that
 * only exists once someone has asked for it. Nothing here runs while the film plays.
 */

internal enum class SettingsPanelKind {
    Tracks,
    Danmaku,
    Cast,
    Skip,
    More,
}

/** The playback page exposes subtitle and audio as two independent controls. */
internal enum class TrackPanelMode {
    Subtitle,
    Audio,
}

private enum class AdvancedPage {
    Root,
    Playback,
    Engine,
    Media,
    Bookmarks,
}

/** Compact function popup; long choices scroll inside without turning into a screen drawer. */
@Composable
internal fun SettingsPanel(
    kind: SettingsPanelKind,
    /** The runtime projection: tracks, versions, durations — everything that is not a clock. */
    state: PlaybackState,
    /**
     * The live timeline, read by the two pages that genuinely need one.
     *
     * 媒体信息 is a readout of telemetry, and 播放设置's 使用当前时间 is a question asked at the
     * moment of the tap. Both used to be served by handing the whole panel the live state, which
     * put every list, every chip and twenty-five lines of diagnostics formatting on the position
     * tick while the panel was open. This is read inside those two places and nowhere else.
     */
    playback: State<PlaybackState>,
    containerLabel: String?,
    engineOptions: List<Pair<String, Boolean>>,
    transcodeLabel: String?,
    transcodeActive: Boolean,
    castDevices: List<Pair<String, String>>,
    castingDeviceId: String?,
    castDiscovering: Boolean,
    castError: String?,
    castStatus: String?,
    castPosition: String?,
    /** The receiver's own clock, resolved inside 投屏 rather than by whoever opened the panel. */
    castPositionSource: (() -> String?)?,
    castCapabilities: String?,
    danmaku: DanmakuPanelState,
    danmakuActions: DanmakuPanelActions,
    onOpenDanmakuSearch: () -> Unit,
    onOpenDanmakuSend: () -> Unit,
    onSelectSubtitle: (String) -> Unit,
    subtitleControls: SubtitleControlState,
    subtitleActions: SubtitleControlActions,
    remoteSubtitles: RemoteSubtitlePanelState,
    remoteSubtitleActions: RemoteSubtitleActions,
    audioControls: AudioControlState,
    audioActions: AudioControlActions,
    onSelectAudio: (String) -> Unit,
    sleepTimer: SleepTimerState,
    sleepTimerActions: SleepTimerActions,
    onSelectEngine: (Int) -> Unit,
    onTranscode: () -> Unit,
    onResetAdaptiveLearning: () -> Unit,
    onNextDiscTitle: () -> Unit,
    onNextDiscChapter: () -> Unit,
    onShowDiscMenu: () -> Unit,
    onExternalPlayer: (() -> Unit)?,
    onDiscoverCast: () -> Unit,
    onCastTo: (String) -> Unit,
    onStopCast: () -> Unit,
    onLock: () -> Unit,
    onOpenGestureHelp: () -> Unit,
    watch: WatchRoomState,
    onOpenWatchTogether: () -> Unit,
    versions: List<Pair<String, String>>,
    selectedVersionId: String?,
    onSelectVersion: (String) -> Unit,
    skip: SkipSegmentState,
    skipActions: SkipSegmentActions,
    trackPanelMode: TrackPanelMode = TrackPanelMode.Subtitle,
    ambientLightEnabled: Boolean = true,
    bookmarks: PlaybackBookmarkPanelState = PlaybackBookmarkPanelState(),
    bookmarkActions: PlaybackBookmarkActions = PlaybackBookmarkActions(),
    onToggleAmbientLight: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedPage by remember(kind) { mutableStateOf(AdvancedPage.Root) }
    val discNavigationRevision by ActiveDiscNavigation.revision.collectAsState()

    PlayerPopupPanel(onDismiss = onDismiss, modifier = modifier) {
        // Two transitions, because this shell has two axes of change: one popup kind hands
        // over to another, and 更多 goes a level deeper and comes back. The travel is resolved
        // to pixels up here because a transition spec is not a composable and cannot read
        // the density itself.
        val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
        val density = LocalDensity.current
        val pushTravelPx = with(density) { Motion.pushOffset.roundToPx() }
        val popTravelPx = with(density) { Motion.popOffset.roundToPx() }
        // The list takes whatever height the drawer has left instead of the old fixed
        // 210dp window, which scrolled a short slot inside a mostly empty screen.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsKindHost(kind = kind, reduceMotion = reduceMotion) { currentKind ->
                when (currentKind) {
                    SettingsPanelKind.Danmaku ->
                        DanmakuTab(
                            state = danmaku,
                            actions = danmakuActions,
                            onOpenSearch = overlayAction(onOpenDanmakuSearch),
                            onOpenSend = overlayAction(onOpenDanmakuSend),
                        )

                    SettingsPanelKind.Tracks -> {
                        if (
                            trackPanelMode == TrackPanelMode.Subtitle &&
                            state.subtitleTracks.isNotEmpty()
                        ) {
                            if (subtitleControls.secondarySupported) {
                                GroupLabel("双字幕方案 · 主上副下")
                                DualSubtitleLanguagePair.entries.forEach { pair ->
                                    OptionRow(
                                        pair.label,
                                        selected = false,
                                        onClick = { subtitleActions.onLanguagePair(pair) },
                                    )
                                }
                                if (
                                    subtitleControls.secondaryTrackId != null &&
                                    state.subtitleTracks.any { it.selected }
                                ) {
                                    OptionRow("互换主副字幕", selected = false, onClick = subtitleActions.onSwap)
                                }
                                if (subtitleControls.independentScaleAvailable) {
                                    GroupLabel("副字幕字号")
                                    listOf(0.8f, 1f, 1.2f, 1.5f).forEach { scale ->
                                        OptionRow(
                                            "${(scale * 100).toInt()}%",
                                            subtitleControls.secondaryScale == scale,
                                            onClick = { subtitleActions.onSecondaryScale(scale) },
                                        )
                                    }
                                }
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                    horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                ) {
                                    Text("主字幕预览", color = Color.White, fontSize = (18 * subtitleControls.scale).sp)
                                    Spacer(Modifier.size(4.dp))
                                    Text(
                                        "Secondary subtitle",
                                        color = Color.White,
                                        fontSize = (18 * subtitleControls.secondaryScale).sp,
                                    )
                                }
                                GroupLabel("主字幕")
                            }
                            OptionRow(
                                "关闭",
                                state.subtitleTracks.none { it.selected },
                                onClick = { onSelectSubtitle(EngineTrack.OFF) },
                            )
                            state.subtitleTracks.forEach { track ->
                                OptionRow(track.label, track.selected, onClick = { onSelectSubtitle(track.id) })
                            }
                            GroupLabel("副字幕")
                            subtitleControls.dualLayoutNote?.let { UnsupportedSubtitleControl(it) }
                            if (subtitleControls.secondarySupported) {
                                OptionRow(
                                    "关闭",
                                    subtitleControls.secondaryTrackId == null,
                                    onClick = { subtitleActions.onSecondaryTrack(EngineTrack.OFF) },
                                )
                                state.subtitleTracks.forEach { track ->
                                    OptionRow(
                                        track.label,
                                        subtitleControls.secondaryTrackId == track.id,
                                        onClick = { subtitleActions.onSecondaryTrack(track.id) },
                                    )
                                }
                            } else {
                                Text(
                                    subtitleControls.secondaryUnavailableReason
                                        ?: "当前播放器内核不支持双字幕。",
                                    style = AppTypography.caption.medium,
                                    color = Color.White.copy(alpha = 0.68f),
                                )
                            }
                            if (
                                subtitleControls.secondaryOffsetAvailable &&
                                subtitleControls.secondaryTrackId != null
                            ) {
                                GroupLabel("副字幕时间偏移")
                                listOf(-5000L, -2000L, -1000L, 0L, 1000L, 2000L, 5000L).forEach { offset ->
                                    val label =
                                        when {
                                            offset < 0L -> "提前 ${-offset / 1000} 秒"
                                            offset > 0L -> "延后 ${offset / 1000} 秒"
                                            else -> "同步"
                                        }
                                    OptionRow(
                                        label,
                                        subtitleControls.secondaryOffsetMs == offset,
                                        onClick = { subtitleActions.onSecondaryOffset(offset) },
                                    )
                                }
                            }
                            GroupLabel("字幕样式")
                            if (
                                subtitleControls.scaleAvailable &&
                                subtitleControls.brightnessAvailable &&
                                subtitleControls.positionAvailable
                            ) {
                                SubtitleStylePreset.entries
                                    .filterNot { it == SubtitleStylePreset.Custom }
                                    .forEach { preset ->
                                        OptionRow(
                                            preset.label,
                                            subtitleControls.stylePreset == preset,
                                            onClick = { subtitleActions.onStylePreset(preset) },
                                        )
                                    }
                            } else {
                                UnsupportedSubtitleControl(subtitleControls.unavailableReason)
                            }
                            GroupLabel("字幕位置")
                            if (subtitleControls.positionAvailable) {
                                listOf(0.76f to "靠上", 0.88f to "居中偏下", 0.92f to "标准", 0.96f to "靠下")
                                    .forEach { (position, label) ->
                                        OptionRow(
                                            label,
                                            subtitleControls.position == position,
                                            onClick = { subtitleActions.onPosition(position) },
                                        )
                                    }
                            } else {
                                UnsupportedSubtitleControl(subtitleControls.unavailableReason)
                            }
                            GroupLabel("字幕时间偏移")
                            if (subtitleControls.offsetAvailable) {
                                listOf(-5_000L, -2_000L, 0L, 2_000L, 5_000L).forEach { offset ->
                                    val label =
                                        when {
                                            offset < 0L -> "提前 ${-offset / 1000} 秒"
                                            offset > 0L -> "延后 ${offset / 1000} 秒"
                                            else -> "同步"
                                        }
                                    OptionRow(
                                        label,
                                        subtitleControls.offsetMs == offset,
                                        onClick = { subtitleActions.onOffset(offset) },
                                    )
                                }
                            } else {
                                UnsupportedSubtitleControl(subtitleControls.unavailableReason)
                            }
                            GroupLabel("字幕大小")
                            if (subtitleControls.scaleAvailable) {
                                listOf(0.8f to "小", 1f to "标准", 1.25f to "大", 1.5f to "特大")
                                    .forEach { (scale, label) ->
                                        OptionRow(
                                            label,
                                            subtitleControls.scale == scale,
                                            onClick = { subtitleActions.onScale(scale) },
                                        )
                                    }
                            } else {
                                UnsupportedSubtitleControl(subtitleControls.unavailableReason)
                            }
                            GroupLabel("HDR 字幕亮度")
                            if (subtitleControls.brightnessAvailable) {
                                listOf(0.4f to "40%", 0.6f to "60%", 0.8f to "80%", 1f to "100%")
                                    .forEach { (brightness, label) ->
                                        OptionRow(
                                            label,
                                            subtitleControls.brightness == brightness,
                                            onClick = { subtitleActions.onBrightness(brightness) },
                                        )
                                    }
                            } else {
                                UnsupportedSubtitleControl(subtitleControls.unavailableReason)
                            }
                            GroupLabel("字幕颜色")
                            if (subtitleControls.appearanceAvailable) {
                                listOf(
                                    0xFFFFFFFFL to "白色",
                                    0xFFFFF2CCL to "暖黄",
                                    0xFFBFE3FFL to "浅蓝",
                                    0xFFBFFFD0L to "浅绿",
                                ).forEach { (color, label) ->
                                    OptionRow(
                                        label,
                                        subtitleControls.appearance.textColorArgb == color,
                                        onClick = { subtitleActions.onTextColor(color) },
                                    )
                                }
                                GroupLabel("字幕背景")
                                listOf(
                                    0x00000000L to "透明",
                                    0x66000000L to "半透明黑",
                                    0x99000000L to "深色背景",
                                ).forEach { (color, label) ->
                                    OptionRow(
                                        label,
                                        subtitleControls.appearance.backgroundColorArgb == color,
                                        onClick = { subtitleActions.onBackgroundColor(color) },
                                    )
                                }
                                GroupLabel("字幕描边")
                                listOf(0f to "关闭", 1f to "细", 2f to "标准", 4f to "粗")
                                    .forEach { (width, label) ->
                                        OptionRow(
                                            label,
                                            subtitleControls.appearance.outlineWidth == width,
                                            onClick = { subtitleActions.onOutlineWidth(width) },
                                        )
                                    }
                                listOf(0xFF000000L to "黑色描边", 0xFFFFFFFFL to "白色描边")
                                    .forEach { (color, label) ->
                                        OptionRow(
                                            label,
                                            subtitleControls.appearance.outlineColorArgb == color,
                                            onClick = { subtitleActions.onOutlineColor(color) },
                                        )
                                    }
                            } else {
                                UnsupportedSubtitleControl(subtitleControls.unavailableReason)
                            }
                        } else if (trackPanelMode == TrackPanelMode.Subtitle) {
                            Text(
                                "当前版本没有可用字幕，可继续搜索第三方字幕。",
                                style = AppTypography.caption.medium,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                        }
                        if (trackPanelMode == TrackPanelMode.Subtitle) {
                            GroupLabel("第三方字幕")
                            OptionRow(
                                if (remoteSubtitles.loading) "正在搜索中文字幕…" else "搜索中文字幕",
                                false,
                                onClick = remoteSubtitleActions.onSearch,
                            )
                            remoteSubtitles.results.forEach { result ->
                                OptionRow(
                                    label =
                                        listOf(result.label, result.detail)
                                            .filter(String::isNotBlank)
                                            .joinToString(" · "),
                                    selected = remoteSubtitles.downloadingId == result.id,
                                    onClick = { remoteSubtitleActions.onDownload(result.id) },
                                )
                            }
                            remoteSubtitles.message?.let { message ->
                                Text(
                                    message,
                                    style = AppTypography.caption.medium,
                                    color = Color.White.copy(alpha = 0.68f),
                                )
                            }
                        } else if (state.audioTracks.isNotEmpty()) {
                            state.audioTracks.forEach { track ->
                                OptionRow(
                                    track.label,
                                    track.selected,
                                    onClick = { onSelectAudio(track.id) },
                                )
                            }
                            GroupLabel("音频同步")
                            if (audioControls.available) {
                                // Telemetry, so it is not in the projection the rest of this page
                                // reads; derived from the live state here keeps the page itself
                                // off the position tick.
                                val measuredAvOffsetMs by remember(playback, audioControls.measuredAvOffsetMs) {
                                    derivedStateOf {
                                        playback.value.diagnostics.avSyncOffsetMs
                                            ?: audioControls.measuredAvOffsetMs
                                    }
                                }
                                measuredAvOffsetMs?.let { offset ->
                                    OptionRow(
                                        label =
                                            if (offset == 0L) {
                                                "自动校准 · 当前已同步"
                                            } else {
                                                "自动校准当前输出 · ${if (offset > 0L) "+" else ""}$offset ms"
                                            },
                                        selected = false,
                                        onClick = audioActions.onAutoSync,
                                    )
                                }
                                listOf(-2_000L, -500L, 0L, 500L, 2_000L).forEach { delay ->
                                    val label =
                                        when {
                                            delay < 0L -> "提前 ${-delay} 毫秒"
                                            delay > 0L -> "延后 $delay 毫秒"
                                            else -> "同步"
                                        }
                                    OptionRow(
                                        label,
                                        audioControls.delayMs == delay,
                                        onClick = { audioActions.onDelay(delay) },
                                    )
                                }
                            } else {
                                Text(
                                    audioControls.unavailableReason ?: "当前播放模式不支持音频延迟。",
                                    style = AppTypography.caption.medium,
                                    color = Color.White.copy(alpha = 0.68f),
                                )
                            }
                            GroupLabel("音频增强")
                            if (audioControls.enhancementAvailable) {
                                AudioEnhancementMode.entries.forEach { mode ->
                                    OptionRow(
                                        mode.label,
                                        audioControls.enhancement == mode,
                                        onClick = { audioActions.onEnhancement(mode) },
                                    )
                                }
                                Text(
                                    "响度均衡会统一不同剧集的主观音量；夜间人声会压缩动态范围。音频增强会关闭原码直通。",
                                    style = AppTypography.caption.medium,
                                    color = Color.White.copy(alpha = 0.68f),
                                )
                            } else {
                                Text(
                                    "当前锁定内核不支持音量增强或夜间人声。",
                                    style = AppTypography.caption.medium,
                                    color = Color.White.copy(alpha = 0.68f),
                                )
                            }
                        } else {
                            Text(
                                "当前版本没有可切换的音轨。",
                                style = AppTypography.caption.medium,
                                color = Color.White.copy(alpha = 0.68f),
                            )
                        }
                    }

                    SettingsPanelKind.Skip -> {
                        val enabled = skip.mode != SkipMode.Off
                        // 使用当前时间 is a question asked at the moment of the tap, so it is read
                        // when the tap happens rather than kept up to date while the panel sits
                        // open over the film.
                        val here = { (playback.value.positionMs / 1000).coerceAtLeast(0L) }
                        val durationSeconds = (state.durationMs / 1000).coerceAtLeast(0L)
                        val savedCreditsStart =
                            creditsStartSecondsFromLead(skip.creditsLeadSeconds, durationSeconds)
                        var introStartInput by remember(skip.introStartSeconds) {
                            mutableStateOf(formatSkipTimestamp(skip.introStartSeconds))
                        }
                        var introEndInput by remember(skip.introEndSeconds) {
                            mutableStateOf(
                                skip.introEndSeconds
                                    .takeIf { it > 0L }
                                    ?.let(::formatSkipTimestamp)
                                    .orEmpty(),
                            )
                        }
                        var creditsInput by remember(skip.creditsLeadSeconds, durationSeconds) {
                            mutableStateOf(savedCreditsStart?.let(::formatSkipTimestamp).orEmpty())
                        }
                        var introError by remember(skip.introStartSeconds, skip.introEndSeconds) {
                            mutableStateOf<String?>(null)
                        }
                        var creditsError by remember(skip.creditsLeadSeconds, durationSeconds) {
                            mutableStateOf<String?>(null)
                        }

                        PopupToggleHeader(
                            label = "跳过片头/片尾",
                            checked = enabled,
                            onToggle = {
                                skipActions.onSelectMode(
                                    if (enabled) SkipMode.Off else SkipMode.Button,
                                )
                            },
                        )
                        SegmentedRow(
                            options = listOf("显示跳过按钮", "自动跳过"),
                            selectedIndex = if (skip.mode == SkipMode.Auto) 1 else 0,
                            onSelect = { index ->
                                skipActions.onSelectMode(
                                    if (index == 0) SkipMode.Button else SkipMode.Auto,
                                )
                            },
                        )
                        PopupDivider()
                        GroupLabel("片头")
                        SkipTimeField(
                            label = "开始时间",
                            value = introStartInput,
                            onValueChange = {
                                introStartInput = it
                                introError = null
                            },
                            onUseCurrent = {
                                introStartInput = formatSkipTimestamp(here())
                                introError = null
                            },
                        )
                        SkipTimeField(
                            label = "结束时间",
                            value = introEndInput,
                            onValueChange = {
                                introEndInput = it
                                introError = null
                            },
                            onUseCurrent = {
                                introEndInput = formatSkipTimestamp(here())
                                introError = null
                            },
                        )
                        introError?.let { error ->
                            Text(
                                error,
                                style = AppTypography.caption.medium,
                                color = DarkPalette.error,
                                modifier = Modifier.padding(horizontal = 5.dp),
                            )
                        }
                        OptionRow(
                            label = "保存片头时间",
                            selected = false,
                            onClick = {
                                val introStart = parseSkipTimestamp(introStartInput)
                                val introEnd = parseSkipTimestamp(introEndInput)
                                introError =
                                    when {
                                        introStart == null || introEnd == null ->
                                            "请输入秒数、mm:ss 或 hh:mm:ss"
                                        introStart == 0L && introEnd == 0L -> {
                                            skipActions.onSetTimes(0L, 0L, skip.creditsLeadSeconds)
                                            null
                                        }
                                        introEnd <= introStart -> "片头结束时间必须晚于开始时间"
                                        durationSeconds > 0L && introEnd >= durationSeconds ->
                                            "片头结束时间必须早于视频结束"
                                        else -> {
                                            skipActions.onSetTimes(
                                                introStart,
                                                introEnd,
                                                skip.creditsLeadSeconds,
                                            )
                                            null
                                        }
                                    }
                            },
                        )

                        PopupDivider()
                        GroupLabel("片尾")
                        Text(
                            "只设置片尾开始的时间点；无需结束时间。",
                            style = AppTypography.caption.medium,
                            color = Color.White.copy(alpha = 0.54f),
                            modifier = Modifier.padding(horizontal = 5.dp),
                        )
                        SkipTimeField(
                            label = "片尾时间",
                            value = creditsInput,
                            onValueChange = {
                                creditsInput = it
                                creditsError = null
                            },
                            onUseCurrent = {
                                creditsInput = formatSkipTimestamp(here())
                                creditsError = null
                            },
                        )
                        creditsError?.let { error ->
                            Text(
                                error,
                                style = AppTypography.caption.medium,
                                color = DarkPalette.error,
                                modifier = Modifier.padding(horizontal = 5.dp),
                            )
                        }
                        OptionRow(
                            label = "保存片尾时间",
                            selected = false,
                            onClick = {
                                val creditsStart = parseSkipTimestamp(creditsInput)
                                creditsError =
                                    when {
                                        creditsStart == null -> "请输入秒数、mm:ss 或 hh:mm:ss"
                                        creditsStart == 0L -> {
                                            skipActions.onSetTimes(
                                                skip.introStartSeconds,
                                                skip.introEndSeconds,
                                                0L,
                                            )
                                            null
                                        }
                                        durationSeconds <= 0L ->
                                            "视频时长尚未就绪，暂时无法保存片尾时间"
                                        else -> {
                                            val lead =
                                                creditsLeadSecondsFromStart(
                                                    creditsStart,
                                                    durationSeconds,
                                                )
                                            if (lead == null) {
                                                "片尾时间必须位于视频时长范围内"
                                            } else {
                                                skipActions.onSetTimes(
                                                    skip.introStartSeconds,
                                                    skip.introEndSeconds,
                                                    lead,
                                                )
                                                null
                                            }
                                        }
                                    }
                            },
                        )
                        if (skip.anySet) {
                            PopupDivider()
                            OptionRow(
                                label = "清除片头片尾标记",
                                selected = false,
                                onClick = { skipActions.onSetTimes(0L, 0L, 0L) },
                            )
                        }
                    }

                    SettingsPanelKind.More -> {
                        val diagnostics = state.diagnostics
                        AdvancedPageHost(
                            page = advancedPage,
                            reduceMotion = reduceMotion,
                            pushTravelPx = pushTravelPx,
                            popTravelPx = popTravelPx,
                        ) { page ->
                            when (page) {
                                AdvancedPage.Bookmarks -> {
                                    PopupBackLabel("时间书签") { advancedPage = AdvancedPage.Root }
                                    PlaybackBookmarkPanel(bookmarks, bookmarkActions)
                                }
                                AdvancedPage.Root -> {
                                    PopupMenuRow(
                                        icon = AppIcons.Bookmark,
                                        title = "时间书签",
                                        subtitle = "标记片段与备注 · ${bookmarks.items.size} 个",
                                        onClick = { advancedPage = AdvancedPage.Bookmarks },
                                    )
                                    PopupDivider()
                                    PopupMenuRow(
                                        icon = AppIcons.PlaybackSource,
                                        title = "播放内核",
                                        subtitle = "当前：${diagnostics.engine.ifBlank { "等待内核" }}",
                                        onClick = { advancedPage = AdvancedPage.Engine },
                                    )
                                    PopupDivider()
                                    PopupMenuRow(
                                        icon = AppIcons.Info,
                                        title = "媒体信息",
                                        subtitle = diagnostics.playMethod.ifBlank { "实时播放诊断" },
                                        onClick = { advancedPage = AdvancedPage.Media },
                                    )
                                    PopupDivider()
                                    PopupMenuRow(
                                        icon = AppIcons.Grid,
                                        title = "播放设置",
                                        subtitle = "定时与播放控制",
                                        onClick = { advancedPage = AdvancedPage.Playback },
                                    )
                                }

                                AdvancedPage.Playback -> {
                                    PopupBackLabel("播放设置") { advancedPage = AdvancedPage.Root }
                                    // Native HDMV state is richer than mpv's edition/chapter properties:
                                    // it owns menu/angle state and is pushed from libbluray callbacks.
                                    // Observing the revision above makes menu availability and activation
                                    // visible immediately without polling the native session.
                                    @Suppress("UNUSED_VARIABLE")
                                    val observedDiscNavigationRevision = discNavigationRevision
                                    val activeDisc = ActiveDiscNavigation.navigation
                                    val disc =
                                        if (ActiveDiscNavigation.isBound && activeDisc.available) {
                                            activeDisc
                                        } else {
                                            state.discNavigation
                                        }
                                    if (disc.available) {
                                        GroupLabel("${disc.kind.label}导航")
                                        if (disc.effectiveTitleCount > 1) {
                                            if (ActiveDiscNavigation.isBound) {
                                                GroupLabel("标题 / Playlist")
                                                disc.titleOptions.forEach { title ->
                                                    val authored = title.title?.trim()?.takeIf(String::isNotEmpty)
                                                    val playlist = title.playlistLabel
                                                    val authoredIsPlaylist =
                                                        authored?.contains(".mpls", ignoreCase = true) == true ||
                                                            authored?.contains("mpls/", ignoreCase = true) == true ||
                                                            authored?.contains("mpls\\", ignoreCase = true) == true
                                                    val label =
                                                        listOfNotNull(
                                                            authored?.takeUnless { authoredIsPlaylist }
                                                                ?: playlist
                                                                ?: "标题 ${title.index + 1}",
                                                            playlist?.takeUnless {
                                                                authoredIsPlaylist ||
                                                                    it == authored
                                                            },
                                                            "默认".takeIf { title.isDefault },
                                                        ).joinToString(" · ")
                                                    OptionRow(
                                                        label = label,
                                                        selected = title.index == disc.selectedTitleIndex,
                                                        onClick = { ActiveDiscNavigation.selectTitle(title.index) },
                                                    )
                                                }
                                            } else {
                                                OptionRow(
                                                    listOfNotNull(
                                                        disc.selectedTitle?.label,
                                                        "${disc.selectedTitleIndex + 1} / ${disc.effectiveTitleCount}",
                                                    ).joinToString(" · "),
                                                    false,
                                                    onClick = onNextDiscTitle,
                                                )
                                            }
                                        }
                                        if (disc.effectiveChapterCount > 1) {
                                            if (ActiveDiscNavigation.isBound) {
                                                GroupLabel("章节")
                                                disc.chapterOptions.forEach { chapter ->
                                                    OptionRow(
                                                        label =
                                                            listOfNotNull(chapter.timeLabel, chapter.label)
                                                                .joinToString(" · "),
                                                        selected = chapter.index == disc.selectedChapterIndex,
                                                        onClick = { ActiveDiscNavigation.selectChapter(chapter.index) },
                                                    )
                                                }
                                            } else {
                                                val chapterPosition =
                                                    "${disc.selectedChapterIndex + 1} / " +
                                                        "${disc.effectiveChapterCount}"
                                                OptionRow(
                                                    listOfNotNull(
                                                        disc.selectedChapter?.timeLabel,
                                                        disc.selectedChapter?.label,
                                                        chapterPosition,
                                                    ).joinToString(" · "),
                                                    false,
                                                    onClick = onNextDiscChapter,
                                                )
                                            }
                                        }
                                        if (disc.effectiveAngleCount > 1 && ActiveDiscNavigation.isBound) {
                                            GroupLabel("多视角")
                                            disc.angleOptions.forEach { angle ->
                                                OptionRow(
                                                    label = angle.label,
                                                    selected = angle.index == disc.selectedAngleIndex,
                                                    onClick = { ActiveDiscNavigation.selectAngle(angle.index) },
                                                )
                                            }
                                        }
                                        if (disc.menuSupported) {
                                            OptionRow(
                                                "打开光盘菜单",
                                                disc.menuActive,
                                                onClick = {
                                                    if (
                                                        !ActiveDiscNavigation.sendMenuCommand(
                                                            com.yfuse.core.playback.PlaybackDiscMenuCommand.ShowMenu,
                                                        )
                                                    ) {
                                                        onShowDiscMenu()
                                                    }
                                                },
                                            )
                                        }
                                    }
                                    GroupLabel("画面")
                                    // Stays open: the light is judged against the picture behind the panel.
                                    PopupToggleHeader(
                                        label = "氛围光",
                                        checked = ambientLightEnabled,
                                        onToggle = onToggleAmbientLight,
                                    )
                                    OptionRow("锁定控制", false, onClick = overlayAction(onLock))
                                    OptionRow("手势说明", false, onClick = overlayAction(onOpenGestureHelp))
                                    onExternalPlayer?.let { open ->
                                        OptionRow("使用外部播放器", false, onClick = overlayAction(open))
                                    }
                                    if (watch.available || watch.connected) {
                                        OptionRow(
                                            if (watch.connected) {
                                                "一起看 · ${watch.roomCode.orEmpty()}"
                                            } else {
                                                "一起看"
                                            },
                                            watch.connected,
                                            onClick = overlayAction(onOpenWatchTogether),
                                        )
                                    }
                                    if (versions.size > 1) {
                                        GroupLabel("播放版本")
                                        versions.forEach { (id, label) ->
                                            OptionRow(
                                                label,
                                                id == selectedVersionId,
                                                onClick = overlayAction { onSelectVersion(id) },
                                            )
                                        }
                                    }
                                    GroupLabel("睡眠定时")
                                    SleepTimerOption.entries.forEach { option ->
                                        OptionRow(
                                            option.label,
                                            sleepTimer.selected == option,
                                            onClick = { sleepTimerActions.onSelect(option) },
                                        )
                                    }
                                }

                                AdvancedPage.Engine -> {
                                    PopupBackLabel("播放内核", "仅当前视频 · 切换后重新加载") {
                                        advancedPage = AdvancedPage.Root
                                    }
                                    engineOptions.forEachIndexed { index, (label, selected) ->
                                        EngineChoiceRow(
                                            label = label,
                                            selected = selected,
                                            onClick = { onSelectEngine(index) },
                                        )
                                        if (index != engineOptions.lastIndex || transcodeLabel != null) {
                                            PopupDivider()
                                        }
                                    }
                                    if (transcodeLabel != null) {
                                        PopupMenuRow(
                                            icon = AppIcons.Play,
                                            title = transcodeLabel,
                                            subtitle = "服务器兼容播放模式",
                                            selected = transcodeActive,
                                            onClick = onTranscode,
                                        )
                                    }
                                }

                                AdvancedPage.Media -> {
                                    // The one page in this panel that is a live readout:
                                    // it takes the timeline itself so the four that are not
                                    // stay off it.
                                    val liveDiagnostics = playback.value.diagnostics
                                    PopupBackLabel("媒体信息") { advancedPage = AdvancedPage.Root }
                                    DiagnosticRow("容器", containerLabel ?: "未知")
                                    DiagnosticRow(
                                        "YCore 管线",
                                        liveDiagnostics.plannedRenderPath.ifBlank { "等待规划" },
                                    )
                                    DiagnosticRow("运行健康", liveDiagnostics.playbackHealth)
                                    DiagnosticRow(
                                        "A/V 同步",
                                        liveDiagnostics.avSyncOffsetMs?.let { offset ->
                                            val signed = if (offset > 0L) "+$offset" else offset.toString()
                                            "$signed ms · ${liveDiagnostics.avSyncMeasurement}"
                                        } ?: liveDiagnostics.avSyncMeasurement,
                                    )
                                    DiagnosticRow("功耗估计", liveDiagnostics.powerProfile)
                                    DiagnosticRow("资源压力", liveDiagnostics.resourcePressure)
                                    DiagnosticRow("媒体探测", liveDiagnostics.mediaProbe)
                                    DiagnosticRow("历史基线", liveDiagnostics.performanceBaseline)
                                    DiagnosticRow(
                                        "分辨率",
                                        when {
                                            liveDiagnostics.videoWidth > 0 && state.videoHeight > 0 ->
                                                "${liveDiagnostics.videoWidth} × ${state.videoHeight}"
                                            state.videoHeight > 0 -> "${state.videoHeight}P"
                                            else -> "未知"
                                        },
                                    )
                                    DiagnosticRow(
                                        "视频",
                                        listOf(
                                            liveDiagnostics.videoCodec,
                                            liveDiagnostics.dynamicRange,
                                        ).filter(String::isNotBlank).joinToString(" · "),
                                    )
                                    DiagnosticRow("码率", liveDiagnostics.bitrateBitsPerSecond.asBitrate())
                                    DiagnosticRow("实时网络", liveDiagnostics.networkBitsPerSecond.asBitrate())
                                    DiagnosticRow(
                                        "前向缓冲",
                                        maxOf(
                                            liveDiagnostics.bufferedDurationMs,
                                            liveDiagnostics.sourceBufferedMs,
                                        ).asSeconds(),
                                    )
                                    DiagnosticRow("帧率", liveDiagnostics.frameRate.asFrameRate())
                                    DiagnosticRow(
                                        "音频",
                                        state.audioTracks.firstOrNull { it.selected }?.label
                                            ?: liveDiagnostics.audioFormat.ifBlank { "未知" },
                                    )
                                    DiagnosticRow(
                                        "字幕",
                                        state.subtitleTracks.firstOrNull { it.selected }?.label ?: "未加载",
                                    )
                                    DiagnosticRow(
                                        "播放内核",
                                        listOf(
                                            liveDiagnostics.engine,
                                            liveDiagnostics.decoder,
                                        ).filter(String::isNotBlank).joinToString(" · "),
                                    )
                                    PopupDivider()
                                    Text(
                                        "●  ${liveDiagnostics.playMethod.ifBlank { "实时播放" }}",
                                        style = AppTypography.caption.medium,
                                        color = Color.White.copy(alpha = 0.58f),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                                    )
                                    liveDiagnostics.fallbackReason?.takeIf(String::isNotBlank)?.let { reason ->
                                        DiagnosticRow("降级原因", reason)
                                    }
                                    liveDiagnostics.planningReason?.takeIf(String::isNotBlank)?.let { reason ->
                                        DiagnosticRow("规划原因", reason)
                                    }
                                    PopupDivider()
                                    PopupMenuRow(
                                        icon = AppIcons.Refresh,
                                        title = "重置 YCore 学习数据",
                                        subtitle = "清除本机故障记忆与性能基线",
                                        onClick = onResetAdaptiveLearning,
                                    )
                                }
                            }
                        }
                    }

                    SettingsPanelKind.Cast -> {
                        // Derived, so the receiver's clock invalidates this page when the text
                        // it prints changes and never the four pages that do not show it.
                        val remotePosition by remember(castPositionSource, castPosition) {
                            derivedStateOf { castPositionSource?.invoke() ?: castPosition }
                        }
                        GroupLabel("局域网投屏设备")
                        castStatus?.let { DiagnosticRow("状态", it) }
                        remotePosition?.let { DiagnosticRow("远端进度", it) }
                        castCapabilities?.let { DiagnosticRow("远端能力", it) }
                        if (castDiscovering) {
                            Text(
                                "正在发现 DLNA 设备…",
                                style = AppTypography.caption.medium,
                                color = Color.White.copy(alpha = 0.55f),
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                        castDevices.forEach { (id, name) ->
                            OptionRow(name, id == castingDeviceId, onClick = { onCastTo(id) })
                        }
                        if (castingDeviceId != null) {
                            OptionRow("停止投屏", false, onClick = onStopCast)
                        }
                        if (castError != null) {
                            Text(
                                castError,
                                style = AppTypography.caption.medium,
                                color = DarkPalette.error,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        OptionRow("重新扫描", false, onClick = onDiscoverCast)
                    }
                }
            }
        }
    }
}

/**
 * One popup kind handing over to another.
 *
 * 字幕, 音轨, 弹幕, 投屏 and 更多 all open the same surface in the same corner, and swapping its
 * contents in a single frame made it read as five unrelated panels appearing at the same
 * coordinates. A crossfade with the height settling underneath says it is one panel showing
 * something else.
 *
 * [motionAwareAnimateContentSize] covers growth *within* a kind, which is what the track lists do
 * when a downloaded subtitle or a second audio stream turns up. Those lists share this scrolling
 * column with everything else on the page, so they cannot become lazy lists of their own; what
 * they can stop doing is making the page jump when one of them changes length.
 *
 * 更多 is the exception. It owns two levels of its own and animates between them through
 * [AdvancedPageHost]'s [SizeTransform]; a second size animation out here chased the first one all
 * the way down, so opening 媒体信息 settled twice — once on the page's real height and once on
 * whatever the outer spring had reached by then.
 */
@Composable
private fun SettingsKindHost(
    kind: SettingsPanelKind,
    reduceMotion: Boolean,
    content: @Composable ColumnScope.(SettingsPanelKind) -> Unit,
) {
    AnimatedContent(
        targetState = kind,
        contentKey = { it },
        transitionSpec = { settingsKindTransform(reduceMotion) },
        modifier = Modifier.fillMaxWidth(),
        label = "player-settings-kind",
    ) { current ->
        val sizeAware =
            if (current == SettingsPanelKind.More) {
                Modifier
            } else {
                Modifier.motionAwareAnimateContentSize()
            }
        Column(
            Modifier.fillMaxWidth().then(sizeAware),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            content(current)
        }
    }
}

/**
 * 更多's two levels: the list of destinations, and the page behind one of them.
 *
 * The same gesture as a detail page opening, at panel scale — [Motion.PUSH] and [Motion.pushOffset]
 * going deeper, [Motion.POP] and [Motion.popOffset] coming back — so 返回 inside this panel reads
 * as 返回 everywhere else in the app rather than as the contents being replaced.
 */
@Composable
private fun AdvancedPageHost(
    page: AdvancedPage,
    reduceMotion: Boolean,
    pushTravelPx: Int,
    popTravelPx: Int,
    content: @Composable ColumnScope.(AdvancedPage) -> Unit,
) {
    AnimatedContent(
        targetState = page,
        contentKey = { it },
        transitionSpec = {
            advancedPageTransform(
                popping = targetState == AdvancedPage.Root,
                reduceMotion = reduceMotion,
                pushTravelPx = pushTravelPx,
                popTravelPx = popTravelPx,
            )
        },
        modifier = Modifier.fillMaxWidth(),
        label = "player-advanced-page",
    ) { current ->
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            content(current)
        }
    }
}

private fun AnimatedContentTransitionScope<*>.settingsKindTransform(reduceMotion: Boolean): ContentTransform {
    val duration = if (reduceMotion) 0 else Motion.STATE_HANDOFF
    val crossfade =
        fadeIn(tween(duration, easing = Motion.Curve)) togetherWith
            fadeOut(tween(duration, easing = Motion.Curve))
    // No size animation under 减弱动态效果: the fade is the hand-off, and a surface still
    // resizing after it has finished is the movement that setting is there to remove.
    return if (reduceMotion) {
        crossfade.using(null)
    } else {
        crossfade.using(SizeTransform(clip = false) { _, _ -> Motion.settle() })
    }
}

private fun AnimatedContentTransitionScope<*>.advancedPageTransform(
    popping: Boolean,
    reduceMotion: Boolean,
    pushTravelPx: Int,
    popTravelPx: Int,
): ContentTransform {
    if (reduceMotion) {
        return (fadeIn(snap()) togetherWith fadeOut(snap())).using(null)
    }
    val transform =
        if (popping) {
            (
                fadeIn(tween(Motion.POP, easing = Motion.Curve)) +
                    slideInHorizontally(tween(Motion.POP, easing = Motion.Curve)) { -popTravelPx }
            ) togetherWith
                (
                    fadeOut(tween(Motion.POP, easing = Motion.Curve)) +
                        slideOutHorizontally(tween(Motion.POP, easing = Motion.Curve)) { popTravelPx }
                )
        } else {
            (
                fadeIn(tween(Motion.PUSH, easing = Motion.Curve)) +
                    slideInHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { pushTravelPx }
            ) togetherWith
                (
                    // One duration for the whole push, as the pop already does: the outgoing
                    // page used to finish fading a page-length before it finished moving.
                    fadeOut(tween(Motion.PUSH, easing = Motion.Curve)) +
                        slideOutHorizontally(tween(Motion.PUSH, easing = Motion.Curve)) { -pushTravelPx / 2 }
                )
        }
    // The two levels are nowhere near the same height, so the surface travels between them
    // instead of cutting — otherwise it changes shape under a page that is still arriving.
    return transform.using(SizeTransform(clip = false) { _, _ -> Motion.settle() })
}

@Composable
private fun UnsupportedSubtitleControl(reason: String?) {
    Text(
        reason ?: "当前播放内核不支持此字幕调节。",
        style = AppTypography.caption.medium,
        color = Color.White.copy(alpha = 0.68f),
    )
}

@Composable
private fun SkipTimeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onUseCurrent: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            label,
            style = AppTypography.caption.strong,
            color = Color.White.copy(alpha = 0.72f),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .glass(
                        shape = AppShapes.thumb,
                        fill = Color.White.copy(alpha = 0.055f),
                        border = Color.White.copy(alpha = 0.13f),
                    ).padding(horizontal = 11.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = { candidate ->
                        val normalized = candidate.replace('：', ':')
                        if (
                            normalized.length <= 10 &&
                            normalized.all { it.isDigit() || it == ':' }
                        ) {
                            onValueChange(normalized)
                        }
                    },
                    singleLine = true,
                    textStyle =
                        AppTypography.body.strong.copy(
                            color = Color.White.copy(alpha = 0.94f),
                        ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    cursorBrush = SolidColor(accent.accent),
                    decorationBox = { field ->
                        if (value.isBlank()) {
                            Text(
                                "mm:ss / hh:mm:ss",
                                style = AppTypography.body.medium,
                                color = Color.White.copy(alpha = 0.34f),
                            )
                        }
                        field()
                    },
                )
            }
            Text(
                "当前",
                style = AppTypography.caption.strong,
                color = accent.accent,
                modifier =
                    Modifier
                        .glass(
                            shape = AppShapes.thumb,
                            fill = accent.container,
                            border = accent.border,
                        ).noRippleClickable(onUseCurrent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun PopupBackLabel(
    title: String,
    trailing: String? = null,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹  $title",
            style = AppTypography.body.strong,
            color = Color.White.copy(alpha = 0.90f),
            modifier = Modifier.noRippleClickable(onBack),
        )
        trailing?.let {
            Text(
                it,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.48f),
            )
        }
    }
}

private fun engineDescription(label: String): String =
    when {
        label.contains("EXO", ignoreCase = true) -> "系统解码 · HDR/Dolby Vision"
        label.contains("MDK", ignoreCase = true) -> "画质优先 · 高兼容性"
        label.contains("MPV", ignoreCase = true) -> "格式支持更完整"
        else -> "兼容播放内核"
    }

@Composable
private fun EngineChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val badge = label.substringBefore(' ').take(3).uppercase()
    Row(
        Modifier
            .fillMaxWidth()
            .noRippleClickable(onClick)
            .padding(horizontal = 5.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .glass(
                    shape = AppShapes.thumb,
                    fill = if (selected) accent.container else Color.Transparent,
                    border = if (selected) accent.border else Color.White.copy(alpha = 0.16f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                badge,
                style = AppTypography.caption.strong,
                color = if (selected) accent.accent else Color.White.copy(alpha = 0.70f),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = AppTypography.body.strong, color = Color.White.copy(alpha = 0.92f))
            Text(
                engineDescription(label),
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.52f),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(
            Modifier
                .size(24.dp)
                .glass(
                    shape = AppShapes.pill,
                    fill = if (selected) accent.container else Color.Transparent,
                    border = if (selected) accent.border else Color.White.copy(alpha = 0.22f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    AppIcons.Check,
                    contentDescription = null,
                    tint = accent.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun SourcePickerPopup(
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    PlayerPopupPanel(
        onDismiss = onDismiss,
        modifier = modifier,
        compact = true,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${options.size} 个可用",
                style = AppTypography.caption.strong,
                color = Color.White.copy(alpha = 0.86f),
            )
            Text(
                "切换不改变播放进度",
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.44f),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            options.forEach { (id, label) ->
                val selected = id == selectedId
                Column(
                    Modifier
                        .widthIn(min = 116.dp, max = 150.dp)
                        .glass(
                            shape = AppShapes.card,
                            fill = if (selected) accent.container else Color.White.copy(alpha = 0.05f),
                            border = if (selected) accent.border else Color.White.copy(alpha = 0.08f),
                        ).noRippleClickable { onSelect(id) }
                        .padding(horizontal = 11.dp, vertical = 10.dp),
                ) {
                    Text(
                        label,
                        style = AppTypography.body.strong,
                        color = if (selected) accent.accent else Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                    Text(
                        if (selected) "当前线路" else "可用线路",
                        style = AppTypography.caption.medium,
                        color = Color.White.copy(alpha = 0.44f),
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        Text(
            "播放失败时会自动切换到下一条可用线路",
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.40f),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 9.dp),
        )
    }
}

/** Dedicated speed popup opened from the playback page; no unrelated settings are mixed in. */
@Composable
internal fun SpeedPickerPopup(
    speeds: List<Float>,
    selectedSpeed: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerPopupPanel(
        onDismiss = onDismiss,
        modifier = modifier,
        compact = true,
    ) {
        CompactChoiceGrid(
            options = speeds.map(::speedLabel),
            selectedIndex = speeds.indexOf(selectedSpeed),
            columns = 4,
            onSelect = { onSelect(speeds[it]) },
        )
        Text(
            "选择后立即生效",
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.40f),
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun CompactChoiceGrid(
    options: List<String>,
    selectedIndex: Int,
    columns: Int,
    onSelect: (Int) -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(columns).forEachIndexed { rowIndex, rowOptions ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowOptions.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * columns + columnIndex
                    val selected = index == selectedIndex
                    Text(
                        label,
                        style = if (selected) AppTypography.caption.strong else AppTypography.caption.medium,
                        color = if (selected) accent.accent else Color.White.copy(alpha = 0.68f),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier =
                            Modifier
                                .weight(1f)
                                .glass(
                                    shape = AppShapes.pill,
                                    fill = if (selected) accent.container else Color.White.copy(alpha = 0.045f),
                                    border = if (selected) accent.border else Color.White.copy(alpha = 0.07f),
                                ).noRippleClickable { onSelect(index) }
                                .padding(vertical = 9.dp),
                    )
                }
                repeat(columns - rowOptions.size) {
                    Spacer(Modifier.weight(1f).size(1.dp))
                }
            }
        }
    }
}
