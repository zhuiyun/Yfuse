package com.yfuse.core2.android

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YSubtitlePacketDecoder
import com.yfuse.core2.demux.YSubtitleTrackFormat
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleCueBuffer
import com.yfuse.core2.subtitle.YSubtitleDecodeResult
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDemuxReadAheadNodeTest {
    @Test
    fun future_subtitle_memory_pressure_does_not_hold_av_packets_behind_the_shared_queue() {
        val subtitleTrack = YTrackId(2)
        val packets =
            List(6) { YCompressedSample(subtitleTrack, byteArrayOf(1), it * 10_000_000L) } +
                listOf(YCompressedSample(TRACK, byteArrayOf(2), 0L), YCompressedSample(YTrackId(1), byteArrayOf(3), 0L))
        val fake =
            object : YDemuxer by FakeDemuxer(packets), YSubtitlePacketDecoder {
                override fun open(source: YDemuxSource) =
                    YDemuxOpenResult(
                        YContainer.Matroska,
                        tracks =
                            listOf(
                                YDemuxTrack(
                                    subtitleTrack,
                                    YDemuxTrackType.Subtitle,
                                    subtitle = YSubtitleTrackFormat(YSubtitleFormat.Pgs, "application/pgs"),
                                ),
                            ),
                    )

                override fun supportsSubtitleFormat(format: YSubtitleFormat) = format == YSubtitleFormat.Pgs

                override fun decodeSubtitle(sample: YCompressedSample) =
                    YSubtitleDecodeResult.DisplaySet(
                        sample.presentationTimeUs,
                        listOf(
                            YSubtitleCue(
                                "${sample.presentationTimeUs}",
                                sample.presentationTimeUs,
                                Long.MAX_VALUE,
                                YSubtitlePayload.BitmapArgb(512, 1024, 0, 0, 512, 1024, IntArray(512 * 1024)),
                            ),
                        ),
                    )
            }
        val node = AndroidDemuxReadAheadNode(fake)
        val buffer = YSubtitleCueBuffer(maximumBitmapBytes = 4L * 1024L * 1024L)
        try {
            node.open(YDemuxSource("file:///badly-interleaved.mkv"))
            node.configure(
                targetAheadUs = 1_000_000L,
                mediaBitRateBitsPerSecond = 8_000_000L,
                memoryBudgetBytes = 4L * 1024L * 1024L,
            )
            node.selectTracks(setOf(TRACK, YTrackId(1), subtitleTrack))
            val receivedAv = mutableSetOf<YTrackId>()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (receivedAv.size < 2 && System.nanoTime() < deadline) {
                when (val result = node.pollSample()) {
                    is YQueuedDemuxResult.Sample -> {
                        result.subtitleResult?.let {
                            buffer.apply(it)
                            // Playback is stalled at 0 while A/V waits behind all future subtitles.
                            buffer.prune(-60_000_000L, positionUs = 0L)
                        } ?: receivedAv.add(result.value.trackId)
                    }
                    is YQueuedDemuxResult.Failed -> throw result.cause
                    YQueuedDemuxResult.Empty -> Thread.sleep(1L)
                    YQueuedDemuxResult.EndOfInput -> break
                }
            }
            assertEquals(setOf(TRACK, YTrackId(1)), receivedAv)
            assertTrue(buffer.droppedFutureDisplayCount > 0L)
            assertTrue(buffer.retainedBitmapBytes <= 4L * 1024L * 1024L)
        } finally {
            node.release()
        }
    }

    @Test
    fun subtitle_result_is_ready_without_waiting_for_the_next_blocking_network_read() {
        val blockedRead = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val subtitle = YCompressedSample(YTrackId(2), byteArrayOf(1), 0L)
        val cue = YSubtitleCue("s", 0, 1_000_000, YSubtitlePayload.Text("subtitle"))
        var decodeThread = ""
        val fake =
            object : YDemuxer, YSubtitlePacketDecoder {
                override val name = "subtitle"
                var reads = 0

                override fun open(source: YDemuxSource) =
                    YDemuxOpenResult(
                        YContainer.Matroska,
                        tracks =
                            listOf(
                                YDemuxTrack(
                                    YTrackId(2),
                                    YDemuxTrackType.Subtitle,
                                    subtitle = YSubtitleTrackFormat(YSubtitleFormat.Pgs, "application/pgs"),
                                ),
                            ),
                    )

                override fun selectTracks(trackIds: Set<YTrackId>) = Unit

                override fun readSample(): YCompressedSample? {
                    when (reads++) {
                        0 -> return subtitle
                        1 -> return subtitle.copy(presentationTimeUs = 3_000_000L)
                        2 -> return subtitle.copy(presentationTimeUs = 4_000_000L)
                    }
                    blockedRead.countDown()
                    check(releaseRead.await(2, TimeUnit.SECONDS))
                    return null
                }

                override fun seekTo(positionUs: Long) = Unit

                override fun close() = Unit

                override fun supportsSubtitleFormat(format: YSubtitleFormat) = true

                override fun decodeSubtitle(sample: YCompressedSample): YSubtitleDecodeResult {
                    decodeThread = Thread.currentThread().name
                    return when (sample.presentationTimeUs) {
                        0L -> YSubtitleDecodeResult.DisplaySet(0L, listOf(cue))
                        3_000_000L -> YSubtitleDecodeResult.DisplaySet(3_000_000L, emptyList())
                        else -> YSubtitleDecodeResult.NoOutput
                    }
                }
            }
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///test.mkv"))
            node.selectTracks(setOf(YTrackId(2)))
            assertTrue(blockedRead.await(2, TimeUnit.SECONDS))
            // The demux owner is blocked. Polling still returns its earlier decoded subtitle.
            val result = node.pollSample() as YQueuedDemuxResult.Sample
            assertEquals(YSubtitleDecodeResult.DisplaySet(0L, listOf(cue)), result.subtitleResult)
            val clear = node.pollSample() as YQueuedDemuxResult.Sample
            assertEquals(YSubtitleDecodeResult.DisplaySet(3_000_000L, emptyList()), clear.subtitleResult)
            val unchanged = node.pollSample() as YQueuedDemuxResult.Sample
            assertEquals(YSubtitleDecodeResult.NoOutput, unchanged.subtitleResult)
            assertTrue(decodeThread.startsWith("YCore-Demux-"))
            assertEquals(1L, releaseRead.count)
        } finally {
            releaseRead.countDown()
            node.release()
        }
    }

    @Test
    fun backpressured_video_does_not_hide_audio_or_reorder_video_packets() {
        val video = samples(0, 2)
        val audio = video[0].copy(trackId = YTrackId(1))
        val node = AndroidDemuxReadAheadNode(FakeDemuxer(video + audio))
        try {
            node.open(YDemuxSource("file:///test.mkv"))
            node.selectTracks(setOf(TRACK, YTrackId(1)))
            awaitQueuedSamples(node, 3)
            assertEquals(audio, (node.pollSample(setOf(TRACK)) as YQueuedDemuxResult.Sample).value)
            assertEquals(video[0], awaitSample(node))
            assertEquals(video[1], awaitSample(node))
        } finally {
            node.release()
        }
    }

    @Test
    fun prepared_demux_adoption_never_repeats_open() {
        val fake = FakeDemuxer(samples(0, 2))
        val opened = fake.open(YDemuxSource("file:///test.mkv"))
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.adoptOpen(opened)
            node.selectTracks(setOf(TRACK))
            assertEquals(0L, awaitSample(node).presentationTimeUs)
            assertEquals(1, fake.openCount)
        } finally {
            node.release()
        }
    }

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

    private class FakeDemuxer(
        initial: List<YCompressedSample>,
    ) : YDemuxer {
        override val name: String = "fake"
        private var samples = ArrayDeque(initial)
        var samplesAfterSeek: List<YCompressedSample> = emptyList()
        var lastReadThread: String = ""
        var openCount = 0

        override fun open(source: YDemuxSource): YDemuxOpenResult =
            YDemuxOpenResult(
                container = YContainer.Matroska,
                tracks = emptyList(),
            ).also { openCount++ }

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
