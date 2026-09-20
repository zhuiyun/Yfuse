package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** The three independently adjustable properties of modal glass. */
@Immutable
data class GlassMaterial(
    val tone: Float,
    val opacity: Float,
    val scrim: Float,
) {
    fun normalized(dark: Boolean): GlassMaterial {
        val fallback = defaults(dark)

        fun Float.valid(default: Float) = if (isFinite()) coerceIn(0f, 1f) else default
        return GlassMaterial(tone.valid(fallback.tone), opacity.valid(fallback.opacity), scrim.valid(fallback.scrim))
    }

    fun tint(dark: Boolean): Color {
        val value = normalized(dark)
        val start = if (dark) Color(0xFF191E27) else Color(0xFF878F9B)
        val end = if (dark) Color(0xFF878F9B) else Color(0xFFE4E9F0)
        val color =
            when (value.tone) {
                0f -> start
                1f -> end
                else -> lerp(start, end, value.tone)
            }
        return color.copy(alpha = value.opacity)
    }

    fun encode(): String = "$tone,$opacity,$scrim"

    companion object {
        val PreviousLight = GlassMaterial(0f, 0.52f, 0.16f)

        fun defaults(dark: Boolean): GlassMaterial =
            if (dark) GlassMaterial(0f, 0.72f, 0.28f) else GlassMaterial(1f, 0.82f, 0.30f)

        fun decode(
            stored: String?,
            dark: Boolean,
        ): GlassMaterial {
            val parts = stored?.split(',')?.map { it.toFloatOrNull() } ?: return defaults(dark)
            if (parts.size != 3 || parts.any { it == null }) return defaults(dark)
            return GlassMaterial(parts[0]!!, parts[1]!!, parts[2]!!).normalized(dark)
        }
    }
}

@Immutable
data class GlassMaterials(
    val light: GlassMaterial = GlassMaterial.defaults(false),
    val dark: GlassMaterial = GlassMaterial.defaults(true),
) {
    fun forTheme(dark: Boolean): GlassMaterial = if (dark) this.dark else light
}

val LocalGlassMaterials = compositionLocalOf { GlassMaterials() }
