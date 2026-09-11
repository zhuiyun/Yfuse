package com.yfuse.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.LocalRouteVisible
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.ambientSeekAccent
import com.yfuse.core.designsystem.cssLinearGradient
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent
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
                    if (dolbyVision) DolbyChip("VISION", Color.White.copy(alpha = 0.88f))
                    if (dolbyAtmos) DolbyChip("ATMOS", Color.White.copy(alpha = 0.88f))
                }
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = AppTypography.caption.regular,
                        color = Color.White.copy(alpha = 0.44f),
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
            PlayerClock()
            PlayerBatteryStatus()
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
            CircleControl(
                AppIcons.PictureInPicture,
                "小窗播放",
                28.dp,
                12.dp,
                onClick = onEnterPictureInPicture,
            )
            CircleControl(
                icon = if (filled) AppIcons.AspectFill else AppIcons.AspectFit,
                description = if (filled) "画面比例：填充" else "画面比例：适应",
                size = 28.dp,
                iconSize = 12.dp,
                onClick = onToggleFill,
            )
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
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var scrubbed by remember { mutableStateOf<Float?>(null) }
    val latestTransport by rememberUpdatedState(state)
    val fraction = scrubbed ?: playbackProgressFraction(state.positionMs, state.durationMs)
    val bufferedFraction =
        playbackProgressFraction(state.bufferedPositionMs, state.durationMs)
    val shownPosition = scrubbed?.let { scrubPositionMs(it, state.durationMs) } ?: state.positionMs
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
                RefinedTimeText(shownPosition.coerceAtLeast(0L) / 1_000L)
            }
            Column(Modifier.weight(1f)) {
                val preview = trickplay
                AnimatedVisibility(
                    visible = scrubbed != null && preview != null,
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
                            val availableWidth = (maxWidth - RefinedTrickplayPreviewWidth).coerceAtLeast(0.dp)
                            val previewX =
                                (maxWidth * fraction - RefinedTrickplayPreviewWidth / 2f)
                                    .coerceIn(0.dp, availableWidth)
                            TrickplayPreview(
                                storyboard = preview,
                                positionMs = shownPosition,
                                modifier = Modifier.offset(x = previewX),
                            )
                        }
                    }
                }
                StandardSeekBar(
                    fraction = fraction,
                    bufferedFraction = bufferedFraction,
                    positionMs = shownPosition,
                    durationMs = state.durationMs,
                    progressMarkers = progressMarkers,
                    accent = progressAccent,
                    enabled = !seekLocked && state.durationMs > 0L,
                    showTimeBubble = trickplay == null,
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
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                RefinedTimeText(state.durationMs.coerceAtLeast(0L) / 1_000L)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportRow(
                state = state.buttons,
                locked = seekLocked,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekBackward = {
                    onSeek((latestTransport.positionMs - REFINED_SEEK_STEP_MS).coerceAtLeast(0L))
                },
                onSeekForward = {
                    val latest = latestTransport
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
                RefinedSpeedControl(state.speed, onOpenSpeed)
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
                if (hasEpisodes) {
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

@Composable
private fun RefinedSpeedControl(
    speed: Float,
    onClick: () -> Unit,
) {
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
private fun RefinedTimeText(seconds: Long) {
    Text(
        formatTime(seconds * 1_000L),
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
    fraction: Float,
    bufferedFraction: Float,
    positionMs: Long,
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
    val shownFraction = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    val interaction by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = Motion.pressSpec(pressed = dragging, reduceMotion = reduceMotion),
        label = "artwork-seek-interaction",
    )
    // Under a finger the track thickens to nearly twice itself: the thing being dragged
    // should look like it can take the weight.
    val trackHeight = 3.5.dp + 2.5.dp * interaction
    val thumbDiameter = 10.dp + 3.dp * interaction
    val haloDiameter = 20.dp + 8.dp * interaction
    val keyStep = (5_000f / durationMs.coerceAtLeast(1L)).coerceIn(0.01f, 0.1f)
    val commit: (Float) -> Boolean = { target ->
        if (!enabled) {
            false
        } else {
            latestOnCommit(target.coerceIn(0f, 1f))
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
    }

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .drawWithCache {
                val outline = AppShapes.thumb.createOutline(size, layoutDirection, this)
                val stroke = Stroke(1.dp.toPx())
                onDrawBehind {
                    if (focused) drawOutline(outline, accent().copy(alpha = 0.72f), style = stroke)
                }
            }.semantics {
                stateDescription = "播放进度 ${formatTime(positionMs)} / ${formatTime(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(shownFraction, 0f..1f)
                if (enabled) setProgress { commit(it) } else disabled()
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
                            },
                            onDragCancel = {
                                dragging = false
                                snappedMarkerIndex = null
                                latestOnCancel()
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
        val played = rememberUpdatedState(shownFraction)
        val buffered = remember(durationMs) { Animatable(bufferedFraction.coerceIn(0f, 1f)) }
        val moving = !reduceMotion && LocalRouteVisible.current
        LaunchedEffect(bufferedFraction, durationMs, moving) {
            val target = bufferedFraction.coerceIn(0f, 1f)
            if (!moving || target < buffered.value) {
                buffered.snapTo(target)
            } else {
                buffered.animateTo(target, tween(Motion.STANDARD, easing = Motion.Curve))
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(AppShapes.track)
                .drawBehind {
                    val color = accent()
                    val playedWidth = size.width * played.value
                    drawRect(Color.White.copy(alpha = 0.16f))
                    drawRect(
                        lerp(color, Color.Gray, 0.62f).copy(alpha = 0.50f),
                        size =
                            androidx.compose.ui.geometry.Size(
                                size.width * buffered.value.coerceIn(played.value, 1f),
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

        progressMarkers.forEach { marker ->
            val markerFraction =
                (marker.positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
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
                }.graphicsLayer { alpha = if (enabled) 0.28f + 0.18f * interaction else 0.10f }
                .drawBehind { drawCircle(accent()) },
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
                }.graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .background(Color.White, CircleShape)
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

        if (showTimeBubble) {
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
                                (widthPx * shownFraction - bubblePx / 2f)
                                    .toInt()
                                    .coerceIn(0, (widthPx - bubblePx).coerceAtLeast(0)),
                            y = -SeekTimeBubbleRise.roundToPx(),
                        )
                    }.graphicsLayer {
                        alpha = interaction
                        val scale = 0.8f + 0.2f * interaction
                        scaleX = scale
                        scaleY = scale
                        translationY = 6.dp.toPx() * (1f - interaction)
                    }.background(Color.Black.copy(alpha = 0.58f), AppShapes.pill)
                    .border(1.dp, Color.White.copy(alpha = 0.20f), AppShapes.pill),
                contentAlignment = Alignment.Center,
            ) {
                Text(formatTime(positionMs), style = AppTypography.caption.medium, color = Color.White)
            }
        }
    }
}

private val SeekTimeBubbleWidth = 60.dp
private val SeekTimeBubbleHeight = 22.dp
private val SeekTimeBubbleRise = 26.dp

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
