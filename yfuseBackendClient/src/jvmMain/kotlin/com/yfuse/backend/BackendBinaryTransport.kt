package com.yfuse.backend

import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Fully consumed bounded publication; non-success statuses have an empty body. */
data class BackendDocumentResponse(
    val statusCode: Int,
    val bytes: ByteArray,
)

/** The consumer owns close; package verification, range acceptance and file writes stay outside. */
interface BackendDownloadResponse : Closeable {
    val statusCode: Int
    val contentLength: Long
    val contentRange: String?

    /** Stored as etag:VALUE or last-modified:VALUE, matching persisted update validators. */
    val validator: String?
    val inputStream: InputStream
}

/** Owns project-backend HTTP configuration and connection lifetimes without Android dependencies. */
class BackendBinaryTransport(
    private val access: BackendAccess = BackendAccess.Default,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) {
    fun fetchDocument(
        url: String,
        feature: BackendFeature,
        maxBytes: Int,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        tooLargeMessage: String = "Backend document is too large",
    ): BackendDocumentResponse {
        access.requireEnabled(feature)
        require(maxBytes in 1 until Int.MAX_VALUE) { "Invalid document size limit" }
        val connection = open(url, connectTimeoutMillis, readTimeoutMillis)
        try {
            val status = connection.responseCode
            if (status !in 200..299) return BackendDocumentResponse(status, byteArrayOf())
            check(connection.contentLengthLong < 0L || connection.contentLengthLong <= maxBytes) { tooLargeMessage }
            val bytes = connection.inputStream.use { it.readAtMost(maxBytes + 1) }
            check(bytes.size <= maxBytes) { tooLargeMessage }
            return BackendDocumentResponse(status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    fun openDownload(
        url: String,
        feature: BackendFeature,
        startBytes: Long = 0L,
        validator: String? = null,
    ): BackendDownloadResponse {
        access.requireEnabled(feature)
        require(startBytes >= 0L) { "Invalid download offset" }
        val connection = open(url, connectTimeoutMillis = 15_000, readTimeoutMillis = 30_000)
        try {
            if (startBytes > 0L) {
                connection.setRequestProperty("Range", "bytes=$startBytes-")
                validator?.let { connection.setRequestProperty("If-Range", it.substringAfter(':')) }
            }
            return ConnectionDownloadResponse(connection, connection.responseCode)
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }

    private fun open(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): HttpURLConnection {
        val connection = connectionFactory(url)
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.useCaches = false
            // A deployment URL was validated by the app; redirects must not move that trust decision.
            connection.instanceFollowRedirects = false
            // Byte ranges, content lengths and signed package hashes refer to identity bytes.
            connection.setRequestProperty("Accept-Encoding", "identity")
            return connection
        } catch (failure: Throwable) {
            connection.disconnect()
            throw failure
        }
    }
}

private class ConnectionDownloadResponse(
    private val connection: HttpURLConnection,
    override val statusCode: Int,
) : BackendDownloadResponse {
    private val closed = AtomicBoolean(false)
    private var openedStream: InputStream? = null
    override val contentLength: Long = connection.contentLengthLong
    override val contentRange: String? = connection.getHeaderField("Content-Range")
    override val validator: String? = connection.resumeValidator()
    override val inputStream: InputStream
        get() {
            check(!closed.get()) { "Backend response is closed" }
            return openedStream ?: CloseOnceInputStream(connection.inputStream).also { openedStream = it }
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            openedStream?.close()
        } finally {
            connection.disconnect()
        }
    }
}

private class CloseOnceInputStream(
    input: InputStream,
) : FilterInputStream(input) {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) super.close()
    }
}

private fun HttpURLConnection.resumeValidator(): String? {
    val strongEtag =
        getHeaderField("ETag")
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
    if (strongEtag != null) return "etag:$strongEtag"
    return getHeaderField("Last-Modified")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "last-modified:$it" }
}

private fun InputStream.readAtMost(limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var offset = 0
    while (offset < limit) {
        val read = read(buffer, offset, limit - offset)
        if (read < 0) break
        if (read == 0) {
            val next = read()
            if (next < 0) break
            buffer[offset++] = next.toByte()
        } else {
            offset += read
        }
    }
    return buffer.copyOf(offset)
}
