package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AMBIENT_SCRIM_TINT
import com.yfuse.core.designsystem.AmbientLight
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.ambientSeekAccent
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.lightFeedback
import com.yfuse.core.designsystem.lightOnAppear
import com.yfuse.core.designsystem.lightOnChange
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
import com.yfuse.core.designsystem.rememberLightFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

/**
 * Player top chrome after the control hierarchy was simplified.
 *
 * Episode navigation belongs with the playback controls at the bottom. Cast and overflow are
 * session-level actions, so they live at the top where they no longer compete with subtitles,
 * audio, danmaku and episode navigation for the same narrow strip.
 */
@Composable
internal fun RefinedTopBar(
    title: String,
    subtitle: String,
    filled: Boolean,
    dolbyVision: Boolean,
    dolbyAtmos: Boolean,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onToggleFill: () -> Unit,
    onOpenCast: () -> Unit,
    onOpenMore: () -> Unit,
    watchConnected: Boolean,
    unreadChat: Boolean,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
    /** 氛围光, read only inside the scrim's draw node; null keeps the plain black scrim. */
    ambientLight: State<AmbientLight>? = null,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    cssLinearGradient(
                        180f,
                        0f to ambientScrim(ambientLight?.value, alpha = 0.44f),
                        1f to Color.Transparent,
                    ),
                )
            }.padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleControl(AppIcons.Close, "关闭播放器", 28.dp, 12.dp, onClick = onBack)
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        title,
                        style = AppTypography.body.strong,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Both badges are discovered from the stream, not from the library entry, so
                    // they land a beat after the title they sit beside. They expand out of it
                    // rather than appearing on top of it, which is also what stops the title
                    // from snapping narrower under them.
                    AnimatedVisibility(
                        visible = dolbyVision,
                        enter = barControlEnter(reduceMotion),
                        exit = barControlExit(reduceMotion),
                    ) {
                        DolbyChip("VISION", Color.White.copy(alpha = 0.88f))
                    }
                    AnimatedVisibility(
                        visible = dolbyAtmos,
                        enter = barControlEnter(reduceMotion),
                        exit = barControlExit(reduceMotion),
                    ) {
                        DolbyChip("ATMOS", Color.White.copy(alpha = 0.88f))
                    }
                }
                // The readout is assembled from engine diagnostics, so it arrives after the title
                // and goes away again whenever a source stops reporting. Growing the line rather
                // than inserting it keeps the title from hopping up and down the bar. The last
                // non-empty text is kept so the line still has something to say on its way out.
                val shownSubtitle = rememberLastNonNull(subtitle.takeIf(String::isNotEmpty)).orEmpty()
                AnimatedVisibility(
                    visible = subtitle.isNotEmpty(),
                    enter = barLineEnter(reduceMotion),
                    exit = barLineExit(reduceMotion),
                ) {
                    Text(
                        shownSubtitle,
                        style = AppTypography.caption.regular,
                        color = Color.White.copy(alpha = 0.44f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerClock()
            PlayerBatteryStatus()
            Spacer(Modifier.width(6.dp))
            AnimatedVisibility(
                visible = watchConnected,
                enter = barControlEnter(reduceMotion),
                exit = barControlExit(reduceMotion),
            ) {
                // 有新消息 is a change of treatment on one key rather than a second key, so the
                // disc fills through a crossfade instead of being swapped for a filled one.
                AnimatedContent(
                    targetState = unreadChat,
                    modifier = Modifier.lightOnChange(unreadChat, emitWhen = unreadChat),
                    contentKey = { it },
                    transitionSpec = { barSwapTransform(reduceMotion) },
                    label = "player-chat-unread",
                ) { unread ->
                    CircleControl(
                        AppIcons.Chat,
                        if (unread) "房间聊天，有新消息" else "房间聊天",
                        28.dp,
                        12.dp,
                        filled = unread,
                        onClick = onOpenChat,
                    )
                }
            }
            CircleControl(
                AppIcons.PictureInPicture,
                "小窗播放",
                28.dp,
                12.dp,
                onClick = onEnterPictureInPicture,
            )
            // One key with two readings, so the glyph dissolves into the other one. The key keeps
            // its size through the swap, which is the whole reason there is no size transform.
            AnimatedContent(
                targetState = filled,
                contentKey = { it },
                transitionSpec = { barSwapTransform(reduceMotion) },
                label = "player-aspect-mode",
            ) { fill ->
                CircleControl(
                    icon = if (fill) AppIcons.AspectFill else AppIcons.AspectFit,
                    description = if (fill) "画面比例：填充" else "画面比例：适应",
                    size = 28.dp,
                    iconSize = 12.dp,
                    onClick = onToggleFill,
                )
            }
            CircleControl(
                AppIcons.Cast,
                "投屏",
                28.dp,
                12.dp,
                onClick = onOpenCast,
            )
            CircleControl(
                AppIcons.More,
                "更多",
                28.dp,
                12.dp,
                onClick = onOpenMore,
            )
        }
    }
}

/**
 * Bottom playback chrome with a stable progress row. Auto-skip is deliberately not a child of
 * this Column, so appearing/disappearing countdown text can never move the seek bar vertically.
 */
@Composable
internal fun RefinedBottomBar(
    state: PlaybackTransportState,
    seekLocked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    trickplay: TrickplayStoryboard?,
    progressMarkers: List<PlaybackProgressMarker>,
    hasEpisodes: Boolean,
    onOpenEpisodes: () -> Unit,
    hasMultipleSources: Boolean,
    onOpenSources: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSpeed: () -> Unit,
    skipSettingsAvailable: Boolean,
    onOpenSkipSettings: () -> Unit,
    danmakuEnabled: Boolean,
    onOpenDanmaku: () -> Unit,
    artworkUrl: String?,
    artworkIdentity: Any?,
    modifier: Modifier = Modifier,
    /** 氛围光; the scrim reads it per frame, the seek accent follows its mean. Null keeps both plain. */
    ambientLight: State<AmbientLight>? = null,
) {
    // A new timeline sample arrives twice a second, and this function is called with it. Only
    // this frame stops here: everything below takes the holder and reads it from a draw or a
    // layout lambda, so a new position repaints the rail instead of recomposing the transport
    // row, the six chip keys, their three AnimatedVisibility scopes and the full-width scrim.
    val timeline = rememberUpdatedState(state)
    // The identity is usually a boxed item index. Held by value rather than by box, so the bar
    // below compares equal to last frame's even when the platform hands out a new Integer.
    val stableArtworkIdentity = remember(artworkIdentity) { artworkIdentity }
    RefinedBottomBarContent(
        timeline = timeline,
        buttons = state.buttons,
        durationMs = state.durationMs,
        speed = state.speed,
        seekLocked = seekLocked,
        onPlayPause = onPlayPause,
        onPrevious = onPrevious,
        onNext = onNext,
        onSeek = onSeek,
        onScrub = onScrub,
        trickplay = trickplay,
        progressMarkers = progressMarkers,
        hasEpisodes = hasEpisodes,
        onOpenEpisodes = onOpenEpisodes,
        hasMultipleSources = hasMultipleSources,
        onOpenSources = onOpenSources,
        onOpenSubtitles = onOpenSubtitles,
        onOpenAudio = onOpenAudio,
        onOpenSpeed = onOpenSpeed,
        skipSettingsAvailable = skipSettingsAvailable,
        onOpenSkipSettings = onOpenSkipSettings,
        danmakuEnabled = danmakuEnabled,
        onOpenDanmaku = onOpenDanmaku,
        artworkUrl = artworkUrl,
        artworkIdentity = stableArtworkIdentity,
        modifier = modifier,
        ambientLight = ambientLight,
    )
}

@Composable
private fun RefinedBottomBarContent(
    timeline: State<PlaybackTransportState>,
    buttons: PlaybackButtonState,
    durationMs: Long,
    speed: Float,
    seekLocked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    trickplay: TrickplayStoryboard?,
    progressMarkers: List<PlaybackProgressMarker>,
    hasEpisodes: Boolean,
    onOpenEpisodes: () -> Unit,
    hasMultipleSources: Boolean,
    onOpenSources: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSpeed: () -> Unit,
    skipSettingsAvailable: Boolean,
    onOpenSkipSettings: () -> Unit,
    danmakuEnabled: Boolean,
    onOpenDanmaku: () -> Unit,
    artworkUrl: String?,
    artworkIdentity: Any?,
    modifier: Modifier = Modifier,
    ambientLight: State<AmbientLight>? = null,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // Where the finger left the thumb. Read from derived state only, never from composition:
    // under a drag it changes sixty times a second.
    val scrubbed = remember { mutableStateOf<Float?>(null) }
    // The fraction a committed seek is still waiting for the engine to reach, and the one a
    // cancelled drag is travelling back from.
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    var cancelledFrom by remember { mutableStateOf<Float?>(null) }
    val releasing = remember { mutableStateOf<Float?>(null) }
    val scrubbing = remember { derivedStateOf { scrubbed.value != null } }
    val shownFraction =
        remember {
            derivedStateOf {
                scrubbed.value
                    ?: releasing.value
                    ?: playbackProgressFraction(timeline.value.positionMs, timeline.value.durationMs)
            }
        }
    val bufferedFraction =
        remember {
            derivedStateOf {
                playbackProgressFraction(timeline.value.bufferedPositionMs, timeline.value.durationMs)
            }
        }
    val shownPositionMs =
        remember {
            {
                val held = scrubbed.value ?: releasing.value
                if (held != null) scrubPositionMs(held, timeline.value.durationMs) else timeline.value.positionMs
            }
        }
    // A committed seek is not reported back for about half a second. Clearing the scrubbed
    // fraction on release therefore threw the thumb back to where the film still was and then
    // forward again — twice the distance, in the wrong order. It stays where the finger left it
    // until the engine's own position arrives there, or until the seek has plainly not landed.
    LaunchedEffect(pendingSeek) {
        val target = pendingSeek ?: return@LaunchedEffect
        val targetMs = scrubPositionMs(target, timeline.value.durationMs)
        withTimeoutOrNull(SEEK_COMMIT_TIMEOUT_MS) {
            snapshotFlow { timeline.value.positionMs }
                .first { abs(it - targetMs) <= SEEK_COMMIT_EPSILON_MS }
        }
        scrubbed.value = null
        pendingSeek = null
    }
    // A cancelled drag has no seek behind it, so the thumb travels back to the playhead rather
    // than teleporting to it.
    LaunchedEffect(cancelledFrom, reduceMotion) {
        val from = cancelledFrom
        if (from == null || reduceMotion) {
            releasing.value = null
            cancelledFrom = null
            return@LaunchedEffect
        }
        releasing.value = from
        Animatable(from).animateTo(
            playbackProgressFraction(timeline.value.positionMs, timeline.value.durationMs),
            Motion.settle(),
        ) { releasing.value = value }
        releasing.value = null
        cancelledFrom = null
    }
    val artworkAccent =
        rememberAnimatedArtworkAccent(
            url = artworkUrl,
            fallback = PlayerTokens.progressAccentFallback,
            darkTheme = true,
            identity = artworkIdentity,
        )
    // Read only from the seek bar's draw nodes. The ambient transition already owns the clock.
    val progressAccent =
        remember(ambientLight, artworkAccent) {
            { ambientSeekAccent(ambientLight?.value, artworkAccent.value) }
        }

    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    cssLinearGradient(
                        0f,
                        0f to ambientScrim(ambientLight?.value, alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                )
            }.padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                RefinedTimeText { shownPositionMs().coerceAtLeast(0L) / 1_000L }
            }
            Column(Modifier.weight(1f)) {
                val preview = trickplay
                AnimatedVisibility(
                    visible = scrubbing.value && preview != null,
                    enter =
                        if (reduceMotion) {
                            EnterTransition.None
                        } else {
                            fadeIn(tween(Motion.QUICK, easing = Motion.Curve)) +
                                scaleIn(Motion.settle(), initialScale = 0.92f) +
                                expandVertically(Motion.settle(), expandFrom = Alignment.Bottom)
                        },
                    exit =
                        if (reduceMotion) {
                            ExitTransition.None
                        } else {
                            fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
                                scaleOut(tween(Motion.QUICK, easing = Motion.Curve), targetScale = 0.96f) +
                                shrinkVertically(
                                    tween(Motion.QUICK, easing = Motion.Curve),
                                    shrinkTowards = Alignment.Bottom,
                                )
                        },
                ) {
                    if (preview != null) {
                        val previewHeight =
                            (
                                RefinedTrickplayPreviewWidth.value * preview.height /
                                    preview.width.coerceAtLeast(1)
                            ).dp + 30.dp
                        BoxWithConstraints(
                            Modifier
                                .fillMaxWidth()
                                .height(previewHeight + 8.dp),
                        ) {
                            // The still follows the finger, so the card is placed rather than
                            // laid out again on every sample of it.
                            val trackWidthPx = constraints.maxWidth
                            TrickplayPreview(
                                storyboard = preview,
                                positionMs = shownPositionMs(),
                                modifier =
                                    Modifier
                                        .offset {
                                            val cardPx = RefinedTrickplayPreviewWidth.roundToPx()
                                            IntOffset(
                                                x =
                                                    (trackWidthPx * shownFraction.value - cardPx / 2f)
                                                        .toInt()
                                                        .coerceIn(0, (trackWidthPx - cardPx).coerceAtLeast(0)),
                                                y = 0,
                                            )
                                        }.lightOnAppear(enabled = !reduceMotion),
                            )
                        }
                    }
                }
                StandardSeekBar(
                    fraction = shownFraction,
                    bufferedFraction = bufferedFraction,
                    positionMs = shownPositionMs,
                    durationMs = durationMs,
                    progressMarkers = progressMarkers,
                    accent = progressAccent,
                    enabled = !seekLocked && durationMs > 0L,
                    showTimeBubble = trickplay == null,
                    onScrubTo = {
                        scrubbed.value = it
                        if (pendingSeek != null) pendingSeek = null
                        if (cancelledFrom != null) cancelledFrom = null
                        releasing.value = null
                        onScrub()
                    },
                    onCommit = {
                        // Stays on the committed fraction; the effect above lets go of it once
                        // the engine reports its way there.
                        scrubbed.value = it
                        pendingSeek = it
                        onSeek(scrubPositionMs(it, timeline.value.durationMs))
                    },
                    onCancel = {
                        // Handed to the release animation in the same breath, so the thumb never
                        // shows the playhead's position for the frame in between.
                        releasing.value = scrubbed.value
                        cancelledFrom = scrubbed.value
                        scrubbed.value = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                RefinedTimeText { durationMs.coerceAtLeast(0L) / 1_000L }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportRow(
                state = buttons,
                locked = seekLocked,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekBackward = {
                    onSeek((timeline.value.positionMs - REFINED_SEEK_STEP_MS).coerceAtLeast(0L))
                },
                onSeekForward = {
                    val latest = timeline.value
                    val target = latest.positionMs + REFINED_SEEK_STEP_MS
                    onSeek(if (latest.durationMs > 0L) target.coerceAtMost(latest.durationMs) else target)
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(AppIcons.Subtitle, "字幕", 26.dp, 12.dp, onClick = onOpenSubtitles)
                CircleControl(AppIcons.AudioTrack, "音轨", 26.dp, 12.dp, onClick = onOpenAudio)
                CircleControl(
                    icon = AppIcons.Danmaku,
                    description = if (danmakuEnabled) "弹幕，已开启" else "弹幕，已关闭",
                    size = 26.dp,
                    iconSize = 12.dp,
                    active = danmakuEnabled,
                    onClick = onOpenDanmaku,
                )
                RefinedSpeedControl(speed, onOpenSpeed)
                // Which of these three exist is decided by the item, and the item changes under
                // the bar every time the queue advances: a source list resolves, a series gains
                // 片头 markers, a film has no 选集. Each one used to blink into the cluster and
                // shove its neighbours across; the row's width follows them instead.
                AnimatedVisibility(
                    visible = hasMultipleSources,
                    enter = barControlEnter(reduceMotion),
                    exit = barControlExit(reduceMotion),
                ) {
                    CircleControl(
                        AppIcons.PlaybackSource,
                        "播放服务器",
                        26.dp,
                        12.dp,
                        onClick = onOpenSources,
                    )
                }
                AnimatedVisibility(
                    visible = skipSettingsAvailable,
                    enter = barControlEnter(reduceMotion),
                    exit = barControlExit(reduceMotion),
                ) {
                    CircleControl(
                        AppIcons.SkipMarkers,
                        "标记片头片尾",
                        26.dp,
                        12.dp,
                        onClick = onOpenSkipSettings,
                    )
                }
                AnimatedVisibility(
                    visible = hasEpisodes,
                    enter = barControlEnter(reduceMotion),
                    exit = barControlExit(reduceMotion),
                ) {
                    CircleControl(
                        icon = AppIcons.EpisodeList,
                        description = "选集",
                        size = 26.dp,
                        iconSize = 12.dp,
                        onClick = onOpenEpisodes,
                    )
                }
            }
        }
    }
}

/** Compact non-layout-affecting countdown. Tapping it cancels this one automatic skip. */
@Composable
internal fun CompactAutoSkipPill(
    label: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .height(30.dp)
            .glass(
                shape = AppShapes.pill,
                fill = Color.Black.copy(alpha = 0.58f),
                border = Color.White.copy(alpha = 0.22f),
            ).noRippleClickable(onCancel)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = AppTypography.caption.medium,
            color = Color.White.copy(alpha = 0.88f),
            maxLines = 1,
        )
        Icon(
            AppIcons.Close,
            contentDescription = "取消自动跳过",
            tint = Color.White.copy(alpha = 0.68f),
            modifier = Modifier.size(10.dp),
        )
    }
}

private class RetainedLine<T>(
    var value: T?,
)

/**
 * The last value that was actually there, so a line can finish its exit still saying something.
 *
 * The cache is written from a [SideEffect] rather than during composition: composition can be
 * abandoned and replayed, and filling a cache from it is a side effect either way. A plain holder
 * rather than a state, for the same reason [ChromeContent] uses one — remembering the outgoing
 * value must not cost the bar a second pass.
 */
@Composable
private fun <T : Any> rememberLastNonNull(value: T?): T? {
    val retained = remember { RetainedLine(value) }
    SideEffect { if (value != null) retained.value = value }
    return value ?: retained.value
}

/**
 * How a control that comes and goes inside one of the bars arrives and leaves.
 *
 * Expanding, not only fading: these rows are laid out horizontally with a fixed gap, so a control
 * that simply appears shoves every neighbour sideways in a single frame — the 选集 key landing
 * when a series loads used to move 字幕, 音轨 and 弹幕 with it. The row's width follows the
 * control instead. Under 减弱动态效果 the fade stays and the travel goes: chrome appearing over a
 * moving picture is harder to follow than chrome that fades, and a fade is not the kind of motion
 * that setting exists to suppress.
 */
private fun barControlEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(snap())
    } else {
        fadeIn(tween(Motion.QUICK, easing = Motion.Curve)) +
            expandHorizontally(
                tween(Motion.QUICK, easing = Motion.Curve),
                expandFrom = Alignment.Start,
            )
    }

private fun barControlExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(snap())
    } else {
        fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
            shrinkHorizontally(
                tween(Motion.QUICK, easing = Motion.Curve),
                shrinkTowards = Alignment.Start,
            )
    }

/** [barControlEnter] for a line stacked under another one rather than a key beside one. */
private fun barLineEnter(reduceMotion: Boolean): EnterTransition =
    if (reduceMotion) {
        fadeIn(snap())
    } else {
        fadeIn(tween(Motion.QUICK, easing = Motion.Curve)) +
            expandVertically(
                tween(Motion.QUICK, easing = Motion.Curve),
                expandFrom = Alignment.Top,
            )
    }

private fun barLineExit(reduceMotion: Boolean): ExitTransition =
    if (reduceMotion) {
        fadeOut(snap())
    } else {
        fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
            shrinkVertically(
                tween(Motion.QUICK, easing = Motion.Curve),
                shrinkTowards = Alignment.Top,
            )
    }

/**
 * One control changing what it shows without changing size.
 *
 * Deliberately no size transform: these keys are fixed discs and fixed rings, and 1× handing over
 * to 1.75× must not make the key breathe. The bars' layout stays where it is.
 */
private fun AnimatedContentTransitionScope<*>.barSwapTransform(reduceMotion: Boolean): ContentTransform {
    val duration = if (reduceMotion) 0 else Motion.QUICK
    return (
        fadeIn(tween(duration, easing = Motion.Curve)) togetherWith
            fadeOut(tween(duration, easing = Motion.Curve))
    ).using(null)
}

@Composable
private fun RefinedSpeedControl(
    speed: Float,
    onClick: () -> Unit,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val label = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"
    Box(
        Modifier.noRippleClickable(onClick).size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = label,
                contentKey = { it },
                transitionSpec = { barSwapTransform(reduceMotion) },
                label = "player-speed-readout",
            ) { current ->
                Text(
                    current,
                    style = AppTypography.caption.strong,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * A clock that reads its own source.
 *
 * [seconds] is a lambda rather than a number so the position lands here instead of in the bar
 * that contains it: the readout changes once a second, and the row around it never has to.
 */
@Composable
private fun RefinedTimeText(seconds: () -> Long) {
    val latestSeconds by rememberUpdatedState(seconds)
    val label by remember { derivedStateOf { formatTime(latestSeconds() * 1_000L) } }
    Text(
        label,
        style = AppTypography.caption.regular,
        color = PlayerTokens.timeTextLandscape,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

/** The scrim's dark end: black, warmed by 30% of the light's mean while the light is on. */
private fun ambientScrim(
    light: AmbientLight?,
    alpha: Float,
): Color = lerp(Color.Black, light?.mean ?: Color.Black, AMBIENT_SCRIM_TINT).copy(alpha = alpha)

/**
 * Artwork-aware scrubber. The current still/poster supplies one restrained accent that is reused
 * for the played rail, thumb halo and semantic chapter markers. The unplayed rail stays neutral and
 * the buffer is a low-saturation tint, keeping time and control readability stable across artwork.
 */
@Composable
private fun StandardSeekBar(
    /** The played fraction as a state, so a new sample of it reaches the rail and nothing else. */
    fraction: State<Float>,
    bufferedFraction: State<Float>,
    positionMs: () -> Long,
    durationMs: Long,
    progressMarkers: List<PlaybackProgressMarker>,
    accent: () -> Color,
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Without a trickplay preview, the scrubbed time rides above the thumb instead. */
    showTimeBubble: Boolean = false,
) {
    val haptics = LocalHaptics.current
    val light = rememberLightFeedback(enabled)
    val currentLight by rememberUpdatedState(light)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val magnetRadiusPx = with(LocalDensity.current) { SeekMarkerMagnetRadius.toPx() }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    var snappedMarkerIndex by remember { mutableStateOf<Int?>(null) }
    val markerFractions =
        remember(progressMarkers, durationMs) {
            progressMarkers.map { marker ->
                (marker.positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            }
        }
    val latestOnScrubTo by rememberUpdatedState(onScrubTo)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestOnCancel by rememberUpdatedState(onCancel)
    val latestPositionMs by rememberUpdatedState(positionMs)
    // Where the thumb is, read from the offset and draw lambdas below rather than from
    // composition. Under a finger this value follows the pointer — sixty samples a second — and
    // reading it here recomposed the whole bar, its markers, its halo and its bubble on every
    // one of them, to move three boxes that only ever needed placing again. The played fraction
    // now arrives as a state for the same reason: off a finger it still changes twice a second.
    val shownFraction =
        remember {
            derivedStateOf { if (dragging) dragFraction else fraction.value.coerceIn(0f, 1f) }
        }
    val interaction =
        animateFloatAsState(
            targetValue = if (dragging) 1f else 0f,
            animationSpec = Motion.pressSpec(pressed = dragging, reduceMotion = reduceMotion),
            label = "artwork-seek-interaction",
        )
    val keyStep = (5_000f / durationMs.coerceAtLeast(1L)).coerceIn(0.01f, 0.1f)
    val commit: (Float) -> Boolean = { target ->
        if (!enabled) {
            false
        } else {
            latestOnCommit(target.coerceIn(0f, 1f))
            currentLight.emit(LightEffect.Converge, fractionX = target)
            true
        }
    }

    fun magneticTarget(rawFraction: Float): MagneticSeekTarget =
        magneticSeekTarget(
            rawFraction = rawFraction,
            markerFractions = markerFractions,
            thresholdFraction =
                (magnetRadiusPx / widthPx.coerceAtLeast(1)).coerceAtMost(0.04f),
        )

    fun updateDrag(rawFraction: Float) {
        val target = magneticTarget(rawFraction)
        if (target.markerIndex != null && target.markerIndex != snappedMarkerIndex) {
            haptics.play(HapticSignal.Select)
        }
        snappedMarkerIndex = target.markerIndex
        dragFraction = target.fraction
        latestOnScrubTo(target.fraction)
        currentLight.emit(LightEffect.Trail, fractionX = target.fraction)
    }

    Box(
        modifier
            .height(44.dp)
            .lightFeedback(light)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .drawWithCache {
                val outline = AppShapes.thumb.createOutline(size, layoutDirection, this)
                val stroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    if (focused) drawOutline(outline, accent().copy(alpha = 0.72f), style = stroke)
                }
            }.semantics {
                stateDescription = "播放进度 ${formatTime(latestPositionMs())} / ${formatTime(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(shownFraction.value, 0f..1f)
                if (enabled) setProgress { commit(it) } else disabled()
            }.onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> commit(shownFraction.value - keyStep)
                    Key.DirectionRight, Key.DirectionUp -> commit(shownFraction.value + keyStep)
                    else -> false
                }
            }.onFocusChanged { focused = it.isFocused }
            .focusable(enabled)
            .let { base ->
                if (!enabled) return@let base
                base
                    .pointerInput(enabled) {
                        detectTapGestures { offset ->
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            val target = magneticTarget((offset.x / width).coerceIn(0f, 1f))
                            if (commit(target.fraction)) {
                                haptics.play(HapticSignal.Select)
                            }
                        }
                    }.pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                dragging = true
                                snappedMarkerIndex = null
                                haptics.play(HapticSignal.Select)
                                updateDrag((offset.x / width).coerceIn(0f, 1f))
                            },
                            onDragEnd = {
                                dragging = false
                                snappedMarkerIndex = null
                                haptics.play(HapticSignal.Confirm)
                                latestOnCommit(dragFraction)
                                currentLight.emit(LightEffect.Converge, fractionX = dragFraction)
                            },
                            onDragCancel = {
                                dragging = false
                                snappedMarkerIndex = null
                                latestOnCancel()
                                currentLight.clear()
                            },
                        ) { change, _ ->
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            updateDrag((change.position.x / width).coerceIn(0f, 1f))
                        }
                    }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Both rails keep fixed geometry; progress invalidates paint, not child measurement.
        val buffered = remember(durationMs) { Animatable(0f) }
        val moving = !reduceMotion && LocalRouteVisible.current
        // Collected rather than read in composition: the buffer advances on the same clock as
        // the playhead, and the rail is the only thing that has anything to do with it. The
        // first sample is where the buffer already is, so raising the chrome never replays it.
        LaunchedEffect(durationMs, moving) {
            var arrived = false
            snapshotFlow { bufferedFraction.value.coerceIn(0f, 1f) }.collect { target ->
                if (!arrived || !moving || target < buffered.value) {
                    arrived = true
                    buffered.snapTo(target)
                } else {
                    buffered.animateTo(target, tween(Motion.STANDARD, easing = Motion.Curve))
                }
            }
        }
        // The rail is laid out at its pressed height and scaled down to its resting one, so the
        // press spring repaints a layer instead of re-measuring the row sixty times a second.
        // The pill's radius follows the scale, which is what keeps the ends round at both sizes.
        Box(
            Modifier
                .fillMaxWidth()
                .height(SeekTrackPressedHeight)
                .graphicsLayer {
                    scaleY = (SeekTrackRestingHeight + SeekTrackGrowth * interaction.value) / SeekTrackPressedHeight
                }.clip(AppShapes.track)
                .drawBehind {
                    val color = accent()
                    val played = shownFraction.value
                    val playedWidth = size.width * played
                    drawRect(Color.White.copy(alpha = 0.16f))
                    drawRect(
                        lerp(color, Color.Gray, 0.62f).copy(alpha = 0.50f),
                        size =
                            androidx.compose.ui.geometry.Size(
                                size.width * buffered.value.coerceIn(played, 1f),
                                size.height,
                            ),
                    )
                    if (playedWidth > 0f) {
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(lerp(color, Color.Black, 0.14f), lerp(color, Color.White, 0.24f)),
                                endX = playedWidth,
                            ),
                            size =
                                androidx.compose.ui.geometry
                                    .Size(playedWidth, size.height),
                        )
                    }
                },
        )

        // The same list the magnet snaps to, rather than a second copy of the same arithmetic.
        progressMarkers.forEachIndexed { index, marker ->
            val markerFraction = markerFractions.getOrElse(index) { 0f }
            Box(
                Modifier
                    .width(if (marker.emphasized) 2.dp else 1.dp)
                    .height(if (marker.emphasized) 9.dp else 7.dp)
                    .offset {
                        IntOffset(
                            x =
                                (widthPx * markerFraction)
                                    .toInt()
                                    .coerceIn(0, (widthPx - 1).coerceAtLeast(0)),
                            y = 7.dp.roundToPx(),
                        )
                    }.clip(AppShapes.track)
                    .drawBehind { drawRect(if (marker.emphasized) accent() else accent().copy(alpha = 0.70f)) },
            )
        }

        // Halo and thumb are laid out at their pressed diameters and scaled about their own
        // centres, so growing under a finger never moves the boxes they sit in.
        Box(
            Modifier
                .size(SeekHaloPressedDiameter)
                .offset {
                    val haloPx = SeekHaloPressedDiameter.roundToPx()
                    IntOffset(
                        x =
                            (widthPx * shownFraction.value - haloPx / 2f)
                                .toInt()
                                .coerceIn(-haloPx / 2, (widthPx - haloPx / 2).coerceAtLeast(0)),
                        y = 0,
                    )
                }.graphicsLayer {
                    alpha = if (enabled) 0.28f + 0.18f * interaction.value else 0.10f
                    val scale =
                        (SeekHaloRestingDiameter + SeekHaloGrowth * interaction.value) / SeekHaloPressedDiameter
                    scaleX = scale
                    scaleY = scale
                }.drawBehind { drawCircle(accent()) },
        )

        Box(
            Modifier
                .size(SeekThumbPressedDiameter)
                .offset {
                    val thumbPx = SeekThumbPressedDiameter.roundToPx()
                    IntOffset(
                        x =
                            (widthPx * shownFraction.value - thumbPx / 2f)
                                .toInt()
                                .coerceIn(-thumbPx / 2, (widthPx - thumbPx / 2).coerceAtLeast(0)),
                        y = 0,
                    )
                }.graphicsLayer {
                    alpha = if (enabled) 1f else 0.45f
                    val scale =
                        (SeekThumbRestingDiameter + SeekThumbGrowth * interaction.value) / SeekThumbPressedDiameter
                    scaleX = scale
                    scaleY = scale
                }.background(Color.White, CircleShape)
                .drawWithCache {
                    val stroke = Stroke(2.dp.toPx())
                    onDrawBehind {
                        drawCircle(
                            lerp(accent(), Color.White, 0.24f),
                            radius = (size.minDimension - stroke.width) / 2f,
                            style = stroke,
                        )
                    }
                },
        )

        // The storyboard usually resolves a beat into the drag, and the bubble is what the
        // preview replaces. Cutting it out with an `if` took the readout away mid-gesture; the
        // two hand over to each other instead.
        AnimatedVisibility(
            visible = showTimeBubble,
            enter = if (reduceMotion) fadeIn(snap()) else fadeIn(tween(Motion.QUICK, easing = Motion.Curve)),
            exit = if (reduceMotion) fadeOut(snap()) else fadeOut(tween(Motion.QUICK, easing = Motion.Curve)),
        ) {
            // Grows out of the thumb on the same spring as the track, and settles back into
            // it on release — the preview bubble's smaller sibling.
            Box(
                Modifier
                    .width(SeekTimeBubbleWidth)
                    .height(SeekTimeBubbleHeight)
                    .offset {
                        val bubblePx = SeekTimeBubbleWidth.roundToPx()
                        IntOffset(
                            x =
                                (widthPx * shownFraction.value - bubblePx / 2f)
                                    .toInt()
                                    .coerceIn(0, (widthPx - bubblePx).coerceAtLeast(0)),
                            y = -SeekTimeBubbleRise.roundToPx(),
                        )
                    }.graphicsLayer {
                        alpha = interaction.value
                        val scale = 0.8f + 0.2f * interaction.value
                        scaleX = scale
                        scaleY = scale
                        translationY = 6.dp.toPx() * (1f - interaction.value)
                    }.background(Color.Black.copy(alpha = 0.58f), AppShapes.pill)
                    .border(1.dp, Color.White.copy(alpha = 0.20f), AppShapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                val bubbleLabel by remember { derivedStateOf { formatTime(latestPositionMs()) } }
                Text(bubbleLabel, style = AppTypography.caption.medium, color = Color.White)
            }
        }
    }
}

private val SeekTimeBubbleWidth = 60.dp
private val SeekTimeBubbleHeight = 22.dp
private val SeekTimeBubbleRise = 26.dp

/**
 * Rail and thumb geometry.
 *
 * Under a finger the track thickens to nearly twice itself and the thumb grows with it: the thing
 * being dragged should look like it can take the weight. Both are laid out at the pressed size and
 * scaled down to the resting one, so the press spring never re-measures the row it sits in.
 */
private val SeekTrackRestingHeight = 3.5.dp
private val SeekTrackGrowth = 2.5.dp
private val SeekTrackPressedHeight = SeekTrackRestingHeight + SeekTrackGrowth
private val SeekThumbRestingDiameter = 10.dp
private val SeekThumbGrowth = 3.dp
private val SeekThumbPressedDiameter = SeekThumbRestingDiameter + SeekThumbGrowth
private val SeekHaloRestingDiameter = 20.dp
private val SeekHaloGrowth = 8.dp
private val SeekHaloPressedDiameter = SeekHaloRestingDiameter + SeekHaloGrowth

/** How close the engine has to land for a committed seek to hand the thumb back, and how long. */
private const val SEEK_COMMIT_EPSILON_MS = 1_000L
private const val SEEK_COMMIT_TIMEOUT_MS = 1_500L

internal data class MagneticSeekTarget(
    val fraction: Float,
    val markerIndex: Int?,
)

internal fun magneticSeekTarget(
    rawFraction: Float,
    markerFractions: List<Float>,
    thresholdFraction: Float,
): MagneticSeekTarget {
    val raw = rawFraction.coerceIn(0f, 1f)
    val nearest = markerFractions.indices.minByOrNull { index -> abs(markerFractions[index] - raw) }
    return if (
        nearest != null &&
        abs(markerFractions[nearest] - raw) <= thresholdFraction.coerceAtLeast(0f)
    ) {
        MagneticSeekTarget(markerFractions[nearest].coerceIn(0f, 1f), nearest)
    } else {
        MagneticSeekTarget(raw, null)
    }
}

private val RefinedTrickplayPreviewWidth = 160.dp
private val SeekMarkerMagnetRadius = 14.dp
private const val REFINED_SEEK_STEP_MS = 10_000L
