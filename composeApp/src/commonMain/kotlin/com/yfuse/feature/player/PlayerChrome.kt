package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.LightEffect
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.PressFeedback
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.lightFeedback
import com.yfuse.core.designsystem.liveStatus
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.rememberAccentColorsForSurface
import com.yfuse.core.designsystem.rememberDelayedBusy
import com.yfuse.core.designsystem.rememberLightFeedback
import com.yfuse.core.designsystem.softSelectionSurface
import com.yfuse.core.designsystem.touchTarget
import com.yfuse.core.util.currentClockTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
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
    onExplain: (() -> Unit)? = null,
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
            // Spoken at once, reason included: the failure has stopped what the person was doing.
            // The title stays a heading, where the explanation and the ways out below are found from.
            Column(
                Modifier.liveStatus(assertive = true),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "播放遇到问题",
                    style = AppTypography.section.strong,
                    color = Color.White,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    message,
                    style = AppTypography.body.regular,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            onExplain?.let { explain ->
                Text(
                    "查看原因与导出日志",
                    style = AppTypography.body.strong,
                    color = Color.White,
                    modifier = Modifier.noRippleClickable(explain).padding(12.dp),
                )
            }
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
        while (isActive) {
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
    /** 没听清: a held ⟲10 rewinds and brings subtitles up for the replay; null leaves it a plain key. */
    onSeekBackwardLongPress: (() -> Unit)? = null,
    /** Applied to the play/pause key alone: where a remote's focus lands when the controls come up. */
    playKeyModifier: Modifier = Modifier,
) {
    // The same wait as the status chip's, so a seek's short stall shows nothing in either place.
    val bufferingIndicatorVisible =
        rememberDelayedBusy(state.buffering, showAfterMillis = BUFFERING_INDICATOR_DELAY_MS.toInt())
    var settledPlaying by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state.playing, state.buffering) {
        if (!state.buffering) settledPlaying = state.playing
    }
    val showsPause = transportShowsPause(state.playing, state.buffering, settledPlaying)
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // The ring [CircleControl] will draw, which a television enlarges; the stall ring follows it.
    val keySize = chromeKeySize(TransportKeySize)

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
            onLongClick = onSeekBackwardLongPress,
            onLongClickLabel = onSeekBackwardLongPress?.let { "没听清：倒回 10 秒并临时打开字幕" },
        )
        // Buffering never takes the key away: a stalled film can still be paused, and a spoken
        // cursor resting on the key does not lose it. The stall is a ring round the key instead.
        // Nor does pressing it: the glyph turns into the other one inside the same key, so focus
        // stays put and a screen reader hears the new state rather than nothing.
        Box(Modifier.size(keySize + ControlTouchPadding * 2), contentAlignment = Alignment.Center) {
            CircleControl(
                if (showsPause) AppIcons.Pause else AppIcons.Play,
                if (showsPause) "暂停" else "播放",
                TransportKeySize,
                TransportIconSize,
                enabled = !locked,
                onClick = {
                    // Nothing will report the answer until the stall ends; the key gives it now.
                    if (state.buffering) settledPlaying = !showsPause
                    onPlayPause()
                },
                modifier =
                    playKeyModifier.semantics {
                        stateDescription =
                            when {
                                bufferingIndicatorVisible -> "缓冲中"
                                showsPause -> "正在播放"
                                else -> "已暂停"
                            }
                    },
                glyph = rememberPlayPauseGlyph(showsPause),
            )
            // Drawn over the key but never hit: a tap on the ring is a tap on the key. Qualified,
            // because inside this Box the Row's `RowScope.AnimatedVisibility` would be chosen and
            // the layout-scope DSL rule forbids reaching it from here.
            androidx.compose.animation.AnimatedVisibility(
                visible = bufferingIndicatorVisible,
                enter = fadeIn(Motion.tween(if (reduceMotion) 0 else Motion.QUICK)),
                exit = fadeOut(Motion.tween(if (reduceMotion) 0 else Motion.QUICK)),
                label = "transport-buffering",
            ) {
                BufferingRing(Modifier.size(keySize + BufferingRingGap * 2))
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
 * A stall, drawn round the transport key rather than in its place: a short arc running the rim.
 * Under 减弱动态效果 the rim is simply lit — the key's 「缓冲中」 says the rest without motion.
 */
@Composable
private fun BufferingRing(modifier: Modifier = Modifier) {
    val moving = !LocalAccessibilityOptions.current.reduceMotion && LocalRouteVisible.current
    // Only a ring that moves has a clock; a still one requests no frames at all.
    val turn =
        if (moving) {
            rememberInfiniteTransition(label = "buffering-ring").animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(Motion.tween(Motion.SPINNER_TURN, easing = LinearEasing)),
                label = "buffering-turn",
            )
        } else {
            null
        }
    Canvas(modifier) {
        val stroke = BufferingRingStroke.toPx()
        val rim = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        drawArc(
            color = Color.White.copy(alpha = if (turn != null) 0.18f else 0.62f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = rim,
            style = Stroke(stroke),
        )
        // Read here, so the turn redraws the ring and nothing else.
        turn?.let {
            drawArc(
                color = Color.White,
                startAngle = it.value - 90f,
                sweepAngle = BUFFERING_ARC_DEGREES,
                useCenter = false,
                topLeft = topLeft,
                size = rim,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
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
    /** A reader: the rocker and a drag move it many times a second, and only the bar follows it. */
    volume: () -> Float,
    onVolume: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val light = rememberLightFeedback()
    val currentLight by rememberUpdatedState(light)
    val accent = rememberAccentColorsForSurface(dark = true)
    val latestVolume by rememberUpdatedState(volume)
    var height by remember { mutableIntStateOf(1) }
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    // The level as drawn: eased to each new volume, straight onto it under a finger. The fill
    // reads it while drawing and the figure through a whole-percent derived state, so the frames
    // of a settle repaint the rail instead of recomposing the slider.
    val shown = remember { Animatable(volume().coerceIn(0f, 1f)) }
    LaunchedEffect(reduceMotion) {
        snapshotFlow { latestVolume().coerceIn(0f, 1f) to dragging }.collectLatest { (target, underFinger) ->
            if (underFinger || reduceMotion) shown.snapTo(target) else shown.animateTo(target, Motion.settle())
        }
    }
    val percent by remember { derivedStateOf { (shown.value * 100).toInt() } }
    // What is spoken is where the volume is, not where the drawing has got to: a settle read out
    // its in-between figures to a cursor resting on the bar.
    val spokenPercent by remember { derivedStateOf { (latestVolume().coerceIn(0f, 1f) * 100).toInt() } }
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
            Text("$percent", style = AppTypography.caption.strong, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .width(6.dp)
                    .height(140.dp)
                    .clip(AppShapes.track)
                    .background(Color.White.copy(alpha = 0.22f))
                    .drawBehind {
                        val level = shown.value
                        // Muted draws no fill at all rather than a zero-height sliver.
                        if (level <= 0f) return@drawBehind
                        val fill = Size(size.width, size.height * level)
                        translate(top = size.height - fill.height) {
                            drawOutline(AppShapes.track.createOutline(fill, layoutDirection, this), Color.White)
                        }
                    },
            )
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
                    stateDescription = "音量 $spokenPercent%"
                    progressBarRangeInfo = ProgressBarRangeInfo(spokenPercent / 100f, 0f..1f, 100)
                    setProgress { adjust(it) }
                }.onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown, Key.DirectionLeft -> adjust(latestVolume() - 0.05f)
                        Key.DirectionUp, Key.DirectionRight -> adjust(latestVolume() + 0.05f)
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
 * Not tied to the rest of the controls at first: the offer is only good for as long as playback
 * is inside the segment, and making the user summon the controls first would spend a chunk of
 * that window. It comes up on its own as playback enters the segment, and only after those first
 * seconds does it follow the controls' show/hide — see [shouldShowManualSkipPill].
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

/**
 * The standing sign of a cast session: where the picture went, what it is doing there, and the
 * way back.
 *
 * Casting leaves this screen on a still frame, and once the controls faded that read as a frozen
 * player — the one place that said otherwise was a row inside the 投屏 panel. The body opens that
 * panel; 断开 ends the session and hands playback back to this device.
 */
@Composable
internal fun CastSessionPill(
    status: String,
    onOpen: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Read the line out as it changes: only while something went wrong, not at every poll. */
    announce: Boolean = false,
) {
    Row(
        modifier.glass(
            shape = AppShapes.pill,
            fill = Color.Black.copy(alpha = 0.56f),
            border = Color.White.copy(alpha = 0.24f),
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .pressable(onClickLabel = "投屏设置", onClick = onOpen)
                .touchTarget()
                .padding(
                    start = Dimens.space.lg,
                    end = Dimens.space.md,
                    top = Dimens.space.sm,
                    bottom = Dimens.space.sm,
                ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.Cast, "投屏", tint = Color.White, modifier = Modifier.size(14.dp))
            Text(
                status,
                style = AppTypography.caption.medium,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // A failed command changes this line while nobody is looking. DLNA polling swaps
                // 缓冲中 and 播放中 back and forth, which is not worth interrupting anyone for.
                modifier = Modifier.widthIn(max = 160.dp).then(if (announce) Modifier.liveStatus() else Modifier),
            )
        }
        Box(
            Modifier
                .width(1.dp)
                .height(16.dp)
                .background(Color.White.copy(alpha = 0.24f)),
        )
        Text(
            "断开",
            style = AppTypography.caption.strong,
            color = Color.White,
            modifier =
                Modifier
                    .pressable(onClickLabel = "断开投屏", onClick = onDisconnect)
                    .touchTarget()
                    .padding(
                        start = Dimens.space.md,
                        end = Dimens.space.lg,
                        top = Dimens.space.sm,
                        bottom = Dimens.space.sm,
                    ),
        )
    }
}

/**
 * "3 秒后跳过片头 · 点击取消" — the label says what will happen and how to stop it. With [remote] it
 * names the remote's way instead: OK over the picture cancels (see TvRemoteInputController).
 */
internal fun skipCountdownLabel(
    skipSegmentLabel: String?,
    seconds: Int,
    remote: Boolean = false,
): String {
    // 跳过片头 -> 片头. The type's own label is the only place this wording lives.
    val what = skipSegmentLabel?.removePrefix("跳过").orEmpty()
    return "$seconds 秒后跳过$what · ${if (remote) "按确定键取消" else "点击取消"}"
}

/** The same countdown as a screen reader hears it: once, and without the seconds that tick. */
internal fun skipCountdownAnnouncement(skipSegmentLabel: String?): String {
    val what = skipSegmentLabel?.removePrefix("跳过").orEmpty()
    return "即将自动跳过$what"
}

@Composable
internal fun TrickplayPreview(
    storyboard: TrickplayStoryboard,
    positionMs: Long,
    modifier: Modifier = Modifier,
    /** The chapter [positionMs] is in, read under the time. */
    chapter: String? = null,
    /**
     * Keeps the chapter line even where [chapter] is null — before a file's first chapter — so a
     * card sliding across chapters never changes height under the finger.
     */
    chapterLine: Boolean = chapter != null,
    /** Said after the time, for the scrub's current precision: "¼ 速". */
    timeSuffix: String? = null,
) {
    val frame = storyboard.frameAt(positionMs)
    val previewHeight =
        (TrickplayPreviewWidth.value * storyboard.height / storyboard.width.coerceAtLeast(1)).dp
    Box(
        modifier
            .width(TrickplayPreviewWidth)
            .height(trickplayPreviewHeight(storyboard, chapterLine)),
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
                timeSuffix?.let { "${formatTime(positionMs)} · $it" } ?: formatTime(positionMs),
                style = AppTypography.caption.strong,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
            )
            if (chapterLine) {
                Text(
                    chapter.orEmpty(),
                    style = AppTypography.caption.regular,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 1.dp),
                )
            }
        }
    }
}

/** How tall [TrickplayPreview] is for [storyboard], with or without its chapter line. */
internal fun trickplayPreviewHeight(
    storyboard: TrickplayStoryboard,
    chapterLine: Boolean,
): Dp =
    (TrickplayPreviewWidth.value * storyboard.height / storyboard.width.coerceAtLeast(1)).dp +
        30.dp +
        if (chapterLine) TrickplayChapterLineHeight else 0.dp

/** Room for one caption line of chapter name under the card's time. */
private val TrickplayChapterLineHeight = 18.dp

/**
 * A quiet glass rail in normal playback. Scrubbing enlarges the liquid thumb and reveals a
 * short specular streak; reduced-motion mode keeps the same hierarchy without animation.
 */
internal data class PlaybackProgressMarker(
    val positionMs: Long,
    val label: String? = null,
    val emphasized: Boolean = false,
    /**
     * A chapter start rather than a skip boundary: the rail is cut here instead of ticked, and
     * the preview card names the chapter the playhead is in.
     */
    val chapter: Boolean = false,
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
    /** Applied to the visible ring rather than the touch target, for callers that need its bounds. */
    ringModifier: Modifier = Modifier,
    /** A second action behind a held press; [pressable] gives it the long-press tick. */
    onLongClick: (() -> Unit)? = null,
    /** What a screen reader calls [onLongClick]. */
    onLongClickLabel: String? = null,
    /** Drawn in place of [icon], for a glyph that changes shape inside the key. */
    glyph: Painter? = null,
    /**
     * A new [icon] dissolves into the key instead of arriving at once. A key whose glyph says its
     * state changes glyph in place rather than being swapped for its twin: the swap took focus with
     * it on every press.
     */
    crossfadeIcon: Boolean = false,
) {
    val interactions = remember { MutableInteractionSource() }
    // Across a room the phone's ring is a speck: a television draws it no smaller than 40 dp, and
    // the glyph grows with it.
    val ring = chromeKeySize(size)
    val glyphSize = iconSize * (ring / size)
    // The ring is what you see; the touch target is bigger than the ring. Sizing them
    // together is what made these controls big enough to cover a face — a 48dp disc over
    // the middle of the picture is 48dp of picture you cannot see.
    Box(
        modifier
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .let {
                if (interactive) {
                    it
                        .pressable(
                            enabled = enabled,
                            pressedScale = PressFeedback.QUIET,
                            lightFeedback = false,
                            interactionSource = interactions,
                            focusShape = CircleShape,
                            // The ring paints its own pressed colour ([softSelectionSurface] below).
                            stateLayer = false,
                            onLongClick = onLongClick,
                            onLongClickLabel = onLongClickLabel,
                            onClick = onClick,
                        ).touchTarget()
                } else {
                    it.touchTarget()
                }
            }.size(ring + ControlTouchPadding * 2),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            ringModifier
                .size(ring)
                // A filled key gets no ring. Outlined siblings are drawn *by* their hairline;
                // putting the same hairline around a solid disc gave the play key two edges
                // and made it read as a third kind of object wedged between two rings rather
                // than as the emphatic member of their family.
                .let {
                    if (filled) {
                        it.background(PlayerTokens.playFill, CircleShape)
                    } else {
                        it
                            .border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape)
                    }
                }.softSelectionSurface(
                    interactionSource = interactions,
                    shape = CircleShape,
                    selected = active && !filled,
                    selectedColor = Color.White.copy(alpha = 0.12f),
                    pressedColor = (if (filled) PlayerTokens.onPlay else Color.White).copy(alpha = 0.10f),
                    enabled = enabled && interactive,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (filled) PlayerTokens.onPlay else Color.White
            when {
                glyph != null -> {
                    Icon(glyph, contentDescription = description, tint = tint, modifier = Modifier.size(glyphSize))
                }
                crossfadeIcon -> {
                    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
                    // The name belongs to the key, not to either glyph, so a screen reader never
                    // hears both halves of the dissolve.
                    Crossfade(
                        targetState = icon,
                        modifier = Modifier.size(glyphSize).semantics { contentDescription = description },
                        animationSpec = Motion.tween(if (reduceMotion) 0 else Motion.QUICK),
                        label = "circle-control-glyph",
                    ) { shown ->
                        Icon(shown, contentDescription = null, tint = tint, modifier = Modifier.size(glyphSize))
                    }
                }
                else -> {
                    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(glyphSize))
                }
            }
        }
    }
}

/** Transport controls share one optical size; meaning comes from the glyph, not a larger disc. */
private val TransportKeySize = 28.dp

private val TransportIconSize = 14.dp

/** Clear of the key's own hairline, so the stall reads as a second ring and not a thicker first one. */
private val BufferingRingGap = 5.dp

private val BufferingRingStroke = 2.dp

private const val BUFFERING_ARC_DEGREES = 100f

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
internal val ControlTouchPadding = 7.dp

/** From the left edge, where the left thumb rests on a phone held sideways. */
internal val LockKeyEdgePadding = 16.dp

/** A 34dp ring; with the shared touch padding the target is 48dp. */
private val LockKeyRingSize = 34.dp

private val LockKeyIconSize = 16.dp

/**
 * 锁定 on the left edge, halfway down: the key other Chinese players keep there, one tap from the
 * picture instead of 更多 → 播放设置's eleventh row. It stays in the same spot once locked
 * ([LockedOverlay]), so the thumb that locked the screen is the one that opens it.
 */
@Composable
internal fun PlayerLockKey(
    locked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    CircleControl(
        icon = if (locked) AppIcons.Lock else AppIcons.Unlock,
        description = if (locked) "解锁屏幕" else "锁定屏幕",
        size = LockKeyRingSize,
        iconSize = LockKeyIconSize,
        active = locked,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = "解锁屏幕".takeIf { onLongClick != null },
        modifier = modifier,
    )
}

/**
 * Lock screen — a 52px circle over `屏幕已锁定` at `gap:14px`, with the lock key back on the left
 * edge where it was pressed.
 *
 * The lock refuses the whole picture, not only the drag. A catcher under the lock's own chrome
 * takes every touch before the gesture layer beneath it can read one as a double tap or a hold: a
 * tap brings the circle and the key back for a moment ([controlsVisible]), a double tap or a hold
 * is refused ([onRefuse]). The key opens with a tap — a stray one only brings it up, so it takes
 * two deliberate taps — or, with 解锁方式 · 长按 ([unlockByLongPress]), only under a held press, for
 * the child the lock is there for. A screen reader's double tap always opens it: it is the only
 * press that reader has.
 */
@Composable
internal fun LockedOverlay(
    controlsVisible: Boolean,
    message: String,
    screenReaderActive: Boolean,
    unlockByLongPress: Boolean,
    onReveal: () -> Unit,
    onRefuse: () -> Unit,
    onUnlock: () -> Unit,
) {
    val latestReveal by rememberUpdatedState(onReveal)
    val latestRefuse by rememberUpdatedState(onRefuse)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { latestRefuse() },
                        onLongPress = { latestRefuse() },
                        onTap = { latestReveal() },
                    )
                },
        )
        ChromeVisibility(visible = controlsVisible, modifier = Modifier.align(Alignment.Center)) {
            Column(
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
                Text(
                    message,
                    style = AppTypography.body.medium,
                    color = Color.White.copy(alpha = 0.57f),
                    modifier = Modifier.liveStatus(),
                )
            }
        }
        ChromeVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = LockKeyEdgePadding),
        ) {
            PlayerLockKey(
                locked = true,
                // With 长按 chosen, a tap only says how to unlock — unless it is a screen reader's.
                onClick = if (unlockByLongPress && !screenReaderActive) onRefuse else onUnlock,
                onLongClick = onUnlock,
            )
        }
    }
}
