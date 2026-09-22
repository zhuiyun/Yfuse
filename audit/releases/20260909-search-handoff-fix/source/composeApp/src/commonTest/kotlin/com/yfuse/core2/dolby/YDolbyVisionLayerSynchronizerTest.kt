package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YTrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class YDolbyVisionLayerSynchronizerTest {
    @Test
    fun joins_dual_tracks_within_250_microseconds_and_flushes_branches() {
        val synchronizer = YDolbyVisionLayerSynchronizer()
        assertNull(synchronizer.offerBase(sample(1, 1_000_000)))
        val pair = assertNotNull(synchronizer.offerEnhancement(sample(2, 1_000_180)))
        assertEquals(180L, pair.presentationTimeErrorUs)

        synchronizer.offerBase(sample(1, 2_000_000))
        synchronizer.onDiscontinuity()
        assertNull(synchronizer.offerEnhancement(sample(2, 2_000_000)))
    }

    @Test
    fun splits_base_rpu_and_enhancement_without_claiming_composition() {
        val accessUnit = annexB(1, 0x11) + annexB(62, 0x22) + annexB(63, 0x33)
        val split = splitDolbyVisionLayers(accessUnit, YSamplePacking.AnnexB, 42)
        assertEquals(42, split.presentationTimeUs)
        assertEquals(1, split.rpu.size)
        assertEquals(1, split.enhancementLayer.size)
        assertEquals(0x11, split.baseLayer.last().toInt() and 0xff)
    }

    private fun sample(track: Int, pts: Long) =
        YCompressedSample(YTrackId(track), byteArrayOf(track.toByte()), pts)

    private fun annexB(type: Int, payload: Int): ByteArray =
        byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, payload.toByte())
}
