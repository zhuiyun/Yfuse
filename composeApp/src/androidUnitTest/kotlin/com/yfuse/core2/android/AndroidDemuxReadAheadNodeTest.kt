package com.yfuse.core2.android

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YTrackId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDemuxReadAheadNodeTest {
    @Test
    fun blocking_demux_reads_run_off_the_codec_pump_thread() {
        val callerThread = Thread.currentThread().name
        val fake = FakeDemuxer(samples(start = 0, count = 12))
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///test.mkv"))
            node.configure(targetAheadUs = 1_000_000L, mediaBitRateBitsPerSecond = 8_000_000L)
            node.selectTracks(setOf(TRACK))

            val first = awaitSample(node)

            assertEquals(0L, first.presentationTimeUs)
            assertNotEquals(callerThread, fake.lastReadThread)
            assertTrue(fake.lastReadThread.startsWith("YCore-Demux-"))
        } finally {
            node.release()
        }
    }

    @Test
    fun seek_discards_prefetched_samples_before_refilling() {
        val fake = FakeDemuxer(samples(start = 0, count = 20))
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///test.mkv"))
            node.configure(targetAheadUs = 1_000_000L, mediaBitRateBitsPerSecond = 8_000_000L)
            node.selectTracks(setOf(TRACK))
            awaitQueuedSamples(node, 4)

            fake.samplesAfterSeek = samples(start = 100, count = 8)
            node.seekTo(10_000_000L)

            assertEquals(10_000_000L, awaitSample(node).presentationTimeUs)
        } finally {
            node.release()
        }
    }

    private fun awaitSample(node: AndroidDemuxReadAheadNode): YCompressedSample {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            when (val result = node.pollSample()) {
                is YQueuedDemuxResult.Sample -> return result.value
                is YQueuedDemuxResult.Failed -> throw result.cause
                YQueuedDemuxResult.EndOfInput -> error("Unexpected end of input")
                YQueuedDemuxResult.Empty -> Thread.sleep(1L)
            }
        }
        error("Timed out waiting for demux read-ahead")
    }

    private fun awaitQueuedSamples(
        node: AndroidDemuxReadAheadNode,
        minimum: Int,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (node.snapshot().queuedSamples >= minimum) return
            Thread.sleep(1L)
        }
        error("Timed out waiting for queued samples")
    }

    private class FakeDemuxer(initial: List<YCompressedSample>) : YDemuxer {
        override val name: String = "fake"
        private var samples = ArrayDeque(initial)
        var samplesAfterSeek: List<YCompressedSample> = emptyList()
        var lastReadThread: String = ""

        override fun open(source: YDemuxSource): YDemuxOpenResult =
            YDemuxOpenResult(
                container = YContainer.Matroska,
                tracks = emptyList(),
            )

        override fun selectTracks(trackIds: Set<YTrackId>) = Unit

        override fun readSample(): YCompressedSample? {
            lastReadThread = Thread.currentThread().name
            return samples.removeFirstOrNull()
        }

        override fun seekTo(positionUs: Long) {
            samples = ArrayDeque(samplesAfterSeek)
        }

        override fun close() = Unit
    }

    private companion object {
        val TRACK = YTrackId(0)

        fun samples(
            start: Int,
            count: Int,
        ): List<YCompressedSample> =
            List(count) { offset ->
                val index = start + offset
                YCompressedSample(
                    trackId = TRACK,
                    data = ByteArray(1024 * 1024),
                    presentationTimeUs = index * 100_000L,
                    durationUs = 100_000L,
                )
            }
    }
}
