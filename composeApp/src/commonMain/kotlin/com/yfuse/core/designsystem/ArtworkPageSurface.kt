package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Brightness envelope for a page that visually continues a poster/backdrop.
 *
 * The artwork itself is never retinted. Only the opaque colour revealed by the lower
 * alpha-dissolve is adjusted, so a dark poster remains a dark poster while the content
 * surface below it stays readable and consistent with the active light/dark appearance.
 */
private const val LightArtworkPageMinimumLuminance = 0.30f
private const val DarkArtworkPageMinimumLuminance = 0.04f
private const val DarkArtworkPageMaximumLuminance = 0.12f

/**
 * Protects the poster-derived page colour without replacing its hue with a fixed fallback.
 *
 * Light appearance only lifts colours that would make the whole page read as a dark theme.
 * Dark appearance keeps the same artwork colour inside a restrained dark envelope: very
 * bright artwork is brought down, while near-black artwork is lifted just enough that glass
 * edges and secondary surfaces do not disappear into a single black slab.
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
    repeat(24) {
        if (result.luminance() >= minimum) return result
        result = lerp(result, Color.White, 0.08f)
    }
    return result
}

private fun Color.moveLuminanceAtMost(maximum: Float): Color {
    var result = this
    repeat(24) {
        if (result.luminance() <= maximum) return result
        result = lerp(result, Color.Black, 0.08f)
    }
    return result
}
