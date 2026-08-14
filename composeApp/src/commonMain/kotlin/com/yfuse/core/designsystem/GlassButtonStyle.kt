package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One visual contract for every actionable glass button, whether it lives in a form or dialog.
 * Context may change what is behind the glass, but emphasis never changes its material.
 */
internal enum class GlassButtonEmphasis {
    Primary,
    Neutral,
    Destructive,
}

@Immutable
internal data class GlassButtonVisuals(
    val fill: Color,
    val border: Color,
    val content: Color,
    val sheen: Float,
)

internal fun resolveGlassButtonVisuals(
    emphasis: GlassButtonEmphasis,
    palette: Palette,
    accent: AccentColors,
): GlassButtonVisuals =
    when (emphasis) {
        GlassButtonEmphasis.Primary ->
            GlassButtonVisuals(
                fill = accent.container.copy(alpha = 0.68f),
                border = accent.border,
                content = if (palette.isDark) palette.text else accent.accent,
                // Text spans the upper half of a compact control. Keeping this highlight
                // restrained prevents white specular light from washing out dark-theme ink.
                sheen = 0.50f,
            )
        GlassButtonEmphasis.Neutral ->
            GlassButtonVisuals(
                fill = palette.card2,
                border = palette.border,
                content = palette.text,
                sheen = 0.55f,
            )
        GlassButtonEmphasis.Destructive ->
            GlassButtonVisuals(
                fill = palette.errorContainer.copy(alpha = 0.66f),
                border = palette.error,
                content = if (palette.isDark) palette.onErrorContainer else palette.error,
                sheen = 0.50f,
            )
    }

internal fun glassButtonAlpha(enabled: Boolean): Float = if (enabled) 1f else 0.44f
