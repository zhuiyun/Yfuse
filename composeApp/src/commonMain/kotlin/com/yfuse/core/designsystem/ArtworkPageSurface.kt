package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Soft safety envelope for the opaque colour revealed by an artwork dissolve.
 *
 * Every poster keeps its own sampled hue and normal brightness. Protection only enters for
 * extremes: near-black colours in light appearance, and near-black / overly bright colours
 * in dark appearance. The artwork itself is never retinted.
 */
private const val LightArtworkPageMinimumLuminance = 0.18f
private const val DarkArtworkPageMinimumLuminance = 0.025f
private const val DarkArtworkPageMaximumLuminance = 0.20f

/**
 * Protects only extreme targets. The correction uses a soft knee: the farther a sample is
 * outside the safe envelope the larger each correction step is; close to the threshold the
 * step becomes very small. That keeps red posters red, blue posters blue and green posters
 * green instead of normalising them towards one grey/beige surface.
 */
fun artworkPageSurface(
    sampled: Color,
    darkTheme: Boolean,
): Color =
    if (darkTheme) {
        sampled
            .softLiftTo(DarkArtworkPageMinimumLuminance)
            .softLowerTo(DarkArtworkPageMaximumLuminance)
    } else {
        sampled.softLiftTo(LightArtworkPageMinimumLuminance)
    }

private fun Color.softLiftTo(minimum: Float): Color {
    if (luminance() >= minimum) return this
    var result = this
    repeat(48) {
        val current = result.luminance()
        if (current >= minimum) return result
        val severity = ((minimum - current) / minimum).coerceIn(0f, 1f)
        val step = 0.012f + 0.078f * severity * severity
        result = lerp(result, Color.White, step)
    }
    return result
}

private fun Color.softLowerTo(maximum: Float): Color {
    if (luminance() <= maximum) return this
    var result = this
    repeat(48) {
        val current = result.luminance()
        if (current <= maximum) return result
        val severity = ((current - maximum) / (1f - maximum)).coerceIn(0f, 1f)
        val step = 0.012f + 0.078f * severity * severity
        result = lerp(result, Color.Black, step)
    }
    return result
}
