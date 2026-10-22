package com.yfuse.core.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

/** Keep saved appearance selection separate from a custom panel's effective appearance. */
internal val LocalGlassAppearance = compositionLocalOf<Boolean?> { null }

fun contentColorForSurface(surface: Color): Color =
    listOf(Color.Black, Color.White).maxBy { artworkPageContrastRatio(it, surface) }

internal fun readableSurfaceInk(
    ink: Color,
    surface: Color,
    minimum: Float = 4.5f,
): Color {
    val opaque = ink.compositeOver(surface)
    if (artworkPageContrastRatio(opaque, surface) >= minimum) return opaque
    val endpoint = contentColorForSurface(surface)
    return (1..100).firstNotNullOfOrNull { step ->
        lerp(opaque, endpoint, step / 100f).takeIf { artworkPageContrastRatio(it, surface) >= minimum }
    } ?: endpoint
}

/** Controls stay translucent; only their ink is adapted to the surface beneath. */
val Palette.controlBorder: Color
    get() = border

val Palette.controlSurface: Color
    get() = card2

internal fun modalControlFill(
    fill: Color,
    palette: Palette,
    reduceTransparency: Boolean = false,
): Color =
    when {
        fill.alpha == 0f -> Color.Transparent
        reduceTransparency -> fill.compositeOver(palette.background)
        else -> fill.copy(alpha = fill.alpha.coerceAtMost(0.12f))
    }

internal fun modalControlBorder(
    border: Color?,
    palette: Palette,
): Color? = border?.let { if (it == palette.border || it == palette.tabbarBorder) palette.border else it }

/** Applies the same actual-surface contract to both custom and Material controls. */
@Composable
internal fun SurfaceContentTheme(
    palette: Palette,
    contrastSurface: Color = palette.background,
    content: @Composable () -> Unit,
) {
    val base = LocalArtworkAccent.current ?: Brand.Primary
    val accent = remember(base, palette, contrastSurface) { resolveAccentColors(base, palette.isDark, contrastSurface) }
    val scheme =
        MaterialTheme.colorScheme.copy(
            primary = accent.accent,
            onPrimary = accent.onAccent,
            primaryContainer = accent.container,
            onPrimaryContainer = accent.accent,
            secondary = palette.sub,
            tertiary = accent.accent,
            onTertiary = accent.onAccent,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.background,
            onSurface = palette.text,
            surfaceVariant = palette.controlSurface,
            onSurfaceVariant = palette.sub2,
            outline = palette.controlBorder,
            outlineVariant = palette.border,
            error = palette.error,
            onError = palette.onError,
            errorContainer = palette.errorContainer,
            onErrorContainer = palette.onErrorContainer,
        )
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalAccentColors provides accent,
        LocalContentColor provides palette.text,
        LocalThemeColorTarget provides ThemeColors(palette, accent),
    ) { MaterialTheme(colorScheme = scheme, content = content) }
}
