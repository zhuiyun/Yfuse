package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import com.yfuse.core.logging.AppLog
import com.yfuse.core.playback.PLAYBACK_PROXY_HEADER_TIMEOUT_MS
import com.yfuse.core.playback.PlaybackProxyAdmission
import com.yfuse.core.playback.PlaybackProxyHeaderReader
import okhttp3.Request
import okhttp3.Response
import java.io.Closeable
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Gives native players an HTTP loopback URL while Android reads the real source.
 *
 * The bridge keeps credentials in process memory, preserves byte-range requests and rewrites HLS
 * child URLs. Eligible direct files use the same sparse Media3 cache as ExoPlayer; manifests,
 * transcodes, DRM and local/disc sources never enter that persistent cache.
 */
@OptIn(UnstableApi::class)
internal class AndroidPlaybackHttpProxy(
    context: Context?,
    private val userAgent: String,
    videoCacheBytes: Long,
    private val connectionAdmission: PlaybackProxyAdmission = PlaybackProxyAdmission(),
    private val headerTimeoutMs: Long = PLAYBACK_PROXY_HEADER_TIMEOUT_MS,
) : Closeable {
    private val routes = PlaybackProxyRoutes()
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val httpClient =
        PlaybackHttpDataSource.client
            .newBuilder()
            .connectTimeout(UPSTREAM_CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(UPSTREAM_READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val uri = original.url.toUri()
                val cookies = cookieManager.get(uri, emptyMap())["Cookie"].orEmpty()
                val outgoing =
                    if (cookies.isEmpty()) {
                        original
                    } else {
                        original
                            .newBuilder()
                            .header("Cookie", cookies.joinToString("; "))
                            .build()
                    }
                chain.proceed(outgoing).also { response ->
                    cookieManager.put(uri, response.headers.toMultimap())
                }
            }.build()
    private val cacheHandle =
        if (videoCacheBytes >
            0L
        ) {
            VideoCachePool.acquire(requireNotNull(context).applicationContext, videoCacheBytes)
        } else {
            null
        }
    private val closed = AtomicBoolean(false)
    private val requests =
        PlaybackProxyRequests {
            runCatching { cacheHandle?.close() }.onFailure { error ->
                AppLog.warning(
                    "player.network",
                    "cache_proxy_release_failed",
                    "Could not release playback cache ownership",
                    error,
                )
            }
        }
    private val workers: ExecutorService = connectionAdmission.workers("Yfuse-PlaybackHttpProxy-worker")
    private val server = ServerSocket(0, LOOPBACK_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    private val acceptThread =
        Thread(::acceptLoop, "Yfuse-PlaybackHttpProxy-accept").apply {
            isDaemon = true
            start()
        }

    val port: Int
        get() = server.localPort

    /** State of this listener, independent of later reuse of its ephemeral port. */
    val isListening: Boolean
        get() = !server.isClosed

    fun localUrl(
        upstreamUrl: String,
        cacheable: Boolean = false,
    ): String {
        if (!shouldProxyMpvNetworkUrl(upstreamUrl)) return upstreamUrl
        val routeId = routes.registerRoot(PlaybackProxyRoute(upstreamUrl, cacheable)) ?: return upstreamUrl
        return routeUrl(routeId)
    }

    private fun routeUrl(routeId: String): String = "http://$LOOPBACK_HOST:$port/$ROUTE_PREFIX/$routeId"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        requests.close()
        workers.shutdownNow()
        // A DefaultHttpDataSource open cannot always be interrupted. Its worker retains the
        // cache lease until its existing timeout/finally completes; release never waits for it.
        routes.close()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            val acceptedAtNs = System.nanoTime()
            val admission = connectionAdmission.tryAcquire()
            if (admission == null) {
                runCatching { socket.close() }
                continue
            }
            val request = requests.register(socket)
            if (request == null) {
                admission.close()
                continue
            }
            runCatching {
                workers.execute {
                    try {
                        request.ensureOpen()
                        serve(request, acceptedAtNs)
                    } catch (error: Exception) {
                        if (!request.isCancelled) {
                            AppLog.warning(
                                "player.network",
                                "proxy_client_failed",
                                "Playback proxy client request failed",
                                error,
                            )
                        }
                    } finally {
                        try {
                            requests.finish(request)
                        } finally {
                            admission.close()
                        }
                    }
                }
            }.onFailure {
                try {
                    requests.finish(request)
                } finally {
                    admission.close()
                }
            }
        }
    }

    private fun serve(
        request: PlaybackProxyRequest,
        acceptedAtNs: Long,
    ) {
        val socket = request.socket
        val reader = PlaybackProxyHeaderReader(socket, headerTimeoutMs, acceptedAtNs)
        val requestLine = reader.readLine().orEmpty()
        val parts = requestLine.split(' ', limit = 3)
        val method = parts.getOrNull(0)?.uppercase().orEmpty()
        val routeId = parts.getOrNull(1)?.substringBefore('?')?.substringAfter("/$ROUTE_PREFIX/")
        if (method !in setOf("GET", "HEAD")) {
            writeSimpleResponse(socket, 404, "Not Found")
            return
        }

        val requestHeaders = reader.readHeaders()
        request.ensureOpen()

        val lease = routeId?.let(routes::acquire)
        if (lease == null) {
            writeSimpleResponse(socket, 404, "Not Found")
            return
        }
        lease.use {
            if (method == "GET" && lease.route.cacheable && cacheHandle != null) {
                serveCached(request, lease.route, requestHeaders)
            } else {
                servePlatform(request, lease, method, requestHeaders)
            }
        }
    }

    private fun serveCached(
        request: PlaybackProxyRequest,
        route: PlaybackProxyRoute,
        requestHeaders: Map<String, String>,
    ) {
        val socket = request.socket
        request.ensureOpen()
        val rawRange = requestHeaders["range"]
        val range = parsePlaybackHttpByteRange(rawRange)
        val knownLength = cachedContentLength(route.upstreamUrl)
        if (rawRange != null && range == null) {
            writeRangeNotSatisfiable(socket, knownLength)
            return
        }
        if (knownLength != null && range != null && range.start >= knownLength) {
            writeRangeNotSatisfiable(socket, knownLength)
            return
        }

        val upstreamUri = URI(route.upstreamUrl)
        val requestProperties =
            buildMap {
                requestHeaders["accept"]?.let { put("Accept", it) }
                cookieManager
                    .get(upstreamUri, emptyMap())["Cookie"]
                    ?.takeIf(List<String>::isNotEmpty)
                    ?.let { put("Cookie", it.joinToString("; ")) }
            }
        val upstreamFactory =
            PlaybackHttpDataSource.factory(userAgent)
        val dataSource =
            CacheDataSource
                .Factory()
                .setCache(requireNotNull(cacheHandle).cache)
                .setCacheKeyFactory(SecureMediaCacheKeyFactory)
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        var responseStarted = false
        try {
            val start = range?.start ?: 0L
            val requestedLength =
                range?.endInclusive?.let { end -> end - start + 1L } ?: UNKNOWN_LENGTH
            val dataSpec =
                DataSpec
                    .Builder()
                    .setUri(route.upstreamUrl)
                    .setPosition(start)
                    .setLength(requestedLength)
                    .setHttpRequestHeaders(requestProperties)
                    .build()
            val openedLength = dataSource.open(dataSpec)
            request.ensureOpen()
            val responseHeaders = dataSource.responseHeaders
            runCatching { cookieManager.put(upstreamUri, responseHeaders) }

            val totalLength =
                cachedContentLength(route.upstreamUrl)
                    ?: responseContentRangeTotal(responseHeaders)
                    ?: when {
                        range == null && openedLength != UNKNOWN_LENGTH -> openedLength
                        range?.endInclusive == null && openedLength != UNKNOWN_LENGTH ->
                            start + openedLength
                        else -> null
                    }
            if (totalLength != null && start >= totalLength) {
                writeRangeNotSatisfiable(socket, totalLength)
                return
            }
            val contentLength =
                sequenceOf(
                    openedLength.takeIf { it != UNKNOWN_LENGTH },
                    requestedLength.takeIf { it != UNKNOWN_LENGTH },
                    totalLength?.let { it - start },
                ).filterNotNull()
                    .minOrNull()
                    ?.coerceAtLeast(0L)
                    ?: UNKNOWN_LENGTH
            val endInclusive =
                if (contentLength != UNKNOWN_LENGTH && contentLength > 0L) {
                    start + contentLength - 1L
                } else {
                    range?.endInclusive
                }
            writeCachedResponse(
                socket = socket,
                status = if (range == null) 200 else 206,
                contentType = responseHeader(responseHeaders, "Content-Type"),
                contentLength = contentLength,
                rangeStart = start.takeIf { range != null },
                rangeEndInclusive = endInclusive.takeIf { range != null },
                totalLength = totalLength,
                responseHeaders = responseHeaders,
            )
            responseStarted = true
            copyDataSource(
                dataSource = dataSource,
                request = request,
                contentLength = contentLength,
            )
            socket.getOutputStream().flush()
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (!request.isCancelled && !responseStarted) {
                if (error.responseCode == 416) {
                    writeRangeNotSatisfiable(socket, knownLength)
                } else {
                    writeSimpleResponse(socket, error.responseCode, "Upstream")
                }
            }
        } catch (error: Exception) {
            if (request.isCancelled) return
            AppLog.warning(
                category = "player.network",
                event = "cache_proxy_failed",
                message = "Shared playback cache bridge could not read the media source",
                throwable = error,
                attributes = mapOf("scheme" to upstreamUri.scheme.orEmpty()),
            )
            if (!responseStarted) runCatching { writeSimpleResponse(socket, 502, "Bad Gateway") }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun servePlatform(
        request: PlaybackProxyRequest,
        lease: PlaybackProxyRoutes.Lease,
        method: String,
        requestHeaders: Map<String, String>,
    ) {
        val route = lease.route
        val socket = request.socket
        val upstreamUri = URI(route.upstreamUrl)
        val builder =
            Request
                .Builder()
                .url(route.upstreamUrl)
                .method(method, null)
                .header("Accept-Encoding", "identity")
        userAgent.trim().takeIf(String::isNotEmpty)?.let { builder.header("User-Agent", it) }
        FORWARDED_REQUEST_HEADERS.forEach { name ->
            requestHeaders[name.lowercase()]?.let { builder.header(name, it) }
        }
        val call = httpClient.newCall(builder.build())
        var responseStarted = false
        try {
            request.attachUpstreamCancellation(call::cancel)
            request.ensureOpen()
            call.execute().use { connection ->
                val status = connection.code
                request.ensureOpen()
                val body = connection.body?.byteStream()
                val manifest = method == "GET" && connection.isHlsManifest(route.upstreamUrl)
                if (manifest) {
                    val bytes = body?.readBounded(MAX_HLS_MANIFEST_BYTES) ?: ByteArray(0)
                    val rewritten =
                        routes
                            .rewriteManifest(
                                parent = lease,
                                manifest = bytes.toString(StandardCharsets.UTF_8),
                                upstreamUrl = connection.request.url.toString(),
                                localUrl = ::routeUrl,
                            ).toByteArray(StandardCharsets.UTF_8)
                    responseStarted = true
                    writeResponse(socket, connection, status, rewritten.size.toLong())
                    socket.getOutputStream().write(rewritten)
                } else {
                    responseStarted = true
                    val length =
                        connection.header("Content-Length")?.toLongOrNull()
                            ?: connection.body?.contentLength() ?: UNKNOWN_LENGTH
                    writeResponse(socket, connection, status, length)
                    if (method == "GET") body?.copyTo(socket.getOutputStream(), NETWORK_BUFFER_BYTES)
                }
                socket.getOutputStream().flush()
            }
        } catch (error: Exception) {
            if (request.isCancelled) return
            AppLog.warning(
                category = "player.network",
                event = "platform_proxy_failed",
                message = "Android platform transport could not read the media source",
                throwable = error,
                attributes = mapOf("scheme" to upstreamUri.scheme.orEmpty()),
            )
            if (!responseStarted) runCatching { writeSimpleResponse(socket, 502, "Bad Gateway") }
        } finally {
            call.cancel()
        }
    }

    private fun cachedContentLength(upstreamUrl: String): Long? {
        val handle = cacheHandle ?: return null
        val contentLength =
            ContentMetadata.getContentLength(
                handle.cache.getContentMetadata(secureMediaCacheKeyForUrl(upstreamUrl)),
            )
        return contentLength.takeIf { it >= 0L }
    }

    private fun copyDataSource(
        dataSource: CacheDataSource,
        request: PlaybackProxyRequest,
        contentLength: Long,
    ) {
        val output = request.socket.getOutputStream()
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        var remaining = contentLength
        while (remaining != 0L) {
            request.ensureOpen()
            val requested =
                if (remaining == UNKNOWN_LENGTH) {
                    buffer.size
                } else {
                    min(buffer.size.toLong(), remaining).toInt()
                }
            val read = dataSource.read(buffer, 0, requested)
            request.ensureOpen()
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
            if (remaining != UNKNOWN_LENGTH) remaining -= read.toLong()
        }
    }

    private fun writeCachedResponse(
        socket: Socket,
        status: Int,
        contentType: String?,
        contentLength: Long,
        rangeStart: Long?,
        rangeEndInclusive: Long?,
        totalLength: Long?,
        responseHeaders: Map<String, List<String>>,
    ) {
        val output = socket.getOutputStream()
        val reason = if (status == 206) "Partial Content" else "OK"
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(
            "Content-Type: ${contentType ?: "application/octet-stream"}\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        output.write("Accept-Ranges: bytes\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        if (rangeStart != null && rangeEndInclusive != null) {
            val total = totalLength?.toString() ?: "*"
            output.write(
                "Content-Range: bytes $rangeStart-$rangeEndInclusive/$total\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1),
            )
        }
        CACHE_VALIDATION_RESPONSE_HEADERS.forEach { name ->
            responseHeader(responseHeaders, name)?.let { value ->
                output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
        }
        if (contentLength != UNKNOWN_LENGTH) {
            output.write(
                "Content-Length: $contentLength\r\n".toByteArray(StandardCharsets.ISO_8859_1),
            )
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeResponse(
        socket: Socket,
        connection: Response,
        status: Int,
        contentLength: Long,
    ) {
        val output = socket.getOutputStream()
        val reason = connection.message.takeIf(String::isNotBlank) ?: "Upstream"
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        FORWARDED_RESPONSE_HEADERS.forEach { name ->
            connection.header(name)?.let { value ->
                output.write("$name: $value\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            }
        }
        if (contentLength >= 0L) {
            output.write("Content-Length: $contentLength\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeRangeNotSatisfiable(
        socket: Socket,
        totalLength: Long?,
    ) {
        val output = socket.getOutputStream()
        output.write(
            "HTTP/1.1 416 Range Not Satisfiable\r\n".toByteArray(StandardCharsets.ISO_8859_1),
        )
        totalLength?.let {
            output.write("Content-Range: bytes */$it\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun writeSimpleResponse(
        socket: Socket,
        status: Int,
        reason: String,
    ) {
        val response =
            "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }
}

internal data class PlaybackHttpByteRange(
    val start: Long,
    val endInclusive: Long?,
)

internal fun parsePlaybackHttpByteRange(value: String?): PlaybackHttpByteRange? {
    val match = value?.trim()?.let(HTTP_BYTE_RANGE::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val endInclusive = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
    if (endInclusive != null && endInclusive < start) return null
    return PlaybackHttpByteRange(start = start, endInclusive = endInclusive)
}

internal fun shouldProxyMpvNetworkUrl(url: String): Boolean =
    runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

internal fun rewriteMpvHlsManifest(
    manifest: String,
    upstreamUrl: String,
    localize: (String) -> String,
): String {
    val base = runCatching { URI(upstreamUrl) }.getOrNull() ?: return manifest
    return manifest.lineSequence().joinToString("\n") { line ->
        when {
            line.isBlank() -> line
            !line.startsWith('#') ->
                runCatching { localize(base.resolve(line.trim()).toString()) }.getOrDefault(line)
            "URI=" in line ->
                HLS_URI_ATTRIBUTE.replace(line) { match ->
                    val quote = match.groupValues[1]
                    val value = match.groupValues[2]
                    val localized =
                        runCatching { localize(base.resolve(value).toString()) }.getOrDefault(value)
                    "URI=$quote$localized$quote"
                }
            else -> line
        }
    }
}

private fun responseHeader(
    headers: Map<String, List<String>>,
    name: String,
): String? =
    headers.entries
        .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
        ?.value
        ?.firstOrNull()

private fun responseContentRangeTotal(headers: Map<String, List<String>>): Long? =
    responseHeader(headers, "Content-Range")
        ?.let(CONTENT_RANGE_TOTAL::matchEntire)
        ?.groupValues
        ?.get(1)
        ?.takeUnless { it == "*" }
        ?.toLongOrNull()

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(NETWORK_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= limit) { "HLS manifest exceeds $limit bytes" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun Response.isHlsManifest(originalUrl: String): Boolean {
    val contentType = header("Content-Type").orEmpty().lowercase()
    val path = request.url.encodedPath.lowercase()
    val originalPath = runCatching { URI(originalUrl).path }.getOrNull().orEmpty().lowercase()
    return "mpegurl" in contentType || path.endsWith(".m3u8") || originalPath.endsWith(".m3u8")
}

private const val LOOPBACK_HOST = "127.0.0.1"
private const val LOOPBACK_BACKLOG = 8
private const val ROUTE_PREFIX = "yfuse-media"
private const val UPSTREAM_CONNECT_TIMEOUT_MS = 15_000
private const val UPSTREAM_READ_TIMEOUT_MS = 30_000
private const val UNKNOWN_LENGTH = -1L
private const val NETWORK_BUFFER_BYTES = 64 * 1024
private const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
private val HTTP_BYTE_RANGE = Regex("^bytes=(\\d+)-(\\d*)$", RegexOption.IGNORE_CASE)
private val CONTENT_RANGE_TOTAL = Regex("^bytes \\d+-\\d+/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
private val HLS_URI_ATTRIBUTE = Regex("URI=([\\\"'])(.*?)(?:\\1)")
private val FORWARDED_REQUEST_HEADERS =
    listOf("Range", "If-Range", "If-None-Match", "If-Modified-Since", "Accept")
private val FORWARDED_RESPONSE_HEADERS =
    listOf(
        "Content-Type",
        "Content-Range",
        "Accept-Ranges",
        "ETag",
        "Last-Modified",
        "Cache-Control",
    )
private val CACHE_VALIDATION_RESPONSE_HEADERS =
    listOf("ETag", "Last-Modified", "Cache-Control")
