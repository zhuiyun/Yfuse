package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.designsystem.DialogAnimation
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
    fun retired_variant_keys_are_scrubbed_and_retired_dialog_names_fall_back() {
        val settings = MapSettings()
        settings.putString("appearance.particleStyle", "Flow")
        settings.putString("appearance.splashVariant.v2", "CloudWell")
        settings.putBoolean("appearance.dialogAnimationLab", true)
        settings.putString("appearance.dialogAnimation", "Hologram")
        val prefs = ThemePreferences(settings)
        assertNull(settings.getStringOrNull("appearance.particleStyle"))
        assertNull(settings.getStringOrNull("appearance.splashVariant.v2"))
        assertFalse(settings.hasKey("appearance.dialogAnimationLab"))
        assertEquals(DialogAnimation.Lift, prefs.dialogAnimation.value)
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
