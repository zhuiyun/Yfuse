package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ChapterDto
import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.MediaStreamDto
import com.yfuse.core.data.dto.PersonDto
import com.yfuse.core.data.dto.PlaybackInfoResponseDto
import com.yfuse.core.data.dto.PlexMediaContainerDto
import com.yfuse.core.data.dto.PlexMediaDto
import com.yfuse.core.data.dto.PlexMetadataDto
import com.yfuse.core.data.dto.PlexPartDto
import com.yfuse.core.data.dto.PlexResponseDto
import com.yfuse.core.data.dto.PlexStreamDto
import com.yfuse.core.data.dto.UserDataDto
import com.yfuse.core.data.dto.toEpisode
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.data.dto.toSeason
import com.yfuse.core.model.Episode
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.PlayTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerSource
import com.yfuse.core.network.normalizeBaseUrl
import com.yfuse.core.sync.SyncedUserItem
import com.yfuse.deviceId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.http.encodeURLParameter
import kotlin.time.TimeSource

private const val PLEX_PRODUCT = "Yfuse"
private const val PLEX_PLATFORM = "Android"
private const val PLEX_SNAPSHOT_PAGE_SIZE = 2_000
private const val PLEX_ARTWORK_PREFIX = "plex:"

/**
 * Native Plex provider boundary.
 *
 * Plex JSON is translated into the existing provider-neutral models, so no screen or playback
 * engine needs a Plex-specific branch. Authentication and API calls use headers; only artwork and
 * media URLs carry the token because Coil, ExoPlayer, mpv and Cast cannot share Ktor's headers.
 */
internal class PlexMediaServerAdapter(
    private val client: HttpClient,
    private val progress: PlaybackProgressProjection = PlaybackProgressProjection(),
) {
    private val durationByItemMs = mutableMapOf<String, Long>()

    suspend fun authenticate(
        baseUrl: String,
        token: String,
    ): Result<AuthedServer> =
        embyApiCall("plex_authenticate") {
            require(token.isNotBlank()) { "Plex Token 不能为空" }
            val url = normalizeBaseUrl(baseUrl)
            val identity = container(url, "/identity", token)
            require(!identity.machineIdentifier.isNullOrBlank()) { "这不是可用的 Plex Media Server" }
            val root = runCatching { container(url, "/", token) }.getOrNull()
            val machineId = requireNotNull(identity.machineIdentifier)
            val accountName = root?.myPlexUsername?.takeIf(String::isNotBlank)
            AuthedServer(
                baseUrl = url,
                serverName =
                    root?.friendlyName?.takeIf(String::isNotBlank)
                        ?: identity.friendlyName?.takeIf(String::isNotBlank)
                        ?: "Plex",
                userId = accountName ?: "plex@$machineId",
                userName = accountName ?: "Plex",
                accessToken = token,
                kind = MediaServerKind.Plex,
            )
        }

    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> =
        embyApiCall("plex_libraries") {
            container(server, "/library/sections")
                .Directory
                .mapNotNull { directory ->
                    val key = directory.key?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val type = directory.type?.lowercase()
                    if (type !in setOf("movie", "show")) return@mapNotNull null
                    MediaLibrary(
                        id = key,
                        name = directory.title?.takeIf(String::isNotBlank) ?: "Plex 媒体库",
                        collectionType = if (type == "show") "tvshows" else "movies",
                    )
                }
        }

    suspend fun itemCounts(server: SavedServer): Result<LibraryCounts> =
        embyApiCall("plex_item_counts") {
            var movies = 0
            var shows = 0
            libraries(server).getOrThrow().forEach { library ->
                val count =
                    container(server, "/library/sections/${library.id}/all") {
                        parameter("X-Plex-Container-Start", 0)
                        parameter("X-Plex-Container-Size", 0)
                    }.effectiveTotal()
                if (library.collectionType == "tvshows") shows += count else movies += count
            }
            LibraryCounts(movieCount = movies, seriesCount = shows)
        }

    suspend fun homeContent(server: SavedServer): Result<HomeContent> =
        embyApiCall("plex_home") {
            val libraries = libraries(server).getOrThrow()
            val rows =
                libraries.map { library ->
                    val page =
                        libraryItems(
                            server = server,
                            libraryId = library.id,
                            sort = LibrarySort.RecentlyAdded,
                            startIndex = 0,
                            limit = 16,
                        ).getOrThrow()
                    HomeRow(
                        libraryId = library.id,
                        title = "${library.name} · 最近添加",
                        items = page.items,
                        totalCount = page.totalCount,
                    )
                }
            val resumeContainer =
                runCatching { container(server, "/hubs/home/continueWatching") }
                    .recoverCatching { container(server, "/library/onDeck") }
                    .getOrNull()
            val resume =
                resumeContainer
                    ?.allMetadata()
                    .orEmpty()
                    .map { progress.project(server, it.toBaseItem(server)).toMediaItem() }
                    .filter { (it.resumePositionTicks ?: 0L) > 0L }
                    .distinctBy(MediaItem::id)
                    .take(20)
            val featured =
                rows
                    .asSequence()
                    .flatMap { it.items.asSequence() }
                    .filter { it.backdropTag != null }
                    .distinctBy(MediaItem::id)
                    .take(12)
                    .toList()
            HomeContent(
                featured = featured,
                resume = resume,
                rows = rows,
                counts =
                    runCatching { itemCounts(server).getOrThrow() }
                        .getOrNull(),
            )
        }

    suspend fun libraryItems(
        server: SavedServer,
        libraryId: String,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
        genre: String? = null,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
        resolution: LibraryResolution = LibraryResolution.All,
    ): Result<LibraryPage> =
        embyApiCall("plex_library_items") {
            val container =
                container(server, "/library/sections/$libraryId/all") {
                    parameter("X-Plex-Container-Start", startIndex.coerceAtLeast(0))
                    parameter("X-Plex-Container-Size", limit.coerceAtLeast(1))
                    parameter("includeGuids", 1)
                    parameter("includeUserState", 1)
                    parameter("includeMedia", 1)
                    parameter("sort", sort.toPlexSort())
                    genre?.takeIf(String::isNotBlank)?.let { parameter("genre", it) }
                    if (resolution == LibraryResolution.FourK) parameter("resolution", "4k")
                }
            val items =
                container
                    .allMetadata()
                    .map { it.toBaseItem(server) }
                    .filter { item -> item.matches(resolution) }
                    .map { progress.project(server, it).toMediaItem() }
            LibraryPage(
                items = items,
                totalCount = container.effectiveTotal().coerceAtLeast(startIndex + items.size),
                startIndex = startIndex,
            )
        }

    suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>> =
        embyApiCall("plex_library_genres") {
            container(server, "/library/sections/$libraryId/genre")
                .Directory
                .mapNotNull { it.title?.takeIf(String::isNotBlank) }
                .distinct()
                .sorted()
        }

    suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
    ): Result<MediaDetail> =
        embyApiCall("plex_item_detail") {
            val item = metadata(server, itemId, includeChildren = false)
            item.duration?.takeIf { it > 0L }?.let { durationByItemMs[itemId] = it }
            progress.project(server, item.toBaseItem(server)).toMediaDetail()
        }

    suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>> =
        embyApiCall("plex_seasons") {
            container(server, "/library/metadata/$seriesId/children") {
                parameter("includeGuids", 1)
            }.allMetadata().map { it.toBaseItem(server).toSeason() }
        }

    suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean,
        seasonNumber: Int?,
    ): Result<List<Episode>> =
        embyApiCall("plex_episodes") {
            val path =
                if (seasonId.isNullOrBlank()) {
                    "/library/metadata/$seriesId/allLeaves"
                } else {
                    "/library/metadata/$seasonId/children"
                }
            container(server, path) {
                parameter("includeGuids", 1)
                parameter("includeMarkers", 1)
                parameter("includeUserState", 1)
                if (includeMediaSources) parameter("includeMedia", 1)
            }.allMetadata()
                .filter { seasonNumber == null || it.parentIndex == seasonNumber }
                .map { item -> progress.project(server, item.toBaseItem(server)).toEpisode() }
        }

    suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget> =
        embyApiCall("plex_play_target") {
            if (detail.type != "Series") {
                PlayTarget(detail.id, detail.resumePositionTicks ?: 0L)
            } else {
                val episodes =
                    episodes(server, detail.id, seasonId = null, includeMediaSources = false, seasonNumber = null)
                        .getOrThrow()
                val selected =
                    episodes.firstOrNull { (it.resumePositionTicks ?: 0L) > 0L }
                        ?: episodes.firstOrNull { !it.played }
                        ?: episodes.firstOrNull()
                        ?: error("Plex 剧集没有可播放的分集")
                PlayTarget(selected.id, selected.resumePositionTicks ?: 0L)
            }
        }

    suspend fun playbackInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String?,
        playSessionId: String,
    ): Result<PlaybackInfoResponseDto> =
        embyApiCall("plex_playback_info") {
            val item = metadata(server, itemId, includeChildren = false)
            item.duration?.takeIf { it > 0L }?.let { durationByItemMs[itemId] = it }
            val sources =
                item
                    .toBaseItem(server, playSessionId)
                    .MediaSources
                    .orEmpty()
                    .let { all ->
                        mediaSourceId
                            ?.takeIf(String::isNotBlank)
                            ?.let { requested -> all.filter { it.Id == requested }.ifEmpty { all } }
                            ?: all
                    }
            PlaybackInfoResponseDto(
                MediaSources = sources,
                PlaySessionId = playSessionId,
            )
        }

    suspend fun reportPlayback(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        stopped: Boolean,
    ): Result<Unit> =
        embyApiCall("plex_playback_timeline") {
            val timeMs = (positionTicks / 10_000L).coerceAtLeast(0L)
            val state =
                if (stopped) {
                    "stopped"
                } else if (isPaused) {
                    "paused"
                } else {
                    "playing"
                }
            client.get("${normalizeBaseUrl(server.baseUrl)}/:/timeline") {
                plexHeaders(server.accessToken, playSessionId)
                parameter("ratingKey", itemId)
                parameter("key", "/library/metadata/$itemId")
                parameter("state", state)
                parameter("time", timeMs)
                durationByItemMs[itemId]?.let { parameter("duration", it) }
            }
            Unit
        }

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> =
        embyApiCall("plex_set_played") {
            val action = if (played) "scrobble" else "unscrobble"
            client.get("${normalizeBaseUrl(server.baseUrl)}/:/$action") {
                plexHeaders(server.accessToken)
                parameter("key", itemId)
                parameter("identifier", "com.plexapp.plugins.library")
            }
            Unit
        }

    suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int,
    ): Result<List<MediaItem>> =
        embyApiCall("plex_on_deck") {
            container(server, "/library/onDeck") {
                parameter("X-Plex-Container-Start", 0)
                parameter("X-Plex-Container-Size", limit)
                parameter("includeGuids", 1)
            }.allMetadata()
                .filter { it.type.equals("episode", ignoreCase = true) }
                .map { progress.project(server, it.toBaseItem(server)).toMediaItem() }
                .take(limit)
        }

    suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int,
    ): Result<List<MediaItem>> =
        embyApiCall("plex_similar") {
            container(server, "/library/metadata/$itemId/related") {
                parameter("includeGuids", 1)
            }.allMetadata()
                .map { progress.project(server, it.toBaseItem(server)).toMediaItem() }
                .distinctBy(MediaItem::id)
                .take(limit)
        }

    suspend fun searchPage(
        server: SavedServer,
        query: String,
        startIndex: Int,
        limit: Int,
        filter: MediaSearchFilter = MediaSearchFilter(),
    ): Result<MediaSearchPage> =
        embyApiCall("plex_search") {
            val response =
                container(
                    server,
                    filter.parentId?.let { "/library/sections/$it/all" } ?: "/library/all",
                ) {
                    parameter("title", query.trim())
                    parameter("includeGuids", 1)
                    parameter("includeUserState", 1)
                    parameter("X-Plex-Container-Start", startIndex.coerceAtLeast(0))
                    parameter("X-Plex-Container-Size", limit.coerceAtLeast(1))
                    filter.productionYear?.let { parameter("year", it) }
                    filter.genre?.takeIf(String::isNotBlank)?.let { parameter("genre", it) }
                    if (filter.played == false) parameter("unwatched", 1)
                    if (filter.resumable) parameter("inProgress", 1)
                    filter.sortBy?.let {
                        parameter("sort", it.toPlexSearchSort(filter.descending))
                    }
                }
            val acceptedTypes =
                filter.includeItemTypes
                    .split(',')
                    .map { it.trim() }
                    .toSet()
            val items =
                response
                    .allMetadata()
                    .filter { it.type?.lowercase() in setOf("movie", "show", "episode") }
                    .map { progress.project(server, it.toBaseItem(server)).toMediaItem() }
                    .filter { it.type in acceptedTypes }
                    .let { rankSearchResults(it, query) }
            MediaSearchPage(items, response.effectiveTotal(), startIndex)
        }

    suspend fun userLibrarySnapshot(
        server: SavedServer,
        includeProgress: Boolean,
    ): Result<List<SyncedUserItem>> =
        embyApiCall("plex_user_library_snapshot") {
            if (!includeProgress) return@embyApiCall emptyList()
            val snapshot = linkedMapOf<String, SyncedUserItem>()
            libraries(server).getOrThrow().forEach { library ->
                var start = 0
                var total = Int.MAX_VALUE
                while (start < total) {
                    val page =
                        container(server, "/library/sections/${library.id}/all") {
                            parameter("includeUserState", 1)
                            parameter("X-Plex-Container-Start", start)
                            parameter("X-Plex-Container-Size", PLEX_SNAPSHOT_PAGE_SIZE)
                        }
                    val values = page.allMetadata()
                    if (values.isEmpty()) break
                    values.forEach { item ->
                        val offset = item.viewOffset?.coerceAtLeast(0L) ?: 0L
                        val played = (item.viewCount ?: 0) > 0
                        if (offset > 0L || played) {
                            val id = item.ratingKey ?: return@forEach
                            snapshot[id] =
                                SyncedUserItem(
                                    id = id,
                                    title = item.title.orEmpty(),
                                    favorite = false,
                                    played = played,
                                    positionTicks = if (played) 0L else offset * 10_000L,
                                    dateModified = item.updatedAt?.toString(),
                                )
                        }
                    }
                    total = page.effectiveTotal()
                    start += values.size
                }
            }
            snapshot.values.toList()
        }

    suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> =
        embyApiCall("plex_find_tmdb") {
            val expected = if (mediaType == "tv") "show" else "movie"
            allTopLevelMetadata(server, expected)
                .firstOrNull { it.providerIds()["Tmdb"] == tmdbId.toString() }
                ?.let { progress.project(server, it.toBaseItem(server)).toMediaItem() }
        }

    suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?> =
        embyApiCall("plex_find_media_key") {
            val provider = mediaKey.substringBefore(':').lowercase()
            val value = mediaKey.substringAfter(':', missingDelimiterValue = "")
            if (provider == "plex" && value.isNotBlank()) {
                return@embyApiCall runCatching { itemDetail(server, value).getOrThrow() }
                    .getOrNull()
                    ?.let { detail ->
                        MediaItem(
                            id = detail.id,
                            title = detail.title,
                            subtitle = detail.year?.toString(),
                            type = detail.type,
                            posterItemId = detail.posterItemId,
                            posterTag = detail.posterTag,
                            backdropItemId = detail.backdropItemId,
                            backdropTag = detail.backdropTag,
                            playedPercentage = null,
                            resumePositionTicks = detail.resumePositionTicks,
                            year = detail.year,
                            overview = detail.overview,
                            communityRating = detail.communityRating,
                            providerIds = detail.providerIds,
                            played = detail.played,
                        )
                    }
            }
            val providerName =
                when (provider) {
                    "tmdb" -> "Tmdb"
                    "imdb" -> "Imdb"
                    "tvdb" -> "Tvdb"
                    else -> return@embyApiCall null
                }
            allTopLevelMetadata(server)
                .firstOrNull { it.providerIds()[providerName] == value }
                ?.let { progress.project(server, it.toBaseItem(server)).toMediaItem() }
        }

    suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>> =
        embyApiCall("plex_series_provider_index") {
            buildMap {
                allTopLevelMetadata(server, "show").forEach { item ->
                    val id = item.ratingKey ?: return@forEach
                    item.providerIds().forEach { (provider, value) ->
                        put("${provider.lowercase()}:$value", id)
                    }
                }
            }
        }

    suspend fun seriesIdentityCatalog(server: SavedServer): Result<List<LibrarySeriesIdentity>> =
        embyApiCall("plex_series_identity_catalog") {
            allTopLevelMetadata(server, "show").mapNotNull { item ->
                val id = item.ratingKey ?: return@mapNotNull null
                val title = item.title?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                LibrarySeriesIdentity(
                    itemId = id,
                    title = title,
                    year = item.year,
                    providerIds = item.providerIds(),
                    dateCreated = item.addedAt?.toString(),
                    isFavorite = false,
                )
            }
        }

    suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>> =
        embyApiCall("plex_movie_provider_index") {
            buildMap {
                allTopLevelMetadata(server, "movie").forEach { item ->
                    val id = item.ratingKey ?: return@forEach
                    val hit = ProviderHit(id, (item.viewCount ?: 0) > 0)
                    item.providerIds().forEach { (provider, value) ->
                        put("${provider.lowercase()}:$value", hit)
                    }
                    if (item.title != null && item.year != null) {
                        putIfAbsent("title:${normalizeIdentityTitle(item.title)}:${item.year}", hit)
                    }
                }
            }
        }

    suspend fun compareSource(
        server: SavedServer,
        currentServerId: String?,
        title: String,
        tmdbId: Int?,
        mediaType: String?,
        year: Int?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): ServerSource {
        val lookup =
            if (tmdbId != null && mediaType != null) {
                findByTmdbId(server, tmdbId, mediaType)
            } else {
                searchPage(server, title, startIndex = 0, limit = 8).map { page ->
                    page.items.firstOrNull { item ->
                        item.title.equals(title, ignoreCase = true) &&
                            (year == null || item.year == year) &&
                            when (mediaType) {
                                "tv" -> item.type == "Series"
                                "movie" -> item.type == "Movie"
                                else -> true
                            }
                    } ?: page.items.firstOrNull()
                }
            }
        var item = lookup.getOrNull()
        val series = item
        if (
            series?.type == "Series" &&
            seasonNumber != null &&
            episodeNumber != null
        ) {
            item =
                episodes(
                    server,
                    seriesId = series.id,
                    seasonId = null,
                    includeMediaSources = true,
                    seasonNumber = seasonNumber,
                ).getOrNull()
                    ?.firstOrNull { it.indexNumber == episodeNumber }
                    ?.let { episode ->
                        MediaItem(
                            id = episode.id,
                            title = episode.name,
                            subtitle = null,
                            type = "Episode",
                            posterItemId = series.id,
                            posterTag = null,
                            backdropItemId = series.id,
                            backdropTag = null,
                            playedPercentage = episode.playedPercentage,
                            resumePositionTicks = episode.resumePositionTicks,
                            providerIds = episode.providerIds,
                            played = episode.played,
                        )
                    }
        }
        val detail = item?.let { itemDetail(server, it.id).getOrNull() }
        return ServerSource(
            serverId = server.id,
            serverName = server.serverName,
            isCurrent = server.id == currentServerId,
            source = detail?.source,
            reachable = lookup.isSuccess,
            itemId = item?.id,
        )
    }

    suspend fun probe(server: SavedServer): Result<Long> = probeAddress(server.baseUrl, server.accessToken)

    suspend fun probeAddress(
        baseUrl: String,
        token: String,
    ): Result<Long> =
        embyApiCall("plex_probe") {
            val mark = TimeSource.Monotonic.markNow()
            container(normalizeBaseUrl(baseUrl), "/identity", token)
            mark.elapsedNow().inWholeMilliseconds
        }

    suspend fun stopTranscoding(
        server: SavedServer,
        playSessionId: String,
    ): Result<Unit> =
        if (playSessionId.isBlank()) {
            Result.success(Unit)
        } else {
            embyApiCall("plex_stop_transcode") {
                client.get("${normalizeBaseUrl(server.baseUrl)}/video/:/transcode/universal/stop") {
                    plexHeaders(server.accessToken, playSessionId)
                    parameter("session", playSessionId)
                }
                Unit
            }
        }

    private suspend fun metadata(
        server: SavedServer,
        itemId: String,
        includeChildren: Boolean,
    ): PlexMetadataDto =
        container(server, "/library/metadata/$itemId") {
            parameter("includeGuids", 1)
            parameter("includeMarkers", 1)
            parameter("includeUserState", 1)
            parameter("includeMedia", 1)
            if (includeChildren) parameter("includeChildren", 1)
        }.allMetadata().firstOrNull() ?: error("Plex 中找不到该媒体")

    private suspend fun allTopLevelMetadata(
        server: SavedServer,
        expectedType: String? = null,
    ): List<PlexMetadataDto> {
        val values = mutableListOf<PlexMetadataDto>()
        libraries(server).getOrThrow().forEach { library ->
            val libraryType = if (library.collectionType == "tvshows") "show" else "movie"
            if (expectedType != null && libraryType != expectedType) return@forEach
            var start = 0
            var total = Int.MAX_VALUE
            while (start < total) {
                val page =
                    container(server, "/library/sections/${library.id}/all") {
                        parameter("includeGuids", 1)
                        parameter("includeUserState", 1)
                        parameter("X-Plex-Container-Start", start)
                        parameter("X-Plex-Container-Size", PLEX_SNAPSHOT_PAGE_SIZE)
                    }
                val items = page.allMetadata()
                if (items.isEmpty()) break
                values += items
                total = page.effectiveTotal()
                start += items.size
            }
        }
        return values
    }

    private suspend fun container(
        server: SavedServer,
        path: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): PlexMediaContainerDto = container(server.baseUrl, path, server.accessToken, block)

    private suspend fun container(
        baseUrl: String,
        path: String,
        token: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): PlexMediaContainerDto =
        client
            .get("${normalizeBaseUrl(baseUrl)}${path.withLeadingSlash()}") {
                plexHeaders(token)
                block()
            }.body<PlexResponseDto>()
            .MediaContainer

    private fun HttpRequestBuilder.plexHeaders(
        token: String,
        sessionId: String? = null,
    ) {
        accept(ContentType.Application.Json)
        header("X-Plex-Token", token)
        header("X-Plex-Client-Identifier", deviceId())
        header("X-Plex-Product", PLEX_PRODUCT)
        header("X-Plex-Platform", PLEX_PLATFORM)
        header("X-Plex-Device-Name", PLEX_PRODUCT)
        sessionId?.takeIf(String::isNotBlank)?.let {
            header("X-Plex-Session-Identifier", it)
        }
    }

    private fun PlexMetadataDto.toBaseItem(
        server: SavedServer,
        playSessionId: String? = null,
    ): BaseItemDto {
        val id = ratingKey ?: key?.substringAfterLast('/') ?: error("Plex 媒体缺少 ratingKey")
        val normalizedType = type.toEmbyType()
        val isEpisode = normalizedType == "Episode"
        val posterPath =
            if (isEpisode) grandparentThumb ?: parentThumb ?: thumb else thumb
        val backdropPath = art ?: parentArt ?: grandparentArt
        val runtimeMs = duration?.takeIf { it > 0L }
        val offsetMs = viewOffset?.coerceAtLeast(0L)?.takeIf { it > 0L }
        val played = (viewCount ?: 0) > 0
        val sources =
            Media.mapIndexedNotNull { mediaIndex, media ->
                media.toMediaSource(server, id, mediaIndex, playSessionId)
            }
        return BaseItemDto(
            Id = id,
            Name = title,
            Type = normalizedType,
            ProductionYear = year,
            IndexNumber = index,
            ParentIndexNumber = parentIndex,
            SeriesName = if (isEpisode) grandparentTitle else null,
            SeriesId = if (isEpisode) grandparentRatingKey else null,
            SeriesPrimaryImageTag = if (isEpisode) grandparentThumb.plexArtworkTag() else null,
            SeasonId = if (isEpisode) parentRatingKey else null,
            ParentBackdropItemId = grandparentRatingKey ?: parentRatingKey,
            ParentBackdropImageTags = listOfNotNull((parentArt ?: grandparentArt).plexArtworkTag()),
            Overview = summary,
            Genres = Genre.mapNotNull { it.tag.takeIf(String::isNotBlank) },
            RunTimeTicks = runtimeMs?.timesSafely(10_000L),
            CommunityRating = audienceRating ?: rating,
            OfficialRating = contentRating,
            People =
                Role.mapNotNull { role ->
                    role.tag.takeIf(String::isNotBlank)?.let { name ->
                        PersonDto(
                            Id = role.key ?: role.id?.toString() ?: name,
                            Name = name,
                            Role = role.role,
                            Type = "Actor",
                            PrimaryImageTag = role.thumb.plexArtworkTag(),
                        )
                    }
                },
            ImageTags = posterPath.plexArtworkTag()?.let { mapOf("Primary" to it) },
            PremiereDate = originallyAvailableAt,
            BackdropImageTags = listOfNotNull(backdropPath.plexArtworkTag()),
            UserData =
                UserDataDto(
                    PlayedPercentage =
                        if (!played && offsetMs != null && runtimeMs != null) {
                            (offsetMs.toDouble() * 100.0 / runtimeMs.toDouble()).coerceIn(0.0, 100.0)
                        } else {
                            null
                        },
                    PlaybackPositionTicks = if (played) null else offsetMs?.timesSafely(10_000L),
                    LastPlayedDate = null,
                    Played = played,
                    IsFavorite = false,
                ),
            MediaSources = sources,
            ProviderIds = providerIds(),
            DateModified = updatedAt?.toString(),
            Chapters = markers(),
        )
    }

    private fun PlexMediaDto.toMediaSource(
        server: SavedServer,
        itemId: String,
        mediaIndex: Int,
        playSessionId: String?,
    ): MediaSourceDto? {
        val part = Part.firstOrNull() ?: return null
        val partPath = part.key?.takeIf(String::isNotBlank) ?: return null
        val sourceId = id?.toString() ?: part.id?.toString() ?: "$itemId:$mediaIndex"
        val directUrl = plexAuthenticatedUrl(server.baseUrl, partPath, server.accessToken)
        val transcodeUrl =
            playSessionId?.let {
                plexTranscodeUrl(server, itemId, mediaIndex, it)
            }
        return MediaSourceDto(
            Id = sourceId,
            Name = videoResolution?.uppercase() ?: part.file?.substringAfterLast('/') ?: container?.uppercase(),
            Container = part.container ?: container,
            VideoType = "VideoFile",
            Size = part.size,
            Bitrate = bitrate.toBitsPerSecond(),
            Path = part.file,
            MediaStreams = streams(part),
            // Yfuse's generated DirectPlay URL is Emby-shaped. Force the negotiated Plex part
            // through DirectStream so the actual `/library/parts/...` address is selected.
            SupportsDirectPlay = false,
            SupportsDirectStream = true,
            SupportsTranscoding = transcodeUrl != null,
            DirectStreamUrl = directUrl,
            AddApiKeyToDirectStreamUrl = false,
            TranscodingUrl = transcodeUrl,
        )
    }

    private fun PlexMediaDto.streams(part: PlexPartDto): List<MediaStreamDto> {
        val declared = part.Stream
        if (declared.isNotEmpty()) return declared.map(PlexStreamDto::toMediaStream)
        return buildList {
            add(
                MediaStreamDto(
                    Index = 0,
                    Type = "Video",
                    Width = width,
                    Height = height,
                    Codec = videoCodec,
                    BitRate = bitrate.toBitsPerSecond(),
                    Profile = videoProfile,
                    AverageFrameRate = videoFrameRate.toFrameRate(),
                ),
            )
            audioCodec?.let {
                add(
                    MediaStreamDto(
                        Index = 1,
                        Type = "Audio",
                        Codec = it,
                        Channels = audioChannels,
                        Profile = audioProfile,
                    ),
                )
            }
        }
    }

    private fun PlexStreamDto.toMediaStream(): MediaStreamDto {
        val titleValue = extendedDisplayTitle ?: displayTitle ?: title
        val range =
            when {
                doviPresent == true || doviProfile != null || titleValue.mentions("Dolby Vision") -> "Dolby Vision"
                colorTrc.mentions("smpte2084") || titleValue.mentions("HDR") -> "HDR10"
                else -> null
            }
        return MediaStreamDto(
            Index = index,
            Type =
                when (streamType) {
                    1 -> "Video"
                    2 -> "Audio"
                    3 -> "Subtitle"
                    else -> null
                },
            Height = height,
            Width = width,
            VideoRange = range,
            Codec = codec,
            Language = languageCode ?: language,
            Title = title,
            DisplayTitle = titleValue,
            DisplayLanguage = language,
            Channels = channels,
            IsForced = forced,
            IsDefault = default ?: selected,
            IsExternal = key != null,
            BitRate = bitrate.toBitsPerSecond(),
            SampleRate = samplingRate,
            BitDepth = bitDepth,
            Profile = profile ?: titleValue?.takeIf { it.mentions("Atmos") },
            Level = level,
            ColorSpace = colorSpace,
            ColorPrimaries = colorPrimaries,
            DvProfile = doviProfile,
            DvLevel = doviLevel,
            RpuPresentFlag = doviRpuPresent?.toPresenceFlag(),
            ElPresentFlag = doviEnhancementLayerPresent?.toPresenceFlag(),
            BlPresentFlag = doviBaseLayerPresent?.toPresenceFlag(),
        )
    }

    private fun PlexMetadataDto.providerIds(): Map<String, String> =
        buildMap {
            (Guid.map { it.id } + listOfNotNull(guid)).forEach { raw ->
                val normalized = raw.substringAfterLast("/").substringBefore('?')
                val scheme = raw.substringBefore("://", missingDelimiterValue = "").substringAfterLast('.').lowercase()
                val key =
                    when (scheme) {
                        "tmdb" -> "Tmdb"
                        "imdb" -> "Imdb"
                        "tvdb" -> "Tvdb"
                        else -> null
                    }
                if (key != null && normalized.isNotBlank()) put(key, normalized)
            }
        }

    private fun PlexMetadataDto.markers(): List<ChapterDto> =
        buildList {
            Marker.forEach { marker ->
                val start = marker.startTimeOffset?.coerceAtLeast(0L) ?: return@forEach
                when (marker.type?.lowercase()) {
                    "intro" -> {
                        add(ChapterDto(start.timesSafely(10_000L), "IntroStart"))
                        marker.endTimeOffset?.takeIf { it > start }?.let {
                            add(ChapterDto(it.timesSafely(10_000L), "IntroEnd"))
                        }
                    }
                    "credits" -> add(ChapterDto(start.timesSafely(10_000L), "CreditsStart"))
                }
            }
        }

    private fun plexTranscodeUrl(
        server: SavedServer,
        itemId: String,
        mediaIndex: Int,
        playSessionId: String,
    ): String {
        val base = normalizeBaseUrl(server.baseUrl)
        return "$base/video/:/transcode/universal/start.m3u8" +
            "?path=${"/library/metadata/$itemId".encodeURLParameter()}" +
            "&mediaIndex=$mediaIndex&partIndex=0&protocol=hls" +
            "&directPlay=0&directStream=1&fastSeek=1&copyts=1" +
            "&videoQuality=100&videoResolution=3840x2160&maxVideoBitrate=120000" +
            "&session=${playSessionId.encodeURLParameter()}" +
            "&X-Plex-Client-Identifier=${deviceId().encodeURLParameter()}" +
            "&X-Plex-Token=${server.accessToken.encodeURLParameter()}"
    }
}

internal fun String?.plexArtworkTag(): String? =
    this
        ?.trim()
        ?.takeIf { it.startsWith('/') && !it.startsWith("//") }
        ?.let { "$PLEX_ARTWORK_PREFIX$it" }

internal fun String?.plexArtworkPath(): String? =
    this?.takeIf { it.startsWith(PLEX_ARTWORK_PREFIX) }?.removePrefix(PLEX_ARTWORK_PREFIX)

internal fun plexAuthenticatedUrl(
    baseUrl: String,
    rawPath: String,
    token: String,
): String {
    val path = rawPath.trim()
    require(path.startsWith('/') && !path.startsWith("//")) {
        "Plex media path must stay on the authenticated server"
    }
    val absolute = "${normalizeBaseUrl(baseUrl)}$path"
    val separator = if ('?' in absolute) '&' else '?'
    return "$absolute${separator}X-Plex-Token=${token.encodeURLParameter()}"
}

private fun PlexMediaContainerDto.allMetadata(): List<PlexMetadataDto> =
    Metadata + Directory + Hub.flatMap { it.Metadata }

private fun PlexMediaContainerDto.effectiveTotal(): Int = totalSize ?: size.takeIf { it > 0 } ?: allMetadata().size

private fun LibrarySort.toPlexSort(): String =
    when (this) {
        LibrarySort.RecentlyAdded -> "addedAt:desc"
        LibrarySort.Name -> "titleSort:asc"
        LibrarySort.Year -> "year:desc"
        LibrarySort.Rating -> "audienceRating:desc"
    }

private fun String.toPlexSearchSort(descending: Boolean): String {
    val key =
        when (substringBefore(',')) {
            "DateCreated" -> "addedAt"
            "ProductionYear", "PremiereDate" -> "year"
            "CommunityRating" -> "audienceRating"
            "SortName" -> "titleSort"
            else -> "titleSort"
        }
    return "$key:${if (descending) "desc" else "asc"}"
}

private fun BaseItemDto.matches(resolution: LibraryResolution): Boolean {
    if (resolution == LibraryResolution.All) return true
    val sources = MediaSources.orEmpty()
    val videos = sources.flatMap { it.MediaStreams.orEmpty() }.filter { it.Type == "Video" }
    return when (resolution) {
        LibraryResolution.All -> true
        LibraryResolution.FourK -> videos.any { (it.Width ?: 0) >= 2_560 || (it.Height ?: 0) >= 1_440 }
        LibraryResolution.DolbyVision ->
            videos.any {
                it.DvProfile != null ||
                    it.VideoRange.mentions("Dolby Vision") ||
                    it.Codec.mentions("dovi")
            }
        LibraryResolution.BluRay ->
            sources.any {
                it.Container.mentions("bluray") ||
                    it.Path.mentions("bdmv") ||
                    it.Path.mentions("blu-ray")
            }
        LibraryResolution.Hd -> videos.any { (it.Height ?: 0) >= 720 }
        LibraryResolution.Sd -> videos.any { (it.Height ?: Int.MAX_VALUE) < 720 }
    }
}

private fun String?.toEmbyType(): String =
    when (this?.lowercase()) {
        "movie" -> "Movie"
        "show" -> "Series"
        "season" -> "Season"
        "episode" -> "Episode"
        else -> this.orEmpty()
    }

private fun String.withLeadingSlash(): String = if (startsWith('/')) this else "/$this"

private fun Int?.toBitsPerSecond(): Int? =
    this?.takeIf { it > 0 }?.let { value ->
        if (value > Int.MAX_VALUE / 1_000) Int.MAX_VALUE else value * 1_000
    }

private fun String?.toFrameRate(): Double? {
    val value = this?.lowercase() ?: return null
    return when {
        value.startsWith("24") -> 24.0
        value.startsWith("25") || value == "pal" -> 25.0
        value.startsWith("30") || value == "ntsc" -> 30.0
        value.startsWith("50") -> 50.0
        value.startsWith("60") -> 60.0
        else -> value.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
    }
}

private fun String?.mentions(value: String): Boolean = this?.contains(value, ignoreCase = true) == true

private fun Boolean.toPresenceFlag(): Int = if (this) 1 else 0

private fun Long.timesSafely(multiplier: Long): Long =
    if (this > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else this * multiplier
