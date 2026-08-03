package com.yfuse.core.data

import com.yfuse.core.data.dto.AuthRequestDto
import com.yfuse.core.data.dto.AuthResultDto
import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.PublicInfoDto
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.data.dto.PlaybackReportDto
import com.yfuse.core.data.dto.PlaylistCreatedDto
import com.yfuse.core.data.dto.ViewsDto
import com.yfuse.core.data.dto.toEpisode
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.data.dto.toPerson
import com.yfuse.core.data.dto.toSeason
import com.yfuse.core.data.dto.toSourceInfo
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.Season
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.PlayTarget
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.SourceInfo
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.normalizeBaseUrl
import com.yfuse.core.sync.SyncedUserItem
import com.yfuse.core.sync.parseEpisodeWatchKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope

/** Result of a successful authentication, ready to persist as a [SavedServer]. */
data class AuthedServer(
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
) {
    fun toSavedServer(serverName: String? = null) = SavedServer(
        id = SavedServer.idOf(baseUrl, userId),
        baseUrl = baseUrl,
        serverName = serverName ?: this.serverName,
        userId = userId,
        userName = userName,
        accessToken = accessToken,
    )
}

/**
 * Talks to Emby. Stateless with respect to sessions: every call targets an
 * explicit server. Failures carry an [EmbyErrorException].
 */
/** One library item found by provider id: what to open, and whether it was watched. */
data class ProviderHit(val itemId: String, val played: Boolean)

class EmbyRepository(private val client: HttpClient) {

    suspend fun publicUsers(baseUrl: String): Result<List<PublicUserDto>> = call("public_users") {
        client.get("${normalizeBaseUrl(baseUrl)}/Users/Public").body()
    }

    suspend fun authenticate(baseUrl: String, username: String, password: String): Result<AuthedServer> =
        call("authenticate") {
        val url = normalizeBaseUrl(baseUrl)
        val auth: AuthResultDto = client.post("$url/Users/AuthenticateByName") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequestDto(Username = username, Pw = password))
        }.body()
        val serverInfo = runCatching {
            client.get("$url/System/Info/Public").body<PublicInfoDto>().ServerName
        }.onFailure {
            AppLog.warning(
                category = "emby",
                event = "server_info_degraded",
                message = "Authentication succeeded but public server info failed",
                throwable = it,
            )
        }
        AuthedServer(url, serverInfo.getOrNull() ?: url, auth.User.Id, auth.User.Name, auth.AccessToken)
    }

    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> =
        call("libraries") { fetchViews(server) }

    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> = call("set_favorite") {
        val url = "${server.baseUrl}/Users/${server.userId}/FavoriteItems/$itemId"
        if (favorite) {
            client.post(url) { header("X-Emby-Token", server.accessToken) }
        } else {
            client.delete(url) { header("X-Emby-Token", server.accessToken) }
        }
        Unit
    }

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> = call("set_played") {
        val url = "${server.baseUrl}/Users/${server.userId}/PlayedItems/$itemId"
        if (played) {
            client.post(url) { header("X-Emby-Token", server.accessToken) }
        } else {
            client.delete(url) { header("X-Emby-Token", server.accessToken) }
        }
        Unit
    }

    /**
     * Emby has no special “watch later” flag. It is represented by a real
     * user playlist so it follows the account across clients and servers.
     */
    suspend fun addToWatchLater(server: SavedServer, itemId: String): Result<Unit> =
        call("add_to_watch_later") {
        val playlists: ItemsResponseDto =
            client.get("${server.baseUrl}/Users/${server.userId}/Items") {
                header("X-Emby-Token", server.accessToken)
                parameter("Recursive", true)
                parameter("IncludeItemTypes", "Playlist")
                parameter("SearchTerm", "稍后观看")
                parameter("Limit", 20)
            }.body()
        val playlistId = playlists.Items
            .firstOrNull { it.Name.equals("稍后观看", ignoreCase = true) }
            ?.Id
        if (playlistId != null) {
            client.post("${server.baseUrl}/Playlists/$playlistId/Items") {
                header("X-Emby-Token", server.accessToken)
                parameter("Ids", itemId)
                parameter("UserId", server.userId)
            }
        } else {
            val created: PlaylistCreatedDto = client.post("${server.baseUrl}/Playlists") {
                header("X-Emby-Token", server.accessToken)
                parameter("UserId", server.userId)
                parameter("Name", "稍后观看")
                parameter("Ids", itemId)
            }.body()
            require(!created.Id.isNullOrBlank()) { "playlist was not created" }
        }
        Unit
    }

    suspend fun reportPlaybackStarted(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ): Result<Unit> = reportPlayback(
        server = server,
        path = "/Sessions/Playing",
        itemId = itemId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
    )

    suspend fun reportPlaybackProgress(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ): Result<Unit> = reportPlayback(
        server = server,
        path = "/Sessions/Playing/Progress",
        itemId = itemId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
    )

    suspend fun reportPlaybackStopped(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ): Result<Unit> = reportPlayback(
        server = server,
        path = "/Sessions/Playing/Stopped",
        itemId = itemId,
        playSessionId = playSessionId,
        positionTicks = positionTicks,
        isPaused = isPaused,
    )

    /** Aggregates the home screen: continue-watching, latest-per-library, featured. */
    suspend fun homeContent(server: SavedServer): Result<HomeContent> = call("home_content") {
        coroutineScope {
            val views = fetchViews(server)
            // A single library (or the resume row) failing must not blank the
            // whole home screen — degrade to an empty row instead.
            val resumeDeferred = async {
                runCatching { fetchResume(server) }
                    .onFailure {
                        AppLog.warning(
                            category = "emby",
                            event = "home_section_degraded",
                            message = "Continue-watching section failed and was omitted",
                            throwable = it,
                            attributes = mapOf(
                                "serverId" to server.id,
                                "section" to "resume",
                            ),
                        )
                    }
                    .getOrDefault(emptyList())
            }
            val rowDeferred = views.map { view ->
                async {
                    val items = runCatching { fetchLatest(server, view.id) }
                        .onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "home_section_degraded",
                                message = "Library latest-items section failed and was omitted",
                                throwable = it,
                                attributes = mapOf(
                                    "serverId" to server.id,
                                    "section" to "latest",
                                    "libraryId" to view.id,
                                ),
                            )
                        }
                        .getOrDefault(emptyList())
                    // The chip shows the library's real size, not the loaded page.
                    val total = runCatching { fetchLibraryCount(server, view.id) }
                        .onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "library_count_degraded",
                                message = "Library count failed; loaded item count used as fallback",
                                throwable = it,
                                attributes = mapOf(
                                    "serverId" to server.id,
                                    "libraryId" to view.id,
                                ),
                            )
                        }
                        .getOrDefault(items.size)
                    HomeRow(view.id, view.name, items, total)
                }
            }
            val resume = resumeDeferred.await()
            val rows = rowDeferred.awaitAll().filter { it.items.isNotEmpty() }
            val featured = (resume + rows.flatMap { it.items })
                .filter { it.backdropTag != null }
                .distinctBy { it.id }
                .take(6)
            HomeContent(featured = featured, resume = resume, rows = rows)
        }
    }

    /** All movies/series in a library, for the "see all" grid. */
    suspend fun libraryItems(server: SavedServer, libraryId: String): Result<List<MediaItem>> =
        call("library_items") {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items") {
            header("X-Emby-Token", server.accessToken)
            parameter("ParentId", libraryId)
            parameter("Recursive", true)
            parameter("IncludeItemTypes", "Movie,Series")
            parameter("SortBy", "SortName")
            parameter("SortOrder", "Ascending")
            parameter(
                "Fields",
                "ProductionYear,BackdropImageTags,ParentBackdropItemId," +
                    "ParentBackdropImageTags,SeriesPrimaryImageTag",
            )
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
            parameter("Limit", 120)
        }.body()
        dto.Items.map { it.toMediaItem() }
    }

    /** Real Emby recommendations used by the detail page's compact poster rail. */
    suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int = 12,
    ): Result<List<MediaItem>> = call("similar_items") {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Items/$itemId/Similar") {
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
        dto.Items.map { it.toMediaItem() }
    }

    /**
     * Resolves what to actually play for a detail item: movies/episodes play
     * themselves; a series plays its "next up" episode (falling back to the
     * first episode), carrying that episode's resume position.
     */
    suspend fun resolvePlayTarget(server: SavedServer, detail: MediaDetail): Result<PlayTarget> =
        call("resolve_play_target") {
        if (detail.type != "Series") {
            PlayTarget(detail.id, detail.resumePositionTicks ?: 0L)
        } else {
            val episode = fetchNextUp(server, detail.id) ?: fetchFirstEpisode(server, detail.id)
            requireNotNull(episode) { "no episodes" }
            PlayTarget(episode.Id, episode.UserData?.PlaybackPositionTicks ?: 0L)
        }
    }

    private suspend fun fetchNextUp(server: SavedServer, seriesId: String): BaseItemDto? {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/NextUp") {
            header("X-Emby-Token", server.accessToken)
            parameter("UserId", server.userId)
            parameter("SeriesId", seriesId)
            parameter("Limit", 1)
        }.body()
        return dto.Items.firstOrNull()
    }

    private suspend fun fetchFirstEpisode(server: SavedServer, seriesId: String): BaseItemDto? {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/$seriesId/Episodes") {
            header("X-Emby-Token", server.accessToken)
            parameter("UserId", server.userId)
            parameter("Limit", 1)
        }.body()
        return dto.Items.firstOrNull()
    }

    /** Title search, used by the search tab and to match TMDB picks to the library. */
    suspend fun search(server: SavedServer, query: String, limit: Int = 24): Result<List<MediaItem>> =
        call("search") {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items") {
            header("X-Emby-Token", server.accessToken)
            parameter("SearchTerm", query)
            parameter("Recursive", true)
            parameter("IncludeItemTypes", "Movie,Series")
            parameter(
                "Fields",
                "ProductionYear,Overview,ProviderIds,BackdropImageTags," +
                    "ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag",
            )
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
            parameter("Limit", limit)
        }.body()
        dto.Items.map { it.toMediaItem() }
    }

    /** Complete user-state snapshot used by the real multi-server sync coordinator. */
    suspend fun userLibrarySnapshot(server: SavedServer): Result<List<SyncedUserItem>> =
        call("user_library_snapshot") {
        val dto: ItemsResponseDto =
            client.get("${server.baseUrl}/Users/${server.userId}/Items") {
                header("X-Emby-Token", server.accessToken)
                parameter("Recursive", true)
                parameter("IncludeItemTypes", "Movie,Series,Episode")
                parameter("Fields", "UserData,DateModified")
                parameter("EnableImages", false)
                parameter("Limit", 10_000)
            }.body()
        dto.Items.map { item ->
            SyncedUserItem(
                id = item.Id,
                title = item.Name.orEmpty(),
                favorite = item.UserData?.IsFavorite == true,
                played = item.UserData?.Played == true,
                positionTicks = item.UserData?.PlaybackPositionTicks ?: 0L,
                dateModified = item.DateModified,
            )
        }
    }

    /** Precise TMDB-to-Emby match, avoiding localized-title mismatches. */
    suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> = call("find_item_by_provider") {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items") {
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
    ): Result<MediaItem?> = call("find_item_by_media_key") {
        // `tmdb:1399/s2e5` — the show is identified by provider id, the episode by its
        // place in it. Resolved in two steps because that's how Emby indexes it: nothing
        // queries "episode 5 of the show with this Tmdb id" directly.
        parseEpisodeWatchKey(mediaKey)?.let { coordinate ->
            val series = findByMediaKey(server, coordinate.seriesKey).getOrNull()
                ?: return@call null
            val dto: ItemsResponseDto = client.get(
                "${server.baseUrl}/Shows/${series.id}/Episodes",
            ) {
                header("X-Emby-Token", server.accessToken)
                parameter("UserId", server.userId)
                parameter("Season", coordinate.seasonNumber)
                parameter("Fields", "ProductionYear,Overview,ProviderIds")
            }.body()
            return@call dto.Items
                .firstOrNull { it.IndexNumber == coordinate.episodeNumber }
                ?.toMediaItem()
        }
        val provider = mediaKey.substringBefore(':', "")
        val value = mediaKey.substringAfter(':', "")
        if (provider.isBlank() || value.isBlank()) {
            return@call null
        }
        if (provider.equals("emby", ignoreCase = true)) {
            val dto: BaseItemDto = client.get(
                "${server.baseUrl}/Users/${server.userId}/Items/$value",
            ) {
                header("X-Emby-Token", server.accessToken)
                parameter("Fields", "ProductionYear,Overview,ProviderIds")
            }.body()
            return@call dto.toMediaItem()
        }
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items") {
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

    /** Full detail for a single item. Episodes inherit the series' cast. */
    suspend fun itemDetail(server: SavedServer, itemId: String): Result<MediaDetail> =
        call("item_detail") {
        val dto: BaseItemDto = client.get("${server.baseUrl}/Users/${server.userId}/Items/$itemId") {
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
        val detail = dto.toMediaDetail()

        // Emby returns no cast on episodes; borrow the series' cast instead.
        if (detail.type == "Episode" && detail.people.isEmpty() && detail.seriesId != null) {
            val seriesResult = runCatching {
                client.get("${server.baseUrl}/Users/${server.userId}/Items/${detail.seriesId}") {
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

    /**
     * 跨服务器片源对比: looks the title up on every saved server and reports which
     * ones carry it, with the primary source's specs. Per-server failures degrade to
     * "unreachable" rather than failing the whole comparison.
     */
    suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int? = null,
        mediaType: String? = null,
        year: Int? = null,
    ): List<ServerSource> = coroutineScope {
        servers.map { server ->
            async {
                val lookup = runCatching {
                    suspend fun query(providerMatch: Boolean): ItemsResponseDto =
                        client.get("${server.baseUrl}/Users/${server.userId}/Items") {
                            header("X-Emby-Token", server.accessToken)
                            parameter("Recursive", true)
                            parameter(
                                "IncludeItemTypes",
                                when (mediaType) {
                                    "tv" -> "Series"
                                    "movie" -> "Movie"
                                    else -> "Movie,Series"
                                },
                            )
                            if (providerMatch && tmdbId != null) {
                                parameter("AnyProviderIdEquals", "tmdb.$tmdbId")
                            } else {
                                parameter("SearchTerm", title)
                            }
                            parameter("Fields", "MediaSources,ProductionYear,ProviderIds")
                            parameter("Limit", 5)
                        }.body()
                    val providerItems = if (tmdbId != null) query(providerMatch = true).Items
                    else emptyList()
                    val candidates = providerItems.ifEmpty {
                        query(providerMatch = false).Items
                    }
                    candidates.firstOrNull { candidate ->
                        val titleMatches = candidate.Name.equals(title, ignoreCase = true)
                        val yearMatches = year == null || candidate.ProductionYear == year
                        val typeMatches = when (mediaType) {
                            "tv" -> candidate.Type == "Series"
                            "movie" -> candidate.Type == "Movie"
                            else -> true
                        }
                        titleMatches && yearMatches && typeMatches
                    } ?: candidates.firstOrNull()
                }
                lookup.onFailure {
                    AppLog.warning(
                        category = "emby",
                        event = "source_lookup_failed",
                        message = "Cross-server source lookup failed",
                        throwable = it,
                        attributes = mapOf("serverId" to server.id),
                    )
                }
                val item = lookup.getOrNull()
                val source = item?.let {
                    runCatching { fetchComparableSource(server, it) }
                        .onFailure { error ->
                            AppLog.warning(
                                category = "emby",
                                event = "source_metadata_degraded",
                                message = "Cross-server source metadata lookup failed",
                                throwable = error,
                                attributes = mapOf("serverId" to server.id),
                            )
                        }
                        .getOrNull()
                        // A matching library entry is still a resource even if
                        // this server withholds its stream metadata.
                        ?: SourceInfo("已有资源", null, null)
                }
                ServerSource(
                    serverId = server.id,
                    serverName = server.serverName,
                    isCurrent = server.id == currentServerId,
                    itemId = item?.Id,
                    source = source,
                    reachable = lookup.isSuccess,
                )
            }
        }.awaitAll()
    }

    /**
     * Search results frequently omit MediaSources. Fetch the concrete item again;
     * for a series, resolve a playable episode because the Series object itself
     * never owns a video stream.
     */
    private suspend fun fetchComparableSource(
        server: SavedServer,
        item: BaseItemDto,
    ): SourceInfo? {
        item.MediaSources?.firstOrNull()?.toSourceInfo()?.let { return it }

        val playable = if (item.Type == "Series") {
            fetchNextUp(server, item.Id) ?: fetchFirstEpisode(server, item.Id)
        } else {
            item
        } ?: return null

        val full: BaseItemDto =
            client.get("${server.baseUrl}/Users/${server.userId}/Items/${playable.Id}") {
                header("X-Emby-Token", server.accessToken)
                parameter("Fields", "MediaSources")
            }.body()
        return full.MediaSources?.firstOrNull()?.toSourceInfo()
    }

    /** Seasons of a series. */
    suspend fun seasons(server: SavedServer, seriesId: String): Result<List<Season>> =
        call("seasons") {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/$seriesId/Seasons") {
            header("X-Emby-Token", server.accessToken)
            parameter("UserId", server.userId)
        }.body()
        dto.Items.map { it.toSeason() }
    }

    /** Episodes of a season (or of the whole series when [seasonId] is null). */
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
        call("provider_index") {
            val dto: ItemsResponseDto = client.get(
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

    suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
    ): Result<List<Episode>> = call("episodes") {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/$seriesId/Episodes") {
            header("X-Emby-Token", server.accessToken)
            parameter("UserId", server.userId)
            if (seasonId != null) parameter("SeasonId", seasonId)
            parameter(
                "Fields",
                "Overview,Chapters,ProviderIds,RunTimeTicks,UserData,PremiereDate",
            )
        }.body()
        dto.Items.map { it.toEpisode() }
    }

    private suspend fun fetchViews(server: SavedServer): List<MediaLibrary> {
        val dto: ViewsDto = client.get("${server.baseUrl}/Users/${server.userId}/Views") {
            header("X-Emby-Token", server.accessToken)
        }.body()
        return dto.Items.map { MediaLibrary(it.Id, it.Name, it.CollectionType) }
    }

    /** `Limit=0` returns just the count, which is all the category chip needs. */
    private suspend fun fetchLibraryCount(server: SavedServer, viewId: String): Int {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items") {
            header("X-Emby-Token", server.accessToken)
            parameter("ParentId", viewId)
            parameter("Recursive", true)
            parameter("IncludeItemTypes", "Movie,Series")
            parameter("Limit", 0)
        }.body()
        return dto.TotalRecordCount
    }

    private suspend fun fetchResume(server: SavedServer): List<MediaItem> {
        val dto: ItemsResponseDto = client.get("${server.baseUrl}/Users/${server.userId}/Items/Resume") {
            header("X-Emby-Token", server.accessToken)
            parameter("Limit", 12)
            parameter("Recursive", true)
            parameter("MediaTypes", "Video")
            // UserData carries PlayedPercentage, which draws the resume bar.
            parameter(
                "Fields",
                "BackdropImageTags,UserData,Overview,ParentBackdropItemId," +
                    "ParentBackdropImageTags,SeriesPrimaryImageTag",
            )
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
        }.body()
        return dto.Items.map { it.toMediaItem() }
    }

    private suspend fun fetchLatest(server: SavedServer, viewId: String): List<MediaItem> {
        val items: List<BaseItemDto> = client.get("${server.baseUrl}/Users/${server.userId}/Items/Latest") {
            header("X-Emby-Token", server.accessToken)
            parameter("ParentId", viewId)
            parameter("Limit", 16)
            // Overview feeds the carousel synopsis.
            parameter(
                "Fields",
                "BackdropImageTags,ProductionYear,Overview,ParentBackdropItemId," +
                    "ParentBackdropImageTags,SeriesPrimaryImageTag",
            )
            parameter("EnableImageTypes", "Primary,Backdrop")
            parameter("ImageTypeLimit", 2)
        }.body()
        return items.map { it.toMediaItem() }
    }

    private suspend fun reportPlayback(
        server: SavedServer,
        path: String,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ): Result<Unit> = call("report_playback") {
        client.post("${normalizeBaseUrl(server.baseUrl)}$path") {
            header("X-Emby-Token", server.accessToken)
            contentType(ContentType.Application.Json)
            setBody(
                PlaybackReportDto(
                    ItemId = itemId,
                    PlaySessionId = playSessionId,
                    PositionTicks = positionTicks.coerceAtLeast(0L),
                    IsPaused = isPaused,
                ),
            )
        }
        Unit
    }

    private inline fun <T> call(operation: String, block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val mapped = e.toEmbyError()
            AppLog.error(
                category = "emby",
                event = "request_failed",
                message = "Emby operation failed",
                throwable = e,
                attributes = mapOf(
                    "operation" to operation,
                    "error" to mapped.toString(),
                ),
            )
            Result.failure(EmbyErrorException(mapped))
        }

    private fun Throwable.toEmbyError(): EmbyError = when (this) {
        is ResponseException -> when (response.status.value) {
            401 -> EmbyError.Unauthorized
            in 500..599 -> EmbyError.Server(response.status.value)
            else -> EmbyError.Unknown("HTTP ${response.status.value}")
        }
        else -> EmbyError.Network
    }
}
