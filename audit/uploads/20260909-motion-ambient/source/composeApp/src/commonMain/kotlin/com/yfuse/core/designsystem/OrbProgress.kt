package com.yfuse.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

/**
 * The app's one indeterminate loader: a comet circling a breathing core.
 *
 * It replaces Material's `CircularProgressIndicator` everywhere so that a button saving,
 * a list fetching its next page, a dialog connecting and the player rebuffering all say
 * "working" in the same voice. The comet is a full ring under a sweep gradient, so most of
 * the ring is transparent and only its head reads as a mark; the core scales 1 → 1.18 on the
 * skeleton pulse's own period, so a loader beside a skeleton breathes with it.
 *
 * Reduced motion draws the resting frame — head at twelve o'clock, core at rest.
 */
@Composable
fun OrbProgress(
    modifier: Modifier = Modifier,
    size: Dp = OrbProgressDefaults.Size,
    color: Color = LocalAccentColors.current.accent,
    contentDescription: String? = "加载中",
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val transition = rememberInfiniteTransition(label = "orb")
    val cometTurn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ORB_COMET_MS, easing = LinearEasing)),
        label = "orbComet",
    )
    val breathPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ORB_BREATH_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "orbBreath",
    )
    val turn = if (reduceMotion) 0f else cometTurn
    val coreScale = if (reduceMotion) 1f else orbCoreScale(breathPhase)
    val head = lerp(color, Color.White, 0.55f)
    val coreEdge = lerp(color, Color.Black, 0.25f)
    Canvas(
        modifier
            .size(size)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        val radius = this.size.minDimension / 2f
        val stroke = radius * ORB_RING_FRACTION
        val centre = Offset(radius, radius)
        rotate(turn * 360f, centre) {
            drawCircle(
                brush =
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        0.72f to color.copy(alpha = 0.18f),
                        0.94f to color,
                        1f to head,
                        center = centre,
                    ),
                radius = radius - stroke / 2f,
                center = centre,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        val coreRadius = radius * ORB_CORE_FRACTION * coreScale
        drawCircle(
            brush =
                Brush.radialGradient(
                    0f to head,
                    0.55f to color,
                    1f to coreEdge,
                    center = centre - Offset(coreRadius * 0.3f, coreRadius * 0.3f),
                    radius = coreRadius * 1.4f,
                ),
            radius = coreRadius,
            center = centre,
        )
    }
}

object OrbProgressDefaults {
    /** A loader beside body text. Buttons and chips use 15–18dp, page centres 32dp. */
    val Size: Dp = 22.dp
    val Inline: Dp = 16.dp
    val Page: Dp = 32.dp
}

/** Core scale over one breath: 1 at rest, [ORB_CORE_SWELL] larger at the midpoint. */
internal fun orbCoreScale(phase: Float): Float {
    val wave = (1f - cos(phase.coerceIn(0f, 1f) * 2f * PI.toFloat())) / 2f
    return 1f + ORB_CORE_SWELL * wave
}

internal const val ORB_COMET_MS = 1_200
internal const val ORB_BREATH_MS = SKELETON_PULSE_MS_INT
private const val ORB_RING_FRACTION = 0.2f
private const val ORB_CORE_FRACTION = 0.36f
private const val ORB_CORE_SWELL = 0.18f
