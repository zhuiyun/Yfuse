package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.LinkedHashMap

internal interface YCoreDiscBlockSource : AutoCloseable {
    fun readBlocks(
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Int
}

/** Protocol-neutral 2,048-byte reader used by libudfread for remote Blu-ray ISO images. */
internal class AndroidTransportDiscBlockSource(
    private val uri: String,
    private val protocol: YSourceProtocol,
    private val headers: Map<String, String>,
    private val credentials: YTransportCredentials?,
    private val createTransport: () -> YMediaTransport,
    private val readAheadBytes: Int = DEFAULT_DISC_READ_AHEAD_BYTES,
    private val maximumCacheBytes: Int = DEFAULT_DISC_CACHE_BYTES,
) : YCoreDiscBlockSource {
    @Volatile private var closed = false
    private val cachedWindows = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private var cachedBytes = 0
    private var knownLength = -1L

    init {
        require(readAheadBytes >= DISC_LOGICAL_BLOCK_BYTES && readAheadBytes % DISC_LOGICAL_BLOCK_BYTES == 0)
        require(maximumCacheBytes >= readAheadBytes)
    }

    fun probeRangeSupport(): Boolean {
        val probe = ByteArray(DISC_LOGICAL_BLOCK_BYTES)
        return readBlocks(0, 1, probe, 0) == 1
    }

    @Synchronized
    override fun readBlocks(
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int,
    ): Int {
        if (closed || lba < 0 || blockCount <= 0 || targetOffset < 0) return -1
        val byteCount = blockCount.toLong() * DISC_LOGICAL_BLOCK_BYTES
        val start = lba.toLong() * DISC_LOGICAL_BLOCK_BYTES
        if (
            byteCount <= 0L ||
            byteCount > Int.MAX_VALUE ||
            start < 0L ||
            start > Long.MAX_VALUE - byteCount ||
            targetOffset > target.size ||
            byteCount > target.size.toLong() - targetOffset
        ) {
            return -1
        }
        var copied = 0
        while (copied < byteCount.toInt()) {
            val position = start + copied
            val windowIndex = position / readAheadBytes
            val windowStart = windowIndex * readAheadBytes
            val window =
                cachedWindows[windowIndex]
                    ?: when (val loaded = loadWindow(windowStart)) {
                        DiscWindowResult.EndOfFile -> break
                        DiscWindowResult.Failure -> return if (copied == 0) -1 else copied / DISC_LOGICAL_BLOCK_BYTES
                        is DiscWindowResult.Loaded ->
                            loaded.bytes.also { cacheWindow(windowIndex, it) }
                    }
            val windowOffset = (position - windowStart).toInt()
            if (windowOffset >= window.size) break
            val count = minOf(byteCount.toInt() - copied, window.size - windowOffset)
            window.copyInto(target, targetOffset + copied, windowOffset, windowOffset + count)
            copied += count
        }
        return copied / DISC_LOGICAL_BLOCK_BYTES
    }

    private fun loadWindow(start: Long): DiscWindowResult {
        if (knownLength >= 0L && start >= knownLength) return DiscWindowResult.EndOfFile
        val unboundedEnd = start + readAheadBytes - 1L
        val end = if (knownLength >= 0L) minOf(unboundedEnd, knownLength - 1L) else unboundedEnd
        val transport = createTransport()
        return runBlocking {
            try {
                val response =
                    transport.open(
                        yCoreRandomAccessRequest(
                            uri = uri,
                            protocol = protocol,
                            startInclusive = start,
                            endInclusive = end,
                            headers = headers,
                            credentials = credentials,
                        ),
                    )
                response.contentLength?.takeIf { it >= 0L }?.let { knownLength = it }
                if (response.statusCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    return@runBlocking if (knownLength >= 0L && start >= knownLength) {
                        DiscWindowResult.EndOfFile
                    } else {
                        DiscWindowResult.Failure
                    }
                }
                if (response.statusCode != 206) return@runBlocking DiscWindowResult.Failure
                val accepted =
                    response.acceptedRange
                        ?: return@runBlocking if (knownLength >= 0L && start >= knownLength) {
                            DiscWindowResult.EndOfFile
                        } else {
                            DiscWindowResult.Failure
                        }
                if (
                    accepted.startInclusive != start ||
                    accepted.endInclusive == null ||
                    accepted.endInclusive > end
                ) {
                    return@runBlocking DiscWindowResult.Failure
                }
                val expected = (accepted.endInclusive - start + 1L).toInt()
                val bytes = ByteArray(expected)
                var total = 0
                var emptyReads = 0
                while (total < expected) {
                    val count = transport.read(bytes, total, expected - total)
                    if (count < 0) break
                    if (count == 0) {
                        if (++emptyReads >= MAX_EMPTY_TRANSPORT_READS) {
                            return@runBlocking DiscWindowResult.Failure
                        }
                        continue
                    }
                    emptyReads = 0
                    total += count
                }
                if (total != expected) DiscWindowResult.Failure else DiscWindowResult.Loaded(bytes)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                DiscWindowResult.Failure
            } finally {
                runCatching { transport.close() }
            }
        }
    }

    private fun cacheWindow(
        index: Long,
        bytes: ByteArray,
    ) {
        cachedWindows.put(index, bytes)?.let { cachedBytes -= it.size }
        cachedBytes += bytes.size
        val iterator = cachedWindows.entries.iterator()
        while (cachedBytes > maximumCacheBytes && iterator.hasNext()) {
            val entry = iterator.next()
            cachedBytes -= entry.value.size
            iterator.remove()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
        cachedWindows.clear()
        cachedBytes = 0
        knownLength = -1L
    }
}

private sealed interface DiscWindowResult {
    data class Loaded(val bytes: ByteArray) : DiscWindowResult

    data object EndOfFile : DiscWindowResult

    data object Failure : DiscWindowResult
}

internal const val DISC_LOGICAL_BLOCK_BYTES = 2_048
private const val MAX_EMPTY_TRANSPORT_READS = 3
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val DEFAULT_DISC_READ_AHEAD_BYTES = 256 * 1024
private const val DEFAULT_DISC_CACHE_BYTES = 2 * 1024 * 1024
