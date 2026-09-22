package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import kotlin.math.pow

/** Channel-wise sRGB interpolation stays inside the exact component bounds used by the contrast proof. */
internal fun interpolateArtworkPageColor(
    start: Color,
    end: Color,
    fraction: Float,
): Color {
    val from = start.convert(ColorSpaces.Srgb)
    val to = end.convert(ColorSpaces.Srgb)
    val progress = fraction.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * progress,
        green = from.green + (to.green - from.green) * progress,
        blue = from.blue + (to.blue - from.blue) * progress,
        alpha = 1f,
    )
}

/**
 * sRGB transfer and relative luminance are monotone in each channel. Component minima/maxima therefore
 * bound every possible intermediate luminance, including alpha-composited foregrounds. Disjoint
 * luminance intervals prove a 4.5:1 floor for the whole transition, without relying on sampled frames.
 */
internal fun artworkPageTransitionIsSafe(
    start: Color,
    end: Color,
    foregrounds: List<Color>,
): Boolean {
    val from = start.convert(ColorSpaces.Srgb)
    val to = end.convert(ColorSpaces.Srgb)
    if (from.alpha != 1f || to.alpha != 1f || foregrounds.isEmpty()) return false
    val lowRed = minOf(from.red, to.red).toDouble()
    val lowGreen = minOf(from.green, to.green).toDouble()
    val lowBlue = minOf(from.blue, to.blue).toDouble()
    val highRed = maxOf(from.red, to.red).toDouble()
    val highGreen = maxOf(from.green, to.green).toDouble()
    val highBlue = maxOf(from.blue, to.blue).toDouble()
    val backgroundLow = srgbLuminance(lowRed, lowGreen, lowBlue)
    val backgroundHigh = srgbLuminance(highRed, highGreen, highBlue)
    return foregrounds.all { foreground ->
        val ink = foreground.convert(ColorSpaces.Srgb)
        val alpha = ink.alpha.toDouble()

        // Constructing an intermediate sRGB Color rounds to 8-bit channels, which can round a
        // lower bound up and falsely approve translucent text just below the contrast floor.
        fun compositedLuminance(
            red: Double,
            green: Double,
            blue: Double,
        ) = srgbLuminance(
            red = ink.red.toDouble() * alpha + red * (1.0 - alpha),
            green = ink.green.toDouble() * alpha + green * (1.0 - alpha),
            blue = ink.blue.toDouble() * alpha + blue * (1.0 - alpha),
        )
        val inkLow = compositedLuminance(lowRed, lowGreen, lowBlue)
        val inkHigh = compositedLuminance(highRed, highGreen, highBlue)
        val guaranteedContrast =
            when {
                inkLow >= backgroundHigh -> (inkLow + 0.05) / (backgroundHigh + 0.05)
                backgroundLow >= inkHigh -> (backgroundLow + 0.05) / (inkHigh + 0.05)
                else -> 1.0
            }
        guaranteedContrast >= 4.5
    }
}

private fun srgbLuminance(
    red: Double,
    green: Double,
    blue: Double,
): Double {
    fun linear(channel: Double): Double =
        if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
    return 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
}
