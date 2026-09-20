package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassMaterialTest {
    @Test
    fun sliders_can_reconstruct_every_recipe_starting_from_classic_glass() {
        for (dark in listOf(false, true)) {
            for (preset in GlassMaterialPreset.selectable) {
                val target = preset.material(dark)
                var adjusted = GlassMaterial.defaults(dark)
                GlassAdjustment.entries.forEach { control ->
                    adjusted = control.update(adjusted, dark, control.value(target, dark))
                }
                adjusted = adjusted.copy(ink = target.ink)
                assertEquals(GlassMaterialPreset.Default, adjusted.preset)
                assertEquals(target.tint(dark).toArgb(), adjusted.tint(dark).toArgb(), preset.id)
                GlassAdjustment.entries.forEach { control ->
                    assertEquals(
                        control.value(target, dark),
                        control.value(adjusted, dark),
                        0.0001f,
                        "${preset.id}: ${control.label}",
                    )
                }
                val palette = if (dark) DarkPalette else LightPalette
                assertEquals(target.contentPalette(palette), adjusted.contentPalette(palette))
                assertEquals(adjusted, GlassMaterial.decode(adjusted.encode(), dark))
            }
        }
    }

    @Test
    fun mixed_effects_survive_restart_and_invalid_parameters_are_bounded() {
        val mixed =
            GlassMaterialPreset.SoftMist.material(false).copy(
                tintRgb = 0x123456,
                blur = 37f,
                saturation = 1.34f,
                refraction = 6.2f,
                rim = 0.8f,
                rimWidth = 1.7f,
                prism = 0.45f,
                pearl = 0.38f,
                fluted = 0.72f,
                fluteWidth = 23f,
                ink = GlassInk.Light,
            )
        assertEquals(mixed, GlassMaterial.decode(mixed.encode(), false))
        val damaged =
            mixed
                .copy(
                    blur = Float.NaN,
                    saturation = 5f,
                    refraction = -1f,
                    rim = Float.POSITIVE_INFINITY,
                    rimWidth = -4f,
                    prism = 4f,
                    pearl = -2f,
                    fluted = Float.NaN,
                    fluteWidth = 0f,
                ).normalized(false)
        assertEquals(30f, damaged.blur)
        assertEquals(2f, damaged.saturation)
        assertEquals(0f, damaged.refraction)
        assertEquals(0.2f, damaged.rim)
        assertEquals(0.3f, damaged.rimWidth)
        assertEquals(1f, damaged.prism)
        assertEquals(0f, damaged.pearl)
        assertEquals(0f, damaged.fluted)
        assertEquals(4f, damaged.fluteWidth)
    }

    @Test
    fun v2_presets_migrate_to_editable_parameters_and_slider_steps_respect_limits() {
        assertEquals(
            GlassMaterialPreset.Prism.material(false).copy(tone = 0.5f, opacity = 0.4f, scrim = 0.3f),
            GlassMaterial.decode("v2,prism,0.5,0.4,0.3", false),
        )
        val base = GlassMaterial.defaults(false)
        for (control in GlassAdjustment.entries) {
            assertEquals(base, control.update(base, false, Float.NaN))
            assertEquals(control.min, control.value(control.update(base, false, -999f), false), 0.0001f)
            assertEquals(control.max, control.value(control.update(base, false, 999f), false), 0.0001f)
        }
    }

    @Test
    fun legacy_values_and_unknown_presets_keep_user_adjustments() {
        assertEquals(GlassMaterial(0.3f, 0.4f, 0.5f), GlassMaterial.decode("0.3,0.4,0.5", false))
        assertEquals(GlassMaterial(0.3f, 0.4f, 0.5f), GlassMaterial.decode("v2,future,0.3,0.4,0.5", false))
        assertEquals(GlassMaterial.defaults(true), GlassMaterial.decode("v3,smoke,0,1,0", true))
        assertEquals(GlassMaterial.defaults(false), GlassMaterial.decode("v2,prism,broken,0,0", false))
    }

    @Test
    fun presets_round_trip_with_adjustments_and_sanitize_using_their_own_defaults() {
        assertEquals(
            listOf("01", "02", "03", "04", "05", "06", "S1", "S2", "S3"),
            GlassMaterialPreset.selectable.map {
                it.number
            },
        )
        for (dark in listOf(false, true)) {
            for (preset in GlassMaterialPreset.selectable) {
                val modified = preset.material(dark).copy(tone = 0.36f, opacity = 0.57f, scrim = 0.21f)
                assertEquals(modified, GlassMaterial.decode(modified.encode(), dark))
                assertEquals(
                    preset.material(dark).copy(opacity = 1f, scrim = 0f),
                    GlassMaterial(Float.NaN, 2f, -1f, preset).normalized(dark),
                )
            }
        }
    }

    @Test
    fun smooth_mist_diffuses_progressively_without_refraction_and_smoke_uses_light_ink() {
        val mist = listOf(GlassMaterialPreset.SoftMist, GlassMaterialPreset.DenseMist, GlassMaterialPreset.MilkyMist)
        assertTrue(
            mist.zipWithNext().all { (first, second) ->
                first.blur < second.blur &&
                    first.material(false).opacity < second.material(false).opacity
            },
        )
        assertTrue(mist.all { it.refraction == 0f })
        val smoke = GlassMaterialPreset.Smoke.material(false).contentPalette(LightPalette)
        assertEquals(DarkPalette.text, smoke.text)
        assertEquals(false, smoke.isDark)
        assertEquals(LightPalette, GlassMaterialPreset.SoftMist.material(false).contentPalette(LightPalette))
    }

    @Test
    fun defaults_preserve_shipped_material_and_previous_preset_restores_original_light_glass() {
        assertEquals(LightPalette.dialogTint, GlassMaterial.defaults(false).tint(false))
        assertEquals(DarkPalette.dialogTint, GlassMaterial.defaults(true).tint(true))
        assertEquals(LightPalette.scrim.alpha, GlassMaterial.defaults(false).scrim, 0.003f)
        assertEquals(DarkPalette.scrim.alpha, GlassMaterial.defaults(true).scrim, 0.003f)
        assertEquals(Color(0xFF878F9B).copy(alpha = 0.52f), GlassMaterial.PreviousLight.tint(false))
        assertEquals(0.16f, GlassMaterial.PreviousLight.scrim)
    }

    @Test
    fun everyPresetKeepsTheLightScrimThatDialogsWereRaisedTo() {
        for (preset in GlassMaterialPreset.entries) {
            assertEquals(0.30f, preset.material(false).scrim, preset.id)
            assertEquals(0.28f, preset.material(true).scrim, preset.id)
        }
    }

    @Test
    fun shippedDefaultsReadAtBodyContrastWithTheThemeInk() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            val material = GlassMaterial.defaults(palette.isDark)
            assertEquals(GlassInk.Theme, material.resolvedInk(palette))
            assertTrue(material.textContrast(palette) >= GlassMaterial.MIN_TEXT_CONTRAST, "dark=${palette.isDark}")
        }
    }

    @Test
    fun themeInkYieldsOnlyWhenItCannotBeReadAndTheOtherInkReadsBetter() {
        val darkTintInLight = GlassMaterial.defaults(false).copy(tintRgb = 0x101820, opacity = 0.92f)
        assertEquals(GlassInk.Light, darkTintInLight.resolvedInk(LightPalette))
        assertEquals(DarkPalette.text, darkTintInLight.contentPalette(LightPalette).text)
        assertEquals(false, darkTintInLight.contentPalette(LightPalette).isDark)

        val paleTintInDark = GlassMaterial.defaults(true).copy(tintRgb = 0xF2F4F8, opacity = 0.95f)
        assertEquals(GlassInk.Dark, paleTintInDark.resolvedInk(DarkPalette))

        // An explicit choice is never overridden, readable or not.
        assertEquals(GlassInk.Dark, darkTintInLight.copy(ink = GlassInk.Dark).resolvedInk(LightPalette))
    }

    @Test
    fun solidFallbackKeepsEachPresetsOwnColour() {
        val fills = GlassMaterialPreset.selectable.map { it.material(false).panelBody(dark = false, opaque = true) }
        assertTrue(fills.all { it.alpha == 1f })
        assertTrue(fills.toSet().size > GlassMaterialPreset.selectable.size / 2, "presets collapsed: $fills")
        // The shipped default keeps the exact solid it always had.
        assertEquals(LightPalette.background, GlassMaterial.defaults(false).panelBody(dark = false, opaque = true))
        assertEquals(1f, GlassMaterial.defaults(true).panelBody(dark = true, opaque = true).alpha)
    }

    @Test
    fun namedStorageSurvivesUnknownAndMissingFields() {
        val base = GlassMaterialPreset.Pearl.material(true)
        assertEquals(
            base.copy(opacity = 0.5f),
            GlassMaterial.decode("v4{\"preset\":\"pearl\",\"opacity\":0.5,\"futureKnob\":3}", true),
        )
        assertEquals(GlassMaterial.defaults(false), GlassMaterial.decode("v4{broken", false))
        assertTrue(base.encode().startsWith("v4{"))
    }
}
