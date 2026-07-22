package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Platform HTTP engine (CIO on Android). */
expect fun embyHttpEngine(): HttpClientEngine

/**
 * Creates the shared Ktor client for talking to an Emby server.
 *
 * - `ContentEncoding(gzip)`: Emby returns gzip-compressed responses by default.
 * - `expectSuccess = true`: non-2xx responses throw, so callers can map them.
 * - injects the Emby auth header, and the access token when available.
 */
fun createEmbyClient(
    engine: HttpClientEngine = embyHttpEngine(),
    tokenProvider: () -> String?,
): HttpClient = HttpClient(engine) {
    expectSuccess = true
    install(ContentEncoding) { gzip() }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; isLenient = true })
    }
    defaultRequest {
        header("X-Emby-Authorization", buildAuthHeader())
        tokenProvider()?.let { header("X-Emby-Token", it) }
    }
}
