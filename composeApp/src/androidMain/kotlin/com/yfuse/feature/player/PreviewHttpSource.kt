package com.yfuse.feature.player

import android.media.MediaDataSource
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A disposable random-access source: no global cookie jar, disk files or playback decoder. */
internal class PreviewHttpSource(
    private val uri: String,
    private val headers: Map<String, String>,
    private val cancelled: AtomicBoolean,
    private val client: Call.Factory = previewHttpClient,
    budgetMs: Long = 4_000L,
) : MediaDataSource() {
    private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs.coerceIn(1L, 4_000L))
    private var remainingBytes = 8L * 1024L * 1024L
    private var length = -1L
    private var entityUrl: String? = null

    // Keep null and weak tags as observed identity evidence; only strong tags can be If-Range.
    private var entityTag: String? = null

    @Volatile private var activeCall: Call? = null
    private val blocks = LinkedHashMap<Long, ByteArray>(16, 0.75f, true)

    @Synchronized
    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        checkBudget()
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (length >= 0 && position >= length) return -1
        // A server may return fewer bytes than requested without reaching EOF. Keep actual
        // spans, so the next read fetches the missing suffix instead of reporting false EOF.
        val blockStart =
            blocks.entries
                .firstOrNull { (start, bytes) ->
                    position >= start && position - start < bytes.size
                }?.key ?: position
        val bytes =
            blocks[blockStart] ?: readBlock(blockStart).also {
                blocks[blockStart] = it
                while (blocks.size > 16) blocks.remove(blocks.keys.first())
            }
        val inside = (position - blockStart).toInt()
        if (inside >= bytes.size) return -1
        val count = minOf(size, bytes.size - inside)
        bytes.copyInto(buffer, offset, inside, inside + count)
        return count
    }

    @Synchronized
    override fun getSize(): Long {
        checkBudget()
        if (length < 0) readAt(0, ByteArray(1), 0, 1)
        return length
    }

    override fun close() {
        cancelled.set(true)
        activeCall?.cancel()
    }

    private fun checkBudget() {
        if (cancelled.get() ||
            Thread.currentThread().isInterrupted ||
            System.nanoTime() >= deadlineNanos
        ) {
            throw IOException("Preview request cancelled or budget exhausted")
        }
    }

    private fun readBlock(start: Long): ByteArray {
        var url = uri.toHttpUrl()
        val origin = url
        repeat(6) {
            checkBudget()
            if (remainingBytes <= 0) throw IOException("Preview byte budget exhausted")
            val request = Request.Builder().url(url)
            // Every caller header is origin-bound, not merely headers whose names resemble auth.
            if (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port) {
                headers.forEach { (key, value) -> request.header(key, value) }
            }
            val end = start.coerceAtMost(Long.MAX_VALUE - PREVIEW_BLOCK_BYTES + 1L) + PREVIEW_BLOCK_BYTES - 1L
            request
                .header(
                    "Accept-Encoding",
                    "identity",
                ).header("Range", "bytes=$start-$end")
            request.removeHeader("If-Range")
            entityTag
                ?.takeIf { entityUrl == url.toString() && isStrongPreviewEntityTag(it) }
                ?.let { request.header("If-Range", it) }
            val call = client.newCall(request.build())
            activeCall = call
            try {
                checkBudget() // close() may have raced with newCall(), before activeCall was visible.
                call.timeout().deadlineNanoTime(deadlineNanos)
                call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        url =
                            response.header("Location")?.let(url::resolve)
                                ?: throw IOException("Preview redirect has no target")
                        return@repeat
                    }
                    if (response.code == 416) {
                        val total = parsePreviewUnsatisfiedRange(response.header("Content-Range"))
                        if (total != null && start >= total) {
                            if (length >= 0 && length != total) throw IOException("Preview source changed")
                            length = total
                            return ByteArray(0)
                        }
                    }
                    if (response.code != 206) throw IOException("Preview requires HTTP byte ranges")
                    val range =
                        parsePreviewContentRange(response.header("Content-Range"), start)
                            ?: throw IOException("Preview received a different byte range")
                    if (length >= 0 && length != range.third) throw IOException("Preview source changed")
                    val finalUrl = response.request.url.toString()
                    val tag = response.header("ETag")
                    if (entityUrl != null && (entityUrl != finalUrl || entityTag != tag)) {
                        throw IOException("Preview source changed")
                    }
                    length = range.third
                    entityUrl = finalUrl
                    entityTag = tag
                    // Missing/weak validators remain usable for best-effort previews, but unchanged
                    // URL, length and validator absence cannot prove byte identity on a mutable server.
                    val count = (range.second - range.first + 1L).toInt()
                    if (count.toLong() > remainingBytes) throw IOException("Preview byte budget exhausted")
                    val bytes = ByteArray(count)
                    response.body.byteStream().use { input ->
                        var copied = 0
                        while (copied < count) {
                            checkBudget()
                            val read = input.read(bytes, copied, count - copied)
                            if (read < 0) throw IOException("Preview range is truncated")
                            copied += read
                            remainingBytes -= read
                        }
                    }
                    return bytes
                }
            } finally {
                if (activeCall === call) activeCall = null
                call.cancel()
            }
        }
        throw IOException("Preview redirect limit reached")
    }
}

internal fun parsePreviewUnsatisfiedRange(value: String?): Long? =
    Regex("bytes \\*/(\\d+)")
        .matchEntire(value.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()

private fun isStrongPreviewEntityTag(value: String): Boolean =
    value.length >= 2 &&
        value.first() == '"' &&
        value.last() == '"' &&
        value
            .substring(
                1,
                value.lastIndex,
            ).all { it == '\u0021' || it in '\u0023'..'\u007e' || it in '\u0080'..'\u00ff' }

internal fun parsePreviewContentRange(
    value: String?,
    requestedStart: Long,
): Triple<Long, Long, Long>? {
    val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty()) ?: return null
    val (start, end, total) = match.destructured
    val first = start.toLongOrNull() ?: return null
    val last = end.toLongOrNull() ?: return null
    val length = total.toLongOrNull() ?: return null
    if (first != requestedStart || last < first || last >= length || last - first >= PREVIEW_BLOCK_BYTES) return null
    return Triple(first, last, length)
}

private const val PREVIEW_BLOCK_BYTES = 128 * 1024
private val previewHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
