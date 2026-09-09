package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.model.ServerLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePreferencesTest {
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
            setSplashVariant(SplashAnimation.Two)
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
        assertEquals(SplashAnimation.Two, restored.splashVariant.value)
        assertEquals(ServerLayout.List, restored.serverLayout.value)
    }
}
