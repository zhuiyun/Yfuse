from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    if old not in text:
        raise SystemExit(f"expected snippet not found in {path}: {old[:120]!r}")
    target.write_text(text.replace(old, new, 1))


transport = "composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidTransportMediaDataSource.kt"
player = "composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidNativeDirectYPlayer.kt"

replace_once(
    transport,
    """    private var maximumResolveWaitMs = 0L\n    private var maximumRemoteLoadMs = 0L\n\n    private val activePrefetchTransports = mutableSetOf<YMediaTransport>()\n""",
    """    private var maximumResolveWaitMs = 0L\n    private var maximumRemoteLoadMs = 0L\n    private var latestReadPosition = 0L\n\n    private val activePrefetchTransports = mutableSetOf<YMediaTransport>()\n""",
)

replace_once(
    transport,
    """        val copied = size - remaining\n        return if (copied == 0) -1 else copied\n""",
    """        val copied = size - remaining\n        if (copied > 0) {\n            latestReadPosition = readPosition\n        }\n        return if (copied == 0) -1 else copied\n""",
)

replace_once(
    transport,
    """        prefetchDepthBlocks =\n            transportPrefetchDepthBlocks(\n                blockSize = blockSize,\n                mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,\n            )\n    }\n\n    @Synchronized\n    fun qoeSnapshot(): YTransportPrefetchQoeSnapshot =\n        YTransportPrefetchQoeSnapshot(\n            depthBlocks = prefetchDepthBlocks,\n            hitCount = prefetchHitCount,\n            synchronousLoadCount = synchronousLoadCount,\n            maximumResolveWaitMs = maximumResolveWaitMs,\n            maximumRemoteLoadMs = maximumRemoteLoadMs,\n        )\n""",
    """        prefetchDepthBlocks =\n            transportPrefetchDepthBlocks(\n                blockSize = blockSize,\n                mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,\n            )\n        if (!prefetchSuppressed) {\n            schedulePrefetch(latestReadPosition / blockSize + 1L)\n        }\n    }\n\n    @Synchronized\n    fun qoeSnapshot(): YTransportPrefetchQoeSnapshot {\n        val bufferedAheadBytes = bufferedAheadBytesSnapshot()\n        return YTransportPrefetchQoeSnapshot(\n            depthBlocks = prefetchDepthBlocks,\n            hitCount = prefetchHitCount,\n            synchronousLoadCount = synchronousLoadCount,\n            maximumResolveWaitMs = maximumResolveWaitMs,\n            maximumRemoteLoadMs = maximumRemoteLoadMs,\n            bufferedAheadBytes = bufferedAheadBytes,\n            contentLengthBytes = knownSize,\n            mediaBitRateBitsPerSecond = mediaBitRateBitsPerSecond,\n        )\n    }\n""",
)

replace_once(
    transport,
    """                } else {\n                    synchronousLoadCount++\n                    cancelPrefetchOutside(emptySet())\n                    loadBlockNow(blockIndex)\n                }\n""",
    """                } else {\n                    synchronousLoadCount++\n                    if (prefetchSuppressed) {\n                        cancelPrefetchOutside(emptySet())\n                    } else {\n                        // Keep the forward window filling while the foreground block is fetched.\n                        // A cache miss must not throw away already useful read-ahead work.\n                        schedulePrefetch(blockIndex + 1L)\n                    }\n                    loadBlockNow(blockIndex)\n                }\n""",
)

replace_once(
    transport,
    """    @Synchronized\n    override fun getSize(): Long {\n""",
    """    private fun bufferedAheadBytesSnapshot(): Long {\n        var cursor = latestReadPosition.coerceAtLeast(0L)\n        val start = cursor\n        var blockIndex = cursor / blockSize\n        var scannedBlocks = 0\n        val scanLimit = prefetchDepthBlocks + TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS\n        while (scannedBlocks < scanLimit) {\n            val block = blocks[blockIndex] ?: completedPrefetchBytes(blockIndex) ?: break\n            val blockStart = blockIndex.saturatedMultiply(blockSize.toLong())\n            val blockEnd = blockStart.saturatedAdd(block.size.toLong())\n            if (cursor < blockEnd) {\n                cursor = blockEnd\n            }\n            if (block.size < blockSize) break\n            blockIndex = blockIndex.saturatedAdd(1L)\n            scannedBlocks++\n        }\n        knownSize.takeIf { it >= 0L }?.let { size -> cursor = cursor.coerceAtMost(size) }\n        return (cursor - start).coerceAtLeast(0L)\n    }\n\n    private fun completedPrefetchBytes(blockIndex: Long): ByteArray? {\n        val prefetch = prefetchedBlocks[blockIndex] ?: return null\n        if (!prefetch.future.isDone || prefetch.future.isCancelled) return null\n        return try {\n            prefetch.future.get().bytes\n        } catch (_: CancellationException) {\n            null\n        } catch (_: ExecutionException) {\n            null\n        } catch (_: InterruptedException) {\n            Thread.currentThread().interrupt()\n            null\n        }\n    }\n\n    @Synchronized\n    override fun getSize(): Long {\n""",
)

replace_once(
    transport,
    """internal data class YTransportPrefetchQoeSnapshot(\n    val depthBlocks: Int,\n    val hitCount: Long,\n    val synchronousLoadCount: Long,\n    val maximumResolveWaitMs: Long,\n    val maximumRemoteLoadMs: Long,\n)\n""",
    """internal data class YTransportPrefetchQoeSnapshot(\n    val depthBlocks: Int,\n    val hitCount: Long,\n    val synchronousLoadCount: Long,\n    val maximumResolveWaitMs: Long,\n    val maximumRemoteLoadMs: Long,\n    val bufferedAheadBytes: Long = 0L,\n    val contentLengthBytes: Long = -1L,\n    val mediaBitRateBitsPerSecond: Long = 0L,\n)\n\ninternal fun YTransportPrefetchQoeSnapshot.bufferedAheadDurationMs(durationMs: Long): Long {\n    if (bufferedAheadBytes <= 0L) return 0L\n    if (mediaBitRateBitsPerSecond > 0L) {\n        return bufferedAheadBytes\n            .saturatedMultiply(BITS_PER_BYTE * MILLIS_PER_SECOND)\n            .div(mediaBitRateBitsPerSecond)\n            .coerceAtLeast(0L)\n    }\n    if (durationMs > 0L && contentLengthBytes > 0L) {\n        return ((bufferedAheadBytes.toDouble() * durationMs.toDouble()) / contentLengthBytes.toDouble())\n            .toLong()\n            .coerceAtLeast(0L)\n    }\n    return 0L\n}\n""",
)

replace_once(
    transport,
    """private const val TRANSPORT_PREFETCH_SAFETY_BLOCKS = 1L\nprivate const val BITS_PER_BYTE = 8L\n""",
    """private const val TRANSPORT_PREFETCH_SAFETY_BLOCKS = 1L\nprivate const val TRANSPORT_BUFFER_PROGRESS_EXTRA_BLOCKS = 2\nprivate const val BITS_PER_BYTE = 8L\n""",
)

replace_once(
    player,
    """                positionMs = bounded,\n                buffering = it.playbackRequested,\n""",
    """                positionMs = bounded,\n                bufferedPositionMs = bounded,\n                buffering = it.playbackRequested,\n""",
)

replace_once(
    player,
    """                buffering = it.playbackRequested,\n                positionMs = 0L,\n                currentIndex = index,\n""",
    """                buffering = it.playbackRequested,\n                positionMs = 0L,\n                bufferedPositionMs = 0L,\n                currentIndex = index,\n""",
)

replace_once(
    player,
    """            val positionUs = currentPositionUs()\n            mutableState.value =\n                mutableState.value.copy(\n                    positionMs = positionUs / MICROS_PER_MILLISECOND,\n""",
    """            val positionUs = currentPositionUs()\n            val positionMs = positionUs / MICROS_PER_MILLISECOND\n            val currentState = mutableState.value\n            val transportQoe = demux.transportQoeSnapshot()\n            val sourceBufferedMs =\n                transportQoe?.bufferedAheadDurationMs(currentState.durationMs) ?: 0L\n            val bufferedPositionMs =\n                when {\n                    !sourceRemote && currentState.durationMs > 0L -> currentState.durationMs\n                    transportQoe != null -> {\n                        val candidate = positionMs + sourceBufferedMs\n                        if (currentState.durationMs > 0L) {\n                            candidate.coerceAtMost(currentState.durationMs)\n                        } else {\n                            candidate\n                        }\n                    }\n                    else -> currentState.bufferedPositionMs.coerceAtLeast(positionMs)\n                }\n            mutableState.value =\n                currentState.copy(\n                    positionMs = positionMs,\n                    bufferedPositionMs = bufferedPositionMs,\n""",
)

replace_once(
    player,
    """                            droppedFrames = droppedFrames,\n                            droppedFramesMeasured = true,\n                            audioUnderrunCount =\n""",
    """                            droppedFrames = droppedFrames,\n                            droppedFramesMeasured = true,\n                            sourceQueueBytes = transportQoe?.bufferedAheadBytes ?: 0L,\n                            sourceBufferedMs = sourceBufferedMs,\n                            audioUnderrunCount =\n""",
)

print("progressive buffering + buffered progress patch applied")
