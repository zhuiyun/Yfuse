package com.yfuse.core.network

import com.yfuse.core.logging.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.io.IOException
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

/** One saved session on one origin: what a learned client identity and an access cooldown belong to. */
private data class EmbyIdentityPreferenceKey(
    val origin: EmbyRequestOrigin,
    val accessToken: String,
)

/** Consecutive transport failures of one origin, and until when it is skipped for them. */
private data class OriginFailureStreak(
    val failures: Int,
    val lastFailureAtMs: Long,
    val coolingUntilMs: Long,
)

private val embyRequestOriginKey = AttributeKey<EmbyRequestOrigin>("EmbyRequestOrigin")
private val suppressEmbyIdentityKey = AttributeKey<Unit>("SuppressEmbyIdentity")
private val serverCooldownExemptKey = AttributeKey<Unit>("ServerCooldownExempt")

private const val EMBY_CLIENT_HEADER = "X-Emby-Client"
private const val EMBY_TOKEN_HEADER = "X-Emby-Token"
private const val PLEX_TOKEN_HEADER = "X-Plex-Token"
private const val EMBY_ACCESS_COOLDOWN_MS = 5 * 60_000L

/**
 * An origin that failed this many times in a row - DNS, refused or timed-out connects - is skipped
 * for [ORIGIN_UNREACHABLE_COOLDOWN_MS]. One failure is noise on a mobile link; three are a host
 * that is down, and each further attempt would hold a request slot for its connect timeout.
 */
private const val ORIGIN_UNREACHABLE_FAILURES = 3

/** Failures further apart than this are separate incidents, not a streak. */
private const val ORIGIN_FAILURE_STREAK_WINDOW_MS = 2 * 60_000L

/**
 * Short on purpose: the device's own connection may have been the problem, and a user who has just
 * come back online must not wait long. Long outages are the health monitor's backoff to handle.
 */
private const val ORIGIN_UNREACHABLE_COOLDOWN_MS = 30_000L

/** Legacy-identity sessions remembered at once; a forgotten one only costs one 403 to relearn. */
private const val MAX_LEARNED_CLIENT_IDENTITIES = 64

private fun HttpResponse.isCloudflareChallenge(): Boolean =
    status.value == 403 &&
        (
            headers["cf-mitigated"].equals("challenge", ignoreCase = true) ||
                (
                    headers[HttpHeaders.Server].orEmpty().contains("cloudflare", ignoreCase = true) &&
                        headers[HttpHeaders.ContentType].orEmpty().contains("text/html", ignoreCase = true)
                )
        )

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
 * Lets a request through while its server or session is cooling down.
 *
 * Health probes and sign-in are how a cooldown is proven wrong: blocking them kept a server that had
 * recovered, or a user who had just signed in again, locked out until the timer ran out. Their answers
 * still feed the same bookkeeping, so a success lifts the cooldown for every other request.
 */
internal fun HttpRequestBuilder.exemptFromServerCooldown() {
    attributes.put(serverCooldownExemptKey, Unit)
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
    nowEpochMs: () -> Long = { System.currentTimeMillis() },
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
        // identity. Learn compatibility on metadata reads as well as library discovery:
        // cross-server search/playback does not visit that server's Views endpoint first.
        val preferredClientBySession =
            MutableStateFlow<Map<EmbyIdentityPreferenceKey, String>>(emptyMap())
        val accessCooldowns =
            MutableStateFlow<Map<EmbyIdentityPreferenceKey, Pair<Long, EmbyError>>>(emptyMap())

        // Keyed by origin alone: a host that does not answer is unreachable for every session on it.
        val unreachableOrigins = MutableStateFlow<Map<EmbyRequestOrigin, OriginFailureStreak>>(emptyMap())
        client.plugin(HttpSend).intercept { request ->
            val suppressIdentity = request.attributes.getOrNull(suppressEmbyIdentityKey) != null
            if (suppressIdentity) {
                request.headers.remove("X-Emby-Authorization")
                request.headers.remove(EMBY_CLIENT_HEADER)
                request.headers.remove(EMBY_CLIENT_VERSION_HEADER)
                request.headers.remove(EMBY_DEVICE_ID_HEADER)
                request.headers.remove(EMBY_DEVICE_NAME_HEADER)
                request.headers.remove(EMBY_TOKEN_HEADER)
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

            val accessToken = request.headers[EMBY_TOKEN_HEADER]?.takeIf { it.isNotBlank() }
            val explicitAuthorization = request.headers[HttpHeaders.Authorization]
            if (!suppressIdentity) {
                if (explicitAuthorization == null) {
                    val identity = request.headers["X-Emby-Authorization"] ?: buildAuthHeader(appVersion)
                    request.headers.append(
                        HttpHeaders.Authorization,
                        if (accessToken == null) identity else mediaBrowserAuthorization(accessToken, identity),
                    )
                } else if (accessToken != null && !explicitAuthorization.startsWith("MediaBrowser ")) {
                    // An edge proxy may own Authorization. Use Jellyfin's supported URL form
                    // without replacing its Basic/Bearer credential.
                    request.url.parameters.remove("ApiKey")
                    request.url.parameters.append("ApiKey", accessToken)
                }
            }

            // Plex sends its token as X-Plex-Token. Watching only the Emby header left every Plex
            // server outside the cooldowns. Requests without a credential - sign-in, public server
            // discovery - are never held back.
            val plexCredential = accessToken == null
            val sessionKey =
                (accessToken ?: request.headers[PLEX_TOKEN_HEADER]?.takeIf { it.isNotBlank() })
                    ?.let { EmbyIdentityPreferenceKey(currentOrigin, it) }
            if (sessionKey != null && request.attributes.getOrNull(serverCooldownExemptKey) == null) {
                val now = nowEpochMs()
                accessCooldowns.value[sessionKey]?.let { (until, error) ->
                    if (now < until) throw EmbyErrorException(error, fromCooldown = true)
                }
                unreachableOrigins.value[currentOrigin]?.let { streak ->
                    if (now < streak.coolingUntilMs) {
                        throw EmbyErrorException(EmbyError.Network, fromCooldown = true)
                    }
                }
            }

            fun rememberAnswer(response: HttpResponse) {
                // Any HTTP answer, an error page included, proves the origin reachable again.
                if (currentOrigin in unreachableOrigins.value) unreachableOrigins.update { it - currentOrigin }
                val key = sessionKey ?: return
                val failure =
                    when {
                        response.status.value == 401 && request.isCredentialCheck(plexCredential) ->
                            EmbyError.Unauthorized
                        response.isCloudflareChallenge() -> EmbyError.AccessDenied("Cloudflare")
                        else -> {
                            // Only a probe or a sign-in can succeed while a cooldown stands; its
                            // success proves the session usable again for everyone else.
                            if (response.status.isSuccess() && key in accessCooldowns.value) {
                                accessCooldowns.update { it - key }
                            }
                            return
                        }
                    }
                val now = nowEpochMs()
                val previous =
                    accessCooldowns.getAndUpdate { current ->
                        current.filterValues { it.first > now } +
                            (key to (now + EMBY_ACCESS_COOLDOWN_MS to failure))
                    }
                // Once per cooldown rather than once per refused request.
                if ((previous[key]?.first ?: 0L) <= now) {
                    AppLog.info(
                        category = "network.emby",
                        event = "session_cooldown_started",
                        message = "A server refused this session; other requests wait instead of repeating it",
                        attributes =
                            mapOf(
                                "reason" to if (failure == EmbyError.Unauthorized) "unauthorized" else "access_denied",
                                "cooldownMs" to EMBY_ACCESS_COOLDOWN_MS.toString(),
                            ),
                    )
                }
            }

            fun rememberTransportFailure() {
                val now = nowEpochMs()
                val streak =
                    unreachableOrigins
                        .updateAndGet { current ->
                            val failures = (current[currentOrigin]?.takeIf { it.isRecent(now) }?.failures ?: 0) + 1
                            val coolingUntil =
                                if (failures >= ORIGIN_UNREACHABLE_FAILURES) {
                                    now + ORIGIN_UNREACHABLE_COOLDOWN_MS
                                } else {
                                    0L
                                }
                            // Streaks age out here, so the map only ever holds recently failing origins.
                            current.filterValues { it.isRecent(now) } +
                                (currentOrigin to OriginFailureStreak(failures, now, coolingUntil))
                        }.getValue(currentOrigin)
                if (streak.failures == ORIGIN_UNREACHABLE_FAILURES) {
                    AppLog.info(
                        category = "network.emby",
                        event = "origin_cooldown_started",
                        message = "A server stopped answering; its requests fail fast for a short while",
                        attributes =
                            mapOf(
                                "failures" to streak.failures.toString(),
                                "cooldownMs" to ORIGIN_UNREACHABLE_COOLDOWN_MS.toString(),
                            ),
                    )
                }
            }

            suspend fun executeWithAccessTracking(): HttpClientCall {
                val call =
                    try {
                        execute(request)
                    } catch (error: ResponseException) {
                        rememberAnswer(error.response)
                        throw error
                    } catch (error: IOException) {
                        // DNS, refused and timed-out connects, resets: no answer from this origin.
                        rememberTransportFailure()
                        throw error
                    } catch (cancelled: CancellationException) {
                        // HttpTimeout cancels only this request's execution context, so the caller is
                        // still active. Whatever cancelled the caller itself is not the origin's doing.
                        if (currentCoroutineContext().isActive && cancelled.causedByRequestTimeout()) {
                            rememberTransportFailure()
                        }
                        throw cancelled
                    }
                rememberAnswer(call.response)
                return call
            }

            // Plex and credential-free requests have no Emby client identity to negotiate.
            if (accessToken == null) return@intercept executeWithAccessTracking()
            val preferenceKey = EmbyIdentityPreferenceKey(currentOrigin, accessToken)

            val preferredClient =
                preferredClientBySession.value[preferenceKey] ?: DEFAULT_EMBY_CLIENT_NAME
            request.applyEmbyIdentity(appVersion, preferredClient)
            val canProbeLegacyIdentity =
                (request.method == HttpMethod.Get || request.method == HttpMethod.Head) &&
                    request.url
                        .build()
                        .encodedPath
                        .isEmbyIdentityDiscoveryPath()
            if (!canProbeLegacyIdentity) return@intercept executeWithAccessTracking()

            val firstCall = executeWithAccessTracking()
            if (firstCall.response.status.value != 403) return@intercept firstCall
            // A WAF challenge is not an Emby identity mismatch. Do not probe alternate
            // identities or keep other background modules hammering the same endpoint.
            if (firstCall.response.isCloudflareChallenge()) return@intercept firstCall
            // Metadata 403s usually mean a settled permission failure. Only use the broader
            // discovery path when the server explicitly identifies a client/session mismatch.
            // Keep the established Views-only migration behavior for older installations.
            val isLibraryDiscovery =
                request.url
                    .build()
                    .encodedPath
                    .trimEnd('/')
                    .endsWith("/Views")
            if (!isLibraryDiscovery) {
                val response = firstCall.response
                if (response.headers[HttpHeaders.ContentType].orEmpty().contains("html", ignoreCase = true)) {
                    return@intercept firstCall
                }
                val detail = response.bodyAsText().lowercase()
                val identityRejected =
                    ("client" in detail || "session identity" in detail) &&
                        listOf("mismatch", "invalid", "not match", "identity required").any { it in detail }
                if (!identityRejected) return@intercept firstCall
            }

            val fallbackClient =
                if (preferredClient == LEGACY_EMBY_CLIENT_NAME) {
                    DEFAULT_EMBY_CLIENT_NAME
                } else {
                    LEGACY_EMBY_CLIENT_NAME
                }
            firstCall.response.bodyAsChannel().cancel(CancellationException("Retrying with alternate Emby identity"))
            request.applyEmbyIdentity(appVersion, fallbackClient)
            val fallbackCall = executeWithAccessTracking()
            if (fallbackCall.response.status.value in 200..299) {
                preferredClientBySession.update { current ->
                    // Every signed-in session that ever needed the fallback used to stay here for the
                    // life of the process. Oldest first out; re-learning costs one extra request.
                    val others = current - preferenceKey
                    val bounded =
                        if (others.size >= MAX_LEARNED_CLIENT_IDENTITIES) others - others.keys.first() else others
                    bounded + (preferenceKey to fallbackClient)
                }
            }
            fallbackCall
        }
    }

/** Only read-only Emby metadata routes may learn an old session's client identity. */
private fun String.isEmbyIdentityDiscoveryPath(): Boolean =
    Regex("(?:^|/)(?:Items(?:/[^/]+)?|Users/[^/]+/(?:Views|Items(?:/[^/]+)?)|Shows/[^/]+/(?:Episodes|Seasons))$")
        .containsMatchIn(trimEnd('/'))

private val embyCredentialCheckPath = Regex("(?:^|/)(?:system/info|users(?:/.*)?|auth(?:/.*)?)$")
private val plexCredentialCheckPath = Regex("^(?:/(?:library|hubs)(?:/.*)?)?$")

/**
 * Whether a 401 from this request says the session's credential itself was refused.
 *
 * Only the session's own identity and library reads qualify: Emby's System/Info plus its Users and
 * Auth routes, and Plex's library and hub reads. Playback reports, transcode cleanup and admin actions can answer
 * 401 for reasons that have nothing to do with the token - a missing permission, a proxy rule - and
 * one such answer used to hold every request to the server for five minutes as "需要重新登录".
 */
private fun HttpRequestBuilder.isCredentialCheck(plex: Boolean): Boolean {
    val path =
        url
            .build()
            .encodedPath
            .trimEnd('/')
            .lowercase()
    return if (plex) {
        // Section refresh and analysis are owner-only, so a managed Home user is refused them.
        method == HttpMethod.Get &&
            plexCredentialCheckPath.matches(path) &&
            !path.endsWith("/refresh") &&
            !path.endsWith("/analyze")
    } else {
        embyCredentialCheckPath.containsMatchIn(path)
    }
}

private fun OriginFailureStreak.isRecent(now: Long): Boolean =
    now - lastFailureAtMs in 0 until ORIGIN_FAILURE_STREAK_WINDOW_MS

private fun Throwable.causedByRequestTimeout(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is HttpRequestTimeoutException }

/**
 * Sends both forms used by Emby clients. Emby Server accepts the combined authorization
 * value, while a number of reverse proxies and access-control plugins inspect the explicit
 * client/device headers before the request reaches Emby.
 */
private fun HttpRequestBuilder.applyEmbyIdentity(
    appVersion: String,
    clientName: String,
) {
    if (headers[HttpHeaders.Authorization]?.startsWith("MediaBrowser ") == true) {
        headers.remove(HttpHeaders.Authorization)
        header(
            HttpHeaders.Authorization,
            mediaBrowserAuthorization(headers["X-Emby-Token"].orEmpty(), buildAuthHeader(appVersion, clientName)),
        )
    }
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
