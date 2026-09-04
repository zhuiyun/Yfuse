package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HTTP/2 + HTTP/3/QUIC streaming transport. Cronet negotiates down to HTTP/2/1.1 when required. */
internal class AndroidCronetMediaTransport(
    context: Context,
    private val engine: CronetEngine = AndroidCronetRuntime.engine(context.applicationContext),
    private val callbackExecutor: ExecutorService = AndroidCronetRuntime.callbackExecutor,
    private val followMediaRedirects: Boolean = false,
    private val allowCrossProtocolRedirects: Boolean = false,
    private val redirectState: AndroidHttpMediaRedirectState? = null,
) : YMediaTransport {
    override val supportedProtocols: Set<YSourceProtocol> =
        setOf(YSourceProtocol.Http, YSourceProtocol.Https, YSourceProtocol.WebDav, YSourceProtocol.WebDavTls)
    override val features: Set<YTransportFeature> =
        setOf(
            YTransportFeature.ByteRange,
            YTransportFeature.Http2,
            YTransportFeature.Http3,
            YTransportFeature.ConnectionReuse,
            YTransportFeature.RandomAccess,
        )

    private val chunks = ArrayBlockingQueue<CronetChunk>(2)
    private var request: UrlRequest? = null
    private var activeChunk: ByteArray? = null
    private var activeOffset = 0
    private var endOfStream = false

    @Volatile private var callbackFailure: Throwable? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol in supportedProtocols)
            closeRequest()
            chunks.clear()
            callbackFailure = null
            activeChunk = null
            activeOffset = 0
            endOfStream = false
            val originalUri = request.uri
            val originalHeaders = request.headers.withHttpBasicCredentials(request.credentials)
            val cachedRoute = redirectState?.resolve(originalUri)
            val targetUri = cachedRoute?.targetUri ?: originalUri
            val requestHeaders = originalHeaders.withoutCredentials(cachedRoute?.stripCredentials == true)
            val requestMethod = request.method
            val responseReady = CountDownLatch(1)
            var responseInfo: UrlResponseInfo? = null
            var activeUrl = targetUri.toHttpUrlOrNull()
            var redirectCount = 0
            var cleartextRedirect = false
            var strippedCredentials = cachedRoute?.stripCredentials == true
            val callback =
                object : UrlRequest.Callback() {
                    override fun onRedirectReceived(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                        newLocationUrl: String,
                    ) {
                        val previous = activeUrl
                        val target = newLocationUrl.toHttpUrlOrNull()
                        val redirectsToCleartext = previous?.isHttps == true && target?.isHttps == false
                        val crossesOrigin =
                            previous == null ||
                                target == null ||
                                !previous.hasSameOrigin(target)
                        val carriesCredentials =
                            requestHeaders.keys.any(String::isCredentialHeader)
                        val canFollow =
                            followMediaRedirects &&
                                requestMethod == YTransportMethod.Get &&
                                redirectCount < MAX_SAFE_MEDIA_REDIRECTS &&
                                target != null &&
                                (!redirectsToCleartext || allowCrossProtocolRedirects) &&
                                (!crossesOrigin || !carriesCredentials)
                        if (!canFollow) {
                            callbackFailure =
                                IOException("Cronet media redirect requires OkHttp policy fallback")
                            request.cancel()
                            responseReady.countDown()
                            chunks.offer(CronetChunk.Failed)
                            return
                        }
                        redirectCount += 1
                        cleartextRedirect = cleartextRedirect || redirectsToCleartext
                        strippedCredentials = strippedCredentials || crossesOrigin
                        activeUrl = target
                        runCatching(request::followRedirect).onFailure { failure ->
                            callbackFailure = failure
                            request.cancel()
                            responseReady.countDown()
                            chunks.offer(CronetChunk.Failed)
                        }
                    }

                    override fun onResponseStarted(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                    ) {
                        responseInfo = info
                        responseReady.countDown()
                        request.read(ByteBuffer.allocateDirect(CRONET_READ_BYTES))
                    }

                    override fun onReadCompleted(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                        byteBuffer: ByteBuffer,
                    ) {
                        byteBuffer.flip()
                        val bytes = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(bytes)
                        chunks.put(CronetChunk.Data(bytes))
                        byteBuffer.clear()
                        request.read(byteBuffer)
                    }

                    override fun onSucceeded(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                    ) {
                        chunks.put(CronetChunk.End)
                    }

                    override fun onFailed(
                        request: UrlRequest,
                        info: UrlResponseInfo?,
                        error: CronetException,
                    ) {
                        callbackFailure = error
                        responseReady.countDown()
                        chunks.offer(CronetChunk.Failed)
                    }
                }
            val builder = engine.newUrlRequestBuilder(targetUri, callback, callbackExecutor)
            builder.addHeader("Accept-Encoding", "identity")
            builder.addHeader("Cache-Control", "no-transform")
            requestHeaders.forEach { (name, value) ->
                require(name.isSafeCronetHeader() && value.isSafeCronetHeader())
                builder.addHeader(name, value)
            }
            request.range?.let { builder.addHeader("Range", "bytes=${it.startInclusive}-${it.endInclusive ?: ""}") }
            val opened = builder.build()
            this@AndroidCronetMediaTransport.request = opened
            opened.start()
            if (!responseReady.await(CRONET_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                closeRequest()
                throw IOException("Cronet response timed out")
            }
            callbackFailure?.let { failure ->
                closeRequest()
                throw IOException("Cronet request failed", failure)
            }
            val info =
                responseInfo ?: run {
                    closeRequest()
                    throw IOException("Cronet response metadata is unavailable")
                }
            val rawContentRange = info.headerValue("Content-Range")
            val contentRange = rawContentRange?.let(::parseContentRange)
            val protocol = info.negotiatedProtocol.lowercase()
            val finalUri = activeUrl?.toString()
            if (info.httpStatusCode in 200..299 && finalUri != null && finalUri != originalUri) {
                redirectState?.remember(
                    sourceUri = originalUri,
                    targetUri = finalUri,
                    stripCredentials = strippedCredentials,
                )
            }
            YMediaTransportResponse(
                statusCode = info.httpStatusCode,
                contentLength =
                    contentRange?.total
                        ?: parseUnsatisfiedContentRangeLength(rawContentRange)
                        ?: info.headerValue("Content-Length")?.toLongOrNull(),
                acceptedRange = contentRange?.let { YByteRange(it.start, it.end) },
                features =
                    buildSet {
                        add(YTransportFeature.ByteRange)
                        add(YTransportFeature.ConnectionReuse)
                        add(YTransportFeature.RandomAccess)
                        if (protocol == "h2") add(YTransportFeature.Http2)
                        if (protocol.startsWith("h3") || "quic" in protocol) add(YTransportFeature.Http3)
                    },
                implementation = "Cronet",
                negotiatedProtocol = protocol,
                redirectCount = redirectCount,
                finalProtocol =
                    if (activeUrl?.isHttps == true) {
                        YSourceProtocol.Https
                    } else {
                        YSourceProtocol.Http
                    },
                cleartextRedirect = cleartextRedirect,
            )
        }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        withContext(Dispatchers.IO) {
            require(offset >= 0 && length >= 0 && offset + length <= destination.size)
            if (length == 0) return@withContext 0
            if (endOfStream) return@withContext -1
            var outputOffset = offset
            var remaining = length
            while (remaining > 0) {
                val current = activeChunk
                if (current != null && activeOffset < current.size) {
                    val count = minOf(remaining, current.size - activeOffset)
                    current.copyInto(destination, outputOffset, activeOffset, activeOffset + count)
                    activeOffset += count
                    outputOffset += count
                    remaining -= count
                    if (activeOffset == current.size) {
                        activeChunk = null
                        activeOffset = 0
                    }
                    continue
                }
                val next = chunks.poll(CRONET_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (next == null) {
                    closeRequest()
                    throw IOException("Cronet streaming read timed out")
                }
                when (next) {
                    is CronetChunk.Data -> {
                        activeChunk = next.bytes
                        activeOffset = 0
                    }
                    CronetChunk.End -> {
                        endOfStream = true
                        return@withContext if (outputOffset == offset) -1 else outputOffset - offset
                    }
                    CronetChunk.Failed -> {
                        val failure = callbackFailure
                        closeRequest()
                        throw IOException("Cronet streaming read failed", failure)
                    }
                }
            }
            outputOffset - offset
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) { closeRequest() }
    }

    private fun closeRequest() {
        request?.cancel()
        request = null
        chunks.clear()
        activeChunk = null
        activeOffset = 0
        endOfStream = false
    }
}

/** Process-wide Cronet runtime so parallel range prefetch shares one HTTP/2/HTTP/3 connection pool. */
private object AndroidCronetRuntime {
    val callbackExecutor: ExecutorService =
        Executors.newFixedThreadPool(CRONET_CALLBACK_THREADS) { task ->
            Thread(task, "YCore-Cronet").apply { isDaemon = true }
        }

    @Volatile
    private var sharedEngine: CronetEngine? = null

    fun engine(context: Context): CronetEngine =
        sharedEngine ?: synchronized(this) {
            sharedEngine
                ?: CronetEngine
                    .Builder(context.applicationContext)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .build()
                    .also { sharedEngine = it }
        }
}

private sealed interface CronetChunk {
    data class Data(
        val bytes: ByteArray,
    ) : CronetChunk

    data object End : CronetChunk

    data object Failed : CronetChunk
}

private fun UrlResponseInfo.headerValue(name: String): String? =
    allHeadersAsList.lastOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun String.isSafeCronetHeader(): Boolean = isNotBlank() && '\r' !in this && '\n' !in this

private const val CRONET_READ_BYTES = 256 * 1024
private const val CRONET_CALLBACK_THREADS = 4

/**
 * Opening a range is a handshake plus response headers, not a body transfer, so it must not share
 * the streaming budget. A Cloudflare-tunnelled origin that cannot answer QUIC leaves the latch
 * un-counted for the whole timeout, and because [AndroidAdaptiveHttpMediaTransport] probes Cronet
 * before OkHttp, every one of those seconds is paid before the first frame can render.
 *
 * Real successful opens in the field land at 1.6-3.0 s, so this leaves roughly a 1.7x margin. An
 * origin wrongly given up on still plays through the OkHttp fallback that opens in one to two
 * seconds; an origin waited on too long shows the user a black screen instead.
 */
private const val CRONET_OPEN_TIMEOUT_MS = 5_000L
private const val CRONET_READ_TIMEOUT_SECONDS = NATIVE_MEDIA_TRANSPORT_TIMEOUT_SECONDS
