package com.yfuse.app

internal val PiF = kotlin.math.PI.toFloat()

internal fun span(
    nowMs: Float,
    start: Float,
    duration: Float,
): Float = ((nowMs - start) / duration).coerceIn(0f, 1f)

internal fun smooth(value: Float): Float = value * value * (3f - 2f * value)

internal fun lerp(
    from: Float,
    to: Float,
    fraction: Float,
): Float = from + (to - from) * fraction

internal fun scatter(
    index: Int,
    salt: Int,
): Float {
    val hashed = (index * 73_856_093) xor (salt * 19_349_663)
    return ((hashed and 0xFFFF) / 65_535f)
}

internal data class CraftDot(
    val x: Float,
    val y: Float,
    val color: Int,
)