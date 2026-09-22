package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

enum class GlassInk { Theme, Light, Dark }

/** Editable optical recipe; preset is its origin, not a switch in the renderer. */
@Immutable
data class GlassMaterial(
    val tone: Float,
    val opacity: Float,
    val scrim: Float,
    val preset: GlassMaterialPreset = GlassMaterialPreset.Default,
    val tintRgb: Int? = null,
    val blur: Float = preset.blur,
    val saturation: Float = preset.saturation,
    val refraction: Float = preset.refraction,
    val rim: Float = preset.rim,
    val rimWidth: Float = if (preset == GlassMaterialPreset.Prism) 1f else 0.6f,
    val prism: Float = if (preset == GlassMaterialPreset.Prism) 1f else 0f,
    val pearl: Float = if (preset == GlassMaterialPreset.Pearl) 1f else 0f,
    val fluted: Float = if (preset == GlassMaterialPreset.Fluted) 1f else 0f,
    val fluteWidth: Float = 12f,
    val ink: GlassInk = if (preset == GlassMaterialPreset.Smoke) GlassInk.Light else GlassInk.Theme,
) {
    fun normalized(dark: Boolean): GlassMaterial {
        val fallback = preset.material(dark)

        fun Float.valid(default: Float) = if (isFinite()) coerceIn(0f, 1f) else default

        fun Float.bounded(
            default: Float,
            min: Float,
            max: Float,
        ) = if (isFinite()) coerceIn(min, max) else default
        return copy(
            tone = tone.valid(fallback.tone),
            opacity = opacity.valid(fallback.opacity),
            scrim = scrim.valid(fallback.scrim),
            tintRgb = tintRgb?.and(0xFFFFFF),
            blur = blur.bounded(fallback.blur, 1f, 70f),
            saturation = saturation.bounded(fallback.saturation, 0f, 2f),
            refraction = refraction.bounded(fallback.refraction, 0f, 12f),
            rim = rim.valid(fallback.rim),
            rimWidth = rimWidth.bounded(fallback.rimWidth, 0.3f, 2f),
            prism = prism.valid(fallback.prism),
            pearl = pearl.valid(fallback.pearl),
            fluted = fluted.valid(fallback.fluted),
            fluteWidth = fluteWidth.bounded(fallback.fluteWidth, 4f, 32f),
        )
    }

    fun tint(dark: Boolean): Color {
        val value = normalized(dark)
        value.tintRgb?.let { return Color(it or (0xFF shl 24)).copy(alpha = value.opacity) }
        val (start, end) = tintRange(dark)
        val color =
            when (value.tone) {
                0f -> start
                1f -> end
                else -> lerp(start, end, value.tone)
            }
        return color.copy(alpha = value.opacity)
    }

    private fun tintRange(dark: Boolean): Pair<Color, Color> =
        when (preset) {
            GlassMaterialPreset.Default ->
                if (dark) Color(0xFF191E27) to Color(0xFF878F9B) else Color(0xFF878F9B) to Color(0xFFE4E9F0)
            GlassMaterialPreset.Smoke -> Color(0xFF152330) to Color(0xFF344856)
            GlassMaterialPreset.Pearl ->
                if (dark) Color(0xFF423C51) to Color(0xFF746573) else Color(0xFFBBBACB) to Color(0xFFF2ECF0)
            GlassMaterialPreset.Fluted ->
                if (dark) Color(0xFF1C435D) to Color(0xFF7294A6) else Color(0xFF8CAAB7) to Color(0xFFD1E2E4)
            else ->
                if (dark) Color(0xFF222D3A) to Color(0xFF778998) else Color(0xFFA5B5C3) to Color(0xFFEFF4F6)
        }

    /** Ink is independent of the source preset; retain the app theme's storage identity. */
    fun contentPalette(palette: Palette): Palette =
        when (ink) {
            GlassInk.Theme -> palette
            GlassInk.Light -> DarkPalette.copy(isDark = palette.isDark)
            GlassInk.Dark -> LightPalette.copy(isDark = palette.isDark)
        }

    fun encode(): String =
        listOf(
            "v3",
            preset.id,
            tone,
            opacity,
            scrim,
            tintRgb ?: "auto",
            blur,
            saturation,
            refraction,
            rim,
            rimWidth,
            prism,
            pearl,
            fluted,
            fluteWidth,
            ink.name,
        ).joinToString(",")

    companion object {
        val PreviousLight = GlassMaterial(0f, 0.52f, 0.16f)

        fun defaults(dark: Boolean): GlassMaterial =
            if (dark) GlassMaterial(0f, 0.72f, 0.28f) else GlassMaterial(1f, 0.82f, 0.30f)

        fun decode(
            stored: String?,
            dark: Boolean,
        ): GlassMaterial {
            val fields = stored?.split(',') ?: return defaults(dark)
            if (fields.size == 16 && fields[0] == "v3") {
                val preset = GlassMaterialPreset.fromId(fields[1])
                val base = preset.material(dark)

                fun number(
                    index: Int,
                    fallback: Float,
                ) = fields[index].toFloatOrNull() ?: fallback
                return base
                    .copy(
                        tone = number(2, base.tone),
                        opacity = number(3, base.opacity),
                        scrim = number(4, base.scrim),
                        tintRgb = fields[5].toIntOrNull(),
                        blur = number(6, base.blur),
                        saturation = number(7, base.saturation),
                        refraction = number(8, base.refraction),
                        rim = number(9, base.rim),
                        rimWidth = number(10, base.rimWidth),
                        prism = number(11, base.prism),
                        pearl = number(12, base.pearl),
                        fluted = number(13, base.fluted),
                        fluteWidth = number(14, base.fluteWidth),
                        ink = GlassInk.entries.firstOrNull { it.name == fields[15] } ?: base.ink,
                    ).normalized(dark)
            }
            val preset =
                if (fields.size == 5 && fields[0] == "v2") {
                    GlassMaterialPreset.fromId(fields[1])
                } else if (fields.size == 3) {
                    GlassMaterialPreset.Default
                } else {
                    return defaults(dark)
                }
            val parts = fields.takeLast(3).map { it.toFloatOrNull() }
            if (parts.any { it == null }) return defaults(dark)
            return GlassMaterial(parts[0]!!, parts[1]!!, parts[2]!!, preset).normalized(dark)
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
