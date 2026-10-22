package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

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
    val border: Color?,
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
                fill = lerp(accent.container, accent.accent, 0.08f).copy(alpha = 0.90f),
                border = glassControlHighlight(accent, palette.isDark),
                content =
                    readableSurfaceInk(
                        accent.accent,
                        lerp(
                            accent.container,
                            accent.accent,
                            0.08f,
                        ).copy(alpha = 0.90f).compositeOver(palette.background),
                    ),
                // Text spans the upper half of a compact control. Keeping this highlight
                // restrained prevents white specular light from washing out dark-theme ink.
                sheen = 0.40f,
            )
        GlassButtonEmphasis.Neutral ->
            GlassButtonVisuals(
                fill = palette.controlSurface,
                border = Color.White.copy(alpha = if (palette.isDark) 0.22f else 0.92f),
                content = palette.text,
                sheen = 0.55f,
            )
        GlassButtonEmphasis.Destructive ->
            GlassButtonVisuals(
                fill = palette.errorContainer.copy(alpha = 0.66f),
                border = lerp(palette.error, Color.White, 0.72f).copy(alpha = 0.72f),
                content =
                    readableSurfaceInk(
                        palette.error,
                        palette.errorContainer.copy(alpha = 0.66f).compositeOver(palette.background),
                    ),
                sheen = 0.50f,
            )
    }.let { visual ->
        visual.copy(content = readableGlassButtonInk(visual.content, visual.fill, palette))
    }

/** Frosted diffusion can lighten a dark fill after its original ink was selected. */
private fun readableGlassButtonInk(
    ink: Color,
    fill: Color,
    palette: Palette,
): Color {
    val frosted = resolveFrostedMaterialTones(fill, palette, GlassWeight.Strong.frostDensity)
    val surfaces =
        listOf(
            fill.compositeOver(palette.background),
            modalControlFill(fill, palette).compositeOver(palette.background),
            frosted.top.compositeOver(palette.background),
            frosted.body.compositeOver(palette.background),
            frosted.bottom.compositeOver(palette.background),
        )

    fun readable(candidate: Color): Boolean = surfaces.all { artworkPageContrastRatio(candidate, it) >= 4.5f }
    if (readable(ink)) return ink
    val endpoint = contentColorForSurface(palette.background)
    return (1..100).firstNotNullOfOrNull { step ->
        lerp(ink, endpoint, step / 100f).takeIf(::readable)
    } ?: endpoint
}

internal fun glassButtonAlpha(enabled: Boolean): Float = if (enabled) 1f else 0.44f

/** A luminous rim keeps controls separate without a dark outline. */
internal fun glassControlHighlight(
    accent: AccentColors,
    dark: Boolean,
): Color = lerp(accent.accent, Color.White, if (dark) 0.68f else 0.78f).copy(alpha = if (dark) 0.72f else 0.96f)

internal fun glassButtonLift(palette: Palette): CssShadow =
    CssShadow(0.dp, 3.dp, 10.dp, 0.dp, Color(0xFF294877).copy(alpha = if (palette.isDark) 0.20f else 0.12f))
