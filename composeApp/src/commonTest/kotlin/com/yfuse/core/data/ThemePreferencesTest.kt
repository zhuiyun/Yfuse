package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.designsystem.DialogAnimation
import com.yfuse.core.designsystem.GlassInk
import com.yfuse.core.designsystem.GlassMaterial
import com.yfuse.core.designsystem.GlassMaterialPreset
import com.yfuse.core.designsystem.GlassMaterials
import com.yfuse.core.designsystem.LoadingAnimation
import com.yfuse.core.designsystem.ParticleLight
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.ServerLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ThemePreferencesTest {
    @Test
    fun all_custom_material_parameters_survive_restart_and_do_not_modify_the_other_theme() {
        val settings = MapSettings()
        val prefs = ThemePreferences(settings)
        val light =
            GlassMaterialPreset.Prism.material(false).copy(
                tintRgb = 0xBBDDEE,
                blur = 48f,
                saturation = 0.84f,
                refraction = 0f,
                rim = 0.17f,
                rimWidth = 0.4f,
                prism = 0.23f,
                pearl = 0.31f,
                fluted = 0.43f,
                fluteWidth = 19f,
                ink = GlassInk.Dark,
            )
        val dark = light.copy(tintRgb = 0x243746, ink = GlassInk.Light)
        prefs.setGlassMaterial(false, light)
        prefs.setGlassMaterial(true, dark)
        val restored = ThemePreferences(settings)
        assertEquals(light, restored.glassMaterials.value.light)
        assertEquals(dark, restored.glassMaterials.value.dark)
        restored.setGlassMaterial(false, GlassMaterialPreset.SoftMist.material(false))
        val reset = ThemePreferences(settings).glassMaterials.value
        assertEquals(0f, reset.light.prism)
        assertEquals(0f, reset.light.pearl)
        assertEquals(0f, reset.light.fluted)
        assertEquals(dark, reset.dark)
    }

    @Test
    fun all_material_presets_and_fine_tuning_persist_independently_per_theme() {
        val settings = MapSettings()
        val preferences = ThemePreferences(settings)
        for (dark in listOf(false, true)) {
            for (preset in GlassMaterialPreset.selectable) {
                val other = preferences.glassMaterials.value.forTheme(!dark)
                val modified = preset.material(dark).copy(tone = 0.43f, opacity = 0.64f, scrim = 0.19f)
                preferences.setGlassMaterial(dark, modified)
                val restored = ThemePreferences(settings).glassMaterials.value
                assertEquals(modified, restored.forTheme(dark))
                assertEquals(other, restored.forTheme(!dark))
                preferences.setGlassMaterial(dark, GlassMaterial.defaults(dark))
                assertEquals(
                    GlassMaterialPreset.Default,
                    ThemePreferences(settings)
                        .glassMaterials.value
                        .forTheme(dark)
                        .preset,
                )
            }
        }
    }

    @Test
    fun glass_parameters_survive_restart_and_reset_each_theme_independently() {
        val settings = MapSettings()
        val preferences = ThemePreferences(settings)
        assertEquals(GlassMaterials(), preferences.glassMaterials.value)
        val dark = GlassMaterial(0.4f, 0.6f, 0.2f)
        preferences.setGlassMaterial(false, GlassMaterial.PreviousLight)
        preferences.setGlassMaterial(true, dark)
        val restored = ThemePreferences(settings)
        assertEquals(GlassMaterial.PreviousLight, restored.glassMaterials.value.light)
        assertEquals(dark, restored.glassMaterials.value.dark)
        restored.setGlassMaterial(false, GlassMaterial.defaults(false))
        val reset = ThemePreferences(settings)
        assertEquals(GlassMaterial.defaults(false), reset.glassMaterials.value.light)
        assertEquals(dark, reset.glassMaterials.value.dark)
        assertFalse(reset.reduceTransparency.value)
    }

    @Test
    fun corrupt_glass_parameters_are_normalized_before_rendering_or_saving() {
        val settings = MapSettings()
        settings.putString("appearance.glassMaterial.light", "NaN,2,-1")
        settings.putString("appearance.glassMaterial.dark", "broken")
        val preferences = ThemePreferences(settings)
        assertEquals(GlassMaterial(1f, 1f, 0f), preferences.glassMaterials.value.light)
        assertEquals(GlassMaterial.defaults(true), preferences.glassMaterials.value.dark)
        preferences.setGlassMaterial(true, GlassMaterial(Float.POSITIVE_INFINITY, -10f, 10f))
        assertEquals(GlassMaterial(0f, 0f, 1f), ThemePreferences(settings).glassMaterials.value.dark)
    }

    @Test
    fun all_loading_choices_survive_restart_without_changing_accessibility() {
        val settings = MapSettings()
        val preferences = ThemePreferences(settings)
        assertEquals(LoadingAnimation.Orbit, preferences.loadingAnimation.value)
        preferences.setReduceMotion(true)
        LoadingAnimation.entries.forEach { animation ->
            preferences.setLoadingAnimation(animation)
            assertEquals(animation, preferences.loadingAnimation.value)
            val restored = ThemePreferences(settings)
            assertEquals(animation, restored.loadingAnimation.value)
            assertTrue(restored.reduceMotion.value)
        }
    }

    @Test
    fun unknown_loading_choice_falls_back_and_can_be_replaced() {
        val settings = MapSettings()
        settings.putString("appearance.loadingAnimation", "UnknownFutureStyle")
        val preferences = ThemePreferences(settings)
        assertEquals(LoadingAnimation.Orbit, preferences.loadingAnimation.value)
        preferences.setLoadingAnimation(LoadingAnimation.BeadRelay)
        assertEquals(LoadingAnimation.BeadRelay, ThemePreferences(settings).loadingAnimation.value)
    }

    @Test
    fun library_carousel_defaults_to_original_layout_and_remembers_explicit_choice() {
        val settings = MapSettings()
        // The former compact-mode default must not silently hide the restored carousel.
        settings.putBoolean("appearance.compactLibrary", true)
        val original = ThemePreferences(settings)
        assertTrue(original.libraryCarousel.value)
        original.setLibraryCarousel(false)
        val restored = ThemePreferences(settings)
        assertFalse(restored.libraryCarousel.value)
        assertTrue(restored.splashAnimation.value)
        restored.setLibraryCarousel(true)
        assertTrue(ThemePreferences(settings).libraryCarousel.value)
    }

    @Test
    fun pulse_sweep_can_be_disabled_and_restored_independently_of_reduced_motion() {
        val settings = MapSettings()
        val original = ThemePreferences(settings)
        assertTrue(original.pulseSweep.value)
        original.setPulseSweep(false)
        val restored = ThemePreferences(settings)
        assertFalse(restored.pulseSweep.value)
        assertFalse(restored.reduceMotion.value)
        restored.setReduceMotion(true)
        restored.setPulseSweep(true)
        val enabledAgain = ThemePreferences(settings)
        assertTrue(enabledAgain.pulseSweep.value)
        assertTrue(enabledAgain.reduceMotion.value)
    }

    @Test
    fun theme_defaults_to_dark_and_particle_light_keeps_its_level() {
        val settings = MapSettings()
        val original = ThemePreferences(settings)
        assertEquals(ThemeMode.Dark, original.mode.value)
        assertEquals(ParticleLight.Gentle, original.particleLight.value)
        original.setParticleLight(ParticleLight.Off)
        assertEquals(ParticleLight.Off, ThemePreferences(settings).particleLight.value)
    }

    @Test
    fun retired_variant_keys_are_scrubbed_and_restored_dialog_names_are_preserved() {
        val settings = MapSettings()
        settings.putString("appearance.particleStyle", "Flow")
        settings.putString("appearance.splashVariant.v2", "CloudWell")
        settings.putBoolean("appearance.dialogAnimationLab", true)
        settings.putString("appearance.dialogAnimation", "Hologram")
        val prefs = ThemePreferences(settings)
        assertNull(settings.getStringOrNull("appearance.particleStyle"))
        assertNull(settings.getStringOrNull("appearance.splashVariant.v2"))
        assertFalse(settings.hasKey("appearance.dialogAnimationLab"))
        assertEquals(DialogAnimation.Hologram, prefs.dialogAnimation.value)
        prefs.setDialogAnimation(DialogAnimation.Cascade)
        assertEquals(DialogAnimation.Cascade, ThemePreferences(settings).dialogAnimation.value)
    }

    @Test
    fun player_preferences_survive_recreation() {
        val settings = MapSettings()
        ThemePreferences(settings).apply {
            setEngine(PlayerEngine.Mpv)
            setDecoder(DecoderMode.Software)
            setAutoNext(false)
            setMode(ThemeMode.System)
            setReduceTransparency(true)
            setLargeText(true)
            setReduceMotion(true)
            setSplashAnimation(false)
            setServerLayout(ServerLayout.List)
        }

        val restored = ThemePreferences(settings)

        assertEquals(PlayerEngine.Mpv, restored.engine.value)
        assertEquals(DecoderMode.Software, restored.decoder.value)
        assertFalse(restored.autoNext.value)
        assertEquals(ThemeMode.System, restored.mode.value)
        assertTrue(restored.reduceTransparency.value)
        assertTrue(restored.largeText.value)
        assertTrue(restored.reduceMotion.value)
        assertFalse(restored.splashAnimation.value)
        assertEquals(ServerLayout.List, restored.serverLayout.value)
    }
}
