package com.yfuse.feature.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

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
