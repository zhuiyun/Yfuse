package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Only catalog identity leaves the device; never a media URL, server token or account session. */
data class TheOtherDbQuery(
    val tmdbId: Long,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val isTv: Boolean get() = season != null && episode != null
    val cacheKey: String get() = if (isTv) "tv:$tmdbId/s${season}e$episode" else "movie:$tmdbId"
}

@Serializable
data class TheOtherDbMarker(
    @SerialName("marker_type") val type: String,
    @SerialName("time_start") val start: Double,
    @SerialName("time_end") val end: Double? = null,
)

@Serializable
data class TheOtherDbSeason(
    @SerialName("season_number") val number: Int,
)

@Serializable
data class TheOtherDbEpisode(
    @SerialName("episode_number") val number: Int,
)

@Serializable
data class TheOtherDbVersion(
    @SerialName("version_id") val id: Long,
    @SerialName("version_runtime") val runtime: Double = 0.0,
    @SerialName("version_platform") val platform: String? = null,
    @SerialName("video_type") val videoType: String = "",
    @SerialName("base_video_season") val season: TheOtherDbSeason? = null,
    @SerialName("base_video_episode") val episode: TheOtherDbEpisode? = null,
    @SerialName("marker") val markers: List<TheOtherDbMarker> = emptyList(),
)

@Serializable
private data class TheOtherDbPage(
    val items: List<TheOtherDbVersion>,
    @SerialName("has_more") val hasMore: Boolean,
)

/** Dedicated anonymous client: no auth, cookies, default server headers or cross-host redirects. */
fun createTheOtherDbClient(engine: HttpClientEngine = embyHttpEngine()): HttpClient =
    HttpClient(engine) {
        expectSuccess = true
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000L
            requestTimeoutMillis = 5_000L
            socketTimeoutMillis = 5_000L
        }
    }

class TheOtherDbApi(
    private val client: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun versions(query: TheOtherDbQuery): List<TheOtherDbVersion> {
        require(query.tmdbId > 0L)
        require((query.season == null) == (query.episode == null))
        val versions = mutableListOf<TheOtherDbVersion>()
        // Read every page before matching; a truncated list can hide a conflicting version.
        repeat(10) { index ->
            val body =
                buildJsonObject {
                    put("platform_type", if (query.isTv) "tmdb_id_tv" else "tmdb_id_movie")
                    put("platform_value", query.tmdbId.toString())
                    query.season?.let { put("season_number", it) }
                    query.episode?.let { put("episode_number", it) }
                    putJsonArray("includes") {
                        add(kotlinx.serialization.json.JsonPrimitive("marker"))
                        add(kotlinx.serialization.json.JsonPrimitive("base_video_season"))
                        add(kotlinx.serialization.json.JsonPrimitive("base_video_episode"))
                    }
                    put("page", index + 1)
                    put("page_size", 30)
                }
            val response =
                client.post("https://playback.theotherdb.org/api/metadata") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            check(response.status.value == 200) { "Unexpected metadata response" }
            val raw = response.bodyAsText()
            check(raw.length <= 512_000) { "Oversized metadata response" }
            val page = json.decodeFromString<TheOtherDbPage>(raw)
            check(page.items.size <= 30) { "Oversized metadata page" }
            versions += page.items
            if (!page.hasMore) return versions.distinctBy { it.id }
        }
        error("Incomplete metadata pagination")
    }
}
