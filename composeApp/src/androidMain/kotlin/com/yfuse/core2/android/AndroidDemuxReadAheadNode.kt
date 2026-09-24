package com.yfuse.core2.android

import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YSubtitlePacketDecoder
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YSubtitleDecodeResult
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import kotlinx.coroutines.CancellationException
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-owner demux executor with a bounded compressed-sample queue.
 *
 * Network reads and FFmpeg packetization never run on the codec/render pump. Control operations are
 * serialized through the same owner so AVFormatContext is not raced by seek, track switch or close.
 */
internal class AndroidDemuxReadAheadNode(
    private val delegate: YDemuxer,
    private val controlTimeoutMs: Long = 1_500L,
    /** Deterministic test barrier outside the queue lock, at the fill/consumer handoff. */
    private val beforeFillFinished: (() -> Unit)? = null,
) {
    private val monitor = Any()
    private val samples = ArrayDeque<YQueuedDemuxResult.Sample>()
    private var nativeSubtitleTracks = emptySet<YTrackId>()
    private var subtitleTracks = emptySet<YTrackId>()
    private var generation = 0L
    private var executor: ExecutorService? = null
    private var opened = false
    private var tracksSelected = false
    private var endOfInput = false
    private var failure: Throwable? = null
    private var fillScheduled = false
    private var selectedTrackIds = emptySet<YTrackId>()
    private var readStartedNs = 0L
    private var lastPacketNs = 0L
    private var packetsRead = 0L
    private var queuedBytes = 0L
    private var lowWatermarkUs = DEFAULT_LOW_WATERMARK_US
    private var highWatermarkUs = DEFAULT_HIGH_WATERMARK_US
    private var maximumQueueBytes = DEFAULT_MAXIMUM_QUEUE_BYTES
    private var memoryLease: PlaybackMemoryLease? = null

    private fun queueBudgetBytes() = minOf(maximumQueueBytes, memoryLease?.limitBytes ?: maximumQueueBytes)

    private var maximumQueuedBytesObserved = 0L
    private var starvationCount = 0L
    private val throughput = DemuxFillThroughput()

    val name: String get() = delegate.name

    fun open(
        source: YDemuxSource,
        budget: AndroidProbeBudget? = null,
    ): YDemuxOpenResult =
        runOnOwner {
            budget?.ensureActive()
            val result = if (delegate is AndroidFfmpegDemuxer) delegate.open(source, budget) else delegate.open(source)
            result.also {
                budget?.ensureActive()
                synchronized(monitor) {
                    opened = true
                    tracksSelected = false
                    clearQueueLocked()
                    endOfInput = false
                    failure = null
                    configureSubtitleTracks(result)
                }
            }
        }

    /** Only signal the native interrupt flag here; all context destruction stays on the owner. */
    fun cancelPendingRead() {
        (delegate as? AndroidDemuxReadControl)?.cancelPendingRead()
    }

    /** Transfers an already-open, idle demuxer to this owner without repeating source analysis. */
    fun adoptOpen(result: YDemuxOpenResult): YDemuxOpenResult =
        runOnOwner {
            synchronized(monitor) {
                opened = true
                tracksSelected = false
                clearQueueLocked()
                endOfInput = false
                failure = null
                configureSubtitleTracks(result)
            }
            result
        }

    private fun configureSubtitleTracks(result: YDemuxOpenResult) {
        if (memoryLease == null) {
            memoryLease = AndroidPlaybackMemoryBudget.acquire(PlaybackBufferKind.Demux, MAXIMUM_QUEUE_BYTES)
        }
        val decoder = delegate as? YSubtitlePacketDecoder
        subtitleTracks = result.tracks.filter { it.subtitle != null }.mapTo(mutableSetOf()) { it.id }
        nativeSubtitleTracks =
            result.tracks
                .filter { track ->
                    track.subtitle?.format?.let { decoder?.supportsSubtitleFormat(it) } == true
                }.mapTo(mutableSetOf()) { it.id }
    }

    fun configure(
        targetAheadUs: Long,
        mediaBitRateBitsPerSecond: Long?,
        memoryBudgetBytes: Long = MAXIMUM_QUEUE_BYTES,
    ) {
        val high = targetAheadUs.coerceIn(MINIMUM_HIGH_WATERMARK_US, MAXIMUM_HIGH_WATERMARK_US)
        val estimatedBytes =
            mediaBitRateBitsPerSecond
                ?.takeIf { it > 0L }
                ?.let { bitsPerSecond ->
                    (bitsPerSecond / BITS_PER_BYTE)
                        .coerceAtMost(Long.MAX_VALUE / high) * high / MICROS_PER_SECOND
                }
        synchronized(monitor) {
            highWatermarkUs = high
            lowWatermarkUs = (high / 2L).coerceAtLeast(MINIMUM_LOW_WATERMARK_US)
            maximumQueueBytes =
                estimatedBytes
                    ?.times(QUEUE_HEADROOM_NUMERATOR)
                    ?.div(QUEUE_HEADROOM_DENOMINATOR)
                    ?.coerceIn(MINIMUM_QUEUE_BYTES, MAXIMUM_QUEUE_BYTES)
                    ?: DEFAULT_MAXIMUM_QUEUE_BYTES
            maximumQueueBytes = maximumQueueBytes.coerceAtMost(memoryBudgetBytes.coerceAtLeast(1L))
        }
        requestFill()
    }

    fun selectTracks(
        trackIds: Set<YTrackId>,
        positionUs: Long? = null,
        controlTimeoutMs: Long = this.controlTimeoutMs,
    ) {
        runReadControl(resumeReadAhead = trackIds.isNotEmpty(), timeoutMs = controlTimeoutMs) {
            delegate.selectTracks(trackIds)
            positionUs?.let(delegate::seekTo)
            synchronized(monitor) { selectedTrackIds = trackIds.toSet() }
        }
    }

    fun pollSample(excludedTrackIds: Set<YTrackId> = emptySet()): YQueuedDemuxResult {
        synchronized(monitor) {
            failure?.let { return YQueuedDemuxResult.Failed(it) }
            val iterator = samples.iterator()
            var sample: YQueuedDemuxResult.Sample? = null
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.value.trackId !in excludedTrackIds) {
                    sample = candidate
                    iterator.remove()
                    break
                }
            }
            if (sample != null) {
                queuedBytes = (queuedBytes - sample.memoryBytes).coerceAtLeast(0L)
                if (bufferedDurationUsLocked() <= lowWatermarkUs) requestFillLocked()
                return sample
            }
            if (endOfInput && samples.isEmpty()) return YQueuedDemuxResult.EndOfInput
            if (samples.isNotEmpty()) return YQueuedDemuxResult.Empty
            starvationCount++
            requestFillLocked()
            return YQueuedDemuxResult.Empty
        }
    }

    fun seekTo(
        positionUs: Long,
        controlTimeoutMs: Long = this.controlTimeoutMs,
    ) {
        val resumeReadAhead = synchronized(monitor) { tracksSelected }
        runReadControl(resumeReadAhead, timeoutMs = controlTimeoutMs) { delegate.seekTo(positionUs) }
    }

    fun supportsSubtitleFormat(format: YSubtitleFormat): Boolean =
        (delegate as? YSubtitlePacketDecoder)?.supportsSubtitleFormat(format) == true

    fun snapshot(includeTrackDetails: Boolean = false): YDemuxReadAheadSnapshot =
        synchronized(monitor) {
            YDemuxReadAheadSnapshot(
                queuedSamples = samples.size,
                queuedBytes = queuedBytes,
                bufferedDurationUs = bufferedDurationUsLocked(),
                maximumQueuedBytesObserved = maximumQueuedBytesObserved,
                starvationCount = starvationCount,
                throughputBitsPerSecond = throughput.snapshot(System.nanoTime(), fillScheduled),
                endOfInput = endOfInput,
                atCapacity = samples.isNotEmpty() && queuedBytes >= queueBudgetBytes(),
                fillScheduled = fillScheduled,
                readElapsedMs = if (readStartedNs == 0L) 0L else (System.nanoTime() - readStartedNs) / 1_000_000L,
                lastPacketAgeMs = if (lastPacketNs == 0L) -1L else (System.nanoTime() - lastPacketNs) / 1_000_000L,
                packetsRead = packetsRead,
                generation = generation,
                trackBufferedUs =
                    if (includeTrackDetails) {
                        selectedTrackIds.filter { it !in subtitleTracks }.associate { id ->
                            val queued = samples.filter { it.value.trackId == id }
                            val first = queued.minOfOrNull { it.value.presentationTimeUs }
                            val last = queued.maxOfOrNull { it.value.presentationTimeUs + (it.value.durationUs ?: 0L) }
                            id.value to if (first == null || last == null) 0L else (last - first).coerceAtLeast(0L)
                        }
                    } else {
                        emptyMap()
                    },
            )
        }

    /** A closed output gate must still observe source failures and keep its producer alive. */
    fun ensureReadAhead() {
        synchronized(monitor) {
            failure?.let { throw it }
            requestFillLocked()
        }
    }

    fun close() {
        cancelPendingRead()
        val owner =
            synchronized(monitor) {
                opened = false
                tracksSelected = false
                clearQueueLocked()
                memoryLease?.close()
                memoryLease = null
                executor
            }
        if (owner != null) {
            runCatching {
                runOnOwner {
                    delegate.close()
                    synchronized(monitor) {
                        opened = false
                        tracksSelected = false
                        clearQueueLocked()
                        endOfInput = false
                        failure = null
                    }
                }
            }
        }
    }

    /** Stops packet reads and waits until the demux owner reaches a safe native-session barrier. */
    fun pauseReadAhead() {
        if (synchronized(monitor) { executor == null }) return
        runReadControl(resumeReadAhead = false) {}
    }

    /** Cleanup must wait for ownership; a timeout never authorizes native or codec destruction. */
    fun awaitReleaseBarrier() {
        val owner =
            synchronized(monitor) {
                tracksSelected = false
                clearQueueLocked()
                executor
            } ?: return
        await(owner.submit(Callable { Unit }))
    }

    private fun runReadControl(
        resumeReadAhead: Boolean,
        timeoutMs: Long = controlTimeoutMs,
        block: () -> Unit,
    ) {
        require(timeoutMs > 0L)
        val request =
            synchronized(monitor) {
                tracksSelected = false
                clearQueueLocked()
                generation
            }
        // [timeoutMs] bounds only the wait for the owner to reach this control. The control's own
        // seek or track switch reads the network too (Matroska Cues, the target cluster); on a slow
        // link that alone took several seconds, so a 1.5 s limit over the whole operation failed
        // ordinary seeks and subtitle switches as network errors while the source was still fine.
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var started = false
        val interruptible = delegate as? AndroidDemuxReadControl
        interruptible?.interruptRead(request)
        val control =
            owner().submit(
                Callable {
                    synchronized(monitor) {
                        check(generation == request && System.nanoTime() < deadlineNs) { "Demux control superseded" }
                        started = true
                    }
                    check(interruptible?.resumeRead(request) != false) { "Demux read cannot resume after cancellation" }
                    block()
                    synchronized(monitor) {
                        check(generation == request) { "Demux control superseded" }
                        clearQueueLocked()
                        tracksSelected = resumeReadAhead
                        endOfInput = false
                        failure = null
                    }
                },
            )
        try {
            try {
                control.get((deadlineNs - System.nanoTime()).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
            } catch (barrierTimeout: TimeoutException) {
                // Decided under the monitor: a control that has not started can no longer start.
                if (!synchronized(monitor) { started }) throw barrierTimeout
                control.get(CONTROL_IO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        } catch (timeout: TimeoutException) {
            val expired =
                synchronized(monitor) {
                    if (generation == request) {
                        clearQueueLocked()
                        generation
                    } else {
                        null
                    }
                }
            expired?.let { interruptible?.interruptRead(it) }
            control.cancel(false)
            throw DemuxControlTimeoutException(timeout)
        } catch (failure: ExecutionException) {
            throw failure.cause ?: failure
        }
        requestFill()
    }

    fun release() {
        close()
        val owner = synchronized(monitor) { executor.also { executor = null } }
        owner?.shutdownNow()
    }

    private fun requestFill() {
        synchronized(monitor) { requestFillLocked() }
    }

    private fun requestFillLocked() {
        if (
            !opened ||
            !tracksSelected ||
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

    private fun fillToHighWatermark() {
        val fillStartedNs = System.nanoTime()
        synchronized(monitor) { throughput.start(fillStartedNs) }
        var readGeneration = -1L
        var cancelled = false
        try {
            while (true) {
                readGeneration =
                    synchronized(monitor) {
                        if (
                            !opened ||
                            !tracksSelected ||
                            endOfInput ||
                            failure != null ||
                            queueAtHighWatermarkLocked()
                        ) {
                            return
                        }
                        readStartedNs = System.nanoTime()
                        generation
                    }
                val sample = delegate.readSample()
                // Decode on the native owner before publishing the packet. The codec pump only
                // consumes completed cues and never waits behind a blocking read on this executor.
                val queued =
                    sample?.let {
                        YQueuedDemuxResult.Sample(
                            value = it,
                            subtitleResult =
                                if (it.trackId in nativeSubtitleTracks) {
                                    com.yfuse.core2.api.yPlaybackStage(
                                        category = com.yfuse.core2.api.YPlaybackFailureCategory.Container,
                                        stage = com.yfuse.core2.api.YPlaybackFailureStage.Bitstream,
                                        safeDetail = "Enhanced native subtitle decode",
                                    ) { (delegate as YSubtitlePacketDecoder).decodeSubtitle(it) }
                                } else {
                                    null
                                },
                        )
                    }
                synchronized(monitor) {
                    readStartedNs = 0L
                    if (!opened || readGeneration != generation) return
                    if (queued == null) {
                        endOfInput = true
                        return
                    }
                    samples.addLast(queued)
                    lastPacketNs = System.nanoTime()
                    packetsRead++
                    queuedBytes += queued.memoryBytes
                    throughput.record(
                        queued.value.data.size
                            .toLong(),
                        lastPacketNs,
                    )
                    maximumQueuedBytesObserved = maxOf(maximumQueuedBytesObserved, queuedBytes)
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                cancelled = true
                throw throwable
            }
            synchronized(monitor) {
                if (opened && readGeneration == generation) {
                    failure = throwable
                    endOfInput = true
                }
            }
        } finally {
            beforeFillFinished?.invoke()
            synchronized(monitor) {
                if (readGeneration == generation) throughput.finish(System.nanoTime())
                fillScheduled = false
                readStartedNs = 0L
                // A consumer can drain the queue after the high-water check but before this
                // handoff. Clear and recheck atomically so its refill request is never lost.
                if (!cancelled) requestFillLocked()
            }
        }
    }

    private fun queueAtHighWatermarkLocked(): Boolean =
        (samples.isNotEmpty() && queuedBytes >= queueBudgetBytes()) ||
            (samples.size >= MINIMUM_SAMPLES_BEFORE_TIME_LIMIT && bufferedDurationUsLocked() >= highWatermarkUs)

    private fun bufferedDurationUsLocked(): Long {
        if (samples.size < 2) return 0L
        val playbackTracks = selectedTrackIds.filterNot { it in subtitleTracks }
        if (playbackTracks.isEmpty()) return 0L
        var shortest = Long.MAX_VALUE
        for (trackId in playbackTracks) {
            var first = Long.MAX_VALUE
            var last = Long.MIN_VALUE
            for (queued in samples) {
                val sample = queued.value
                if (sample.trackId != trackId) continue
                first = minOf(first, sample.presentationTimeUs)
                last = maxOf(last, sample.presentationTimeUs + (sample.durationUs ?: 0L))
            }
            // A video span cannot stand in for an empty audio queue, or vice versa.
            if (first == Long.MAX_VALUE) return 0L
            shortest = minOf(shortest, (last - first).coerceAtLeast(0L))
        }
        return shortest
    }

    private fun clearQueueLocked() {
        generation++
        samples.clear()
        queuedBytes = 0L
        lastPacketNs = 0L
        throughput.reset()
    }

    private fun owner(): ExecutorService =
        synchronized(monitor) {
            executor ?: Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "$DEMUX_THREAD_NAME-${threadIndex.incrementAndGet()}").apply {
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

/** Reports packet progress while a fill is active, even if its next remote read is blocked. */
internal class DemuxFillThroughput {
    private var committedBitsPerSecond = 0L
    private var windowStartedNs = 0L
    private var windowBytes = 0L

    fun reset() {
        committedBitsPerSecond = 0L
        windowStartedNs = 0L
        windowBytes = 0L
    }

    fun start(nowNs: Long) {
        windowStartedNs = nowNs
        windowBytes = 0L
    }

    fun record(
        bytes: Long,
        nowNs: Long,
    ) {
        windowBytes += bytes.coerceAtLeast(0L)
        if (nowNs - windowStartedNs >= MINIMUM_THROUGHPUT_SAMPLE_NS) commit(nowNs)
    }

    fun snapshot(
        nowNs: Long,
        fillScheduled: Boolean,
    ): Long {
        if (!fillScheduled || windowStartedNs == 0L) return committedBitsPerSecond
        val elapsedNs = nowNs - windowStartedNs
        if (elapsedNs < MINIMUM_THROUGHPUT_SAMPLE_NS) return committedBitsPerSecond
        // An in-flight packet read must not leave a previously healthy rate looking current.
        if (windowBytes == 0L) {
            return if (elapsedNs >= STALLED_THROUGHPUT_WINDOW_NS) 0L else committedBitsPerSecond
        }
        return rate(windowBytes, elapsedNs)
    }

    fun finish(nowNs: Long) {
        if (windowStartedNs != 0L && nowNs - windowStartedNs >= MINIMUM_THROUGHPUT_SAMPLE_NS) {
            commit(nowNs)
        }
        windowStartedNs = 0L
        windowBytes = 0L
    }

    private fun commit(nowNs: Long) {
        if (windowBytes > 0L) {
            val measured = rate(windowBytes, nowNs - windowStartedNs)
            committedBitsPerSecond =
                when {
                    committedBitsPerSecond == 0L || measured < committedBitsPerSecond -> measured
                    else ->
                        (
                            committedBitsPerSecond * THROUGHPUT_HISTORY_WEIGHT +
                                measured * THROUGHPUT_SAMPLE_WEIGHT
                        ) / THROUGHPUT_TOTAL_WEIGHT
                }
        }
        windowStartedNs = nowNs
        windowBytes = 0L
    }

    private fun rate(
        bytes: Long,
        elapsedNs: Long,
    ): Long =
        ((bytes.toDouble() * BITS_PER_BYTE * NANOS_PER_SECOND) / elapsedNs.coerceAtLeast(1L))
            .coerceAtMost(Long.MAX_VALUE.toDouble())
            .toLong()
}

/** The owner did not reach its safe control barrier before the bounded wait expired. */
internal class DemuxControlTimeoutException(
    cause: TimeoutException,
) : IllegalStateException("Demux control timed out while its owner was busy", cause)

internal sealed interface YQueuedDemuxResult {
    data class Sample(
        val value: YCompressedSample,
        val subtitleResult: YSubtitleDecodeResult? = null,
    ) : YQueuedDemuxResult {
        val memoryBytes: Long =
            value.data.size.toLong() +
                subtitleResult?.cues.orEmpty().sumOf { cue ->
                    when (val payload = cue.payload) {
                        is YSubtitlePayload.BitmapArgb -> payload.pixels.size.toLong() * 4L
                        is YSubtitlePayload.Encoded -> payload.data.size.toLong()
                        is YSubtitlePayload.AssEvent -> payload.packet?.size?.toLong() ?: 0L
                        is YSubtitlePayload.Text ->
                            (payload.plainText.length + payload.sourceMarkup.length).toLong() *
                                2L
                    }
                }
    }

    data class Failed(
        val cause: Throwable,
    ) : YQueuedDemuxResult

    data object Empty : YQueuedDemuxResult

    data object EndOfInput : YQueuedDemuxResult
}

internal data class YDemuxReadAheadSnapshot(
    val queuedSamples: Int,
    val queuedBytes: Long,
    val bufferedDurationUs: Long,
    val maximumQueuedBytesObserved: Long,
    val starvationCount: Long,
    val throughputBitsPerSecond: Long,
    val endOfInput: Boolean,
    val atCapacity: Boolean = false,
    val fillScheduled: Boolean = false,
    val readElapsedMs: Long = 0L,
    val lastPacketAgeMs: Long = -1L,
    val packetsRead: Long = 0L,
    val generation: Long = 0L,
    val trackBufferedUs: Map<Int, Long> = emptyMap(),
)

private const val DEMUX_THREAD_NAME = "YCore-Demux"
private const val BITS_PER_BYTE = 8L
private const val MICROS_PER_SECOND = 1_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val MINIMUM_THROUGHPUT_SAMPLE_NS = 50_000_000L
private const val STALLED_THROUGHPUT_WINDOW_NS = 2_000_000_000L

/** A started seek/track switch may read the network; FFmpeg bounds each read with rw_timeout=15 s. */
private const val CONTROL_IO_TIMEOUT_MS = 20_000L
private const val THROUGHPUT_HISTORY_WEIGHT = 3L
private const val THROUGHPUT_SAMPLE_WEIGHT = 1L
private const val THROUGHPUT_TOTAL_WEIGHT = 4L
private const val QUEUE_HEADROOM_NUMERATOR = 3L
private const val QUEUE_HEADROOM_DENOMINATOR = 2L
private const val MINIMUM_SAMPLES_BEFORE_TIME_LIMIT = 8
private const val MINIMUM_LOW_WATERMARK_US = 500_000L
private const val MINIMUM_HIGH_WATERMARK_US = 1_000_000L
private const val MAXIMUM_HIGH_WATERMARK_US = 30_000_000L
private const val DEFAULT_LOW_WATERMARK_US = 1_500_000L
private const val DEFAULT_HIGH_WATERMARK_US = 3_000_000L
private const val MINIMUM_QUEUE_BYTES = 4L * 1024L * 1024L
private const val DEFAULT_MAXIMUM_QUEUE_BYTES = 24L * 1024L * 1024L
private const val MAXIMUM_QUEUE_BYTES = 64L * 1024L * 1024L
