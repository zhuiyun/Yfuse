package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection
import com.yfuse.core.playback.PlaybackFailureRecord
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackPerformanceRecord
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
    fun ycore_optimization_mode_defaults_balanced_and_persists() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)

        assertEquals(PlaybackOptimizationMode.Balanced, first.optimizationMode.value)
        first.setOptimizationMode(PlaybackOptimizationMode.PowerSaver)

        assertEquals(
            PlaybackOptimizationMode.PowerSaver,
            PlaybackPreferences(settings).optimizationMode.value,
        )
    }

    @Test
    fun ycore_engine_selection_defaults_to_auto_and_persists_a_lock() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)

        assertEquals(PlaybackEngineSelection.Auto, first.engineSelection.value)
        first.setEngineSelection(PlaybackEngineSelection.LockMpv)

        assertEquals(
            PlaybackEngineSelection.LockMpv,
            PlaybackPreferences(settings).engineSelection.value,
        )
    }

    @Test
    fun ycore2_defaults_on_and_persists_opt_out() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)

        assertTrue(first.core2TrialEnabled.value)
        first.setCore2TrialEnabled(false)

        assertFalse(PlaybackPreferences(settings).core2TrialEnabled.value)
    }

    @Test
    fun ycore_device_quirks_are_bounded_and_persist_without_media_identity() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)
        val records =
            List(MAX_PLAYBACK_FAILURE_RECORDS + 1) { index ->
                PlaybackFailureRecord(
                    signature = "MKV|HEVC|$index",
                    engine = PlayerEngine.Exo,
                    count = 2,
                    lastFailureEpochMs = 1_000L + index,
                )
            }

        first.storePlaybackFailureRecords(records)
        val restored = PlaybackPreferences(settings).playbackFailureRecords()

        assertEquals(MAX_PLAYBACK_FAILURE_RECORDS, restored.size)
        assertEquals("MKV|HEVC|1", restored.first().signature)
        assertEquals("MKV|HEVC|$MAX_PLAYBACK_FAILURE_RECORDS", restored.last().signature)
        assertTrue(restored.none { it.signature.contains("http", ignoreCase = true) })
    }

    @Test
    fun ycore_performance_baselines_are_bounded_and_private() {
        val settings = MapSettings()
        val first = PlaybackPreferences(settings)
        val records =
            List(MAX_PLAYBACK_PERFORMANCE_RECORDS + 1) { index ->
                PlaybackPerformanceRecord(
                    signature = "MKV|HEVC|$index",
                    engine = PlayerEngine.Exo,
                    sessions = 3,
                    averageStartupMs = 1_200L,
                    averageRebufferEventsPerMinute = 0.5f,
                    averageDroppedFramesPerMinute = 1f,
                    lastObservedEpochMs = 1_000L + index,
                )
            }

        first.storePlaybackPerformanceRecords(records)
        val restored = PlaybackPreferences(settings).playbackPerformanceRecords()

        assertEquals(MAX_PLAYBACK_PERFORMANCE_RECORDS, restored.size)
        assertEquals("MKV|HEVC|1", restored.first().signature)
        assertTrue(restored.none { it.signature.contains("http", ignoreCase = true) })
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
                audioDelayMs = 25_000L,
                subtitleOffsetMs = 90_000L,
                subtitleScale = 4f,
                subtitleBrightness = 0.1f,
                subtitlePosition = 0.1f,
                subtitleStylePreset = "Unknown",
                speed = 8f,
                aspectMode = "not-a-mode",
            )
        }

        val restored = PlaybackPreferences(settings).rememberedSeriesPlayback("server-a", "series-1")
        assertEquals(RememberedPlaybackTrack("zho", "国语", "eac3"), restored?.audio)
        assertEquals(10_000L, restored?.audioDelayMs)
        assertEquals(60_000L, restored?.subtitleOffsetMs)
        assertEquals(1.8f, restored?.subtitleScale)
        assertEquals(0.35f, restored?.subtitleBrightness)
        assertEquals(0.60f, restored?.subtitlePosition)
        assertEquals("Standard", restored?.subtitleStylePreset)
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
