package com.yfuse.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.time.TimeSource

/**
 * 「水火潮涌」: the cold-start arrival of the library page.
 *
 * One wave leaves the top of the window and runs to the bottom in [TRAVEL_MS]. Each element it
 * reaches rises, dips once below rest and settles, and each picture it reaches fades in and
 * shrinks to size at that same moment, so the page reads as one surface being pushed rather than
 * cards arriving one by one. Everything is a draw-phase layer transform: layout, hit areas and
 * scrolling never move. The tab dock is not part of the page and does not ride the wave.
 */
object LaunchWaveSpec {
    /** Time for the wave to cross one window height. */
    const val TRAVEL_MS = 650f

    /**
     * Scale of the swing. The envelope has already decayed by the time the first rise peaks, so
     * the highest an element actually lifts is about half of this: under 8dp. It used to be 24dp
     * and three swings, which read as the page wobbling rather than arriving.
     */
    const val AMPLITUDE_DP = 16f

    /** One full rise and fall. */
    const val PERIOD_MS = 420f

    /** Envelope time constant: one rise, one small dip below rest (about 1dp), then rest. */
    const val DECAY_MS = 110f

    /** Scale follows the swing in phase, so a lifted element reads as floating. */
    const val SCALE_SWING = 0.015f

    const val IMAGE_FADE_MS = 360f
    const val IMAGE_ZOOM_MS = 600f
    const val IMAGE_ZOOM_FROM = 1.08f

    /** The hero picture arrives first and slower, and sways inside its own frame. */
    const val HERO_FADE_MS = 520f
    const val HERO_ZOOM_MS = 900f
    const val HERO_SWAY = 0.6f

    /** Below half a pixel the swing is invisible; the clock stops there. */
    val TOTAL_MS: Float = TRAVEL_MS + DECAY_MS * ln(AMPLITUDE_DP / 0.5f) + 60f

    /**
     * A page that shows up later than this does not get the wave. Past a few seconds the person
     * is already looking at the page, waiting — and a wave then is the page moving under them.
     */
    const val ARMED_WINDOW_MS = 3_000L
}

/** Displacement (px, negative is up) and scale of one element [tauMs] after the wave reached it. */
data class LaunchWaveBob(
    val offset: Float,
    val scale: Float,
)

fun launchWaveBob(
    tauMs: Float,
    amplitudePx: Float,
): LaunchWaveBob {
    if (tauMs <= 0f) return LaunchWaveBob(0f, 1f)
    val envelope = exp(-tauMs / LaunchWaveSpec.DECAY_MS)
    val phase = sin(2f * PI.toFloat() * tauMs / LaunchWaveSpec.PERIOD_MS)
    return LaunchWaveBob(
        offset = -amplitudePx * phase * envelope,
        scale = 1f + LaunchWaveSpec.SCALE_SWING * phase * envelope,
    )
}

/** When the wave reaches an element whose top edge sits at [topPx] in a window [windowPx] tall. */
fun launchWaveArrivalMs(
    topPx: Float,
    windowPx: Float,
): Float {
    if (windowPx <= 0f) return 0f
    return (topPx.coerceIn(0f, windowPx) / windowPx) * LaunchWaveSpec.TRAVEL_MS
}

internal fun launchWaveEase(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return 1f - (1f - t) * (1f - t) * (1f - t)
}

/**
 * Arms the wave once per process: set by the root on a cold start that opens on 库, consumed by
 * the library page the first time it has content on screen. Leaving the tab or waiting past
 * [LaunchWaveSpec.ARMED_WINDOW_MS] disarms it, so the wave never plays on a later visit.
 */
object LaunchWaveGate {
    private var armedAt: TimeSource.Monotonic.ValueTimeMark? = null

    /** The wave that is playing right now, for chrome outside the page (the tab dock). */
    var current: LaunchWaveState? by mutableStateOf(null)
        internal set

    val pending: Boolean
        get() =
            armedAt?.let { it.elapsedNow().inWholeMilliseconds <= LaunchWaveSpec.ARMED_WINDOW_MS } == true

    fun arm() {
        armedAt = TimeSource.Monotonic.markNow()
    }

    fun disarm() {
        armedAt = null
    }

    /** True exactly once, while armed. */
    fun consume(): Boolean = pending.also { armedAt = null }
}

@Stable
class LaunchWaveState internal constructor(
    private val scope: CoroutineScope,
    armed: Boolean,
) {
    private enum class Phase { Idle, Armed, Running, Done }

    private val clock = Animatable(0f)
    private var phase by mutableStateOf(if (armed) Phase.Armed else Phase.Idle)

    /**
     * Milliseconds since the wave left the top, or null when nothing on this page animates.
     * While armed it holds at 0: the first content frame is composed with pictures hidden, so
     * they never flash at full strength one frame before the wave takes them.
     */
    val elapsedMs: Float?
        get() =
            when (phase) {
                Phase.Idle, Phase.Done -> null
                Phase.Armed -> 0f
                Phase.Running -> clock.value
            }

    /** Phase only, never the clock: safe to read in composition without recomposing per frame. */
    val animating: Boolean
        get() = phase == Phase.Armed || phase == Phase.Running

    internal suspend fun run() {
        if (phase != Phase.Armed) return
        phase = Phase.Running
        LaunchWaveGate.current = this
        try {
            clock.snapTo(0f)
            clock.animateTo(
                LaunchWaveSpec.TOTAL_MS,
                tween(LaunchWaveSpec.TOTAL_MS.toInt(), easing = LinearEasing),
            )
        } finally {
            settle()
        }
    }

    /** Drops the wave where everything rests, e.g. the moment the user touches the page. */
    fun finish() {
        if (phase == Phase.Idle || phase == Phase.Done) return
        scope.launch { clock.stop() }
        settle()
    }

    private fun settle() {
        phase = Phase.Done
        if (LaunchWaveGate.current === this) LaunchWaveGate.current = null
    }
}

val LocalLaunchWave = staticCompositionLocalOf<LaunchWaveState?> { null }

/**
 * The page's wave. It starts the first time [contentVisible] is true while the gate is armed,
 * and never on any other visit; with 减弱动态效果 on it is consumed without playing.
 */
@Composable
fun rememberLaunchWave(contentVisible: Boolean): LaunchWaveState {
    val scope = rememberCoroutineScope()
    // 静息 has no waves: the page simply fades in with the shell.
    val reduceMotion = LocalAccessibilityOptions.current.reduceMotion || calmMotion()
    val wave = remember { LaunchWaveState(scope, armed = LaunchWaveGate.pending && !reduceMotion) }
    LaunchedEffect(contentVisible) {
        if (!contentVisible) return@LaunchedEffect
        if (LaunchWaveGate.consume() && !reduceMotion) wave.run() else wave.finish()
    }
    return wave
}

/** Any press on the page ends the wave at once; the press itself is not consumed. */
fun Modifier.launchWaveInterrupt(wave: LaunchWaveState): Modifier =
    pointerInput(wave) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            wave.finish()
        }
    }

private class LaunchWaveAnchor {
    var arrivalMs = 0f

    fun update(coordinates: LayoutCoordinates) {
        val window = coordinates.findRootCoordinates().size
        arrivalMs = launchWaveArrivalMs(topPx = coordinates.positionInRoot().y, windowPx = window.height.toFloat())
    }
}

/** Rises, sinks and settles when the wave reaches this element's top edge. */
@Composable
fun Modifier.launchWaveItem(wave: LaunchWaveState? = LocalLaunchWave.current): Modifier {
    if (wave == null) return this
    val anchor = remember { LaunchWaveAnchor() }
    return onPlaced(anchor::update).graphicsLayer {
        val elapsed = wave.elapsedMs ?: return@graphicsLayer
        val bob = launchWaveBob(elapsed - anchor.arrivalMs, LaunchWaveSpec.AMPLITUDE_DP.dp.toPx())
        translationY = bob.offset
        scaleX = bob.scale
        scaleY = bob.scale
    }
}

/**
 * The picture develops when the wave reaches it: fades in over its placeholder and shrinks from
 * [LaunchWaveSpec.IMAGE_ZOOM_FROM]. Apply inside the picture's clip, so the zoom never spills.
 * [hero] uses the slower hero timings and lets the picture sway within its frame.
 */
@Composable
fun Modifier.launchWaveImage(
    hero: Boolean = false,
    wave: LaunchWaveState? = LocalLaunchWave.current,
): Modifier {
    if (wave == null) return this
    val anchor = remember { LaunchWaveAnchor() }
    return onPlaced(anchor::update).graphicsLayer {
        val elapsed = wave.elapsedMs ?: return@graphicsLayer
        val tau = elapsed - anchor.arrivalMs
        val fadeMs = if (hero) LaunchWaveSpec.HERO_FADE_MS else LaunchWaveSpec.IMAGE_FADE_MS
        val zoomMs = if (hero) LaunchWaveSpec.HERO_ZOOM_MS else LaunchWaveSpec.IMAGE_ZOOM_MS
        alpha = launchWaveEase(tau / fadeMs)
        val from = LaunchWaveSpec.IMAGE_ZOOM_FROM
        val zoom = from - (from - 1f) * launchWaveEase(tau / zoomMs)
        scaleX = zoom
        scaleY = zoom
        if (hero) {
            translationY =
                LaunchWaveSpec.HERO_SWAY * launchWaveBob(tau, LaunchWaveSpec.AMPLITUDE_DP.dp.toPx()).offset
        }
    }
}
