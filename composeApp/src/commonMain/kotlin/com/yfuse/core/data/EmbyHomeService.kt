package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.MediaSourceDto
import com.yfuse.core.data.dto.PlaybackInfoResponseDto
import com.yfuse.core.data.dto.PlaylistCreatedDto
import com.yfuse.core.data.dto.PublicUserDto
import com.yfuse.core.data.dto.RemoteSubtitleInfoDto
import com.yfuse.core.data.dto.bestTrickplay
import com.yfuse.core.data.dto.toEpisode
import com.yfuse.core.data.dto.toMediaDetail
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.data.dto.toPerson
import com.yfuse.core.data.dto.toSeason
import com.yfuse.core.data.dto.toSourceInfo
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.Episode
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
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
import com.yfuse.core.model.SourceInfo
import com.yfuse.core.model.TrickplayInfo
import com.yfuse.core.model.compareSourceInfoBestFirst
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.normalizeBaseUrl
import com.yfuse.core.sync.SyncedUserItem
import com.yfuse.core.sync.parseEpisodeWatchKey
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.io.IOException
import kotlin.time.TimeSource

internal class EmbyHomeService(
    private val client: HttpClient,
    private val libraryService: EmbyLibraryService,
    private val browseService: EmbyBrowseService,
) {
    /** Aggregates the home screen: continue-watching, latest-per-library, featured. */
    suspend fun homeContent(server: SavedServer): Result<HomeContent> =
        embyApiCall("home_content") {
            coroutineScope {
                val views = libraryService.views(server)
                // A single library (or the resume row) failing must not blank the
                // whole home screen — degrade to an empty row instead.
                val resumeDeferred =
                    async {
                        runCatching { fetchResume(server) }
                            .onFailure {
                                AppLog.warning(
                                    category = "emby",
                                    event = "home_section_degraded",
                                    message = "Continue-watching section failed and was omitted",
                                    throwable = it,
                                    attributes =
                                        mapOf(
                                            "serverId" to server.id,
                                            "section" to "resume",
                                        ),
                                )
                            }.getOrDefault(emptyList())
                    }
                val favoritesDeferred =
                    async {
                        runCatching {
                            val collection = browseService.fetchFavorites(server, PERSONAL_COLLECTION_PREVIEW_LIMIT)
                            HomeRow(
                                libraryId = FAVORITES_COLLECTION_ID,
                                title = "我的收藏",
                                items = collection.items,
                                totalCount = collection.totalCount,
                            )
                        }.onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "home_section_degraded",
                                message = "Favorites section failed and was left empty",
                                throwable = it,
                                attributes =
                                    mapOf(
                                        "serverId" to server.id,
                                        "section" to "favorites",
                                    ),
                            )
                        }.getOrDefault(HomeRow(FAVORITES_COLLECTION_ID, "我的收藏", emptyList()))
                    }
                val watchLaterDeferred =
                    async {
                        runCatching {
                            val collection = browseService.fetchWatchLater(server, PERSONAL_COLLECTION_PREVIEW_LIMIT)
                            HomeRow(
                                libraryId = WATCH_LATER_COLLECTION_ID,
                                title = "稍后观看",
                                items = collection.items,
                                totalCount = collection.totalCount,
                            )
                        }.onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "home_section_degraded",
                                message = "Watch-later section failed and was left empty",
                                throwable = it,
                                attributes =
                                    mapOf(
                                        "serverId" to server.id,
                                        "section" to "watch_later",
                                    ),
                            )
                        }.getOrDefault(HomeRow(WATCH_LATER_COLLECTION_ID, "稍后观看", emptyList()))
                    }
                val countsDeferred =
                    async {
                        runCatching { libraryService.counts(server) }
                            .onFailure {
                                // Counts are useful footer metadata, not a reason to blank an
                                // otherwise healthy library page. A missing/older endpoint simply
                                // leaves the footer hidden until a later refresh succeeds.
                                AppLog.warning(
                                    category = "emby",
                                    event = "library_counts_degraded",
                                    message = "Library title counts failed and were omitted",
                                    throwable = it,
                                    attributes = mapOf("serverId" to server.id),
                                )
                            }.getOrNull()
                    }
                val collectionsDeferred =
                    async {
                        runCatching {
                            browseService.fetchMediaContainers(
                                server,
                                MediaContainerKind.BoxSet,
                                startIndex = 0,
                                limit = MEDIA_CONTAINER_PREVIEW_LIMIT,
                            ).containers
                        }.onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "home_section_degraded",
                                message = "Collection previews failed and were omitted",
                                throwable = it,
                                attributes =
                                    mapOf(
                                        "serverId" to server.id,
                                        "section" to "collections",
                                    ),
                            )
                        }.getOrDefault(emptyList())
                    }
                val playlistsDeferred =
                    async {
                        runCatching {
                            browseService.fetchMediaContainers(
                                server,
                                MediaContainerKind.Playlist,
                                startIndex = 0,
                                limit = MEDIA_CONTAINER_PREVIEW_LIMIT,
                            ).containers
                        }.onFailure {
                            AppLog.warning(
                                category = "emby",
                                event = "home_section_degraded",
                                message = "Playlist previews failed and were omitted",
                                throwable = it,
                                attributes =
                                    mapOf(
                                        "serverId" to server.id,
                                        "section" to "playlists",
                                    ),
                            )
                        }.getOrDefault(emptyList())
                    }
                val rowDeferred =
                    views.map { view ->
                        async {
                            val items =
                                runCatching { fetchLatest(server, view.id) }
                                    .onFailure {
                                        AppLog.warning(
                                            category = "emby",
                                            event = "home_section_degraded",
                                            message = "Library latest-items section failed and was omitted",
                                            throwable = it,
                                            attributes =
                                                mapOf(
                                                    "serverId" to server.id,
                                                    "section" to "latest",
                                                    "libraryId" to view.id,
                                                ),
                                        )
                                    }.getOrDefault(emptyList())
                            // The chip shows the library's real size, not the loaded page.
                            val total =
                                runCatching { fetchLibraryCount(server, view.id) }
                                    .onFailure {
                                        AppLog.warning(
                                            category = "emby",
                                            event = "library_count_degraded",
                                            message = "Library count failed; loaded item count used as fallback",
                                            throwable = it,
                                            attributes =
                                                mapOf(
                                                    "serverId" to server.id,
                                                    "libraryId" to view.id,
                                                ),
                                        )
                                    }.getOrDefault(items.size)
                            HomeRow(view.id, view.name, items, total)
                        }
                    }
                val resume = resumeDeferred.await()
                val counts = countsDeferred.await()
                val collections = collectionsDeferred.await()
                val playlists = playlistsDeferred.await()
                val rows =
                    listOf(favoritesDeferred.await(), watchLaterDeferred.await()) +
                        rowDeferred.awaitAll().filter { it.items.isNotEmpty() }
                val featured =
                    (resume + rows.flatMap { it.items })
                        .filter { it.backdropTag != null }
                        .distinctBy { it.id }
                        .take(6)
                HomeContent(
                    featured = featured,
                    resume = resume,
                    rows = rows,
                    counts = counts,
                    collections = collections,
                    playlists = playlists,
                )
            }
        }

    /** `Limit=0` returns just the count, which is all the category chip needs. */
    private suspend fun fetchLibraryCount(
        server: SavedServer,
        viewId: String,
    ): Int {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("ParentId", viewId)
                    parameter("Recursive", true)
                    parameter("IncludeItemTypes", "Movie,Series")
                    parameter("Limit", 0)
                }.body()
        return dto.TotalRecordCount ?: 0
    }

    private suspend fun fetchResume(server: SavedServer): List<MediaItem> {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items/Resume") {
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

    private suspend fun fetchLatest(
        server: SavedServer,
        viewId: String,
    ): List<MediaItem> {
        val items: List<BaseItemDto> =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items/Latest") {
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
}
