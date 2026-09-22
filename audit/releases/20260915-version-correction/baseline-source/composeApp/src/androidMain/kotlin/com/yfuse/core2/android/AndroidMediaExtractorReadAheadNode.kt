package com.yfuse.core2.android

import android.content.Context
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-owner MediaExtractor executor with a bounded compressed-sample queue.
 *
 * MediaExtractor and its MediaDataSource may block while resolving a remote byte range. Keeping
 * those calls off NativeDirect's codec/render pump lets already queued MediaCodec and AudioTrack
 * output continue while the next range is fetched. Seek and track selection are serialized through
 * the same owner because MediaExtractor is not thread-safe.
 */
internal class AndroidMediaExtractorReadAheadNode(
    @Volatile private var delegate: YPlatformExtractorSource,
) {
    constructor(
        context: Context,
        onBlockingReadStateChanged: ((Boolean) -> Unit)? = null,
    ) : this(
        AndroidMediaExtractorDemuxNode(
            context = context,
            onBlockingReadStateChanged = onBlockingReadStateChanged,
        ),
    )

    private val monitor = Any()
    private val samples = ArrayDeque<YExtractorSample>()
    private var executor: ExecutorService? = null
    private var opened = false
    private var readAheadEnabled = false
    private var selectedTracks = emptySet<Int>()
    private var bufferingTracks = emptySet<Int>()
    private var endOfInput = false
    private var failure: Throwable? = null
    private var fillScheduled = false
    private var sampleCapacity = DEFAULT_SAMPLE_CAPACITY_BYTES
    private var targetAheadUs = DEFAULT_HIGH_WATERMARK_US
    private var maximumQueueBytes = DEFAULT_MAXIMUM_QUEUE_BYTES
    private var memoryLease: PlaybackMemoryLease? = null

    private fun queueBudgetBytes() = minOf(maximumQueueBytes, memoryLease?.limitBytes ?: maximumQueueBytes)

    private var queuedBytes = 0L
    private var starvationCount = 0L
    private var starved = false
    private var hasDeliveredSample = false
    private var generation = 0L

    @Volatile
    private var latestTransportQoeSnapshot: YTransportPrefetchQoeSnapshot? = null

    private val transportQoeRefreshScheduled = AtomicBoolean(false)

    val name: String get() = delegate.name

    fun open(
        source: YAndroidMediaSource,
        preparedSource: YPlatformExtractorSource? = null,
    ) {
        synchronized(monitor) {
            if (memoryLease == null) {
                memoryLease = AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.Demux, 24L * 1024L * 1024L)
            }
            opened = false
            readAheadEnabled = false
            selectedTracks = emptySet()
            clearQueueLocked()
            latestTransportQoeSnapshot = null
            transportQoeRefreshScheduled.set(false)
        }
        runOnOwner {
            if (preparedSource == null) {
                delegate.open(source)
            } else {
                delegate.release()
                delegate = preparedSource
            }
            synchronized(monitor) {
                opened = true
                ownerSelectedTracks = emptySet()
                selectedTracks = emptySet()
                resetQueueStateLocked()
            }
        }
    }

    val trackCount: Int get() = runOnOwner { delegate.trackCount }

    fun trackFormat(index: Int): MediaFormat = runOnOwner { delegate.trackFormat(index) }

    fun findFirstTrack(mimePrefix: String): Int? = runOnOwner { delegate.findFirstTrack(mimePrefix) }

    fun readSourcePrefix(maximumBytes: Int): ByteArray? = runOnOwner { delegate.readSourcePrefix(maximumBytes) }

    fun drmInitializationData(schemeUuid: java.util.UUID): ByteArray? =
        runOnOwner { delegate.drmInitializationData(schemeUuid) }

    fun setMediaBitRateBitsPerSecond(value: Long) {
        runOnOwner { delegate.setMediaBitRateBitsPerSecond(value) }
        requestTransportQoeRefresh()
    }

    /**
     * Returns the last completed transport snapshot immediately.
     *
     * The transport MediaDataSource serializes foreground random-access reads. Calling its
     * synchronized QoE snapshot directly from the NativeDirect codec/render pump can therefore
     * make diagnostics wait behind a slow network Range request and stop MediaCodec/AudioTrack
     * draining. Refresh the snapshot on the MediaExtractor owner instead and keep playback-side
     * observation strictly non-blocking.
     */
    fun transportQoeSnapshot(): YTransportPrefetchQoeSnapshot? {
        requestTransportQoeRefresh()
        return latestTransportQoeSnapshot
    }

    /**
     * How long the foreground range fetch has been outstanding, read live rather than from
     * [latestTransportQoeSnapshot].
     *
     * The snapshot above is refreshed on the owner executor, which is the same single thread that
     * blocks inside the fetch, so a snapshot can never carry the duration of the stall that is
     * holding it up - it just goes stale. Reading the transport's volatile marker directly is what
     * makes a blocked pump visible in diagnostics.
     */
    fun blockedForegroundReadMs(): Long = delegate.blockedForegroundReadMs()

    fun cancelPendingRead() = delegate.cancelPendingRead()

    fun updatePlaybackWindow(window: YTransportPlaybackWindow) = delegate.updatePlaybackWindow(window)

    fun configureSampleCapacity(bytes: Int) {
        require(bytes > 0)
        synchronized(monitor) { sampleCapacity = bytes }
    }

    /**
     * Applies a [com.yfuse.core2.network.YBufferController] plan to the compressed queue.
     *
     * A fixed three-second watermark is a local-playback figure. The Enhanced session already
     * re-plans its read-ahead from measured throughput, so NativeDirect kept the shallowest queue
     * of the two exactly on the weak links where depth matters.
     */
    fun configureBufferPlan(
        targetAheadUs: Long,
        maximumBytes: Long,
    ) {
        require(targetAheadUs > 0L && maximumBytes > 0L)
        val fill =
            synchronized(monitor) {
                if (targetAheadUs == this.targetAheadUs && maximumBytes == maximumQueueBytes) {
                    return
                }
                this.targetAheadUs = targetAheadUs
                maximumQueueBytes = maximumBytes
                !queueAtHighWatermarkLocked()
            }
        if (fill) requestFill()
    }

    /**
     * Startup can select tracks without filling from the container's initial position. Metadata
     * reads and a resume seek must finish before [startReadAhead] queues any sample I/O; otherwise
     * those owner-thread commands wait behind a whole forward buffer that the seek discards.
     */
    fun selectTracks(
        trackIndices: Set<Int>,
        startReadAhead: Boolean = true,
        bufferingTrackIndices: Set<Int> = trackIndices,
    ) {
        synchronized(monitor) {
            readAheadEnabled = false
            selectedTracks = emptySet()
            clearQueueLocked()
        }
        delegate.cancelPendingRead()
        runOnOwner {
            val previous = synchronized(monitor) { ownerSelectedTracks }
            previous.minus(trackIndices).forEach(delegate::unselectTrack)
            trackIndices.minus(previous).forEach(delegate::selectTrack)
            synchronized(monitor) {
                ownerSelectedTracks = trackIndices
                selectedTracks = trackIndices
                bufferingTracks = bufferingTrackIndices.intersect(trackIndices)
                resetQueueStateLocked()
                readAheadEnabled = startReadAhead
            }
        }
        requestFill()
    }

    fun startReadAhead() {
        synchronized(monitor) {
            if (!opened) return
            readAheadEnabled = true
            requestFillLocked()
        }
    }

    fun pauseReadAhead() {
        synchronized(monitor) {
            readAheadEnabled = false
            generation++
        }
        delegate.cancelPendingRead()
    }

    fun seekTo(positionUs: Long) {
        val resume =
            synchronized(monitor) {
                val current = selectedTracks
                selectedTracks = emptySet()
                clearQueueLocked()
                current
            }
        delegate.cancelPendingRead()
        runOnOwner {
            delegate.seekTo(positionUs)
            synchronized(monitor) {
                selectedTracks = resume
                resetQueueStateLocked()
            }
        }
        requestFill()
    }

    fun pollSample(excludedTrackIndex: Int? = null): YQueuedExtractorResult {
        synchronized(monitor) {
            val sample =
                if (excludedTrackIndex == null) {
                    samples.pollFirst()
                } else {
                    val iterator = samples.iterator()
                    var selected: YExtractorSample? = null
                    while (iterator.hasNext()) {
                        val candidate = iterator.next()
                        if (candidate.trackIndex != excludedTrackIndex) {
                            iterator.remove()
                            selected = candidate
                            break
                        }
                    }
                    selected
                }
            if (sample != null) {
                queuedBytes = (queuedBytes - sample.data.remaining()).coerceAtLeast(0L)
                starved = false
                hasDeliveredSample = true
                requestFillLocked()
                return YQueuedExtractorResult.Sample(sample)
            }
            if (samples.isEmpty()) failure?.let { return YQueuedExtractorResult.Failed(it) }
            // End-of-input is terminal only after all samples, including samples for a temporarily
            // excluded/backpressured track, have been consumed.
            if (endOfInput && samples.isEmpty()) return YQueuedExtractorResult.EndOfInput
            if (samples.isNotEmpty()) return YQueuedExtractorResult.Empty
            if (!starved) {
                starved = true
                // The initial asynchronous fill is startup latency, not a rebuffer starvation.
                if (hasDeliveredSample) starvationCount++
            }
            requestFillLocked()
            return YQueuedExtractorResult.Empty
        }
    }

    fun returnSample(sample: YExtractorSample) {
        synchronized(monitor) {
            if (!opened || sample.queueGeneration != generation) return
            samples.addFirst(sample)
            queuedBytes += sample.data.remaining()
            starved = false
        }
    }

    fun snapshot(): YExtractorReadAheadSnapshot =
        synchronized(monitor) {
            val liveThroughput = delegate.liveTransportThroughput()
            YExtractorReadAheadSnapshot(
                queuedSamples = samples.size,
                queuedBytes = queuedBytes,
                bufferedDurationUs = bufferedDurationUsLocked(),
                starvationCount = starvationCount,
                starved = starved && samples.isEmpty() && !endOfInput,
                targetAheadUs = targetAheadUs,
                throughputBitsPerSecond = liveThroughput ?: latestTransportQoeSnapshot?.throughputBitsPerSecond ?: 0L,
                throughputMeasured = liveThroughput != null || latestTransportQoeSnapshot?.throughputMeasured == true,
                endOfInput = endOfInput || failure != null,
                atCapacity = samples.isNotEmpty() && queuedBytes >= queueBudgetBytes(),
                trackBufferedDurationUs = trackBufferedDurationsUsLocked(),
            )
        }

    fun release() {
        val owner =
            synchronized(monitor) {
                opened = false
                readAheadEnabled = false
                selectedTracks = emptySet()
                clearQueueLocked()
                memoryLease?.close()
                memoryLease = null
                latestTransportQoeSnapshot = null
                transportQoeRefreshScheduled.set(false)
                executor
            }
        if (owner != null) {
            delegate.cancelPendingRead()
            runCatching {
                runOnOwner {
                    delegate.release()
                    ownerStagingBuffer = null
                    synchronized(monitor) {
                        ownerSelectedTracks = emptySet()
                        resetQueueStateLocked()
                    }
                }
            }
        }
    }

    fun close() {
        release()
        val owner = synchronized(monitor) { executor.also { executor = null } }
        owner?.shutdownNow()
    }

    private var ownerSelectedTracks = emptySet<Int>()

    /** Owner-thread only. Never read or written from the playback pump. */
    private var ownerStagingBuffer: ByteBuffer? = null

    private fun requestFill() {
        synchronized(monitor) { requestFillLocked() }
    }

    private fun requestFillLocked() {
        if (
            !opened ||
            !readAheadEnabled ||
            selectedTracks.isEmpty() ||
            endOfInput ||
            failure != null ||
            fillScheduled ||
            queueAtHighWatermarkLocked()
        ) {
            return
        }
        fillScheduled = true
        owner().execute(::fillToHighWatermark)
    }

    private fun requestTransportQoeRefresh() {
        val owner =
            synchronized(monitor) {
                val activeOwner = executor
                if (
                    !opened ||
                    activeOwner == null ||
                    !transportQoeRefreshScheduled.compareAndSet(false, true)
                ) {
                    null
                } else {
                    activeOwner
                }
            } ?: return

        runCatching {
            owner.execute {
                try {
                    val snapshot = delegate.transportQoeSnapshot()
                    synchronized(monitor) {
                        if (opened) latestTransportQoeSnapshot = snapshot
                    }
                } finally {
                    transportQoeRefreshScheduled.set(false)
                }
            }
        }.onFailure {
            transportQoeRefreshScheduled.set(false)
        }
    }

    /**
     * Owner-thread staging buffer for [AndroidMediaExtractorDemuxNode.readSample].
     *
     * A fill runs whenever the queue drops below the watermark, which in steady state is about
     * once per consumed sample. Allocating the staging buffer per fill therefore meant tens of
     * multi-MiB `allocateDirect` calls per second - each of them zeroing the whole block and
     * leaving native memory for the collector to reclaim. It is owner-confined, so a plain field
     * that only grows is enough.
     */
    private fun stagingBuffer(capacity: Int): ByteBuffer {
        // readSample() clears the target itself, so a buffer that is large enough is reusable as is.
        ownerStagingBuffer?.takeIf { it.capacity() >= capacity }?.let { return it }
        return ByteBuffer.allocateDirect(capacity).also { ownerStagingBuffer = it }
    }

    private fun fillToHighWatermark() {
        var readingGeneration = synchronized(monitor) { generation }
        try {
            val capacity = synchronized(monitor) { sampleCapacity }
            val buffer = stagingBuffer(capacity)
            while (true) {
                synchronized(monitor) {
                    if (
                        !opened ||
                        !readAheadEnabled ||
                        selectedTracks.isEmpty() ||
                        endOfInput ||
                        failure != null ||
                        queueAtHighWatermarkLocked()
                    ) {
                        return
                    }
                    readingGeneration = generation
                }
                val extracted = delegate.readSample(buffer)
                if (synchronized(monitor) { generation != readingGeneration }) return
                val copied =
                    extracted?.let { sample ->
                        val bytes = ByteArray(sample.data.remaining())
                        sample.data.duplicate().get(bytes)
                        sample.copy(data = ByteBuffer.wrap(bytes), queueGeneration = readingGeneration)
                    }
                if (copied != null) delegate.advance()
                synchronized(monitor) {
                    if (!opened ||
                        !readAheadEnabled ||
                        selectedTracks.isEmpty() ||
                        generation != readingGeneration
                    ) {
                        return
                    }
                    if (copied == null) {
                        endOfInput = true
                        return
                    }
                    samples.addLast(copied)
                    queuedBytes += copied.data.remaining()
                    starved = false
                }
            }
        } catch (throwable: Throwable) {
            synchronized(monitor) {
                if (generation == readingGeneration) {
                    failure = throwable
                    endOfInput = true
                }
            }
        } finally {
            synchronized(monitor) {
                fillScheduled = false
                if (!queueAtHighWatermarkLocked()) requestFillLocked()
            }
        }
    }

    private fun queueAtHighWatermarkLocked(): Boolean =
        (samples.isNotEmpty() && queuedBytes >= queueBudgetBytes()) ||
            (samples.size >= MINIMUM_SAMPLES_BEFORE_TIME_LIMIT && bufferedDurationUsLocked() >= targetAheadUs)

    private fun bufferedDurationUsLocked(): Long = trackBufferedDurationsUsLocked().values.minOrNull() ?: 0L

    private fun trackBufferedDurationsUsLocked(): Map<Int, Long> {
        val bounds = bufferingTracks.associateWith { longArrayOf(Long.MAX_VALUE, Long.MIN_VALUE) }
        samples.forEach { sample ->
            bounds[sample.trackIndex]?.let { range ->
                range[0] = minOf(range[0], sample.presentationTimeUs)
                range[1] = maxOf(range[1], sample.presentationTimeUs)
            }
        }
        return bounds.mapValues { (_, range) ->
            if (range[0] == Long.MAX_VALUE) 0L else (range[1] - range[0]).coerceAtLeast(0L)
        }
    }

    private fun resetQueueStateLocked() {
        clearQueueLocked()
        endOfInput = false
        failure = null
        starved = false
        hasDeliveredSample = false
    }

    private fun clearQueueLocked() {
        generation++
        samples.clear()
        queuedBytes = 0L
        starved = false
    }

    private fun owner(): ExecutorService =
        synchronized(monitor) {
            executor ?: Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "$EXTRACTOR_THREAD_NAME-${threadIndex.incrementAndGet()}").apply {
                        priority = Thread.NORM_PRIORITY + 1
                        isDaemon = true
                    }
                }.also { executor = it }
        }

    private fun <T> runOnOwner(block: () -> T): T = await(owner().submit(Callable(block)))

    private fun <T> await(future: Future<T>): T {
        try {
            return future.get()
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
    }

    private companion object {
        val threadIndex = AtomicInteger()
    }
}

internal sealed interface YQueuedExtractorResult {
    data class Sample(
        val value: YExtractorSample,
    ) : YQueuedExtractorResult

    data class Failed(
        val cause: Throwable,
    ) : YQueuedExtractorResult

    data object Empty : YQueuedExtractorResult

    data object EndOfInput : YQueuedExtractorResult
}

internal data class YExtractorReadAheadSnapshot(
    val queuedSamples: Int,
    val queuedBytes: Long,
    val bufferedDurationUs: Long,
    val starvationCount: Long,
    val starved: Boolean,
    val targetAheadUs: Long = DEFAULT_HIGH_WATERMARK_US,
    /** Aggregate transport throughput, or 0 before the first measured busy period. */
    val throughputBitsPerSecond: Long = 0L,
    val throughputMeasured: Boolean = false,
    val endOfInput: Boolean = false,
    val atCapacity: Boolean = false,
    val trackBufferedDurationUs: Map<Int, Long> = emptyMap(),
)

private const val EXTRACTOR_THREAD_NAME = "YCore-PlatformDemux"
private const val DEFAULT_SAMPLE_CAPACITY_BYTES = 8 * 1024 * 1024
private const val MINIMUM_SAMPLES_BEFORE_TIME_LIMIT = 8
private const val DEFAULT_HIGH_WATERMARK_US = 3_000_000L
private const val DEFAULT_MAXIMUM_QUEUE_BYTES = 24L * 1024L * 1024L
