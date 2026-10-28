package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.mdkBufferProfile
import com.yfuse.core.playback.mpvBufferProfile
import com.yfuse.core.playback.playbackBufferProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackBufferMemoryBudgetTest {
    @Test
    fun large_heap_gives_all_three_engines_a_long_high_bitrate_reserve() {
        val heap = 2048L * MIB
        val budget = playbackMemoryBudgetBytes(heap, lowRam = false)
        val mode = PlaybackOptimizationMode.Quality
        val bitrate = 80_000_000L
        val exoBytes = minOf(playbackBufferProfile(mode).targetBufferBytes.toLong(), budget)
        val mpv = mpvBufferProfile(mode, budget)
        val mdk = mdkBufferProfile(mode, bitrate, budget, remote = true)
        assertEquals(1024L * MIB, budget)
        assertTrue(exoBytes * 8L / bitrate >= 100L)
        assertTrue(mpv.forwardBytes.toLong() * 8L / bitrate >= 100L)
        assertTrue(mdk.maximumMs >= 100_000)
        assertTrue(mpv.forwardBytes.toLong() + mpv.backBytes <= budget)
        assertTrue(mdk.maximumMs.toLong() * bitrate / 8_000L <= budget)
    }

    @Test
    fun ordinary_devices_retain_half_the_heap_and_low_ram_devices_keep_the_small_budget() {
        for (heapMiB in listOf(64L, 128L, 256L, 512L, 1024L, 2048L, 4096L)) {
            val budget = playbackMemoryBudgetBytes(heapMiB * MIB, lowRam = false)
            assertTrue(budget <= heapMiB * MIB / 2L)
            assertTrue(budget <= 1024L * MIB)
        }
        assertEquals(128L * MIB, playbackMemoryBudgetBytes(256L * MIB, lowRam = false))
        assertEquals(256L * MIB, playbackMemoryBudgetBytes(512L * MIB, lowRam = false))
        assertEquals(16L * MIB, playbackMemoryBudgetBytes(512L * MIB, lowRam = true))
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
