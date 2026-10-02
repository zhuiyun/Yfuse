package com.yfuse.core.offline

import java.io.IOException
import java.net.HttpURLConnection

/** A single byte range. A response length is not necessarily the complete representation length. */
internal data class OfflineContentRange(
    val start: Long,
    val endInclusive: Long,
    val totalBytes: Long?,
) {
    val responseBytes: Long get() = endInclusive - start + 1L
}

private val offlineContentRangePattern = Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

internal fun parseOfflineContentRange(value: String?): OfflineContentRange? {
    val match = value?.trim()?.let(offlineContentRangePattern::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start || end == Long.MAX_VALUE) return null
    val total =
        if (match.groupValues[3] == "*") {
            null
        } else {
            match.groupValues[3].toLongOrNull()?.takeIf { it > end } ?: return null
        }
    return OfflineContentRange(start, end, total)
}

internal fun canAppendOfflineRange(
    existingBytes: Long,
    statusCode: Int,
    contentRange: String?,
    expectedValidator: String?,
    responseValidator: String?,
    contentLength: Long = -1L,
): Boolean {
    val range = parseOfflineContentRange(contentRange) ?: return false
    return existingBytes > 0L &&
        statusCode == HttpURLConnection.HTTP_PARTIAL &&
        range.start == existingBytes &&
        range.totalBytes != null &&
        (contentLength < 0L || contentLength == range.responseBytes) &&
        !expectedValidator.isNullOrBlank() &&
        expectedValidator == responseValidator
}

/** Unknown totals in a 206 cannot establish completeness; the caller retries a full GET instead. */
internal fun offlineTransferTotalBytes(
    append: Boolean,
    contentRange: String?,
    contentLength: Long,
): Long =
    if (append) {
        parseOfflineContentRange(contentRange)?.totalBytes
            ?: throw IOException("服务器未返回有效的完整文件大小")
    } else {
        contentLength.coerceAtLeast(0L)
    }

internal fun requireCompleteOfflineTransfer(
    downloadedBytes: Long,
    totalBytes: Long,
) {
    if (downloadedBytes <= 0L || totalBytes > 0L && downloadedBytes != totalBytes) {
        throw IOException("下载连接提前结束，内容不完整")
    }
}
