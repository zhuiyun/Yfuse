package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SenPlayerPicturePreferencesTest {
    @Test
    fun both_enhancements_are_opt_in_and_do_not_change_anime4k() {
        val store = MapSettings()
        val preferences = PlaybackPreferences(store)
        preferences.setAnime4KMode(Anime4KMode.Quality)
        assertEquals(SenPlayerPictureSettings(), preferences.senPlayerPicture.state.value)
        preferences.senPlayerPicture.setUpscaling(SenPlayerEnhancementLevel.Low)
        assertEquals(Anime4KMode.Quality, preferences.anime4KMode.value)
        assertSame(preferences.senPlayerPicture, preferences.senPlayerPicture)
    }

    @Test
    fun independent_levels_survive_restart_including_explicit_off() {
        val store = MapSettings()
        val preferences = SenPlayerPicturePreferences(store)
        preferences.setUpscaling(SenPlayerEnhancementLevel.High)
        preferences.setSdrToHdr(SenPlayerEnhancementLevel.Medium)
        val expected = SenPlayerPictureSettings(SenPlayerEnhancementLevel.High, SenPlayerEnhancementLevel.Medium)
        assertEquals(expected, preferences.state.value)
        val restored = SenPlayerPicturePreferences(store)
        assertEquals(expected, restored.state.value)
        restored.setUpscaling(SenPlayerEnhancementLevel.Off)
        assertEquals(
            expected.copy(upscaling = SenPlayerEnhancementLevel.Off),
            SenPlayerPicturePreferences(store).state.value,
        )
        restored.setSdrToHdr(SenPlayerEnhancementLevel.Off)
        assertEquals(SenPlayerPictureSettings(), SenPlayerPicturePreferences(store).state.value)
    }

    @Test
    fun unknown_or_invalid_saved_values_do_not_enable_enhancement() {
        val store = MapSettings()
        store.putString("playback.senplayer.upscaling", "FutureUltra")
        store.putInt("playback.senplayer.sdr_to_hdr", 100)
        assertEquals(SenPlayerPictureSettings(), SenPlayerPicturePreferences(store).state.value)
    }
}
