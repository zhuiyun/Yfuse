package com.yfuse.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

private const val MinimumArtworkPageContrast = 4.5f
private const val StrongArtworkPageContrast = 7.0f

internal fun artworkPageContrastRatio(
    first: Color,
    second: Color,
): Float {
    val light = maxOf(first.luminance(), second.luminance())
    val dark = minOf(first.luminance(), second.luminance())
    return (light + 0.05f) / (dark + 0.05f)
}

private fun readableArtworkInk(
    foreground: Color,
    background: Color,
    minimumContrast: Float,
    lightInk: Boolean,
): Color {
    if (artworkPageContrastRatio(foreground, background) >= minimumContrast) return foreground
    val target = if (lightInk) Color.White else Color.Black
    var result = foreground
    repeat(24) {
        result = lerp(result, target, 0.12f)
        if (artworkPageContrastRatio(result, background) >= minimumContrast) return result
    }
    return target
}

/**
 * Semantic palette for the exact poster-derived page colour.
 *
 * The sampled background is intentionally never retinted here. Altering it would make
 * the fully transparent final hero pixel reveal a different colour and bring the seam
 * back. Readability is solved only on the foreground side.
 */
fun resolveArtworkPagePalette(background: Color): Palette {
    val darkPaletteContrast = artworkPageContrastRatio(DarkPalette.text, background)
    val lightPaletteContrast = artworkPageContrastRatio(LightPalette.text, background)
    val useDarkPalette = darkPaletteContrast >= lightPaletteContrast
    val base = if (useDarkPalette) DarkPalette else LightPalette
    return base.copy(
        background = background,
        text = readableArtworkInk(base.text, background, StrongArtworkPageContrast, useDarkPalette),
        sub = readableArtworkInk(base.sub, background, MinimumArtworkPageContrast, useDarkPalette),
        sub2 = readableArtworkInk(base.sub2, background, MinimumArtworkPageContrast, useDarkPalette),
        body = readableArtworkInk(base.body, background, MinimumArtworkPageContrast, useDarkPalette),
        hint = readableArtworkInk(base.hint, background, MinimumArtworkPageContrast, useDarkPalette),
        error = readableArtworkInk(base.error, background, MinimumArtworkPageContrast, useDarkPalette),
        isDark = useDarkPalette,
    )
}

@Composable
fun rememberArtworkPagePalette(background: Color?): Palette {
    val inherited = LocalPalette.current
    return remember(background, inherited) {
        background?.let(::resolveArtworkPagePalette) ?: inherited
    }
}

/** Publishes the poster-aware semantic palette to Yfuse and Material controls. */
@Composable
fun ArtworkPageTheme(
    background: Color?,
    artworkAccent: Color?,
    content: @Composable () -> Unit,
) {
    val palette = rememberArtworkPagePalette(background)
    val inheritedArtwork = LocalArtworkAccent.current
    val resolvedArtwork = artworkAccent ?: inheritedArtwork
    val inheritedAccent = LocalAccentColors.current
    val accentColors =
        remember(resolvedArtwork, palette.isDark, inheritedAccent) {
            resolvedArtwork?.let { resolveAccentColors(it, dark = palette.isDark) } ?: inheritedAccent
        }
    val outerScheme = MaterialTheme.colorScheme
    val surface = palette.card.compositeOver(palette.background)
    val surfaceVariant = palette.card2.compositeOver(palette.background)

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalArtworkAccent provides resolvedArtwork,
        LocalAccentColors provides accentColors,
    ) {
        MaterialTheme(
            colorScheme =
                outerScheme.copy(
                    primary = accentColors.accent,
                    onPrimary = accentColors.onAccent,
                    primaryContainer = accentColors.container,
                    onPrimaryContainer = accentColors.accent,
                    secondary = palette.sub,
                    tertiary = accentColors.accent,
                    onTertiary = accentColors.onAccent,
                    tertiaryContainer = accentColors.container,
                    onTertiaryContainer = accentColors.accent,
                    background = palette.background,
                    onBackground = palette.text,
                    surface = surface,
                    onSurface = palette.text,
                    surfaceVariant = surfaceVariant,
                    onSurfaceVariant = palette.sub2,
                    outline = palette.border,
                    outlineVariant = palette.tabbarBorder,
                    error = palette.error,
                    onError = palette.onError,
                    errorContainer = palette.errorContainer,
                    onErrorContainer = palette.onErrorContainer,
                ),
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
            content = content,
        )
    }
}
