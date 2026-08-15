package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.parseEpisodeWatchKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter

internal class EmbyLookupService(
    private val client: HttpClient,
) {
    /**
     * Every series in the library, indexed by provider key (`tmdb:1399`).
     *
     * One request for the whole library rather than a lookup per show. The airing calendar
     * asks "do you have this?" about a couple of dozen shows at once, and most of the
     * answers are no — resolving each one individually would be two dozen round trips to
     * learn almost nothing. A library's series list is small even when its episode count is
     * not, so fetching it whole is cheaper than querying it piecemeal.
     */
    suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>> =
        providerIndex(server, "Series").map { index -> index.mapValues { it.value.itemId } }

    /**
     * Every film in the library, keyed by provider id and carrying whether it was watched.
     *
     * Separate from [seriesProviderIndex] because a film *is* the thing the calendar row is
     * about — there is no episode below it — so 已看 has to come from the same request. A
     * series' watched state belongs to its episodes and is read from the episode list.
     */
    suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>> =
        providerIndex(server, "Movie")

    private suspend fun providerIndex(
        server: SavedServer,
        includeItemTypes: String,
    ): Result<Map<String, ProviderHit>> =
        embyApiCall("provider_index") {
            val dto: ItemsResponseDto =
                client
                    .get(
                        "${server.baseUrl}/Users/${server.userId}/Items",
                    ) {
                        header("X-Emby-Token", server.accessToken)
                        parameter("IncludeItemTypes", includeItemTypes)
                        parameter("Recursive", "true")
                        parameter("Fields", "ProviderIds")
                        parameter("EnableImages", "false")
                    }.body()
            buildMap {
                dto.Items.forEach { item ->
                    val hit = ProviderHit(item.Id, item.UserData?.Played == true)
                    item.ProviderIds.orEmpty().forEach { (provider, value) ->
                        if (value.isNotBlank()) {
                            put("${provider.lowercase()}:$value", hit)
                        }
                    }
                }
            }
        }

    /** Precise TMDB-to-Emby match, avoiding localized-title mismatches. */
    suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> =
        embyApiCall("find_item_by_provider") {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Users/${server.userId}/Items") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("Recursive", true)
                        parameter("IncludeItemTypes", if (mediaType == "tv") "Series" else "Movie")
                        parameter("AnyProviderIdEquals", "tmdb.$tmdbId")
                        parameter("Fields", "ProductionYear,Overview,ProviderIds")
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                        parameter("Limit", 1)
                    }.body()
            dto.Items.firstOrNull()?.toMediaItem()
        }

    /**
     * Resolves a watch-together `mediaKey` to an item on [server].
     *
     * A room identifies its film by provider id (`tmdb:603`, `imdb:tt0133093`) precisely so
     * that two people on different Emby servers, holding different files, can watch it
     * together — see `PlayerStore.watchKey`. Joining an invite therefore means asking *my*
     * servers "which of your items is this?", which is what this does.
     *
     * `emby:<id>` keys are the same-server fallback the key scheme falls back to when a
     * title carries no provider ids at all. They're only meaningful on the server they came
     * from, so they're looked up as a plain item id and will simply miss elsewhere — which
     * is the honest answer, and what lets the caller say "你的服务器上没有这部片".
     */
    suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?> =
        embyApiCall("find_item_by_media_key") {
            // `tmdb:1399/s2e5` — the show is identified by provider id, the episode by its
            // place in it. Resolved in two steps because that's how Emby indexes it: nothing
            // queries "episode 5 of the show with this Tmdb id" directly.
            parseEpisodeWatchKey(mediaKey)?.let { coordinate ->
                val series =
                    findByMediaKey(server, coordinate.seriesKey).getOrNull()
                        ?: return@embyApiCall null
                val dto: ItemsResponseDto =
                    client
                        .get(
                            "${server.baseUrl}/Shows/${series.id}/Episodes",
                        ) {
                            header("X-Emby-Token", server.accessToken)
                            parameter("UserId", server.userId)
                            parameter("Season", coordinate.seasonNumber)
                            parameter("Fields", "ProductionYear,Overview,ProviderIds")
                        }.body()
                return@embyApiCall dto.Items
                    .firstOrNull { it.IndexNumber == coordinate.episodeNumber }
                    ?.toMediaItem()
            }
            val provider = mediaKey.substringBefore(':', "")
            val value = mediaKey.substringAfter(':', "")
            if (provider.isBlank() || value.isBlank()) {
                return@embyApiCall null
            }
            if (provider.equals("emby", ignoreCase = true)) {
                val dto: BaseItemDto =
                    client
                        .get(
                            "${server.baseUrl}/Users/${server.userId}/Items/$value",
                        ) {
                            header("X-Emby-Token", server.accessToken)
                            parameter("Fields", "ProductionYear,Overview,ProviderIds")
                        }.body()
                return@embyApiCall dto.toMediaItem()
            }
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Users/${server.userId}/Items") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("Recursive", true)
                        parameter("IncludeItemTypes", "Movie,Series,Episode")
                        parameter("AnyProviderIdEquals", "${provider.lowercase()}.$value")
                        parameter("Fields", "ProductionYear,Overview,ProviderIds")
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                        parameter("Limit", 1)
                    }.body()
            dto.Items.firstOrNull()?.toMediaItem()
        }
}
