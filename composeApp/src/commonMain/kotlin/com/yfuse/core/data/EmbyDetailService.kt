package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.bestTrickplay
import com.yfuse.core.data.dto.toEpisode
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.data.dto.toPerson
import com.yfuse.core.data.dto.toSeason
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.PlayTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.TrickplayInfo
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter

internal class EmbyDetailService(
    private val client: HttpClient,
    private val progress: PlaybackProgressProjection = PlaybackProgressProjection(),
) {
    /** Real Emby recommendations used by the detail page's compact poster rail. */
    suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int = 12,
    ): Result<List<MediaItem>> =
        embyApiCall("similar_items") {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Items/$itemId/Similar") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("Limit", limit)
                        parameter(
                            "Fields",
                            "ProductionYear,CommunityRating,BackdropImageTags,ParentBackdropItemId," +
                                "ParentBackdropImageTags,SeriesPrimaryImageTag",
                        )
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                    }.body()
            dto.Items.map { progress.project(server, it).toMediaItem() }
        }

    /**
     * Resolves what to actually play for a detail item: movies/episodes play
     * themselves; a series plays its "next up" episode (falling back to the
     * first episode), carrying that episode's resume position.
     */
    suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget> =
        embyApiCall("resolve_play_target") {
            if (detail.type != "Series") {
                PlayTarget(detail.id, detail.resumePositionTicks ?: 0L)
            } else {
                val episode =
                    if (progress.localOnly) {
                        fetchLocalNextUp(server, detail.id)
                    } else {
                        fetchNextUp(server, detail.id)
                    } ?: fetchFirstEpisode(server, detail.id)
                requireNotNull(episode) { "no episodes" }
                val projected = progress.project(server, episode)
                PlayTarget(projected.Id, projected.UserData?.PlaybackPositionTicks ?: 0L)
            }
        }

    internal suspend fun fetchNextUp(
        server: SavedServer,
        seriesId: String,
    ): BaseItemDto? {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Shows/NextUp") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                    parameter("SeriesId", seriesId)
                    parameter("Limit", 1)
                }.body()
        return dto.Items.firstOrNull()
    }

    internal suspend fun fetchFirstEpisode(
        server: SavedServer,
        seriesId: String,
    ): BaseItemDto? {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Shows/$seriesId/Episodes") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                    parameter("Limit", 1)
                }.body()
        return dto.Items.firstOrNull()
    }

    private suspend fun fetchLocalNextUp(
        server: SavedServer,
        seriesId: String,
    ): BaseItemDto? {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Shows/$seriesId/Episodes") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                    parameter("Fields", "ProviderIds,RunTimeTicks,UserData")
                }.body()
        val projected = dto.Items.map { item -> item to progress.project(server, item) }
        val recentIds = progress.localStates(server).mapNotNull { it.serverItemId }
        val byId = projected.associateBy { it.first.Id }
        recentIds.forEach { id ->
            val candidate = byId[id]?.takeIf { (_, item) ->
                item.UserData?.Played != true && (item.UserData?.PlaybackPositionTicks ?: 0L) > 0L
            }
            if (candidate != null) return candidate.first
        }
        return projected.firstOrNull { (_, item) -> item.UserData?.Played != true }?.first
            ?: dto.Items.firstOrNull()
    }

    /** Server-wide next episodes for the 首页「下一集」shelf. */
    suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int = 12,
    ): Result<List<MediaItem>> =
        embyApiCall("next_up") {
            if (progress.localOnly) return@embyApiCall localNextUpEpisodes(server, limit)
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Shows/NextUp") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("Limit", limit)
                        parameter(
                            "Fields",
                            "ProductionYear,CommunityRating,Overview,ProviderIds,BackdropImageTags,ParentBackdropItemId," +
                                "ParentBackdropImageTags,SeriesPrimaryImageTag,UserData",
                        )
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                    }.body()
            dto.Items.map { progress.project(server, it).toMediaItem() }
        }

    private suspend fun localNextUpEpisodes(
        server: SavedServer,
        limit: Int,
    ): List<MediaItem> {
        val recentStates = progress.localStates(server).take(MAX_LOCAL_NEXT_UP_HISTORY)
        val ids = recentStates.mapNotNull { it.serverItemId }.distinct()
        if (ids.isEmpty()) return emptyList()
        val cards: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Ids", ids.joinToString(","))
                    parameter(
                        "Fields",
                        "ProductionYear,CommunityRating,Overview,ProviderIds,BackdropImageTags," +
                            "ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag,RunTimeTicks,UserData",
                    )
                    parameter("EnableImageTypes", "Primary,Backdrop")
                    parameter("ImageTypeLimit", 2)
                    parameter("Limit", ids.size)
                }.body()
        val cardsById = cards.Items.associateBy(BaseItemDto::Id)
        val episodeCache = mutableMapOf<String, List<BaseItemDto>>()
        val result = mutableListOf<BaseItemDto>()
        recentStates.forEach { state ->
            if (result.size >= limit) return@forEach
            val item = state.serverItemId?.let(cardsById::get) ?: return@forEach
            if (item.Type != "Episode") return@forEach
            val candidate =
                if (!state.played && state.positionMs > 0L) {
                    item
                } else if (state.played) {
                    val seriesId = item.SeriesId ?: return@forEach
                    val episodes =
                        episodeCache[seriesId]
                            ?: fetchSeriesEpisodes(server, seriesId).also {
                                episodeCache[seriesId] = it
                            }
                    val currentIndex = episodes.indexOfFirst { it.Id == item.Id }
                    episodes
                        .asSequence()
                        .drop((currentIndex + 1).coerceAtLeast(0))
                        .firstOrNull { progress.project(server, it).UserData?.Played != true }
                } else {
                    null
                }
            if (candidate != null && result.none { it.Id == candidate.Id }) result += candidate
        }
        return result.take(limit).map { progress.project(server, it).toMediaItem() }
    }

    private suspend fun fetchSeriesEpisodes(
        server: SavedServer,
        seriesId: String,
    ): List<BaseItemDto> {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Shows/$seriesId/Episodes") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                    parameter(
                        "Fields",
                        "ProductionYear,CommunityRating,Overview,ProviderIds,BackdropImageTags," +
                            "ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag,RunTimeTicks,UserData",
                    )
                    parameter("EnableImageTypes", "Primary,Backdrop")
                    parameter("ImageTypeLimit", 2)
                }.body()
        return dto.Items
    }

    private companion object {
        const val MAX_LOCAL_NEXT_UP_HISTORY = 36
    }

    /** Full detail for a single item. Episodes inherit the series' cast. */
    suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
    ): Result<MediaDetail> =
        embyApiCall("item_detail") {
            val dto: BaseItemDto =
                client
                    .get("${server.baseUrl}/Users/${server.userId}/Items/$itemId") {
                        header("X-Emby-Token", server.accessToken)
                        parameter(
                            "Fields",
                            // Path and DateCreated are opt-in, and the 媒体信息 block is built out of
                            // them. BackdropImageTags is deliberately absent: it is not an ItemFields
                            // value — image tags come back on their own — and naming one Emby doesn't
                            // know risks the whole request rather than adding a field.
                            "Overview,Genres,People,ParentBackdropItemId,ParentBackdropImageTags," +
                                "SeriesPrimaryImageTag,MediaSources,MediaStreams," +
                                "Path,DateCreated,Chapters,ProviderIds",
                        )
                    }.body()
            val detail = progress.project(server, dto).toMediaDetail()

            // Emby returns no cast on episodes; borrow the series' cast instead.
            if (detail.type == "Episode" && detail.people.isEmpty() && detail.seriesId != null) {
                val seriesResult =
                    runCatching {
                        client
                            .get("${server.baseUrl}/Users/${server.userId}/Items/${detail.seriesId}") {
                                header("X-Emby-Token", server.accessToken)
                                parameter("Fields", "People")
                            }.body<BaseItemDto>()
                    }.onFailure {
                        AppLog.warning(
                            category = "emby",
                            event = "episode_cast_degraded",
                            message = "Episode detail loaded but series cast lookup failed",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                    }
                val series = seriesResult.getOrNull()
                detail.copy(people = series?.People?.map { it.toPerson() } ?: emptyList())
            } else {
                detail
            }
        }

    /** Seasons of a series. */
    suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>> =
        embyApiCall("seasons") {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Shows/$seriesId/Seasons") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                    }.body()
            dto.Items.map { it.toSeason() }
        }

    suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean = false,
        seasonNumber: Int? = null,
    ): Result<List<Episode>> =
        embyApiCall("episodes") {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Shows/$seriesId/Episodes") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        if (seasonId != null) parameter("SeasonId", seasonId)
                        if (seasonNumber != null) parameter("Season", seasonNumber)
                        parameter(
                            "Fields",
                            "Overview,Chapters,ProviderIds,RunTimeTicks,UserData,PremiereDate" +
                                if (includeMediaSources) ",MediaSources,MediaStreams" else "",
                        )
                    }.body()
            dto.Items.map { progress.project(server, it).toEpisode() }
        }

    /** Optional Jellyfin storyboard metadata; failure is intentionally isolated from playback. */
    suspend fun trickplayInfo(
        server: SavedServer,
        itemId: String,
    ): Result<TrickplayInfo?> =
        embyApiCall("trickplay_info") {
            val dto: BaseItemDto =
                client
                    .get(
                        "${server.baseUrl}/Users/${server.userId}/Items/$itemId",
                    ) {
                        header("X-Emby-Token", server.accessToken)
                        parameter("Fields", "Trickplay")
                    }.body()
            dto.bestTrickplay()
        }
}
