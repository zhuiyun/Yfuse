package com.yfuse.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/** Shared loader for pages, buttons, dialogs and buffering, using the saved appearance. */
@Composable
fun OrbProgress(
    modifier: Modifier = Modifier,
    size: Dp = OrbProgressDefaults.Size,
    color: Color = LocalAccentColors.current.accent,
    contentDescription: String? = "加载中",
    animation: LoadingAnimation = LocalLoadingAnimation.current,
) {
    val moving = !LocalAccessibilityOptions.current.reduceMotion && LocalRouteVisible.current
    val phase =
        key(animation, moving) {
            if (moving) {
                rememberInfiniteTransition(label = "loading").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(animation.periodMillis, easing = LinearEasing)),
                    label = "loading-phase",
                )
            } else {
                rememberUpdatedState(0f)
            }
        }
    val dark = LocalPalette.current.isDark
    val artwork = remember(dark, color) { LoadingArtwork(dark, color) }
    Canvas(
        modifier
            .size(size)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        // Read the clock only while drawing: no layout or composition work on animation frames.
        val side = this.size.minDimension
        if (side > 0f) {
            translate((this.size.width - side) / 2f, (this.size.height - side) / 2f) {
                scale(side / 56f, pivot = Offset.Zero) {
                    with(artwork) { draw(animation, phase.value, moving) }
                }
            }
        }
    }
}

object OrbProgressDefaults {
    val Size: Dp = 22.dp
    val Inline: Dp = 16.dp
    val Page: Dp = 32.dp
}

internal fun orbCoreScale(phase: Float): Float {
    val wave = (1f - cos(phase.coerceIn(0f, 1f) * 2f * PI.toFloat())) / 2f
    return 1f + 0.18f * wave
}
