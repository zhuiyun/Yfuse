package com.yfuse.core2.android

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YTrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidDemuxReadAheadBufferTest {
    @Test
    fun `active fill reports progress before it finishes and does not retain a stalled rate`() {
        val throughput = DemuxFillThroughput()
        throughput.start(1L)
        throughput.record(100_000L, 100_000_001L)
        assertEquals(8_000_000L, throughput.snapshot(100_000_001L, fillScheduled = true))
        assertEquals(0L, throughput.snapshot(2_200_000_001L, fillScheduled = true))

        throughput.record(100_000L, 2_300_000_001L)
        assertTrue(throughput.snapshot(2_300_000_001L, fillScheduled = true) in 1L until 8_000_000L)
        throughput.reset()
        assertEquals(0L, throughput.snapshot(2_400_000_001L, fillScheduled = false))
    }

    @Test
    fun `buffer duration follows the shortest selected playback track`() {
        val video = YTrackId(0)
        val audio = YTrackId(1)
        val samples =
            listOf(
                YCompressedSample(video, byteArrayOf(1), 0L, durationUs = 100_000L),
                YCompressedSample(video, byteArrayOf(2), 1_000_000L, durationUs = 100_000L),
                YCompressedSample(audio, byteArrayOf(3), 0L, durationUs = 100_000L),
                YCompressedSample(audio, byteArrayOf(4), 100_000L, durationUs = 100_000L),
            )
        val demux =
            object : YDemuxer {
                override val name = "queued samples"
                private var next = 0

                override fun open(source: YDemuxSource) =
                    YDemuxOpenResult(container = YContainer.Matroska, tracks = emptyList())

                override fun selectTracks(trackIds: Set<YTrackId>) = Unit

                override fun readSample(): YCompressedSample? = samples.getOrNull(next++)

                override fun seekTo(positionUs: Long) = Unit

                override fun close() = Unit
            }
        val node = AndroidDemuxReadAheadNode(demux)
        try {
            node.open(YDemuxSource("https://media.invalid/movie.mkv"))
            node.selectTracks(setOf(video, audio))
            val until = System.nanoTime() + 2_000_000_000L
            while (node.snapshot().queuedSamples < samples.size && System.nanoTime() < until) {
                Thread.sleep(1L)
            }
            assertEquals(samples.size, node.snapshot().queuedSamples)
            assertEquals(200_000L, node.snapshot().bufferedDurationUs)
        } finally {
            node.release()
        }
    }
}
