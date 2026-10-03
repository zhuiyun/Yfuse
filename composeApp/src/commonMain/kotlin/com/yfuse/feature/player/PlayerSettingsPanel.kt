package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.motionAwareAnimateContentSize
import com.yfuse.core.designsystem.overlayAction
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.model.ShortDramaMode
import kotlin.math.abs
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
    castTransport: String?,
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
    autoNextEnabled: Boolean = true,
    onToggleAutoNext: () -> Unit = {},
    shortDramaMode: ShortDramaMode? = null,
    onSelectShortDramaMode: (ShortDramaMode) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var advancedPage by remember(kind) { mutableStateOf(AdvancedPage.Root) }
    var problemOpen by remember { mutableStateOf(false) }
    if (problemOpen) PlaybackProblemDialog(playback) { problemOpen = false }
    val discNavigationRevision = ActiveDiscNavigation.revision.collectAsState()

    PlayerPopupPanel(
        onDismiss = onDismiss,
        modifier = modifier,
        paneTitle = settingsPanelTitle(kind, trackPanelMode),
    ) {
        // Two transitions, because this shell has two axes of change: one popup kind hands
        // over to another here, and 更多 goes a level deeper and comes back inside [MorePanel].
        val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
        // The list takes whatever height the drawer has left instead of the old fixed
        // 210dp window, which scrolled a short slot inside a mostly empty screen.
        val panelScroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(panelScroll),
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

                    SettingsPanelKind.Tracks ->
                        TracksPanel(
                            trackPanelMode = trackPanelMode,
                            state = state,
                            playback = playback,
                            panelScroll = panelScroll,
                            reduceMotion = reduceMotion,
                            onSelectSubtitle = onSelectSubtitle,
                            subtitleControls = subtitleControls,
                            subtitleActions = subtitleActions,
                            remoteSubtitles = remoteSubtitles,
                            remoteSubtitleActions = remoteSubtitleActions,
                            onSelectAudio = onSelectAudio,
                            audioControls = audioControls,
                            audioActions = audioActions,
                        )

                    SettingsPanelKind.Skip ->
                        SkipPanel(
                            skip = skip,
                            skipActions = skipActions,
                            state = state,
                            playback = playback,
                        )

                    SettingsPanelKind.More ->
                        MorePanel(
                            advancedPage = advancedPage,
                            onAdvancedPage = { advancedPage = it },
                            reduceMotion = reduceMotion,
                            state = state,
                            playback = playback,
                            discNavigationRevision = discNavigationRevision,
                            containerLabel = containerLabel,
                            engineOptions = engineOptions,
                            transcodeLabel = transcodeLabel,
                            transcodeActive = transcodeActive,
                            onSelectEngine = onSelectEngine,
                            onTranscode = onTranscode,
                            onResetAdaptiveLearning = onResetAdaptiveLearning,
                            onOpenProblem = { problemOpen = true },
                            onNextDiscTitle = onNextDiscTitle,
                            onNextDiscChapter = onNextDiscChapter,
                            onShowDiscMenu = onShowDiscMenu,
                            onExternalPlayer = onExternalPlayer,
                            onLock = onLock,
                            onOpenGestureHelp = onOpenGestureHelp,
                            watch = watch,
                            onOpenWatchTogether = onOpenWatchTogether,
                            versions = versions,
                            selectedVersionId = selectedVersionId,
                            onSelectVersion = onSelectVersion,
                            sleepTimer = sleepTimer,
                            sleepTimerActions = sleepTimerActions,
                            ambientLightEnabled = ambientLightEnabled,
                            onToggleAmbientLight = onToggleAmbientLight,
                            autoNextEnabled = autoNextEnabled,
                            onToggleAutoNext = onToggleAutoNext,
                            shortDramaMode = shortDramaMode,
                            onSelectShortDramaMode = onSelectShortDramaMode,
                            bookmarks = bookmarks,
                            bookmarkActions = bookmarkActions,
                        )

                    SettingsPanelKind.Cast ->
                        CastPanel(
                            castDevices = castDevices,
                            castingDeviceId = castingDeviceId,
                            castDiscovering = castDiscovering,
                            castError = castError,
                            castStatus = castStatus,
                            castPosition = castPosition,
                            castPositionSource = castPositionSource,
                            castCapabilities = castCapabilities,
                            castTransport = castTransport,
                            onDiscoverCast = onDiscoverCast,
                            onCastTo = onCastTo,
                            onStopCast = onStopCast,
                        )
                }
            }
        }
    }
}

/** The name of the key that opened the popup, which is what a screen reader announces it as. */
private fun settingsPanelTitle(
    kind: SettingsPanelKind,
    trackMode: TrackPanelMode,
): String =
    when (kind) {
        SettingsPanelKind.Tracks -> if (trackMode == TrackPanelMode.Subtitle) "字幕" else "音轨"
        SettingsPanelKind.Danmaku -> "弹幕"
        SettingsPanelKind.Cast -> "投屏"
        SettingsPanelKind.Skip -> "标记片头片尾"
        SettingsPanelKind.More -> "更多"
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
 * 更多 is the exception. It owns two levels of its own and animates between them through its page
 * host's [SizeTransform] (see [MorePanel]); a second size animation out here chased the first one
 * all the way down, so opening 媒体信息 settled twice — once on the page's real height and once on
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

/**
 * One press of an offset stepper: the new absolute value, held inside what every engine accepts.
 * A value saved past [limitMs] elsewhere (a remembered series delay may reach ±10 s) is never
 * pulled in by a step away from zero; it only moves back towards the range.
 */
internal fun steppedOffsetMs(
    currentMs: Long,
    stepMs: Long,
    limitMs: Long,
): Long = (currentMs + stepMs).coerceIn(minOf(-limitMs, currentMs), maxOf(limitMs, currentMs))

/** Seconds as the steppers move them — 1_500 → 1.5, 2_000 → 2, 250 → 0.25 — without the sign. */
internal fun offsetSecondsLabel(offsetMs: Long): String {
    val magnitude = abs(offsetMs)
    val fraction = (magnitude % 1_000L).toString().padStart(3, '0').trimEnd('0')
    return if (fraction.isEmpty()) "${magnitude / 1_000L}" else "${magnitude / 1_000L}.$fraction"
}

internal fun subtitleOffsetLabel(offsetMs: Long): String =
    when {
        offsetMs < 0L -> "提前 ${offsetSecondsLabel(offsetMs)} 秒"
        offsetMs > 0L -> "延后 ${offsetSecondsLabel(offsetMs)} 秒"
        else -> "同步"
    }

internal fun subtitleOffsetStepLabel(stepMs: Long): String = "${stepSign(stepMs)}${offsetSecondsLabel(stepMs)} 秒"

internal fun audioDelayLabel(delayMs: Long): String =
    when {
        delayMs < 0L -> "提前 ${-delayMs} 毫秒"
        delayMs > 0L -> "延后 $delayMs 毫秒"
        else -> "同步"
    }

/** Bare figures: the unit is on the value between the steps, and the steps have to fit beside it. */
internal fun audioDelayStepLabel(stepMs: Long): String = "${stepSign(stepMs)}${abs(stepMs)}"

/** A minus sign rather than a hyphen: the steps are signed numbers. */
private fun stepSign(stepMs: Long): String = if (stepMs < 0L) "−" else "+"

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
        paneTitle = "播放服务器",
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
        paneTitle = "播放速度",
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
internal fun CompactChoiceGrid(
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
                                .playerChoiceFeedback(
                                    selected = selected,
                                    shape = AppShapes.pill,
                                    role = androidx.compose.ui.semantics.Role.RadioButton,
                                    onClick = { onSelect(index) },
                                ).padding(vertical = 9.dp),
                    )
                }
                repeat(columns - rowOptions.size) {
                    Spacer(Modifier.weight(1f).size(1.dp))
                }
            }
        }
    }
}
