package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.PlaybackQuality
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackPreferencesTest {
    @Test
    fun smart_source_defaults_on_and_persists_off() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)

        assertTrue(first.smartCrossServerSource.value)
        first.setSmartCrossServerSource(false)

        assertFalse(PlaybackPreferences(settings).smartCrossServerSource.value)
    }

    @Test
    fun network_quality_defaults_and_server_memory_persist() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)

        assertEquals(PlaybackQuality.Original, first.wifiQualityCap.value)
        assertEquals(PlaybackQuality.Hd, first.cellularQualityCap.value)
        assertTrue(first.autoQualityDowngrade.value)
        assertFalse(first.qualityLocked.value)
        assertNull(first.rememberedQuality("server-a"))

        first.setWifiQualityCap(PlaybackQuality.UltraHd)
        first.setCellularQualityCap(PlaybackQuality.Sd)
        first.setAutoQualityDowngrade(false)
        first.setQualityLocked(true)
        first.rememberQuality("server-a", PlaybackQuality.FullHd)

        val restored = PlaybackPreferences(settings)
        assertEquals(PlaybackQuality.UltraHd, restored.wifiQualityCap.value)
        assertEquals(PlaybackQuality.Sd, restored.cellularQualityCap.value)
        assertFalse(restored.autoQualityDowngrade.value)
        assertTrue(restored.qualityLocked.value)
        assertEquals(PlaybackQuality.FullHd, restored.rememberedQuality("server-a"))
        assertNull(restored.rememberedQuality("server-b"))
    }

    @Test
    fun playback_output_preferences_default_off_and_persist() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)

        assertEquals(PlaybackFrameRateMatch.Disabled, first.frameRateMatch.value)
        assertEquals(PlaybackAudioPassthrough.Disabled, first.audioPassthrough.value)

        first.setFrameRateMatch(PlaybackFrameRateMatch.Always)
        first.setAudioPassthrough(PlaybackAudioPassthrough.Compatible)

        val restored = PlaybackPreferences(settings)
        assertEquals(PlaybackFrameRateMatch.Always, restored.frameRateMatch.value)
        assertEquals(PlaybackAudioPassthrough.Compatible, restored.audioPassthrough.value)
    }

    @Test
    fun series_playback_is_server_scoped_persistent_and_normalized() {
        val settings = MapSettings()
        val preferences = PlaybackPreferences(settings)
        preferences.updateSeriesPlayback("server-a", "series-1") {
            SeriesPlaybackPreference(
                audio = RememberedPlaybackTrack(" zho ", " 国语 ", " eac3 "),
                primarySubtitlesOff = false,
                primarySubtitle = RememberedPlaybackTrack("zho", "简体", "srt"),
                secondarySubtitle = RememberedPlaybackTrack("eng", "English", "ass"),
                subtitleOffsetMs = 90_000L,
                subtitleScale = 4f,
                subtitleBrightness = 0.1f,
                speed = 8f,
                aspectMode = "not-a-mode",
            )
        }

        val restored = PlaybackPreferences(settings).rememberedSeriesPlayback("server-a", "series-1")
        assertEquals(RememberedPlaybackTrack("zho", "国语", "eac3"), restored?.audio)
        assertEquals(60_000L, restored?.subtitleOffsetMs)
        assertEquals(1.8f, restored?.subtitleScale)
        assertEquals(0.35f, restored?.subtitleBrightness)
        assertEquals(4f, restored?.speed)
        assertEquals("Fit", restored?.aspectMode)
        assertNull(PlaybackPreferences(settings).rememberedSeriesPlayback("server-b", "series-1"))
        assertNull(PlaybackPreferences(settings).rememberedSeriesPlayback("server-a", ""))
    }

    @Test
    fun series_playback_memory_is_bounded_and_evicts_the_oldest_choice() {
        val preferences = PlaybackPreferences(MapSettings())
        repeat(MAX_SERIES_PLAYBACK_PREFERENCES + 1) { index ->
            preferences.updateSeriesPlayback("server", "series-$index") { current ->
                current.copy(speed = 1f + index / 100f)
            }
        }

        assertEquals(MAX_SERIES_PLAYBACK_PREFERENCES, preferences.rememberedSeriesPlaybackCount())
        assertNull(preferences.rememberedSeriesPlayback("server", "series-0"))
        assertEquals(
            1f + MAX_SERIES_PLAYBACK_PREFERENCES / 100f,
            preferences
                .rememberedSeriesPlayback("server", "series-$MAX_SERIES_PLAYBACK_PREFERENCES")
                ?.speed,
        )
    }
}
