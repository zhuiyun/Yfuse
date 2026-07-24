package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ThemePreferencesTest {

    @Test
    fun player_preferences_survive_recreation() {
        val settings = MapSettings()
        ThemePreferences(settings).apply {
            setDecoder(DecoderMode.Software)
            setAutoNext(false)
            setQuality(PlaybackQuality.FullHd)
        }

        val restored = ThemePreferences(settings)

        assertEquals(DecoderMode.Software, restored.decoder.value)
        assertFalse(restored.autoNext.value)
        assertEquals(PlaybackQuality.FullHd, restored.quality.value)
    }
}
