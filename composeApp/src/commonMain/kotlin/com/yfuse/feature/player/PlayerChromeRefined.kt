package com.yfuse.feature.player

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import com.yfuse.core.designsystem.rememberAnimatedArtworkAccent

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
    state: PlaybackState,
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
) {
    var scrubbed by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1L)
    val fraction = scrubbed ?: playbackProgressFraction(state.positionMs, state.durationMs)
    val bufferedFraction =
        playbackProgressFraction(state.bufferedPositionMs, state.durationMs)
    val shownPosition = scrubbed?.let { scrubPositionMs(it, state.durationMs) } ?: state.positionMs
    val progressAccent =
        rememberAnimatedArtworkAccent(
            url = artworkUrl,
            fallback = PlayerTokens.progressAccentFallback,
            darkTheme = true,
            identity = artworkIdentity,
        )

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
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                RefinedTimeText(shownPosition)
            }
            Column(Modifier.weight(1f)) {
                if (scrubbed != null && trickplay != null) {
                    val previewHeight =
                        (
                            RefinedTrickplayPreviewWidth.value * trickplay.height /
                                trickplay.width.coerceAtLeast(1)
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
                            storyboard = trickplay,
                            positionMs = shownPosition,
                            modifier = Modifier.offset(x = previewX),
                        )
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
                RefinedTimeText(state.durationMs)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportRow(
                state = state,
                locked = seekLocked,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekBackward = {
                    onSeek((state.positionMs - REFINED_SEEK_STEP_MS).coerceAtLeast(0L))
                },
                onSeekForward = {
                    val target = state.positionMs + REFINED_SEEK_STEP_MS
                    onSeek(if (state.durationMs > 0L) target.coerceAtMost(state.durationMs) else target)
                },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(AppIcons.Subtitle, "字幕", 26.dp, 12.dp, onClick = onOpenSubtitles)
                CircleControl(AppIcons.AudioTrack, "音轨", 26.dp, 12.dp, onClick = onOpenAudio)
                ChromeLabeledAction(
                    icon = AppIcons.Danmaku,
                    label = "弹幕",
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
                    ChromeLabeledAction(
                        icon = AppIcons.EpisodeList,
                        label = "选集",
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
private fun ChromeLabeledAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = if (active) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f)
    Row(
        Modifier
            .height(30.dp)
            .glass(
                shape = AppShapes.pill,
                fill = fill,
                border = Color.White.copy(alpha = if (active) 0.52f else 0.24f),
            ).noRippleClickable(onClick)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(13.dp),
        )
        Text(
            label,
            style = AppTypography.caption.strong,
            color = Color.White.copy(alpha = 0.94f),
            maxLines = 1,
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
private fun RefinedTimeText(timeMs: Long) {
    Text(
        formatTime(timeMs.coerceAtLeast(0L)),
        style = AppTypography.caption.regular,
        color = PlayerTokens.timeTextLandscape,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 44.dp),
    )
}

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
    accent: Color,
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalHaptics.current
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    var dragFraction by remember { mutableFloatStateOf(0f) }
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
        label = "artwork-seek-interaction",
    )
    val playedStart = lerp(accent, Color.Black, 0.14f)
    val playedEnd = lerp(accent, Color.White, 0.24f)
    val bufferedTint = lerp(accent, Color.Gray, 0.62f)
    val trackHeight = 3.dp + interaction.dp
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

    Box(
        modifier
            .height(44.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .then(
                if (focused) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.72f), AppShapes.thumb)
                } else {
                    Modifier
                },
            ).semantics {
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
                .background(Color.White.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedFraction.coerceIn(shownFraction, 1f))
                    .background(bufferedTint.copy(alpha = 0.50f)),
            )
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shownFraction)
                    .background(Brush.horizontalGradient(listOf(playedStart, playedEnd))),
            )
        }

        repeat(TIMELINE_MINOR_TICK_COUNT - 1) { index ->
            val tickFraction = (index + 1).toFloat() / TIMELINE_MINOR_TICK_COUNT
            val tickHeight = if ((index + 1) % 5 == 0) 6.dp else 4.dp
            Box(
                Modifier
                    .width(1.dp)
                    .height(tickHeight)
                    .offset {
                        IntOffset(
                            x = (widthPx * tickFraction).toInt().coerceIn(0, widthPx - 1),
                            y = 7.dp.roundToPx(),
                        )
                    }.background(
                        if (tickFraction <= shownFraction) {
                            accent.copy(alpha = 0.62f)
                        } else {
                            Color.White.copy(alpha = 0.28f)
                        },
                    ),
            )
        }

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
                    }.background(
                        if (marker.emphasized) accent else accent.copy(alpha = 0.70f),
                        AppShapes.track,
                    ),
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
                .background(accent, CircleShape),
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
                .border(2.dp, playedEnd, CircleShape),
        )
    }
}

private val RefinedTrickplayPreviewWidth = 160.dp
private const val REFINED_SEEK_STEP_MS = 10_000L
private const val TIMELINE_MINOR_TICK_COUNT = 20
