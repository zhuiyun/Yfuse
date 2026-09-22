package com.yfuse.core2.android

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YTrackId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDemuxReadControlTest {
    @Test
    fun `an intentionally interrupted old read is not published as terminal failure`() {
        val demux = BlockingDemux(holdControl = true)
        val node = opened(demux)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val seeking = caller.submit { node.seekTo(7_000_000L) }
            assertTrue(demux.controlEntered.await(2, TimeUnit.SECONDS))
            assertEquals(YQueuedDemuxResult.Empty, node.pollSample())
            assertFalse(node.snapshot().endOfInput)
            demux.allowControl.countDown()
            seeking.get(2, TimeUnit.SECONDS)
            assertEquals(7_000_000L, awaitSample(node).presentationTimeUs)
        } finally {
            demux.allowControl.countDown()
            demux.unblock.countDown()
            node.release()
            caller.shutdownNow()
        }
    }

    @Test
    fun `seek interrupts blocked read then continues on the same demux owner`() {
        val demux = BlockingDemux()
        val node = opened(demux)
        try {
            node.seekTo(8_000_000L)
            assertEquals(8_000_000L, awaitSample(node).presentationTimeUs)
            assertEquals(listOf(8_000_000L), demux.seeks)
            assertEquals(1, demux.owners.toSet().size)
            assertFalse(demux.cancelled)
        } finally {
            node.release()
        }
    }

    @Test
    fun `expired queued seek never runs after a blocked reader finally returns`() {
        val demux = BlockingDemux(ignoreInterrupt = true)
        val node = opened(demux, timeoutMs = 250L)
        try {
            assertFailsWith<IllegalStateException> { node.seekTo(1_000_000L) }
            assertTrue(demux.seeks.isEmpty())
            demux.unblock.countDown()
            node.selectTracks(setOf(TRACK), positionUs = 9_000_000L)
            assertEquals(9_000_000L, awaitSample(node).presentationTimeUs)
            assertEquals(listOf(9_000_000L), demux.seeks)
        } finally {
            demux.unblock.countDown()
            node.release()
        }
    }

    @Test
    fun `permanent cancellation cannot be cleared by a pending seek`() {
        val demux = BlockingDemux(ignoreInterrupt = true)
        val node = opened(demux)
        val caller = Executors.newSingleThreadExecutor()
        try {
            val seeking =
                caller.submit<Boolean> {
                    runCatching { node.seekTo(4_000_000L) }.isSuccess
                }
            assertTrue(demux.controlRequested.await(2, TimeUnit.SECONDS))
            node.cancelPendingRead()
            assertFalse(seeking.get(2, TimeUnit.SECONDS))
            assertTrue(demux.seeks.isEmpty())
            assertTrue(demux.cancelled)
        } finally {
            demux.unblock.countDown()
            node.release()
            caller.shutdownNow()
        }
    }

    @Test
    fun `cleanup retains native ownership while a timed out read ignores cancellation`() {
        val demux = BlockingDemux(ignoreInterrupt = true, ignoreCancel = true)
        val node = opened(demux, timeoutMs = 250L)
        val caller = Executors.newSingleThreadExecutor()
        try {
            assertFailsWith<IllegalStateException> { node.seekTo(3_000_000L) }
            val releasing = caller.submit { node.release() }
            assertTrue(demux.cancelRequested.await(2, TimeUnit.SECONDS))
            assertFalse(releasing.isDone)
            assertFalse(demux.closed)
            demux.unblock.countDown()
            releasing.get(2, TimeUnit.SECONDS)
            assertTrue(demux.closed)
            assertTrue(demux.seeks.isEmpty())
        } finally {
            demux.unblock.countDown()
            caller.shutdownNow()
        }
    }

    private fun opened(
        demux: BlockingDemux,
        timeoutMs: Long = 1_500L,
    ): AndroidDemuxReadAheadNode {
        val node = AndroidDemuxReadAheadNode(demux, timeoutMs)
        node.open(YDemuxSource("https://media.invalid/movie.mkv"))
        node.selectTracks(setOf(TRACK))
        assertTrue(demux.readEntered.await(2, TimeUnit.SECONDS))
        return node
    }

    private fun awaitSample(node: AndroidDemuxReadAheadNode): YCompressedSample {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < until) {
            when (val result = node.pollSample()) {
                is YQueuedDemuxResult.Sample -> return result.value
                is YQueuedDemuxResult.Failed -> throw result.cause
                else -> Thread.sleep(1L)
            }
        }
        error("No sample after seek")
    }

    private class BlockingDemux(
        private val ignoreInterrupt: Boolean = false,
        private val ignoreCancel: Boolean = false,
        private val holdControl: Boolean = false,
    ) : YDemuxer,
        AndroidDemuxReadControl {
        override val name = "blocked test demux"
        val readEntered = CountDownLatch(1)
        val controlRequested = CountDownLatch(1)
        val cancelRequested = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val controlEntered = CountDownLatch(1)
        val allowControl = CountDownLatch(1)
        val seeks = CopyOnWriteArrayList<Long>()
        val owners = CopyOnWriteArrayList<String>()
        private val samples = ConcurrentLinkedQueue<YCompressedSample>()
        private val latestInterrupt = AtomicLong()
        private var blockFirstRead = true

        @Volatile var cancelled = false

        @Volatile var closed = false

        override fun open(source: YDemuxSource) =
            YDemuxOpenResult(container = YContainer.Matroska, tracks = emptyList())

        override fun selectTracks(trackIds: Set<YTrackId>) {
            owners += Thread.currentThread().name
        }

        override fun readSample(): YCompressedSample? {
            owners += Thread.currentThread().name
            if (blockFirstRead) {
                blockFirstRead = false
                readEntered.countDown()
                check(unblock.await(3, TimeUnit.SECONDS)) { "Test reader never interrupted" }
                error("simulated interrupted IO")
            }
            return samples.poll()
        }

        override fun seekTo(positionUs: Long) {
            owners += Thread.currentThread().name
            check(!cancelled)
            seeks += positionUs
            samples.add(YCompressedSample(TRACK, byteArrayOf(1), positionUs))
        }

        override fun interruptRead(generation: Long) {
            latestInterrupt.accumulateAndGet(generation, ::maxOf)
            if (readEntered.count == 0L) {
                controlRequested.countDown()
                if (!ignoreInterrupt) unblock.countDown()
            }
        }

        override fun resumeRead(generation: Long): Boolean {
            if (holdControl && readEntered.count == 0L) {
                controlEntered.countDown()
                check(allowControl.await(2, TimeUnit.SECONDS))
            }
            return !cancelled && generation == latestInterrupt.get()
        }

        override fun cancelPendingRead() {
            cancelled = true
            cancelRequested.countDown()
            if (!ignoreCancel) unblock.countDown()
        }

        override fun close() {
            check(unblock.count == 0L)
            closed = true
        }
    }
}

private val TRACK = YTrackId(0)
