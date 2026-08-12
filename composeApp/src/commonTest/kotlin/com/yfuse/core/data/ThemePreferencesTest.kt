package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.designsystem.AccentColor
import com.yfuse.core.designsystem.SplashAnimation
import com.yfuse.core.designsystem.ThemeMode
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThemePreferencesTest {

    @Test
    fun original_quality_survives_recreation() {
        val settings = MapSettings()
        ThemePreferences(settings).setQuality(PlaybackQuality.Original)

        assertEquals(PlaybackQuality.Original, ThemePreferences(settings).quality.value)
    }

    @Test
    fun player_preferences_survive_recreation() {
        val settings = MapSettings()
        ThemePreferences(settings).apply {
            setEngine(PlayerEngine.Mpv)
            setDecoder(DecoderMode.Software)
            setAutoNext(false)
            setQuality(PlaybackQuality.FullHd)
            setMode(ThemeMode.System)
            setAccent(AccentColor.Teal)
            setReduceTransparency(true)
            setLargeText(true)
            setReduceMotion(true)
            setSplashAnimation(false)
            setSplashVariant(SplashAnimation.Two)
        }

        val restored = ThemePreferences(settings)

        assertEquals(PlayerEngine.Mpv, restored.engine.value)
        assertEquals(DecoderMode.Software, restored.decoder.value)
        assertFalse(restored.autoNext.value)
        assertEquals(PlaybackQuality.FullHd, restored.quality.value)
        assertEquals(ThemeMode.System, restored.mode.value)
        assertEquals(AccentColor.Teal, restored.accent.value)
        assertTrue(restored.reduceTransparency.value)
        assertTrue(restored.largeText.value)
        assertTrue(restored.reduceMotion.value)
        assertFalse(restored.splashAnimation.value)
        assertEquals(SplashAnimation.Two, restored.splashVariant.value)
    }
}
