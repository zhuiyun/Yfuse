package com.yfuse.core2.android

import android.media.MediaDataSource
import android.os.Looper
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheConditions
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YCachePlanner
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFailureKind
import com.yfuse.core2.network.mediaRangeRetryDelayMs
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.util.LinkedHashMap

/** Adapts protocol transports to MediaExtractor without ever materializing the full remote file. */
internal class AndroidTransportMediaDataSource(
    private val uri: String,
    private val protocol: YSourceProtocol,
    private val headers: Map<String, String>,
    private val createTransport: () -> YMediaTransport,
    cacheDirectory: File? = null,
    cacheIdentity: YCacheIdentity? = null,
    cacheMaximumBytes: Long = 0L,
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
    private val blockSize = cachePlan.readAheadBytes.toInt().coerceAtLeast(MIN_TRANSPORT_BLOCK_BYTES)
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
    private var cachedBytes = 0L
    private var knownSize = diskCache?.contentLength ?: -1L
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
            val block = blocks[blockIndex] ?: loadBlock(blockIndex).also { cache(blockIndex, it) }
            val blockOffset = (readPosition % blockSize).toInt()
            if (blockOffset >= block.size) break
            val count = minOf(remaining, block.size - blockOffset)
            block.copyInto(buffer, outputOffset, blockOffset, blockOffset + count)
            readPosition += count
            outputOffset += count
            remaining -= count
        }
        val copied = size - remaining
        return if (copied == 0) -1 else copied
    }

    private fun loadBlock(blockIndex: Long): ByteArray {
        diskCache?.readBlock(blockIndex, blockSize)?.let { return it }
        var completedRetries = 0
        while (true) {
            try {
                return loadRemoteBlock(blockIndex)
            } catch (failure: Exception) {
                val failureKind =
                    when (failure) {
                        is YRangeReadException -> failure.failureKind
                        is IOException -> YTransportFailureKind.TransientIo
                        else -> throw failure
                    }
                val delayMs = mediaRangeRetryDelayMs(completedRetries, failureKind) ?: throw failure
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

    private fun loadRemoteBlock(blockIndex: Long): ByteArray =
        runBlocking {
            val position = blockIndex.saturatedMultiply(blockSize.toLong())
            val end = position.saturatedAdd(blockSize.toLong() - 1L)
            try {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = uri,
                            protocol = protocol,
                            range = YByteRange(position, end),
                            headers = headers,
                        ),
                    )
                if (response.statusCode != 206) {
                    throw YRangeReadException(
                        failureKind = response.statusCode.toRangeFailureKind(),
                        safeMessage = "Random-access transport did not accept byte range",
                    )
                }
                if (response.acceptedRange?.startInclusive != position) {
                    throw YRangeReadException(
                        failureKind = YTransportFailureKind.InvalidRange,
                        safeMessage = "Random-access transport returned mismatched range metadata",
                    )
                }
                response.contentLength?.takeIf { it >= 0L }?.let { total ->
                    if (knownSize >= 0L) require(knownSize == total) { "Remote media size changed during playback" }
                    knownSize = total
                }
                val output = ByteArray(blockSize)
                var total = 0
                while (total < output.size) {
                    val count = transport.read(output, total, output.size - total)
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
                    (knownSize < 0L || position + total != knownSize)
                ) {
                    throw YRangeReadException(
                        failureKind = YTransportFailureKind.PrematureEof,
                        safeMessage = "Random-access transport ended before the accepted block range",
                    )
                }
                output.copyOf(total).also { block ->
                    if (block.isNotEmpty()) diskCache?.writeBlock(blockIndex, block, knownSize.takeIf { it >= 0L })
                }
            } finally {
                transport.close()
            }
        }

    @Synchronized
    override fun getSize(): Long {
        checkWorkerThread()
        check(!closed)
        if (knownSize >= 0L) return knownSize
        val probe = ByteArray(1)
        readAt(0L, probe, 0, 1)
        return knownSize
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        blocks.clear()
        cachedBytes = 0L
        runBlocking { transport.close() }
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

private class YRangeReadException(
    val failureKind: YTransportFailureKind,
    safeMessage: String,
) : IOException(safeMessage)

private fun Int.toRangeFailureKind(): YTransportFailureKind =
    when (this) {
        401, 403 -> YTransportFailureKind.Authorization
        408, 425, 429 -> YTransportFailureKind.ServerBusy
        in 500..599 -> YTransportFailureKind.ServerBusy
        else -> YTransportFailureKind.InvalidRange
    }

private fun checkWorkerThread() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "Remote media I/O is forbidden on the main thread" }
}

private fun Long.saturatedAdd(other: Long): Long = if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatedMultiply(other: Long): Long = if (other > 0L && this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private const val MIN_TRANSPORT_BLOCK_BYTES = 256 * 1024
private const val DEFAULT_TRANSPORT_CACHE_BYTES = 64L * 1024L * 1024L
