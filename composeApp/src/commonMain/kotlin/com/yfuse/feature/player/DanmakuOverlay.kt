package com.yfuse.feature.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuDisplayArea
import com.yfuse.core.data.DanmakuFontSize
import com.yfuse.core.data.DanmakuKind
import com.yfuse.core.data.DanmakuOpacity
import com.yfuse.core.data.DanmakuSpeed
import com.yfuse.core.designsystem.sc
import kotlinx.coroutines.isActive

private const val FIXED_DURATION_MS = 4_000L
private const val MAX_ON_SCREEN = 120

/**
 * Playback-position-driven overlay. Deriving position directly from the engine keeps comments
 * correct after pause, seek, episode changes and player-engine handovers without a second clock.
 */
@Composable
fun DanmakuOverlay(
    comments: List<DanmakuComment>,
    positionMs: Long,
    playing: Boolean,
    playbackRate: Float,
    displayArea: DanmakuDisplayArea,
    fontSize: DanmakuFontSize,
    speed: DanmakuSpeed,
    opacity: DanmakuOpacity,
    modifier: Modifier = Modifier,
) {
    var renderedPositionMs by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, playing, playbackRate) {
        renderedPositionMs = positionMs
        if (!playing) return@LaunchedEffect
        val startedAt = withFrameMillis { it }
        while (isActive) {
            withFrameMillis { frameTime ->
                renderedPositionMs =
                    positionMs + ((frameTime - startedAt) * playbackRate).toLong()
            }
        }
    }

    val visible = remember(comments, renderedPositionMs, speed) {
        comments.withIndex()
            .filter { (_, comment) ->
                val elapsed = renderedPositionMs - comment.timeMs
                val duration = if (comment.kind == DanmakuKind.Scroll) {
                    speed.durationMs
                } else {
                    FIXED_DURATION_MS
                }
                elapsed in 0..duration
            }
            .takeLast(MAX_ON_SCREEN)
    }

    Box(
        modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(displayArea.fraction)
                .clipToBounds(),
        ) {
            val textSize = 18f * fontSize.scale
            val laneHeight = (textSize + 10f).dp
            val laneCount = (maxHeight / laneHeight).toInt().coerceAtLeast(1)

            visible.forEach { (index, comment) ->
                key(index, comment.timeMs, comment.text) {
                    val elapsed = (renderedPositionMs - comment.timeMs).coerceAtLeast(0L)
                    val approximateWidth = (
                        comment.text.length.coerceIn(2, 40) * textSize * 0.62f
                        ).dp
                    val x = when (comment.kind) {
                        DanmakuKind.Scroll -> {
                            val progress =
                                (elapsed.toFloat() / speed.durationMs).coerceIn(0f, 1f)
                            maxWidth - (maxWidth + approximateWidth) * progress
                        }
                        DanmakuKind.Top,
                        DanmakuKind.Bottom,
                        -> (maxWidth - approximateWidth).coerceAtLeast(0.dp) / 2f
                    }
                    val lane = stableLane(index, comment.timeMs, laneCount)
                    val y = when (comment.kind) {
                        DanmakuKind.Bottom -> laneHeight * (laneCount - lane - 1).toFloat()
                        else -> laneHeight * lane.toFloat()
                    }
                    Text(
                        text = comment.text,
                        maxLines = 1,
                        color = Color(0xFF000000 or comment.color)
                            .copy(alpha = opacity.alpha),
                        style = sc(textSize, 650).copy(
                            shadow = Shadow(
                                color = Color.Black.copy(alpha = 0.9f),
                                offset = Offset(1.5f, 1.5f),
                                blurRadius = 3f,
                            ),
                        ),
                        modifier = Modifier.offset(x = x, y = y),
                    )
                }
            }
        }
    }
}

private fun stableLane(index: Int, timeMs: Long, count: Int): Int {
    val mixed = index * 31 + (timeMs / 1_000L).toInt()
    return (mixed and Int.MAX_VALUE) % count
}
