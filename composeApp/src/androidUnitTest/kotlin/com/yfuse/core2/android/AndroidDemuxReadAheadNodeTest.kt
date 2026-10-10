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
import com.yfuse.core2.network.YBufferConditions
import com.yfuse.core2.network.YBufferController
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleCueBuffer
import com.yfuse.core2.subtitle.YSubtitleDecodeResult
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AndroidDemuxReadAheadNodeTest {
    @Test
    fun lease_shrink_and_recovery_replan_without_being_capped_by_the_previous_queue_limit() {
        val mib = 1024L * 1024L
        val pool = PlaybackMemoryPool(128L * mib)
        val transport = pool.acquire(PlaybackBufferKind.Transport, 64L * mib)
        val lease = pool.acquire(PlaybackBufferKind.Demux, 128L * mib)
        val staging = pool.reserve().also { it.resize(32L * mib) }
        val node = AndroidDemuxReadAheadNode(FakeDemuxer(emptyList()), memoryLeaseOverride = lease)
        try {
            node.open(YDemuxSource("file:///budget.mkv"))
            node.configure(20_000_000L, 78_938_975L, 24L * mib)
            val original = node.snapshot()
            assertTrue(original.memoryBudgetBytes > 24L * mib)
            assertEquals(24L * mib, original.queueBudgetBytes)

            pool.setPressure(true)
            val pressure = node.snapshot()
            assertTrue(pressure.memoryBudgetBytes < 24L * mib)
            assertEquals(pressure.memoryBudgetBytes, pressure.queueBudgetBytes)
            val smallerPlan =
                YBufferController.plan(
                    YBufferConditions(
                        remote = true,
                        mediaBitRateBitsPerSecond = 78_938_975L,
                        preferredTargetAheadUs = 20_000_000L,
                        memoryBudgetBytes = playbackDemuxMemoryBudgetBytes(pressure.memoryBudgetBytes),
                    ),
                )
            node.configure(smallerPlan.targetAheadUs, 78_938_975L, smallerPlan.maximumBytes)

            pool.setPressure(false)
            val recovered = node.snapshot()
            assertEquals(original.memoryBudgetBytes, recovered.memoryBudgetBytes)
            assertEquals(smallerPlan.maximumBytes, recovered.queueBudgetBytes)
            val recoveredPlan =
                YBufferController.plan(
                    YBufferConditions(
                        remote = true,
                        mediaBitRateBitsPerSecond = 78_938_975L,
                        preferredTargetAheadUs = 20_000_000L,
                        memoryBudgetBytes = playbackDemuxMemoryBudgetBytes(recovered.memoryBudgetBytes),
                    ),
                )
            assertTrue(recoveredPlan.targetAheadUs > smallerPlan.targetAheadUs)
            assertTrue(recoveredPlan.resumePlaybackUs > smallerPlan.resumePlaybackUs)
            node.configure(recoveredPlan.targetAheadUs, 78_938_975L, recoveredPlan.maximumBytes)
            assertTrue(node.snapshot().queueBudgetBytes > 24L * mib)
            assertTrue(node.snapshot().queueBudgetBytes <= recovered.memoryBudgetBytes)
        } finally {
            node.release()
            staging.close()
            transport.close()
        }
    }

    @Test
    fun high_bitrate_queue_can_fill_past_128_mib_and_thirty_seconds() {
        val mib = 1024L * 1024L
        val pool = PlaybackMemoryPool(1024L * mib)
        val packet = ByteArray(mib.toInt())
        val fake =
            FakeDemuxer(
                List(270) { index ->
                    YCompressedSample(TRACK, packet, index * 250_000L, durationUs = 250_000L)
                },
            )
        val node =
            AndroidDemuxReadAheadNode(
                fake,
                memoryLeaseOverride = pool.acquire(PlaybackBufferKind.Demux, 1024L * mib),
            )
        try {
            node.open(YDemuxSource("file:///high-bitrate.mkv"))
            node.configure(120_000_000L, 80_000_000L, 1024L * mib)
            node.selectTracks(setOf(TRACK))
            awaitQueuedSamples(node, fake, 270)
            assertEquals(270L * mib, node.snapshot().queuedBytes)
            assertEquals(67_500_000L, node.snapshot().bufferedDurationUs)
            assertEquals(1024L * mib, node.snapshot().queueBudgetBytes)
        } finally {
            node.release()
        }
    }

    @Test
    fun indexed_queue_duration_preserves_duplicate_and_reordered_timestamps_when_packets_are_removed() {
        val fake =
            FakeDemuxer(
                listOf(
                    YCompressedSample(TRACK, byteArrayOf(1), 2_000_000L, durationUs = 1_000_000L),
                    YCompressedSample(TRACK, byteArrayOf(1), 0L, durationUs = 10_000_000L),
                    YCompressedSample(TRACK, byteArrayOf(1), 2_000_000L, durationUs = 2_000_000L),
                    YCompressedSample(TRACK, byteArrayOf(1), 1_000_000L, durationUs = 1_000_000L),
                ),
            )
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///reordered.mkv"))
            node.configure(120_000_000L, 8_000_000L)
            node.selectTracks(setOf(TRACK))
            awaitQueuedSamples(node, fake, 4)
            assertEquals(10_000_000L, node.snapshot().bufferedDurationUs)
            assertTrue(node.pollSample() is YQueuedDemuxResult.Sample)
            assertEquals(10_000_000L, node.snapshot().bufferedDurationUs)
            assertTrue(node.pollSample() is YQueuedDemuxResult.Sample)
            assertEquals(3_000_000L, node.snapshot().bufferedDurationUs)
            assertTrue(node.pollSample() is YQueuedDemuxResult.Sample)
            assertEquals(mapOf(0 to 1_000_000L), node.snapshot(includeTrackDetails = true).trackBufferedUs)
            assertTrue(node.pollSample() is YQueuedDemuxResult.Sample)
            assertEquals(mapOf(0 to 0L), node.snapshot(includeTrackDetails = true).trackBufferedUs)
        } finally {
            node.release()
        }
    }

    @Test
    fun minute_long_queue_with_many_small_packets_keeps_its_time_span_without_full_queue_scans() {
        val packet = byteArrayOf(1)
        val fake =
            FakeDemuxer(
                List(50_000) { index -> YCompressedSample(TRACK, packet, index * 1_200L, durationUs = 1_200L) },
            )
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///many-packets.mkv"))
            node.configure(120_000_000L, 1_000_000L)
            node.selectTracks(setOf(TRACK))
            awaitQueuedSamples(node, fake, 50_000)
            assertEquals(60_000_000L, node.snapshot().bufferedDurationUs)
            assertTrue(node.pollSample() is YQueuedDemuxResult.Sample)
            assertEquals(59_998_800L, node.snapshot().bufferedDurationUs)
        } finally {
            node.release()
        }
    }

    @Test
    fun refill_request_at_owner_exit_survives_without_another_consumer_poll() {
        val finishing = CountDownLatch(1)
        val finishAllowed = CountDownLatch(1)
        val firstFill =
            java.util.concurrent.atomic
                .AtomicBoolean(true)
        val fake = FakeDemuxer(samples(0, 20))
        val node =
            AndroidDemuxReadAheadNode(fake, beforeFillFinished = {
                if (firstFill.compareAndSet(true, false)) {
                    finishing.countDown()
                    check(finishAllowed.await(3, TimeUnit.SECONDS))
                }
            })
        try {
            node.open(YDemuxSource("file:///handoff.mkv"))
            node.configure(10_000_000L, 8_000_000L, 4L * 1024L * 1024L)
            node.selectTracks(setOf(TRACK))
            assertTrue(finishing.await(2, TimeUnit.SECONDS))
            repeat(4) { assertTrue(node.pollSample() is YQueuedDemuxResult.Sample) }
            assertEquals(0, node.snapshot().queuedSamples)
            assertTrue(node.snapshot().fillScheduled)
            finishAllowed.countDown()
            // A buffering player no longer polls packets: only the producer can recover here.
            awaitQueuedSamples(node, fake, 4)
            assertTrue(node.snapshot().packetsRead >= 8)
        } finally {
            finishAllowed.countDown()
            node.release()
        }
    }

    @Test
    fun buffering_observes_read_failure_without_consuming_packets() {
        val finished = CountDownLatch(1)
        val failure = java.io.IOException("test read failed")
        val fake =
            object : YDemuxer by FakeDemuxer(emptyList()) {
                override fun readSample(): YCompressedSample? = throw failure
            }
        val node = AndroidDemuxReadAheadNode(fake, beforeFillFinished = { finished.countDown() })
        try {
            node.open(YDemuxSource("file:///failure.mkv"))
            node.selectTracks(setOf(TRACK))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            kotlin.test.assertSame(failure, kotlin.test.assertFailsWith<java.io.IOException> { node.ensureReadAhead() })
        } finally {
            node.release()
        }
    }

    @Test
    fun queue_diagnostics_distinguish_missing_audio_from_video_reserve() {
        val fake = FakeDemuxer(samples(0, 4))
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///tracks.mkv"))
            node.selectTracks(setOf(TRACK, YTrackId(1)))
            awaitQueuedSamples(node, fake, 4)
            assertEquals(mapOf(0 to 400_000L, 1 to 0L), node.snapshot(includeTrackDetails = true).trackBufferedUs)
            assertTrue(node.snapshot().trackBufferedUs.isEmpty())
        } finally {
            node.release()
        }
    }

    @Test
    fun future_subtitle_memory_pressure_does_not_hold_av_packets_behind_the_shared_queue() {
        val subtitleTrack = YTrackId(2)
        val packets =
            List(6) { YCompressedSample(subtitleTrack, byteArrayOf(1), it * 10_000_000L) } +
                listOf(YCompressedSample(TRACK, byteArrayOf(2), 0L), YCompressedSample(YTrackId(1), byteArrayOf(3), 0L))
        val demuxer = FakeDemuxer(packets)
        val fake =
            object : YDemuxer by demuxer, YSubtitlePacketDecoder {
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
                    YQueuedDemuxResult.Empty -> demuxer.awaitNextRead()
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
        val fake = FakeDemuxer(video + audio)
        val node = AndroidDemuxReadAheadNode(fake)
        try {
            node.open(YDemuxSource("file:///test.mkv"))
            node.selectTracks(setOf(TRACK, YTrackId(1)))
            awaitQueuedSamples(node, fake, 3)
            assertEquals(audio, (node.pollSample(setOf(TRACK)) as YQueuedDemuxResult.Sample).value)
            assertEquals(video[0], awaitSample(node, fake))
            assertEquals(video[1], awaitSample(node, fake))
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
            assertEquals(0L, awaitSample(node, fake).presentationTimeUs)
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

            val first = awaitSample(node, fake)

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
            awaitQueuedSamples(node, fake, 4)

            fake.samplesAfterSeek = samples(start = 100, count = 8)
            node.seekTo(10_000_000L)

            assertEquals(10_000_000L, awaitSample(node, fake).presentationTimeUs)
        } finally {
            node.release()
        }
    }

    private fun awaitSample(
        node: AndroidDemuxReadAheadNode,
        fake: FakeDemuxer,
    ): YCompressedSample {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            when (val result = node.pollSample()) {
                is YQueuedDemuxResult.Sample -> return result.value
                is YQueuedDemuxResult.Failed -> throw result.cause
                YQueuedDemuxResult.EndOfInput -> error("Unexpected end of input")
                YQueuedDemuxResult.Empty -> fake.awaitNextRead()
            }
        }
        error("Timed out waiting for demux read-ahead")
    }

    private fun awaitQueuedSamples(
        node: AndroidDemuxReadAheadNode,
        fake: FakeDemuxer,
        minimum: Int,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (node.snapshot().queuedSamples >= minimum) return
            fake.awaitNextRead()
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

        /** One permit per read: the read-ahead queue only changes when the owner thread reads. */
        private val reads = Semaphore(0)

        /**
         * Parks until the demuxer is read again instead of for a fixed interval. The bound covers
         * the gap between a read returning and the node publishing that sample, and the read
         * after which there are no more.
         */
        fun awaitNextRead() {
            reads.tryAcquire(READ_WAIT_MS, TimeUnit.MILLISECONDS)
        }

        override fun open(source: YDemuxSource): YDemuxOpenResult =
            YDemuxOpenResult(
                container = YContainer.Matroska,
                tracks = emptyList(),
            ).also { openCount++ }

        override fun selectTracks(trackIds: Set<YTrackId>) = Unit

        override fun readSample(): YCompressedSample? {
            lastReadThread = Thread.currentThread().name
            return samples.removeFirstOrNull().also { reads.release() }
        }

        override fun seekTo(positionUs: Long) {
            samples = ArrayDeque(samplesAfterSeek)
        }

        override fun close() = Unit
    }

    private companion object {
        val TRACK = YTrackId(0)
        const val READ_WAIT_MS = 20L

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
