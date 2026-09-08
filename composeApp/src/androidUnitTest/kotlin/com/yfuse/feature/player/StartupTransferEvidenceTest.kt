package com.yfuse.feature.player

import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@UnstableApi
class StartupTransferEvidenceTest {
    @Test
    fun network_estimate_decays_during_silence_and_expires_without_new_bytes() {
        var now = 0L
        val evidence = StartupTransferEvidence { now }
        evidence.recordBytes(isNetwork = true, bytesTransferred = 128 * 1024)
        now = 300L
        evidence.recordBytes(isNetwork = true, bytesTransferred = 128 * 1024)
        val burstRate = evidence.snapshot().measuredThroughputBitsPerSecond
        now = 600L
        assertEquals(burstRate / 2L, evidence.snapshot().measuredThroughputBitsPerSecond)
        now = 801L
        assertEquals(0L, evidence.snapshot().measuredThroughputBitsPerSecond)
        assertEquals(0L, evidence.snapshot().measuredNetworkBytes)
    }

    @Test
    fun cache_reads_do_not_refresh_stalled_network_evidence() {
        var now = 0L
        val evidence = StartupTransferEvidence { now }
        evidence.recordBytes(isNetwork = true, bytesTransferred = 512 * 1024)
        now = 900L
        evidence.recordBytes(isNetwork = false, bytesTransferred = 1024 * 1024)
        assertEquals(0L, evidence.snapshot().measuredThroughputBitsPerSecond)
        assertEquals(1024L * 1024L, evidence.snapshot().cachedBytesRead)
        now = 3_001L
        assertEquals(0L, evidence.snapshot().cachedBytesRead)
    }

    @Test
    fun resetting_for_a_new_item_discards_all_transfer_evidence() {
        var now = 0L
        val evidence = StartupTransferEvidence { now }
        evidence.recordBytes(isNetwork = true, bytesTransferred = 512 * 1024)
        now = 300L
        evidence.recordBytes(isNetwork = false, bytesTransferred = 1024 * 1024)
        assertTrue(evidence.snapshot().measuredThroughputBitsPerSecond > 0L)
        evidence.reset()
        assertEquals(0L, evidence.snapshot().measuredThroughputBitsPerSecond)
        assertEquals(0L, evidence.snapshot().cachedBytesRead)
    }
}
