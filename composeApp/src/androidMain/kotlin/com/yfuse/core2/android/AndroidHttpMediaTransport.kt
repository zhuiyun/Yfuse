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
import java.util.concurrent.TimeUnit

/** Stateful random-access HTTP/WebDAV transport. A seek is expressed by closing and reopening it. */
internal class AndroidHttpMediaTransport(
    private val client: OkHttpClient = sharedMediaTransportClient,
    private val followSafeRedirects: Boolean = false,
    private val allowCrossProtocolRedirects: Boolean = false,
    private val redirectState: AndroidHttpMediaRedirectState? = null,
    private val callTimeoutSeconds: Long? = null,
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
            .apply { callTimeoutSeconds?.let { callTimeout(it, TimeUnit.SECONDS) } }
            .build()

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse =
        withContext(Dispatchers.IO) {
            require(request.protocol in supportedProtocols) { "Unsupported HTTP transport protocol" }
            closeCurrent()
            val originalUri = request.uri
            val originalHeaders = request.headers.withHttpBasicCredentials(request.credentials)
            val cachedRoute = redirectState?.resolve(originalUri)
            var targetUri = cachedRoute?.targetUri ?: originalUri
            var activeHeaders = originalHeaders.withoutCredentials(cachedRoute?.stripCredentials == true)
            var redirectCount = 0
            var cleartextRedirect = false
            var strippedCredentials = cachedRoute?.stripCredentials == true
            var usingCachedRoute = cachedRoute != null
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
                if (usingCachedRoute && candidate.code in STALE_MEDIA_ROUTE_STATUS_CODES) {
                    candidate.close()
                    if (candidate.code == 403) {
                        // A validated provider can hand out short-lived media targets. Once one of
                        // those targets rejects a later range, route every remaining range through
                        // the authenticated origin so parallel prefetch cannot keep resurrecting it.
                        redirectState?.disableReuse(originalUri)
                    } else {
                        redirectState?.invalidate(originalUri, targetUri)
                    }
                    targetUri = originalUri
                    activeHeaders = originalHeaders
                    redirectCount = 0
                    cleartextRedirect = false
                    strippedCredentials = false
                    usingCachedRoute = false
                    continue
                }
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
                val redirectsToCleartext = previous.scheme == "https" && redirectTarget.scheme == "http"
                if (redirectsToCleartext && !allowCrossProtocolRedirects) {
                    candidate.close()
                    error("Secure media redirect cannot downgrade to HTTP")
                }
                redirectCount += 1
                cleartextRedirect = cleartextRedirect || redirectsToCleartext
                if (!previous.hasSameOrigin(redirectTarget)) {
                    activeHeaders = activeHeaders.filterKeys { !it.isCredentialHeader() }
                    strippedCredentials = true
                }
                targetUri = redirectTarget.toString()
                candidate.close()
            }
            val finalResponse = checkNotNull(opened)
            if (followSafeRedirects && finalResponse.isSuccessful && finalResponse.request.url.toString() != originalUri) {
                redirectState?.remember(
                    sourceUri = originalUri,
                    targetUri = finalResponse.request.url.toString(),
                    stripCredentials = strippedCredentials,
                )
            }
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
                implementation = "OkHttp",
                negotiatedProtocol = finalResponse.protocol.toString(),
                redirectCount = redirectCount,
                finalProtocol =
                    if (finalResponse.request.url.isHttps) {
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

/** One-source, memory-only redirect target shared across random-access transports. */
internal class AndroidHttpMediaRedirectState {
    private var route: AndroidHttpMediaRedirectRoute? = null
    private var reuseDisabledSourceUri: String? = null

    @Synchronized
    fun resolve(sourceUri: String): AndroidHttpMediaRedirectRoute? =
        route?.takeIf { cached -> reuseDisabledSourceUri != sourceUri && cached.sourceUri == sourceUri }

    @Synchronized
    fun remember(
        sourceUri: String,
        targetUri: String,
        stripCredentials: Boolean,
    ) {
        if (reuseDisabledSourceUri == sourceUri) return
        route =
            AndroidHttpMediaRedirectRoute(
                sourceUri = sourceUri,
                targetUri = targetUri,
                stripCredentials = stripCredentials,
            )
    }

    @Synchronized
    fun invalidate(
        sourceUri: String,
        targetUri: String,
    ) {
        if (route?.sourceUri == sourceUri && route?.targetUri == targetUri) route = null
    }

    @Synchronized
    fun disableReuse(sourceUri: String) {
        if (route?.sourceUri == sourceUri) route = null
        reuseDisabledSourceUri = sourceUri
    }
}

internal data class AndroidHttpMediaRedirectRoute(
    val sourceUri: String,
    val targetUri: String,
    val stripCredentials: Boolean,
)

private fun Response.safeMediaRedirectTarget(): HttpUrl? {
    if (code !in SAFE_MEDIA_REDIRECT_CODES) return null
    val location = header("Location")?.trim().orEmpty()
    if (location.isEmpty()) return null
    return request.url.resolve(location)
}

internal fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

private fun YByteRange.toHttpRange(): String = "bytes=$startInclusive-${endInclusive ?: ""}"

internal fun Map<String, String>.withoutCredentials(required: Boolean): Map<String, String> =
    if (required) filterKeys { name -> !name.isCredentialHeader() } else this

private fun String.isSafeTransportHeader(): Boolean = isNotBlank() && none { it == '\r' || it == '\n' || it == ':' }

internal fun String.isCredentialHeader(): Boolean {
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
        .connectTimeout(NATIVE_MEDIA_TRANSPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NATIVE_MEDIA_TRANSPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NATIVE_MEDIA_TRANSPORT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

private val SAFE_MEDIA_REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
private val STALE_MEDIA_ROUTE_STATUS_CODES = setOf(401, 403, 404, 410)
internal const val MAX_SAFE_MEDIA_REDIRECTS = 8
internal const val NATIVE_MEDIA_TRANSPORT_TIMEOUT_SECONDS = 20L
