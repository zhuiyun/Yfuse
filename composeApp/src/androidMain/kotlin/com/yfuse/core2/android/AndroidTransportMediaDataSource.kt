package com.yfuse.core2.android

import android.media.MediaDataSource
import android.os.Looper
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheConditions
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YCachePlanner
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFailureKind
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.mediaRangeRetryDelayMs
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Adapts protocol transports to MediaExtractor without ever materializing the full remote file. */
internal class AndroidTransportMediaDataSource(
    private val uri: String,
    private val protocol: YSourceProtocol,
    private val headers: Map<String, String>,
    private val credentials: YTransportCredentials? = null,
    private val createTransport: () -> YMediaTransport,
    initialMediaBitRateBitsPerSecond: Long = 0L,
    cacheDirectory: File? = null,
    cacheIdentity: YCacheIdentity? = null,
    cacheMaximumBytes: Long = 0L,
    private val onNetworkSample: ((bytes: Long, durationMs: Long) -> Unit)? = null,
    private val onBlockingReadStateChanged: ((Boolean) -> Unit)? = null,
    blockSizeOverride: Int? = null,
) : MediaDataSource() {
    private val transport = createTransport()
    private val cachePlan =
        YCachePlanner.plan(
            YCacheConditions(
                remote = true,
                live = false,
                seekable = true,
                availableBytes = DEFAULT_TRANSPORT_CACHE_BYTES,
            ),
        )
    private val blockSize =
        blockSizeOverride
            ?.also { require(it > 0) }
            ?: cachePlan.readAheadBytes.toInt().coerceAtLeast(MIN_TRANSPORT_BLOCK_BYTES)
    private val diskCache =
        if (cacheDirectory != null && cacheIdentity != null && cacheMaximumBytes > 0L) {
            AndroidYCoreBlockCache(
                cacheDirectory = cacheDirectory,
                identity = cacheIdentity,
                maximumBytes = cacheMaximumBytes,
            )
        } else {
            null
        }
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private val prefetchThreadIndex = AtomicInteger()
    private val prefetchExecutor: ExecutorService =
        Executors.newFixedThreadPool(MAX_TRANSPORT_PREFETCH_CONCURRENCY) { runnable ->
            Thread(
                runnable,
                "$TRANSPORT_PREFETCH_THREAD_NAME-${prefetchThreadIndex.incrementAndGet()}",
            ).apply { isDaemon = true }
        }
    private val prefetchTransportLock = Any()
    private var cachedBytes = 0L
    private var knownSize = diskCache?.contentLength ?: -1L
    private val prefetchedBlocks = LinkedHashMap<Long, YTransportBlockPrefetch>()
    private var prefetchSuppressed = false
    private var mediaBitRateBitsPerSecond = initialMediaBitRateBitsPerSecond.coerceAtLeast(0L)
    private var prefetchDepthBlocks =
        transportPrefetchDepthBlocks(
            blockSize = blockSize,
            mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,
        )
    private var prefetchHitCount = 0L
    private var synchronousLoadCount = 0L
    private var maximumResolveWaitMs = 0L
    private var maximumRemoteLoadMs = 0L
    private var maximumCacheLoadMs = 0L
    private var promotedPrefetchCount = 0L
    private var latestReadPosition = 0L

    private val activePrefetchTransports = mutableSetOf<YMediaTransport>()
    private val transportRouteLogged = AtomicBoolean(false)
    private val transportFailureLogged = AtomicBoolean(false)

    @Volatile
    private var closed = false

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        checkWorkerThread()
        check(!closed)
        require(position >= 0L && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (knownSize >= 0L && position >= knownSize) return -1
        var readPosition = position
        var outputOffset = offset
        var remaining = size
        while (remaining > 0 && (knownSize < 0L || readPosition < knownSize)) {
            val blockIndex = readPosition / blockSize
            val block = blocks[blockIndex] ?: resolveBlock(blockIndex)
            if (!prefetchSuppressed) schedulePrefetch(blockIndex + 1L)
            val blockOffset = (readPosition % blockSize).toInt()
            if (blockOffset >= block.size) break
            val count = minOf(remaining, block.size - blockOffset)
            block.copyInto(buffer, outputOffset, blockOffset, blockOffset + count)
            readPosition += count
            outputOffset += count
            remaining -= count
        }
        val copied = size - remaining
        if (copied > 0) {
            latestReadPosition = readPosition
        }
        return if (copied == 0) -1 else copied
    }

    /**
     * Expands compressed-byte read-ahead after MediaExtractor exposes the real stream bitrate.
     *
     * A single 2 MiB look-ahead block is less than half a second for the 38 Mbps Dolby Vision
     * source seen on affected devices. Range-request latency would therefore block the one media
     * pump that also drains AudioTrack and MediaCodec. Keeping a bounded ten-second window absorbs
     * the long-tail range latency seen behind media redirects without changing the direct-play route.
     */
    @Synchronized
    fun setMediaBitRateBitsPerSecond(value: Long) {
        // MediaExtractor often omits bitrate for Matroska. Never let that zero erase the
        // server-confirmed bitrate that was available before setDataSource opened the first range.
        mediaBitRateBitsPerSecond = maxOf(mediaBitRateBitsPerSecond, value.coerceAtLeast(0L))
        prefetchDepthBlocks =
            transportPrefetchDepthBlocks(
                blockSize = blockSize,
                mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,
            )
        if (!prefetchSuppressed) {
            schedulePrefetch(latestReadPosition / blockSize + 1L)
        }
    }

    @Synchronized
    fun qoeSnapshot(): YTransportPrefetchQoeSnapshot {
        val bufferedAheadBytes = bufferedAheadBytesSnapshot()
        return YTransportPrefetchQoeSnapshot(
            depthBlocks = prefetchDepthBlocks,
            hitCount = prefetchHitCount,
            synchronousLoadCount = synchronousLoadCount,
            maximumResolveWaitMs = maximumResolveWaitMs,
            maximumRemoteLoadMs = maximumRemoteLoadMs,
            maximumCacheLoadMs = maximumCacheLoadMs,
            promotedPrefetchCount = promotedPrefetchCount,
            bufferedAheadBytes = bufferedAheadBytes,
            contentLengthBytes = knownSize,
            mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,
        )
    }

    private fun resolveBlock(blockIndex: Long): ByteArray {
        val startedNs = System.nanoTime()
        onBlockingReadStateChanged?.invoke(true)
        try {
            val prefetched = takePrefetchedBlock(blockIndex)
            val loaded =
                if (prefetched != null) {
                    prefetchHitCount++
                    prefetched
                } else {
                    synchronousLoadCount++
                    if (prefetchSuppressed) {
                        cancelPrefetchOutside(emptySet())
                    } else {
                        // Keep the forward window filling while the foreground block is fetched.
                        // A cache miss must not throw away already useful read-ahead work.
                        schedulePrefetch(blockIndex + 1L)
                    }
                    loadBlockNow(blockIndex)
                }
            maximumResolveWaitMs =
                maxOf(
                    maximumResolveWaitMs,
                    ((System.nanoTime() - startedNs) / NANOS_PER_MILLISECOND).coerceAtLeast(0L),
                )
            maximumRemoteLoadMs = maxOf(maximumRemoteLoadMs, loaded.remoteLoadDurationMs)
            maximumCacheLoadMs = maxOf(maximumCacheLoadMs, loaded.cacheLoadDurationMs)
            loaded.contentLength?.let { contentLength ->
                if (knownSize >= 0L) require(knownSize == contentLength) { "Remote media size changed during playback" }
                knownSize = contentLength
            }
            cache(blockIndex, loaded.bytes)
            if (loaded.bytes.isNotEmpty()) {
                diskCache?.writeBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })
            }
            return loaded.bytes
        } finally {
            onBlockingReadStateChanged?.invoke(false)
        }
    }

    private fun loadBlockNow(blockIndex: Long): YLoadedTransportBlock {
        diskCache?.let { cache ->
            val startedNs = System.nanoTime()
            cache.readBlock(blockIndex, blockSize)?.let { cached ->
                return YLoadedTransportBlock(
                    bytes = cached,
                    contentLength = cache.contentLength,
                    cacheLoadDurationMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLISECOND,
                )
            }
        }
        return loadRemoteBlockWithRetries(
            blockIndex = blockIndex,
            blockTransport = transport,
            knownSizeSnapshot = knownSize,
        )
    }

    private fun loadRemoteBlockWithRetries(
        blockIndex: Long,
        blockTransport: YMediaTransport,
        knownSizeSnapshot: Long,
    ): YLoadedTransportBlock {
        var completedRetries = 0
        while (true) {
            try {
                return loadRemoteBlock(blockIndex, blockTransport, knownSizeSnapshot)
            } catch (failure: Exception) {
                val failureKind =
                    when (failure) {
                        is YRangeReadException -> failure.failureKind
                        is AndroidRangeResponseException -> failure.failureKind
                        is IOException -> YTransportFailureKind.TransientIo
                        else -> {
                            reportTransportFailure(
                                blockIndex = blockIndex,
                                completedRetries = completedRetries,
                                failureKind = null,
                                failure = failure,
                            )
                            throw failure
                        }
                    }
                val delayMs = mediaRangeRetryDelayMs(completedRetries, failureKind)
                if (delayMs == null) {
                    reportTransportFailure(
                        blockIndex = blockIndex,
                        completedRetries = completedRetries,
                        failureKind = failureKind,
                        failure = failure,
                    )
                    throw failure
                }
                completedRetries++
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw failure
                }
            }
        }
    }

    private fun loadRemoteBlock(
        blockIndex: Long,
        blockTransport: YMediaTransport,
        knownSizeSnapshot: Long,
    ): YLoadedTransportBlock =
        runBlocking {
            val startedNs = System.nanoTime()
            val position = blockIndex.saturatedMultiply(blockSize.toLong())
            val end = position.saturatedAdd(blockSize.toLong() - 1L)
            try {
                val response =
                    blockTransport.open(
                        yCoreRandomAccessRequest(
                            uri = uri,
                            protocol = protocol,
                            startInclusive = position,
                            endInclusive = end,
                            headers = headers,
                            credentials = credentials,
                        ),
                    )
                if (response.statusCode != 206) {
                    throw YRangeReadException(
                        failureKind = response.statusCode.toRangeFailureKind(),
                        safeMessage = "Random-access transport did not accept byte range",
                        statusCode = response.statusCode,
                        expectedRangeStart = position,
                        acceptedRangeStart = response.acceptedRange?.startInclusive,
                    )
                }
                if (response.acceptedRange?.startInclusive != position) {
                    throw YRangeReadException(
                        failureKind = YTransportFailureKind.InvalidRange,
                        safeMessage = "Random-access transport returned mismatched range metadata",
                        statusCode = response.statusCode,
                        expectedRangeStart = position,
                        acceptedRangeStart = response.acceptedRange?.startInclusive,
                    )
                }
                reportTransportRoute(response)
                val responseContentLength = response.contentLength?.takeIf { it >= 0L }
                responseContentLength?.let { total ->
                    if (knownSizeSnapshot >= 0L) {
                        require(knownSizeSnapshot == total) { "Remote media size changed during playback" }
                    }
                }
                val effectiveKnownSize = responseContentLength ?: knownSizeSnapshot
                val output = ByteArray(blockSize)
                var total = 0
                while (total < output.size) {
                    val count = blockTransport.read(output, total, output.size - total)
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                }
                val expectedBytes =
                    response.acceptedRange
                        ?.endInclusive
                        ?.let { servedEnd -> servedEnd - position + 1L }
                        ?.coerceAtMost(blockSize.toLong())
                if (
                    expectedBytes != null &&
                    total.toLong() != expectedBytes &&
                    (effectiveKnownSize < 0L || position + total != effectiveKnownSize)
                ) {
                    throw YRangeReadException(
                        failureKind = YTransportFailureKind.PrematureEof,
                        safeMessage = "Random-access transport ended before the accepted block range",
                        statusCode = response.statusCode,
                        expectedRangeStart = position,
                        acceptedRangeStart = response.acceptedRange?.startInclusive,
                    )
                }
                onNetworkSample?.invoke(
                    total.toLong(),
                    ((System.nanoTime() - startedNs) / 1_000_000L).coerceAtLeast(1L),
                )
                YLoadedTransportBlock(
                    bytes = output.copyOf(total),
                    contentLength = responseContentLength,
                    remoteLoadDurationMs =
                        ((System.nanoTime() - startedNs) / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                )
            } finally {
                blockTransport.close()
            }
        }

    private fun reportTransportRoute(response: YMediaTransportResponse) {
        if (!transportRouteLogged.compareAndSet(false, true)) return
        AppLog.info(
            category = "player.core2",
            event = "transport_range_opened",
            message = "YCore opened the first validated media byte range",
            attributes =
                mapOf(
                    "implementation" to response.implementation.ifBlank { "unknown" },
                    "status" to response.statusCode.toString(),
                    "negotiatedProtocol" to response.negotiatedProtocol.ifBlank { "unknown" },
                    "redirectCount" to response.redirectCount.toString(),
                    "finalProtocol" to (response.finalProtocol?.name ?: "unknown"),
                    "cleartextRedirect" to response.cleartextRedirect.toString(),
                    "contentLengthKnown" to (response.contentLength != null).toString(),
                ),
        )
    }

    private fun reportTransportFailure(
        blockIndex: Long,
        completedRetries: Int,
        failureKind: YTransportFailureKind?,
        failure: Exception,
    ) {
        if (closed || Thread.currentThread().isInterrupted || failure.isTransportCancellation()) return
        if (!transportFailureLogged.compareAndSet(false, true)) return
        val statusCode =
            when (failure) {
                is YRangeReadException -> failure.statusCode
                is AndroidRangeResponseException -> failure.statusCode
                else -> null
            }
        val expectedRangeStart =
            when (failure) {
                is YRangeReadException -> failure.expectedRangeStart
                is AndroidRangeResponseException -> failure.expectedRangeStart
                else -> null
            }
        val acceptedRangeStart =
            when (failure) {
                is YRangeReadException -> failure.acceptedRangeStart
                is AndroidRangeResponseException -> failure.acceptedRangeStart
                else -> null
            }
        AppLog.warning(
            category = "player.core2",
            event = "transport_range_failed",
            message = "YCore exhausted a media byte-range request without exposing credentials",
            attributes =
                mapOf(
                    "failureKind" to (failureKind?.name ?: "Unknown"),
                    "exceptionChain" to failure.safeTransportExceptionChain(),
                    "status" to (statusCode?.toString() ?: "unavailable"),
                    "expectedRangeStart" to
                        (expectedRangeStart?.toString()
                            ?: blockIndex.saturatedMultiply(blockSize.toLong()).toString()),
                    "acceptedRangeStart" to (acceptedRangeStart?.toString() ?: "unavailable"),
                    "attemptCount" to (completedRetries + 1).toString(),
                ),
        )
    }

    private fun schedulePrefetch(blockIndex: Long) {
        if (prefetchSuppressed) return
        val desired =
            (0 until prefetchDepthBlocks)
                .map { offset -> blockIndex.saturatedAdd(offset.toLong()) }
                .filter { candidate ->
                    shouldPrefetchTransportBlock(candidate, blockSize, knownSize) &&
                        !blocks.containsKey(candidate)
                }.toSet()
        cancelPrefetchOutside(desired)
        desired.sorted().forEach(::schedulePrefetchBlock)
    }

    private fun schedulePrefetchBlock(blockIndex: Long) {
        if (prefetchedBlocks.containsKey(blockIndex) || blocks.containsKey(blockIndex)) return
        val knownSizeSnapshot = knownSize
        val prefetch = YTransportBlockPrefetch(blockIndex)
        prefetchedBlocks[blockIndex] = prefetch
        prefetch.future =
            prefetchExecutor.submit<YLoadedTransportBlock> {
                if (!prefetch.markStarted()) throw CancellationException("Transport prefetch was promoted")
                diskCache?.let { cache ->
                    val cacheStartedNs = System.nanoTime()
                    cache.readBlock(blockIndex, blockSize)?.let { cached ->
                        return@submit YLoadedTransportBlock(
                            bytes = cached,
                            contentLength = cache.contentLength,
                            cacheLoadDurationMs =
                                (System.nanoTime() - cacheStartedNs) / NANOS_PER_MILLISECOND,
                        )
                    }
                }
                val prefetchTransport = createTransport()
                val rejected =
                    synchronized(prefetchTransportLock) {
                        closed ||
                            !prefetch.bind(prefetchTransport) ||
                            !activePrefetchTransports.add(prefetchTransport)
                    }
                if (rejected) {
                    runBlocking { prefetchTransport.close() }
                    throw CancellationException("Transport data source closed")
                }
                try {
                    loadRemoteBlockWithRetries(blockIndex, prefetchTransport, knownSizeSnapshot)
                } finally {
                    prefetch.unbind(prefetchTransport)
                    synchronized(prefetchTransportLock) {
                        activePrefetchTransports.remove(prefetchTransport)
                    }
                }
            }
    }

    private fun takePrefetchedBlock(blockIndex: Long): YLoadedTransportBlock? {
        val prefetch = prefetchedBlocks.remove(blockIndex) ?: return null
        // A foreground MediaExtractor read must never sit behind speculative ranges. If its future
        // has not started, remove it from the executor queue and load through the primary transport
        // immediately. This is the exact head-of-line case where diagnostics showed a 22 s resolve
        // wait for a block whose actual network transfer took only 2.2 s.
        if (shouldPromoteTransportPrefetch(prefetch.future.isDone, prefetch.started)) {
            prefetch.cancel()
            return null
        }
        return try {
            prefetch.future.get(FOREGROUND_PREFETCH_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // The promotion test above only rescues a prefetch the pool has not picked up yet:
            // markStarted() runs as the task's first statement, before any I/O, so a range that
            // began a millisecond ago and will stall for half a minute already counts as started.
            // Waiting on it was unbounded, which is how a stalled origin came to hold the one pump
            // that also drains MediaCodec and AudioTrack. Past this bound a direct load is the
            // better bet, and cancelling releases the pool thread and its socket.
            promotedPrefetchCount++
            prefetch.cancel()
            null
        } catch (_: CancellationException) {
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun cancelPrefetchOutside(retained: Set<Long>) {
        val iterator = prefetchedBlocks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in retained) {
                entry.value.cancel()
                iterator.remove()
            }
        }
    }

    private fun bufferedAheadBytesSnapshot(): Long {
        var cursor = latestReadPosition.coerceAtLeast(0L)
        val start = cursor
        var blockIndex = cursor / blockSize
        var scannedBlocks = 0
        val scanLimit = prefetchDepthBlocks + TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS
        while (scannedBlocks < scanLimit) {
            val block = blocks[blockIndex] ?: completedPrefetchBytes(blockIndex) ?: break
            val blockStart = blockIndex.saturatedMultiply(blockSize.toLong())
            val blockEnd = blockStart.saturatedAdd(block.size.toLong())
            if (cursor < blockEnd) {
                cursor = blockEnd
            }
            if (block.size < blockSize) break
            blockIndex = blockIndex.saturatedAdd(1L)
            scannedBlocks++
        }
        knownSize.takeIf { it >= 0L }?.let { size -> cursor = cursor.coerceAtMost(size) }
        return (cursor - start).coerceAtLeast(0L)
    }

    private fun completedPrefetchBytes(blockIndex: Long): ByteArray? {
        val prefetch = prefetchedBlocks[blockIndex] ?: return null
        if (!prefetch.future.isDone || prefetch.future.isCancelled) return null
        return try {
            prefetch.future.get().bytes
        } catch (_: CancellationException) {
            null
        } catch (_: ExecutionException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    @Synchronized
    override fun getSize(): Long {
        checkWorkerThread()
        check(!closed)
        if (knownSize >= 0L) return knownSize
        val probe = ByteArray(1)
        prefetchSuppressed = true
        try {
            readAt(0L, probe, 0, 1)
        } finally {
            prefetchSuppressed = false
        }
        return knownSize
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cancelPrefetchOutside(emptySet())
        prefetchExecutor.shutdownNow()
        val prefetchTransports =
            synchronized(prefetchTransportLock) {
                activePrefetchTransports.toList().also { activePrefetchTransports.clear() }
            }
        blocks.clear()
        cachedBytes = 0L
        runBlocking {
            prefetchTransports.forEach { prefetchTransport -> prefetchTransport.close() }
            transport.close()
        }
    }

    private fun cache(
        index: Long,
        block: ByteArray,
    ) {
        blocks.put(index, block)?.let { cachedBytes -= it.size }
        cachedBytes += block.size
        val iterator = blocks.entries.iterator()
        while (cachedBytes > cachePlan.maximumBytes && iterator.hasNext()) {
            cachedBytes -= iterator.next().value.size
            iterator.remove()
        }
    }
}

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

private data class YLoadedTransportBlock(
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
)

private class YTransportBlockPrefetch(
    val blockIndex: Long,
) {
    private val cancelled = AtomicBoolean(false)
    private val executionStarted = AtomicBoolean(false)
    private var activeTransport: YMediaTransport? = null

    lateinit var future: Future<YLoadedTransportBlock>

    val started: Boolean get() = executionStarted.get()

    fun markStarted(): Boolean {
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

    fun cancel() {
        cancelled.set(true)
        future.cancel(true)
        val transport = synchronized(this) { activeTransport.also { activeTransport = null } }
        transport?.let { active -> runCatching { runBlocking { active.close() } } }
    }
}

private class YRangeReadException(
    val failureKind: YTransportFailureKind,
    safeMessage: String,
    val statusCode: Int? = null,
    val expectedRangeStart: Long? = null,
    val acceptedRangeStart: Long? = null,
) : IOException(safeMessage)

private fun Throwable.safeTransportExceptionChain(): String =
    generateSequence(this) { current -> current.cause }
        .take(MAX_SAFE_EXCEPTION_CHAIN_DEPTH)
        .joinToString(">") { current -> current.javaClass.simpleName.ifBlank { "Throwable" } }

private fun Throwable.isTransportCancellation(): Boolean =
    generateSequence(this) { current -> current.cause }
        .take(MAX_SAFE_EXCEPTION_CHAIN_DEPTH)
        .any { current ->
            current is CancellationException || current is InterruptedException
        }

private fun Int.toRangeFailureKind(): YTransportFailureKind =
    when (this) {
        401, 403 -> YTransportFailureKind.Authorization
        408, 425, 429 -> YTransportFailureKind.ServerBusy
        in 500..599 -> YTransportFailureKind.ServerBusy
        else -> YTransportFailureKind.InvalidRange
    }

private fun checkWorkerThread() {
    val mainLooper = Looper.getMainLooper()
    check(mainLooper == null || Looper.myLooper() != mainLooper) {
        "Remote media I/O is forbidden on the main thread"
    }
}

private fun Long.saturatedAdd(other: Long): Long {
    if (other <= 0L || this <= Long.MAX_VALUE - other) return this + other
    return Long.MAX_VALUE
}

private fun Long.saturatedMultiply(other: Long): Long {
    if (other <= 0L || this <= Long.MAX_VALUE / other) return this * other
    return Long.MAX_VALUE
}

private const val MIN_TRANSPORT_BLOCK_BYTES = 256 * 1024
private const val DEFAULT_TRANSPORT_CACHE_BYTES = 64L * 1024L * 1024L
private const val TRANSPORT_PREFETCH_THREAD_NAME = "YCore-TransportPrefetch"
private const val DEFAULT_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 2
private const val MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 12
// Four ordered ranges keep HTTP/1.1 origins busy without letting speculative traffic crowd out
// playback. Foreground reads separately promote any queued block they need immediately.
private const val MAX_TRANSPORT_PREFETCH_CONCURRENCY = 4
private const val TARGET_TRANSPORT_PREFETCH_WINDOW_MS = 10_000L
private const val TRANSPORT_PREFETCH_SAFETY_BLOCKS = 1L
private const val TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS = 2
private const val BITS_PER_BYTE = 8L
private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L
// A 2 MiB speculative block that has not arrived in this long is no longer beating a direct load,
// and the stalled socket behind it is worth releasing.
private const val FOREGROUND_PREFETCH_WAIT_MS = 1_500L
private const val MAX_SAFE_EXCEPTION_CHAIN_DEPTH = 4
