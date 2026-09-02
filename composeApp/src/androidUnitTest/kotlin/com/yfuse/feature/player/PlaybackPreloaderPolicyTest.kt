package com.yfuse.feature.player

import com.yfuse.core.data.PlaybackNetworkClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackPreloaderPolicyTest {
    @Test
    fun `native-only runtime does not start an unused Media3 source download`() {
        assertFalse(
            shouldWarmPlaybackCache(
                networkClass = PlaybackNetworkClass.Unmetered,
                powerSaveMode = false,
                nativeOnlyRuntime = true,
            ),
        )
    }

    @Test
    fun warms_only_unmetered_networks_outside_battery_saver() {
        assertTrue(shouldWarmPlaybackCache(PlaybackNetworkClass.Unmetered, powerSaveMode = false))
        assertFalse(shouldWarmPlaybackCache(PlaybackNetworkClass.Metered, powerSaveMode = false))
        assertFalse(shouldWarmPlaybackCache(PlaybackNetworkClass.Unknown, powerSaveMode = false))
        assertFalse(shouldWarmPlaybackCache(PlaybackNetworkClass.Unmetered, powerSaveMode = true))
    }

    @Test
    fun startup_prefix_follows_bitrate_with_safe_bounds() {
        val mebibyte = 1024L * 1024L
        assertEquals(8L * mebibyte, playbackPreloadBytes(null))
        assertEquals(4L * mebibyte, playbackPreloadBytes(1_000_000))
        assertEquals(8L * mebibyte, playbackPreloadBytes(8 * 1024 * 1024))
        assertEquals(16L * mebibyte, playbackPreloadBytes(100_000_000))
    }
}
