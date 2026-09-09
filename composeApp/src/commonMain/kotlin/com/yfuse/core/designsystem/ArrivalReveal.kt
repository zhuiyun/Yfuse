package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Progress of the [index]th item in a staggered arrival: each item starts [step] later than
 * the one before, capped at [maxIndex] so a long list does not keep the tail invisible.
 *
 * [progress] is the shared clock's elapsed fraction, linear. The stagger is applied in that
 * linear time and each item is then eased on its own — easing the shared clock first would
 * make nearly every item enter together in the first few frames.
 */
fun staggeredReveal(
    progress: Float,
    index: Int,
    step: Float = REVEAL_STEP,
    maxIndex: Int = REVEAL_MAX_INDEX,
): Float {
    val delay = index.coerceIn(0, maxIndex) * step
    val local = ((progress - delay) / (1f - delay)).coerceIn(0f, 1f)
    return Motion.Curve.transform(local)
}

/**
 * Motion for content replacing a skeleton: items rise [REVEAL_LIFT] and fade in, in order.
 *
 * One clock for the whole page, so rows that appear on later frames join the same sweep
 * rather than each starting its own entrance.
 */
class ArrivalMotion internal constructor(
    private val progress: Animatable<Float, AnimationVector1D>,
) {
    /** The shared clock, linear: 0 when the arrival starts, 1 once everything has landed. */
    val rawProgress: Float
        get() = progress.value

    fun item(index: Int = 0): Modifier =
        Modifier.graphicsLayer {
            val amount = staggeredReveal(progress.value, index)
            alpha = amount
            translationY = REVEAL_LIFT.toPx() * (1f - amount)
        }

    /** The page's artwork: it fades in and settles from a slight zoom rather than rising. */
    fun hero(): Modifier =
        Modifier.graphicsLayer {
            val amount = Motion.Curve.transform(progress.value)
            alpha = amount
            val scale = HERO_SETTLE_SCALE - (HERO_SETTLE_SCALE - 1f) * amount
            scaleX = scale
            scaleY = scale
        }
}

/**
 * Starts the arrival once [arrived] turns true while the route is visible; content that is
 * already there when the page is first composed, or that arrives with motion reduced, is
 * shown at rest.
 */
@Composable
fun rememberArrivalReveal(arrived: Boolean): ArrivalMotion {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val initiallyArrived = remember { arrived }
    val progress = remember { Animatable(if (arrived || !moving) 1f else 0f) }
    LaunchedEffect(arrived, moving) {
        when {
            !arrived -> progress.snapTo(0f)
            !moving || initiallyArrived -> progress.snapTo(1f)
            progress.value < 1f -> progress.animateTo(1f, tween(REVEAL_MS, easing = LinearEasing))
        }
    }
    return remember(progress) { ArrivalMotion(progress) }
}

/**
 * Content that is complete on its first frame but still deserves an entrance — an empty
 * state, an error with its retry — plays the arrival once, from its first composition.
 */
@Composable
fun rememberEntranceReveal(): ArrivalMotion {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val progress = remember { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, tween(REVEAL_MS, easing = LinearEasing))
    }
    return remember(progress) { ArrivalMotion(progress) }
}

/**
 * Content replaced in place — a shelf after 换一批, a list after pull-to-refresh — keeps
 * showing the old rows while [refreshing] and plays the arrival for the new ones the moment
 * it turns false. Nothing plays for the first load or for a refresh that was cancelled
 * before it began.
 */
@Composable
fun rememberRefreshReveal(refreshing: Boolean): ArrivalMotion {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val progress = remember { Animatable(1f) }
    var wasRefreshing by remember { mutableStateOf(refreshing) }
    LaunchedEffect(refreshing, moving) {
        val landed = wasRefreshing && !refreshing
        wasRefreshing = refreshing
        if (landed && moving) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(REVEAL_MS, easing = LinearEasing))
        }
    }
    return remember(progress) { ArrivalMotion(progress) }
}

/**
 * The page-level sweep that accompanies an arrival: one soft diagonal band crossing the
 * node while [motion] runs, gone once it has landed. Same band as the skeleton sweep, so
 * a refresh reads as the loading language finishing rather than a new effect.
 */
@Composable
fun Modifier.arrivalSweep(motion: ArrivalMotion): Modifier {
    val palette = LocalPalette.current
    val accent = LocalAccentColors.current.accent
    val band =
        if (palette.isDark) {
            Color.White.copy(alpha = ARRIVAL_SWEEP_ALPHA_DARK)
        } else {
            accent.copy(alpha = ARRIVAL_SWEEP_ALPHA_LIGHT)
        }
    return drawWithContent {
        drawContent()
        val progress = motion.rawProgress
        if (progress <= 0f || progress >= 1f) return@drawWithContent
        drawDiagonalSweep(band, progress)
    }
}

/**
 * One accent-tinted sweep across the node whenever [key] changes to a non-null value: the
 * way a control says "something just arrived for you" without moving. The dock plays it
 * when a 一起看 invite lands, right before the invite sheet opens.
 */
@Composable
fun Modifier.attentionSweep(key: Any?): Modifier {
    val moving = LocalRouteVisible.current && !LocalAccessibilityOptions.current.reduceMotion
    val accent = LocalAccentColors.current.accent
    val progress = remember { Animatable(1f) }
    LaunchedEffect(key, moving) {
        if (key == null || !moving) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(ATTENTION_SWEEP_MS, easing = LinearEasing))
    }
    val band = accent.copy(alpha = ATTENTION_SWEEP_ALPHA)
    return drawWithContent {
        drawContent()
        val value = progress.value
        if (value <= 0f || value >= 1f) return@drawWithContent
        drawDiagonalSweep(band, value)
    }
}

/** A 120° band whose head runs from before the top-left corner to past the bottom-right. */
internal fun DrawScope.drawDiagonalSweep(
    band: Color,
    progress: Float,
) {
    val envelope = sin(progress * PI.toFloat())
    val span = size.width + size.height * SWEEP_SLOPE
    val width = span * SWEEP_BAND
    val head = -width + (span + 2f * width) * progress
    drawRect(
        brush =
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to band.copy(alpha = band.alpha * envelope),
                1f to Color.Transparent,
                start = Offset(head - width, size.height),
                end = Offset(head, 0f),
            ),
    )
}

const val REVEAL_MS = 480
const val REVEAL_STEP = 0.07f
const val REVEAL_MAX_INDEX = 5
val REVEAL_LIFT = 9.dp
private const val HERO_SETTLE_SCALE = 1.06f
const val ATTENTION_SWEEP_MS = 520
private const val ATTENTION_SWEEP_ALPHA = 0.22f
private const val ARRIVAL_SWEEP_ALPHA_DARK = 0.07f
private const val ARRIVAL_SWEEP_ALPHA_LIGHT = 0.09f
internal const val SWEEP_BAND = 0.34f
internal const val SWEEP_SLOPE = 0.58f
