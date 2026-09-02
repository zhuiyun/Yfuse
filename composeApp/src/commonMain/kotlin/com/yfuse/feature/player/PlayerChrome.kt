package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.DolbyChip
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.util.currentClockTime
import kotlinx.coroutines.delay

/** A forgiving touch target around the visually slim playback track and its boundary labels. */
private val SeekBarTouchHeight = 48.dp

private val TrickplayPreviewWidth = 160.dp

private val LiquidProgressBlue = Color(0xFF4F8DFF)

private val LiquidProgressViolet = Color(0xFF7D5FF6)

@Composable
internal fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onExternalPlayer: (() -> Unit)?,
    onBack: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("播放遇到问题", style = AppTypography.section.strong, color = Color.White)
            Text(
                message,
                style = AppTypography.body.regular,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "返回",
                    style = AppTypography.body.medium,
                    color = Color.White.copy(alpha = 0.82f),
                    modifier =
                        Modifier
                            .glass(
                                shape = AppShapes.pill,
                                fill = Color.White.copy(alpha = 0.10f),
                                border = Color.White.copy(alpha = 0.28f),
                            ).noRippleClickable(onBack)
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                Text(
                    "重试",
                    style = AppTypography.body.strong,
                    color = Color(0xFF1B2436),
                    modifier =
                        Modifier
                            .glass(
                                shape = AppShapes.pill,
                                fill = Color.White.copy(alpha = 0.68f),
                                border = Color.White.copy(alpha = 0.88f),
                            ).noRippleClickable(onRetry)
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                )
                onExternalPlayer?.let { open ->
                    Text(
                        "外部播放器",
                        style = AppTypography.body.strong,
                        color = Color.White,
                        modifier =
                            Modifier
                                .glass(
                                    shape = AppShapes.pill,
                                    fill = Color.White.copy(alpha = 0.16f),
                                    border = Color.White.copy(alpha = 0.36f),
                                ).noRippleClickable(open)
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/**
 * Wall-clock time in the control overlay.
 *
 * Composed only while the controls are up, so the ticking coroutine lives exactly as long as
 * the thing it updates. It re-reads on the minute boundary rather than every minute from
 * whenever it happened to start, so the displayed minute changes when the real one does
 * instead of up to 59 seconds late.
 */
@Composable
internal fun PlayerClock() {
    var now by remember { mutableStateOf(currentClockTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            val millisIntoMinute = System.currentTimeMillis() % 60_000L
            delay(60_000L - millisIntoMinute)
            now = currentClockTime()
        }
    }
    Text(
        now,
        style = AppTypography.caption.strong,
        color = Color.White.copy(alpha = 0.82f),
        maxLines = 1,
    )
}

/** `padding:14px 22px`, `linear-gradient(180deg,rgba(0,0,0,.5),transparent)`. */
@Composable
internal fun TopBar(
    title: String,
    /**
     * `alphatv · 1080P · EXO · HEVC · 18.1 Mbps · 60.0 fps` — where this is coming from and
     * how it is actually being played.
     *
     * This line used to be `1080P · 音轨 2 · 字幕 3`, which counts things the 字幕 tab
     * already lists and answers no question anyone has while watching. What is worth
     * standing here is the pair that cannot be found anywhere else without opening a panel:
     * which server the file is on, and whether it is direct-playing at full bitrate. Both
     * are what you look at when the picture stutters.
     */
    subtitle: String,
    filled: Boolean,
    hasEpisodes: Boolean,
    dolbyVision: Boolean,
    dolbyAtmos: Boolean,
    onBack: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onOpenDrawer: () -> Unit,
    onToggleFill: () -> Unit,
    watchConnected: Boolean,
    unreadChat: Boolean,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    180f,
                    0f to Color.Black.copy(alpha = 0.5f),
                    1f to Color.Transparent,
                ),
            ).padding(horizontal = 22.dp, vertical = 14.dp),
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
                    // Only what this file actually carries — the same rule the detail page
                    // follows. A badge that is always there says nothing.
                    if (dolbyVision) DolbyChip("VISION", Color.White.copy(alpha = 0.88f))
                    if (dolbyAtmos) DolbyChip("ATMOS", Color.White.copy(alpha = 0.88f))
                }
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = AppTypography.caption.medium,
                        color = Color.White.copy(alpha = 0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The player hides the status bar, so while a film is running this is the only
            // clock on screen. "How long has this been going" and "can I finish it before I
            // have to leave" are questions people answer by looking up, and until now the
            // answer required leaving the film.
            PlayerClock()
            Spacer(Modifier.width(6.dp))
            if (watchConnected) {
                CircleControl(
                    AppIcons.Chat,
                    if (unreadChat) "房间聊天，有新消息" else "房间聊天",
                    28.dp,
                    12.dp,
                    filled = unreadChat,
                    onClick = onOpenChat,
                )
            }
            if (hasEpisodes) {
                CircleControl(AppIcons.EpisodeList, "剧集列表", 28.dp, 13.dp, onClick = onOpenDrawer)
            }
            CircleControl(
                AppIcons.PictureInPicture,
                "小窗播放",
                28.dp,
                12.dp,
                onClick = onEnterPictureInPicture,
            )
            CircleControl(
                // The glyph shows the state you are in, not the one a tap would move to:
                // a control that draws its own opposite is unreadable the moment you stop
                // and look at it, which is exactly when you reach for this one.
                icon = if (filled) AppIcons.AspectFill else AppIcons.AspectFit,
                description = if (filled) "画面比例：填充" else "画面比例：适应",
                size = 28.dp,
                iconSize = 12.dp,
                onClick = onToggleFill,
            )
        }
    }
}

/**
 * 上一集 / 后退 10 秒 / 播放 / 前进 10 秒 / 下一集. Every action has its own key so
 * episode navigation cannot be mistaken for seeking, and the 10-second labels remain visible.
 *
 * It used to float in the centre of the frame at 48 / 58 / 48, with a filled white disc on
 * the play button. That is the worst place to put anything: the middle of a shot is where
 * the subject is, so the one control that is always on screen was always over a face. Down
 * here it shares the gradient the scrubber already needs, and the picture keeps its middle.
 *
 * Rings rather than plates, and small enough that the row reads as one strip with the
 * chips opposite it. The touch target does not shrink with the ring — see [CircleControl].
 *
 * [locked] dims the cluster to half opacity and stops it taking taps: a connected guest can
 * see what the room is doing but does not drive it. Dimming rather than hiding keeps the
 * transport where the eye expects it and makes the reason legible alongside the
 * 「房主控制播放」 banner.
 */
@Composable
internal fun TransportRow(
    state: PlaybackState,
    locked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var bufferingIndicatorVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.buffering) {
        if (!state.buffering) {
            bufferingIndicatorVisible = false
        } else {
            delay(BUFFERING_INDICATOR_DELAY_MS)
            bufferingIndicatorVisible = true
        }
    }
    val visualState =
        transportVisualState(
            playing = state.playing,
            buffering = state.buffering,
            bufferingIndicatorVisible = bufferingIndicatorVisible,
        )
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion

    Row(
        modifier.graphicsLayer { alpha = if (locked) 0.45f else 1f },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleControl(
            AppIcons.Previous,
            "上一集",
            TransportKeySize,
            TransportIconSize,
            enabled = !locked && state.hasPrevious,
            onClick = onPrevious,
        )
        CircleControl(
            AppIcons.SeekBackward10,
            "后退 10 秒",
            TransportKeySize,
            TransportIconSize,
            enabled = !locked && state.durationMs > 0L,
            onClick = onSeekBackward,
        )
        AnimatedContent(
            targetState = visualState,
            transitionSpec = {
                if (reduceMotion) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    (
                        fadeIn(tween(Motion.QUICK, easing = Motion.Curve)) +
                            scaleIn(
                                animationSpec = Motion.settle(),
                                initialScale = 0.82f,
                            )
                    ) togetherWith
                        (
                            fadeOut(tween(Motion.QUICK, easing = Motion.Curve)) +
                                scaleOut(
                                    tween(Motion.QUICK, easing = Motion.Curve),
                                    targetScale = 0.88f,
                                )
                        )
                }
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(TransportKeySize + ControlTouchPadding * 2),
            label = "transport-state",
        ) { visual ->
            when (visual) {
                TransportVisualState.Buffering ->
                    Box(
                        Modifier.size(TransportKeySize + ControlTouchPadding * 2),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                TransportVisualState.Pause,
                TransportVisualState.Play,
                -> {
                    val playing = visual == TransportVisualState.Pause
                    CircleControl(
                        if (playing) AppIcons.Pause else AppIcons.Play,
                        if (playing) "暂停" else "播放",
                        TransportKeySize,
                        TransportIconSize,
                        enabled = !locked && !state.buffering,
                        onClick = onPlayPause,
                    )
                }
            }
        }

        CircleControl(
            AppIcons.SeekForward10,
            "前进 10 秒",
            TransportKeySize,
            TransportIconSize,
            enabled = !locked && state.durationMs > 0L,
            onClick = onSeekForward,
        )

        CircleControl(
            AppIcons.Next,
            "下一集",
            TransportKeySize,
            TransportIconSize,
            enabled = !locked && state.hasNext,
            onClick = onNext,
        )
    }
}

/**
 * The vertical volume bar the rocker raises, in place of the system's own panel.
 *
 * Draggable rather than a read-only readout: once it is on screen and under the thumb, the
 * remaining distance is usually more than a couple of rocker steps, and the alternative
 * (the edge-drag gesture) means dismissing this first.
 *
 * Fill grows upward, so the gesture matches both the rocker and the bar's own shape.
 */
@Composable
internal fun VolumeSlider(
    volume: Float,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val targetFraction = volume.coerceIn(0f, 1f)
    var height by remember { mutableIntStateOf(1) }
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val animatedFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = Motion.settle(reduceMotion),
        label = "volume-level",
    )
    val fraction = if (dragging) targetFraction else animatedFraction
    val adjust: (Float) -> Boolean = { target ->
        onVolume(target.coerceIn(0f, 1f))
        true
    }
    Box(modifier.width(44.dp)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .glass(
                    shape = AppShapes.pill,
                    fill = Color.Black.copy(alpha = 0.56f),
                    border = Color.White.copy(alpha = 0.24f),
                ).padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${(fraction * 100).toInt()}", style = AppTypography.caption.strong, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .width(6.dp)
                    .height(140.dp)
                    .clip(AppShapes.track)
                    .background(Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                // Muted draws no fill at all rather than a zero-height sliver.
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .clip(AppShapes.track)
                            .background(Color.White),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Icon(AppIcons.Volume, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        // The painted rail stays 6dp. This sibling is the focus, semantics and hit layer.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(44.dp)
                .height(140.dp)
                .then(
                    if (focused) {
                        Modifier.border(1.dp, accent.border, AppShapes.thumb)
                    } else {
                        Modifier
                    },
                ).semantics {
                    stateDescription = "音量 ${(fraction * 100).toInt()}%"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, 100)
                    setProgress { adjust(it) }
                }.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown, Key.DirectionLeft -> adjust(fraction - 0.05f)
                        Key.DirectionUp, Key.DirectionRight -> adjust(fraction + 0.05f)
                        else -> false
                    }
                }.onFocusChanged { focused = it.isFocused }
                .focusable()
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(height) {
                    // Bottom of the track is 0, top is 1 — hence the inversion.
                    detectTapGestures { offset -> adjust(1f - offset.y / height) }
                }.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        adjust(1f - change.position.y / height)
                    }
                },
        )
    }
}

/**
 * The pill offering to move the playhead past a 片头 / 片尾.
 *
 * Deliberately outside the show/hide of the rest of the controls: the offer is only good
 * for as long as playback is inside the segment, and making the user summon the controls
 * first would spend a chunk of that window.
 */
@Composable
internal fun SkipPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        label,
        style = AppTypography.body.strong,
        color = Color.White,
        modifier =
            modifier
                .glass(
                    shape = AppShapes.pill,
                    fill = Color.Black.copy(alpha = 0.64f),
                    border = Color.White.copy(alpha = 0.28f),
                ).noRippleClickable(onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

/** "3 秒后跳过片头 · 点击取消" — the label says what will happen and how to stop it. */
internal fun skipCountdownLabel(
    skipSegmentLabel: String?,
    seconds: Int,
): String {
    // 跳过片头 -> 片头. The type's own label is the only place this wording lives.
    val what = skipSegmentLabel?.removePrefix("跳过").orEmpty()
    return "$seconds 秒后跳过$what · 点击取消"
}

/** `padding:14px 22px 16px`, `linear-gradient(0deg,rgba(0,0,0,.55),transparent)`. */
@Composable
internal fun BottomBar(
    state: PlaybackState,
    /** Guest in a room: the scrubber becomes a read-only progress indicator. */
    seekLocked: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    /** Non-null while an automatic skip is counting down; shown under the progress row. */
    skipCountdownLabel: String?,
    onCancelAutoSkip: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    trickplay: TrickplayStoryboard?,
    progressMarkers: List<PlaybackProgressMarker>,
    hasMultipleSources: Boolean,
    onOpenSources: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenSpeed: () -> Unit,
    skipSettingsAvailable: Boolean,
    onOpenSkipSettings: () -> Unit,
    danmakuEnabled: Boolean,
    onOpenDanmaku: () -> Unit,
    onOpenCast: () -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Non-null only while the thumb is held; the engine's position is ignored then
    // so the bar doesn't snap back between ticks.
    var scrubbed by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = scrubbed ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val bufferedFraction =
        if (state.durationMs > 0L) {
            (state.bufferedPositionMs.toFloat() / duration).coerceIn(0f, 1f)
        } else {
            0f
        }
    val shownPosition = scrubbed?.let { scrubPositionMs(it, state.durationMs) } ?: state.positionMs

    Column(
        modifier
            .fillMaxWidth()
            .background(
                cssLinearGradient(
                    0f,
                    0f to Color.Black.copy(alpha = 0.55f),
                    1f to Color.Transparent,
                ),
            ).padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 16.dp),
    ) {
        // Progress row — `gap:10px`, `400 11px Manrope`, `rgba(255,255,255,.75)`.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.height(SeekBarTouchHeight), contentAlignment = Alignment.Center) {
                RollingTimeText(shownPosition)
            }
            Column(Modifier.weight(1f)) {
                if (scrubbed != null && trickplay != null) {
                    val previewHeight =
                        (
                            TrickplayPreviewWidth.value * trickplay.height /
                                trickplay.width.coerceAtLeast(1)
                        ).dp + 30.dp
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .height(previewHeight + 8.dp),
                    ) {
                        val availableWidth = (maxWidth - TrickplayPreviewWidth).coerceAtLeast(0.dp)
                        val previewX =
                            (maxWidth * fraction - TrickplayPreviewWidth / 2f)
                                .coerceIn(0.dp, availableWidth)
                        TrickplayPreview(
                            storyboard = trickplay,
                            positionMs = shownPosition,
                            modifier = Modifier.offset(x = previewX),
                        )
                    }
                }
                SeekBar(
                    fraction = fraction,
                    bufferedFraction = bufferedFraction,
                    positionMs = shownPosition,
                    durationMs = state.durationMs,
                    progressMarkers = progressMarkers,
                    enabled = !seekLocked && state.durationMs > 0L,
                    onScrubTo = {
                        scrubbed = it
                        onScrub()
                    },
                    onCommit = {
                        onSeek(scrubPositionMs(it, state.durationMs))
                        scrubbed = null
                    },
                    onCancel = { scrubbed = null },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(Modifier.height(SeekBarTouchHeight), contentAlignment = Alignment.Center) {
                RollingTimeText(state.durationMs)
            }
        }

        if (skipCountdownLabel != null) {
            Spacer(Modifier.height(8.dp))
            SkipPill(
                label = skipCountdownLabel,
                onClick = onCancelAutoSkip,
                modifier = Modifier.align(Alignment.End),
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Transport lives here now rather than floating in the middle of the frame.
                // A 58dp disc centred on the picture is 58dp of picture you cannot see, and
                // it lands on the subject's face more often than not — the one part of the
                // shot the controls should never be over. Along the bottom edge it sits on
                // the gradient that is already there for the scrubber.
                TransportRow(
                    state = state,
                    locked = seekLocked,
                    onPlayPause = onPlayPause,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSeekBackward = {
                        onSeek((state.positionMs - SEEK_STEP_MS).coerceAtLeast(0L))
                    },
                    onSeekForward = {
                        val target = state.positionMs + SEEK_STEP_MS
                        onSeek(
                            if (state.durationMs > 0L) {
                                target.coerceAtMost(state.durationMs)
                            } else {
                                target
                            },
                        )
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Ordered by how often a viewer reaches for each one, most-used first, so
                // the two adjustments made mid-scene sit at the start of the strip instead
                // of behind the ones set once and left alone.
                //
                // The two conditional keys are late on purpose as well: 播放服务器 appears
                // only for a multi-source item and 片头片尾 only inside a series, and a key
                // that comes and goes near the front shifts every key behind it between one
                // episode and the next. 更多 stays last, where an overflow belongs.
                CircleControl(
                    AppIcons.Subtitle,
                    "字幕",
                    26.dp,
                    12.dp,
                    onClick = onOpenSubtitles,
                )
                CircleControl(
                    AppIcons.AudioTrack,
                    "音轨",
                    26.dp,
                    12.dp,
                    onClick = onOpenAudio,
                )
                CircleControl(
                    AppIcons.Danmaku,
                    "弹幕",
                    26.dp,
                    12.dp,
                    filled = danmakuEnabled,
                    onClick = onOpenDanmaku,
                )
                SpeedControl(
                    speed = state.speed,
                    onClick = onOpenSpeed,
                )
                CircleControl(
                    AppIcons.Cast,
                    "投屏",
                    26.dp,
                    12.dp,
                    onClick = onOpenCast,
                )
                if (hasMultipleSources) {
                    CircleControl(
                        AppIcons.PlaybackSource,
                        "播放服务器",
                        26.dp,
                        12.dp,
                        onClick = onOpenSources,
                    )
                }
                if (skipSettingsAvailable) {
                    CircleControl(
                        AppIcons.SkipMarkers,
                        "标记片头片尾",
                        26.dp,
                        12.dp,
                        onClick = onOpenSkipSettings,
                    )
                }
                CircleControl(
                    AppIcons.More,
                    "更多",
                    26.dp,
                    12.dp,
                    onClick = onOpenMore,
                )
            }
        }
    }
}

/**
 * A compact odometer keeps the timestamp still while only the digits that changed roll up.
 * Separators never animate, so the progress row does not pulse sideways once per second.
 */
@Composable
private fun RollingTimeText(timeMs: Long) {
    val wholeSeconds = timeMs.coerceAtLeast(0L) / 1000L
    val time = remember(wholeSeconds) { formatTime(wholeSeconds * 1000L) }

    Row(
        modifier = Modifier.widthIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        time.forEach { character ->
            RollingTimeGlyph(character)
        }
    }
}

@Composable
private fun RollingTimeGlyph(character: Char) {
    if (!character.isDigit()) {
        Text(
            character.toString(),
            style = AppTypography.caption.regular,
            color = PlayerTokens.timeTextLandscape,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(4.dp),
        )
        return
    }

    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    AnimatedContent(
        targetState = character,
        transitionSpec = {
            val enter =
                if (reduceMotion) {
                    fadeIn(snap())
                } else {
                    fadeIn(tween(Motion.STANDARD, easing = Motion.Curve)) +
                        slideInVertically(tween(Motion.STANDARD, easing = Motion.Curve)) { it }
                }
            val exit =
                if (reduceMotion) {
                    fadeOut(snap())
                } else {
                    fadeOut(tween(Motion.STANDARD, easing = Motion.Curve)) +
                        slideOutVertically(tween(Motion.STANDARD, easing = Motion.Curve)) { -it }
                }
            enter togetherWith exit
        },
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .width(7.dp)
                .height(18.dp)
                .clipToBounds(),
        label = "player-time-digit-roll",
    ) { digit ->
        Text(
            digit.toString(),
            style = AppTypography.caption.regular,
            color = PlayerTokens.timeTextLandscape,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** Playback speed is clearer as its value than as another abstract glyph. */
@Composable
private fun SpeedControl(
    speed: Float,
    onClick: () -> Unit,
) {
    val label =
        when {
            speed % 1f == 0f -> "${speed.toInt()}×"
            else -> "$speed×"
        }
    Box(
        Modifier
            .noRippleClickable(onClick)
            .size(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = AppTypography.caption.strong,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun TrickplayPreview(
    storyboard: TrickplayStoryboard,
    positionMs: Long,
    modifier: Modifier = Modifier,
) {
    val frame = storyboard.frameAt(positionMs)
    val previewHeight =
        (TrickplayPreviewWidth.value * storyboard.height / storyboard.width.coerceAtLeast(1)).dp
    Box(
        modifier
            .width(TrickplayPreviewWidth)
            .height(previewHeight + 30.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-2).dp)
                    .size(10.dp)
                    .rotate(45f)
                    .background(Color(0xFF171A23).copy(alpha = 0.92f), AppShapes.micro)
                    .border(1.dp, Color.White.copy(alpha = 0.22f), AppShapes.micro),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .glass(
                    shape = AppShapes.thumb,
                    fill = Color(0xFF151821).copy(alpha = 0.82f),
                    border = Color.White.copy(alpha = 0.28f),
                ).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(TrickplayPreviewWidth - 8.dp, previewHeight)
                    .clip(AppShapes.thumb)
                    .background(Color.Black),
            ) {
                AsyncImage(
                    model = frame.url,
                    contentDescription = "${formatTime(positionMs)} 预览",
                    contentScale = ContentScale.FillBounds,
                    modifier =
                        Modifier
                            .width(
                                (TrickplayPreviewWidth.value - 8f).dp *
                                    storyboard.tileColumns.toFloat(),
                            ).height(previewHeight * storyboard.tileRows.toFloat())
                            .offset(
                                x =
                                    -(
                                        (TrickplayPreviewWidth.value - 8f).dp *
                                            frame.column.toFloat()
                                    ),
                                y = -(previewHeight * frame.row.toFloat()),
                            ),
                )
            }
            Text(
                formatTime(positionMs),
                style = AppTypography.caption.strong,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
            )
        }
    }
}

/**
 * A quiet glass rail in normal playback. Scrubbing enlarges the liquid thumb and reveals a
 * short specular streak; reduced-motion mode keeps the same hierarchy without animation.
 */
internal data class PlaybackProgressMarker(
    val positionMs: Long,
    val label: String? = null,
    val emphasized: Boolean = false,
)

/** Converts the player's real skip boundaries into the semantic nodes shown on the rail. */
internal fun playbackProgressMarkers(
    skip: SkipSegmentState,
    durationMs: Long,
): List<PlaybackProgressMarker> {
    if (durationMs <= 0L) return emptyList()
    val markers = mutableListOf<PlaybackProgressMarker>()
    if (skip.introEndSeconds > 0L) {
        markers +=
            PlaybackProgressMarker(
                positionMs =
                    (skip.introStartSeconds.coerceAtLeast(0L) * 1_000L)
                        .coerceAtMost(durationMs),
                label = "片头",
                emphasized = true,
            )
        markers +=
            PlaybackProgressMarker(
                positionMs = (skip.introEndSeconds * 1_000L).coerceAtMost(durationMs),
            )
    } else if (skip.introStartSeconds > 0L) {
        markers +=
            PlaybackProgressMarker(
                positionMs = (skip.introStartSeconds * 1_000L).coerceAtMost(durationMs),
                label = "片头",
                emphasized = true,
            )
    }
    if (skip.creditsLeadSeconds > 0L) {
        markers +=
            PlaybackProgressMarker(
                positionMs = (durationMs - skip.creditsLeadSeconds * 1_000L).coerceAtLeast(0L),
                label = "片尾",
                emphasized = true,
            )
    }
    return markers
        .sortedBy(PlaybackProgressMarker::positionMs)
        .distinctBy(PlaybackProgressMarker::positionMs)
}

@Composable
internal fun SeekBar(
    fraction: Float,
    bufferedFraction: Float,
    positionMs: Long,
    durationMs: Long,
    progressMarkers: List<PlaybackProgressMarker> = emptyList(),
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    val haptics = LocalHaptics.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var dragFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var widthPx by remember { mutableIntStateOf(1) }
    val latestOnScrubTo by rememberUpdatedState(onScrubTo)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestOnCancel by rememberUpdatedState(onCancel)
    val shownFraction = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    val interaction by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = Motion.pressSpec(pressed = dragging, reduceMotion = reduceMotion),
        label = "seek-interaction",
    )
    val trackHeight = 4.dp + 2.dp * interaction
    val thumbDiameter = 9.dp + 5.dp * interaction
    val haloDiameter = 15.dp + 11.dp * interaction
    val keyStep = (5_000f / durationMs.coerceAtLeast(1L)).coerceIn(0.01f, 0.1f)
    val commit: (Float) -> Boolean = { target ->
        if (!enabled) {
            false
        } else {
            latestOnCommit(target.coerceIn(0f, 1f))
            true
        }
    }

    Box(
        modifier
            // Keep the painted track at 4dp while the whole 44dp row accepts the gesture.
            .height(SeekBarTouchHeight)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .then(
                if (focused) {
                    Modifier.border(1.dp, accent.border, AppShapes.thumb)
                } else {
                    Modifier
                },
            ).semantics {
                stateDescription = "播放进度 ${formatTime(positionMs)} / ${formatTime(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(shownFraction, 0f..1f)
                if (enabled) {
                    setProgress { commit(it) }
                } else {
                    disabled()
                }
            }.onKeyEvent { event ->
                if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> commit(shownFraction - keyStep)
                    Key.DirectionRight, Key.DirectionUp -> commit(shownFraction + keyStep)
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
                            if (commit((offset.x / width).coerceIn(0f, 1f))) {
                                haptics.play(HapticSignal.Select)
                            }
                        }
                    }.pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                val width = size.width.toFloat().coerceAtLeast(1f)
                                dragging = true
                                dragFraction = (offset.x / width).coerceIn(0f, 1f)
                                haptics.play(HapticSignal.Select)
                                latestOnScrubTo(dragFraction)
                            },
                            onDragEnd = {
                                dragging = false
                                haptics.play(HapticSignal.Confirm)
                                latestOnCommit(dragFraction)
                            },
                            onDragCancel = {
                                dragging = false
                                latestOnCancel()
                            },
                        ) { change, _ ->
                            change.consume()
                            val width = size.width.toFloat().coerceAtLeast(1f)
                            dragFraction = (change.position.x / width).coerceIn(0f, 1f)
                            latestOnScrubTo(dragFraction)
                        }
                    }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(AppShapes.track)
                .background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, Color.White.copy(alpha = 0.18f), AppShapes.track),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedFraction.coerceIn(shownFraction, 1f))
                    .clip(AppShapes.track)
                    .background(Color.White.copy(alpha = 0.22f)),
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shownFraction)
                    .clip(AppShapes.track)
                    .background(
                        Brush.horizontalGradient(
                            listOf(LiquidProgressBlue, LiquidProgressViolet),
                        ),
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.38f)),
                )
                if (interaction > 0f) {
                    Box(
                        Modifier
                            .width(58.dp)
                            .fillMaxHeight()
                            .offset {
                                val streakWidth = 58.dp.roundToPx()
                                IntOffset(
                                    x =
                                        (widthPx * shownFraction - streakWidth * 0.72f)
                                            .toInt()
                                            .coerceAtLeast(0),
                                    y = 0,
                                )
                            }.graphicsLayer { alpha = interaction }
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.58f),
                                        Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }
            }
        }

        progressMarkers.forEach { marker ->
            val markerFraction =
                (marker.positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val markerDiameter = if (marker.emphasized) 7.dp else 5.dp
            Box(
                Modifier
                    .size(markerDiameter)
                    .offset {
                        val markerPx = markerDiameter.roundToPx()
                        IntOffset(
                            x =
                                (widthPx * markerFraction - markerPx / 2f)
                                    .toInt()
                                    .coerceIn(
                                        -markerPx / 2,
                                        (widthPx - markerPx / 2).coerceAtLeast(0),
                                    ),
                            y = 0,
                        )
                    }.background(
                        if (marker.emphasized) LiquidProgressViolet else Color(0xFFDDE8FF),
                        CircleShape,
                    ).border(1.dp, Color.White.copy(alpha = 0.90f), CircleShape),
            )
            marker.label?.let { label ->
                Text(
                    label,
                    style = AppTypography.caption.medium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .offset {
                                val labelHalfWidth = 12.dp.roundToPx()
                                IntOffset(
                                    x =
                                        (widthPx * markerFraction - labelHalfWidth)
                                            .toInt()
                                            .coerceIn(
                                                0,
                                                (widthPx - labelHalfWidth * 2).coerceAtLeast(0),
                                            ),
                                    y = 0,
                                )
                            },
                )
            }
        }

        Box(
            Modifier
                .size(haloDiameter)
                .offset {
                    val haloPx = haloDiameter.roundToPx()
                    IntOffset(
                        x =
                            (widthPx * shownFraction - haloPx / 2f)
                                .toInt()
                                .coerceIn(-haloPx / 2, (widthPx - haloPx / 2).coerceAtLeast(0)),
                        y = 0,
                    )
                }.graphicsLayer { alpha = 0.12f + 0.34f * interaction }
                .background(
                    Brush.radialGradient(
                        listOf(LiquidProgressBlue.copy(alpha = 0.92f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .size(thumbDiameter)
                .offset {
                    val thumbPx = thumbDiameter.roundToPx()
                    IntOffset(
                        x =
                            (widthPx * shownFraction - thumbPx / 2f)
                                .toInt()
                                .coerceIn(-thumbPx / 2, (widthPx - thumbPx / 2).coerceAtLeast(0)),
                        y = 0,
                    )
                }.graphicsLayer { alpha = 0.88f + 0.12f * interaction }
                .background(
                    Brush.linearGradient(listOf(Color.White, Color(0xFFDCE8FF))),
                    CircleShape,
                ).border(1.dp, Color.White.copy(alpha = 0.94f), CircleShape),
        )
    }
}

/**
 * 字幕 / 音轨 / 投屏 / 更多 are one control family, so they share one height, one radius
 * and one material. Only the width flexes — a label needs more room than a glyph, and a
 * text chip that shrank to fit its text used to sit two thirds the height of the icon
 * ones next to it.
 */
private val ChipHeight = 40.dp
private val ChipMinWidth = 46.dp
private val ChipShape = AppShapes.chip

/** Labelled chip — `radius:14px`, `600 11.5px Manrope`, `rgba(255,255,255,.92)`. */
@Composable
internal fun Chip(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Box(
        Modifier
            .noRippleClickable(onClick)
            .height(ChipHeight)
            .widthIn(min = ChipMinWidth)
            .glass(
                shape = ChipShape,
                fill = if (active) accent.container else PlayerTokens.chipFill,
                border = if (active) accent.border else Color.White.copy(alpha = 0.24f),
            ).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AppTypography.caption.medium,
            color = if (active) accent.accent else Color.White.copy(alpha = 0.92f),
            maxLines = 1,
        )
    }
}

/** Glyph chip — same box as [Chip], sized to [ChipMinWidth] since it holds one icon. */
@Composable
internal fun IconChip(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = rememberAccentColorsForSurface(dark = true)
    Box(
        Modifier
            .noRippleClickable(onClick)
            .width(ChipMinWidth)
            .height(ChipHeight)
            .glass(
                shape = ChipShape,
                fill =
                    if (active) {
                        accent.container
                    } else {
                        PlayerTokens.chipFill
                    },
                border =
                    if (active) {
                        accent.border
                    } else {
                        Color.White.copy(alpha = 0.24f)
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (active) accent.accent else Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** `rgba(255,255,255,.16)` circle over a `rgba(255,255,255,.28)` hairline. */
@Composable
internal fun CircleControl(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean = true,
    interactive: Boolean = true,
    /** Filled emphasis is reserved for transient notification state such as unread chat. */
    filled: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The ring is what you see; the touch target is bigger than the ring. Sizing them
    // together is what made these controls big enough to cover a face — a 48dp disc over
    // the middle of the picture is 48dp of picture you cannot see.
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .let {
                if (enabled && interactive) it.noRippleClickable(onClick) else it.touchTarget()
            }.size(size + ControlTouchPadding * 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size)
                // A filled key gets no ring. Outlined siblings are drawn *by* their hairline;
                // putting the same hairline around a solid disc gave the play key two edges
                // and made it read as a third kind of object wedged between two rings rather
                // than as the emphatic member of their family.
                .let {
                    if (filled) {
                        it.background(PlayerTokens.playFill, CircleShape)
                    } else {
                        it.border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (filled) PlayerTokens.onPlay else Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Transport controls share one optical size; meaning comes from the glyph, not a larger disc. */
private val TransportKeySize = 28.dp

private val TransportIconSize = 14.dp

/**
 * The paused key over the middle of the frame — the one control drawn away from an edge.
 *
 * 52dp is [LockedOverlay]'s circle, the player's only other centred disc, so the two states
 * that take over the picture do it at the same size. It is deliberately not larger: a disc
 * in the middle is picture you cannot see, and it lands on a face more often than not.
 */
internal val CenterKeySize = 52.dp

internal val CenterKeyIconSize = 22.dp

private const val SEEK_STEP_MS = 10_000L

/** Slack around a control's ring, so a small ring still has a thumb-sized target. */
private val ControlTouchPadding = 7.dp

/**
 * Lock screen — a 52px circle over `屏幕已锁定` at `gap:14px`, with the
 * `解锁` pill at `right:22px; bottom:40px`.
 */
@Composable
internal fun LockedOverlay(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .glass(
                        shape = CircleShape,
                        fill = Color.White.copy(alpha = 0.09f),
                        border = Color.White.copy(alpha = 0.24f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Lock, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text("屏幕已锁定", style = AppTypography.body.medium, color = Color.White.copy(alpha = 0.57f))
        }

        Text(
            "解锁",
            style = AppTypography.body.medium,
            color = Color.White,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 22.dp, bottom = 40.dp)
                    .glass(
                        shape = AppShapes.pill,
                        fill = Color.White.copy(alpha = 0.10f),
                        border = Color.White.copy(alpha = 0.28f),
                    ).noRippleClickable(onUnlock)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
        )
    }
}
