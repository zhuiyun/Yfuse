package com.yfuse.core2.android

import android.media.MediaDataSource
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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
    /** Only bounded background warmers opt in; playback keeps non-blocking cache admission. */
    private val persistReadBlocks: Boolean = false,
    /** Proxy startup follows the demuxer's demand before spending bandwidth on distant ranges. */
    private val prioritizeStartupReads: Boolean = false,
    memoryLeaseOverride: PlaybackMemoryLease? = null,
    private val allowsSpeculativeWork: () -> Boolean = { AndroidPlaybackMemoryBudget.allowsSpeculativeWork },
    private val refreshMemoryPressure: () -> Unit = AndroidPlaybackMemoryBudget::refreshPressure,
    private val representationSession: AndroidMediaRepresentationSession? = null,
    private val rangeReadClock: () -> Long = System::nanoTime,
    private val originBoundHeaderNames: Set<String> = emptySet(),
) : MediaDataSource(),
    BufferedScrubMedia.CachedSource {
    private var scrubWriter: BufferedScrubMedia.Writer? = null
    private val sourceInstance = transportSourceSequence.incrementAndGet().toString()
    private val sourceTrace by lazy {
        com.yfuse.core.logging
            .playbackDiagnosticTrace(uri)
    }

    @Volatile
    private var foregroundRead: YForegroundRangeRead? = null

    /** Cancels only the current read. A later seek/read uses a fresh transport and coroutine. */
    fun cancelPendingRead() {
        foregroundRead?.cancel()
    }

    /** Permanently stops admission before cancelling I/O; final cache disposal stays on its owner. */
    fun cancelReads() {
        closed = true
        closedLatch.countDown()
        cancelPendingRead()
        prefetchExecutor.shutdownNow()
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
    private var representationSnapshot: AndroidMediaRepresentationSession.Snapshot? = null

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
        ThreadPoolExecutor(
            2,
            MAX_TRANSPORT_PREFETCH_CONCURRENCY,
            15L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
        ) { runnable ->
            Thread(
                runnable,
                "$TRANSPORT_PREFETCH_THREAD_NAME-${prefetchThreadIndex.incrementAndGet()}",
            ).apply { isDaemon = true }
        }.apply {
            allowCoreThreadTimeOut(true)
        }
    private val prefetchTransportLock = Any()
    private val bandwidthMeter = YAggregateBandwidthMeter()
    private var cachedBytes = 0L

    @Volatile private var knownSize = -1L

    @Volatile private var cacheValidated = diskCache == null && representationSession == null

    private fun validatePersistentCache() {
        if (cacheValidated) return
        val budget = YRangeReadBudget(rangeReadBudgetMs, rangeReadClock)
        // One bounded foreground range validates even same-length replacements before any cached read.
        val operation = YForegroundRangeRead()
        foregroundRead = operation
        try {
            fun ensureActive() {
                if (closed || Thread.currentThread().isInterrupted) throw CancellationException("Media source closed")
                operation.checkActive()
                budget.checkRemaining()
            }

            fun loadInitialRange(): AndroidMediaRepresentationSession.InitialRange {
                ensureActive()
                val transport = createTransport()
                try {
                    operation.bind(transport)
                    val fresh =
                        loadRemoteBlockWithRetries(
                            0L,
                            transport,
                            -1L,
                            budget = budget,
                            requestedBytes = minOf(STARTUP_RANGE_BYTES, blockSize),
                            isCancelled = { operation.cancelled },
                            foreground = operation,
                        )
                    ensureActive()
                    return AndroidMediaRepresentationSession.InitialRange(
                        fresh.bytes,
                        fresh.contentLength,
                        representationTag?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') },
                    )
                } finally {
                    runCatching { runBlocking { transport.close() } }
                }
            }
            val snapshot = representationSession?.obtain(::ensureActive, ::loadInitialRange)
            val fresh = snapshot?.initial ?: loadInitialRange()
            ensureActive()
            if (snapshot != null) {
                representationSnapshot = snapshot
                if (!checkNotNull(representationSession).accept(snapshot) {
                        diskCache?.validateRepresentation(fresh.contentLength, fresh.entityTag)
                    }
                ) {
                    throwRepresentationExpired()
                }
            } else {
                diskCache?.validateRepresentation(fresh.contentLength, fresh.entityTag)
            }
            representationTag = fresh.entityTag
            representationLength = fresh.contentLength
            knownSize = fresh.contentLength ?: -1L
            startupSlice = 0L to YLoadedTransportBlock(fresh.bytes, fresh.contentLength)
            cacheValidated = true
        } catch (failure: Throwable) {
            if (!closed && !operation.cancelled && !failure.isTransportCancellation()) foregroundFailure = failure
            throw failure
        } finally {
            if (foregroundRead === operation) foregroundRead = null
            operation.finish()
        }
    }

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
                    cacheValidated &&
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
    private var contiguousReadStart = 0L
    private val readHeads = YTransportReadHeads()

    private val activePrefetchTransports = mutableSetOf<YMediaTransport>()
    private val transportRouteLogged = AtomicBoolean(false)
    private val transportFailureLogged = AtomicBoolean(false)

    @Volatile
    private var closed = false
    private val resourcesClosed = AtomicBoolean(false)

    /** Released by [close] so a retry wait on the extractor thread ends immediately. */
    private val closedLatch = CountDownLatch(1)

    /** Start of the in-flight foreground range fetch, or 0 when no read is blocked. */
    @Volatile
    private var foregroundReadStartedAtNs = 0L

    @Volatile
    private var foregroundFailure: Throwable? = null

    init {
        BufferedScrubMedia.register(this)
    }

    override fun matches(
        uri: String,
        headers: Map<String, String>,
        credentials: YTransportCredentials?,
    ): Boolean =
        !closed &&
            !resourcesClosed.get() &&
            this.uri == uri &&
            this.credentials == credentials &&
            headers.all { (k, v) -> this.headers.entries.any { it.key.equals(k, true) && it.value == v } } &&
            this.headers.none { (k, _) ->
                (k.equals("Authorization", true) || k.equals("Cookie", true)) &&
                    headers.keys.none { it.equals(k, true) } &&
                    !(k.equals("Authorization", true) && credentials != null)
            }

    override fun cachedSize(): Long = if (closed || !cacheValidated) -1L else knownSize

    /** A separate preview cursor. This path may only inspect a completed range; it never calls resolveBlock,
     * getSize, readAt, validation, prefetch, or the transport. It does not move the playback cursor. */
    @Synchronized
    override fun cachedRead(
        position: Long,
        bytes: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        if (closed || !cacheValidated || knownSize <= 0) throw java.io.EOFException("Inactive cache")
        if (position >= knownSize) return -1
        val wanted = minOf(size.toLong(), knownSize - position).toInt()
        var done = 0
        while (done < wanted) {
            val p = position + done
            val index = p / blockSize
            val at = (p % blockSize).toInt()
            val slice =
                startupSlice
                    ?.takeIf {
                        it.first == index &&
                            at in it.second.offsetInBlock until (it.second.offsetInBlock + it.second.bytes.size)
                    }?.second
            val partial =
                slice
                    ?: completedPrefetchBlock(index)?.takeIf {
                        at in
                            it.offsetInBlock until (it.offsetInBlock + it.bytes.size)
                    }
            val data =
                partial?.bytes ?: blocks[index] ?: diskCache?.readBlock(index)
                    ?: throw java.io.EOFException("Unbuffered")
            val within = at - (partial?.offsetInBlock ?: 0)
            val count = minOf(wanted - done, data.size - within)
            if (within < 0 || count <= 0) throw java.io.EOFException("Sparse cache hole")
            data.copyInto(bytes, offset + done, within, within + count)
            done += count
        }
        return done
    }

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
        validatePersistentCache()
        ensureRepresentationCurrent()
        if (knownSize >= 0L && position >= knownSize) return -1
        if (position != latestReadPosition) contiguousReadStart = position
        // Returning to the other run of a non-interleaved file is not a seek.
        val continuesRun = readHeads.record(position / blockSize, System.nanoTime())
        if (!continuesRun && kotlin.math.abs(position - latestReadPosition) > blockSize.toLong() * 2L) {
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
                        requestedOffset = offsetInBlock,
                        requestedBytes = minOf(remaining, blockSize - offsetInBlock),
                        startupOffset = startupRangeOffset(readPosition, offsetInBlock, size),
                    )
            val block = loaded.bytes
            val writer = scrubWriter ?: BufferedScrubMedia.lookup(uri, headers, credentials)?.also { scrubWriter = it }
            val blockOffset = offsetInBlock - loaded.offsetInBlock
            if (blockOffset >= block.size) break
            val count = minOf(remaining, block.size - blockOffset)
            block.copyInto(buffer, outputOffset, blockOffset, blockOffset + count)
            writer?.put(readPosition, block, blockOffset, count, knownSize)
            readPosition += count
            if (!prefetchSuppressed) {
                val approachingBoundary =
                    blockSize > STARTUP_RANGE_BYTES &&
                        readPosition - contiguousReadStart >= STARTUP_RANGE_BYTES &&
                        offsetInBlock + count >= blockSize - STARTUP_RANGE_BYTES
                schedulePrefetch(blockIndex + 1L, approachingBoundary)
            }
            outputOffset += count
            remaining -= count
        }
        val copied = size - remaining
        if (copied > 0) {
            if (!prefetchSuppressed) startupReadServed = true
            latestReadPosition = readPosition
            scheduleForwardCache()
        }
        ensureRepresentationCurrent()
        return if (copied == 0) -1 else copied
    }

    /** The codec pump never waits on a range-read monitor to publish feedback. */
    fun updatePlaybackWindow(window: YTransportPlaybackWindow) {
        playbackWindow = window
    }

    private fun startupRangeOffset(
        position: Long,
        offsetInBlock: Int,
        requestSize: Int,
    ): Int? {
        if (
            persistReadBlocks ||
            (startupReadServed && playbackWindow.playing) ||
            blockSize <= STARTUP_RANGE_BYTES ||
            requestSize > STARTUP_RANGE_BYTES
        ) {
            return null
        }
        // A queued prefetch can be promoted before its worker starts. Its foreground replacement
        // should fetch the same whole block when the demuxer is already reading sequentially.
        val continuingAtBoundary =
            prioritizeStartupReads && offsetInBlock == 0 && position - contiguousReadStart >= STARTUP_RANGE_BYTES
        return offsetInBlock.takeUnless {
            continuingAtBoundary && !prefetchSuppressed && memoryCacheBytes >= blockSize && allowsSpeculativeWork()
        }
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
        // Follow the heavier run. Handing the warmer each run in turn made every alternation a
        // disjoint window, which abandons the disk request it had in flight.
        val base =
            readHeads.primaryBlock(System.nanoTime())?.let { it * blockSize } ?: latestReadPosition
        val first = base / blockSize + prefetchDepthBlocks + 1L
        val end = minOf((knownSize + blockSize - 1L) / blockSize, (base + targetBytes) / blockSize)
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
        return ((System.nanoTime() - startedAtNs).coerceAtLeast(0L)) / TRANSPORT_NANOS_PER_MILLISECOND
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
        requestedOffset: Int = 0,
        requestedBytes: Int = blockSize,
    ): YLoadedTransportBlock {
        if (startupTailPrefetchBlockIndex == blockIndex) startupTailPrefetchBlockIndex = null
        val startedNs = System.nanoTime()
        val budget = YRangeReadBudget(rangeReadBudgetMs, rangeReadClock)
        val operation = YForegroundRangeRead()
        foregroundRead = operation
        foregroundReadStartedAtNs = startedNs
        onBlockingReadStateChanged?.invoke(true)
        try {
            // Join an already running range instead of cancelling it for every small extractor
            // read. Queued/stalled ranges are still promoted by takePrefetchedBlock's budget.
            val prefetched = takePrefetchedBlock(blockIndex, budget, operation, requestedOffset, requestedBytes)
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
                    ((System.nanoTime() - startedNs) / TRANSPORT_NANOS_PER_MILLISECOND).coerceAtLeast(0L),
                )
            maximumRemoteLoadMs = maxOf(maximumRemoteLoadMs, loaded.remoteLoadDurationMs)
            maximumCacheLoadMs = maxOf(maximumCacheLoadMs, loaded.cacheLoadDurationMs)
            loaded.contentLength?.let { contentLength ->
                if (knownSize >= 0L && knownSize != contentLength) representationChanged()
                knownSize = contentLength
            }
            if (isCompleteBlock(blockIndex, loaded)) {
                cache(blockIndex, loaded.bytes)
                if (loaded.bytes.isNotEmpty() && !loaded.fromDiskCache) {
                    diskCache?.let { persistent ->
                        if (persistReadBlocks) {
                            persistent.writeBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })
                        } else {
                            persistent.enqueueWriteBlock(blockIndex, loaded.bytes, knownSize.takeIf { it >= 0L })
                        }
                    }
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

    /** From the start of block [blockIndex] to the end of its stride, or to the end of the media. */
    private fun isCompleteBlock(
        blockIndex: Long,
        loaded: YLoadedTransportBlock,
    ): Boolean =
        loaded.offsetInBlock == 0 &&
            (loaded.bytes.size == blockSize || blockIndex * blockSize + loaded.bytes.size == knownSize)

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
                    cacheLoadDurationMs = (System.nanoTime() - startedNs) / TRANSPORT_NANOS_PER_MILLISECOND,
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

    private fun representationChanged(): Nothing {
        if (representationSession != null) {
            representationSession.invalidate(representationSnapshot) { diskCache?.invalidate() }
        } else {
            diskCache?.invalidate()
        }
        throwRepresentationExpired()
    }

    private fun ensureRepresentationCurrent() {
        val snapshot = representationSnapshot ?: return
        if (representationSession?.isCurrent(snapshot) == false) throwRepresentationExpired()
    }

    private fun throwRepresentationExpired(): Nothing =
        throw YRangeReadException(
            YTransportFailureKind.InvalidRange,
            "Remote media representation changed; reopen required",
        )

    private fun loadRemoteBlockWithRetries(
        blockIndex: Long,
        blockTransport: YMediaTransport,
        knownSizeSnapshot: Long,
        progress: YTransportBlockPrefetch? = null,
        isCancelled: () -> Boolean = { false },
        budget: YRangeReadBudget = YRangeReadBudget(rangeReadBudgetMs, rangeReadClock),
        rangeOffset: Int = 0,
        requestedBytes: Int = blockSize,
        foreground: YForegroundRangeRead? = null,
    ): YLoadedTransportBlock {
        var completedRetries = 0
        var partial = YPartialTransportBlock(ByteArray(requestedBytes))
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
                    completedRetries,
                ).copy(
                    remoteLoadDurationMs =
                        ((System.nanoTime() - startedNs) / TRANSPORT_NANOS_PER_MILLISECOND).coerceAtLeast(
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
                    // Published prefixes may still be read by MediaExtractor. Never overwrite
                    // them when a response without a strong validator must restart at zero.
                    progress?.available?.clear()
                    partial = YPartialTransportBlock(ByteArray(requestedBytes))
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
        retry: Int = 0,
    ): YLoadedTransportBlock =
        runBlocking(foreground?.job ?: EmptyCoroutineContext) {
            val startedNs = System.nanoTime()
            progress?.beginAttempt(startedNs)
            val blockStart = blockIndex.saturatedMultiply(blockSize.toLong()).saturatedAdd(rangeOffset.toLong())
            val position = blockStart.saturatedAdd(partial.total.toLong())
            val requestedEnd = blockStart.saturatedAdd(partial.bytes.size.toLong() - 1L)
            val validatedLength = partial.contentLength ?: knownSizeSnapshot.takeIf { it >= 0L }
            val end = validatedLength?.let { minOf(requestedEnd, it - 1L) } ?: requestedEnd
            val attemptOffset = partial.total
            var transferredBytes = 0L
            val diagnostics =
                AndroidRangeReadDiagnostics(sourceTrace, position, end, retry, foreground != null, sourceInstance)
            diagnostics.queueWaitMs = progress?.queueWaitMs ?: 0L
            bandwidthMeter.onTransferStarted(startedNs)
            val watchdog =
                AndroidRangeReadWatchdog(blockTransport, budget, idleBudgetMs = {
                    (playbackWindow.bufferedUs / playbackWindow.speed / 1_000L).toLong().coerceIn(4_000L, 12_000L)
                }, onDiagnosticTick = diagnostics::tick)
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
                                } else if (representationSnapshot != null &&
                                    representationTag?.startsWith('"') == true
                                ) {
                                    headers + ("If-Range" to checkNotNull(representationTag))
                                } else {
                                    headers
                                },
                            credentials = credentials,
                            originBoundHeaderNames = originBoundHeaderNames,
                        ),
                    )
                diagnostics.status = response.statusCode
                diagnostics.headersMs = (System.nanoTime() - startedNs) / TRANSPORT_NANOS_PER_MILLISECOND
                diagnostics.phase = "reading"
                if (response.statusCode != 206) {
                    if (representationSnapshot != null && response.statusCode in setOf(200, 412, 416)) {
                        representationChanged()
                    }
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
                progress?.progressiveEntityTag?.let { expectedTag ->
                    if (response.entityTag != expectedTag) representationChanged()
                }
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
                    representationChanged()
                }
                synchronized(representationLock) {
                    if (representationSnapshot?.initial?.entityTag?.startsWith('"') == true &&
                        (response.entityTag != representationTag || responseContentLength != representationLength)
                    ) {
                        representationChanged()
                    }
                    if (representationTag != null &&
                        response.entityTag != null &&
                        response.entityTag != representationTag ||
                        representationLength != null &&
                        responseContentLength != null &&
                        responseContentLength != representationLength
                    ) {
                        representationChanged()
                    }
                    response.entityTag?.let { representationTag = it }
                    responseContentLength?.let { representationLength = it }
                }
                partial.entityTag =
                    response.entityTag?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }
                partial.contentLength = responseContentLength
                responseContentLength?.let { total ->
                    if (knownSizeSnapshot >= 0L) {
                        if (knownSizeSnapshot != total) representationChanged()
                    }
                }
                val effectiveKnownSize = responseContentLength ?: knownSizeSnapshot
                val output = partial.bytes
                val expectedBytes =
                    response.acceptedRange
                        ?.endInclusive
                        ?.let { servedEnd -> servedEnd - position + 1L }
                        ?.coerceAtMost((output.size - attemptOffset).toLong())
                        ?: effectiveKnownSize.takeIf { it >= 0L }?.let {
                            (it - position).coerceIn(0L, (output.size - attemptOffset).toLong())
                        }
                val expectedTotal = expectedBytes?.let { attemptOffset + it.toInt() } ?: output.size
                var total = partial.total
                var emptyReads = 0
                // Content-Range defines the exchange boundary. Waiting for a further EOF after
                // receiving its last byte can time out on a keep-alive connection and resume at
                // the file length, turning a complete tail/index read into an HTTP 416 failure.
                while (total < expectedTotal) {
                    val count = blockTransport.read(output, total, expectedTotal - total)
                    if (count < 0) break
                    if (count == 0) {
                        // A transport that keeps returning 0 without ever reaching end-of-stream
                        // used to spin this thread at full CPU forever. Bound it and let the
                        // retry policy decide, the same as any other truncated range.
                        if (++emptyReads > TRANSPORT_MAX_EMPTY_READS) {
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
                    if (diagnostics.firstByteMs < 0L) {
                        diagnostics.firstByteMs = (System.nanoTime() - startedNs) / TRANSPORT_NANOS_PER_MILLISECOND
                    }
                    emptyReads = 0
                    total += count
                    partial.total = total
                    transferredBytes += count
                    diagnostics.bytes.addAndGet(count.toLong())
                    val nowNs = System.nanoTime()
                    progress?.recordProgress(total.toLong(), nowNs)
                    // A consumed prefix may outlive this exchange. Only expose it when a
                    // strong validator can bind any resumed tail to the same representation.
                    if (partial.entityTag != null && responseContentLength != null) {
                        progress?.progressiveEntityTag = partial.entityTag
                        progress?.available?.publish(output, total, responseContentLength)
                    }
                    bandwidthMeter
                        .onBytesTransferred(count.toLong(), nowNs)
                        ?.let { sample -> onNetworkSample?.invoke(sample.bytes, sample.durationMs) }
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
                ensureRepresentationCurrent()
                transferredBytes = (total - attemptOffset).toLong()
                diagnostics.phase = "completed"
                YLoadedTransportBlock(
                    // A full block is the common case; copyOf would duplicate the whole 2 MiB.
                    bytes = if (total == output.size) output else output.copyOf(total),
                    contentLength = responseContentLength,
                    offsetInBlock = rangeOffset,
                    remoteLoadDurationMs =
                        ((System.nanoTime() - startedNs) / TRANSPORT_NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                )
            } catch (failure: Exception) {
                val cancelled =
                    closed ||
                        progress?.isCancelled == true ||
                        foreground?.cancelled == true ||
                        failure.isTransportCancellation()
                diagnostics.phase = if (cancelled) "cancelled" else "failed"
                diagnostics.reason =
                    when {
                        closed -> "source_closed"
                        progress?.isCancelled == true -> progress.cancellationReason
                        foreground?.cancelled == true -> "foreground_superseded"
                        failure.isTransportCancellation() -> "operation_cancelled"
                        else -> failure.javaClass.simpleName
                    }
                failure.mediaHttpStatus()?.let { diagnostics.status = it }
                // A watchdog close is a retryable timeout, not a user cancellation or clean EOF.
                watchdog.checkFailure()
                throw failure
            } finally {
                watchdog.close()
                diagnostics.finish()
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
                ) +
                    if (failure is AndroidMediaRedirectException) {
                        mapOf(
                            "redirectReason" to failure.reason.name,
                            "redirectCount" to failure.redirectCount.toString(),
                            "locationPresent" to failure.locationPresent.toString(),
                        )
                    } else {
                        emptyMap()
                    },
        )
    }

    private fun schedulePrefetch(
        blockIndex: Long,
        approachingSequentialBoundary: Boolean = false,
    ) {
        if (prefetchSuppressed) return
        if (!allowsSpeculativeWork()) {
            prefetchDepthBlocks = 0
            cancelPrefetchOutside(emptySet(), "memory_pressure")
            forwardCache?.updateWindow(0L, 0L)
            return
        }
        val window = playbackWindow
        prefetchDepthBlocks =
            transportPrefetchDepthBlocks(
                blockSize,
                (mediaBitRateBitsPerSecond * window.speed.toDouble()).toLong(),
            ).coerceAtMost(memoryPrefetchBlocks)
        val desiredConcurrency =
            transportPrefetchConcurrency(
                throughput = effectiveThroughput(System.nanoTime()),
                consumption = (mediaBitRateBitsPerSecond * window.speed.toDouble()).toLong(),
                bufferedUs = (window.bufferedUs / window.speed).toLong(),
            )
        (prefetchExecutor as? ThreadPoolExecutor)?.let { pool ->
            if (desiredConcurrency > pool.maximumPoolSize) pool.maximumPoolSize = desiredConcurrency
            pool.corePoolSize = desiredConcurrency
        }
        val startupReadsOnly = prioritizeStartupReads && !window.playing
        val activeDepth =
            when {
                // A demuxer reading through a block can consume the next response progressively.
                // Sparse header and index probes still request only their own block.
                startupReadsOnly -> if (approachingSequentialBoundary) minOf(prefetchDepthBlocks, 1) else 0
                !window.playing || window.bufferedUs / window.speed < 2_000_000L -> minOf(prefetchDepthBlocks, 2)
                else -> prefetchDepthBlocks
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
        // A slice that already holds its whole block, as the startup range of a file no larger than
        // that range does, leaves nothing for a prefetch to fill: it would only request the same bytes.
        startupSlice
            ?.takeIf { (index, slice) -> !blocks.containsKey(index) && !isCompleteBlock(index, slice) }
            ?.first
            ?.let(desired::add)
        // Shrinking concurrency under pressure must not discard nearly downloaded forward
        // blocks; that turns a temporary shortage into another download of the same bytes.
        prefetchedBlocks.forEach { (index, pending) ->
            if (index in blockIndex until blockIndex.saturatedAdd(prefetchDepthBlocks.toLong()) &&
                (pending.future.isDone || pending.completedBytes.get() > 0L)
            ) {
                desired.add(index)
            }
        }
        // A non-interleaved file is read in two runs; the one not being read right now keeps a
        // window of its own instead of losing its read-ahead at every alternation.
        val otherRuns = if (startupReadsOnly) emptySet() else otherRunPrefetchBlocks(blockIndex - 1L, activeDepth)
        // Index reads can still land anywhere. Give at most two moving ranges a chance to finish
        // instead of repeatedly discarding them as that window moves.
        if (!startupReadsOnly) retainMovingPrefetches(desired)
        cancelPrefetchOutside(desired + otherRuns)
        // The current run first: under a tight memory budget it is the one about to be read.
        desired.sorted().forEach(::schedulePrefetchBlock)
        otherRuns.sorted().forEach(::schedulePrefetchBlock)
    }

    private fun otherRunPrefetchBlocks(
        currentBlock: Long,
        activeDepth: Int,
    ): Set<Long> {
        val depth = maxOf(OTHER_RUN_MIN_PREFETCH_BLOCKS, activeDepth / 2).toLong()
        return readHeads
            .otherHeads(currentBlock, System.nanoTime())
            .flatMap { head -> (1L..depth).map { head.saturatedAdd(it) } }
            .filter { candidate ->
                shouldPrefetchTransportBlock(candidate, blockSize, knownSize) && !blocks.containsKey(candidate)
            }.toSet()
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
                                (System.nanoTime() - cacheStartedNs) / TRANSPORT_NANOS_PER_MILLISECOND,
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
        requestedOffset: Int,
        requestedBytes: Int,
    ): YLoadedTransportBlock? {
        val prefetch = prefetchedBlocks[blockIndex] ?: return null
        // A foreground MediaExtractor read must never sit behind speculative ranges. If its future
        // has not started, remove it from the executor queue and load through the primary transport
        // immediately. This is the exact head-of-line case where diagnostics showed a 22 s resolve
        // wait for a block whose actual network transfer took only 2.2 s.
        if (shouldPromoteTransportPrefetch(prefetch.future.isDone, prefetch.started)) {
            prefetchedBlocks.remove(blockIndex)
            promotedPrefetchCount++
            prefetch.cancel("foreground_promotion")
            return null
        }
        shedSpeculativeWorkFor(blockIndex)
        return try {
            val waitStartedNs = System.nanoTime()
            var loaded: YLoadedTransportBlock? = null
            while (loaded == null) {
                if (operation.cancelled) {
                    prefetch.cancel("foreground_superseded")
                    operation.checkActive()
                }
                // A 2 MiB block may take seconds on a variable link. Feed bytes already
                // received to the demuxer while the same request fills the rest of the block.
                // Completed futures still go through get(), including range/EOF failures.
                if (!persistReadBlocks && !prefetch.future.isDone) {
                    prefetch.available.read(requestedOffset, minOf(requestedBytes, STARTUP_RANGE_BYTES))?.let { slice ->
                        val expectedSize =
                            slice.contentLength
                                ?.let { (it - blockIndex * blockSize).coerceIn(0L, blockSize.toLong()).toInt() }
                                ?: blockSize
                        // The last bytes wait for final range/EOF validation. This also lets the
                        // completed block enter the normal memory/disk caches exactly once.
                        if (slice.offset + slice.bytes.size < expectedSize) {
                            prefetch.prefixDelivered = true
                            return YLoadedTransportBlock(
                                bytes = slice.bytes,
                                contentLength = slice.contentLength,
                                offsetInBlock = slice.offset,
                            )
                        }
                    }
                }
                try {
                    loaded = prefetch.future.get(50L, TimeUnit.MILLISECONDS)
                } catch (timeout: TimeoutException) {
                    val nowNs = System.nanoTime()
                    if (budget.remainingMs() == 0L ||
                        !shouldKeepTransportPrefetch(
                            waitedMs = (nowNs - waitStartedNs) / TRANSPORT_NANOS_PER_MILLISECOND,
                            idleMs = (nowNs - prefetch.lastProgressNs.get()) / TRANSPORT_NANOS_PER_MILLISECOND,
                            completedBytes = prefetch.completedBytes.get(),
                            blockBytes = blockSize.toLong(),
                            bufferedMs = (playbackWindow.bufferedUs / playbackWindow.speed / 1_000L).toLong(),
                        )
                    ) {
                        throw timeout
                    }
                }
            }
            prefetchedBlocks.remove(blockIndex)
            loaded
        } catch (failure: TimeoutException) {
            prefetchedBlocks.remove(blockIndex)
            // A stalled range is promoted only after checking live byte progress and headroom.
            promotedPrefetchCount++
            prefetch.cancel("prefetch_stalled")
            if (prefetch.prefixDelivered) {
                throw IOException(
                    "Media range stalled after its prefix was consumed",
                    failure,
                )
            }
            null
        } catch (failure: CancellationException) {
            prefetchedBlocks.remove(blockIndex)
            if (prefetch.prefixDelivered) throw failure
            null
        } catch (failure: ExecutionException) {
            prefetchedBlocks.remove(blockIndex)
            // This is now a foreground read: its earlier bytes have reached the decoder.
            // Preserve the failure instead of replacing its tail with a fresh exchange.
            if (prefetch.prefixDelivered) throw (failure.cause ?: failure)
            null
        } catch (_: InterruptedException) {
            prefetchedBlocks.remove(blockIndex)
            prefetch.cancel("foreground_interrupted")
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun shedSpeculativeWorkFor(blockIndex: Long) {
        if (playbackWindow.bufferedUs / playbackWindow.speed >= 2_000_000L) return
        forwardCache?.updateWindow(0L, 0L)
        val otherRuns = readHeads.otherHeads(blockIndex, System.nanoTime())
        val retained =
            prefetchedBlocks
                .filter { (index, pending) ->
                    pending.future.isDone ||
                        index in blockIndex..blockIndex.saturatedAdd(2L) ||
                        otherRuns.any { head -> index in head..head.saturatedAdd(2L) }
                }.keys
                .toMutableSet()
        retainMovingPrefetches(retained)
        cancelPrefetchOutside(retained)
    }

    private fun retainMovingPrefetches(retained: MutableSet<Long>) {
        val nowNs = System.nanoTime()
        prefetchedBlocks.entries
            .filter { (index, pending) ->
                index !in retained &&
                    !pending.future.isDone &&
                    pending.started &&
                    keepActiveTransportPrefetch(
                        (nowNs - pending.lastProgressNs.get()) / TRANSPORT_NANOS_PER_MILLISECOND,
                    )
            }.take(2)
            .forEach { retained.add(it.key) }
    }

    private fun cancelPrefetchOutside(
        retained: Set<Long>,
        reason: String = "window_changed",
    ) {
        val iterator = prefetchedBlocks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in retained) {
                // A window change retires speculative ownership, not validated bytes. Dropping
                // completed futures here made alternating extractor runs download the same
                // ranges again, even when a persistent cache was configured.
                if (!closed && reason == "window_changed") {
                    completedPrefetchBlock(entry.key)?.takeIf { isCompleteBlock(entry.key, it) }?.let { loaded ->
                        cache(entry.key, loaded.bytes)
                        if (loaded.bytes.isNotEmpty() && !loaded.fromDiskCache) {
                            diskCache?.enqueueWriteBlock(entry.key, loaded.bytes, knownSize.takeIf { it >= 0L })
                        }
                    }
                }
                entry.value.cancel(reason)
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
                blocks[blockIndex]?.size ?: completedPrefetchBlock(blockIndex)?.bytes?.size
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

    private fun completedPrefetchBlock(blockIndex: Long): YLoadedTransportBlock? {
        val prefetch = prefetchedBlocks[blockIndex] ?: return null
        if (!prefetch.future.isDone || prefetch.future.isCancelled) return null
        return try {
            prefetch.future.get()
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
        ensureRepresentationCurrent()
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
        if (!startupTailPrefetchScheduled &&
            knownSize > blockSize &&
            (!prioritizeStartupReads || playbackWindow.playing)
        ) {
            startupTailPrefetchScheduled = true
            val tailBlock = (knownSize - 1L) / blockSize
            if (tailBlock > 1L) {
                startupTailPrefetchBlockIndex = tailBlock
                schedulePrefetchBlock(tailBlock)
            }
        }
        return knownSize
    }

    /** Optional warmers await their bounded writes; normal playback never blocks on this. */
    fun awaitCacheWrites(timeoutMs: Long): Boolean = diskCache?.awaitPendingWrites(timeoutMs) ?: true

    /**
     * Not `@Synchronized` on purpose. [readAt] holds this instance's monitor for the whole of a
     * blocking range fetch, so a synchronized close would wait for a stalled origin before it
     * could even signal shutdown - and closing the transport is exactly what unblocks that read.
     * Shutdown is therefore signalled and the sockets are closed first; the monitor is only taken
     * afterwards, to drop the cached blocks once the reader has unwound.
     */

    override fun close() {
        if (!resourcesClosed.compareAndSet(false, true)) return
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
            cancelPrefetchOutside(retained, "memory_pressure")
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
