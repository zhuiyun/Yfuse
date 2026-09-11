package com.yfuse.feature.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.AppShapes
import com.yfuse.core.designsystem.AppTypography
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.OrbProgress
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.lightFeedback
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.rememberLightFeedback
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.util.currentClockTime
import kotlinx.coroutines.delay
import com.yfuse.core.designsystem.ThemeIcon as Icon
import com.yfuse.core.designsystem.ThemeText as Text

private val TrickplayPreviewWidth = 160.dp

/**
 * [alternatives] are the other ways this title can be played, offered right where playback
 * failed: another file the server holds, another engine. The label says what changes; the
 * action performs it. Server transcoding is deliberately not among them: it would hide the
 * client's failure behind the server's CPU.
 */
@Composable
internal fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onExternalPlayer: (() -> Unit)?,
    onBack: () -> Unit,
    alternatives: List<Pair<String, () -> Unit>> = emptyList(),
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
            if (alternatives.isNotEmpty()) {
                Text(
                    "换一种方式播放",
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    alternatives.forEach { (label, action) ->
                        Text(
                            label,
                            style = AppTypography.caption.strong,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier
                                    .glass(
                                        shape = AppShapes.pill,
                                        fill = Color.White.copy(alpha = 0.10f),
                                        border = Color.White.copy(alpha = 0.28f),
                                    ).noRippleClickable(action)
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
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
    state: PlaybackButtonState,
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
            enabled = !locked && state.seekable,
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
                        OrbProgress(size = 16.dp, color = Color.White)
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
            enabled = !locked && state.seekable,
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
    val light = rememberLightFeedback()
    val currentLight by rememberUpdatedState(light)
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
        currentLight.emit(LightEffect.Trail, fractionY = 1f - target)
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
                .lightFeedback(light)
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
                        onDragCancel = {
                            dragging = false
                            currentLight.clear()
                        },
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
                TrickplayImage(
                    storyboard = storyboard,
                    frame = frame,
                    description = "${formatTime(positionMs)} 预览",
                    modifier = Modifier.fillMaxSize(),
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
    active: Boolean = false,
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
                        it
                            .background(
                                if (active) Color.White.copy(alpha = 0.12f) else Color.Transparent,
                                CircleShape,
                            ).border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape)
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
