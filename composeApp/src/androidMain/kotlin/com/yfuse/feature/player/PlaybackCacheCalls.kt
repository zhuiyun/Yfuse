package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackProxyRequest
import com.yfuse.core2.android.YHttpContentRange
import com.yfuse.core2.android.parseContentRange
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** A cached playback GET can recover one stalled body read without discarding its delivered prefix. */
internal class PlaybackCacheCalls(
    client: OkHttpClient,
) {
    private val resumeClient =
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val http1ResumeClient by lazy {
        resumeClient
            .newBuilder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build()
    }
    private val initialClient =
        client
            .newBuilder()
            .apply {
                interceptors().add(0) { chain ->
                    val response = chain.proceed(chain.request())
                    val window = response.resumableWindow()
                    val tag = response.header("ETag")
                    if (window == null || tag == null || !isStrongPlaybackEntityTag(tag)) {
                        response
                    } else {
                        val owner = requireNotNull(chain.request().tag(PlaybackProxyRequest::class.java))
                        val source =
                            PlaybackResponseSource(response, window, tag, recoveryClient(response), owner, chain.call())
                        response
                            .newBuilder()
                            .body(
                                source.buffer().asResponseBody(
                                    response.body.contentType(),
                                    response.body.contentLength(),
                                ),
                            ).build()
                    }
                }
            }.build()

    private fun recoveryClient(response: Response): OkHttpClient =
        if (response.protocol == Protocol.HTTP_2 &&
            response.request.url.isHttps &&
            Protocol.HTTP_1_1 in resumeClient.protocols
        ) {
            http1ResumeClient
        } else {
            resumeClient
        }

    fun forRequest(owner: PlaybackProxyRequest): Call.Factory =
        Call.Factory { request ->
            owner.ensureOpen()
            initialClient
                .newCall(request.newBuilder().tag(PlaybackProxyRequest::class.java, owner).build())
                .also { owner.attachUpstreamCancellation(it::cancel) }
        }
}

private fun Response.resumableWindow(): YHttpContentRange? {
    val encoding = networkResponse?.header("Content-Encoding") ?: header("Content-Encoding")
    if (request.method != "GET" || (encoding != null && !encoding.equals("identity", true))) return null
    val length = body.contentLength()
    return when (code) {
        206 ->
            parseContentRange(header("Content-Range"))?.takeIf {
                it.total != null && (length == -1L || length == it.end - it.start + 1L)
            }
        200 ->
            if (length > 0 &&
                header("Accept-Ranges").equals("bytes", true)
            ) {
                YHttpContentRange(0, length - 1, length)
            } else {
                null
            }
        else -> null
    }
}

private class PlaybackResponseSource(
    private var response: Response,
    private val window: YHttpContentRange,
    private val entityTag: String,
    private val client: Call.Factory,
    private val owner: PlaybackProxyRequest,
    private val originalCall: Call,
) : Source {
    private var nextByte = window.start
    private var retried = false
    private val staging = Buffer()

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (nextByte > window.end) return -1
        val count = minOf(byteCount, window.end - nextByte + 1)
        val read =
            try {
                response.body.source().read(staging, count)
            } catch (timeout: SocketTimeoutException) {
                if (retried || originalCall.isCanceled()) throw timeout
                retried = true
                // A failing source can append bytes before throwing. The caller has not received them.
                staging.clear()
                resume()
                response.body.source().read(staging, count)
            }
        if (read > 0) {
            sink.write(staging, read)
            nextByte += read
        }
        return read
    }

    private fun resume() {
        owner.ensureOpen()
        val initial = response.request
        response.close()
        val request =
            initial
                .newBuilder()
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$nextByte-${window.end}")
                .header("If-Range", entityTag)
                .build()
        val call = client.newCall(request)
        owner.attachUpstreamCancellation(call::cancel)
        val resumed = call.execute()
        try {
            owner.ensureOpen()
            val accepted = resumed.resumableWindow()
            if (resumed.code != 206 ||
                resumed.request.url != initial.url ||
                resumed.header("ETag") != entityTag ||
                accepted != window.copy(start = nextByte)
            ) {
                throw IOException("Playback source changed or returned a different interval after the read timeout")
            }
            response = resumed
        } catch (failure: Exception) {
            resumed.close()
            throw failure
        }
    }

    override fun timeout(): Timeout = response.body.source().timeout()

    override fun close() = response.close()
}
