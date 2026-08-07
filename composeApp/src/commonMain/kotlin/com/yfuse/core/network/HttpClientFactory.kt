package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
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
    customUserAgent: () -> String = { "" },
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
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        defaultRequest {
            header("X-Emby-Authorization", buildAuthHeader(appVersion))
            customUserAgent().trim().takeIf { it.isNotEmpty() }?.let { value ->
                header(HttpHeaders.UserAgent, value)
            }
        }
    }

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
