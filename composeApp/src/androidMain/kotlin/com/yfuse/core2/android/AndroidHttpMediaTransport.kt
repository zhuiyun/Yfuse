package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream

/** Stateful random-access HTTP/WebDAV transport. A seek is expressed by closing and reopening it. */
internal class AndroidHttpMediaTransport(
    private val client: OkHttpClient = sharedMediaTransportClient,
    private val followSafeRedirects: Boolean = false,
) : YMediaTransport {
    override val supportedProtocols: Set<YSourceProtocol> =
        setOf(YSourceProtocol.Http, YSourceProtocol.Https, YSourceProtocol.WebDav, YSourceProtocol.WebDavTls)
    override val features: Set<YTransportFeature> =
        setOf(
            YTransportFeature.ByteRange,
            YTransportFeature.Http2,
            YTransportFeature.ConnectionReuse,
            YTransportFeature.RandomAccess,
        )

    private var response: Response? = null
    private var input: InputStream? = null
    private val activeClient =
        if (followSafeRedirects) {
            client
                .newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } else {
            client
        }

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol in supportedProtocols) { "Unsupported HTTP transport protocol" }
            if (followSafeRedirects) {
                require(request.headers.keys.none(String::isCredentialHeader)) {
                    "Redirectable media transport cannot carry provider credentials"
                }
            }
            closeCurrent()
            val builder =
                Request
                    .Builder()
                    .url(request.uri)
                    .header("Accept-Encoding", "identity")
                    .header("Cache-Control", "no-transform")
            when (request.method) {
                YTransportMethod.Get -> builder.get()
                YTransportMethod.Post -> builder.post((request.body ?: ByteArray(0)).toRequestBody())
            }
            request.headers.forEach { (name, value) ->
                require(name.isSafeTransportHeader() && value.isSafeTransportHeader()) {
                    "Unsafe transport header"
                }
                builder.header(name, value)
            }
            request.range?.let { range -> builder.header("Range", range.toHttpRange()) }
            val opened = activeClient.newCall(builder.build()).execute()
            response = opened
            input = opened.body?.byteStream()
            val acceptedRange =
                parseContentRange(opened.header("Content-Range"))?.let {
                    YByteRange(it.start, it.end)
                }
            YMediaTransportResponse(
                statusCode = opened.code,
                contentLength =
                    parseContentRange(opened.header("Content-Range"))?.total
                        ?: opened.body?.contentLength()?.takeIf { it >= 0L },
                acceptedRange = acceptedRange,
                features =
                    buildSet {
                        add(YTransportFeature.ByteRange)
                        add(YTransportFeature.ConnectionReuse)
                        add(YTransportFeature.RandomAccess)
                        if (opened.protocol == Protocol.HTTP_2 || opened.protocol == Protocol.H2_PRIOR_KNOWLEDGE) {
                            add(YTransportFeature.Http2)
                        }
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
            input?.read(destination, offset, length) ?: -1
        }

    override suspend fun close() {
        withContext(Dispatchers.IO) { closeCurrent() }
    }

    private fun closeCurrent() {
        runCatching { input?.close() }
        runCatching { response?.close() }
        input = null
        response = null
    }
}

private fun YByteRange.toHttpRange(): String = "bytes=$startInclusive-${endInclusive ?: ""}"

private fun String.isSafeTransportHeader(): Boolean = isNotBlank() && none { it == '\r' || it == '\n' || it == ':' }

private fun String.isCredentialHeader(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "authorization" ||
        normalized == "proxy-authorization" ||
        normalized == "cookie" ||
        normalized == "cookie2" ||
        normalized.contains("auth") ||
        normalized.contains("token") ||
        normalized.contains("api-key") ||
        normalized.contains("apikey")
}

private val sharedMediaTransportClient =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
