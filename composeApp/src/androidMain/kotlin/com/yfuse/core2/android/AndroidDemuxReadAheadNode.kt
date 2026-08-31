package com.yfuse.core2.android

import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxer
import com.yfuse.core2.demux.YSubtitlePacketDecoder
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YSubtitleCue
import com.yfuse.core2.subtitle.YSubtitleFormat
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-owner demux executor with a bounded compressed-sample queue.
 *
 * Network reads and FFmpeg packetization never run on the codec/render pump. Control operations are
 * serialized through the same owner so AVFormatContext is not raced by seek, track switch or close.
 */
internal class AndroidDemuxReadAheadNode(
    private val delegate: YDemuxer,
) : YSubtitlePacketDecoder {
    private val monitor = Any()
    private val samples = ArrayDeque<YCompressedSample>()
    private var executor: ExecutorService? = null
    private var opened = false
    private var tracksSelected = false
    private var endOfInput = false
    private var failure: Throwable? = null
    private var fillScheduled = false
    private var queuedBytes = 0L
    private var lowWatermarkUs = DEFAULT_LOW_WATERMARK_US
    private var highWatermarkUs = DEFAULT_HIGH_WATERMARK_US
    private var maximumQueueBytes = DEFAULT_MAXIMUM_QUEUE_BYTES
    private var maximumQueuedBytesObserved = 0L
    private var starvationCount = 0L

    val name: String get() = delegate.name

    fun open(source: YDemuxSource): YDemuxOpenResult =
        runOnOwner {
            delegate.open(source).also {
                synchronized(monitor) {
                    opened = true
                    tracksSelected = false
                    clearQueueLocked()
                    endOfInput = false
                    failure = null
                }
            }
        }

    fun configure(
        targetAheadUs: Long,
        mediaBitRateBitsPerSecond: Long?,
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
        }
        requestFill()
    }

    fun selectTracks(trackIds: Set<YTrackId>) {
        synchronized(monitor) {
            tracksSelected = false
            clearQueueLocked()
        }
        runOnOwner {
            delegate.selectTracks(trackIds)
            synchronized(monitor) {
                tracksSelected = trackIds.isNotEmpty()
                clearQueueLocked()
                endOfInput = false
                failure = null
            }
        }
        requestFill()
    }

    fun pollSample(): YQueuedDemuxResult {
        synchronized(monitor) {
            failure?.let { return YQueuedDemuxResult.Failed(it) }
            val sample = samples.pollFirst()
            if (sample != null) {
                queuedBytes = (queuedBytes - sample.data.size).coerceAtLeast(0L)
                if (bufferedDurationUsLocked() <= lowWatermarkUs) requestFillLocked()
                return YQueuedDemuxResult.Sample(sample)
            }
            if (endOfInput) return YQueuedDemuxResult.EndOfInput
            starvationCount++
            requestFillLocked()
            return YQueuedDemuxResult.Empty
        }
    }

    fun seekTo(positionUs: Long) {
        val resumeReadAhead =
            synchronized(monitor) {
                val selected = tracksSelected
                tracksSelected = false
                clearQueueLocked()
                selected
            }
        runOnOwner {
            delegate.seekTo(positionUs)
            synchronized(monitor) {
                tracksSelected = resumeReadAhead
                clearQueueLocked()
                endOfInput = false
                failure = null
            }
        }
        requestFill()
    }

    override fun supportsSubtitleFormat(format: YSubtitleFormat): Boolean =
        (delegate as? YSubtitlePacketDecoder)?.supportsSubtitleFormat(format) == true

    override fun decodeSubtitle(sample: YCompressedSample): List<YSubtitleCue> =
        runOnOwner {
            val decoder = delegate as? YSubtitlePacketDecoder
                ?: error("The active demuxer has no native subtitle decoder")
            decoder.decodeSubtitle(sample)
        }

    fun snapshot(): YDemuxReadAheadSnapshot =
        synchronized(monitor) {
            YDemuxReadAheadSnapshot(
                queuedSamples = samples.size,
                queuedBytes = queuedBytes,
                bufferedDurationUs = bufferedDurationUsLocked(),
                maximumQueuedBytesObserved = maximumQueuedBytesObserved,
                starvationCount = starvationCount,
                endOfInput = endOfInput,
            )
        }

    fun close() {
        val owner =
            synchronized(monitor) {
                opened = false
                tracksSelected = false
                clearQueueLocked()
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
        val owner =
            synchronized(monitor) {
                tracksSelected = false
                clearQueueLocked()
                executor
            } ?: return
        await(owner.submit(Callable { Unit }))
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
        try {
            while (true) {
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
                }
                val sample = delegate.readSample()
                synchronized(monitor) {
                    if (!opened) return
                    if (sample == null) {
                        endOfInput = true
                        return
                    }
                    samples.addLast(sample)
                    queuedBytes += sample.data.size
                    maximumQueuedBytesObserved = maxOf(maximumQueuedBytesObserved, queuedBytes)
                }
            }
        } catch (throwable: Throwable) {
            synchronized(monitor) {
                failure = throwable
                endOfInput = true
            }
        } finally {
            synchronized(monitor) {
                fillScheduled = false
            }
        }
    }

    private fun queueAtHighWatermarkLocked(): Boolean =
        queuedBytes >= maximumQueueBytes ||
            (samples.size >= MINIMUM_SAMPLES_BEFORE_TIME_LIMIT && bufferedDurationUsLocked() >= highWatermarkUs)

    private fun bufferedDurationUsLocked(): Long {
        if (samples.size < 2) return 0L
        var minimum = Long.MAX_VALUE
        var maximum = Long.MIN_VALUE
        samples.forEach { sample ->
            minimum = minOf(minimum, sample.presentationTimeUs)
            maximum = maxOf(maximum, sample.presentationTimeUs + (sample.durationUs ?: 0L))
        }
        return (maximum - minimum).coerceAtLeast(0L)
    }

    private fun clearQueueLocked() {
        samples.clear()
        queuedBytes = 0L
    }

    private fun owner(): ExecutorService =
        synchronized(monitor) {
            executor ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "$DEMUX_THREAD_NAME-${threadIndex.incrementAndGet()}").apply {
                    priority = Thread.NORM_PRIORITY + 1
                    isDaemon = true
                }
            }.also { executor = it }
        }

    private fun <T> runOnOwner(block: () -> T): T {
        return await(owner().submit(Callable(block)))
    }

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

internal sealed interface YQueuedDemuxResult {
    data class Sample(
        val value: YCompressedSample,
    ) : YQueuedDemuxResult

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
    val endOfInput: Boolean,
)

private const val DEMUX_THREAD_NAME = "YCore-Demux"
private const val BITS_PER_BYTE = 8L
private const val MICROS_PER_SECOND = 1_000_000L
private const val QUEUE_HEADROOM_NUMERATOR = 3L
private const val QUEUE_HEADROOM_DENOMINATOR = 2L
private const val MINIMUM_SAMPLES_BEFORE_TIME_LIMIT = 8
private const val MINIMUM_LOW_WATERMARK_US = 500_000L
private const val MINIMUM_HIGH_WATERMARK_US = 1_000_000L
private const val MAXIMUM_HIGH_WATERMARK_US = 12_000_000L
private const val DEFAULT_LOW_WATERMARK_US = 1_500_000L
private const val DEFAULT_HIGH_WATERMARK_US = 3_000_000L
private const val MINIMUM_QUEUE_BYTES = 4L * 1024L * 1024L
private const val DEFAULT_MAXIMUM_QUEUE_BYTES = 24L * 1024L * 1024L
private const val MAXIMUM_QUEUE_BYTES = 64L * 1024L * 1024L
