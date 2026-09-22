package com.yfuse.core2.android

import android.media.MediaDataSource
import android.os.Looper
import com.yfuse.core.logging.AppLog
import com.yfuse.core2.network.YAggregateBandwidthMeter
import com.yfuse.core2.network.YCacheConditions
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YCachePlanner
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFailureKind
import com.yfuse.core2.network.mediaRangeRetryDelayMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.EmptyCoroutineContext

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
    private val cacheMaximumBytes: Long = 0L,
    private val onNetworkSample: ((bytes: Long, durationMs: Long) -> Unit)? = null,
    private val onBlockingReadStateChanged: ((Boolean) -> Unit)? = null,
    blockSizeOverride: Int? = null,
    private val rangeReadBudgetMs: Long = 30_000L,
    memoryLeaseOverride: PlaybackMemoryLease? = null,
    private val allowsSpeculativeWork: () -> Boolean = { AndroidPlaybackMemoryBudget.allowsSpeculativeWork },
    private val refreshMemoryPressure: () -> Unit = AndroidPlaybackMemoryBudget::refreshPressure,
) : MediaDataSource() {
    @Volatile
    private var foregroundRead: YForegroundRangeRead? = null

    /** Cancels only the current read. A later seek/read uses a fresh transport and coroutine. */
    fun cancelPendingRead() {
        foregroundRead?.cancel()
    }

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
                blockSizeBytes = blockSize,
                maximumBytes = cacheMaximumBytes,
            )
        } else {
            null
        }
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private val memoryLease =
        memoryLeaseOverride ?: AndroidPlaybackMemoryBudget.acquire(
            PlaybackBufferKind.Transport,
            DEFAULT_TRANSPORT_CACHE_BYTES,
        )
    private var startupSlice: Pair<Long, YLoadedTransportBlock>? = null
    private var startupReadServed = false
    private val representationLock = Any()
    private var representationTag: String? = null
    private var representationLength: Long? = null

    /**
     * Heap budget for [blocks], which holds already-delivered bytes.
     *
     * [YCachePlanner] sizes a *disk* cache - its ceiling is 4 GiB - so its `maximumBytes` must
     * never bound a Java-heap map. Retaining 64 MiB of played-back bytes on top of the completed
     * prefetch futures and the demux sample queue is what put a single playback session near the
     * default (non-largeHeap) limit. Only a small backward window is useful: short seeks back and
     * the MediaExtractor header/index re-reads, which the access-ordered LRU keeps hot.
     */
    private val memoryCacheBytes: Long
        get() = minOf(blockSize.toLong().saturatedMultiply(MEMORY_CACHE_BLOCKS), memoryLease.limitBytes / 4L)

    private val memoryPrefetchBlocks: Int
        get() =
            if (!allowsSpeculativeWork()) {
                0
            } else {
                (
                    (memoryLease.limitBytes - memoryCacheBytes - 2L * blockSize - STARTUP_RANGE_BYTES)
                        .coerceAtLeast(0L) / blockSize
                ).toInt().coerceAtMost(MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS)
            }
    private val prefetchThreadIndex = AtomicInteger()
    private val prefetchExecutor: ExecutorService =
        Executors.newFixedThreadPool(MAX_TRANSPORT_PREFETCH_CONCURRENCY) { runnable ->
            Thread(
                runnable,
                "$TRANSPORT_PREFETCH_THREAD_NAME-${prefetchThreadIndex.incrementAndGet()}",
            ).apply { isDaemon = true }
        }
    private val prefetchTransportLock = Any()
    private val bandwidthMeter = YAggregateBandwidthMeter()
    private var cachedBytes = 0L
    private var knownSize = diskCache?.contentLength ?: -1L

    @Volatile
    private var playbackWindow = YTransportPlaybackWindow()
    private val forwardCache =
        diskCache?.let { cache ->
            AndroidForwardCacheWarmer(
                cache = cache,
                executor = prefetchExecutor,
                createTransport = createTransport,
                load = { index, transport, cancelled ->
                    loadRemoteBlockWithRetries(
                        index,
                        transport,
                        cache.contentLength ?: -1L,
                        isCancelled = cancelled,
                    ).let {
                        it.bytes to it.contentLength
                    }
                },
                canWarm = {
                    val window = playbackWindow
                    !closed &&
                        allowsSpeculativeWork() &&
                        memoryLease.limitBytes >= 4L * blockSize &&
                        window.playing &&
                        window.bufferedUs >= window.minimumWarmBufferUs &&
                        effectiveThroughput(System.nanoTime()) >
                        mediaBitRateBitsPerSecond.toDouble() * window.speed * 1.1
                },
            )
        }
    private val prefetchedBlocks = LinkedHashMap<Long, YTransportBlockPrefetch>()
    private var prefetchSuppressed = false

    @Volatile
    private var mediaBitRateBitsPerSecond = initialMediaBitRateBitsPerSecond.coerceAtLeast(0L)
    private var prefetchDepthBlocks =
        transportPrefetchDepthBlocks(
            blockSize = blockSize,
            mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,
        )

    /** Container indexes often live in the final block; overlap that probe with header parsing. */
    private var startupTailPrefetchBlockIndex: Long? = null
    private var startupTailPrefetchScheduled = false
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

    /** Released by [close] so a retry wait on the extractor thread ends immediately. */
    private val closedLatch = CountDownLatch(1)

    /** Start of the in-flight foreground range fetch, or 0 when no read is blocked. */
    @Volatile
    private var foregroundReadStartedAtNs = 0L

    @Volatile
    private var foregroundFailure: Throwable? = null

    /** MediaExtractor may turn a MediaDataSource IOException into EOF. Preserve the real cause. */
    fun throwIfReadFailed() {
        foregroundFailure?.let { throw it }
    }

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        checkWorkerThread()
        check(!closed)
        reclaimMemoryBudget()
        throwIfReadFailed()
        require(position >= 0L && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (knownSize >= 0L && position >= knownSize) return -1
        if (kotlin.math.abs(position - latestReadPosition) > blockSize.toLong() * 2L) {
            forwardCache?.updateWindow(0L, 0L)
            startupSlice = null
            startupReadServed = false
        }
        var readPosition = position
        var outputOffset = offset
        var remaining = size
        while (remaining > 0 && (knownSize < 0L || readPosition < knownSize)) {
            val blockIndex = readPosition / blockSize
            val offsetInBlock = (readPosition % blockSize).toInt()
            val loaded =
                blocks[blockIndex]?.let { YLoadedTransportBlock(it, knownSize.takeIf { it >= 0L }) }
                    ?: startupSlice
                        ?.takeIf { (index, slice) ->
                            index == blockIndex &&
                                offsetInBlock in slice.offsetInBlock until (slice.offsetInBlock + slice.bytes.size)
                        }?.second
                    ?: resolveBlock(
                        blockIndex,
                        startupOffset =
                            offsetInBlock.takeIf {
                                !startupReadServed && blockSize > STARTUP_RANGE_BYTES && size <= STARTUP_RANGE_BYTES
                            },
                    )
            val block = loaded.bytes
            if (!prefetchSuppressed) schedulePrefetch(blockIndex + 1L)
            val blockOffset = offsetInBlock - loaded.offsetInBlock
            if (blockOffset >= block.size) break
            val count = minOf(remaining, block.size - blockOffset)
            block.copyInto(buffer, outputOffset, blockOffset, blockOffset + count)
            readPosition += count
            outputOffset += count
            remaining -= count
        }
        val copied = size - remaining
        if (copied > 0) {
            if (!prefetchSuppressed) startupReadServed = true
            latestReadPosition = readPosition
            scheduleForwardCache()
        }
        return if (copied == 0) -1 else copied
    }

    /** The codec pump never waits on a range-read monitor to publish feedback. */
    fun updatePlaybackWindow(window: YTransportPlaybackWindow) {
        playbackWindow = window
    }

    private fun scheduleForwardCache() {
        val warmer = forwardCache ?: return
        if (!allowsSpeculativeWork()) {
            warmer.updateWindow(0L, 0L)
            return
        }
        if (knownSize <= 0L || mediaBitRateBitsPerSecond <= 0L) return
        val targetBytes =
            (mediaBitRateBitsPerSecond / 8.0 * playbackWindow.targetAheadUs / 1_000_000.0)
                .toLong()
                .coerceAtMost(cacheMaximumBytes / 5L * 4L)
        val first = latestReadPosition / blockSize + prefetchDepthBlocks + 1L
        val end = minOf((knownSize + blockSize - 1L) / blockSize, (latestReadPosition + targetBytes) / blockSize)
        warmer.updateWindow(first, end)
    }

    /**
     * Expands compressed-byte read-ahead after MediaExtractor exposes the real stream bitrate.
     *
     * A single 2 MiB look-ahead block is less than half a second for the 38 Mbps Dolby Vision
     * source seen on affected devices. Range-request latency would therefore block the one media
     * pump that also drains AudioTrack and MediaCodec. Keeping a bounded twenty-second window absorbs
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

    /**
     * How long the current foreground range fetch has been outstanding, or 0 when none is.
     *
     * Never make this `@Synchronized`. [readAt] holds this instance's monitor for the whole of a
     * blocking fetch, and [qoeSnapshot] is refreshed on the MediaExtractor owner - the very thread
     * that blocks - so neither can report a stall while it is happening. NativeDirect's pump reads
     * this directly, which is the only way a frozen pump leaves a trace in diagnostics.
     */
    fun blockedForegroundReadMs(): Long {
        val startedAtNs = foregroundReadStartedAtNs
        if (startedAtNs == 0L) return 0L
        return ((System.nanoTime() - startedAtNs).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
    }

    @Synchronized
    fun qoeSnapshot(): YTransportPrefetchQoeSnapshot {
        reclaimMemoryBudget()
        scheduleForwardCache()
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
            throughputBitsPerSecond = effectiveThroughput(System.nanoTime()),
            throughputMeasured = bandwidthMeter.hasEstimate,
            maximumCacheWriteMs = diskCache?.maximumWriteMs ?: 0L,
            failedCacheWriteCount = diskCache?.failedWriteCount ?: 0L,
            droppedCacheWriteCount = diskCache?.droppedWriteCount ?: 0L,
        )
    }

    private fun resolveBlock(
        blockIndex: Long,
        startupOffset: Int? = null,
    ): YLoadedTransportBlock {
        if (startupTailPrefetchBlockIndex == blockIndex) startupTailPrefetchBlockIndex = null
        val startedNs = System.nanoTime()
        val budget = YRangeReadBudget(rangeReadBudgetMs)
        val operation = YForegroundRangeRead()
        foregroundRead = operation
        foregroundReadStartedAtNs = startedNs
        onBlockingReadStateChanged?.invoke(true)
        try {
            val pending = prefetchedBlocks[blockIndex]
            val preferStartupSlice =
                startupOffset != null &&
                    pending != null &&
                    !pending.future.isDone &&
                    pending.completedBytes.get() < blockSize.toLong() * 3L / 4L
            val prefetched =
                if (preferStartupSlice) {
                    // A seek needs a small access unit now, not an unfinished whole speculative block.
                    // Preserve almost-complete transfers so a late seek does not discard their tail.
                    prefetchedBlocks.remove(blockIndex)?.cancel()
                    promotedPrefetchCount++
                    null
                } else {
                    takePrefetchedBlock(blockIndex, budget, operation)
                }
            val loaded =
                if (prefetched != null) {
                    prefetchHitCount++
                    prefetched
                } else {
                    shedSpeculativeWorkFor(blockIndex)
                    synchronousLoadCount++
                    reportSynchronousBlockLoad(blockIndex)
                    if (prefetchSuppressed) {
                        cancelPrefetchOutside(emptySet())
                    } else {
                        // Keep the forward window filling while the foreground block is fetched.
                        // A cache miss must not throw away already useful read-ahead work.
                        schedulePrefetch(blockIndex + 1L)
                    }
                    loadBlockNow(blockIndex, budget, startupOffset, operation)
                }
            operation.checkActive()
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
            val completeBlock =
                loaded.offsetInBlock == 0 &&
                    (loaded.bytes.size == blockSize || blockIndex * blockSize + loaded.bytes.size == knownSize)
            if (completeBlock) {
                cache(blockIndex, loaded.bytes)
                if (loaded.bytes.isNotEmpty() && !loaded.fromDiskCache) {
                    diskCache?.enqueueWriteBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })
                }
            } else if (loaded.bytes.isNotEmpty()) {
                // A startup slice is addressed by its real offset and is never a persistent block.
                // Keep the stable 2 MiB stride and fill the complete block in the background.
                startupSlice = blockIndex to loaded
                if (!prefetchSuppressed) schedulePrefetchBlock(blockIndex)
            }
            return loaded
        } catch (failure: Exception) {
            if (!closed && !operation.cancelled && !failure.isTransportCancellation()) foregroundFailure = failure
            throw failure
        } finally {
            foregroundRead = null
            operation.finish()
            foregroundReadStartedAtNs = 0L
            onBlockingReadStateChanged?.invoke(false)
        }
    }

    /**
     * Records where the first blocking reads land, so a startup profile can be read from the log.
     *
     * MediaExtractor's container parse is the largest unexplained span of a cold start, and whether
     * it costs one round trip or two depends on whether the index sits at the front of the file or
     * at its end. The block index is the only thing that distinguishes those, and a handful of
     * entries per source is enough to tell them apart without turning playback into a log flood.
     */
    private fun reportSynchronousBlockLoad(blockIndex: Long) {
        if (synchronousLoadCount > MAX_REPORTED_SYNCHRONOUS_LOADS) return
        AppLog.info(
            category = "player.core2",
            event = "transport_block_loaded",
            message = "YCore loaded a media block without a ready prefetch",
            attributes =
                mapOf(
                    "blockIndex" to blockIndex.toString(),
                    "blockSize" to blockSize.toString(),
                    "loadOrdinal" to synchronousLoadCount.toString(),
                    "contentLength" to knownSize.toString(),
                ),
        )
    }

    private fun loadBlockNow(
        blockIndex: Long,
        budget: YRangeReadBudget,
        startupOffset: Int? = null,
        operation: YForegroundRangeRead,
    ): YLoadedTransportBlock {
        diskCache?.let { cache ->
            val startedNs = System.nanoTime()
            cache.readBlock(blockIndex)?.let { cached ->
                return YLoadedTransportBlock(
                    bytes = cached,
                    contentLength = cache.contentLength,
                    cacheLoadDurationMs = (System.nanoTime() - startedNs) / NANOS_PER_MILLISECOND,
                    fromDiskCache = true,
                )
            }
        }
        operation.checkActive()
        val transport = createTransport()
        try {
            operation.bind(transport)
        } catch (failure: Throwable) {
            runCatching { runBlocking { transport.close() } }
            throw failure
        }
        return loadRemoteBlockWithRetries(
            blockIndex = blockIndex,
            blockTransport = transport,
            knownSizeSnapshot = knownSize,
            budget = budget,
            isCancelled = { operation.cancelled },
            foreground = operation,
            rangeOffset = startupOffset ?: 0,
            requestedBytes =
                if (startupOffset ==
                    null
                ) {
                    blockSize
                } else {
                    minOf(STARTUP_RANGE_BYTES, blockSize - startupOffset)
                },
        )
    }

    private fun loadRemoteBlockWithRetries(
        blockIndex: Long,
        blockTransport: YMediaTransport,
        knownSizeSnapshot: Long,
        progress: YTransportBlockPrefetch? = null,
        isCancelled: () -> Boolean = { false },
        budget: YRangeReadBudget = YRangeReadBudget(rangeReadBudgetMs),
        rangeOffset: Int = 0,
        requestedBytes: Int = blockSize,
        foreground: YForegroundRangeRead? = null,
    ): YLoadedTransportBlock {
        var completedRetries = 0
        val partial = YPartialTransportBlock(ByteArray(requestedBytes))
        val startedNs = System.nanoTime()
        while (true) {
            if (closed || isCancelled() || progress?.isCancelled == true || Thread.currentThread().isInterrupted) {
                throw CancellationException("Media range was abandoned")
            }
            try {
                budget.checkRemaining()
                return loadRemoteBlock(
                    blockIndex,
                    blockTransport,
                    knownSizeSnapshot,
                    progress,
                    budget,
                    partial,
                    rangeOffset,
                    foreground,
                ).copy(
                    remoteLoadDurationMs =
                        ((System.nanoTime() - startedNs) / NANOS_PER_MILLISECOND).coerceAtLeast(
                            1L,
                        ),
                )
            } catch (failure: Exception) {
                if (closed || isCancelled() || progress?.isCancelled == true || failure.isTransportCancellation()) {
                    throw CancellationException("Media range was abandoned")
                }
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
                // A closed source is being torn down: retrying only holds up the caller that is
                // waiting to release the extractor.
                val delayMs =
                    if (closed) null else mediaRangeRetryDelayMs(completedRetries, failureKind)
                if (delayMs == null || budget.remainingMs() <= delayMs) {
                    reportTransportFailure(
                        blockIndex = blockIndex,
                        completedRetries = completedRetries,
                        failureKind = failureKind,
                        failure = failure,
                    )
                    throw failure
                }
                completedRetries++
                // Only a strong entity tag plus a known length can bind bytes across exchanges.
                if (partial.entityTag == null ||
                    partial.contentLength == null ||
                    partial.total >= partial.bytes.size
                ) {
                    partial.total = 0
                }
                // Waiting on the close latch instead of sleeping lets close() interrupt the retry
                // at once instead of pinning the extractor thread until the delay expires.
                val closedDuringWait =
                    try {
                        (foreground?.cancelLatch ?: closedLatch).await(delayMs, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw failure
                    }
                if (closedDuringWait) throw failure
            }
        }
    }

    private fun loadRemoteBlock(
        blockIndex: Long,
        blockTransport: YMediaTransport,
        knownSizeSnapshot: Long,
        progress: YTransportBlockPrefetch?,
        budget: YRangeReadBudget,
        partial: YPartialTransportBlock,
        rangeOffset: Int,
        foreground: YForegroundRangeRead? = null,
    ): YLoadedTransportBlock =
        runBlocking(foreground?.job ?: EmptyCoroutineContext) {
            val startedNs = System.nanoTime()
            progress?.beginAttempt(startedNs)
            val blockStart = blockIndex.saturatedMultiply(blockSize.toLong()).saturatedAdd(rangeOffset.toLong())
            val position = blockStart.saturatedAdd(partial.total.toLong())
            val end = blockStart.saturatedAdd(partial.bytes.size.toLong() - 1L)
            val attemptOffset = partial.total
            var transferredBytes = 0L
            bandwidthMeter.onTransferStarted(startedNs)
            val watchdog =
                AndroidRangeReadWatchdog(blockTransport, budget, idleBudgetMs = {
                    (playbackWindow.bufferedUs / playbackWindow.speed / 1_000L).toLong().coerceIn(4_000L, 12_000L)
                })
            try {
                val response =
                    blockTransport.open(
                        yCoreRandomAccessRequest(
                            uri = uri,
                            protocol = protocol,
                            startInclusive = position,
                            endInclusive = end,
                            headers =
                                if (attemptOffset >
                                    0
                                ) {
                                    headers + ("If-Range" to checkNotNull(partial.entityTag))
                                } else {
                                    headers
                                },
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
                response.acceptedRange?.endInclusive?.let { servedEnd ->
                    val expectedEnd = responseContentLength?.let { minOf(end, it - 1L) } ?: end
                    if (servedEnd != expectedEnd) {
                        throw YRangeReadException(
                            YTransportFailureKind.InvalidRange,
                            "Random-access transport returned mismatched range end",
                        )
                    }
                }
                if (attemptOffset > 0 &&
                    (response.entityTag != partial.entityTag || responseContentLength != partial.contentLength)
                ) {
                    throw YRangeReadException(
                        YTransportFailureKind.InvalidRange,
                        "Media representation changed during range resumption",
                    )
                }
                synchronized(representationLock) {
                    require(
                        representationTag == null ||
                            response.entityTag == null ||
                            response.entityTag == representationTag,
                    ) { "Remote media entity changed during playback" }
                    require(
                        representationLength == null ||
                            responseContentLength == null ||
                            responseContentLength == representationLength,
                    ) { "Remote media size changed during playback" }
                    response.entityTag?.let { representationTag = it }
                    responseContentLength?.let { representationLength = it }
                }
                partial.entityTag =
                    response.entityTag?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
                partial.contentLength = responseContentLength
                responseContentLength?.let { total ->
                    if (knownSizeSnapshot >= 0L) {
                        require(knownSizeSnapshot == total) { "Remote media size changed during playback" }
                    }
                }
                val effectiveKnownSize = responseContentLength ?: knownSizeSnapshot
                val output = partial.bytes
                var total = partial.total
                var emptyReads = 0
                while (total < output.size) {
                    val count = blockTransport.read(output, total, output.size - total)
                    if (count < 0) break
                    if (count == 0) {
                        // A transport that keeps returning 0 without ever reaching end-of-stream
                        // used to spin this thread at full CPU forever. Bound it and let the
                        // retry policy decide, the same as any other truncated range.
                        if (++emptyReads > MAX_EMPTY_TRANSPORT_READS) {
                            throw YRangeReadException(
                                failureKind = YTransportFailureKind.TransientIo,
                                safeMessage = "Random-access transport stopped producing block bytes",
                                statusCode = response.statusCode,
                                expectedRangeStart = position,
                                acceptedRangeStart = response.acceptedRange?.startInclusive,
                            )
                        }
                        continue
                    }
                    watchdog.progressed()
                    emptyReads = 0
                    total += count
                    partial.total = total
                    transferredBytes += count
                    val nowNs = System.nanoTime()
                    progress?.recordProgress(total.toLong(), nowNs)
                    bandwidthMeter
                        .onBytesTransferred(count.toLong(), nowNs)
                        ?.let { sample -> onNetworkSample?.invoke(sample.bytes, sample.durationMs) }
                }
                val expectedBytes =
                    response.acceptedRange
                        ?.endInclusive
                        ?.let { servedEnd -> servedEnd - position + 1L }
                        ?.coerceAtMost((output.size - attemptOffset).toLong())
                        ?: effectiveKnownSize.takeIf { it >= 0L }?.let {
                            (it - position).coerceIn(0L, (output.size - attemptOffset).toLong())
                        }
                if (
                    expectedBytes != null &&
                    total.toLong() - attemptOffset != expectedBytes &&
                    (effectiveKnownSize < 0L || blockStart + total != effectiveKnownSize)
                ) {
                    throw YRangeReadException(
                        failureKind = YTransportFailureKind.PrematureEof,
                        safeMessage = "Random-access transport ended before the accepted block range",
                        statusCode = response.statusCode,
                        expectedRangeStart = position,
                        acceptedRangeStart = response.acceptedRange?.startInclusive,
                    )
                }
                watchdog.checkFailure()
                transferredBytes = (total - attemptOffset).toLong()
                YLoadedTransportBlock(
                    // A full block is the common case; copyOf would duplicate the whole 2 MiB.
                    bytes = if (total == output.size) output else output.copyOf(total),
                    contentLength = responseContentLength,
                    offsetInBlock = rangeOffset,
                    remoteLoadDurationMs =
                        ((System.nanoTime() - startedNs) / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                )
            } catch (failure: Exception) {
                // A watchdog close is a retryable timeout, not a user cancellation or clean EOF.
                watchdog.checkFailure()
                throw failure
            } finally {
                watchdog.close()
                // One aggregate sample per busy period, not one per range: see
                // YAggregateBandwidthMeter for why per-range wall clocks under-report the link.
                bandwidthMeter
                    .onTransferFinished(transferredBytes, System.nanoTime())
                    ?.let { sample -> onNetworkSample?.invoke(sample.bytes, sample.durationMs) }
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
                        (
                            expectedRangeStart?.toString()
                                ?: blockIndex.saturatedMultiply(blockSize.toLong()).toString()
                        ),
                    "acceptedRangeStart" to (acceptedRangeStart?.toString() ?: "unavailable"),
                    "attemptCount" to (completedRetries + 1).toString(),
                ),
        )
    }

    private fun schedulePrefetch(blockIndex: Long) {
        if (prefetchSuppressed) return
        if (!allowsSpeculativeWork()) {
            prefetchDepthBlocks = 0
            cancelPrefetchOutside(emptySet())
            forwardCache?.updateWindow(0L, 0L)
            return
        }
        val window = playbackWindow
        prefetchDepthBlocks =
            transportPrefetchDepthBlocks(
                blockSize,
                (mediaBitRateBitsPerSecond * window.speed.toDouble()).toLong(),
            ).coerceAtMost(memoryPrefetchBlocks)
        (prefetchExecutor as? ThreadPoolExecutor)?.corePoolSize =
            transportPrefetchConcurrency(
                throughput = effectiveThroughput(System.nanoTime()),
                consumption = (mediaBitRateBitsPerSecond * window.speed.toDouble()).toLong(),
                bufferedUs = (window.bufferedUs / window.speed).toLong(),
            )
        val activeDepth =
            if (foregroundReadStartedAtNs != 0L && window.bufferedUs / window.speed < 2_000_000L) {
                minOf(prefetchDepthBlocks, 2)
            } else {
                prefetchDepthBlocks
            }
        val desired =
            (0 until activeDepth)
                .map { offset -> blockIndex.saturatedAdd(offset.toLong()) }
                .filter { candidate ->
                    shouldPrefetchTransportBlock(candidate, blockSize, knownSize) &&
                        !blocks.containsKey(candidate)
                }.toMutableSet()
        startupTailPrefetchBlockIndex
            ?.takeIf { tail ->
                shouldPrefetchTransportBlock(tail, blockSize, knownSize) &&
                    !blocks.containsKey(tail)
            }?.let(desired::add)
        startupSlice?.first?.takeIf { !blocks.containsKey(it) }?.let(desired::add)
        cancelPrefetchOutside(desired)
        desired.sorted().forEach(::schedulePrefetchBlock)
    }

    private fun schedulePrefetchBlock(blockIndex: Long) {
        if (prefetchedBlocks.containsKey(blockIndex) || blocks.containsKey(blockIndex)) return
        if (prefetchedBlocks.size >= memoryPrefetchBlocks) return
        val knownSizeSnapshot = knownSize
        val prefetch = YTransportBlockPrefetch(blockIndex)
        prefetchedBlocks[blockIndex] = prefetch
        prefetch.future =
            prefetchExecutor.submit<YLoadedTransportBlock> {
                if (!prefetch.markStarted()) throw CancellationException("Transport prefetch was promoted")
                diskCache?.let { cache ->
                    val cacheStartedNs = System.nanoTime()
                    cache.readBlock(blockIndex)?.let { cached ->
                        return@submit YLoadedTransportBlock(
                            bytes = cached,
                            contentLength = cache.contentLength,
                            cacheLoadDurationMs =
                                (System.nanoTime() - cacheStartedNs) / NANOS_PER_MILLISECOND,
                            fromDiskCache = true,
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
                    loadRemoteBlockWithRetries(blockIndex, prefetchTransport, knownSizeSnapshot, prefetch)
                } finally {
                    prefetch.unbind(prefetchTransport)
                    synchronized(prefetchTransportLock) {
                        activePrefetchTransports.remove(prefetchTransport)
                    }
                }
            }
    }

    private fun takePrefetchedBlock(
        blockIndex: Long,
        budget: YRangeReadBudget,
        operation: YForegroundRangeRead,
    ): YLoadedTransportBlock? {
        val prefetch = prefetchedBlocks.remove(blockIndex) ?: return null
        // A foreground MediaExtractor read must never sit behind speculative ranges. If its future
        // has not started, remove it from the executor queue and load through the primary transport
        // immediately. This is the exact head-of-line case where diagnostics showed a 22 s resolve
        // wait for a block whose actual network transfer took only 2.2 s.
        if (shouldPromoteTransportPrefetch(prefetch.future.isDone, prefetch.started)) {
            promotedPrefetchCount++
            prefetch.cancel()
            return null
        }
        shedSpeculativeWorkFor(blockIndex)
        return try {
            val waitStartedNs = System.nanoTime()
            var loaded: YLoadedTransportBlock? = null
            while (loaded == null) {
                if (operation.cancelled) {
                    prefetch.cancel()
                    operation.checkActive()
                }
                try {
                    loaded = prefetch.future.get(50L, TimeUnit.MILLISECONDS)
                } catch (timeout: TimeoutException) {
                    val nowNs = System.nanoTime()
                    if (budget.remainingMs() == 0L ||
                        !shouldKeepTransportPrefetch(
                            waitedMs = (nowNs - waitStartedNs) / NANOS_PER_MILLISECOND,
                            idleMs = (nowNs - prefetch.lastProgressNs.get()) / NANOS_PER_MILLISECOND,
                            completedBytes = prefetch.completedBytes.get(),
                            blockBytes = blockSize.toLong(),
                            bufferedMs = (playbackWindow.bufferedUs / playbackWindow.speed / 1_000L).toLong(),
                        )
                    ) {
                        throw timeout
                    }
                }
            }
            loaded
        } catch (_: TimeoutException) {
            // A stalled range is promoted only after checking live byte progress and headroom.
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

    private fun shedSpeculativeWorkFor(blockIndex: Long) {
        if (playbackWindow.bufferedUs / playbackWindow.speed >= 2_000_000L) return
        forwardCache?.updateWindow(0L, 0L)
        cancelPrefetchOutside(
            prefetchedBlocks
                .filter { (index, pending) ->
                    pending.future.isDone || index in blockIndex..blockIndex.saturatedAdd(2L)
                }.keys,
        )
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
        // Future.cancel clears the body, but a queued cancelled Future still retains its task wrapper.
        (prefetchExecutor as? ThreadPoolExecutor)?.purge()
    }

    private fun bufferedAheadBytesSnapshot(): Long {
        var cursor = latestReadPosition.coerceAtLeast(0L)
        val start = cursor
        var blockIndex = cursor / blockSize
        var scannedBlocks = 0
        val scanLimit =
            maxOf(
                prefetchDepthBlocks + TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS,
                (cacheMaximumBytes / blockSize).coerceAtMost(1024L).toInt(),
            )
        while (scannedBlocks < scanLimit) {
            val length =
                blocks[blockIndex]?.size ?: completedPrefetchBytes(blockIndex)?.size
                    ?: diskCache?.cachedBlockLength(blockIndex) ?: break
            val blockStart = blockIndex.saturatedMultiply(blockSize.toLong())
            val blockEnd = blockStart.saturatedAdd(length.toLong())
            if (cursor < blockEnd) {
                cursor = blockEnd
            }
            if (length < blockSize) break
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
        if (knownSize < 0L) {
            val probe = ByteArray(1)
            prefetchSuppressed = true
            try {
                readAt(0L, probe, 0, 1)
            } finally {
                prefetchSuppressed = false
            }
        }
        // MediaSource metadata often already includes Content-Length. That path must also
        // overlap index I/O, but repeated getSize() calls must not restart an evicted probe.
        if (!startupTailPrefetchScheduled && knownSize > blockSize) {
            startupTailPrefetchScheduled = true
            val tailBlock = (knownSize - 1L) / blockSize
            if (tailBlock > 1L) {
                startupTailPrefetchBlockIndex = tailBlock
                schedulePrefetchBlock(tailBlock)
            }
        }
        return knownSize
    }

    /**
     * Not `@Synchronized` on purpose. [readAt] holds this instance's monitor for the whole of a
     * blocking range fetch, so a synchronized close would wait for a stalled origin before it
     * could even signal shutdown - and closing the transport is exactly what unblocks that read.
     * Shutdown is therefore signalled and the sockets are closed first; the monitor is only taken
     * afterwards, to drop the cached blocks once the reader has unwound.
     */
    override fun close() {
        if (closed) return
        closed = true
        cancelPendingRead()
        closedLatch.countDown()
        forwardCache?.close()
        prefetchExecutor.shutdownNow()
        val prefetchTransports =
            synchronized(prefetchTransportLock) {
                activePrefetchTransports.toList().also { activePrefetchTransports.clear() }
            }
        runBlocking {
            prefetchTransports.forEach { prefetchTransport -> prefetchTransport.close() }
        }
        discardCachedBlocks()
        memoryLease.close()
    }

    @Synchronized
    private fun discardCachedBlocks() {
        cancelPrefetchOutside(emptySet())
        blocks.clear()
        startupSlice = null
        cachedBytes = 0L
    }

    private fun cache(
        index: Long,
        block: ByteArray,
    ) {
        if (startupSlice?.first == index) startupSlice = null
        blocks.put(index, block)?.let { cachedBytes -= it.size }
        cachedBytes += block.size
        trimMemoryCache()
    }

    private fun trimMemoryCache() {
        val iterator = blocks.entries.iterator()
        while (cachedBytes > memoryCacheBytes && iterator.hasNext()) {
            cachedBytes -= iterator.next().value.size
            iterator.remove()
        }
    }

    /** Called only while the extractor owner holds this source's monitor, including paused QoE ticks. */
    private fun reclaimMemoryBudget() {
        refreshMemoryPressure()
        trimMemoryCache()
        val speculative = allowsSpeculativeWork()
        if (!speculative || memoryLease.limitBytes < 2L * blockSize + STARTUP_RANGE_BYTES) startupSlice = null
        val retainedCount = memoryPrefetchBlocks
        if (prefetchedBlocks.size > retainedCount) {
            val currentBlock = latestReadPosition / blockSize
            val retained =
                prefetchedBlocks.keys
                    .sortedBy { kotlin.math.abs(it - currentBlock) }
                    .take(retainedCount)
                    .toSet()
            cancelPrefetchOutside(retained)
        }
        prefetchDepthBlocks = minOf(prefetchDepthBlocks, retainedCount)
        if (!speculative) forwardCache?.updateWindow(0L, 0L)
    }

    private fun effectiveThroughput(nowNs: Long): Long =
        bandwidthMeter.bitsPerSecond(
            nowNs,
            fastDecrease = playbackWindow.bufferedUs / playbackWindow.speed < 3_000_000L,
        )

    /** A short bandwidth-meter lock only; never takes the blocking readAt monitor. */
    fun liveTransportThroughput(): Long? {
        val rate = effectiveThroughput(System.nanoTime())
        return rate.takeIf { bandwidthMeter.hasEstimate }
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
    val offsetInBlock: Int = 0,
    val fromDiskCache: Boolean = false,
)

private class YPartialTransportBlock(
    val bytes: ByteArray,
) {
    var total = 0
    var entityTag: String? = null
    var contentLength: Long? = null
}

private class YTransportBlockPrefetch(
    val blockIndex: Long,
) {
    private val cancelled = AtomicBoolean(false)
    private val executionStarted = AtomicBoolean(false)
    private var activeTransport: YMediaTransport? = null
    val completedBytes = AtomicLong()
    val lastProgressNs = AtomicLong(System.nanoTime())

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
        val transport = synchronized(this) { activeTransport.also { activeTransport = null } }
        // Capture the socket owner before interrupting the worker: its finally block can unbind
        // immediately, while a cancelled coroutine still has blocking network I/O to tear down.
        future.cancel(true)
        transport?.let { active -> runCatching { runBlocking { active.close() } } }
    }
}

private class YForegroundRangeRead {
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
        abandoned.set(true)
        cancelLatch.countDown()
        job.cancel()
        val active = synchronized(this) { transport }
        active?.let { runCatching { runBlocking { it.close() } } }
    }

    fun finish() {
        job.complete()
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

internal fun isRecoverableMediaReadFailure(failure: Throwable?): Boolean {
    var current = failure
    repeat(8) {
        val cause = current ?: return false
        when (cause) {
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
private const val STARTUP_RANGE_BYTES = 128 * 1024
private const val DEFAULT_TRANSPORT_CACHE_BYTES = 64L * 1024L * 1024L

/** Backward window kept on the heap, in blocks. Forward bytes live in the prefetch futures. */
private const val MEMORY_CACHE_BLOCKS = 8L

/** Consecutive zero-length transport reads tolerated before a block counts as failed. */
private const val MAX_EMPTY_TRANSPORT_READS = 64
private const val TRANSPORT_PREFETCH_THREAD_NAME = "YCore-TransportPrefetch"
private const val DEFAULT_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 2
private const val MAX_TRANSPORT_PREFETCH_DEPTH_BLOCKS = 24

// Six ordered ranges hide the long-tail range latency observed on remote high-bitrate remuxes
// without allowing the full twenty-second window to open one socket per block.
// Foreground reads separately promote any queued block they need immediately.
internal const val MAX_TRANSPORT_PREFETCH_CONCURRENCY = 6
private const val TARGET_TRANSPORT_PREFETCH_WINDOW_MS = 20_000L
private const val TRANSPORT_PREFETCH_SAFETY_BLOCKS = 1L
private const val TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS = 2
private const val BITS_PER_BYTE = 8L
private const val MILLIS_PER_SECOND = 1_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

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
        consumption <= 0L || throughput <= 0L -> 3
        bufferedUs < 3_000_000L && throughput < consumption -> 2
        bufferedUs >= 8_000_000L && throughput > consumption -> 2
        bufferedUs < 2_000_000L && throughput > consumption * 1.5 -> MAX_TRANSPORT_PREFETCH_CONCURRENCY
        else -> 4
    }

/** Enough blocking loads to show a cold start's access pattern; playback then falls silent. */
private const val MAX_REPORTED_SYNCHRONOUS_LOADS = 8L
private const val MAX_SAFE_EXCEPTION_CHAIN_DEPTH = 4
