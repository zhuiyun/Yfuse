package com.yfuse.core.cast

import com.yfuse.core.logging.AppLog
import com.yfuse.core.playback.PLAYBACK_PROXY_HEADER_TIMEOUT_MS
import com.yfuse.core.playback.PlaybackProxyAdmission
import com.yfuse.core.playback.PlaybackProxyHeaderReader
import com.yfuse.feature.player.PlaybackProxyRequest
import com.yfuse.feature.player.PlaybackProxyRequests
import com.yfuse.feature.player.PlaybackProxyRoute
import com.yfuse.feature.player.PlaybackProxyRoutes
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Serves the media of a DLNA load to the renderer from this phone.
 *
 * A renderer handed the server's own address has to reach that server by itself: over HTTPS, with
 * its own User-Agent and none of the client headers the server sees from this app. Plenty of
 * televisions cannot do TLS to a current certificate chain, and servers that only admit known
 * clients turn them away, so the load is accepted and then nothing plays. Through the relay the
 * renderer reads plain HTTP on the local network, while this phone fetches the same bytes exactly
 * as its own player does. The access token stays in this process instead of the DIDL metadata.
 *
 * Only peers on the local network are answered, and only for routes published here: a 128-bit
 * random path that is retired when the cast ends.
 */
internal class DlnaMediaRelay(
    /** This phone's address on the network the renderer is on. */
    val bindAddress: InetAddress,
    baseClient: OkHttpClient,
    private val userAgent: () -> String,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val connectionAdmission: PlaybackProxyAdmission = PlaybackProxyAdmission(maximumConnections = 8),
    private val headerTimeoutMs: Long = PLAYBACK_PROXY_HEADER_TIMEOUT_MS,
) : Closeable {
    private val routes = PlaybackProxyRoutes()
    private val formats = ConcurrentHashMap<String, DlnaMediaFormat>()
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val httpClient =
        baseClient
            .newBuilder()
            .connectTimeout(UPSTREAM_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(UPSTREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
    private val closed = AtomicBoolean(false)
    private val requests = PlaybackProxyRequests {}
    private val workers: ExecutorService = connectionAdmission.workers("Yfuse-DlnaRelay-worker")
    private val server = ServerSocket(0, BACKLOG, bindAddress)

    @Volatile private var renderer: InetAddress? = null
    private val generation = AtomicInteger(0)
    private val requestCount = AtomicInteger(0)
    private val bytesServed = AtomicLong(0L)
    private val lastActivityAtMs = AtomicLong(NO_ACTIVITY)
    private val upstreamFailures = AtomicInteger(0)
    private val loggedRequests = AtomicInteger(0)
    private val peerRejectionLogged = AtomicBoolean(false)

    private val acceptThread =
        Thread(::acceptLoop, "Yfuse-DlnaRelay-accept").apply {
            isDaemon = true
            start()
        }

    val port: Int
        get() = server.localPort

    val isOpen: Boolean
        get() = !closed.get() && !server.isClosed

    /**
     * Makes [upstreamUrl] the media this relay serves and returns the address to give [rendererAddress].
     * The previous media stays reachable for a short grace period, so a renderer still finishing a
     * read of it, or a session restored after a failed load, keeps working. Publishing the same
     * address again returns the same path.
     */
    fun publish(
        upstreamUrl: String,
        format: DlnaMediaFormat,
        rendererAddress: InetAddress,
    ): String? {
        if (closed.get()) return null
        val routeId = routes.registerRoot(PlaybackProxyRoute(upstreamUrl, cacheable = false)) ?: return null
        formats[routeId] = format
        renderer = rendererAddress
        generation.incrementAndGet()
        requestCount.set(0)
        bytesServed.set(0L)
        lastActivityAtMs.set(NO_ACTIVITY)
        upstreamFailures.set(0)
        loggedRequests.set(0)
        return "http://${bindAddress.urlHost()}:$port/$ROUTE_PREFIX/$routeId/media.${format.extension}"
    }

    /** What the renderer has done with the media published last. */
    fun activity(): DlnaRelayActivity =
        DlnaRelayActivity(
            requests = requestCount.get(),
            bytesServed = bytesServed.get(),
            lastActivityAtMs = lastActivityAtMs.get().takeIf { it != NO_ACTIVITY },
            upstreamFailures = upstreamFailures.get(),
        )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        requests.close()
        workers.shutdownNow()
        routes.close()
        formats.clear()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            val acceptedAtNs = System.nanoTime()
            if (!socket.inetAddress.isAllowedPeer()) {
                if (peerRejectionLogged.compareAndSet(false, true)) {
                    AppLog.warning(
                        category = "cast",
                        event = "dlna_relay_peer_rejected",
                        message = "DLNA relay refused a connection from outside the local network",
                    )
                }
                runCatching { socket.close() }
                continue
            }
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
                        // A renderer closing its connection mid-body is how it seeks and stops.
                        if (!request.isCancelled && error !is IOException) {
                            AppLog.warning("cast", "dlna_relay_client_failed", "DLNA relay request failed", error)
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

    private fun InetAddress.isAllowedPeer(): Boolean = isLocalNetworkAddress() || this == renderer

    private fun serve(
        request: PlaybackProxyRequest,
        acceptedAtNs: Long,
    ) {
        val socket = request.socket
        val reader = PlaybackProxyHeaderReader(socket, headerTimeoutMs, acceptedAtNs)
        val requestLine = reader.readLine().orEmpty()
        val parts = requestLine.split(' ', limit = 3)
        val method = parts.getOrNull(0)?.uppercase().orEmpty()
        val routeId = parts.getOrNull(1)?.let(::relayRouteId)
        val requestHeaders = reader.readHeaders()
        request.ensureOpen()
        if (method != "GET" && method != "HEAD") {
            writeSimpleResponse(socket, 405, "Method Not Allowed", "Allow: GET, HEAD\r\n")
            return
        }
        val lease = routeId?.let(routes::acquire)
        if (lease == null) {
            writeSimpleResponse(socket, 404, "Not Found")
            return
        }
        requestCount.incrementAndGet()
        lastActivityAtMs.set(nowMs())
        lease.use { forward(request, lease, method, requestHeaders) }
    }

    private fun forward(
        request: PlaybackProxyRequest,
        lease: PlaybackProxyRoutes.Lease,
        method: String,
        requestHeaders: Map<String, String>,
    ) {
        val socket = request.socket
        val route = lease.route
        val format = formats[lease.id]
        val builder =
            Request
                .Builder()
                .url(route.upstreamUrl)
                .method(method, null)
                .header("Accept-Encoding", "identity")
        userAgent().trim().takeIf(String::isNotEmpty)?.let { builder.header("User-Agent", it) }
        FORWARDED_REQUEST_HEADERS.forEach { name ->
            requestHeaders[name.lowercase()]?.let { builder.header(name, it) }
        }
        val call = httpClient.newCall(builder.build())
        val startedAtMs = nowMs()
        var responseStarted = false
        try {
            request.attachUpstreamCancellation(call::cancel)
            request.ensureOpen()
            call.execute().use { response ->
                request.ensureOpen()
                val status = response.code
                if (status !in 200..299) upstreamFailures.incrementAndGet()
                logRequest(method, requestHeaders["range"], status, nowMs() - startedAtMs, root = format != null)
                val body = response.body.byteStream()
                if (method == "GET" && status in 200..299 && response.isHlsManifest(route.upstreamUrl)) {
                    val manifest = body.readBounded(MAX_HLS_MANIFEST_BYTES).toString(StandardCharsets.UTF_8)
                    val rewritten =
                        routes
                            .rewriteManifest(
                                parent = lease,
                                manifest = manifest,
                                upstreamUrl = response.request.url.toString(),
                                localUrl = ::childUrl,
                            ).toByteArray(StandardCharsets.UTF_8)
                    responseStarted = true
                    toRenderer {
                        writeHead(socket, response, format, rewritten.size.toLong())
                        socket.getOutputStream().write(rewritten)
                    }
                    countServed(rewritten.size.toLong())
                } else {
                    responseStarted = true
                    val length = response.header("Content-Length")?.toLongOrNull() ?: response.body.contentLength()
                    toRenderer { writeHead(socket, response, format, length) }
                    if (method == "GET") copyBody(body, request)
                }
                toRenderer { socket.getOutputStream().flush() }
            }
        } catch (error: Exception) {
            // Renderers close a read they no longer need all the time: to seek, or once buffered.
            if (request.isCancelled || error is RendererClosedException) return
            upstreamFailures.incrementAndGet()
            AppLog.warning(
                category = "cast",
                event = "dlna_relay_upstream_failed",
                message = "DLNA relay could not read the media from the server",
                throwable = error,
                attributes =
                    mapOf(
                        "method" to method,
                        "responseStarted" to responseStarted.toString(),
                        "elapsedMs" to (nowMs() - startedAtMs).toString(),
                    ),
            )
            if (!responseStarted) runCatching { writeSimpleResponse(socket, 502, "Bad Gateway") }
        } finally {
            call.cancel()
        }
    }

    private fun copyBody(
        body: InputStream,
        request: PlaybackProxyRequest,
    ) {
        val output = request.socket.getOutputStream()
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        while (true) {
            request.ensureOpen()
            val read = body.read(buffer)
            if (read < 0) break
            toRenderer { output.write(buffer, 0, read) }
            countServed(read.toLong())
        }
    }

    private fun countServed(bytes: Long) {
        bytesServed.addAndGet(bytes)
        lastActivityAtMs.set(nowMs())
    }

    private fun childUrl(routeId: String): String = "http://${bindAddress.urlHost()}:$port/$ROUTE_PREFIX/$routeId/s"

    /** The first requests of a load, and every refused one: enough to tell what the renderer did. */
    private fun logRequest(
        method: String,
        range: String?,
        status: Int,
        upstreamMs: Long,
        root: Boolean,
    ) {
        val failed = status !in 200..299
        val ordinal = loggedRequests.incrementAndGet()
        if (!failed && ordinal > LOGGED_REQUESTS_PER_LOAD) return
        if (failed && ordinal > LOGGED_REQUESTS_PER_LOAD + LOGGED_FAILURES_PER_LOAD) return
        val attributes =
            mapOf(
                "method" to method,
                "status" to status.toString(),
                "rangeStart" to (range?.let(::rangeStart)?.toString() ?: "none"),
                "upstreamMs" to upstreamMs.toString(),
                "route" to if (root) "media" else "segment",
                "load" to generation.get().toString(),
            )
        if (failed) {
            AppLog.warning("cast", "dlna_relay_request", "Server refused a DLNA relay request", attributes = attributes)
        } else {
            AppLog.info("cast", "dlna_relay_request", "DLNA renderer read media through the relay", attributes)
        }
    }

    private fun writeHead(
        socket: Socket,
        response: Response,
        format: DlnaMediaFormat?,
        contentLength: Long,
    ) {
        val head = StringBuilder()
        val reason = response.message.takeIf(String::isNotBlank) ?: "Upstream"
        head.append("HTTP/1.1 ${response.code} $reason\r\n")
        val contentType =
            if (format != null && response.code in 200..299) {
                format.mimeType
            } else {
                response.header("Content-Type")
            }
        contentType?.let { head.append("Content-Type: $it\r\n") }
        FORWARDED_RESPONSE_HEADERS.forEach { name ->
            response.header(name)?.let { head.append("$name: $it\r\n") }
        }
        if (contentLength >= 0L) head.append("Content-Length: $contentLength\r\n")
        head.append("transferMode.dlna.org: Streaming\r\n")
        format?.let { head.append("contentFeatures.dlna.org: ${it.contentFeatures}\r\n") }
        head.append("Connection: close\r\n\r\n")
        socket.getOutputStream().write(head.toString().toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeSimpleResponse(
        socket: Socket,
        status: Int,
        reason: String,
        extraHeaders: String = "",
    ) {
        val response = "HTTP/1.1 $status $reason\r\n${extraHeaders}Content-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(response.toByteArray(StandardCharsets.ISO_8859_1))
        socket.getOutputStream().flush()
    }
}

/**
 * The address this phone uses to reach [peer], found by routing a UDP socket there; connecting a UDP
 * socket sends nothing. Null when there is no route or only an address a renderer could not use.
 */
internal fun localAddressFacing(peer: InetAddress): InetAddress? =
    runCatching {
        DatagramSocket().use { socket ->
            socket.connect(peer, DISCARD_PORT)
            socket.localAddress
        }
    }.getOrNull()?.takeIf { address ->
        !address.isAnyLocalAddress &&
            (address is Inet4Address || (address is Inet6Address && !address.isLinkLocalAddress))
    }

/** Loopback, RFC 1918, link-local and IPv6 unique-local addresses. */
internal fun InetAddress.isLocalNetworkAddress(): Boolean =
    isLoopbackAddress ||
        isSiteLocalAddress ||
        isLinkLocalAddress ||
        (this is Inet6Address && (address[0].toInt() and 0xfe) == 0xfc)

/**
 * Why a DLNA load goes through this phone, or null when the renderer can read [mediaUrl] itself: plain
 * HTTP to a host on the local network, the way it reads any other media server there.
 */
internal fun dlnaRelayReason(
    mediaUrl: String,
    resolve: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
): String? {
    val uri = runCatching { URI(mediaUrl) }.getOrNull() ?: return null
    if (uri.scheme.equals("https", ignoreCase = true)) return "https"
    val host = uri.host ?: return null
    val addresses = runCatching { resolve(host) }.getOrNull().orEmpty()
    return when {
        addresses.isEmpty() -> "unresolved_host"
        addresses.all { it.isLocalNetworkAddress() } -> null
        else -> "public_host"
    }
}

/** A write to the renderer failed: it closed the connection, which is not a failure of the server. */
private class RendererClosedException(
    cause: IOException,
) : IOException(cause)

private inline fun <T> toRenderer(write: () -> T): T =
    try {
        write()
    } catch (closed: IOException) {
        throw RendererClosedException(closed)
    }

private fun relayRouteId(target: String): String? {
    // Some renderers send the absolute form of the request target.
    val path =
        if (target.startsWith("http://", ignoreCase = true)) {
            "/" + target.substringAfter("://").substringAfter('/', "")
        } else {
            target
        }
    return path
        .substringBefore('?')
        .takeIf { it.startsWith("/$ROUTE_PREFIX/") }
        ?.removePrefix("/$ROUTE_PREFIX/")
        ?.substringBefore('/')
        ?.takeIf(String::isNotEmpty)
}

private fun rangeStart(range: String): Long? =
    Regex("^bytes=(\\d+)-", RegexOption.IGNORE_CASE)
        .find(range.trim())
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()

private fun InetAddress.urlHost(): String = hostAddress.substringBefore('%').let { if (':' in it) "[$it]" else it }

private fun Response.isHlsManifest(originalUrl: String): Boolean {
    val contentType = header("Content-Type").orEmpty().lowercase()
    val path = request.url.encodedPath.lowercase()
    val originalPath = runCatching { URI(originalUrl).path }.getOrNull().orEmpty().lowercase()
    return "mpegurl" in contentType || path.endsWith(".m3u8") || originalPath.endsWith(".m3u8")
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(NETWORK_BUFFER_BYTES)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw IOException("HLS manifest exceeds $limit bytes")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val ROUTE_PREFIX = "yfuse-cast"
private const val BACKLOG = 8
private const val DISCARD_PORT = 9
private const val NO_ACTIVITY = Long.MIN_VALUE
private const val LOGGED_REQUESTS_PER_LOAD = 4
private const val LOGGED_FAILURES_PER_LOAD = 8
private const val UPSTREAM_CONNECT_TIMEOUT_SECONDS = 15L
private const val UPSTREAM_READ_TIMEOUT_SECONDS = 30L
private const val NETWORK_BUFFER_BYTES = 64 * 1024
private const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
private val FORWARDED_REQUEST_HEADERS = listOf("Range", "If-Range")
private val FORWARDED_RESPONSE_HEADERS =
    listOf(
        "Content-Range",
        "Accept-Ranges",
        "ETag",
        "Last-Modified",
    )
