package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Soft safety envelope for the one opaque colour revealed by an artwork dissolve.
 *
 * The page must still look like the poster. These thresholds are deliberately wide: they
 * only rescue extreme near-black/near-light targets instead of normalising every poster into
 * the same beige/grey family. The artwork itself is never retinted.
 */
private const val LightArtworkPageMinimumLuminance = 0.18f
private const val DarkArtworkPageMinimumLuminance = 0.025f
private const val DarkArtworkPageMaximumLuminance = 0.20f

/**
 * Protects only pathological page targets while preserving the sampled hue and most of its
 * original luminance. Home, Library and detail all use this same final fade target.
 */
fun artworkPageSurface(
    sampled: Color,
    darkTheme: Boolean,
): Color =
    if (darkTheme) {
        sampled
            .moveLuminanceAtLeast(DarkArtworkPageMinimumLuminance)
            .moveLuminanceAtMost(DarkArtworkPageMaximumLuminance)
    } else {
        sampled.moveLuminanceAtLeast(LightArtworkPageMinimumLuminance)
    }

private fun Color.moveLuminanceAtLeast(minimum: Float): Color {
    var result = this
    repeat(28) {
        if (result.luminance() >= minimum) return result
        result = lerp(result, Color.White, 0.06f)
    }
    return result
}

private fun Color.moveLuminanceAtMost(maximum: Float): Color {
    var result = this
    repeat(28) {
        if (result.luminance() <= maximum) return result
        result = lerp(result, Color.Black, 0.06f)
    }
    return result
}
