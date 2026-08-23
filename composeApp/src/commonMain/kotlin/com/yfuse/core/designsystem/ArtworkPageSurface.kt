@file:Suppress("ktlint:standard:property-naming")

package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/**
 * Soft safety envelope for the opaque colour revealed by an artwork dissolve.
 *
 * Every poster keeps its own sampled hue and normal brightness. Protection is confined to the
 * page colour revealed by the lower dissolve: the artwork bitmap itself is never brightened,
 * darkened or saturated.
 */
private const val LightArtworkPageMinimumLuminance = 0.24f
private const val DarkArtworkPageMinimumLuminance = 0.035f
private const val DarkArtworkPageMaximumLuminance = 0.20f

/** Low-chroma samples otherwise average into grey/muddy page colours at the bottom of a hero. */
private const val LightArtworkPageMinimumSaturation = 0.14f
private const val DarkArtworkPageMinimumSaturation = 0.10f

/**
 * Protects only weak/extreme targets while preserving the artwork's own hue.
 *
 * Luminance is corrected first, because lifting a dark colour toward white also drains chroma.
 * Saturation is then restored only when the sampled colour already carries a meaningful hue.
 * Truly neutral greys stay neutral rather than being assigned an arbitrary red/blue hue.
 * Finally the luminance envelope is checked once more because HSL saturation can move relative
 * luminance slightly even though HSL lightness itself stays fixed.
 */
fun artworkPageSurface(
    sampled: Color,
    darkTheme: Boolean,
): Color {
    val luminanceProtected = sampled.protectArtworkLuminance(darkTheme)
    val saturationProtected =
        luminanceProtected.softSaturateTo(
            if (darkTheme) DarkArtworkPageMinimumSaturation else LightArtworkPageMinimumSaturation,
        )
    return saturationProtected.protectArtworkLuminance(darkTheme)
}

private fun Color.protectArtworkLuminance(darkTheme: Boolean): Color =
    if (darkTheme) {
        softLiftTo(DarkArtworkPageMinimumLuminance)
            .softLowerTo(DarkArtworkPageMaximumLuminance)
    } else {
        softLiftTo(LightArtworkPageMinimumLuminance)
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

private const val NeutralSaturationGuard = 0.012f

/**
 * Raises only low-but-meaningful saturation toward [minimum]. A small neutral guard is crucial:
 * an RGB grey has no hue to preserve, so forcing an HSL saturation floor would manufacture one.
 */
private fun Color.softSaturateTo(minimum: Float): Color {
    val initial = toArtworkHsl()
    if (initial.saturation >= minimum || initial.saturation <= NeutralSaturationGuard) return this

    var saturation = initial.saturation
    repeat(18) {
        if (saturation >= minimum - 0.001f) {
            saturation = minimum
            return initial.copy(saturation = saturation).toColor()
        }
        val severity = ((minimum - saturation) / minimum).coerceIn(0f, 1f)
        val step = 0.16f + 0.34f * severity * severity
        saturation += (minimum - saturation) * step
    }
    return initial.copy(saturation = maxOf(saturation, minimum)).toColor()
}

private data class ArtworkHsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val alpha: Float,
)

private fun Color.toArtworkHsl(): ArtworkHsl {
    val maximum = maxOf(red, green, blue)
    val minimum = minOf(red, green, blue)
    val delta = maximum - minimum
    val lightness = (maximum + minimum) / 2f
    val denominator = 1f - abs(2f * lightness - 1f)
    val saturation =
        if (delta <= 0.000001f || denominator <= 0.000001f) {
            0f
        } else {
            (delta / denominator).coerceIn(0f, 1f)
        }
    val rawHue =
        when {
            delta <= 0.000001f -> 0f
            maximum == red -> 60f * (((green - blue) / delta) % 6f)
            maximum == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
    val hue = ((rawHue % 360f) + 360f) % 360f
    return ArtworkHsl(
        hue = hue,
        saturation = saturation,
        lightness = lightness.coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun ArtworkHsl.toColor(): Color {
    if (saturation <= 0f) {
        return Color(lightness, lightness, lightness, alpha)
    }
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val huePrime = hue / 60f
    val x = chroma * (1f - abs((huePrime % 2f) - 1f))
    val (redPrime, greenPrime, bluePrime) =
        when {
            huePrime < 1f -> Triple(chroma, x, 0f)
            huePrime < 2f -> Triple(x, chroma, 0f)
            huePrime < 3f -> Triple(0f, chroma, x)
            huePrime < 4f -> Triple(0f, x, chroma)
            huePrime < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
    val match = lightness - chroma / 2f
    return Color(
        red = (redPrime + match).coerceIn(0f, 1f),
        green = (greenPrime + match).coerceIn(0f, 1f),
        blue = (bluePrime + match).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
