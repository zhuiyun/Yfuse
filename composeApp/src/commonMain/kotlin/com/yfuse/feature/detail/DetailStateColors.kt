package com.yfuse.feature.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/** Contrast-safe colors for selection and interactive states on dynamically tinted detail pages. */
internal data class DetailStateColors(
    val surface: Color,
    val iconSurface: Color,
    val border: Color,
    val foreground: Color,
    val onPage: Color,
    val mutedOnPage: Color,
)

internal fun detailStateColors(
    accent: Color,
    pageBackground: Color,
    dark: Boolean,
): DetailStateColors {
    val opaqueAccent = accent.copy(alpha = 1f)
    val opaqueBackground = pageBackground.copy(alpha = 1f)
    val surfaceBase =
        if (dark) {
            lerp(opaqueBackground, opaqueAccent, 0.34f)
        } else {
            lerp(opaqueBackground, opaqueAccent, 0.30f)
        }
    val borderBase =
        if (dark) {
            lerp(opaqueAccent, Color.White, 0.32f)
        } else {
            lerp(opaqueAccent, Color.Black, 0.30f)
        }
    return DetailStateColors(
        surface = surfaceBase.copy(alpha = if (dark) 0.72f else 0.78f),
        iconSurface = surfaceBase.copy(alpha = if (dark) 0.90f else 0.94f),
        border = borderBase.copy(alpha = if (dark) 0.96f else 0.92f),
        foreground = readableStateAccent(opaqueAccent, surfaceBase, minimumRatio = 4.5f),
        onPage = readableStateAccent(opaqueAccent, opaqueBackground, minimumRatio = 4.5f),
        mutedOnPage = readableStateAccent(opaqueAccent, opaqueBackground, minimumRatio = 3.4f),
    )
}

/**
 * Harmonizes a dynamically extracted play-button color with the sampled page background so it
 * feels cohesive rather than garish, while keeping clear contrast against the background and its
 * own foreground ink.
 */
internal fun harmonizeDetailPlayButtonColor(
    accent: Color,
    background: Color,
    darkTheme: Boolean,
): Color {
    val opaqueAccent = accent.copy(alpha = 1f)
    val opaqueBg = background.copy(alpha = 1f)
    val bgHsl = opaqueBg.toDetailHsl()
    // Gently pull the accent toward the page background's atmosphere when the background has hue.
    val blended =
        if (bgHsl.saturation >= 0.06f) {
            lerp(opaqueAccent, opaqueBg, 0.16f)
        } else {
            opaqueAccent
        }
    val hsl = blended.toDetailHsl()
    // Temper overly neon saturation while preserving recognizable poster chroma.
    val maxSat = if (darkTheme) 0.64f else 0.58f
    val minSat = if (hsl.saturation > 0.04f) 0.22f else hsl.saturation
    val refined = hsl.copy(saturation = hsl.saturation.coerceIn(minSat, maxSat))

    val initial = refined.toDetailColor()
    if (detailContrastRatio(initial, opaqueBg) >= 3.2f) return initial

    // Shift HSL lightness smoothly away from the background rather than muting via RGB lerp.
    val bgLum = opaqueBg.luminance()
    val moveLighter = bgLum < 0.28f
    for (step in 1..24) {
        val frac = step / 24f
        val targetL =
            if (moveLighter) {
                refined.lightness + (0.90f - refined.lightness) * frac
            } else {
                refined.lightness * (1f - frac * 0.85f)
            }
        val candidate = refined.copy(lightness = targetL.coerceIn(0.06f, 0.94f)).toDetailColor()
        if (detailContrastRatio(candidate, opaqueBg) >= 3.2f) return candidate
    }
    return readableStateAccent(initial, opaqueBg, minimumRatio = 3.2f)
}

internal fun readableStateAccent(
    accent: Color,
    background: Color,
    minimumRatio: Float,
): Color {
    val foreground = accent.copy(alpha = 1f)
    val behind = background.copy(alpha = 1f)
    if (detailContrastRatio(foreground, behind) >= minimumRatio) return foreground

    val blackRatio = detailContrastRatio(Color.Black, behind)
    val whiteRatio = detailContrastRatio(Color.White, behind)
    val target = if (blackRatio >= whiteRatio) Color.Black else Color.White
    for (step in 1..20) {
        val candidate = lerp(foreground, target, step / 20f)
        if (detailContrastRatio(candidate, behind) >= minimumRatio) return candidate
    }
    return target
}

internal fun detailContrastRatio(
    foreground: Color,
    background: Color,
): Float {
    val foregroundLuminance = foreground.copy(alpha = 1f).luminance()
    val backgroundLuminance = background.copy(alpha = 1f).luminance()
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05f) / (darker + 0.05f)
}

private data class DetailHsl(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

private fun Color.toDetailHsl(): DetailHsl {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val l = (max + min) / 2f
    val denom = 1f - abs(2f * l - 1f)
    val s = if (delta <= 1e-6f || denom <= 1e-6f) 0f else (delta / denom).coerceIn(0f, 1f)
    val rawH =
        when {
            delta <= 1e-6f -> 0f
            max == red -> 60f * (((green - blue) / delta) % 6f)
            max == green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
    return DetailHsl(((rawH % 360f) + 360f) % 360f, s, l.coerceIn(0f, 1f))
}

private fun DetailHsl.toDetailColor(): Color {
    if (saturation <= 0f) return Color(lightness, lightness, lightness, 1f)
    val c = (1f - abs(2f * lightness - 1f)) * saturation
    val hp = hue / 60f
    val x = c * (1f - abs((hp % 2f) - 1f))
    val (r1, g1, b1) =
        when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
    val m = lightness - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = 1f,
    )
}
