package com.yfuse.app

import androidx.compose.animation.core.CubicBezierEasing
import kotlin.math.atan2

/** Reference timings shared by the renderer and launch-cutoff checks. */
internal object WaterFireMechanismMotion {
    const val HOLO_BANDS = 30
    const val FAN_PLEATS = 18
    const val HOLO_SETTLED_MS = 1830f
    const val MARBLE_SETTLED_MS = 1720f
    const val FAN_SETTLED_MS = 1445f
    const val DOMINO_SETTLED_MS = 1460f

    private val scanEase = CubicBezierEasing(0.24f, 0.8f, 0.3f, 1f)
    private val gatherEase = CubicBezierEasing(0.3f, 0.05f, 0.24f, 1f)
    private val pleatEase = CubicBezierEasing(0.26f, 0.9f, 0.26f, 1.03f)
    private val fallEase = CubicBezierEasing(0.32f, 0.9f, 0.3f, 1f)

    fun bandProgress(
        time: Float,
        index: Int,
    ): Float = scanEase.transform(span(time, (HOLO_BANDS - 1 - index) / (HOLO_BANDS - 1f) * 880f, 950f))

    fun gatherProgress(
        time: Float,
        layer: Int,
    ): Float = gatherEase.transform(span(time, layer * 60f, 1350f))

    fun pleatProgress(
        time: Float,
        index: Int,
    ): Float = pleatEase.transform(span(time, index * 45f, 680f))

    fun pleatRotation(
        time: Float,
        index: Int,
    ): Float {
        val centerX = (index + 0.5f) / FAN_PLEATS * 432f + 40f
        val angle = -atan2(centerX - 256f, 336.96f) * 180f / PiF
        return angle * (1 - pleatProgress(time, index))
    }

    fun dominoProgress(
        time: Float,
        delay: Float,
    ): Float = fallEase.transform(span(time, delay, 440f))

    fun dominoTilt(progress: Float): Float =
        when {
            progress < 0.64f -> lerp(80f, -9f, progress / 0.64f)
            progress < 0.82f -> lerp(-9f, 4f, (progress - 0.64f) / 0.18f)
            else -> lerp(4f, 0f, (progress - 0.82f) / 0.18f)
        }
}
