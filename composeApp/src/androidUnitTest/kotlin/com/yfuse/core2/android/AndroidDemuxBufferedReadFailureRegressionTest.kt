package com.yfuse.core2.android

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.network.YPlaybackBufferGate
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Audit-only probe: a failed speculative read must not discard already readable packets. */
class AndroidDemuxBufferedReadFailureRegressionTest {
    @Test
    fun consumes_valid_buffer_before_reporting_a_later_network_read_failure() {
        withBufferedFailure { node, packets, failure ->
            packets.forEach { expected ->
                val actual = assertIs<YQueuedDemuxResult.Sample>(node.pollSample())
                assertSame(expected, actual.value)
            }
            assertSame(failure, assertIs<YQueuedDemuxResult.Failed>(node.pollSample()).cause)
        }
    }

    @Test
    fun closed_output_gate_can_use_its_thirty_second_reserve_after_a_later_read_failure() {
        withBufferedFailure { node, _, _ ->
            // EnhancedPlaybackSession calls this before evaluating its startup/rebuffer gate.
            // A complete 30-second queued reserve can satisfy either gate without more I/O.
            node.ensureReadAhead()
            assertTrue(node.snapshot().endOfInput)
            assertEquals(30_000_000L, node.snapshot().bufferedDurationUs)
        }
    }

    @Test
    fun a_failed_source_opens_the_gate_even_below_the_normal_resume_threshold() {
        withBufferedFailure(packetDurationUs = 10_000L) { node, _, _ ->
            val snapshot = node.snapshot()
            val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 4_000_000L)
            assertFalse(gate.evaluate(snapshot.bufferedDurationUs, endOfInput = false).outputAllowed)
            node.ensureReadAhead()
            assertTrue(gate.evaluate(snapshot.bufferedDurationUs, endOfInput = snapshot.endOfInput).outputAllowed)
            assertIs<YQueuedDemuxResult.Sample>(node.pollSample())
        }
    }

    @Test
    fun a_blocked_decoder_receives_the_last_pending_sample_before_the_failure_is_deferred() {
        withBufferedFailure { node, packets, failure ->
            val input = AndroidDemuxInputQueue()
            val accepted = mutableListOf<YCompressedSample>()
            var deferred: Throwable? = null
            input.pump(node::pollSample, { sample ->
                if (sample.value === packets.last()) {
                    false
                } else {
                    accepted += sample.value
                    true
                }
            }, { deferred = it })
            assertEquals(1, input.pendingCount)
            assertEquals(null, deferred)
            node.ensureReadAhead(hasPendingInput = input.pendingCount > 0)
            val result =
                input.pump(node::pollSample, { sample ->
                    accepted += sample.value
                    true
                }, { deferred = it })
            assertTrue(result.ended)
            assertEquals(packets, accepted)
            assertSame(failure, deferred)
            assertEquals(0, input.pendingCount)
        }
    }

    private fun withBufferedFailure(
        packetDurationUs: Long = 10_000_000L,
        verify: (AndroidDemuxReadAheadNode, List<YCompressedSample>, SocketTimeoutException) -> Unit,
    ) {
        val track = YTrackId(0)
        val packets =
            List(3) { index ->
                YCompressedSample(
                    track,
                    byteArrayOf(index.toByte()),
                    index * packetDurationUs,
                    durationUs = packetDurationUs,
                )
            }
        val failure = SocketTimeoutException("A later speculative media read timed out")
        val fillFinished = CountDownLatch(1)
        val demuxer =
            object : YDemuxer {
                override val name = "buffered-network-failure-probe"
                private var nextPacket = 0

                override fun open(source: YDemuxSource) =
                    YDemuxOpenResult(container = YContainer.Matroska, tracks = emptyList())

                override fun selectTracks(trackIds: Set<YTrackId>) = Unit

                override fun readSample(): YCompressedSample = packets.getOrNull(nextPacket++) ?: throw failure

                override fun seekTo(positionUs: Long) = Unit

                override fun close() = Unit
            }
        val memory = PlaybackMemoryPool(64L * 1024L * 1024L)
        val node =
            AndroidDemuxReadAheadNode(
                delegate = demuxer,
                beforeFillFinished = { fillFinished.countDown() },
                memoryLeaseOverride = memory.acquire(PlaybackBufferKind.Demux, 64L * 1024L * 1024L),
            )
        try {
            node.open(YDemuxSource("https://media.invalid/buffered.mkv"))
            node.configure(targetAheadUs = 120_000_000L, mediaBitRateBitsPerSecond = 8_000_000L)
            node.selectTracks(setOf(track))
            assertTrue(fillFinished.await(2, TimeUnit.SECONDS), "The later read must fail before polling")
            assertEquals(packets.size, node.snapshot().queuedSamples)
            assertEquals(3 * packetDurationUs, node.snapshot().bufferedDurationUs)
            verify(node, packets, failure)
        } finally {
            node.release()
        }
    }
}
