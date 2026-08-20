package com.yfuse.feature.player

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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives libmpv an HTTP loopback URL while Android's platform TLS stack reads the real source.
 *
 * The bundled FFmpeg uses mbedTLS, which is less compatible with some reverse proxies than the
 * Android network stack used by the rest of Yfuse. The bridge is loopback-only, keeps credentials
 * in process memory, preserves byte-range requests, and rewrites HLS child URLs back through the
 * same bridge.
 */
internal class AndroidPlaybackHttpProxy(
    private val userAgent: String,
) : Closeable {
    private data class Route(
        val upstreamUrl: String,
    )

    private val routes = ConcurrentHashMap<String, Route>()
    private val routeIds = ConcurrentHashMap<String, String>()
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val closed = AtomicBoolean(false)
    private val workers: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "Yfuse-MpvHttpProxy-worker").apply { isDaemon = true }
        }
    private val server = ServerSocket(0, LOOPBACK_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    private val acceptThread =
        Thread(::acceptLoop, "Yfuse-MpvHttpProxy-accept").apply {
            isDaemon = true
            start()
        }

    val port: Int
        get() = server.localPort

    fun localUrl(upstreamUrl: String): String {
        if (!shouldProxyMpvNetworkUrl(upstreamUrl) || closed.get()) return upstreamUrl
        val routeId =
            routeIds.computeIfAbsent(upstreamUrl) {
                UUID.randomUUID().toString().replace("-", "")
            }
        routes[routeId] = Route(upstreamUrl)
        return "http://$LOOPBACK_HOST:$port/$ROUTE_PREFIX/$routeId"
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        workers.shutdownNow()
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
                        localize = ::localUrl,
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
                category = "player.mpv.network",
                event = "platform_proxy_failed",
                message = "Android platform transport could not read the mpv media source",
                throwable = error,
                attributes = mapOf("scheme" to upstreamUri.scheme.orEmpty()),
            )
            runCatching { writeSimpleResponse(socket, 502, "Bad Gateway") }
        } finally {
            connection.disconnect()
        }
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
private const val NETWORK_BUFFER_BYTES = 64 * 1024
private const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_REQUEST_LINE_LENGTH = 8 * 1024
private const val MAX_REQUEST_HEADER_LENGTH = 8 * 1024
private const val MAX_REQUEST_HEADER_COUNT = 64
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
