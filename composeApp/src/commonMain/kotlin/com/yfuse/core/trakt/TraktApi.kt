package com.yfuse.core.trakt

import com.yfuse.core.migration.migrationRelayHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream

private val traktJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** Public-internet TLS, no redirects and its own engine/pool. Caller owns this client. */
fun createTraktHttpClient(): HttpClient =
    HttpClient(migrationRelayHttpEngine()) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) { json(traktJson) }
    }

@Serializable
data class TraktIds(
    val trakt: Long? = null,
    val tmdb: Int? = null,
    val tvdb: Long? = null,
    val imdb: String? = null,
)

@Serializable
data class TraktTitle(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktEpisode(
    val title: String = "",
    val season: Int,
    val number: Int,
    val ids: TraktIds = TraktIds(),
)

@Serializable
data class TraktListItem(
    val type: String,
    val id: Long? = null,
    val movie: TraktTitle? = null,
    val show: TraktTitle? = null,
    val episode: TraktEpisode? = null,
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("listed_at") val listedAt: String? = null,
)

data class TraktPage(
    val items: List<TraktListItem>,
    val hasNext: Boolean,
)

@Serializable
data class TraktPlaybackMedia(
    val type: String,
    val ids: TraktIds,
) {
    fun valid() =
        type in setOf("movie", "episode") &&
            (
                (ids.trakt ?: 0) > 0 ||
                    (ids.tmdb ?: 0) > 0 ||
                    (ids.tvdb ?: 0) > 0 ||
                    ids.imdb?.matches(Regex("tt[0-9]+")) == true
            )
}

@Serializable
enum class TraktPlaybackAction { Start, Pause, Stop }

interface TraktApi {
    suspend fun history(
        clientId: String,
        token: String,
        page: Int,
        endAt: String,
    ): TraktPage

    suspend fun watchlist(
        clientId: String,
        token: String,
        page: Int,
    ): TraktPage

    suspend fun scrobble(
        clientId: String,
        token: String,
        media: TraktPlaybackMedia,
        action: TraktPlaybackAction,
        progress: Float,
    )
}

/** Direct Trakt data requests; fish-account tokens are used only by AccountTraktAuthApi. */
class HttpTraktApi(
    private val client: HttpClient,
) : TraktApi {
    override suspend fun history(
        clientId: String,
        token: String,
        page: Int,
        endAt: String,
    ) = page(clientId, token, "/sync/history", page, endAt)

    override suspend fun watchlist(
        clientId: String,
        token: String,
        page: Int,
    ) = page(clientId, token, "/sync/watchlist", page)

    private suspend fun page(
        clientId: String,
        token: String,
        path: String,
        page: Int,
        endAt: String? = null,
    ): TraktPage {
        require(page in 1..100_000)
        return client
            .prepareGet("https://api.trakt.tv$path") {
                auth(clientId, token)
                parameter("page", page)
                parameter("limit", 100)
                if (endAt != null) parameter("end_at", endAt)
            }.execute { response ->
                response.requireSuccess()
                val items = traktJson.decodeFromString<List<TraktListItem>>(response.boundedText(2 * 1024 * 1024))
                check(items.size <= 250) { "Trakt 单页返回内容过多" }
                val pages = response.headers["X-Pagination-Page-Count"]?.toIntOrNull()
                // The server may clamp our requested limit. Never infer completeness from items.size < 100.
                check(pages != null || items.isEmpty()) { "Trakt 分页信息缺失，请稍后重试" }
                TraktPage(items, pages != null && page < pages)
            }
    }

    override suspend fun scrobble(
        clientId: String,
        token: String,
        media: TraktPlaybackMedia,
        action: TraktPlaybackAction,
        progress: Float,
    ) {
        require(media.valid() && progress.isFinite() && progress in 0f..100f)
        client
            .preparePost("https://api.trakt.tv/scrobble/${action.name.lowercase()}") {
                auth(clientId, token)
                setBody(
                    buildJsonObject {
                        put(
                            media.type,
                            buildJsonObject {
                                put(
                                    "ids",
                                    traktJson.encodeToJsonElement(TraktIds.serializer(), media.ids),
                                )
                            },
                        )
                        put("progress", progress)
                    },
                )
            }.execute { response ->
                // Trakt explicitly treats recently repeated stop events as duplicates, not failures.
                if (action != TraktPlaybackAction.Stop ||
                    !(response.status.value == 409 || response.status.value == 422 && progress < 1f)
                ) {
                    response.requireSuccess()
                }
                response.boundedText(64 * 1024)
            }
    }

    private fun HttpRequestBuilder.auth(
        clientId: String,
        token: String,
    ) {
        require(clientId.isNotBlank() && token.isNotBlank())
        header("trakt-api-key", clientId)
        header("trakt-api-version", "2")
        header("User-Agent", "Yfuse/Trakt")
        bearerAuth(token)
        contentType(ContentType.Application.Json)
    }
}

private fun HttpResponse.requireSuccess() {
    if (!status.isSuccess()) {
        throw TraktApiException(
            status.value,
            headers["Retry-After"]?.toIntOrNull()?.coerceIn(1, 3600) ?: 0,
        )
    }
}

private suspend fun HttpResponse.boundedText(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val bytes = ByteArray(8192)
    val channel = bodyAsChannel()
    while (true) {
        val count = channel.readAvailable(bytes)
        if (count < 0) break
        check(output.size() + count <= maxBytes) { "Trakt 返回内容过大" }
        output.write(bytes, 0, count)
    }
    return output.toByteArray().decodeToString(throwOnInvalidSequence = true)
}
