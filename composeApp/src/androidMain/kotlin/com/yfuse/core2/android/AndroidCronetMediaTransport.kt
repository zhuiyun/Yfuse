package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** HTTP/2 + HTTP/3/QUIC streaming transport. Cronet negotiates down to HTTP/2/1.1 when required. */
internal class AndroidCronetMediaTransport(
    context: Context,
    private val engine: CronetEngine =
        CronetEngine
            .Builder(context.applicationContext)
            .enableHttp2(true)
            .enableQuic(true)
            .build(),
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

    private val callbackExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "YCore-Cronet").apply { isDaemon = true }
        }
    private var chunks = ArrayBlockingQueue<CronetChunk>(CRONET_CHUNK_QUEUE_CAPACITY)
    private var request: UrlRequest? = null
    private var activeChunk: ByteArray? = null
    private var activeOffset = 0
    private var streamEnded = false
    private var streamFailure: Throwable? = null

    @Volatile private var callbackFailure: Throwable? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol in supportedProtocols)
            closeRequest()
            val requestChunks = ArrayBlockingQueue<CronetChunk>(CRONET_CHUNK_QUEUE_CAPACITY)
            chunks = requestChunks
            val responseReady = CountDownLatch(1)
            var responseInfo: UrlResponseInfo? = null
            val callback =
                object : UrlRequest.Callback() {
                    override fun onRedirectReceived(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                        newLocationUrl: String,
                    ) {
                        if (this@AndroidCronetMediaTransport.request !== request) return
                        callbackFailure = IllegalStateException("HTTP redirect requires provider re-resolution")
                        request.cancel()
                        responseReady.countDown()
                        requestChunks.offer(CronetChunk.Failed)
                    }

                    override fun onResponseStarted(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                    ) {
                        if (this@AndroidCronetMediaTransport.request !== request) return
                        responseInfo = info
                        responseReady.countDown()
                        request.read(ByteBuffer.allocateDirect(CRONET_READ_BYTES))
                    }

                    override fun onReadCompleted(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                        byteBuffer: ByteBuffer,
                    ) {
                        if (this@AndroidCronetMediaTransport.request !== request) return
                        byteBuffer.flip()
                        val bytes = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(bytes)
                        if (!requestChunks.offerChunk(CronetChunk.Data(bytes))) {
                            if (this@AndroidCronetMediaTransport.request === request) {
                                callbackFailure = SocketTimeoutException("Cronet callback queue timed out")
                            }
                            request.cancel()
                            requestChunks.offer(CronetChunk.Failed)
                            return
                        }
                        byteBuffer.clear()
                        request.read(byteBuffer)
                    }

                    override fun onSucceeded(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                    ) {
                        if (this@AndroidCronetMediaTransport.request !== request) return
                        if (!requestChunks.offerChunk(CronetChunk.End)) {
                            if (this@AndroidCronetMediaTransport.request === request) {
                                callbackFailure = SocketTimeoutException("Cronet callback queue timed out")
                            }
                            requestChunks.offer(CronetChunk.Failed)
                        }
                    }

                    override fun onFailed(
                        request: UrlRequest,
                        info: UrlResponseInfo?,
                        error: CronetException,
                    ) {
                        if (this@AndroidCronetMediaTransport.request !== request) return
                        if (callbackFailure == null) callbackFailure = error
                        responseReady.countDown()
                        requestChunks.offer(CronetChunk.Failed)
                    }
                }
            val builder = engine.newUrlRequestBuilder(request.uri, callback, callbackExecutor)
            builder.addHeader("Accept-Encoding", "identity")
            builder.addHeader("Cache-Control", "no-transform")
            request.headers.forEach { (name, value) ->
                require(name.isSafeCronetHeader() && value.isSafeCronetHeader())
                builder.addHeader(name, value)
            }
            request.range?.let { builder.addHeader("Range", "bytes=${it.startInclusive}-${it.endInclusive ?: ""}") }
            val opened = builder.build()
            this@AndroidCronetMediaTransport.request = opened
            opened.start()
            if (!responseReady.await(CRONET_OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                val timeout = SocketTimeoutException("Cronet response timed out")
                callbackFailure = timeout
                opened.cancel()
                throw timeout
            }
            callbackFailure?.let { throw IllegalStateException("Cronet request failed", it) }
            val info = checkNotNull(responseInfo) { "Cronet response metadata is unavailable" }
            val contentRange = info.headerValue("Content-Range")?.let(::parseContentRange)
            val protocol = info.negotiatedProtocol.lowercase()
            YMediaTransportResponse(
                statusCode = info.httpStatusCode,
                contentLength =
                    contentRange?.total
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

                streamFailure?.let { failure ->
                    if (outputOffset > offset) return@withContext outputOffset - offset
                    throw IllegalStateException("Cronet streaming read failed", failure)
                }
                if (streamEnded) {
                    return@withContext if (outputOffset == offset) -1 else outputOffset - offset
                }
                val callbackError = callbackFailure
                if (callbackError != null) {
                    streamFailure = callbackError
                    continue
                }

                when (
                    val next =
                        chunks.poll(
                            CRONET_BODY_READ_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS,
                        )
                ) {
                    is CronetChunk.Data -> {
                        activeChunk = next.bytes
                        activeOffset = 0
                    }
                    CronetChunk.End -> streamEnded = true
                    CronetChunk.Failed ->
                        streamFailure =
                            callbackFailure
                                ?: IllegalStateException("Cronet streaming read failed")
                    null -> {
                        val timeout = SocketTimeoutException("Cronet streaming body timed out")
                        callbackFailure = timeout
                        streamFailure = timeout
                        request?.cancel()
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
        streamEnded = false
        streamFailure = null
        callbackFailure = null
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

private fun ArrayBlockingQueue<CronetChunk>.offerChunk(chunk: CronetChunk): Boolean =
    offer(chunk, CRONET_CALLBACK_QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

private const val CRONET_CHUNK_QUEUE_CAPACITY = 2
private const val CRONET_READ_BYTES = 256 * 1024
private const val CRONET_OPEN_TIMEOUT_SECONDS = 4L
private const val CRONET_BODY_READ_TIMEOUT_SECONDS = 4L
private const val CRONET_CALLBACK_QUEUE_TIMEOUT_SECONDS = 4L
