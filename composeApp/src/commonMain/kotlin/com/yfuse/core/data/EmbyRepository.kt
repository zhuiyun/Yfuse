package com.yfuse.core.data

import com.yfuse.core.data.dto.PlaybackInfoResponseDto
import com.yfuse.core.data.dto.PublicUserDto
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
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.Person
import com.yfuse.core.model.PlayTarget
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.ServerSource
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.model.capabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.sync.SyncedUserItem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post

/** Result of a successful authentication, ready to persist as a [SavedServer]. */
data class AuthedServer(
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val cloudAccessToken: String? = null,
    val cloudOwnerAccessToken: String? = null,
    val kind: MediaServerKind = MediaServerKind.Emby,
    val routes: List<ServerRoute> = emptyList(),
) {
    fun toSavedServer(
        serverName: String? = null,
        localCleartextConfirmed: Boolean = false,
    ) = SavedServer(
        id = SavedServer.idOf(baseUrl, userId),
        baseUrl = baseUrl,
        serverName = serverName ?: this.serverName,
        userId = userId,
        userName = userName,
        accessToken = accessToken,
        cloudAccessToken = cloudAccessToken,
        cloudOwnerAccessToken = cloudOwnerAccessToken,
        kind = kind,
        routes = routes,
        activeRouteId = ServerRoute.PRIMARY_ID.takeIf { routes.isNotEmpty() },
        localCleartextConfirmed = localCleartextConfirmed,
    )
}

/*
 * Talks to Emby. Stateless with respect to sessions: every call targets an
 * explicit server. Failures carry an [EmbyErrorException].
 */

/** One library item found by provider id: what to open, and whether it was watched. */
data class ProviderHit(
    val itemId: String,
    val played: Boolean,
)

/** A real page of server search results, including the server-reported result boundary. */
data class MediaSearchPage(
    val items: List<MediaItem>,
    val totalCount: Int,
    val startIndex: Int,
    /**
     * Where the next page starts on the server. It advances by the rows the server returned,
     * not by the cards kept after type filtering and de-duplication — those can be fewer, and
     * asking from the card count re-reads rows already seen.
     */
    val nextStartIndex: Int = startIndex + items.size,
)

/** Virtual library ids routed to user-specific Emby collections. */
internal const val FAVORITES_COLLECTION_ID = "__yfuse_favorites__"
internal const val WATCH_LATER_COLLECTION_ID = "__yfuse_watch_later__"

internal const val PERSONAL_COLLECTION_PREVIEW_LIMIT = 16
internal const val MEDIA_CONTAINER_LIMIT = 500
internal const val MEDIA_CONTAINER_PREVIEW_LIMIT = 16

/**
 * How many titles one 「查看更多」 request asks for. Small enough that the first screenful
 * arrives quickly on a slow remote server, large enough that a fast one rarely pages.
 */
const val LIBRARY_PAGE_SIZE = 60

/** Genre facets are a filter row, not a catalogue; nobody scrolls past this many. */
internal const val LIBRARY_GENRE_LIMIT = 60

// 演员 is one chip row above the titles, not a directory.
private const val PERSON_SEARCH_LIMIT = 8

// One person's filmography, as much of it as this server holds.
private const val PERSON_ITEMS_LIMIT = 60

/** One page of [EmbyRepository.userLibrarySnapshot]; small enough to arrive inside a timeout. */
internal const val SNAPSHOT_PAGE_SIZE = 2_000

/** Per-filter backstop against a server whose `TotalRecordCount` is wrong, or a paging loop. */
internal const val SNAPSHOT_MAX_PAGES_PER_QUERY = 250

internal fun userLibrarySnapshotPageBudgetExhausted(
    pagesRead: Int,
    startIndex: Int,
    reportedTotal: Int,
    maxPages: Int,
): Boolean = pagesRead >= maxPages && startIndex < reportedTotal

/**
 * Resolves the total used by the grid when an Emby-compatible endpoint omits it.
 *
 * A short page proves that the end was reached. A full page does not, so expose one
 * additional sentinel row to keep `canLoadMore` true until the next request answers.
 * When the server does report a total, keep that exact boundary (with a floor for broken
 * under-counts) so an exactly-full final page does not cause an unnecessary request.
 */
internal fun pageTotal(
    reportedTotal: Int?,
    startIndex: Int,
    itemCount: Int,
    limit: Int,
): Int {
    val loadedThrough = startIndex + itemCount
    return if (reportedTotal == null && limit > 0 && itemCount >= limit) {
        loadedThrough + 1
    } else {
        (reportedTotal ?: loadedThrough).coerceAtLeast(loadedThrough)
    }
}

/** Exact and prefix matches stay ahead without destroying the server's order inside a tier. */
internal fun rankSearchResults(
    items: List<MediaItem>,
    query: String,
): List<MediaItem> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return items

    fun score(title: String): Int {
        val value = title.trim().lowercase()
        return when {
            value == needle -> 0
            value.startsWith(needle) -> 1
            value.split(Regex("\\s+")).any { it.startsWith(needle) } -> 2
            needle in value -> 3
            else -> 4
        }
    }
    return items
        .withIndex()
        .sortedWith(compareBy<IndexedValue<MediaItem>> { score(it.value.title) }.thenBy { it.index })
        .map(IndexedValue<MediaItem>::value)
}

internal data class PersonalCollection(
    val items: List<MediaItem>,
    val totalCount: Int,
)

private val bilingualGenrePairs =
    mapOf(
        "action" to "动作",
        "adventure" to "冒险",
        "animation" to "动画",
        "biography" to "传记",
        "children" to "儿童",
        "kids" to "儿童",
        "comedy" to "喜剧",
        "crime" to "犯罪",
        "documentary" to "纪录片",
        "drama" to "剧情",
        "family" to "家庭",
        "fantasy" to "奇幻",
        "history" to "历史",
        "horror" to "恐怖",
        "music" to "音乐",
        "mystery" to "悬疑",
        "romance" to "爱情",
        "science fiction" to "科幻",
        "sci-fi" to "科幻",
        "sci-fi & fantasy" to "科幻",
        "sport" to "体育",
        "sports" to "体育",
        "thriller" to "惊悚",
        "war" to "战争",
        "western" to "西部",
    )

/**
 * Removes bilingual duplicates without translating a server-only English value.
 *
 * If both "Action" and "动作" exist, keep "动作". If only "Action" exists, keep
 * "Action" so the value sent back through the Genres query remains valid for that server.
 */
internal fun dedupeBilingualGenreLabels(values: List<String>): List<String> {
    val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
    val exactValues = cleaned.toHashSet()
    val seen = hashSetOf<String>()
    return cleaned.filter { value ->
        val chineseTwin = bilingualGenrePairs[value.lowercase()]
        val shadowedByChinese = chineseTwin != null && chineseTwin in exactValues
        !shadowedByChinese && seen.add(value.lowercase())
    }
}

class EmbyRepository(
    private val client: HttpClient,
    capabilitiesProvider: PlaybackDeviceCapabilitiesProvider =
        PlaybackDeviceCapabilitiesProvider { PlaybackDeviceCapabilities.conservative() },
    audioPassthroughEnabled: () -> Boolean = { false },
    private val progressProjection: PlaybackProgressProjection = PlaybackProgressProjection(),
) {
    private val authService = EmbyAuthService(client)
    private val detailService = EmbyDetailService(client, progressProjection)
    private val sourceService = EmbySourceService(client, detailService)
    private val libraryService = EmbyLibraryService(client)
    private val browseService = EmbyBrowseService(client, progressProjection)
    private val homeService = EmbyHomeService(client, libraryService, browseService, progressProjection)
    private val lookupService = EmbyLookupService(client, progressProjection)
    private val playbackService =
        EmbyPlaybackService(client, capabilitiesProvider, audioPassthroughEnabled)
    private val searchService = EmbySearchService(client, progressProjection)
    private val serverService = EmbyServerService(client)
    private val subtitleService = EmbySubtitleService(client)
    private val userDataService = EmbyUserDataService(client)
    private val plex = PlexMediaServerAdapter(client, progressProjection)
    private val plexCloud = PlexCloudAccountService(client)

    suspend fun publicUsers(baseUrl: String): Result<List<PublicUserDto>> = authService.publicUsers(baseUrl)

    suspend fun startPlexCloudSignIn(nowEpochMs: Long): Result<PlexPinSession> = plexCloud.startPin(nowEpochMs)

    suspend fun pollPlexCloudSignIn(
        session: PlexPinSession,
        nowEpochMs: Long,
    ): Result<PlexPinPoll> = plexCloud.pollPin(session, nowEpochMs)

    suspend fun plexHomeUsers(accountToken: String): Result<List<PlexHomeUser>> = plexCloud.homeUsers(accountToken)

    suspend fun switchPlexHomeUser(
        accountToken: String,
        userId: String,
        pin: String,
    ): Result<String> = plexCloud.switchHomeUser(accountToken, userId, pin)

    suspend fun plexCloudResources(accountToken: String): Result<List<PlexCloudResource>> =
        plexCloud.resources(accountToken)

    /** Tries the advertised Plex routes in safe order and persists every usable fallback. */
    suspend fun authenticatePlexCloudResource(
        accountToken: String,
        resource: PlexCloudResource,
        ownerAccountToken: String = accountToken,
    ): Result<AuthedServer> {
        val serverToken = resource.accessToken ?: accountToken
        var lastError: Throwable? = null
        resource.rankedConnections().forEach { connection ->
            val authenticated = plex.authenticate(connection.uri, serverToken)
            authenticated.onSuccess { server ->
                return Result.success(
                    server.copy(
                        serverName = resource.name,
                        cloudAccessToken = accountToken,
                        cloudOwnerAccessToken = ownerAccountToken,
                        routes = resource.routes(server.baseUrl),
                    ),
                )
            }
            lastError = authenticated.exceptionOrNull()
        }
        return Result.failure(lastError ?: IllegalStateException("Plex 账号中没有可连接的服务器线路"))
    }

    suspend fun switchPlexServerHomeUser(
        server: SavedServer,
        userId: String,
        pin: String,
    ): Result<AuthedServer> {
        require(server.kind == MediaServerKind.Plex) { "这不是 Plex 服务器" }
        val ownerToken =
            server.cloudOwnerAccessToken ?: server.cloudAccessToken
                ?: return Result.failure(IllegalStateException("此服务器不是通过 Plex 云账号连接的"))
        val activeToken =
            switchPlexHomeUser(ownerToken, userId, pin).getOrElse { return Result.failure(it) }
        val machineId = plex.machineIdentifierFor(server).getOrElse { return Result.failure(it) }
        val resource =
            plexCloudResources(activeToken)
                .getOrElse { return Result.failure(it) }
                .firstOrNull { it.id == machineId }
                ?: return Result.failure(IllegalStateException("切换后的 Plex 用户无权访问此服务器"))
        return authenticatePlexCloudResource(activeToken, resource, ownerToken)
    }

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
        kind: MediaServerKind = MediaServerKind.Emby,
    ): Result<AuthedServer> =
        if (kind == MediaServerKind.Plex) {
            plex.authenticate(baseUrl, password)
        } else {
            authService.authenticate(baseUrl, username, password)
        }

    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.libraries(server)
        } else {
            embyApiCall("libraries") { libraryService.views(server) }
        }

    suspend fun serverManagement(server: SavedServer): Result<ServerManagementSnapshot> =
        libraries(server).mapCatching { mediaLibraries ->
            val capabilities = server.kind.capabilities()
            val taskResult: Result<List<ServerScheduledTask>> =
                if (!capabilities.scheduledTasks) {
                    Result.success(emptyList())
                } else {
                    runCatching {
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
                                        lastResult =
                                            task.LastExecutionResult?.Status ?: task.LastExecutionResult?.Name,
                                    )
                                }
                            }
                    }
                }
            ServerManagementSnapshot(
                libraries = mediaLibraries,
                tasks = taskResult.getOrDefault(emptyList()),
                supportsScheduledTasks = capabilities.scheduledTasks,
                supportsMetadataAnalysis = capabilities.itemAnalysis,
                plexHomeUsers =
                    if (server.kind == MediaServerKind.Plex) {
                        val ownerToken = server.cloudOwnerAccessToken ?: server.cloudAccessToken
                        ownerToken?.let { plexHomeUsers(it).getOrDefault(emptyList()) }.orEmpty()
                    } else {
                        emptyList()
                    },
                supportsPlexHomeSwitch =
                    server.kind == MediaServerKind.Plex &&
                        (server.cloudOwnerAccessToken != null || server.cloudAccessToken != null),
                scheduledTasksError =
                    taskResult.exceptionOrNull()?.let {
                        "当前账号无权读取服务器计划任务，媒体库扫描仍可使用"
                    },
            )
        }

    suspend fun refreshLibrary(
        server: SavedServer,
        libraryId: String? = null,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            libraryId?.let { plex.refreshLibrary(server, it) }
                ?: Result.failure(IllegalArgumentException("请选择 Plex 媒体库"))
        } else {
            embyApiCall("refresh_library") {
                if (libraryId.isNullOrBlank()) {
                    client.post("${server.baseUrl}/Library/Refresh") {
                        header("X-Emby-Token", server.accessToken)
                    }
                } else {
                    client.post("${server.baseUrl}/Items/$libraryId/Refresh") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("Recursive", true)
                        parameter("MetadataRefreshMode", "Default")
                        parameter("ImageRefreshMode", "Default")
                    }
                }
            }
        }

    suspend fun runServerTask(
        server: SavedServer,
        taskId: String,
    ): Result<Unit> =
        if (!server.kind.capabilities().scheduledTasks) {
            Result.failure(UnsupportedOperationException("Plex 没有可远程运行的通用计划任务接口"))
        } else {
            embyApiCall("run_scheduled_task") {
                require(taskId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "服务器任务标识无效" }
                client.post("${server.baseUrl}/ScheduledTasks/Running/$taskId") {
                    header("X-Emby-Token", server.accessToken)
                }
            }
        }

    suspend fun refreshMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            plex.refreshMetadata(server, itemId)
        } else {
            embyApiCall("refresh_metadata") {
                require(itemId.matches(Regex("[A-Za-z0-9-]{1,128}"))) { "媒体标识无效" }
                client.post("${server.baseUrl}/Items/$itemId/Refresh") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Recursive", true)
                    parameter("MetadataRefreshMode", "FullRefresh")
                    parameter("ImageRefreshMode", "FullRefresh")
                    parameter("ReplaceAllMetadata", false)
                    parameter("ReplaceAllImages", false)
                }
            }
        }

    suspend fun analyzeMetadata(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        if (server.kind.capabilities().itemAnalysis) {
            plex.analyzeMetadata(server, itemId)
        } else {
            Result.failure(UnsupportedOperationException("Emby/Jellyfin 请使用元数据刷新或服务器计划任务"))
        }

    suspend fun mediaContainers(server: SavedServer): Result<List<MediaContainer>> =
        if (server.kind == MediaServerKind.Plex) plex.mediaContainers(server) else browseService.mediaContainers(server)

    suspend fun mediaContainersPage(
        server: SavedServer,
        kind: MediaContainerKind,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<MediaContainerPage> =
        if (server.kind == MediaServerKind.Plex) {
            plex.mediaContainersPage(server, kind, startIndex, limit)
        } else {
            browseService.mediaContainersPage(server, kind, startIndex, limit)
        }

    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> =
        if (!server.kind.capabilities().favorites) {
            // Plex has no first-class favorite flag equivalent to Emby/Jellyfin UserData.
            Result.failure(UnsupportedOperationException("Plex 不支持 Emby 收藏状态同步"))
        } else {
            userDataService.setFavorite(server, itemId, favorite)
        }

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> =
        if (progressProjection.localOnly) {
            Result.success(Unit)
        } else if (server.kind == MediaServerKind.Plex) {
            plex.setPlayed(server, itemId, played)
        } else {
            userDataService.setPlayed(server, itemId, played)
        }

    suspend fun addItemToMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            plex.addItemToMediaContainer(server, containerId, kind, itemId)
        } else {
            browseService.addItemToMediaContainer(server, containerId, kind, itemId)
        }

    suspend fun removeItemFromMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
        playlistItemId: String? = null,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            plex.removeItemFromMediaContainer(
                server,
                containerId,
                kind,
                itemId,
                playlistItemId,
            )
        } else {
            browseService.removeItemFromMediaContainer(
                server,
                containerId,
                kind,
                itemId,
                playlistItemId,
            )
        }

    suspend fun addToWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            plexWatchlist(server, itemId) { token, key ->
                plexCloud.setWatchlist(token, key, inWatchlist = true)
            }
        } else {
            browseService.addToWatchLater(server, itemId)
        }

    suspend fun isInWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Boolean> =
        if (server.kind == MediaServerKind.Plex) {
            plexWatchlist(server, itemId, plexCloud::isInWatchlist)
        } else {
            browseService.isInWatchLater(server, itemId)
        }

    suspend fun removeFromWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            plexWatchlist(server, itemId) { token, key ->
                plexCloud.setWatchlist(token, key, inWatchlist = false)
            }
        } else {
            browseService.removeFromWatchLater(server, itemId)
        }

    private suspend fun <T> plexWatchlist(
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

    suspend fun reportPlaybackStarted(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String = "DirectPlay",
    ): Result<Unit> =
        if (progressProjection.localOnly) {
            Result.success(Unit)
        } else if (server.kind == MediaServerKind.Plex) {
            plex.reportPlayback(server, itemId, playSessionId, positionTicks, isPaused, stopped = false)
        } else {
            playbackService.reportStarted(
                server = server,
                itemId = itemId,
                playSessionId = playSessionId,
                positionTicks = positionTicks,
                isPaused = isPaused,
                playMethod = playMethod,
            )
        }

    suspend fun reportPlaybackProgress(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String = "DirectPlay",
    ): Result<Unit> =
        if (progressProjection.localOnly) {
            Result.success(Unit)
        } else if (server.kind == MediaServerKind.Plex) {
            plex.reportPlayback(server, itemId, playSessionId, positionTicks, isPaused, stopped = false)
        } else {
            playbackService.reportProgress(
                server = server,
                itemId = itemId,
                playSessionId = playSessionId,
                positionTicks = positionTicks,
                isPaused = isPaused,
                playMethod = playMethod,
            )
        }

    suspend fun reportPlaybackStopped(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String = "DirectPlay",
    ): Result<Unit> =
        if (progressProjection.localOnly) {
            Result.success(Unit)
        } else if (server.kind == MediaServerKind.Plex) {
            plex.reportPlayback(server, itemId, playSessionId, positionTicks, isPaused, stopped = true)
        } else {
            playbackService.reportStopped(
                server = server,
                itemId = itemId,
                playSessionId = playSessionId,
                positionTicks = positionTicks,
                isPaused = isPaused,
                playMethod = playMethod,
            )
        }

    /**
     * Negotiates the actual source URL and playback method with the server.
     * Metadata endpoints describe files; PlaybackInfo applies this device profile and is
     * the authoritative answer for DirectPlay, DirectStream, or Transcode.
     */
    suspend fun playbackInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String? = null,
        startPositionTicks: Long = 0L,
        playSessionId: String,
        sourceRequiresDolbyDecoder: Boolean = false,
    ): Result<PlaybackInfoResponseDto> =
        if (server.kind == MediaServerKind.Plex) {
            plex.playbackInfo(server, itemId, mediaSourceId, playSessionId)
        } else {
            playbackService.playbackInfo(
                server = server,
                itemId = itemId,
                mediaSourceId = mediaSourceId,
                startPositionTicks = startPositionTicks,
                playSessionId = playSessionId,
                sourceRequiresDolbyDecoder = sourceRequiresDolbyDecoder,
            )
        }

    suspend fun probeServer(server: SavedServer): Result<Long> =
        if (server.kind == MediaServerKind.Plex) plex.probe(server) else serverService.probe(server)

    suspend fun probeAddress(
        baseUrl: String,
        accessToken: String,
        kind: MediaServerKind = MediaServerKind.Emby,
    ): Result<Long> =
        if (kind == MediaServerKind.Plex) {
            plex.probeAddress(baseUrl, accessToken)
        } else {
            serverService.probeAddress(baseUrl, accessToken)
        }

    /** Server-wide Movie/Series totals, for the server cards' at-a-glance figures. */
    suspend fun itemCounts(server: SavedServer): Result<LibraryCounts> =
        if (server.kind == MediaServerKind.Plex) {
            plex.itemCounts(server)
        } else {
            embyApiCall("item_counts") { libraryService.counts(server) }
        }

    suspend fun homeContent(server: SavedServer): Result<HomeContent> =
        if (server.kind == MediaServerKind.Plex) plex.homeContent(server) else homeService.homeContent(server)

    suspend fun mediaContainerItems(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
        genre: String? = null,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
        resolution: LibraryResolution = LibraryResolution.All,
    ): Result<LibraryPage> =
        if (server.kind == MediaServerKind.Plex) {
            plex.mediaContainerItems(server, containerId, kind, sort, genre, startIndex, limit, resolution)
        } else {
            browseService.mediaContainerItems(server, containerId, kind, sort, genre, startIndex, limit, resolution)
        }

    suspend fun mediaContainerGenres(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
    ): Result<List<String>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.mediaContainerGenres(server, containerId, kind)
        } else {
            browseService.mediaContainerGenres(server, containerId, kind)
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
        if (server.kind == MediaServerKind.Plex) {
            plex.libraryItems(server, libraryId, sort, genre, startIndex, limit, resolution)
        } else {
            browseService.libraryItems(server, libraryId, sort, genre, startIndex, limit, resolution)
        }

    suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.libraryGenres(server, libraryId)
        } else {
            browseService.libraryGenres(server, libraryId)
        }

    suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int = 12,
    ): Result<List<MediaItem>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.similarItems(server, itemId, limit)
        } else {
            detailService.similarItems(server, itemId, limit)
        }

    suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget> =
        if (server.kind == MediaServerKind.Plex) {
            plex.resolvePlayTarget(server, detail)
        } else {
            detailService.resolvePlayTarget(server, detail)
        }

    /** Playback target plus a reusable series directory when the provider can return both cheaply. */
    internal suspend fun resolvePlayTargetWithEpisodes(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTargetResolution> {
        if (server.kind != MediaServerKind.Plex) {
            return detailService.resolvePlayTargetWithEpisodes(server, detail)
        }
        val target = plex.resolvePlayTarget(server, detail).getOrElse { return Result.failure(it) }
        return Result.success(PlayTargetResolution(target))
    }

    /** Libraries available to advanced search filters. */
    suspend fun mediaLibraries(server: SavedServer): Result<List<MediaLibrary>> = libraries(server)

    suspend fun searchGenres(
        server: SavedServer,
        parentId: String? = null,
    ): Result<List<String>> =
        if (server.kind == MediaServerKind.Plex && parentId != null) {
            plex.libraryGenres(server, parentId)
        } else if (server.kind == MediaServerKind.Plex) {
            Result.success(emptyList())
        } else {
            searchService.genres(server, parentId)
        }

    suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int = 12,
    ): Result<List<MediaItem>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.nextUpEpisodes(server, limit)
        } else {
            detailService.nextUpEpisodes(server, limit)
        }

    /** Title search with filters executed by Emby rather than against a truncated client list. */
    suspend fun search(
        server: SavedServer,
        query: String,
        limit: Int = 24,
        filter: MediaSearchFilter = MediaSearchFilter(),
    ): Result<List<MediaItem>> =
        searchPage(
            server = server,
            query = query,
            startIndex = 0,
            limit = limit,
            filter = filter,
        ).map(MediaSearchPage::items)

    /** Search page with offset/total preserved so UI pagination is not a fake fixed cap. */
    suspend fun searchPage(
        server: SavedServer,
        query: String,
        startIndex: Int = 0,
        limit: Int = 24,
        filter: MediaSearchFilter = MediaSearchFilter(),
    ): Result<MediaSearchPage> =
        if (server.kind == MediaServerKind.Plex) {
            plex.searchPage(server, query, startIndex, limit, filter)
        } else {
            searchService.searchPage(server, query, startIndex, limit, filter)
        }

    /**
     * People whose name matches the query, for the search tab's 演员 row.
     *
     * Failure is intentionally degraded to an empty row by the focused search service.
     */
    suspend fun searchPeople(
        server: SavedServer,
        query: String,
        limit: Int = PERSON_SEARCH_LIMIT,
    ): List<Person> =
        if (server.kind == MediaServerKind.Plex) {
            plex.searchPeople(server, query, limit)
        } else {
            searchService.searchPeople(server, query, limit)
        }

    /** Everything on this server that credits one person, newest first. */
    suspend fun itemsByPerson(
        server: SavedServer,
        personId: String,
        limit: Int = PERSON_ITEMS_LIMIT,
    ): Result<List<MediaItem>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.itemsByPerson(server, personId, limit)
        } else {
            searchService.itemsByPerson(server, personId, limit)
        }

    /** Complete paged user-state snapshot used by the multi-server sync coordinator. */
    suspend fun userLibrarySnapshot(
        server: SavedServer,
        includeProgress: Boolean = true,
        includeFavorites: Boolean = true,
    ): Result<List<SyncedUserItem>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.userLibrarySnapshot(server, includeProgress)
        } else {
            userDataService.snapshot(server, includeProgress, includeFavorites)
        }

    /**
     * Asks the server to end the encoding started for [playSessionId] on this device.
     *
     * `Playing/Stopped` alone does not always reap the ffmpeg process — and never did while
     * the stream URL carried no session id to match against. Failure is not worth surfacing:
     * the job may already be gone, or the server may be the one that is unreachable.
     */
    suspend fun stopTranscoding(
        server: SavedServer,
        playSessionId: String,
    ): Result<Unit> =
        if (server.kind == MediaServerKind.Plex) {
            plex.stopTranscoding(server, playSessionId)
        } else {
            playbackService.stopTranscoding(server, playSessionId)
        }

    suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> =
        if (server.kind == MediaServerKind.Plex) {
            plex.findByTmdbId(server, tmdbId, mediaType)
        } else {
            lookupService.findByTmdbId(server, tmdbId, mediaType)
        }

    suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?> =
        if (server.kind == MediaServerKind.Plex) {
            plex.findByMediaKey(server, mediaKey)
        } else {
            lookupService.findByMediaKey(server, mediaKey)
        }

    suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
    ): Result<MediaDetail> =
        if (server.kind == MediaServerKind.Plex) {
            plex.itemDetail(server, itemId)
        } else {
            detailService.itemDetail(server, itemId)
        }

    suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int? = null,
        mediaType: String? = null,
        year: Int? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): List<ServerSource> {
        val compatibleServers = servers.filterNot { it.kind == MediaServerKind.Plex }
        val compatible =
            sourceService.compareSources(
                servers = compatibleServers,
                currentServerId = currentServerId,
                title = title,
                tmdbId = tmdbId,
                mediaType = mediaType,
                year = year,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
            )
        val plexSources =
            servers.filter { it.kind == MediaServerKind.Plex }.map { server ->
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
        val byId = (compatible + plexSources).associateBy(ServerSource::serverId)
        return servers.mapNotNull { byId[it.id] }
    }

    suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.seasons(server, seriesId)
        } else {
            detailService.seasons(server, seriesId)
        }

    suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.seriesProviderIndex(server)
        } else {
            lookupService.seriesProviderIndex(server)
        }

    suspend fun seriesIdentityCatalog(server: SavedServer): Result<List<LibrarySeriesIdentity>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.seriesIdentityCatalog(server)
        } else {
            lookupService.seriesIdentityCatalog(server)
        }

    suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.movieProviderIndex(server)
        } else {
            lookupService.movieProviderIndex(server)
        }

    /** Episodes of a season (or of the whole series when [seasonId] is null). */
    suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean = false,
        seasonNumber: Int? = null,
    ): Result<List<Episode>> =
        if (server.kind == MediaServerKind.Plex) {
            plex.episodes(server, seriesId, seasonId, includeMediaSources, seasonNumber)
        } else {
            detailService.episodes(
                server = server,
                seriesId = seriesId,
                seasonId = seasonId,
                includeMediaSources = includeMediaSources,
                seasonNumber = seasonNumber,
            )
        }

    suspend fun trickplayInfo(
        server: SavedServer,
        itemId: String,
        mediaSourceId: String = itemId,
    ): Result<TrickplayInfo?> =
        if (server.kind == MediaServerKind.Plex) {
            plex.trickplayInfo(server, itemId)
        } else {
            detailService.trickplayInfo(server, itemId, mediaSourceId)
        }

    suspend fun searchRemoteSubtitles(
        server: SavedServer,
        itemId: String,
        language: String = "zh",
    ): Result<List<RemoteSubtitleInfoDto>> =
        if (!server.kind.capabilities().subtitleStore) {
            Result.success(emptyList())
        } else {
            subtitleService.search(server, itemId, language)
        }

    suspend fun downloadRemoteSubtitle(
        server: SavedServer,
        itemId: String,
        subtitleId: String,
    ): Result<Unit> =
        if (!server.kind.capabilities().subtitleStore) {
            Result.failure(UnsupportedOperationException("Plex 不支持 Emby 字幕商店接口"))
        } else {
            subtitleService.download(server, itemId, subtitleId)
        }
}
