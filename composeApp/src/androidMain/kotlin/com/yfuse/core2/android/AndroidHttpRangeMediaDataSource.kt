package com.yfuse.core2.android

import android.media.MediaDataSource
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

/** Random-access HTTP source with a small bounded compressed-byte cache for MediaExtractor. */
internal class AndroidHttpRangeMediaDataSource(
    private val uri: String,
    private val headers: Map<String, String>,
    private val blockSize: Int = DEFAULT_BLOCK_BYTES,
    private val maximumCacheBytes: Int = DEFAULT_CACHE_BYTES,
    private val openConnection: (String) -> HttpURLConnection = { value ->
        URL(value).openConnection() as HttpURLConnection
    },
) : MediaDataSource() {
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)
    private var cachedBytes = 0
    private var knownSize = UNKNOWN_SIZE
    private var closed = false

    init {
        require(uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true))
        require(blockSize in MIN_BLOCK_BYTES..MAX_BLOCK_BYTES)
        require(maximumCacheBytes >= blockSize)
        require(headers.all { (name, value) -> name.isSafeHttpHeader() && value.isSafeHttpHeader() })
    }

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        check(!closed) { "HTTP media source is closed" }
        require(position >= 0L)
        require(offset >= 0 && size >= 0 && offset <= buffer.size - size)
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

    @Synchronized
    override fun getSize(): Long {
        check(!closed) { "HTTP media source is closed" }
        if (knownSize < 0L) {
            blocks[0L] ?: loadBlock(0L).also { cache(0L, it) }
        }
        return knownSize
    }

    @Synchronized
    override fun close() {
        closed = true
        blocks.clear()
        cachedBytes = 0
    }

    private fun loadBlock(blockIndex: Long): ByteArray {
        val start = blockIndex.saturatedMultiply(blockSize.toLong())
        val end = start.saturatedAdd(blockSize.toLong() - 1L)
        var lastFailure: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return requestBlock(start, end)
            } catch (failure: YPlaybackException) {
                if (failure.category != YPlaybackFailureCategory.Network || attempt == MAX_ATTEMPTS - 1) {
                    throw failure
                }
                lastFailure = failure
            } catch (failure: IOException) {
                lastFailure = failure
                if (attempt == MAX_ATTEMPTS - 1) break
            }
        }
        throw YPlaybackException(
            category = YPlaybackFailureCategory.Network,
            stage = YPlaybackFailureStage.Demux,
            safeDetail = "HTTP range read exhausted bounded retries",
            cause = lastFailure,
        )
    }

    private fun requestBlock(
        start: Long,
        end: Long,
    ): ByteArray {
        val connection = openConnection(uri)
        return try {
            // Never forward provider authorization headers to a redirect target.
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.connect()
            when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> readPartialResponse(connection, start, end)
                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> throw classifiedHttpFailure(
                    YPlaybackFailureCategory.Authorization,
                    "HTTP range authorization failed",
                )
                HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                HTTP_TOO_MANY_REQUESTS,
                in 500..599,
                -> throw classifiedHttpFailure(YPlaybackFailureCategory.Network, "HTTP range request is retryable")
                HttpURLConnection.HTTP_OK ->
                    throw classifiedHttpFailure(
                        YPlaybackFailureCategory.Container,
                        "HTTP source ignored byte ranges",
                    )
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    connection
                        .getHeaderField("Content-Range")
                        ?.substringAfter("*/", missingDelimiterValue = "")
                        ?.toLongOrNull()
                        ?.takeIf { it >= 0L }
                        ?.let { knownSize = it }
                    byteArrayOf()
                }
                in 300..399 ->
                    throw classifiedHttpFailure(
                        YPlaybackFailureCategory.Network,
                        "HTTP range redirect requires provider re-resolution",
                    )
                else -> throw classifiedHttpFailure(
                    YPlaybackFailureCategory.Network,
                    "HTTP range request failed ($status)",
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readPartialResponse(
        connection: HttpURLConnection,
        requestedStart: Long,
        requestedEnd: Long,
    ): ByteArray {
        val range =
            parseContentRange(connection.getHeaderField("Content-Range"))
                ?: throw classifiedHttpFailure(YPlaybackFailureCategory.Container, "HTTP range metadata is missing")
        require(range.start == requestedStart && range.end in requestedStart..requestedEnd) {
            "HTTP range response does not match the requested block"
        }
        range.total?.let { total ->
            require(total > range.end) { "HTTP range total is invalid" }
            if (knownSize >= 0L) require(knownSize == total) { "HTTP media size changed during playback" }
            knownSize = total
        }
        val expected = (range.end - range.start + 1L).toInt()
        return connection.inputStream.use { input -> input.readExactlyBounded(expected) }
    }

    private fun cache(
        index: Long,
        block: ByteArray,
    ) {
        blocks.put(index, block)?.let { previous -> cachedBytes -= previous.size }
        cachedBytes += block.size
        val iterator = blocks.entries.iterator()
        while (cachedBytes > maximumCacheBytes && iterator.hasNext()) {
            val entry = iterator.next()
            cachedBytes -= entry.value.size
            iterator.remove()
        }
    }
}

internal data class YHttpContentRange(
    val start: Long,
    val end: Long,
    val total: Long?,
)

internal fun parseContentRange(value: String?): YHttpContentRange? {
    val match = CONTENT_RANGE.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (start < 0L || end < start || total != null && total <= end) return null
    return YHttpContentRange(start, end, total)
}

private fun classifiedHttpFailure(
    category: YPlaybackFailureCategory,
    detail: String,
): YPlaybackException =
    YPlaybackException(
        category = category,
        stage = YPlaybackFailureStage.Demux,
        safeDetail = detail,
    )

private fun InputStream.readExactlyBounded(expected: Int): ByteArray {
    val output = ByteArrayOutputStream(expected)
    val buffer = ByteArray(minOf(READ_BUFFER_BYTES, expected.coerceAtLeast(1)))
    var total = 0
    while (total < expected) {
        val count = read(buffer, 0, minOf(buffer.size, expected - total))
        if (count < 0) break
        if (count == 0) continue
        output.write(buffer, 0, count)
        total += count
    }
    require(total == expected && read() < 0) { "HTTP range body length is invalid" }
    return output.toByteArray()
}

private fun String.isSafeHttpHeader(): Boolean = isNotBlank() && '\r' !in this && '\n' !in this

private fun Long.saturatedMultiply(other: Long): Long =
    if (this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private fun Long.saturatedAdd(other: Long): Long = if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
private const val UNKNOWN_SIZE = -1L
private const val MIN_BLOCK_BYTES = 64 * 1024
private const val MAX_BLOCK_BYTES = 2 * 1024 * 1024
private const val DEFAULT_BLOCK_BYTES = 512 * 1024
private const val DEFAULT_CACHE_BYTES = 32 * 1024 * 1024
private const val READ_BUFFER_BYTES = 16 * 1024
private const val MAX_ATTEMPTS = 3
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
