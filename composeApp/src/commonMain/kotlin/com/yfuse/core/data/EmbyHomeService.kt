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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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

    /** Publishes the directory immediately, then each completed section without waiting for counts. */
    suspend fun homeContent(
        server: SavedServer,
        initialContent: HomeContent = HomeContent(),
        onProgress: suspend (HomeContent) -> Unit = {},
    ): Result<HomeContent> =
        embyApiCall("home_content") {
            val views =
                withTimeoutOrNull(HOME_VIEWS_TIMEOUT_MS) {
                    libraryService.views(server)
                } ?: throw IOException("媒体库目录加载超时，请重试")
            val initialRows = initialContent.rows.associateBy { it.libraryId }
            var content =
                initialContent.copy(
                    rows =
                        listOf(
                            initialRows[FAVORITES_COLLECTION_ID]
                                ?: HomeRow(FAVORITES_COLLECTION_ID, "我的收藏", emptyList()),
                            initialRows[WATCH_LATER_COLLECTION_ID]
                                ?: HomeRow(WATCH_LATER_COLLECTION_ID, "稍后观看", emptyList()),
                        ) +
                            views.map { view ->
                                initialRows[view.id]?.copy(title = view.name)
                                    ?: HomeRow(view.id, view.name, emptyList())
                            },
                )
            onProgress(content)
            val contentLock = Mutex()
            val pendingPreviews =
                (
                    views.map { it.id } +
                        listOf(
                            FAVORITES_COLLECTION_ID,
                            WATCH_LATER_COLLECTION_ID,
                        )
                ).toMutableSet()

            suspend fun update(transform: (HomeContent) -> HomeContent) {
                contentLock.withLock {
                    val next = transform(content)
                    content =
                        next.copy(
                            featured =
                                (next.resume + next.rows.flatMap { it.items })
                                    .filter { it.backdropTag != null }
                                    .distinctBy { it.id }
                                    .take(8),
                        )
                    onProgress(content)
                }
            }
            // This budget includes queueing. A large or slow server cannot extend a refresh
            // indefinitely by starting a fresh timeout for each queued section.
            val completed =
                withTimeoutOrNull(HOME_ENRICHMENT_TIMEOUT_MS) {
                    coroutineScope {
                        val permits = Semaphore(4)

                        suspend fun <T> section(
                            name: String,
                            block: suspend () -> T,
                            apply: (HomeContent, T) -> HomeContent,
                        ) {
                            val result =
                                try {
                                    val value =
                                        permits.withPermit {
                                            withTimeoutOrNull(HOME_SECTION_TIMEOUT_MS) { block() }
                                                ?: throw IOException("媒体库分区加载超时")
                                        }
                                    Result.success(value)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    Result.failure(error)
                                }
                            result
                                .onSuccess { value -> update { apply(it, value) } }
                                .onFailure { error ->
                                    AppLog.warning(
                                        category = "emby",
                                        event = "home_section_degraded",
                                        message = "Library section failed; available content was retained",
                                        throwable = error,
                                        attributes = mapOf("serverId" to server.id, "section" to name),
                                    )
                                }
                        }
                        // Start previews before optional metadata so slow count endpoints never
                        // take every connection while visible library content is still queued.
                        views.forEach { view ->
                            launch {
                                section("latest", { fetchLatest(server, view.id) }) { current, items ->
                                    pendingPreviews.remove(view.id)
                                    current.copy(
                                        rows =
                                            current.rows.map { row ->
                                                if (row.libraryId == view.id) {
                                                    row.copy(
                                                        items = items,
                                                        totalCount = maxOf(row.totalCount, items.size),
                                                        loadFailed = false,
                                                    )
                                                } else {
                                                    row
                                                }
                                            },
                                    )
                                }
                            }
                        }
                        launch {
                            section(
                                "resume",
                                { fetchResume(server) },
                            ) { current, items -> current.copy(resume = items) }
                        }
                        launch {
                            section("favorites", {
                                browseService.fetchFavorites(server, PERSONAL_COLLECTION_PREVIEW_LIMIT)
                            }) { current, collection ->
                                pendingPreviews.remove(FAVORITES_COLLECTION_ID)
                                current.copy(
                                    rows =
                                        current.rows.map { row ->
                                            if (row.libraryId == FAVORITES_COLLECTION_ID) {
                                                HomeRow(
                                                    FAVORITES_COLLECTION_ID,
                                                    "我的收藏",
                                                    collection.items,
                                                    collection.totalCount,
                                                )
                                            } else {
                                                row
                                            }
                                        },
                                )
                            }
                        }
                        launch {
                            section("watch_later", {
                                browseService.fetchWatchLater(server, PERSONAL_COLLECTION_PREVIEW_LIMIT)
                            }) { current, collection ->
                                pendingPreviews.remove(WATCH_LATER_COLLECTION_ID)
                                current.copy(
                                    rows =
                                        current.rows.map { row ->
                                            if (row.libraryId == WATCH_LATER_COLLECTION_ID) {
                                                HomeRow(
                                                    WATCH_LATER_COLLECTION_ID,
                                                    "稍后观看",
                                                    collection.items,
                                                    collection.totalCount,
                                                )
                                            } else {
                                                row
                                            }
                                        },
                                )
                            }
                        }
                        launch {
                            section("collections", {
                                browseService
                                    .fetchMediaContainers(
                                        server,
                                        MediaContainerKind.BoxSet,
                                        0,
                                        MEDIA_CONTAINER_PREVIEW_LIMIT,
                                    ).containers
                            }) { current, containers -> current.copy(collections = containers) }
                        }
                        launch {
                            section("playlists", {
                                browseService
                                    .fetchMediaContainers(
                                        server,
                                        MediaContainerKind.Playlist,
                                        0,
                                        MEDIA_CONTAINER_PREVIEW_LIMIT,
                                    ).containers
                            }) { current, containers -> current.copy(playlists = containers) }
                        }
                        launch {
                            section(
                                "counts",
                                { libraryService.counts(server) },
                            ) { current, counts -> current.copy(counts = counts) }
                        }
                        views.forEach { view ->
                            launch {
                                section("library_count", { fetchLibraryCount(server, view.id) }) { current, count ->
                                    current.copy(
                                        rows =
                                            current.rows.map { row ->
                                                if (row.libraryId == view.id) row.copy(totalCount = count) else row
                                            },
                                    )
                                }
                            }
                        }
                    }
                    true
                } ?: false
            if (!completed) {
                AppLog.warning(
                    category = "emby",
                    event = "home_enrichment_timeout",
                    message = "Library refresh budget exhausted; completed sections remain available",
                    attributes = mapOf("serverId" to server.id, "pendingPreviews" to pendingPreviews.size.toString()),
                )
            }
            // All children have settled here, including those cancelled by the overall budget.
            content.copy(
                rows =
                    content.rows.map { row ->
                        if (row.libraryId in pendingPreviews) row.copy(loadFailed = true) else row
                    },
            )
        }

    /** Library sizes move slowly; one count per library per ten minutes is plenty for a chip. */
    private val libraryCounts = mutableMapOf<String, Pair<Int, Long>>()
    private val libraryCountsLock = Mutex()

    private suspend fun fetchLibraryCount(
        server: SavedServer,
        viewId: String,
    ): Int {
        val key = "${server.id}|$viewId"
        val now = System.currentTimeMillis()
        libraryCountsLock.withLock { libraryCounts[key] }?.let { (count, at) ->
            if (now - at < LIBRARY_COUNT_TTL_MS) return count
        }
        val count = requestLibraryCount(server, viewId)
        libraryCountsLock.withLock {
            if (libraryCounts.size >= MAX_CACHED_LIBRARY_COUNTS) libraryCounts.clear()
            libraryCounts[key] = count to now
        }
        return count
    }

    /** `Limit=0` returns just the count, which is all the category chip needs. */
    private suspend fun requestLibraryCount(
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

private const val LIBRARY_COUNT_TTL_MS = 10 * 60_000L
private const val MAX_CACHED_LIBRARY_COUNTS = 256
private const val HOME_VIEWS_TIMEOUT_MS = 15_000L
private const val HOME_SECTION_TIMEOUT_MS = 15_000L

private const val HOME_ENRICHMENT_TIMEOUT_MS = 30_000L
