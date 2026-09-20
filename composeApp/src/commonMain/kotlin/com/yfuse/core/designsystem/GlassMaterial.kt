package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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

    fun tint(dark: Boolean): Color = normalized(dark).rawTint(dark)

    /** [tint] of a recipe that is already normalized - the renderer normalizes once per read. */
    private fun rawTint(dark: Boolean): Color {
        tintRgb?.let { return Color(it or (0xFF shl 24)).copy(alpha = opacity) }
        val (start, end) = tintRange(dark)
        val color =
            when (tone) {
                0f -> start
                1f -> end
                else -> lerp(start, end, tone)
            }
        return color.copy(alpha = opacity)
    }

    /**
     * The fill [mutedGlassPanel] paints. [opaque] is the solid fallback - no backdrop blur below
     * Android 12, or 降低透明度. There the tint is laid over the page colour at its own opacity,
     * so a preset keeps its hue and weight instead of every recipe collapsing into one colour.
     */
    fun panelBody(
        dark: Boolean,
        opaque: Boolean,
    ): Color {
        val value = normalized(dark)
        val tint = value.rawTint(dark)
        if (!opaque) return tint
        // The shipped default keeps the exact solid it always had.
        if (value == defaults(dark)) return if (dark) tint.copy(alpha = 1f) else LightPalette.background
        return tint.compositeOver(if (dark) DarkPalette.background else LightPalette.background)
    }

    /**
     * The panel as the eye meets it: [panelBody] over the page its own scrim has dimmed. An
     * estimate - the blurred page behind translucent glass can be any colour - but it is the
     * same estimate the fixed dialog inks were calibrated against.
     */
    internal fun perceivedSurface(
        palette: Palette,
        opaque: Boolean,
    ): Color {
        val page = palette.scrim.copy(alpha = normalized(palette.isDark).scrim).compositeOver(palette.background)
        return panelBody(palette.isDark, opaque).compositeOver(page)
    }

    /**
     * The ink in use. An explicit 浅色/深色文字 is the user's call. 跟随主题 yields to the other
     * ink only when the theme's own text falls under [MIN_TEXT_CONTRAST] on this recipe and the
     * other ink reads better - a dark custom tint in the light theme, for one.
     */
    fun resolvedInk(
        palette: Palette,
        opaque: Boolean = false,
    ): GlassInk {
        if (ink != GlassInk.Theme) return ink
        val surface = perceivedSurface(palette, opaque)
        val themeInk = if (palette.isDark) GlassInk.Light else GlassInk.Dark
        val otherInk = if (palette.isDark) GlassInk.Dark else GlassInk.Light
        val themeContrast = inkContrast(themeInk, surface)
        if (themeContrast >= MIN_TEXT_CONTRAST) return GlassInk.Theme
        return if (inkContrast(otherInk, surface) > themeContrast) otherInk else GlassInk.Theme
    }

    /** Body-text contrast of [resolvedInk] against [perceivedSurface], for the settings notice. */
    fun textContrast(
        palette: Palette,
        opaque: Boolean = false,
    ): Float {
        val resolved = resolvedInk(palette, opaque)
        val effective =
            if (resolved == GlassInk.Theme) {
                if (palette.isDark) GlassInk.Light else GlassInk.Dark
            } else {
                resolved
            }
        return inkContrast(effective, perceivedSurface(palette, opaque))
    }

    private fun inkContrast(
        ink: GlassInk,
        surface: Color,
    ): Float {
        val text = if (ink == GlassInk.Light) DarkPalette.dialogBody else LightPalette.dialogBody
        val lighter = maxOf(text.luminance(), surface.luminance())
        val darker = minOf(text.luminance(), surface.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
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
    fun contentPalette(
        palette: Palette,
        opaque: Boolean = false,
    ): Palette =
        when (resolvedInk(palette, opaque)) {
            GlassInk.Theme -> palette
            GlassInk.Light -> if (palette.isDark) palette else DarkPalette.copy(isDark = false)
            GlassInk.Dark -> if (palette.isDark) LightPalette.copy(isDark = true) else palette
        }

    /**
     * Named fields, so a recipe written by a build with one more knob still restores everything
     * this build knows instead of falling back to the default. Older positional values (v3, v2,
     * the first three-number form) are still read by [decode].
     */
    fun encode(): String =
        STORED_V4 +
            StoredJson.encodeToString(
                StoredGlassMaterial.serializer(),
                StoredGlassMaterial(
                    preset = preset.id,
                    tone = tone,
                    opacity = opacity,
                    scrim = scrim,
                    tintRgb = tintRgb,
                    blur = blur,
                    saturation = saturation,
                    refraction = refraction,
                    rim = rim,
                    rimWidth = rimWidth,
                    prism = prism,
                    pearl = pearl,
                    fluted = fluted,
                    fluteWidth = fluteWidth,
                    ink = ink.name,
                ),
            )

    companion object {
        /** WCAG AA for body text. */
        const val MIN_TEXT_CONTRAST = 4.5f
        private const val STORED_V4 = "v4"
        private val StoredJson =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }

        val PreviousLight = GlassMaterial(0f, 0.52f, 0.16f)

        fun defaults(dark: Boolean): GlassMaterial =
            if (dark) GlassMaterial(0f, 0.72f, 0.28f) else GlassMaterial(1f, 0.82f, 0.30f)

        fun decode(
            stored: String?,
            dark: Boolean,
        ): GlassMaterial {
            if (stored != null && stored.startsWith(STORED_V4 + "{")) return decodeNamed(stored, dark)
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

        private fun decodeNamed(
            stored: String,
            dark: Boolean,
        ): GlassMaterial {
            val value =
                try {
                    StoredJson.decodeFromString(StoredGlassMaterial.serializer(), stored.removePrefix(STORED_V4))
                } catch (_: SerializationException) {
                    return defaults(dark)
                } catch (_: IllegalArgumentException) {
                    return defaults(dark)
                }
            val base = GlassMaterialPreset.fromId(value.preset).material(dark)
            return base
                .copy(
                    tone = value.tone ?: base.tone,
                    opacity = value.opacity ?: base.opacity,
                    scrim = value.scrim ?: base.scrim,
                    tintRgb = value.tintRgb,
                    blur = value.blur ?: base.blur,
                    saturation = value.saturation ?: base.saturation,
                    refraction = value.refraction ?: base.refraction,
                    rim = value.rim ?: base.rim,
                    rimWidth = value.rimWidth ?: base.rimWidth,
                    prism = value.prism ?: base.prism,
                    pearl = value.pearl ?: base.pearl,
                    fluted = value.fluted ?: base.fluted,
                    fluteWidth = value.fluteWidth ?: base.fluteWidth,
                    ink = GlassInk.entries.firstOrNull { it.name == value.ink } ?: base.ink,
                ).normalized(dark)
        }
    }
}

/** Storage form of a recipe. Every knob is optional: what is absent comes from the preset. */
@Serializable
internal data class StoredGlassMaterial(
    val preset: String = "default",
    val tone: Float? = null,
    val opacity: Float? = null,
    val scrim: Float? = null,
    val tintRgb: Int? = null,
    val blur: Float? = null,
    val saturation: Float? = null,
    val refraction: Float? = null,
    val rim: Float? = null,
    val rimWidth: Float? = null,
    val prism: Float? = null,
    val pearl: Float? = null,
    val fluted: Float? = null,
    val fluteWidth: Float? = null,
    val ink: String? = null,
)

@Immutable
data class GlassMaterials(
    val light: GlassMaterial = GlassMaterial.defaults(false),
    val dark: GlassMaterial = GlassMaterial.defaults(true),
) {
    fun forTheme(dark: Boolean): GlassMaterial = if (dark) this.dark else light
}

val LocalGlassMaterials = compositionLocalOf { GlassMaterials() }
