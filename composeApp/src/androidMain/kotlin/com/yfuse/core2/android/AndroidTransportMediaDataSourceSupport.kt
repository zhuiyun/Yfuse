package com.yfuse.core2.android

import android.os.Looper
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YTransportFailureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class YTransportPrefetchQoeSnapshot(
    val depthBlocks: Int,
    val hitCount: Long,
    val synchronousLoadCount: Long,
    val maximumResolveWaitMs: Long,
    val maximumRemoteLoadMs: Long,
    val maximumCacheLoadMs: Long = 0L,
    /** Foreground reads that abandoned a stalled speculative range and loaded directly. */
    val promotedPrefetchCount: Long = 0L,
    val bufferedAheadBytes: Long = 0L,
    val contentLengthBytes: Long = -1L,
    val mediaBitRateBitsPerSecond: Long = 0L,
    /** Aggregate link estimate across concurrent range transfers, or 0 before the first sample. */
    val throughputBitsPerSecond: Long = 0L,
    val throughputMeasured: Boolean = false,
    val maximumCacheWriteMs: Long = 0L,
    val failedCacheWriteCount: Long = 0L,
    val droppedCacheWriteCount: Long = 0L,
)

internal fun YTransportPrefetchQoeSnapshot.bufferedAheadDurationMs(durationMs: Long): Long {
    if (bufferedAheadBytes <= 0L) return 0L
    if (mediaBitRateBitsPerSecond > 0L) {
        return bufferedAheadBytes
            .saturatedMultiply(BITS_PER_BYTE * MILLIS_PER_SECOND)
            .div(mediaBitRateBitsPerSecond)
            .coerceAtLeast(0L)
    }
    if (durationMs > 0L && contentLengthBytes > 0L) {
        return ((bufferedAheadBytes.toDouble() * durationMs.toDouble()) / contentLengthBytes.toDouble())
            .toLong()
            .coerceAtLeast(0L)
    }
    return 0L
}

internal fun transportPrefetchDepthBlocks(
    blockSize: Int,
    mediaBitRateBitsPerSecond: Long,
): Int {
    if (blockSize <= 0 || mediaBitRateBitsPerSecond <= 0L) {
        return DEFAULT_TRANSPORT_PREFETCH_DEPTH_BLOCKS
    }
    val targetBytes =
        mediaBitRateBitsPerSecond
            .saturatedMultiply(TARGET_TRANSPORT_PREFETCH_WINDOW_MS)
            .div(BITS_PER_BYTE * MILLIS_PER_SECOND)
    val requiredBlocks =
        ((targetBytes + blockSize - 1L) / blockSize)
            .coerceAtLeast(1L)
    return (requiredBlocks + TRANSPORT_PREFETCH_SAFETY_BLOCKS)
        .coerceIn(
            DEFAULT_TRANSPORT_PREFETCH_DEPTH_BLOCKS.toLong(),
            MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS.toLong(),
        ).toInt()
}

internal fun shouldPrefetchTransportBlock(
    blockIndex: Long,
    blockSize: Int,
    knownSize: Long,
): Boolean {
    if (blockIndex < 0L || blockSize <= 0) return false
    return knownSize < 0L || blockIndex.saturatedMultiply(blockSize.toLong()) < knownSize
}

/** Foreground playback must bypass speculative work that is still waiting in the pool queue. */
internal fun shouldPromoteTransportPrefetch(
    futureDone: Boolean,
    executionStarted: Boolean,
): Boolean = !futureDone && !executionStarted

internal data class YLoadedTransportBlock(
    val bytes: ByteArray,
    val contentLength: Long?,
    val remoteLoadDurationMs: Long = 0L,
    /**
     * Time spent serving this block from the on-disk cache.
     *
     * Kept separate from [remoteLoadDurationMs] so a long resolve wait can be attributed. A cache
     * hit used to record nothing, which made "the network was slow" and "the wait happened inside
     * YCore" produce the same zero.
     */
    val cacheLoadDurationMs: Long = 0L,
    val offsetInBlock: Int = 0,
    val fromDiskCache: Boolean = false,
)

internal class YPartialTransportBlock(
    val bytes: ByteArray,
) {
    var total = 0
    var entityTag: String? = null
    var contentLength: Long? = null
}

internal class YTransportBlockPrefetch(
    val blockIndex: Long,
) {
    private val queuedAtNs = System.nanoTime()

    @Volatile var queueWaitMs: Long = 0L
        private set
    private val cancelled = AtomicBoolean(false)
    private val executionStarted = AtomicBoolean(false)
    private var activeTransport: YMediaTransport? = null
    val completedBytes = AtomicLong()
    val lastProgressNs = AtomicLong(System.nanoTime())
    val available = YProgressiveTransportBlock()
    var progressiveEntityTag: String? = null
    var prefixDelivered = false

    fun beginAttempt(nowNs: Long) {
        completedBytes.set(0L)
        lastProgressNs.set(nowNs)
    }

    fun recordProgress(
        bytes: Long,
        nowNs: Long,
    ) {
        completedBytes.set(bytes)
        lastProgressNs.set(nowNs)
    }

    lateinit var future: Future<YLoadedTransportBlock>

    val started: Boolean get() = executionStarted.get()
    val isCancelled: Boolean get() = cancelled.get()

    @Volatile var cancellationReason: String = "cancelled"
        private set

    fun markStarted(): Boolean {
        val nowNs = System.nanoTime()
        queueWaitMs = (nowNs - queuedAtNs) / 1_000_000L
        lastProgressNs.set(nowNs)
        executionStarted.set(true)
        return !cancelled.get()
    }

    @Synchronized
    fun bind(transport: YMediaTransport): Boolean {
        if (cancelled.get()) return false
        activeTransport = transport
        if (!cancelled.get()) return true
        activeTransport = null
        return false
    }

    @Synchronized
    fun unbind(transport: YMediaTransport) {
        if (activeTransport === transport) activeTransport = null
    }

    fun cancel(reason: String = "cancelled") {
        cancellationReason = reason
        cancelled.set(true)
        val transport = synchronized(this) { activeTransport.also { activeTransport = null } }
        // Capture the socket owner before interrupting the worker: its finally block can unbind
        // immediately, while a cancelled coroutine still has blocking network I/O to tear down.
        future.cancel(true)
        transport?.let { active -> foregroundRangeCleanup.launch { runCatching { active.close() } } }
    }
}

internal class YForegroundRangeRead {
    val job = Job()
    val cancelLatch = CountDownLatch(1)
    private val abandoned = AtomicBoolean(false)
    private var transport: YMediaTransport? = null
    val cancelled: Boolean get() = abandoned.get()

    fun checkActive() {
        if (cancelled) throw CancellationException("Media read was superseded")
    }

    fun bind(value: YMediaTransport) {
        synchronized(this) {
            checkActive()
            transport = value
        }
    }

    fun cancel() {
        if (!abandoned.compareAndSet(false, true)) return
        cancelLatch.countDown()
        job.cancel()
        val active = synchronized(this) { transport }
        // A UI exit/deadline only signals shutdown. The transport remains owned by cleanup until
        // its blocking close completes; waiting for that close here can stall the main thread.
        active?.let { transport ->
            foregroundRangeCleanup.launch { runCatching { transport.close() } }
        }
    }

    fun finish() {
        job.complete()
    }
}

private val foregroundRangeCleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
internal val transportSourceSequence = AtomicLong()

internal fun keepActiveTransportPrefetch(idleMs: Long): Boolean = idleMs in 0L until 4_000L

internal class YRangeReadException(
    val failureKind: YTransportFailureKind,
    safeMessage: String,
    val statusCode: Int? = null,
    val expectedRangeStart: Long? = null,
    val acceptedRangeStart: Long? = null,
) : IOException(safeMessage)

internal fun Throwable.safeTransportExceptionChain(): String =
    generateSequence(this) { current -> current.cause }
        .take(MAX_SAFE_EXCEPTION_CHAIN_DEPTH)
        .joinToString(">") { current -> current.javaClass.simpleName.ifBlank { "Throwable" } }

internal fun Throwable.isTransportCancellation(): Boolean =
    generateSequence(this) { current -> current.cause }
        .take(MAX_SAFE_EXCEPTION_CHAIN_DEPTH)
        .any { current ->
            current is CancellationException || current is InterruptedException
        }

internal fun Int.toRangeFailureKind(): YTransportFailureKind =
    when (this) {
        in 300..399 -> YTransportFailureKind.RedirectRejected
        401, 403 -> YTransportFailureKind.Authorization
        408, 425, 429 -> YTransportFailureKind.ServerBusy
        in 500..599 -> YTransportFailureKind.ServerBusy
        else -> YTransportFailureKind.InvalidRange
    }

internal fun isRecoverableMediaReadFailure(failure: Throwable?): Boolean {
    // Cancellation stays cancellation even when the transport wraps it in an IOException.
    generateSequence(failure) { it.cause.takeUnless { cause -> cause === it } }
        .take(8)
        .filterIsInstance<AndroidProbeAbortedException>()
        .firstOrNull()
        ?.let { return it.reason == "deadline" }
    var current = failure
    repeat(8) {
        val cause = current ?: return false
        when (cause) {
            is YUpstreamHttpException -> return cause.statusCode in setOf(408, 425, 429, 500, 502, 503, 504)
            is YRangeReadException ->
                return cause.failureKind in
                    setOf(YTransportFailureKind.TransientIo, YTransportFailureKind.PrematureEof)
            is AndroidRangeResponseException ->
                return cause.failureKind in
                    setOf(YTransportFailureKind.TransientIo, YTransportFailureKind.PrematureEof)
            is IOException -> return true
        }
        current = cause.cause.takeUnless { it === cause }
    }
    return false
}

internal fun checkWorkerThread() {
    val mainLooper = Looper.getMainLooper()
    check(mainLooper == null || Looper.myLooper() != mainLooper) {
        "Remote media I/O is forbidden on the main thread"
    }
}

internal fun Long.saturatedAdd(other: Long): Long {
    if (other <= 0L || this <= Long.MAX_VALUE - other) return this + other
    return Long.MAX_VALUE
}

internal fun Long.saturatedMultiply(other: Long): Long {
    if (other <= 0L || this <= Long.MAX_VALUE / other) return this * other
    return Long.MAX_VALUE
}

internal const val MIN_TRANSPORT_BLOCK_BYTES = 256 * 1024
internal const val STARTUP_RANGE_BYTES = 128 * 1024
internal const val DEFAULT_TRANSPORT_CACHE_BYTES = 64L * 1024L * 1024L

/** Backward window kept on the heap, in blocks. Forward bytes live in the prefetch futures. */
internal const val MEMORY_CACHE_BLOCKS = 8L

/** Consecutive zero-length transport reads tolerated before a block counts as failed. */
internal const val TRANSPORT_MAX_EMPTY_READS = 64
internal const val TRANSPORT_PREFETCH_THREAD_NAME = "YCore-TransportPrefetch"
internal const val DEFAULT_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 2
internal const val MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 24

/** Read-ahead for the run of a non-interleaved file that is not being read at this moment. */
internal const val OTHER_RUN_MIN_PREFETCH_BLOCKS = 2

// Six ordered ranges hide the long-tail range latency observed on remote high-bitrate remuxes
// without allowing the full twenty-second window to open one socket per block.
// Foreground reads separately promote any queued block they need immediately.
internal const val MAX_TRANSPORT_PREFETCH_CONCURRENCY = 6
private const val TARGET_TRANSPORT_PREFETCH_WINDOW_MS = 20_000L
private const val TRANSPORT_PREFETCH_SAFETY_BLOCKS = 1L
internal const val TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS = 2
private const val BITS_PER_BYTE = 8L
private const val MILLIS_PER_SECOND = 1_000L
internal const val TRANSPORT_NANOS_PER_MILLISECOND = 1_000_000L

// A 2 MiB speculative block that has not arrived in this long is no longer beating a direct load,
// and the stalled socket behind it is worth releasing.
internal fun shouldKeepTransportPrefetch(
    waitedMs: Long,
    idleMs: Long,
    completedBytes: Long,
    blockBytes: Long,
    bufferedMs: Long,
): Boolean {
    val nearlyComplete = blockBytes > 0L && completedBytes.toDouble() / blockBytes >= 0.75
    if (waitedMs >= if (nearlyComplete) 45_000L else 30_000L) return false
    val idleBudgetMs = bufferedMs.coerceIn(4_000L, 12_000L)
    if (idleMs >= idleBudgetMs) return false
    // Keep a moving response, especially its nearly completed tail, instead of discarding bytes.
    return completedBytes > 0L && blockBytes > 0L || waitedMs < idleBudgetMs
}

internal fun transportPrefetchConcurrency(
    throughput: Long,
    consumption: Long,
    bufferedUs: Long,
): Int =
    when {
        consumption <= 0L || throughput <= 0L -> 2
        bufferedUs < 3_000_000L && throughput < consumption -> 2
        bufferedUs >= 8_000_000L && throughput > consumption -> 2
        bufferedUs < 2_000_000L -> 2
        else -> 4
    }

/** Enough blocking loads to show a cold start's access pattern; playback then falls silent. */
internal const val MAX_REPORTED_SYNCHRONOUS_LOADS = 8L
private const val MAX_SAFE_EXCEPTION_CHAIN_DEPTH = 4
