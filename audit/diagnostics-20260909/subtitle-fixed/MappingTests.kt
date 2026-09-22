package com.yfuse.core2.android
import com.yfuse.core2.subtitle.*
import com.yfuse.core2.demux.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*
class AndroidFfmpegDemuxerMappingTest {
    
    fun `PGS sentinel is open until the next display and never accumulates previous pictures`() {
        for (version in 1..2) {
            val buffer = YSubtitleCueBuffer()
            val first = bitmapPayload(version, rectangleCount = 2).toBitmapSubtitleDisplay(sampleAt(1_000_000L))
            assertTrue(first.cues.all { it.endUs == Long.MAX_VALUE })
            buffer.apply(first)
            buffer.apply(bitmapPayload(version).toBitmapSubtitleDisplay(sampleAt(3_000_000L)))
            buffer.apply(bitmapPayload(version, rectangleCount = 0).toBitmapSubtitleDisplay(sampleAt(5_000_000L)))
            val timeline = YSubtitleTimeline(buffer.toList())
            assertEquals(2, timeline.activeAt(1_500_000L).size)
            assertEquals(1, timeline.activeAt(3_500_000L).size)
            assertTrue(timeline.activeAt(5_500_000L).isEmpty())
            assertTrue(timeline.activeAt(3_600_000_000L).isEmpty())
        }
    }

    
    fun `v2 PGS clear preserves composition PTS even when END packet arrives later`() {
        val display =
            bitmapPayload(2, rectangleCount = 0, ptsOffsetUs = -2_000_000L)
                .toBitmapSubtitleDisplay(sampleAt(5_000_000L))
        assertEquals(3_000_000L, display.startUs)
        assertTrue(display.cues.isEmpty())
    }

    
    fun `v2 PGS malformed timestamp extension is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            bitmapPayload(2, rectangleCount = 0).copyOf(28).toBitmapSubtitleDisplay(sampleAt(1_000_000L))
        }
    }

    private fun sampleAt(ptsUs: Long) = YCompressedSample(YTrackId(3), byteArrayOf(1), ptsUs, durationUs = 2_000_000L)

    private fun bitmapPayload(
        version: Int,
        rectangleCount: Int = 1,
        ptsOffsetUs: Long = 0L,
    ): ByteArray =
        ByteBuffer
            .allocate(28 + (if (version >= 2) 8 else 0) + rectangleCount * 32)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(0x42555359)
                putInt(version)
                putInt(if (rectangleCount == 0) 0 else 1920)
                putInt(if (rectangleCount == 0) 0 else 1080)
                putInt(0)
                putInt(-1) // FFmpeg UINT32_MAX: valid until the following display, not 49 days.
                putInt(rectangleCount)
                if (version >= 2) putLong(ptsOffsetUs)
                repeat(rectangleCount) { index ->
                    putInt(index)
                    putInt(900)
                    putInt(1)
                    putInt(1)
                    putInt(0)
                    putInt(1)
                    putInt(0)
                    putInt(0xff112233.toInt())
                }
            }.array()
}
