package com.yfuse.core2.android

import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Queue policy for the node that keeps MediaExtractor off the NativeDirect codec/render pump.
 *
 * The fake extractor stands in for MediaExtractor, which a JVM unit test cannot drive. Everything
 * asserted here is the part the pump depends on: the watermark that bounds memory, per-track
 * backpressure, seek invalidation, end-of-input ordering, and reuse of the staging buffer.
 */
class AndroidMediaExtractorReadAheadNodeTest {
    @Test
    fun `fills up to the configured watermark and stops`() {
        val extractor = FakeExtractorSource(sampleCount = 512, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.configureBufferPlan(targetAheadUs = 3_000_000L, maximumBytes = 64L * 1024L * 1024L)
        node.selectTracks(setOf(VIDEO_TRACK))

        val snapshot = node.awaitQueued(minimumSamples = 8)

        assertTrue(snapshot.bufferedDurationUs >= 3_000_000L, "buffered ${snapshot.bufferedDurationUs}")
        // A watermark that keeps filling would defeat the point of bounding the queue at all.
        assertTrue(snapshot.queuedSamples < 512, "queued ${snapshot.queuedSamples}")
    }

    @Test
    fun `a deeper plan buys more read-ahead`() {
        val shallow = AndroidMediaExtractorReadAheadNode(FakeExtractorSource(512, 100_000L))
        val deep = AndroidMediaExtractorReadAheadNode(FakeExtractorSource(512, 100_000L))
        listOf(shallow to 3_000_000L, deep to 12_000_000L).forEach { (node, targetUs) ->
            node.open(SOURCE)
            node.configureBufferPlan(targetAheadUs = targetUs, maximumBytes = 64L * 1024L * 1024L)
            node.selectTracks(setOf(VIDEO_TRACK))
            node.awaitBufferedDuration(targetUs)
        }

        assertTrue(
            deep.snapshot().bufferedDurationUs > shallow.snapshot().bufferedDurationUs,
            "deep ${deep.snapshot().bufferedDurationUs} shallow ${shallow.snapshot().bufferedDurationUs}",
        )
    }

    @Test
    fun `the byte ceiling bounds the queue independently of the time target`() {
        val extractor = FakeExtractorSource(sampleCount = 4_096, sampleDurationUs = 1_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        // An hour of look-ahead that only the byte ceiling can stop.
        node.configureBufferPlan(targetAheadUs = 3_600_000_000L, maximumBytes = 64L * 1024L)
        node.selectTracks(setOf(VIDEO_TRACK))
        node.awaitQueued(minimumSamples = 2)

        Thread.sleep(SETTLE_MS)
        assertTrue(node.snapshot().queuedBytes <= 64L * 1024L + SAMPLE_BYTES, "bytes ${node.snapshot().queuedBytes}")
    }

    @Test
    fun `an excluded track still yields samples of the other track`() {
        val extractor = FakeExtractorSource(sampleCount = 64, sampleDurationUs = 100_000L, interleaveAudio = true)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.selectTracks(setOf(VIDEO_TRACK, AUDIO_TRACK))
        node.awaitQueued(minimumSamples = 8)

        // A full video codec must never starve audio: this is the per-track backpressure path.
        val audio = node.pollSample(excludedTrackIndex = VIDEO_TRACK)
        assertTrue(audio is YQueuedExtractorResult.Sample)
        assertEquals(AUDIO_TRACK, (audio as YQueuedExtractorResult.Sample).value.trackIndex)
    }

    @Test
    fun `end of input is reported only after the queue drains`() {
        val extractor = FakeExtractorSource(sampleCount = 3, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.selectTracks(setOf(VIDEO_TRACK))
        node.awaitQueued(minimumSamples = 3)

        repeat(3) { assertTrue(node.pollSample() is YQueuedExtractorResult.Sample, "sample $it") }
        assertEquals(YQueuedExtractorResult.EndOfInput, node.awaitTerminal())
    }

    @Test
    fun `a returned sample is delivered again before the rest`() {
        val extractor = FakeExtractorSource(sampleCount = 16, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.selectTracks(setOf(VIDEO_TRACK))
        node.awaitQueued(minimumSamples = 4)

        val first = (node.pollSample() as YQueuedExtractorResult.Sample).value
        node.returnSample(first)
        val again = (node.pollSample() as YQueuedExtractorResult.Sample).value
        assertEquals(first.presentationTimeUs, again.presentationTimeUs)
    }

    @Test
    fun `seek drops everything the old position had queued`() {
        val extractor = FakeExtractorSource(sampleCount = 1_024, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.selectTracks(setOf(VIDEO_TRACK))
        node.awaitQueued(minimumSamples = 8)

        node.seekTo(60_000_000L)
        val resumed = node.awaitQueued(minimumSamples = 1)

        assertEquals(60_000_000L, extractor.lastSeekUs)
        assertTrue(resumed.queuedSamples > 0)
        // Every queued sample must come from the new position, not the abandoned one.
        val sample = (node.pollSample() as YQueuedExtractorResult.Sample).value
        assertTrue(sample.presentationTimeUs >= 60_000_000L, "pts ${sample.presentationTimeUs}")
    }

    @Test
    fun `startup starvation is not counted as a rebuffer`() {
        val extractor = FakeExtractorSource(sampleCount = 8, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.selectTracks(setOf(VIDEO_TRACK))

        // Before the first fill lands the queue is empty, which is startup latency.
        node.pollSample()
        assertEquals(0L, node.snapshot().starvationCount)
    }

    @Test
    fun `the staging buffer is allocated once, not once per fill`() {
        val extractor = FakeExtractorSource(sampleCount = 4_096, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.configureSampleCapacity(SAMPLE_BYTES * 4)
        node.selectTracks(setOf(VIDEO_TRACK))
        node.awaitQueued(minimumSamples = 8)

        // Draining forces a fill per consumed sample; a per-fill allocation would show up here as
        // one distinct staging buffer per sample.
        repeat(64) { node.pollSample() }
        Thread.sleep(SETTLE_MS)

        assertTrue(extractor.readCount.get() > 40, "reads ${extractor.readCount.get()}")
        assertEquals(1, extractor.distinctTargets.size, "staging buffers ${extractor.distinctTargets.size}")
    }

    @Test
    fun `release stops the owner and clears the queue`() {
        val extractor = FakeExtractorSource(sampleCount = 512, sampleDurationUs = 100_000L)
        val node = AndroidMediaExtractorReadAheadNode(extractor)
        node.open(SOURCE)
        node.selectTracks(setOf(VIDEO_TRACK))
        node.awaitQueued(minimumSamples = 8)

        node.close()

        assertTrue(extractor.released)
        assertEquals(0, node.snapshot().queuedSamples)
    }

    private fun AndroidMediaExtractorReadAheadNode.awaitQueued(minimumSamples: Int): YExtractorReadAheadSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val snapshot = snapshot()
            if (snapshot.queuedSamples >= minimumSamples) return snapshot
            Thread.sleep(2)
        }
        throw AssertionError("read-ahead never queued $minimumSamples samples: ${snapshot()}")
    }

    private fun AndroidMediaExtractorReadAheadNode.awaitBufferedDuration(targetUs: Long): YExtractorReadAheadSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val snapshot = snapshot()
            if (snapshot.bufferedDurationUs >= targetUs) return snapshot
            Thread.sleep(2)
        }
        throw AssertionError("read-ahead never reached ${targetUs}us: ${snapshot()}")
    }

    private fun AndroidMediaExtractorReadAheadNode.awaitTerminal(): YQueuedExtractorResult {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val result = pollSample()
            if (result !is YQueuedExtractorResult.Empty) return result
            Thread.sleep(2)
        }
        throw AssertionError("read-ahead never reached a terminal result")
    }

    private companion object {
        const val VIDEO_TRACK = 0
        const val AUDIO_TRACK = 1
        const val SAMPLE_BYTES = 1_024
        const val SETTLE_MS = 50L
        val SOURCE = YAndroidMediaSource(uri = "file:///fake")
    }
}

/** Deterministic stand-in for MediaExtractor: fixed-size samples on a fixed cadence. */
private class FakeExtractorSource(
    private val sampleCount: Int,
    private val sampleDurationUs: Long,
    private val interleaveAudio: Boolean = false,
) : YPlatformExtractorSource {
    override val name: String = "FakeExtractor"

    val readCount = AtomicInteger()
    val distinctTargets = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteBuffer, Boolean>())

    @Volatile
    var released = false

    @Volatile
    var lastSeekUs = 0L

    private val opened = CountDownLatch(1)
    private var index = 0
    private var selected = emptySet<Int>()

    override fun open(source: YAndroidMediaSource) {
        index = 0
        opened.countDown()
    }

    override val trackCount: Int get() = if (interleaveAudio) 2 else 1

    override fun trackFormat(index: Int): MediaFormat = MediaFormat()

    override fun findFirstTrack(mimePrefix: String): Int? = 0

    override fun readSourcePrefix(maximumBytes: Int): ByteArray? = null

    override fun drmInitializationData(schemeUuid: UUID): ByteArray? = null

    override fun setMediaBitRateBitsPerSecond(value: Long) = Unit

    override fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot? = null

    override fun blockedForegroundReadMs(): Long = 0L

    override fun selectTrack(index: Int) {
        selected = selected + index
    }

    override fun unselectTrack(index: Int) {
        selected = selected - index
    }

    override fun seekTo(positionUs: Long) {
        lastSeekUs = positionUs
        index = (positionUs / sampleDurationUs).toInt()
    }

    override fun readSample(target: ByteBuffer): YExtractorSample? {
        if (index >= sampleCount) return null
        readCount.incrementAndGet()
        synchronized(distinctTargets) { distinctTargets.add(target) }
        target.clear()
        repeat(SAMPLE_BYTES) { target.put(0) }
        target.position(0)
        target.limit(SAMPLE_BYTES)
        val track = if (interleaveAudio && index % 2 == 1) 1 else 0
        return YExtractorSample(
            trackIndex = track,
            data = target.slice(),
            presentationTimeUs = index * sampleDurationUs,
            flags = 1,
        )
    }

    override fun advance(): Boolean {
        index++
        return index < sampleCount
    }

    override fun flush() = Unit

    override fun release() {
        released = true
    }

    private companion object {
        const val SAMPLE_BYTES = 1_024
    }
}
