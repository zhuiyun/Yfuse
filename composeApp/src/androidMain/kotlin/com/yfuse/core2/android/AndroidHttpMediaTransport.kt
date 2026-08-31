package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Credentials
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
        client
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol in supportedProtocols) { "Unsupported HTTP transport protocol" }
            closeCurrent()
            var targetUri = request.uri
            var activeHeaders = request.headers.withHttpBasicCredentials(request.credentials)
            var redirectCount = 0
            var opened: Response? = null
            while (true) {
                val builder =
                    Request
                        .Builder()
                        .url(targetUri)
                        .header("Accept-Encoding", "identity")
                        .header("Cache-Control", "no-transform")
                when (request.method) {
                    YTransportMethod.Get -> builder.get()
                    YTransportMethod.Post -> builder.post((request.body ?: ByteArray(0)).toRequestBody())
                }
                activeHeaders.forEach { (name, value) ->
                    require(name.isSafeTransportHeader() && value.isSafeTransportHeader()) {
                        "Unsafe transport header"
                    }
                    builder.header(name, value)
                }
                request.range?.let { range -> builder.header("Range", range.toHttpRange()) }
                val candidate = activeClient.newCall(builder.build()).execute()
                val redirectTarget =
                    if (followSafeRedirects && request.method == YTransportMethod.Get) {
                        candidate.safeMediaRedirectTarget()
                    } else {
                        null
                    }
                if (redirectTarget == null) {
                    opened = candidate
                    break
                }
                if (redirectCount >= MAX_SAFE_MEDIA_REDIRECTS) {
                    candidate.close()
                    error("Too many media redirects")
                }
                val previous = candidate.request.url
                if (previous.scheme == "https" && redirectTarget.scheme != "https") {
                    candidate.close()
                    error("Secure media redirect cannot downgrade to HTTP")
                }
                redirectCount += 1
                if (!previous.hasSameOrigin(redirectTarget)) {
                    activeHeaders = activeHeaders.filterKeys { !it.isCredentialHeader() }
                }
                targetUri = redirectTarget.toString()
                candidate.close()
            }
            val finalResponse = checkNotNull(opened)
            response = finalResponse
            input = finalResponse.body?.byteStream()
            val acceptedRange =
                parseContentRange(finalResponse.header("Content-Range"))?.let {
                    YByteRange(it.start, it.end)
                }
            YMediaTransportResponse(
                statusCode = finalResponse.code,
                contentLength =
                    parseContentRange(finalResponse.header("Content-Range"))?.total
                        ?: parseUnsatisfiedContentRangeLength(finalResponse.header("Content-Range"))
                        ?: finalResponse.body?.contentLength()?.takeIf { it >= 0L },
                acceptedRange = acceptedRange,
                features =
                    buildSet {
                        add(YTransportFeature.ByteRange)
                        add(YTransportFeature.ConnectionReuse)
                        add(YTransportFeature.RandomAccess)
                        if (
                            finalResponse.protocol == Protocol.HTTP_2 ||
                            finalResponse.protocol == Protocol.H2_PRIOR_KNOWLEDGE
                        ) {
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

private fun Response.safeMediaRedirectTarget(): HttpUrl? {
    if (code !in SAFE_MEDIA_REDIRECT_CODES) return null
    val location = header("Location")?.trim().orEmpty()
    if (location.isEmpty()) return null
    return request.url.resolve(location)
}

private fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

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

internal fun Map<String, String>.withHttpBasicCredentials(
    credentials: YTransportCredentials?,
): Map<String, String> {
    if (keys.any { it.equals("Authorization", ignoreCase = true) }) return this
    val usernamePassword = credentials as? YTransportCredentials.UsernamePassword ?: return this
    return this +
        ("Authorization" to Credentials.basic(usernamePassword.username, usernamePassword.password))
}

private val sharedMediaTransportClient =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

private val SAFE_MEDIA_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private const val MAX_SAFE_MEDIA_REDIRECTS = 8
