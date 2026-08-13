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
}
