package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One digit's roll. */
internal const val ROLLING_DIGIT_MS = 220

/** Between neighbouring digits, the lowest first: the change reads from the units up. */
internal const val ROLLING_DIGIT_STAGGER_MS = 30

/** 静息: the changed digits crossfade in place instead of rolling. */
internal const val ROLLING_CALM_MS = 120

/** The most a rolling figure should be fed: faster, a roll restarts before its digits can be read. */
const val ROLLING_NUMBER_MIN_INTERVAL_MS = 100L

/**
 * A number whose changed digits roll into place — the ticker of a trading app, for the figures in
 * this one that change while they are looked at: a download's percentage, a rating that arrives
 * with fresher data, how far an episode has been watched.
 *
 * Each digit that changes rolls on its own over 220 ms: a number going up brings its new digits up
 * from below, one going down drops them in from above, and neighbouring digits start 30 ms apart,
 * the units first. What is not a digit — a decimal point, a percent sign — stays where it is. 静息
 * crossfades the changed digits over 120 ms without moving them; 减少动画 swaps them at once. The
 * first value is simply shown: only a change rolls, and only while the change is on screen.
 *
 * A source that updates faster than it can be read goes through [rememberThrottledValue] first.
 *
 * @param color the ink, following theme changes as [ThemeText] does; [colorProducer] instead, for
 *   ink that animates while drawing (an artwork accent) without recomposing the number.
 */
@Composable
fun RollingNumber(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    colorProducer: ColorProducer? = null,
) {
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion
    val calm = calmMotion()
    val routeVisibility = rememberRouteVisibility()
    val themed = rememberThemeConsumerColor(color.takeOrElse { style.color.takeOrElse { LocalContentColor.current } })
    val ink = colorProducer ?: remember(themed) { ColorProducer { themed.value } }
    // Equal widths for every digit, so a rolling column never changes the number's width under it.
    val digitStyle = remember(style) { style.copy(fontFeatureSettings = "tnum") }

    // What the number showed before this value; the first composition has nothing to roll from.
    val lastShown = remember { mutableStateOf(text) }
    val from = remember(text) { lastShown.value }
    SideEffect { lastShown.value = text }
    val slots = remember(from, text) { rollingSlots(from, text) }
    val direction = remember(from, text) { rollingDirection(from, text) }
    val rolling = slots.count { it.rolls }
    val totalMs =
        if (calm) ROLLING_CALM_MS else ROLLING_DIGIT_MS + ROLLING_DIGIT_STAGGER_MS * (rolling - 1).coerceAtLeast(0)
    // 减少动画 starts, and stays, at rest: the new digits are simply there.
    val progress = remember(from, text) { Animatable(if (rolling > 0 && !reduceMotion) 0f else 1f) }
    LaunchedEffect(progress) {
        if (progress.value >= 1f) return@LaunchedEffect
        // Asked when the value arrives rather than observed: a page nobody is looking at just shows it.
        if (reduceMotion || !routeVisibility.value) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, Motion.tween(totalMs, easing = LinearEasing))
        }
    }

    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        slots.forEachIndexed { index, slot ->
            // Keyed from the right, where the units are, so a column keeps its roll as the number
            // gains or loses a digit on the left.
            key(slots.size - index) {
                RollingSlotText(
                    slot = slot,
                    style = digitStyle,
                    ink = ink,
                    direction = direction,
                    calm = calm,
                    progress = { rollingSlotProgress(progress.value, slot.order, totalMs, calm) },
                )
            }
        }
    }
}

@Composable
private fun RollingSlotText(
    slot: RollingSlot,
    style: TextStyle,
    ink: ColorProducer,
    direction: Int,
    calm: Boolean,
    progress: () -> Float,
) {
    if (!slot.rolls) {
        BasicText(slot.current.toString(), style = style, color = ink)
        return
    }
    Box(Modifier.clipToBounds()) {
        slot.previous?.let { previous ->
            BasicText(
                previous.toString(),
                style = style,
                color = ink,
                modifier =
                    Modifier.graphicsLayer {
                        val shown = progress()
                        alpha = 1f - shown
                        // Out the way the number is going: up when it rises, down when it falls.
                        if (!calm) translationY = -direction * size.height * shown
                    },
            )
        }
        BasicText(
            slot.current.toString(),
            style = style,
            color = ink,
            modifier =
                Modifier.graphicsLayer {
                    val shown = progress()
                    alpha = if (calm) shown else 1f
                    if (!calm) translationY = direction * size.height * (1f - shown)
                },
        )
    }
}

/**
 * One column of a [RollingNumber]: what it showed ([previous], null for a new leading digit) and
 * what it shows now. [order] counts the rolling columns from the right, for their stagger.
 */
internal data class RollingSlot(
    val previous: Char?,
    val current: Char,
    val rolls: Boolean,
    val order: Int,
)

/**
 * [current]'s columns, lined up on the right with [previous]'s so the units meet the units. A column
 * rolls when a digit is involved and it changed; a digit only [previous] had on the left is dropped.
 */
internal fun rollingSlots(
    previous: String,
    current: String,
): List<RollingSlot> {
    val shift = previous.length - current.length
    var order = 0
    return current.indices
        .reversed()
        .map { index ->
            val before = previous.getOrNull(index + shift)
            val now = current[index]
            val rolls = before != now && (now.isDigit() || before?.isDigit() == true)
            RollingSlot(before, now, rolls, if (rolls) order++ else 0)
        }.reversed()
}

/** 1 when the number went up — its new digits come up from below — and -1 when it went down. */
internal fun rollingDirection(
    previous: String,
    current: String,
): Int {
    val before = rollingValue(previous)
    val now = rollingValue(current)
    return if (before != null && now != null && now < before) -1 else 1
}

/** The figure in [text] — "8.4" of "★ 8.4", 45 of "45%" — or null when there is none. */
private fun rollingValue(text: String): Double? =
    text
        .filter { it.isDigit() || it == '.' }
        .toDoubleOrNull()

/**
 * How far one column is through its roll, given the whole number's [overall] progress over [totalMs].
 * Each column takes [ROLLING_DIGIT_MS] on the house curve, [order] staggers later; 静息 fades all at once.
 */
internal fun rollingSlotProgress(
    overall: Float,
    order: Int,
    totalMs: Int,
    calm: Boolean,
): Float {
    if (calm || totalMs <= 0) return overall.coerceIn(0f, 1f)
    val elapsed = overall.coerceIn(0f, 1f) * totalMs
    val local = ((elapsed - order * ROLLING_DIGIT_STAGGER_MS) / ROLLING_DIGIT_MS).coerceIn(0f, 1f)
    return Motion.Curve.transform(local)
}

/**
 * [value], changing at most once every [intervalMillis]: a figure fed from every progress callback
 * would restart its roll before any digit could be read. The latest value always lands, at most
 * [intervalMillis] after it arrived.
 */
@Composable
fun <T> rememberThrottledValue(
    value: T,
    intervalMillis: Long = ROLLING_NUMBER_MIN_INTERVAL_MS,
): T {
    val shown = remember { mutableStateOf(value) }
    val lastChange = remember { arrayOfNulls<TimeMark>(1) }
    LaunchedEffect(value) {
        val wait = throttleWaitMillis(lastChange[0]?.elapsedNow()?.inWholeMilliseconds, intervalMillis)
        if (wait > 0L) delay(wait)
        shown.value = value
        lastChange[0] = TimeSource.Monotonic.markNow()
    }
    return shown.value
}

/** How long a new value waits: nothing for the first, the rest of the interval after the last change. */
internal fun throttleWaitMillis(
    sinceLastChangeMillis: Long?,
    intervalMillis: Long,
): Long = if (sinceLastChangeMillis == null) 0L else (intervalMillis - sinceLastChangeMillis).coerceAtLeast(0L)
