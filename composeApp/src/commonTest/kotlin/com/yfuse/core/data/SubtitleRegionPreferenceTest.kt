package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubtitleRegionPreferenceTest {
    @Test
    fun existing_preferences_without_a_region_enable_black_bars_and_keep_the_old_position() {
        val migrated =
            Json.decodeFromString<SeriesPlaybackPreference>(
                """{"subtitlePosition":0.76,"subtitleScale":1.5}""",
            )
        assertTrue(migrated.subtitlePreferBlackBars)
        assertEquals(0.76f, migrated.subtitlePosition)
        assertEquals(1.5f, migrated.subtitleScale)
    }

    @Test
    fun region_changes_survive_restart_without_resetting_other_controls() {
        val settings = MapSettings()
        val preferences = PlaybackPreferences(settings)
        preferences.updateSeriesPlayback("server", "series") {
            it.copy(
                subtitlePreferBlackBars = false,
                subtitlePosition = 0.88f,
                secondarySubtitleScale = 1.5f,
                subtitleOffsetMs = 600L,
            )
        }
        val stored = PlaybackPreferences(settings).rememberedSeriesPlayback("server", "series")!!
        assertFalse(stored.subtitlePreferBlackBars)
        assertEquals(0.88f, stored.subtitlePosition)
        assertEquals(1.5f, stored.secondarySubtitleScale)
        assertEquals(600L, stored.subtitleOffsetMs)
        preferences.updateSeriesPlayback("server", "series") { it.copy(subtitlePreferBlackBars = true) }
        assertTrue(PlaybackPreferences(settings).rememberedSeriesPlayback("server", "series")!!.subtitlePreferBlackBars)
    }
}
