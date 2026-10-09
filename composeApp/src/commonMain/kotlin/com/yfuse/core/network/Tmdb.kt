package com.yfuse.core.network

import com.yfuse.backend.BackendAccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * TMDB read-access token built into this package, supplied per platform (Android: BuildConfig);
 * empty when the build was made without one. Only the signed-out path still needs it.
 */
expect fun tmdbToken(): String

/**
 * Platform engine for TMDB: the media-server engine's pooling plus an HTTP response cache.
 *
 * TMDB marks its responses cacheable, and the same show, season and image-config reads repeat on
 * every detail page, calendar pass and launch. Media-server calls stay uncached on purpose: their
 * answers are per-user state that must not be served stale.
 */
expect fun tmdbHttpEngine(): HttpClientEngine

const val TMDB_BASE = "https://api.themoviedb.org/3"

/**
 * Shorter than the Emby budget on purpose, but long enough for mainland DNS and TLS setup.
 * Sixteen home feeds are issued concurrently; an overly aggressive six-second connect budget
 * made every shelf fail together on otherwise usable mobile networks.
 */
private const val TMDB_REQUEST_TIMEOUT_MS = 20_000L
private const val TMDB_CONNECT_TIMEOUT_MS = 12_000L

/**
 * Client for TMDB. Callers keep addressing [TMDB_BASE]; [TmdbRequestRouter] decides per request.
 *
 * With a Yfuse account session a request goes through the account server's proxy under the
 * account's bearer, so the TMDB token never has to ship in the APK. Without one it goes direct with
 * the built-in token, and with neither it fails the way an unreachable TMDB does.
 */
fun createTmdbClient(
    engine: HttpClientEngine = tmdbHttpEngine(),
    account: TmdbAccountAccess? = null,
    builtInToken: () -> String = ::tmdbToken,
    nowEpochMs: () -> Long = { System.currentTimeMillis() },
    backendAccess: BackendAccess = BackendAccess.Default,
): HttpClient =
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
            header("Accept", "application/json")
        }
    }.also { client ->
        val router = createTmdbRequestRouter(builtInToken, account, nowEpochMs, backendAccess)
        client.plugin(HttpSend).intercept { request -> router.send(request) { execute(it) } }
    }

/** TMDB image CDN. */
object TmdbImages {
    fun poster(
        path: String?,
        width: String = "w500",
    ): String? = imageUrl(path, width, "image.tmdb.org")

    fun backdrop(
        path: String?,
        width: String = "w1280",
    ): String? = imageUrl(path, width, "image.tmdb.org")

    /** Alternate official image host used when image.tmdb.org is unavailable. */
    fun media(
        path: String?,
        width: String = "w500",
    ): String? = imageUrl(path, width, "media.themoviedb.org")

    private fun imageUrl(
        path: String?,
        width: String,
        host: String,
    ): String? {
        val value = path?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (value.startsWith("https://") || value.startsWith("http://")) return value
        if (!value.startsWith('/') || value.startsWith("//")) return null
        return "https://$host/t/p/$width$value"
    }
}
