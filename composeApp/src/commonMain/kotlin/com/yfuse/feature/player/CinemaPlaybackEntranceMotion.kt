package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberCinemaPlaybackEntrance(
    identity: Any,
    awaitingPicture: Boolean,
    pictureReady: Boolean,
    allowed: Boolean,
): CinemaPlaybackEntrance {
    // Outside AnimatedVisibility: removing its artwork must not forget a completed entrance.
    val entrance = remember(identity) { CinemaPlaybackEntrance(allowed && awaitingPicture) }
    LaunchedEffect(entrance, awaitingPicture, pictureReady, allowed) {
        entrance.update(awaitingPicture, pictureReady, allowed)
        if (entrance.needsFrames) {
            var previous = withFrameMillis { it }
            while (entrance.needsFrames) {
                withFrameMillis { now ->
                    entrance.advance((now - previous).toFloat())
                    previous = now
                }
            }
        }
    }
    return entrance
}

internal fun Modifier.cinemaPlaybackPush(entrance: CinemaPlaybackEntrance): Modifier =
    graphicsLayer {
        scaleX = entrance.scale
        scaleY = entrance.scale
    }

/**
 * Clips only the artwork cover. The live movie, subtitles, controls and HDR/DRM surface keep
 * their original geometry and continue playing under it. The Path is reused for this short move.
 */
internal fun Modifier.cinemaPlaybackReveal(entrance: CinemaPlaybackEntrance): Modifier =
    drawWithCache {
        val aperture = Path()
        val corner = 18.dp.toPx()
        onDrawWithContent {
            val progress = entrance.revealProgress
            when {
                !entrance.choreographed || progress <= 0f -> drawContent()
                progress >= 1f -> Unit
                else -> {
                    aperture.rewind()
                    aperture.addRoundRect(
                        RoundRect(
                            rect = cinemaPlaybackAperture(size, progress),
                            cornerRadius = CornerRadius(corner * (1f - progress)),
                        ),
                    )
                    clipPath(aperture, ClipOp.Difference) { this@onDrawWithContent.drawContent() }
                }
            }
        }
    }
