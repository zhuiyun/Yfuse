package com.yfuse.feature.player

import android.os.Looper
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** libudfread/libbluray read ISO images in 2048-byte logical blocks. */
internal const val BLURAY_UDF_BLOCK_SIZE = 2_048

/** Headers are resolved for every request so an expiring server token can be refreshed externally. */
internal fun interface RemoteDiscHeaderProvider {
    fun headers(): Map<String, String>
}

/**
 * Blocking random-access reader designed for libbluray's `bd_open_stream(read_blocks)` callback.
 *
 * It never logs or exposes [sourceUrl], requires HTTP byte-range semantics, forces identity transfer
 * encoding, uses 64-bit byte offsets, and refuses redirects so authorization headers cannot leak to a
 * different origin. The JNI bridge keeps one higher-level source behind its opaque native handle and
 * calls [readBlocks] from a native worker thread.
 */
internal class HttpRangeDiscBlockSource(
    private val sourceUrl: String,
    private val headerProvider: RemoteDiscHeaderProvider = RemoteDiscHeaderProvider { emptyMap() },
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000,
) : AutoCloseable {
    private val knownLength = AtomicLong(UNKNOWN_LENGTH)

    @Volatile
    private var closed = false

    @Volatile
    var lastFailure: String? = null
        private set

    val contentLengthBytes: Long?
        get() = knownLength.get().takeIf { it >= 0L }

    /**
     * Reads [blockCount] UDF blocks beginning at [lba] into [target].
     *
     * Authentication failure gets exactly one fresh request. [headerProvider] is invoked again for
     * that request, so a token source that renewed credentials after a 401/403 can recover without
     * rebuilding the native disc session. A second 401/403 is terminal and never loops.
     *
     * @return complete blocks read, `0` at EOF, or `-1` on transport/protocol failure.
     */
    fun readBlocks(
        lba: Int,
        blockCount: Int,
        target: ByteArray,
        targetOffset: Int = 0,
    ): Int {
        if (closed) return fail("远程原盘读取器已关闭")
        if (runningOnAndroidMainThread()) return fail("远程原盘随机读取不能运行在主线程")
        if (lba < 0 || blockCount <= 0 || targetOffset < 0) {
            return fail("远程原盘块读取参数无效")
        }

        val requestedBytes = safeByteCount(blockCount) ?: return fail("远程原盘块读取长度溢出")
        if (targetOffset > target.size || requestedBytes > target.size.toLong() - targetOffset) {
            return fail("远程原盘读取缓冲区不足")
        }
        val start = safeByteOffset(lba) ?: return fail("远程原盘块偏移溢出")
        val end = safeInclusiveEnd(start, requestedBytes) ?: return fail("远程原盘 Range 溢出")

        var authRetry = 0
        while (authRetry <= MAX_AUTH_RETRIES) {
            val connection =
                runCatching { openConnection(start, end) }
                    .getOrElse { return fail("无法建立远程原盘 Range 请求") }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL ->
                        return readPartialResponse(
                            connection = connection,
                            requestedStart = start,
                            requestedEnd = end,
                            target = target,
                            targetOffset = targetOffset,
                        )

                    HTTP_RANGE_NOT_SATISFIABLE -> {
                        parseUnsatisfiedLength(connection.getHeaderField("Content-Range"))
                            ?.let(knownLength::set)
                        lastFailure = null
                        return 0
                    }

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> {
                        if (authRetry < MAX_AUTH_RETRIES) {
                            authRetry++
                            continue
                        }
                        return fail("远程原盘鉴权失败（HTTP $code）")
                    }

                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    HTTP_TEMPORARY_REDIRECT,
                    HTTP_PERMANENT_REDIRECT,
                    -> return fail("远程原盘 Range 请求发生重定向，已拒绝转发鉴权信息")

                    else -> return fail("远程原盘服务器不支持安全随机读取（HTTP $code）")
                }
            } catch (_: Exception) {
                return fail("远程原盘 Range 读取失败")
            } finally {
                connection.disconnect()
            }
        }
        return fail("远程原盘鉴权重试耗尽")
    }

    /** Small capability probe; successful completion proves the origin honors byte ranges. */
    fun probeRangeSupport(): Boolean {
        if (closed) {
            fail("远程原盘读取器已关闭")
            return false
        }
        if (runningOnAndroidMainThread()) {
            fail("远程原盘 Range 能力探测不能运行在主线程")
            return false
        }

        var authRetry = 0
        while (authRetry <= MAX_AUTH_RETRIES) {
            val connection =
                runCatching { openConnection(0L, 0L) }
                    .getOrElse {
                        fail("无法探测远程原盘 Range 能力")
                        return false
                    }
            try {
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> {
                        if (authRetry < MAX_AUTH_RETRIES) {
                            authRetry++
                            continue
                        }
                        fail("远程原盘鉴权失败（HTTP $code）")
                        return false
                    }

                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range = parseContentRange(connection.getHeaderField("Content-Range"))
                        if (range == null || range.start != 0L || range.end != 0L) {
                            fail("远程原盘服务器返回了无效 Content-Range")
                            return false
                        }
                        if (!identityEncoding(connection)) {
                            fail("远程原盘服务器对 Range 响应进行了内容编码")
                            return false
                        }
                        knownLength.set(range.total)
                        val probe = ByteArray(1)
                        BufferedInputStream(connection.inputStream).use { input ->
                            if (input.read(probe) != 1) {
                                fail("远程原盘 Range 探测没有返回请求字节")
                                return false
                            }
                        }
                        lastFailure = null
                        return true
                    }

                    else -> {
                        fail("远程原盘服务器没有返回 HTTP 206（HTTP $code）")
                        return false
                    }
                }
            } catch (_: Exception) {
                fail("远程原盘 Range 能力探测失败")
                return false
            } finally {
                connection.disconnect()
            }
        }
        fail("远程原盘鉴权重试耗尽")
        return false
    }

    override fun close() {
        closed = true
    }

    private fun openConnection(
        start: Long,
        end: Long,
    ): HttpURLConnection {
        val connection = URL(sourceUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.setRequestProperty("Range", "bytes=$start-$end")
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-transform")
        headerProvider.headers().forEach { (name, value) ->
            if (name.isAllowedCallerHeader() && value.isNotBlank()) {
                connection.setRequestProperty(name, value)
            }
        }
        return connection
    }

    private fun readPartialResponse(
        connection: HttpURLConnection,
        requestedStart: Long,
        requestedEnd: Long,
        target: ByteArray,
        targetOffset: Int,
    ): Int {
        if (!identityEncoding(connection)) {
            return fail("远程原盘 Range 响应不能使用压缩内容编码")
        }
        val contentRange =
            parseContentRange(connection.getHeaderField("Content-Range"))
                ?: return fail("远程原盘 HTTP 206 缺少有效 Content-Range")
        if (contentRange.start != requestedStart || contentRange.end > requestedEnd) {
            return fail("远程原盘 Content-Range 与请求不一致")
        }
        knownLength.set(contentRange.total)

        val responseBytes = contentRange.end - contentRange.start + 1L
        if (responseBytes <= 0L || responseBytes > Int.MAX_VALUE) {
            return fail("远程原盘 Range 响应长度无效")
        }
        val expected = responseBytes.toInt()
        var got = 0
        BufferedInputStream(connection.inputStream).use { input ->
            while (got < expected) {
                val count = input.read(target, targetOffset + got, expected - got)
                if (count < 0) break
                if (count == 0) continue
                got += count
            }
        }
        if (got != expected) return fail("远程原盘 Range 响应提前结束")

        lastFailure = null
        return got / BLURAY_UDF_BLOCK_SIZE
    }

    private fun identityEncoding(connection: HttpURLConnection): Boolean {
        val encoding = connection.getHeaderField("Content-Encoding")?.trim().orEmpty()
        return encoding.isEmpty() || encoding.equals("identity", ignoreCase = true)
    }

    private fun fail(message: String): Int {
        lastFailure = message
        return -1
    }
}

internal data class HttpContentRange(
    val start: Long,
    val end: Long,
    val total: Long,
)

internal fun parseContentRange(value: String?): HttpContentRange? {
    val match = CONTENT_RANGE_REGEX.matchEntire(value?.trim().orEmpty()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0L || end < start || total <= end) return null
    return HttpContentRange(start = start, end = end, total = total)
}

internal fun remoteDiscRangeHeader(
    lba: Int,
    blockCount: Int,
): String? {
    if (lba < 0 || blockCount <= 0) return null
    val start = safeByteOffset(lba) ?: return null
    val length = safeByteCount(blockCount) ?: return null
    val end = safeInclusiveEnd(start, length) ?: return null
    return "bytes=$start-$end"
}

private fun parseUnsatisfiedLength(value: String?): Long? =
    UNSATISFIED_CONTENT_RANGE_REGEX
        .matchEntire(value?.trim().orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it >= 0L }

private fun safeByteOffset(lba: Int): Long? =
    lba.takeIf { it >= 0 }?.toLong()?.times(BLURAY_UDF_BLOCK_SIZE.toLong())

private fun safeByteCount(blockCount: Int): Long? =
    blockCount.takeIf { it > 0 }?.toLong()?.times(BLURAY_UDF_BLOCK_SIZE.toLong())

private fun safeInclusiveEnd(
    start: Long,
    length: Long,
): Long? {
    if (start < 0L || length <= 0L || start > Long.MAX_VALUE - (length - 1L)) return null
    return start + length - 1L
}

/** Local JVM unit tests use Android stubs where Looper methods throw; production Android does not. */
private fun runningOnAndroidMainThread(): Boolean =
    runCatching {
        val main = Looper.getMainLooper() ?: return@runCatching false
        Looper.myLooper() === main
    }.getOrDefault(false)

private fun String.isAllowedCallerHeader(): Boolean =
    !equals("Range", ignoreCase = true) &&
        !equals("Accept-Encoding", ignoreCase = true) &&
        !equals("Content-Length", ignoreCase = true) &&
        !equals("Host", ignoreCase = true) &&
        !equals("Connection", ignoreCase = true)

private const val UNKNOWN_LENGTH = -1L
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val HTTP_TEMPORARY_REDIRECT = 307
private const val HTTP_PERMANENT_REDIRECT = 308
private const val MAX_AUTH_RETRIES = 1
private val CONTENT_RANGE_REGEX =
    Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
private val UNSATISFIED_CONTENT_RANGE_REGEX =
    Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE)
