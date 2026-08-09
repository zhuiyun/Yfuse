package com.yfuse.feature.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.LocalAccessibilityOptions
import com.yfuse.core.designsystem.LocalHaptics
import com.yfuse.core.designsystem.Motion
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr

private val PremiumSeekTouchHeight = 40.dp
private val PremiumSeekTrackHeight = 4.dp
private val PremiumSeekTrackHeightDragging = 6.dp
private val PremiumSeekThumbDiameter = 8.dp
private val PremiumSeekThumbDiameterDragging = 14.dp

/**
 * Player scrubber with three readable layers: remaining, buffered and played.
 *
 * The painted track stays slim while the whole 40dp row accepts input. Direct manipulation
 * grows the track and thumb, adds a time bubble, and confirms the commit with haptics. The
 * implementation deliberately uses ordinary Compose layout/background primitives so it stays
 * compatible with this project's Compose Multiplatform 1.7 line.
 */
@Composable
internal fun PremiumSeekBar(
    fraction: Float,
    bufferedFraction: Float,
    label: String,
    enabled: Boolean,
    onScrubTo: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    var widthPx by remember { mutableIntStateOf(1) }
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val haptics = LocalHaptics.current
    val density = LocalDensity.current

    val latestOnScrubTo by rememberUpdatedState(onScrubTo)
    val latestOnCommit by rememberUpdatedState(onCommit)
    val latestOnCancel by rememberUpdatedState(onCancel)

    val interaction by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = Motion.pressSpec(pressed = dragging, reduceMotion = reduceMotion),
        label = "premium-seek-interaction",
    )
    val shownFraction = if (dragging) dragFraction else fraction.coerceIn(0f, 1f)
    val visual = premiumSeekVisualState(shownFraction, bufferedFraction, interaction)
    val trackHeight = PremiumSeekTrackHeight +
        (PremiumSeekTrackHeightDragging - PremiumSeekTrackHeight) * interaction
    val thumbDiameter = PremiumSeekThumbDiameter +
        (PremiumSeekThumbDiameterDragging - PremiumSeekThumbDiameter) * interaction

    Box(
        modifier
            .fillMaxWidth()
            .height(PremiumSeekTouchHeight)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .let { base ->
                if (!enabled) return@let base
                base
                    .pointerInput(enabled) {
                        detectTapGestures { offset ->
                            val target = premiumSeekFraction(offset.x, size.width.toFloat())
                            haptics.play(HapticSignal.Select)
                            latestOnCommit(target)
                        }
                    }
                    .pointerInput(enabled) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragFraction = premiumSeekFraction(offset.x, size.width.toFloat())
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
                            dragFraction = premiumSeekFraction(change.position.x, size.width.toFloat())
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
                .clip(CircleShape)
                .background(PlayerTokens.trackFillLandscape),
        ) {
            if (visual.bufferedFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .fillMaxWidth(visual.bufferedFraction)
                        .background(Color.White.copy(alpha = 0.44f)),
                )
            }
            if (visual.playedFraction > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .fillMaxWidth(visual.playedFraction)
                        .background(PlayerTokens.progress),
                )
            }
        }

        Box(
            Modifier
                .size(thumbDiameter)
                .offset {
                    val thumbPx = thumbDiameter.roundToPx()
                    IntOffset(
                        x = (widthPx * visual.playedFraction - thumbPx / 2f)
                            .toInt()
                            .coerceIn(-thumbPx / 2, (widthPx - thumbPx / 2).coerceAtLeast(0)),
                        y = 0,
                    )
                }
                .graphicsLayer { alpha = 0.72f + 0.28f * interaction }
                .background(Color.White, CircleShape),
        )

        if (dragging) {
            val bubbleWidthPx = with(density) { 68.dp.roundToPx() }
            val x = (widthPx * visual.playedFraction - bubbleWidthPx / 2f)
                .toInt()
                .coerceIn(0, (widthPx - bubbleWidthPx).coerceAtLeast(0))
            Text(
                label,
                style = mr(10.5f, 700),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(x, -8.dp.roundToPx()) }
                    .widthIn(min = 56.dp)
                    .glass(
                        shape = GlassShapes.chip,
                        fill = Color.Black.copy(alpha = 0.68f),
                        border = Color.White.copy(alpha = 0.24f),
                    )
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
    }
}

internal data class PremiumSeekVisualState(
    val playedFraction: Float,
    val bufferedFraction: Float,
    val interaction: Float,
)

internal fun premiumSeekVisualState(
    playedFraction: Float,
    bufferedFraction: Float,
    interaction: Float,
): PremiumSeekVisualState {
    val played = playedFraction.coerceIn(0f, 1f)
    return PremiumSeekVisualState(
        playedFraction = played,
        bufferedFraction = bufferedFraction.coerceIn(played, 1f),
        interaction = interaction.coerceIn(0f, 1f),
    )
}

internal fun premiumSeekFraction(x: Float, width: Float): Float =
    if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)
