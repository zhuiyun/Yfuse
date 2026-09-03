package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/** Platform HTTP engine (OkHttp on Android for platform TLS and WebSocket support). */
expect fun embyHttpEngine(): HttpClientEngine

/**
 * Default User-Agent string sent to the user's Emby server when the user has not
 * set a custom one. Mirrors the official "Emby for Android Mobile" client string so
 * that server-side device lists, playback sessions, and any UA-based feature gating
 * treat us as the stock mobile client. The user's custom UA, if set, overrides this
 * everywhere — see [com.yfuse.core.data.UserAgentPreferences].
 */
const val DEFAULT_EMBY_USER_AGENT: String = "Emby for Android Mobile"

private data class EmbyRequestOrigin(
    val protocol: URLProtocol,
    val host: String,
    val port: Int,
)

private data class EmbyIdentityPreferenceKey(
    val origin: EmbyRequestOrigin,
    val accessToken: String,
)

private val embyRequestOriginKey = AttributeKey<EmbyRequestOrigin>("EmbyRequestOrigin")
private val suppressEmbyIdentityKey = AttributeKey<Unit>("SuppressEmbyIdentity")

private const val EMBY_CLIENT_HEADER = "X-Emby-Client"
private const val EMBY_CLIENT_VERSION_HEADER = "X-Emby-Client-Version"
private const val EMBY_DEVICE_ID_HEADER = "X-Emby-Device-Id"
private const val EMBY_DEVICE_NAME_HEADER = "X-Emby-Device-Name"

private val embyJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Some Emby reverse proxies return a valid JSON payload with HTTP 200 but strip Content-Type.
 * Ktor ContentNegotiation deliberately skips such responses, which otherwise turns a successful
 * API call into NoTransformationFoundException. This fallback is scoped to the Emby client and to
 * structured 2xx bodies only; text/byte/channel callers keep Ktor's normal raw-body semantics.
 */
private val EmbyMissingContentTypeJson =
    createClientPlugin("EmbyMissingContentTypeJson") {
        val converter = KotlinxSerializationConverter(embyJson)
        transformResponseBody { response, content, requestedType ->
            if (
                response.status.value !in 200..299 ||
                response.headers[HttpHeaders.ContentType] != null ||
                requestedType.type == String::class ||
                requestedType.type == ByteArray::class ||
                requestedType.type == ByteReadChannel::class ||
                requestedType.type == HttpStatusCode::class ||
                requestedType.type == Unit::class
            ) {
                return@transformResponseBody null
            }
            converter.deserialize(Charsets.UTF_8, requestedType, content)
        }
    }

/** Marks a non-Emby request so shared-client defaults are stripped before network execution. */
internal fun HttpRequestBuilder.suppressEmbyIdentity() {
    attributes.put(suppressEmbyIdentityKey, Unit)
}

/**
 * How long an Emby request may take before it is abandoned.
 *
 * These were never configured: the client relied on whatever the engine happened to default
 * to, which is why timeout failures in the diagnostic logs reported `connect_timeout=unknown
 * ms`. A self-hosted Emby behind a home connection is genuinely slow, so the request budget
 * is generous; failing to *connect* is a different thing and worth giving up on sooner.
 */
data class EmbyTimeouts(
    val requestMs: Long = 30_000L,
    val connectMs: Long = 10_000L,
    val socketMs: Long = 30_000L,
)

/**
 * Danmaku endpoints are user-configured third-party services, so they need their own
 * deliberately short budget instead of being allowed to hold a player request forever.
 */
data class DanmakuTimeouts(
    val requestMs: Long = 15_000L,
    val connectMs: Long = 10_000L,
    val socketMs: Long = 15_000L,
)

/**
 * Creates the shared Ktor client for Emby.
 *
 * - `ContentEncoding(gzip)`: Emby returns gzip-compressed responses by default.
 * - `expectSuccess = true`: non-2xx responses throw, so callers can map them.
 * - `HttpTimeout`: explicit budgets rather than the engine's undeclared defaults.
 * - injects the Emby client identity header. The per-server access token is
 *   added by the repository on each authenticated request.
 */
fun createEmbyClient(
    /** Actual package version supplied by the platform build; never a hand-maintained copy. */
    appVersion: String,
    engine: HttpClientEngine = embyHttpEngine(),
    customUserAgent: () -> String = { DEFAULT_EMBY_USER_AGENT },
    /**
     * Null omits the plugin entirely, for unit tests driving a `MockEngine`.
     *
     * Those answer instantly, so there is nothing for a timeout to protect, and the plugin's
     * per-request watchdog is still being cancelled after a test disposes its store — which
     * lands a continuation on `Dispatchers.Main` moments after `resetMain()` has removed it.
     */
    timeouts: EmbyTimeouts? = EmbyTimeouts(),
): HttpClient =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentEncoding) { gzip() }
        timeouts?.let { budget ->
            install(HttpTimeout) {
                requestTimeoutMillis = budget.requestMs
                connectTimeoutMillis = budget.connectMs
                socketTimeoutMillis = budget.socketMs
            }
        }
        install(EmbyMissingContentTypeJson)
        install(ContentNegotiation) {
            json(embyJson)
        }
        defaultRequest {
            header("X-Emby-Authorization", buildAuthHeader(appVersion))
            header(EMBY_CLIENT_HEADER, DEFAULT_EMBY_CLIENT_NAME)
            header(EMBY_CLIENT_VERSION_HEADER, appVersion)
            header(EMBY_DEVICE_ID_HEADER, com.yfuse.deviceId())
            header(EMBY_DEVICE_NAME_HEADER, com.yfuse.deviceModel())
            header(
                HttpHeaders.UserAgent,
                customUserAgent().trim().ifBlank { DEFAULT_EMBY_USER_AGENT },
            )
        }
    }.also { client ->
        // Tokens issued before 0.2.60 may still be associated with the former Yfuse client
        // identity. Keep the released identity as the default and learn the legacy identity
        // only after the library discovery endpoint rejects the current one with 403.
        val preferredClientBySession =
            MutableStateFlow<Map<EmbyIdentityPreferenceKey, String>>(emptyMap())
        client.plugin(HttpSend).intercept { request ->
            if (request.attributes.getOrNull(suppressEmbyIdentityKey) != null) {
                request.headers.remove("X-Emby-Authorization")
                request.headers.remove(EMBY_CLIENT_HEADER)
                request.headers.remove(EMBY_CLIENT_VERSION_HEADER)
                request.headers.remove(EMBY_DEVICE_ID_HEADER)
                request.headers.remove(EMBY_DEVICE_NAME_HEADER)
                request.headers.remove("X-Emby-Token")
            }
            val target = request.url
            val currentOrigin = target.build().embyRequestOrigin()
            val originalOrigin = request.attributes.getOrNull(embyRequestOriginKey)
            if (originalOrigin == null) {
                request.attributes.put(embyRequestOriginKey, currentOrigin)
            } else {
                check(originalOrigin == currentOrigin) {
                    "Emby 请求禁止跨来源重定向"
                }
            }

            val accessToken = request.headers["X-Emby-Token"]?.takeIf { it.isNotBlank() }
            if (accessToken == null) return@intercept execute(request)

            val preferenceKey = EmbyIdentityPreferenceKey(currentOrigin, accessToken)
            val preferredClient =
                preferredClientBySession.value[preferenceKey] ?: DEFAULT_EMBY_CLIENT_NAME
            request.applyEmbyIdentity(appVersion, preferredClient)
            val canProbeLegacyIdentity =
                (request.method == HttpMethod.Get || request.method == HttpMethod.Head) &&
                    request.url
                        .build()
                        .encodedPath
                        .trimEnd('/')
                        .endsWith("/Views")
            if (!canProbeLegacyIdentity) return@intercept execute(request)

            val firstCall = execute(request)
            if (firstCall.response.status.value != 403) return@intercept firstCall

            val fallbackClient =
                if (preferredClient == LEGACY_EMBY_CLIENT_NAME) {
                    DEFAULT_EMBY_CLIENT_NAME
                } else {
                    LEGACY_EMBY_CLIENT_NAME
                }
            firstCall.response.bodyAsChannel().cancel(CancellationException("Retrying with alternate Emby identity"))
            request.applyEmbyIdentity(appVersion, fallbackClient)
            val fallbackCall = execute(request)
            if (fallbackCall.response.status.value in 200..299) {
                preferredClientBySession.update { it + (preferenceKey to fallbackClient) }
            }
            fallbackCall
        }
    }

/**
 * Sends both forms used by Emby clients. Emby Server accepts the combined authorization
 * value, while a number of reverse proxies and access-control plugins inspect the explicit
 * client/device headers before the request reaches Emby.
 */
private fun HttpRequestBuilder.applyEmbyIdentity(
    appVersion: String,
    clientName: String,
) {
    headers.remove("X-Emby-Authorization")
    headers.remove(EMBY_CLIENT_HEADER)
    headers.remove(EMBY_CLIENT_VERSION_HEADER)
    headers.remove(EMBY_DEVICE_ID_HEADER)
    headers.remove(EMBY_DEVICE_NAME_HEADER)
    header("X-Emby-Authorization", buildAuthHeader(appVersion, clientName))
    header(EMBY_CLIENT_HEADER, clientName)
    header(EMBY_CLIENT_VERSION_HEADER, appVersion)
    header(EMBY_DEVICE_ID_HEADER, com.yfuse.deviceId())
    header(EMBY_DEVICE_NAME_HEADER, com.yfuse.deviceModel())
}

private fun Url.embyRequestOrigin(): EmbyRequestOrigin =
    EmbyRequestOrigin(
        protocol = protocol,
        host = host.lowercase(),
        port = if (specifiedPort == 0) protocol.defaultPort else specifiedPort,
    )

/**
 * Client for arbitrary user-configured danmaku hosts.
 *
 * It intentionally carries no Emby identity or stable device id: third-party danmaku
 * endpoints are outside the user's media server trust boundary.
 */
fun createDanmakuClient(
    engine: HttpClientEngine = embyHttpEngine(),
    timeouts: DanmakuTimeouts = DanmakuTimeouts(),
): HttpClient =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentEncoding) { gzip() }
        install(HttpTimeout) {
            requestTimeoutMillis = timeouts.requestMs
            connectTimeoutMillis = timeouts.connectMs
            socketTimeoutMillis = timeouts.socketMs
        }
    }
