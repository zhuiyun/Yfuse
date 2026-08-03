package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Platform HTTP engine (CIO on Android). */
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
 * Creates the shared Ktor client for Emby.
 *
 * - `ContentEncoding(gzip)`: Emby returns gzip-compressed responses by default.
 * - `expectSuccess = true`: non-2xx responses throw, so callers can map them.
 * - injects the Emby client identity header. The per-server access token is
 *   added by the repository on each authenticated request.
 */
fun createEmbyClient(
    engine: HttpClientEngine = embyHttpEngine(),
    customUserAgent: () -> String = { "" },
): HttpClient =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentEncoding) { gzip() }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        defaultRequest {
            header("X-Emby-Authorization", buildAuthHeader())
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
): HttpClient =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentEncoding) { gzip() }
    }
