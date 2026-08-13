package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** TMDB read-access token, supplied per platform (Android: BuildConfig). */
expect fun tmdbToken(): String

const val TMDB_BASE = "https://api.themoviedb.org/3"

/**
 * Shorter than the Emby budget on purpose, but long enough for mainland DNS and TLS setup.
 * Sixteen home feeds are issued concurrently; an overly aggressive six-second connect budget
 * made every shelf fail together on otherwise usable mobile networks.
 */
private const val TMDB_REQUEST_TIMEOUT_MS = 20_000L
private const val TMDB_CONNECT_TIMEOUT_MS = 12_000L

/** Client for TMDB; authenticates with the v4 read token. */
fun createTmdbClient(engine: HttpClientEngine = embyHttpEngine()): HttpClient =
    HttpClient(engine) {
        expectSuccess = true
        install(ContentEncoding) { gzip() }
        install(HttpTimeout) {
            requestTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = TMDB_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = TMDB_REQUEST_TIMEOUT_MS
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                },
            )
        }
        defaultRequest {
            header("Authorization", "Bearer ${tmdbToken()}")
            header("Accept", "application/json")
        }
    }

/** TMDB image CDN. */
object TmdbImages {
    fun poster(
        path: String?,
        width: String = "w500",
    ): String? = path?.let { "https://image.tmdb.org/t/p/$width$it" }

    fun backdrop(
        path: String?,
        width: String = "w1280",
    ): String? = path?.let { "https://image.tmdb.org/t/p/$width$it" }

    /** Alternate official image host used when image.tmdb.org is unavailable. */
    fun media(
        path: String?,
        width: String = "w500",
    ): String? = path?.let { "https://media.themoviedb.org/t/p/$width$it" }
}
