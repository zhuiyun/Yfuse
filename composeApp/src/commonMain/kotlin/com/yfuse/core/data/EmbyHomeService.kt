package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.HomeRow
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal class EmbyHomeService(
    private val client: HttpClient,
    private val libraryService: EmbyLibraryService,
    private val browseService: EmbyBrowseService,
    private val progress: PlaybackProgressProjection = PlaybackProgressProjection(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Aggregates the home screen: continue-watching, latest-per-library, featured. */
    suspend fun homeContent(server: SavedServer): Result<HomeContent> =
        embyApiCall("home_content") {
            coroutineScope {
                val views =
                    withTimeoutOrNull(HOME_VIEWS_TIMEOUT_MS) {
                        libraryService.views(server)
                    } ?: throw IOException("媒体库目录加载超时，请重试")
                // One server can expose dozens of views. Bound the section fan-out so opening
                // Home does not turn those rows into a TLS/HTTP connection storm.
                val sectionPermits = Semaphore(4)
                suspend fun <T> boundedSection(block: suspend () -> T): T =
                    sectionPermits.withPermit {
                        // Waiting for another section is not time spent requesting this one.
                        withTimeout(HOME_SECTION_TIMEOUT_MS) { block() }
                    }
                // A failed preview must not blank the home screen or hide a library's entry.
                val resumeDeferred =
                    async {
                        runCatching { boundedSection { fetchResume(server) } }
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
                            boundedSection {
                                val collection =
                                    browseService.fetchFavorites(server, PERSONAL_COLLECTION_PREVIEW_LIMIT)
                                HomeRow(
                                    libraryId = FAVORITES_COLLECTION_ID,
                                    title = "我的收藏",
                                    items = collection.items,
                                    totalCount = collection.totalCount,
                                )
                            }
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
                        }.getOrDefault(HomeRow(FAVORITES_COLLECTION_ID, "我的收藏", emptyList(), loadFailed = true))
                    }
                val watchLaterDeferred =
                    async {
                        runCatching {
                            boundedSection {
                                val collection =
                                    browseService.fetchWatchLater(server, PERSONAL_COLLECTION_PREVIEW_LIMIT)
                                HomeRow(
                                    libraryId = WATCH_LATER_COLLECTION_ID,
                                    title = "稍后观看",
                                    items = collection.items,
                                    totalCount = collection.totalCount,
                                )
                            }
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
                        }.getOrDefault(HomeRow(WATCH_LATER_COLLECTION_ID, "稍后观看", emptyList(), loadFailed = true))
                    }
                val countsDeferred =
                    async {
                        runCatching { boundedSection { libraryService.counts(server) } }
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
                            boundedSection {
                                browseService
                                    .fetchMediaContainers(
                                        server,
                                        MediaContainerKind.BoxSet,
                                        startIndex = 0,
                                        limit = MEDIA_CONTAINER_PREVIEW_LIMIT,
                                    ).containers
                            }
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
                            boundedSection {
                                browseService
                                    .fetchMediaContainers(
                                        server,
                                        MediaContainerKind.Playlist,
                                        startIndex = 0,
                                        limit = MEDIA_CONTAINER_PREVIEW_LIMIT,
                                    ).containers
                            }
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
                            val itemsDeferred =
                                async {
                                    runCatching { boundedSection { fetchLatest(server, view.id) } }
                                }
                            val totalDeferred =
                                async {
                                    runCatching { boundedSection { fetchLibraryCount(server, view.id) } }
                                }
                            val itemsResult =
                                itemsDeferred.await().onFailure {
                                    AppLog.warning(
                                        category = "emby",
                                        event = "home_section_degraded",
                                        message = "Library preview failed; browse entry was retained",
                                        throwable = it,
                                        attributes =
                                            mapOf(
                                                "serverId" to server.id,
                                                "section" to "latest",
                                                "libraryId" to view.id,
                                            ),
                                    )
                                }
                            val items = itemsResult.getOrDefault(emptyList())
                            // The chip shows the library's real size, not the loaded page.
                            val total =
                                totalDeferred.await().onFailure {
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
                            HomeRow(view.id, view.name, items, total, loadFailed = itemsResult.isFailure)
                        }
                    }
                val resume = resumeDeferred.await()
                val counts = countsDeferred.await()
                val collections = collectionsDeferred.await()
                val playlists = playlistsDeferred.await()
                val rows =
                    listOf(favoritesDeferred.await(), watchLaterDeferred.await()) +
                        rowDeferred.awaitAll()
                val featured =
                    (resume + rows.flatMap { it.items })
                        .filter { it.backdropTag != null }
                        .distinctBy { it.id }
                        .take(8)
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
        val ids =
            progress
                .localStates(server)
                .asSequence()
                .filter { !it.played && it.positionMs > 0L }
                .mapNotNull { it.serverItemId }
                .distinct()
                .take(12)
                .toList()
        if (ids.isEmpty()) return emptyList()
        val localItems: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Ids", ids.joinToString(","))
                    parameter(
                        "Fields",
                            "BackdropImageTags,UserData,Overview,CommunityRating,ParentBackdropItemId," +
                            "ParentBackdropImageTags,SeriesPrimaryImageTag,RunTimeTicks,ProviderIds",
                    )
                    parameter("EnableImageTypes", "Primary,Backdrop")
                    parameter("ImageTypeLimit", 2)
                    parameter("Limit", ids.size)
                }.body()
        val byId = localItems.Items.associateBy(BaseItemDto::Id)
        return ids.mapNotNull(byId::get).map { progress.project(server, it).toMediaItem() }
    }

    private suspend fun fetchLatest(
        server: SavedServer,
        viewId: String,
    ): List<MediaItem> {
        val response =
            client.get("${server.baseUrl}/Users/${server.userId}/Items/Latest") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("ParentId", viewId)
                    parameter("Limit", 16)
                    // Overview feeds the carousel synopsis.
                    parameter(
                        "Fields",
                        "BackdropImageTags,ProductionYear,Overview,CommunityRating,UserData,ParentBackdropItemId," +
                            "ParentBackdropImageTags,SeriesPrimaryImageTag,RunTimeTicks",
                    )
                    parameter("EnableImageTypes", "Primary,Backdrop")
                    parameter("ImageTypeLimit", 2)
                }
        // Use the generated serializer explicitly. Release shrinking can erase the reflective
        // generic List type that Ktor's body<List<...>>() converter otherwise relies on.
        val items =
            json.decodeFromString(
                ListSerializer(BaseItemDto.serializer()),
                response.bodyAsText(),
            )
        return items.map { progress.project(server, it).toMediaItem() }
    }
}

private const val HOME_VIEWS_TIMEOUT_MS = 15_000L
private const val HOME_SECTION_TIMEOUT_MS = 15_000L
