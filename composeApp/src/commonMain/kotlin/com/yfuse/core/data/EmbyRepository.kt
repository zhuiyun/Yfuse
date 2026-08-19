package com.yfuse.core.data

import com.yfuse.core.data.dto.PlaybackInfoResponseDto
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.data.dto.RemoteSubtitleInfoDto
import com.yfuse.core.model.Episode
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.LibraryPage
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
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackDeviceCapabilitiesProvider
import com.yfuse.core.sync.SyncedUserItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get

/** Result of a successful authentication, ready to persist as a [SavedServer]. */
data class AuthedServer(
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
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

/** Backstop against a server whose `TotalRecordCount` is wrong, or a paging loop. */
internal const val SNAPSHOT_MAX_ITEMS = 100_000

internal fun userLibrarySnapshotIsTruncated(
    collectedItems: Int,
    reportedTotal: Int,
    maxItems: Int,
): Boolean = collectedItems >= maxItems && reportedTotal > maxItems

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
) {
    private val authService = EmbyAuthService(client)
    private val detailService = EmbyDetailService(client)
    private val sourceService = EmbySourceService(client, detailService)
    private val libraryService = EmbyLibraryService(client)
    private val browseService = EmbyBrowseService(client)
    private val homeService = EmbyHomeService(client, libraryService, browseService)
    private val lookupService = EmbyLookupService(client)
    private val playbackService =
        EmbyPlaybackService(client, capabilitiesProvider, audioPassthroughEnabled)
    private val searchService = EmbySearchService(client)
    private val serverService = EmbyServerService(client)
    private val subtitleService = EmbySubtitleService(client)
    private val userDataService = EmbyUserDataService(client)

    suspend fun publicUsers(baseUrl: String): Result<List<PublicUserDto>> = authService.publicUsers(baseUrl)

    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<AuthedServer> = authService.authenticate(baseUrl, username, password)

    suspend fun libraries(server: SavedServer): Result<List<MediaLibrary>> =
        embyApiCall("libraries") { libraryService.views(server) }

    suspend fun mediaContainers(server: SavedServer): Result<List<MediaContainer>> =
        browseService.mediaContainers(server)

    suspend fun mediaContainersPage(
        server: SavedServer,
        kind: MediaContainerKind,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<MediaContainerPage> = browseService.mediaContainersPage(server, kind, startIndex, limit)

    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> = userDataService.setFavorite(server, itemId, favorite)

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> = userDataService.setPlayed(server, itemId, played)

    suspend fun addItemToMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
    ): Result<Unit> = browseService.addItemToMediaContainer(server, containerId, kind, itemId)

    suspend fun removeItemFromMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
        playlistItemId: String? = null,
    ): Result<Unit> =
        browseService.removeItemFromMediaContainer(
            server,
            containerId,
            kind,
            itemId,
            playlistItemId,
        )

    suspend fun addToWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = browseService.addToWatchLater(server, itemId)

    suspend fun isInWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Boolean> = browseService.isInWatchLater(server, itemId)

    suspend fun removeFromWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> = browseService.removeFromWatchLater(server, itemId)

    suspend fun reportPlaybackStarted(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String = "DirectPlay",
    ): Result<Unit> =
        playbackService.reportStarted(
            server = server,
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playMethod = playMethod,
        )

    suspend fun reportPlaybackProgress(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String = "DirectPlay",
    ): Result<Unit> =
        playbackService.reportProgress(
            server = server,
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playMethod = playMethod,
        )

    suspend fun reportPlaybackStopped(
        server: SavedServer,
        itemId: String,
        playSessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String = "DirectPlay",
    ): Result<Unit> =
        playbackService.reportStopped(
            server = server,
            itemId = itemId,
            playSessionId = playSessionId,
            positionTicks = positionTicks,
            isPaused = isPaused,
            playMethod = playMethod,
        )

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
    ): Result<PlaybackInfoResponseDto> =
        playbackService.playbackInfo(
            server = server,
            itemId = itemId,
            mediaSourceId = mediaSourceId,
            startPositionTicks = startPositionTicks,
            playSessionId = playSessionId,
        )

    suspend fun probeServer(server: SavedServer): Result<Long> = serverService.probe(server)

    suspend fun probeAddress(
        baseUrl: String,
        accessToken: String,
    ): Result<Long> = serverService.probeAddress(baseUrl, accessToken)

    /** Server-wide Movie/Series totals, for the server cards' at-a-glance figures. */
    suspend fun itemCounts(server: SavedServer): Result<LibraryCounts> =
        embyApiCall("item_counts") {
            libraryService.counts(server)
        }

    suspend fun homeContent(server: SavedServer): Result<HomeContent> = homeService.homeContent(server)

    suspend fun mediaContainerItems(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
        genre: String? = null,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<LibraryPage> =
        browseService.mediaContainerItems(server, containerId, kind, sort, genre, startIndex, limit)

    suspend fun mediaContainerGenres(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
    ): Result<List<String>> = browseService.mediaContainerGenres(server, containerId, kind)

    suspend fun libraryItems(
        server: SavedServer,
        libraryId: String,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
        genre: String? = null,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<LibraryPage> = browseService.libraryItems(server, libraryId, sort, genre, startIndex, limit)

    suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>> = browseService.libraryGenres(server, libraryId)

    suspend fun similarItems(
        server: SavedServer,
        itemId: String,
        limit: Int = 12,
    ): Result<List<MediaItem>> = detailService.similarItems(server, itemId, limit)

    suspend fun resolvePlayTarget(
        server: SavedServer,
        detail: MediaDetail,
    ): Result<PlayTarget> = detailService.resolvePlayTarget(server, detail)

    /** Libraries available to advanced search filters. */
    suspend fun mediaLibraries(server: SavedServer): Result<List<MediaLibrary>> =
        embyApiCall("search_libraries") { libraryService.views(server) }

    suspend fun searchGenres(
        server: SavedServer,
        parentId: String? = null,
    ): Result<List<String>> = searchService.genres(server, parentId)

    suspend fun nextUpEpisodes(
        server: SavedServer,
        limit: Int = 12,
    ): Result<List<MediaItem>> = detailService.nextUpEpisodes(server, limit)

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
    ): Result<MediaSearchPage> = searchService.searchPage(server, query, startIndex, limit, filter)

    /**
     * People whose name matches the query, for the search tab's 演员 row.
     *
     * Failure is intentionally degraded to an empty row by the focused search service.
     */
    suspend fun searchPeople(
        server: SavedServer,
        query: String,
        limit: Int = PERSON_SEARCH_LIMIT,
    ): List<Person> = searchService.searchPeople(server, query, limit)

    /** Everything on this server that credits one person, newest first. */
    suspend fun itemsByPerson(
        server: SavedServer,
        personId: String,
        limit: Int = PERSON_ITEMS_LIMIT,
    ): Result<List<MediaItem>> = searchService.itemsByPerson(server, personId, limit)

    /** Complete paged user-state snapshot used by the multi-server sync coordinator. */
    suspend fun userLibrarySnapshot(server: SavedServer): Result<List<SyncedUserItem>> =
        userDataService.snapshot(server)

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
    ): Result<Unit> = playbackService.stopTranscoding(server, playSessionId)

    suspend fun findByTmdbId(
        server: SavedServer,
        tmdbId: Int,
        mediaType: String,
    ): Result<MediaItem?> = lookupService.findByTmdbId(server, tmdbId, mediaType)

    suspend fun findByMediaKey(
        server: SavedServer,
        mediaKey: String,
    ): Result<MediaItem?> = lookupService.findByMediaKey(server, mediaKey)

    suspend fun itemDetail(
        server: SavedServer,
        itemId: String,
    ): Result<MediaDetail> = detailService.itemDetail(server, itemId)

    suspend fun compareSources(
        servers: List<SavedServer>,
        currentServerId: String?,
        title: String,
        tmdbId: Int? = null,
        mediaType: String? = null,
        year: Int? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
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

    suspend fun seasons(
        server: SavedServer,
        seriesId: String,
    ): Result<List<Season>> = detailService.seasons(server, seriesId)

    suspend fun seriesProviderIndex(server: SavedServer): Result<Map<String, String>> =
        lookupService.seriesProviderIndex(server)

    suspend fun movieProviderIndex(server: SavedServer): Result<Map<String, ProviderHit>> =
        lookupService.movieProviderIndex(server)

    /** Episodes of a season (or of the whole series when [seasonId] is null). */
    suspend fun episodes(
        server: SavedServer,
        seriesId: String,
        seasonId: String?,
        includeMediaSources: Boolean = false,
    ): Result<List<Episode>> = detailService.episodes(server, seriesId, seasonId, includeMediaSources)

    suspend fun trickplayInfo(
        server: SavedServer,
        itemId: String,
    ): Result<TrickplayInfo?> = detailService.trickplayInfo(server, itemId)

    suspend fun searchRemoteSubtitles(
        server: SavedServer,
        itemId: String,
        language: String = "zh",
    ): Result<List<RemoteSubtitleInfoDto>> = subtitleService.search(server, itemId, language)

    suspend fun downloadRemoteSubtitle(
        server: SavedServer,
        itemId: String,
        subtitleId: String,
    ): Result<Unit> = subtitleService.download(server, itemId, subtitleId)
}
