package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.adaptive.YAdaptiveEncryptionMethod
import com.yfuse.core2.adaptive.YAdaptiveSelectionConditions
import com.yfuse.core2.adaptive.YAdaptiveVariantSelector
import com.yfuse.core2.adaptive.YDashRepresentation
import com.yfuse.core2.adaptive.YDashResourceKind
import com.yfuse.core2.adaptive.YHlsPlaylist
import com.yfuse.core2.adaptive.YHlsResourceKind
import com.yfuse.core2.adaptive.buildYDashPlaybackManifest
import com.yfuse.core2.adaptive.parseYDashManifest
import com.yfuse.core2.adaptive.parseYHlsPlaylist
import com.yfuse.core2.adaptive.renderDashTemplate
import com.yfuse.core2.adaptive.rewriteYHlsResourceUris
import com.yfuse.core2.adaptive.selectYDashPlaybackRepresentations
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-local HTTP boundary for native demuxers that only accept URLs.
 *
 * Every upstream request still goes through [YMediaTransport]. HLS child manifests, segments,
 * initialization data, and keys are rewritten back through this boundary. Persistent bytes use
 * YCore's anonymous sparse block cache; live media and encryption keys are never written to disk.
 */
internal class AndroidYCoreHttpProxy(
    context: Context,
    private val userAgent: String,
    private val cacheMaximumBytes: Long,
    private val createTransport: () -> YMediaTransport = ::AndroidHttpMediaTransport,
) : Closeable {
    private data class Route(
        val upstreamUri: String,
        val cacheable: Boolean,
        val cacheIdentity: YCacheIdentity?,
        val maximumWidth: Int?,
        val maximumHeight: Int?,
        val hlsManifest: Boolean,
        val dashManifest: Boolean,
        val drmProtected: Boolean,
        val dashTemplate: DashTemplateRoute? = null,
    )

    private data class DashTemplateRoute(
        val representation: YDashRepresentation,
        val upstreamTemplate: String,
        val usesNumber: Boolean,
        val usesTime: Boolean,
        val localExtension: String,
    )

    private val cacheDirectory = context.applicationContext.cacheDir
    private val routesLock = Any()
    private val routes = LinkedHashMap<String, Route>()
    private val routeIds = HashMap<Route, String>()
    private val closed = AtomicBoolean(false)
    private val workers: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "YCore-HttpProxy-worker").apply { isDaemon = true }
        }
    private val server = ServerSocket(0, LOOPBACK_BACKLOG, InetAddress.getByName(LOOPBACK_HOST))
    private val acceptThread =
        Thread(::acceptLoop, "YCore-HttpProxy-accept").apply {
            isDaemon = true
            start()
        }

    fun localUrl(
        upstreamUri: String,
        cacheable: Boolean,
        cacheIdentity: YCacheIdentity?,
        maximumWidth: Int? = null,
        maximumHeight: Int? = null,
        hlsManifest: Boolean = upstreamUri.isHlsManifestUri(),
        dashManifest: Boolean = upstreamUri.isDashManifestUri(),
        drmProtected: Boolean = false,
    ): String {
        if (closed.get() || upstreamUri.sourceProtocolOrNull() == null) return upstreamUri
        val route =
            Route(
                upstreamUri = upstreamUri,
                cacheable = cacheable,
                cacheIdentity = cacheIdentity,
                maximumWidth = maximumWidth,
                maximumHeight = maximumHeight,
                hlsManifest = hlsManifest,
                dashManifest = dashManifest,
                drmProtected = drmProtected,
            )
        val syntheticPath =
            when {
                hlsManifest -> "/playlist.m3u8"
                dashManifest -> "/manifest.mpd"
                else -> upstreamUri.safeSyntheticExtension()?.let { "/resource.$it" }.orEmpty()
            }
        return localRouteUrl(route, syntheticPath)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        synchronized(routesLock) {
            routes.clear()
            routeIds.clear()
        }
    }

    private fun registerRoute(route: Route): String {
        require(routes.size < MAX_ROUTES) { "YCore adaptive route limit exceeded" }
        val routeId = UUID.randomUUID().toString().replace("-", "")
        routes[routeId] = route
        routeIds[route] = routeId
        return routeId
    }

    private fun localRouteUrl(
        route: Route,
        pathSuffix: String,
    ): String {
        val routeId =
            synchronized(routesLock) {
                routeIds[route] ?: registerRoute(route)
            }
        return "http://$LOOPBACK_HOST:${server.localPort}/$ROUTE_PREFIX/$routeId$pathSuffix"
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
        val requestLine = reader.readLine()?.take(MAX_REQUEST_LINE_BYTES).orEmpty()
        val requestParts = requestLine.split(' ', limit = 3)
        val method = requestParts.getOrNull(0)?.uppercase().orEmpty()
        val localPath =
            requestParts
                .getOrNull(1)
                ?.substringBefore('?')
                ?.substringAfter("/$ROUTE_PREFIX/", missingDelimiterValue = "")
                ?.takeIf(String::isNotEmpty)
        val routeId = localPath?.substringBefore('/')
        val routeSuffix = localPath?.substringAfter('/', missingDelimiterValue = "").orEmpty()
        val route = routeId?.let(::findRoute)?.resolveDashTemplate(routeSuffix)
        if (method !in ALLOWED_METHODS || route == null) {
            writeEmptyResponse(socket, 404, "Not Found")
            return
        }
        val headers = readRequestHeaders(reader)
        runCatching {
            if (route.hlsManifest) {
                serveHls(socket, route, method)
            } else if (route.dashManifest) {
                serveDash(socket, route, method)
            } else {
                serveBinary(socket, route, method, headers["range"])
            }
        }.onFailure {
            runCatching { writeEmptyResponse(socket, 502, "Bad Gateway") }
        }
    }

    private fun findRoute(routeId: String): Route? =
        synchronized(routesLock) {
            routes.remove(routeId)?.also { route -> routes[routeId] = route }
        }

    private fun Route.resolveDashTemplate(pathSuffix: String): Route? {
        val templateRoute = dashTemplate ?: return this
        val coordinatePattern =
            when {
                templateRoute.usesNumber && templateRoute.usesTime -> "(\\d+)-(\\d+)"
                else -> "(\\d+)"
            }
        val match =
            Regex("^dash-$coordinatePattern\\.${Regex.escape(templateRoute.localExtension)}$")
                .matchEntire(pathSuffix)
                ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val number = if (templateRoute.usesNumber) first else templateRoute.representation.segmentTemplate?.startNumber ?: 1L
        val time =
            when {
                templateRoute.usesNumber && templateRoute.usesTime -> match.groupValues[2].toLongOrNull()
                templateRoute.usesTime -> first
                else -> null
            }
        val resolvedUri =
            renderDashTemplate(
                template = templateRoute.upstreamTemplate,
                representation = templateRoute.representation,
                number = number,
                time = time,
            )
        return copy(
            upstreamUri = resolvedUri,
            cacheIdentity = cacheIdentity?.forAdaptiveResource(resolvedUri),
            dashTemplate = null,
        )
    }

    private fun readRequestHeaders(reader: BufferedReader): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        repeat(MAX_REQUEST_HEADER_COUNT) {
            val line = reader.readLine() ?: return headers
            if (line.isEmpty()) return headers
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim().take(MAX_REQUEST_HEADER_BYTES)
            }
        }
        return headers
    }

    private fun serveHls(
        socket: Socket,
        route: Route,
        method: String,
    ) {
        val rootText = loadBounded(route.upstreamUri, MAX_HLS_MANIFEST_BYTES).decodeToString()
        require(!rootText.hasHlsSessionKey()) { "HLS session keys require the native DRM route" }
        val root = parseYHlsPlaylist(rootText, route.upstreamUri)
        val (mediaText, mediaUri, media) =
            when (root) {
                is YHlsPlaylist.Media -> Triple(rootText, route.upstreamUri, root)
                is YHlsPlaylist.Master -> {
                    require(!rootText.hasSeparateHlsRenditions()) {
                        "Separate HLS rendition groups are not executable yet"
                    }
                    val selected =
                        YAdaptiveVariantSelector.select(
                            variants = root.variants,
                            conditions =
                                YAdaptiveSelectionConditions(
                                    estimatedBandwidthBitsPerSecond = INITIAL_BANDWIDTH_BITS_PER_SECOND,
                                    bufferedDurationUs = STARTUP_BUFFER_US,
                                    maximumWidth = route.maximumWidth,
                                    maximumHeight = route.maximumHeight,
                                ),
                        )
                    val selectedText = loadBounded(selected.uri, MAX_HLS_MANIFEST_BYTES).decodeToString()
                    require(!selectedText.hasHlsSessionKey()) { "HLS session keys require the native DRM route" }
                    val selectedMedia = parseYHlsPlaylist(selectedText, selected.uri) as? YHlsPlaylist.Media
                    requireNotNull(selectedMedia) { "Nested HLS master playlists are not executable" }
                    Triple(selectedText, selected.uri, selectedMedia)
                }
            }
        require(
            media.segments.none { segment ->
                segment.encryption?.method in
                    setOf(YAdaptiveEncryptionMethod.SampleAes, YAdaptiveEncryptionMethod.Other)
            },
        ) { "HLS sample encryption requires the native DRM route" }
        val rewritten =
            rewriteYHlsResourceUris(mediaText, mediaUri) { upstreamUri, kind ->
                val persistent =
                    route.cacheable &&
                        !media.isLive &&
                        kind in setOf(YHlsResourceKind.MediaSegment, YHlsResourceKind.InitializationSegment)
                localUrl(
                    upstreamUri = upstreamUri,
                    cacheable = persistent,
                    cacheIdentity = route.cacheIdentity?.forAdaptiveResource(upstreamUri),
                    maximumWidth = route.maximumWidth,
                    maximumHeight = route.maximumHeight,
                    hlsManifest =
                        kind in
                            setOf(YHlsResourceKind.VariantPlaylist, YHlsResourceKind.RenditionPlaylist),
                )
            }.encodeToByteArray()
        writeHeaders(
            socket = socket,
            status = 200,
            reason = "OK",
            contentType = HLS_CONTENT_TYPE,
            contentLength = rewritten.size.toLong(),
        )
        if (method == "GET") socket.getOutputStream().write(rewritten)
        socket.getOutputStream().flush()
    }

    private fun serveDash(
        socket: Socket,
        route: Route,
        method: String,
    ) {
        val sourceXml = loadBounded(route.upstreamUri, MAX_DASH_MANIFEST_BYTES).decodeToString()
        require(DASH_PERIOD_TAG.findAll(sourceXml).count() == 1) {
            "Multi-period DASH requires the period controller"
        }
        val manifest = parseYDashManifest(sourceXml, route.upstreamUri)
        val selection =
            selectYDashPlaybackRepresentations(
                manifest = manifest,
                conditions =
                    YAdaptiveSelectionConditions(
                        estimatedBandwidthBitsPerSecond = INITIAL_BANDWIDTH_BITS_PER_SECOND,
                        bufferedDurationUs = STARTUP_BUFFER_US,
                        maximumWidth = route.maximumWidth,
                        maximumHeight = route.maximumHeight,
                    ),
            )
        val rewritten =
            buildYDashPlaybackManifest(
                manifest = manifest,
                selection = selection,
                allowContentProtection = route.drmProtected,
            ) { representation, template, kind ->
                when (kind) {
                    YDashResourceKind.Initialization -> {
                        val timelineStart =
                            representation.segmentTemplate
                                ?.timeline
                                ?.firstOrNull()
                                ?.startTime ?: 0L
                        val upstreamUri =
                            renderDashTemplate(
                                template = template,
                                representation = representation,
                                number = representation.segmentTemplate?.startNumber ?: 1L,
                                time = timelineStart,
                            )
                        localUrl(
                            upstreamUri = upstreamUri,
                            cacheable = route.cacheable,
                            cacheIdentity = route.cacheIdentity?.forAdaptiveResource(upstreamUri),
                            hlsManifest = false,
                            dashManifest = false,
                        )
                    }
                    YDashResourceKind.MediaTemplate ->
                        localDashTemplate(
                            route = route,
                            representation = representation,
                            upstreamTemplate = template,
                        )
                }
            }.encodeToByteArray()
        writeHeaders(
            socket = socket,
            status = 200,
            reason = "OK",
            contentType = DASH_CONTENT_TYPE,
            contentLength = rewritten.size.toLong(),
        )
        if (method == "GET") socket.getOutputStream().write(rewritten)
        socket.getOutputStream().flush()
    }

    private fun localDashTemplate(
        route: Route,
        representation: YDashRepresentation,
        upstreamTemplate: String,
    ): String {
        val usesNumber = DASH_NUMBER_TOKEN.containsMatchIn(upstreamTemplate)
        val usesTime = DASH_TIME_TOKEN.containsMatchIn(upstreamTemplate)
        require(usesNumber || usesTime) { "DASH media template has no segment coordinate" }
        val extension = upstreamTemplate.safeTemplateExtension() ?: "m4s"
        val localTemplate =
            when {
                usesNumber && usesTime -> "dash-\$Number\$-\$Time\$.$extension"
                usesNumber -> "dash-\$Number\$.$extension"
                else -> "dash-\$Time\$.$extension"
            }
        val templateRoute =
            route.copy(
                upstreamUri = representation.baseUri,
                cacheIdentity = route.cacheIdentity,
                hlsManifest = false,
                dashManifest = false,
                dashTemplate =
                    DashTemplateRoute(
                        representation = representation,
                        upstreamTemplate = upstreamTemplate,
                        usesNumber = usesNumber,
                        usesTime = usesTime,
                        localExtension = extension,
                    ),
            )
        return localRouteUrl(templateRoute, "/$localTemplate")
    }

    private fun serveBinary(
        socket: Socket,
        route: Route,
        method: String,
        rawRange: String?,
    ) {
        val requestedRange = parseYCoreHttpByteRange(rawRange)
        if (rawRange != null && requestedRange == null) {
            writeEmptyResponse(socket, 416, "Range Not Satisfiable")
            return
        }
        if (requestedRange == null && !route.cacheable) {
            serveSequential(socket, route, method)
            return
        }
        val source =
            AndroidTransportMediaDataSource(
                uri = route.upstreamUri,
                protocol = requireNotNull(route.upstreamUri.sourceProtocolOrNull()),
                headers = upstreamHeaders(),
                createTransport = createTransport,
                cacheDirectory = cacheDirectory.takeIf { route.cacheable },
                cacheIdentity = route.cacheIdentity.takeIf { route.cacheable },
                cacheMaximumBytes = cacheMaximumBytes.takeIf { route.cacheable } ?: 0L,
            )
        try {
            val totalLength = source.getSize()
            require(totalLength >= 0L) { "Upstream media length is unknown" }
            val start = requestedRange?.startInclusive ?: 0L
            if (start >= totalLength) {
                writeRangeNotSatisfiable(socket, totalLength)
                return
            }
            val endInclusive =
                minOf(
                    requestedRange?.endInclusive ?: (totalLength - 1L),
                    totalLength - 1L,
                )
            val contentLength = endInclusive - start + 1L
            writeHeaders(
                socket = socket,
                status = if (requestedRange == null) 200 else 206,
                reason = if (requestedRange == null) "OK" else "Partial Content",
                contentType = route.upstreamUri.guessContentType(),
                contentLength = contentLength,
                contentRange =
                    if (requestedRange == null) {
                        null
                    } else {
                        "bytes $start-$endInclusive/$totalLength"
                    },
            )
            if (method == "HEAD") return
            copySource(source, socket, start, contentLength)
        } finally {
            source.close()
        }
    }

    private fun serveSequential(
        socket: Socket,
        route: Route,
        method: String,
    ) = runBlocking {
        val transport = createTransport()
        try {
            val response =
                transport.open(
                    YMediaTransportRequest(
                        uri = route.upstreamUri,
                        protocol = requireNotNull(route.upstreamUri.sourceProtocolOrNull()),
                        headers = upstreamHeaders(),
                    ),
                )
            require(response.statusCode in 200..299) { "Upstream returned ${response.statusCode}" }
            writeHeaders(
                socket = socket,
                status = 200,
                reason = "OK",
                contentType = route.upstreamUri.guessContentType(),
                contentLength = response.contentLength,
            )
            if (method == "GET") {
                val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                while (true) {
                    val count = transport.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count > 0) socket.getOutputStream().write(buffer, 0, count)
                }
            }
            socket.getOutputStream().flush()
        } finally {
            transport.close()
        }
    }

    private fun loadBounded(
        upstreamUri: String,
        maximumBytes: Int,
    ): ByteArray =
        runBlocking {
            val transport = createTransport()
            try {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = upstreamUri,
                            protocol = requireNotNull(upstreamUri.sourceProtocolOrNull()),
                            headers = upstreamHeaders(),
                        ),
                    )
                require(response.statusCode in 200..299) { "Manifest returned ${response.statusCode}" }
                response.contentLength?.let { require(it <= maximumBytes) { "Manifest exceeds the byte limit" } }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(NETWORK_BUFFER_BYTES)
                while (true) {
                    val count = transport.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() <= maximumBytes - count) { "Manifest exceeds the byte limit" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                transport.close()
            }
        }

    private fun copySource(
        source: AndroidTransportMediaDataSource,
        socket: Socket,
        start: Long,
        contentLength: Long,
    ) {
        val output = socket.getOutputStream()
        val buffer = ByteArray(NETWORK_BUFFER_BYTES)
        var position = start
        var remaining = contentLength
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = source.readAt(position, buffer, 0, requested)
            require(count > 0) { "Cached source ended before the advertised response length" }
            output.write(buffer, 0, count)
            position += count
            remaining -= count
        }
        output.flush()
    }

    private fun upstreamHeaders(): Map<String, String> =
        userAgent
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { mapOf("User-Agent" to it) }
            .orEmpty()

    private fun writeHeaders(
        socket: Socket,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        contentRange: String? = null,
    ) {
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status $reason\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Content-Type: $contentType\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write("Accept-Ranges: bytes\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        contentRange?.let {
            output.write("Content-Range: $it\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        contentLength?.takeIf { it >= 0L }?.let {
            output.write("Content-Length: $it\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        }
        output.write("Connection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun writeRangeNotSatisfiable(
        socket: Socket,
        totalLength: Long,
    ) {
        val output = socket.getOutputStream()
        output.write(
            "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLength\r\n".toByteArray(
                StandardCharsets.ISO_8859_1,
            ),
        )
        output.write("Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun writeEmptyResponse(
        socket: Socket,
        status: Int,
        reason: String,
    ) {
        socket.getOutputStream().write(
            "HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(
                StandardCharsets.ISO_8859_1,
            ),
        )
        socket.getOutputStream().flush()
    }
}

internal data class YCoreHttpByteRange(
    val startInclusive: Long,
    val endInclusive: Long?,
)

internal fun parseYCoreHttpByteRange(value: String?): YCoreHttpByteRange? {
    val match = value?.trim()?.let(HTTP_BYTE_RANGE::matchEntire) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].takeIf(String::isNotEmpty)?.toLongOrNull()
    if (end != null && end < start) return null
    return YCoreHttpByteRange(startInclusive = start, endInclusive = end)
}

private fun String.sourceProtocolOrNull(): YSourceProtocol? =
    when (runCatching { URI(this).scheme?.lowercase() }.getOrNull()) {
        "http" -> YSourceProtocol.Http
        "https" -> YSourceProtocol.Https
        else -> null
    }

private fun String.isHlsManifestUri(): Boolean =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .lowercase()
            .endsWith(".m3u8")
    }.getOrDefault(false)

private fun String.isDashManifestUri(): Boolean =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .lowercase()
            .endsWith(".mpd")
    }.getOrDefault(false)

private fun String.hasSeparateHlsRenditions(): Boolean =
    lineSequence().any { line ->
        val normalized = line.trim().uppercase()
        normalized.startsWith("#EXT-X-MEDIA:") ||
            normalized.startsWith("#EXT-X-STREAM-INF:") &&
            ("AUDIO=" in normalized || "VIDEO=" in normalized || "SUBTITLES=" in normalized)
    }

private fun String.hasHlsSessionKey(): Boolean = lineSequence().any { line -> line.trim().uppercase().startsWith("#EXT-X-SESSION-KEY:") }

private fun String.guessContentType(): String =
    when (
        runCatching {
            URI(this)
                .path
                .orEmpty()
                .substringAfterLast('.')
                .lowercase()
        }.getOrDefault("")
    ) {
        "m3u8" -> HLS_CONTENT_TYPE
        "mpd" -> DASH_CONTENT_TYPE
        "ts" -> "video/mp2t"
        "mp4", "m4s", "m4v" -> "video/mp4"
        "aac" -> "audio/aac"
        "vtt" -> "text/vtt"
        else -> "application/octet-stream"
    }

private fun String.safeSyntheticExtension(): String? =
    runCatching {
        URI(this)
            .path
            .orEmpty()
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
    }.getOrNull()
        ?.takeIf(SAFE_SYNTHETIC_EXTENSIONS::contains)

private fun String.safeTemplateExtension(): String? =
    substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .takeIf(SAFE_SYNTHETIC_EXTENSIONS::contains)

private fun YCacheIdentity.forAdaptiveResource(upstreamUri: String): YCacheIdentity =
    copy(
        version =
            buildString {
                append(version)
                append(':')
                append(upstreamUri.sha256())
            },
    )

private fun String.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private const val LOOPBACK_HOST = "127.0.0.1"
private const val LOOPBACK_BACKLOG = 8
private const val ROUTE_PREFIX = "ycore-resource"
private const val CLIENT_SOCKET_TIMEOUT_MS = 30_000
private const val WORKER_SHUTDOWN_TIMEOUT_MS = 2_000L
private const val NETWORK_BUFFER_BYTES = 64 * 1024
private const val MAX_HLS_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_DASH_MANIFEST_BYTES = 8 * 1024 * 1024
private const val MAX_ROUTES = 50_000
private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
private const val MAX_REQUEST_HEADER_BYTES = 8 * 1024
private const val MAX_REQUEST_HEADER_COUNT = 64
private const val INITIAL_BANDWIDTH_BITS_PER_SECOND = 25_000_000L
private const val STARTUP_BUFFER_US = 10_000_000L
private const val HLS_CONTENT_TYPE = "application/vnd.apple.mpegurl"
private const val DASH_CONTENT_TYPE = "application/dash+xml"
private val ALLOWED_METHODS = setOf("GET", "HEAD")
private val HTTP_BYTE_RANGE = Regex("^bytes=(\\d+)-(\\d*)$", RegexOption.IGNORE_CASE)
private val DASH_PERIOD_TAG = Regex("<\\s*(?:[A-Za-z0-9_.-]+:)?Period(?:\\s|>)", RegexOption.IGNORE_CASE)
private val DASH_NUMBER_TOKEN = Regex("\\\$Number(?:%0\\d+d)?\\\$")
private val DASH_TIME_TOKEN = Regex("\\\$Time(?:%0\\d+d)?\\\$")
private val SAFE_SYNTHETIC_EXTENSIONS =
    setOf("aac", "bin", "key", "m3u8", "m4s", "m4v", "mkv", "mov", "mp4", "mpd", "m2ts", "mts", "ts", "vtt", "webm")
