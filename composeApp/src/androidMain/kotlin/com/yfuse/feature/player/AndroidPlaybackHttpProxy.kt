package com.yfuse.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import com.yfuse.core.logging.AppLog
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    context: Context,
    private val userAgent: String,
    videoCacheBytes: Long,
) : Closeable {
    private data class Route(
        val upstreamUrl: String,
        val cacheable: Boolean,
    )

    private val routes = ConcurrentHashMap<String, Route>()
    private val routeIds = ConcurrentHashMap<Route, String>()
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val cacheHandle = VideoCachePool.acquire(context.applicationContext, videoCacheBytes)
    private val closed = AtomicBoolean(false)
    private val workers: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "Yfuse-PlaybackHttpProxy-worker").apply { isDaemon = true }
        }
    private val server = ServerSocket(0, LOOPBACK_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    private val acceptThread =
        Thread(::acceptLoop, "Yfuse-PlaybackHttpProxy-accept").apply {
            isDaemon = true
            start()
        }

    val port: Int
        get() = server.localPort

    fun localUrl(
        upstreamUrl: String,
        cacheable: Boolean = false,
    ): String {
        if (!shouldProxyMpvNetworkUrl(upstreamUrl) || closed.get()) return upstreamUrl
        val route = Route(upstreamUrl = upstreamUrl, cacheable = cacheable)
        val routeId =
            routeIds.computeIfAbsent(route) {
                UUID.randomUUID().toString().replace("-", "")
            }
        routes[routeId] = route
        return "http://$LOOPBACK_HOST:$port/$ROUTE_PREFIX/$routeId"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        cacheHandle?.close()
        routes.clear()
        routeIds.clear()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            runCatching { workers.execute { socket.use(::serve) } }
                .onFailure { runCatching { socket.close() } }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = CLIENT_SOCKET_TIMEOUT_MS
        val reader =
            BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1),
            )
        val requestLine = reader.readLine()?.take(MAX_REQUEST_LINE_LENGTH).orEmpty()
        val parts = requestLine.split(' ', limit = 3)
        val method = parts.getOrNull(0)?.uppercase().orEmpty()
        val routeId = parts.getOrNull(1)?.substringBefore('?')?.substringAfter("/$ROUTE_PREFIX/")
        val route = routeId?.let(routes::get)
        if (method !in setOf("GET", "HEAD") || route == null) {
            writeSimpleResponse(socket, 404, "Not Found")
            return
        }

        val requestHeaders = linkedMapOf<String, String>()
        var headerCount = 0
        while (headerCount++ < MAX_REQUEST_HEADER_COUNT) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                requestHeaders[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim().take(MAX_REQUEST_HEADER_LENGTH)
            }
        }

        if (method == "GET" && route.cacheable && cacheHandle != null) {
            serveCached(socket, route, requestHeaders)
        } else {
            servePlatform(socket, route, method, requestHeaders)
        }
    }

    private fun serveCached(
        socket: Socket,
        route: Route,
        requestHeaders: Map<String, String>,
    ) {
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
            DefaultHttpDataSource
                .Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(UPSTREAM_CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(UPSTREAM_READ_TIMEOUT_MS)
                .apply {
                    userAgent.trim().takeIf(String::isNotEmpty)?.let { value ->
                        setDefaultRequestProperties(mapOf("User-Agent" to value))
                    }
                }
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
                range?.endInclusive?.let { end -> end - start + 1L } ?: C.LENGTH_UNSET
            val dataSpec =
                DataSpec
                    .Builder()
                    .setUri(route.upstreamUrl)
                    .setPosition(start)
                    .setLength(requestedLength)
                    .setHttpRequestHeaders(requestProperties)
                    .build()
            val openedLength = dataSource.open(dataSpec)
            val responseHeaders = dataSource.responseHeaders
            runCatching { cookieManager.put(upstreamUri, responseHeaders) }

            val totalLength =
                cachedContentLength(route.upstreamUrl)
                    ?: responseContentRangeTotal(responseHeaders)
                    ?: when {
                        range == null && openedLength != C.LENGTH_UNSET -> openedLength
                        range?.endInclusive == null && openedLength != C.LENGTH_UNSET ->
                            start + openedLength
                        else -> null
                    }
            if (totalLength != null && start >= totalLength) {
                writeRangeNotSatisfiable(socket, totalLength)
                return
            }
            val contentLength =
                sequenceOf(
                    openedLength.takeIf { it != C.LENGTH_UNSET },
                    requestedLength.takeIf { it != C.LENGTH_UNSET },
                    totalLength?.let { it - start },
                ).filterNotNull()
                    .minOrNull()
                    ?.coerceAtLeast(0L)
                    ?: C.LENGTH_UNSET
            val endInclusive =
                if (contentLength != C.LENGTH_UNSET && contentLength > 0L) {
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
                socket = socket,
                contentLength = contentLength,
            )
            socket.getOutputStream().flush()
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            if (!responseStarted) {
                if (error.responseCode == 416) {
                    writeRangeNotSatisfiable(socket, knownLength)
                } else {
                    writeSimpleResponse(socket, error.responseCode, "Upstream")
                }
            }
        } catch (error: Exception) {
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
        socket: Socket,
        route: Route,
        method: String,
        requestHeaders: Map<String, String>,
    ) {
        val upstreamUri = URI(route.upstreamUrl)
        val connection = URL(route.upstreamUrl).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = true
            connection.connectTimeout = UPSTREAM_CONNECT_TIMEOUT_MS
            connection.readTimeout = UPSTREAM_READ_TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("Accept-Encoding", "identity")
            userAgent.trim().takeIf(String::isNotEmpty)?.let {
                connection.setRequestProperty("User-Agent", it)
            }
            FORWARDED_REQUEST_HEADERS.forEach { name ->
                requestHeaders[name.lowercase()]?.let { value ->
                    connection.setRequestProperty(name, value)
                }
            }
            cookieManager
                .get(upstreamUri, emptyMap())["Cookie"]
                ?.takeIf(List<String>::isNotEmpty)
                ?.let { connection.setRequestProperty("Cookie", it.joinToString("; ")) }

            val status = connection.responseCode
            runCatching { cookieManager.put(upstreamUri, connection.headerFields) }
            val body = responseBody(connection)
            val manifest = method == "GET" && connection.isHlsManifest(route.upstreamUrl)
            if (manifest) {
                val bytes = body?.readBounded(MAX_HLS_MANIFEST_BYTES) ?: ByteArray(0)
                val rewritten =
                    rewriteMpvHlsManifest(
                        manifest = bytes.toString(StandardCharsets.UTF_8),
                        upstreamUrl = connection.url.toString(),
                        localize = { childUrl -> localUrl(childUrl, cacheable = false) },
                    ).toByteArray(StandardCharsets.UTF_8)
                writeResponse(socket, connection, status, rewritten.size.toLong())
                socket.getOutputStream().write(rewritten)
            } else {
                writeResponse(socket, connection, status, connection.contentLengthLong)
                if (method == "GET") body?.copyTo(socket.getOutputStream(), NETWORK_BUFFER_BYTES)
            }
            socket.getOutputStream().flush()
        } catch (error: Exception) {
            AppLog.warning(
                category = "player.network",
                event = "platform_proxy_failed",
                message = "Android platform transport could not read the media source",
                throwable = error,
                attributes = mapOf("scheme" to upstreamUri.scheme.orEmpty()),
            )
            runCatching { writeSimpleResponse(socket, 502, "Bad Gateway") }
        } finally {
            connection.disconnect()
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
        socket: Socket,
        contentLength: Long,
    ) {
        val output = socket.getOutputStream()
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        var remaining = contentLength
        while (remaining != 0L) {
            val requested =
                if (remaining == C.LENGTH_UNSET) {
                    buffer.size
                } else {
                    min(buffer.size.toLong(), remaining).toInt()
                }
            val read = dataSource.read(buffer, 0, requested)
            if (read == C.RESULT_END_OF_INPUT) break
            output.write(buffer, 0, read)
            if (remaining != C.LENGTH_UNSET) remaining -= read.toLong()
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
        if (contentLength != C.LENGTH_UNSET) {
            output.write(
                "Content-Length: $contentLength\r\n".toByteArray(StandardCharsets.ISO_8859_1),
            )
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeResponse(
        socket: Socket,
        connection: HttpURLConnection,
        status: Int,
        contentLength: Long,
    ) {
        val output = socket.getOutputStream()
        val reason = connection.responseMessage?.takeIf(String::isNotBlank) ?: "Upstream"
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        FORWARDED_RESPONSE_HEADERS.forEach { name ->
            connection.getHeaderField(name)?.let { value ->
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

    private fun responseBody(connection: HttpURLConnection): InputStream? =
        if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
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

private fun HttpURLConnection.isHlsManifest(originalUrl: String): Boolean {
    val contentType = contentType.orEmpty().lowercase()
    val path = runCatching { URI(url.toString()).path }.getOrNull().orEmpty().lowercase()
    val originalPath = runCatching { URI(originalUrl).path }.getOrNull().orEmpty().lowercase()
    return "mpegurl" in contentType || path.endsWith(".m3u8") || originalPath.endsWith(".m3u8")
}

private const val LOOPBACK_HOST = "127.0.0.1"
private const val LOOPBACK_BACKLOG = 8
private const val ROUTE_PREFIX = "yfuse-media"
private const val CLIENT_SOCKET_TIMEOUT_MS = 30_000
private const val UPSTREAM_CONNECT_TIMEOUT_MS = 15_000
private const val UPSTREAM_READ_TIMEOUT_MS = 30_000
private const val WORKER_SHUTDOWN_TIMEOUT_MS = 2_000L
private const val NETWORK_BUFFER_BYTES = 64 * 1024
private const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_REQUEST_LINE_LENGTH = 8 * 1024
private const val MAX_REQUEST_HEADER_LENGTH = 8 * 1024
private const val MAX_REQUEST_HEADER_COUNT = 64
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
