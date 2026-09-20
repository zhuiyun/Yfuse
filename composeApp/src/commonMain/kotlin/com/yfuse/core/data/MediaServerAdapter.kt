package com.yfuse.core.data

import com.yfuse.core.data.dto.PlaybackInfoResponseDto
import com.yfuse.core.data.dto.RemoteSubtitleInfoDto
import com.yfuse.core.model.Episode
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.LibraryResolution
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaContainerPage
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.Person
import com.yfuse.core.model.PlayTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.sync.SyncedUserItem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post

/** Which of the three session reports a playback event is. */
internal enum class PlaybackReportPhase {
    Started,
    Progress,
    Stopped,
}

/**
 * One media-server family behind [EmbyRepository].
 *
 * The repository used to branch on `server.kind` in every method; the provider decision now
 * happens once, in [EmbyRepository.adapterFor], and each family answers the same questions in
 * its own dialect. Operations a family cannot perform return a failed [Result] with the same
 * message the repository used to produce, so screens keep their copy.
 */
internal interface MediaServerAdapter {
    /** Signs in with a user's own credentials; Plex has no user name and reads [password] as its token. */
    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<AuthedServer>

    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>>

    suspend fun scheduledTasks(server: SavedServer): Result<List<ServerScheduledTask>>

    /** Whether the account behind [server] can be switched to another managed home profile. */
    fun supportsHomeUserSwitch(server: SavedServer): Boolean

    /** Profiles [server]'s owner account can switch to; empty where the family has none. */
    suspend fun homeUsers(server: SavedServer): List<PlexHomeUser>

    /**
     * Where else this title can be played, one answer per server in [servers].
     *
     * It takes the family's whole group because Emby-compatible servers share one retry and
     * timeout budget across the batch, while Plex answers server by server.
     */
    suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int?,
        mediaType: String?,
        year: Int?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): List<ServerSource>

    suspend fun refreshLibrary(
        server: SavedServer,
        libraryId: String?,
    ): Result<Unit>

    suspend fun runServerTask(
        server: SavedServer,
        taskId: String,
    ): Result<Unit>

    suspend fun refreshMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit>

    suspend fun analyzeMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit>

    suspend fun mediaContainers(server: SavedServer): Result<List<MediaContainer>>

    suspend fun mediaContainersPage(
        server: SavedServer,
        kind: MediaContainerKind,
        startIndex: Int,
        limit: Int,
    ): Result<MediaContainerPage>

    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit>

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit>

    suspend fun addItemToMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
    ): Result<Unit>

    suspend fun removeItemFromMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
        playlistItemId: String?,
    ): Result<Unit>

    suspend fun addToWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit>

    suspend fun isInWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Boolean>

    suspend fun removeFromWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit>

    suspend fun reportPlayback(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
        phase: PlaybackReportPhase,
    ): Result<Unit>

    suspend fun playbackInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        playSessionId: String,
        sourceRequiresDolbyDecoder: Boolean,
    ): Result<PlaybackInfoResponseDto>

    suspend fun probe(server: SavedServer): Result<Long>

    suspend fun probeAddress(
        baseUrl: String,
        accessToken: String,
    ): Result<Long>

    suspend fun itemCounts(server: SavedServer): Result<LibraryCounts>

    suspend fun homeContent(
        server: SavedServer,
        initialContent: HomeContent,
        onProgress: suspend (HomeContent) -> Unit,
    ): Result<HomeContent>

    suspend fun mediaContainerItems(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        sort: LibrarySort,
        genre: String?,
        startIndex: Int,
        limit: Int,
        resolution: LibraryResolution,
    ): Result<LibraryPage>

    suspend fun mediaContainerGenres(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
    ): Result<List<String>>

    suspend fun libraryItems(
        server: SavedServer,
        libraryId: String,
        sort: LibrarySort,
        genre: String?,
        startIndex: Int,
        limit: Int,
        resolution: LibraryResolution,
        unplayedOnly: Boolean,
    ): Result<LibraryPage>

    suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>>

    suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int,
    ): Result<List<MediaItem>>

    suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget>

    suspend fun resolvePlayTargetWithEpisodes(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTargetResolution>

    suspend fun searchGenres(
        server: SavedServer,
        parentId: String?,
    ): Result<List<String>>

    suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int,
    ): Result<List<MediaItem>>

    suspend fun searchPage(
        server: SavedServer,
        query: String,
        startIndex: Int,
        limit: Int,
        filter: MediaSearchFilter,
    ): Result<MediaSearchPage>

    suspend fun searchPeople(
        server: SavedServer,
        query: String,
        limit: Int,
    ): List<Person>

    suspend fun itemsByPerson(
        server: SavedServer,
        personId: String,
        limit: Int,
    ): Result<List<MediaItem>>

    suspend fun userLibrarySnapshot(
        server: SavedServer,
        includeProgress: Boolean,
        includeFavorites: Boolean,
    ): Result<List<SyncedUserItem>>

    suspend fun stopTranscoding(
        server: SavedServer,
        playSessionId: String,
    ): Result<Unit>

    suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?>

    suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?>

    suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
        includeInheritedPeople: Boolean,
    ): Result<MediaDetail>

    suspend fun inheritedEpisodePeople(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<List<Person>>

    suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>>

    suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>>

    suspend fun seriesIdentityCatalog(server: SavedServer): Result<List<LibrarySeriesIdentity>>

    suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>>

    suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean,
        seasonNumber: Int?,
    ): Result<List<Episode>>

    suspend fun trickplayInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String,
    ): Result<TrickplayInfo?>

    suspend fun searchRemoteSubtitles(
        server: SavedServer,
        itemId: String,
        language: String,
    ): Result<List<RemoteSubtitleInfoDto>>

    suspend fun downloadRemoteSubtitle(
        server: SavedServer,
        itemId: String,
        subtitleId: String,
    ): Result<Unit>
}

/** Emby and Jellyfin: the focused `Emby*Service` classes behind one provider boundary. */
internal class EmbyAdapter(
    private val client: HttpClient,
    private val authService: EmbyAuthService,
    private val detailService: EmbyDetailService,
    private val libraryService: EmbyLibraryService,
    private val browseService: EmbyBrowseService,
    private val homeService: EmbyHomeService,
    private val lookupService: EmbyLookupService,
    private val playbackService: EmbyPlaybackService,
    private val searchService: EmbySearchService,
    private val serverService: EmbyServerService,
    private val sourceService: EmbySourceService,
    private val subtitleService: EmbySubtitleService,
    private val userDataService: EmbyUserDataService,
) : MediaServerAdapter {
    override suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<AuthedServer> = authService.authenticate(baseUrl, username, password)

    override suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> =
        embyApiCall("libraries") { libraryService.views(server) }

    override fun supportsHomeUserSwitch(server: SavedServer): Boolean = false

    override suspend fun homeUsers(server: SavedServer): List<PlexHomeUser> = emptyList()

    override suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int?,
        mediaType: String?,
        year: Int?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): List<ServerSource> =
        sourceService.compareSources(
            servers = servers,
            currentServerId = currentServerId,
            title = title,
            tmdbId = tmdbId,
            mediaType = mediaType,
            year = year,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

    override suspend fun scheduledTasks(server: SavedServer): Result<List<ServerScheduledTask>> =
        runCatchingCancellable {
            client
                .get("${server.baseUrl}/ScheduledTasks") {
                    header("X-Emby-Token", server.accessToken)
                }.body<List<EmbyScheduledTaskDto>>()
                .mapNotNull { task ->
                    task.Id.takeIf(String::isNotBlank)?.let { id ->
                        ServerScheduledTask(
                            id = id,
                            name = task.Name.takeIf(String::isNotBlank) ?: "服务器任务",
                            state = task.State,
                            progressPercent = task.CurrentProgressPercentage,
                            lastResult = task.LastExecutionResult?.Status ?: task.LastExecutionResult?.Name,
                        )
                    }
                }
        }

    override suspend fun refreshLibrary(
        server: SavedServer,
        libraryId: String?,
    ): Result<Unit> =
        embyApiCall("refresh_library") {
            if (libraryId.isNullOrBlank()) {
                client.post("${server.baseUrl}/Library/Refresh") {
                    header("X-Emby-Token", server.accessToken)
                }
            } else {
                client.post("${server.baseUrl}/Items/${embyPath(libraryId)}/Refresh") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Recursive", true)
                    parameter("MetadataRefreshMode", "Default")
                    parameter("ImageRefreshMode", "Default")
                }
            }
        }

    override suspend fun runServerTask(
        server: SavedServer,
        taskId: String,
    ): Result<Unit> =
        embyApiCall("run_scheduled_task") {
            require(taskId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "服务器任务标识无效" }
            client.post("${server.baseUrl}/ScheduledTasks/Running/${embyPath(taskId)}") {
                header("X-Emby-Token", server.accessToken)
            }
        }

    override suspend fun refreshMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        embyApiCall("refresh_metadata") {
            require(itemId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "媒体标识无效" }
            client.post("${server.baseUrl}/Items/${embyPath(itemId)}/Refresh") {
                header("X-Emby-Token", server.accessToken)
                parameter("Recursive", true)
                parameter("MetadataRefreshMode", "FullRefresh")
                parameter("ImageRefreshMode", "FullRefresh")
                parameter("ReplaceAllMetadata", false)
                parameter("ReplaceAllImages", false)
            }
        }

    override suspend fun analyzeMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Emby/Jellyfin 请使用元数据刷新或服务器计划任务"))

    override suspend fun mediaContainers(server: SavedServer): Result<List<MediaContainer>> =
        browseService.mediaContainers(server)

    override suspend fun mediaContainersPage(
        server: SavedServer,
        kind: MediaContainerKind,
        startIndex: Int,
        limit: Int,
    ): Result<MediaContainerPage> = browseService.mediaContainersPage(server, kind, startIndex, limit)

    override suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> = userDataService.setFavorite(server, itemId, favorite)

    override suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> = userDataService.setPlayed(server, itemId, played)

    override suspend fun addItemToMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
    ): Result<Unit> = browseService.addItemToMediaContainer(server, containerId, kind, itemId)

    override suspend fun removeItemFromMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
        playlistItemId: String?,
    ): Result<Unit> = browseService.removeItemFromMediaContainer(server, containerId, kind, itemId, playlistItemId)

    override suspend fun addToWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = browseService.addToWatchLater(server, itemId)

    override suspend fun isInWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Boolean> = browseService.isInWatchLater(server, itemId)

    override suspend fun removeFromWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = browseService.removeFromWatchLater(server, itemId)

    override suspend fun reportPlayback(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
        phase: PlaybackReportPhase,
    ): Result<Unit> =
        when (phase) {
            PlaybackReportPhase.Started ->
                playbackService.reportStarted(
                    server = server,
                    itemId = itemId,
                    playSessionId = playSessionId,
                    positionTicks = positionTicks,
                    isPaused = isPaused,
                    playMethod = playMethod,
                )
            PlaybackReportPhase.Progress ->
                playbackService.reportProgress(
                    server = server,
                    itemId = itemId,
                    playSessionId = playSessionId,
                    positionTicks = positionTicks,
                    isPaused = isPaused,
                    playMethod = playMethod,
                )
            PlaybackReportPhase.Stopped ->
                playbackService.reportStopped(
                    server = server,
                    itemId = itemId,
                    playSessionId = playSessionId,
                    positionTicks = positionTicks,
                    isPaused = isPaused,
                    playMethod = playMethod,
                )
        }

    override suspend fun playbackInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        playSessionId: String,
        sourceRequiresDolbyDecoder: Boolean,
    ): Result<PlaybackInfoResponseDto> =
        playbackService.playbackInfo(
            server = server,
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startPositionTicks = startPositionTicks,
            playSessionId = playSessionId,
            sourceRequiresDolbyDecoder = sourceRequiresDolbyDecoder,
        )

    override suspend fun probe(server: SavedServer): Result<Long> = serverService.probe(server)

    override suspend fun probeAddress(
        baseUrl: String,
        accessToken: String,
    ): Result<Long> = serverService.probeAddress(baseUrl, accessToken)

    override suspend fun itemCounts(server: SavedServer): Result<LibraryCounts> =
        embyApiCall("item_counts") { libraryService.counts(server) }

    override suspend fun homeContent(
        server: SavedServer,
        initialContent: HomeContent,
        onProgress: suspend (HomeContent) -> Unit,
    ): Result<HomeContent> = homeService.homeContent(server, initialContent, onProgress)

    override suspend fun mediaContainerItems(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        sort: LibrarySort,
        genre: String?,
        startIndex: Int,
        limit: Int,
        resolution: LibraryResolution,
    ): Result<LibraryPage> =
        browseService.mediaContainerItems(server, containerId, kind, sort, genre, startIndex, limit, resolution)

    override suspend fun mediaContainerGenres(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
    ): Result<List<String>> = browseService.mediaContainerGenres(server, containerId, kind)

    override suspend fun libraryItems(
        server: SavedServer,
        libraryId: String,
        sort: LibrarySort,
        genre: String?,
        startIndex: Int,
        limit: Int,
        resolution: LibraryResolution,
        unplayedOnly: Boolean,
    ): Result<LibraryPage> =
        browseService.libraryItems(server, libraryId, sort, genre, startIndex, limit, resolution, unplayedOnly)

    override suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>> = browseService.libraryGenres(server, libraryId)

    override suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int,
    ): Result<List<MediaItem>> = detailService.similarItems(server, itemId, limit)

    override suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget> = detailService.resolvePlayTarget(server, detail)

    override suspend fun resolvePlayTargetWithEpisodes(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTargetResolution> = detailService.resolvePlayTargetWithEpisodes(server, detail)

    override suspend fun searchGenres(
        server: SavedServer,
        parentId: String?,
    ): Result<List<String>> = searchService.genres(server, parentId)

    override suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int,
    ): Result<List<MediaItem>> = detailService.nextUpEpisodes(server, limit)

    override suspend fun searchPage(
        server: SavedServer,
        query: String,
        startIndex: Int,
        limit: Int,
        filter: MediaSearchFilter,
    ): Result<MediaSearchPage> = searchService.searchPage(server, query, startIndex, limit, filter)

    override suspend fun searchPeople(
        server: SavedServer,
        query: String,
        limit: Int,
    ): List<Person> = searchService.searchPeople(server, query, limit)

    override suspend fun itemsByPerson(
        server: SavedServer,
        personId: String,
        limit: Int,
    ): Result<List<MediaItem>> = searchService.itemsByPerson(server, personId, limit)

    override suspend fun userLibrarySnapshot(
        server: SavedServer,
        includeProgress: Boolean,
        includeFavorites: Boolean,
    ): Result<List<SyncedUserItem>> = userDataService.snapshot(server, includeProgress, includeFavorites)

    override suspend fun stopTranscoding(
        server: SavedServer,
        playSessionId: String,
    ): Result<Unit> = playbackService.stopTranscoding(server, playSessionId)

    override suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> = lookupService.findByTmdbId(server, tmdbId, mediaType)

    override suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?> = lookupService.findByMediaKey(server, mediaKey)

    override suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
        includeInheritedPeople: Boolean,
    ): Result<MediaDetail> = detailService.itemDetail(server, itemId, includeInheritedPeople)

    override suspend fun inheritedEpisodePeople(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<List<Person>> = detailService.inheritedEpisodePeople(server, detail)

    override suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>> = detailService.seasons(server, seriesId)

    override suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>> =
        lookupService.seriesProviderIndex(server)

    override suspend fun seriesIdentityCatalog(server: SavedServer): Result<List<LibrarySeriesIdentity>> =
        lookupService.seriesIdentityCatalog(server)

    override suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>> =
        lookupService.movieProviderIndex(server)

    override suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean,
        seasonNumber: Int?,
    ): Result<List<Episode>> =
        detailService.episodes(
            server = server,
            seriesId = seriesId,
            seasonId = seasonId,
            includeMediaSources = includeMediaSources,
            seasonNumber = seasonNumber,
        )

    override suspend fun trickplayInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String,
    ): Result<TrickplayInfo?> = detailService.trickplayInfo(server, itemId, mediaSourceId)

    override suspend fun searchRemoteSubtitles(
        server: SavedServer,
        itemId: String,
        language: String,
    ): Result<List<RemoteSubtitleInfoDto>> = subtitleService.search(server, itemId, language)

    override suspend fun downloadRemoteSubtitle(
        server: SavedServer,
        itemId: String,
        subtitleId: String,
    ): Result<Unit> = subtitleService.download(server, itemId, subtitleId)
}

/** Plex Media Server plus the plex.tv account APIs its watchlist needs. */
internal class PlexAdapter(
    private val plex: PlexMediaServerAdapter,
    private val plexCloud: PlexCloudAccountService,
) : MediaServerAdapter {
    /** A plex.tv resource sign-in: the server token plus the account identity it was issued to. */
    suspend fun authenticateWithToken(
        baseUrl: String,
        token: String,
        account: PlexAccountIdentity,
    ): Result<AuthedServer> = plex.authenticate(baseUrl, token, account)

    suspend fun machineIdentifierFor(server: SavedServer): Result<String> = plex.machineIdentifierFor(server)

    override suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<AuthedServer> = plex.authenticate(baseUrl, password)

    override fun supportsHomeUserSwitch(server: SavedServer): Boolean = server.homeOwnerToken != null

    override suspend fun homeUsers(server: SavedServer): List<PlexHomeUser> =
        server.homeOwnerToken?.let { plexCloud.homeUsers(it).getOrDefault(emptyList()) }.orEmpty()

    private val SavedServer.homeOwnerToken: String? get() = cloudOwnerAccessToken ?: cloudAccessToken

    override suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int?,
        mediaType: String?,
        year: Int?,
        seasonNumber: Int?,
        episodeNumber: Int?,
    ): List<ServerSource> =
        servers.map { server ->
            plex.compareSource(
                server = server,
                currentServerId = currentServerId,
                title = title,
                tmdbId = tmdbId,
                mediaType = mediaType,
                year = year,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        }

    override suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> = plex.libraries(server)

    override suspend fun scheduledTasks(server: SavedServer): Result<List<ServerScheduledTask>> =
        Result.success(emptyList())

    override suspend fun refreshLibrary(
        server: SavedServer,
        libraryId: String?,
    ): Result<Unit> =
        libraryId?.let { plex.refreshLibrary(server, it) }
            ?: Result.failure(IllegalArgumentException("请选择 Plex 媒体库"))

    override suspend fun runServerTask(
        server: SavedServer,
        taskId: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Plex 没有可远程运行的通用计划任务接口"))

    override suspend fun refreshMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = plex.refreshMetadata(server, itemId)

    override suspend fun analyzeMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = plex.analyzeMetadata(server, itemId)

    override suspend fun mediaContainers(server: SavedServer): Result<List<MediaContainer>> =
        plex.mediaContainers(server)

    override suspend fun mediaContainersPage(
        server: SavedServer,
        kind: MediaContainerKind,
        startIndex: Int,
        limit: Int,
    ): Result<MediaContainerPage> = plex.mediaContainersPage(server, kind, startIndex, limit)

    override suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> =
        // Plex has no first-class favorite flag equivalent to Emby/Jellyfin UserData.
        Result.failure(UnsupportedOperationException("Plex 不支持 Emby 收藏状态同步"))

    override suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> = plex.setPlayed(server, itemId, played)

    override suspend fun addItemToMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
    ): Result<Unit> = plex.addItemToMediaContainer(server, containerId, kind, itemId)

    override suspend fun removeItemFromMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
        playlistItemId: String?,
    ): Result<Unit> = plex.removeItemFromMediaContainer(server, containerId, kind, itemId, playlistItemId)

    override suspend fun addToWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        watchlist(server, itemId) { token, key ->
            plexCloud.setWatchlist(token, key, inWatchlist = true)
        }

    override suspend fun isInWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Boolean> = watchlist(server, itemId, plexCloud::isInWatchlist)

    override suspend fun removeFromWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        watchlist(server, itemId) { token, key ->
            plexCloud.setWatchlist(token, key, inWatchlist = false)
        }

    private suspend fun <T> watchlist(
        server: SavedServer,
        itemId: String,
        action: suspend (accountToken: String, cloudRatingKey: String) -> Result<T>,
    ): Result<T> {
        val accountToken =
            server.cloudAccessToken
                ?: return Result.failure(IllegalStateException("请先通过 Plex 云账号重新连接此服务器"))
        return plex.cloudRatingKey(server, itemId).fold(
            onSuccess = { key -> action(accountToken, key) },
            onFailure = { Result.failure(it) },
        )
    }

    override suspend fun reportPlayback(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
        phase: PlaybackReportPhase,
    ): Result<Unit> =
        plex.reportPlayback(
            server = server,
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            stopped = phase == PlaybackReportPhase.Stopped,
        )

    override suspend fun playbackInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String?,
        startPositionTicks: Long,
        playSessionId: String,
        sourceRequiresDolbyDecoder: Boolean,
    ): Result<PlaybackInfoResponseDto> = plex.playbackInfo(server, itemId, mediaSourceId, playSessionId)

    override suspend fun probe(server: SavedServer): Result<Long> = plex.probe(server)

    override suspend fun probeAddress(
        baseUrl: String,
        accessToken: String,
    ): Result<Long> = plex.probeAddress(baseUrl, accessToken)

    override suspend fun itemCounts(server: SavedServer): Result<LibraryCounts> = plex.itemCounts(server)

    override suspend fun homeContent(
        server: SavedServer,
        initialContent: HomeContent,
        onProgress: suspend (HomeContent) -> Unit,
    ): Result<HomeContent> = plex.homeContent(server)

    override suspend fun mediaContainerItems(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        sort: LibrarySort,
        genre: String?,
        startIndex: Int,
        limit: Int,
        resolution: LibraryResolution,
    ): Result<LibraryPage> =
        plex.mediaContainerItems(server, containerId, kind, sort, genre, startIndex, limit, resolution)

    override suspend fun mediaContainerGenres(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
    ): Result<List<String>> = plex.mediaContainerGenres(server, containerId, kind)

    override suspend fun libraryItems(
        server: SavedServer,
        libraryId: String,
        sort: LibrarySort,
        genre: String?,
        startIndex: Int,
        limit: Int,
        resolution: LibraryResolution,
        unplayedOnly: Boolean,
    ): Result<LibraryPage> = plex.libraryItems(server, libraryId, sort, genre, startIndex, limit, resolution)

    override suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>> = plex.libraryGenres(server, libraryId)

    override suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int,
    ): Result<List<MediaItem>> = plex.similarItems(server, itemId, limit)

    override suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget> = plex.resolvePlayTarget(server, detail)

    override suspend fun resolvePlayTargetWithEpisodes(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTargetResolution> = plex.resolvePlayTarget(server, detail).map { PlayTargetResolution(it) }

    override suspend fun searchGenres(
        server: SavedServer,
        parentId: String?,
    ): Result<List<String>> =
        if (parentId != null) plex.libraryGenres(server, parentId) else Result.success(emptyList())

    override suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int,
    ): Result<List<MediaItem>> = plex.nextUpEpisodes(server, limit)

    override suspend fun searchPage(
        server: SavedServer,
        query: String,
        startIndex: Int,
        limit: Int,
        filter: MediaSearchFilter,
    ): Result<MediaSearchPage> = plex.searchPage(server, query, startIndex, limit, filter)

    override suspend fun searchPeople(
        server: SavedServer,
        query: String,
        limit: Int,
    ): List<Person> = plex.searchPeople(server, query, limit)

    override suspend fun itemsByPerson(
        server: SavedServer,
        personId: String,
        limit: Int,
    ): Result<List<MediaItem>> = plex.itemsByPerson(server, personId, limit)

    override suspend fun userLibrarySnapshot(
        server: SavedServer,
        includeProgress: Boolean,
        includeFavorites: Boolean,
    ): Result<List<SyncedUserItem>> = plex.userLibrarySnapshot(server, includeProgress)

    override suspend fun stopTranscoding(
        server: SavedServer,
        playSessionId: String,
    ): Result<Unit> = plex.stopTranscoding(server, playSessionId)

    override suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> = plex.findByTmdbId(server, tmdbId, mediaType)

    override suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?> = plex.findByMediaKey(server, mediaKey)

    override suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
        includeInheritedPeople: Boolean,
    ): Result<MediaDetail> = plex.itemDetail(server, itemId)

    override suspend fun inheritedEpisodePeople(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<List<Person>> = Result.success(detail.people)

    override suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>> = plex.seasons(server, seriesId)

    override suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>> =
        plex.seriesProviderIndex(server)

    override suspend fun seriesIdentityCatalog(server: SavedServer): Result<List<LibrarySeriesIdentity>> =
        plex.seriesIdentityCatalog(server)

    override suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>> =
        plex.movieProviderIndex(server)

    override suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean,
        seasonNumber: Int?,
    ): Result<List<Episode>> = plex.episodes(server, seriesId, seasonId, includeMediaSources, seasonNumber)

    override suspend fun trickplayInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String,
    ): Result<TrickplayInfo?> = plex.trickplayInfo(server, itemId)

    override suspend fun searchRemoteSubtitles(
        server: SavedServer,
        itemId: String,
        language: String,
    ): Result<List<RemoteSubtitleInfoDto>> = Result.failure(UnsupportedOperationException("此服务器不提供字幕商店，可导入本地字幕"))

    override suspend fun downloadRemoteSubtitle(
        server: SavedServer,
        itemId: String,
        subtitleId: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Plex 不支持 Emby 字幕商店接口"))
}
