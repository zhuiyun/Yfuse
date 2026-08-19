package com.yfuse.core.data

import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.PlaylistCreatedDto
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.LibraryPage
import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaContainer
import com.yfuse.core.model.MediaContainerKind
import com.yfuse.core.model.MediaContainerPage
import com.yfuse.core.model.SavedServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException

internal class EmbyBrowseService(
    private val client: HttpClient,
) {
    /** Real BoxSet and Playlist containers visible to this Emby user. */
    suspend fun mediaContainers(server: SavedServer): Result<List<MediaContainer>> =
        embyApiCall("media_containers") {
            fetchMediaContainers(server, kind = null, startIndex = 0, limit = MEDIA_CONTAINER_LIMIT)
                .containers
        }

    /** A server-paged directory of one container type for the library's 查看全部 route. */
    suspend fun mediaContainersPage(
        server: SavedServer,
        kind: MediaContainerKind,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<MediaContainerPage> =
        embyApiCall("media_containers_page") {
            fetchMediaContainers(server, kind, startIndex, limit)
        }

    /** Adds an existing media item to an existing server-owned BoxSet or Playlist. */
    suspend fun addItemToMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
    ): Result<Unit> =
        embyApiCall("add_item_to_media_container") {
            val path =
                when (kind) {
                    MediaContainerKind.BoxSet -> "Collections/${containerId.encodeURLPathPart()}/Items"
                    MediaContainerKind.Playlist -> "Playlists/${containerId.encodeURLPathPart()}/Items"
                }
            client.post("${server.baseUrl}/$path") {
                header("X-Emby-Token", server.accessToken)
                parameter("Ids", itemId)
                if (kind == MediaContainerKind.Playlist) parameter("UserId", server.userId)
            }
        }

    /**
     * Removes one membership. PlaylistService requires its entry id; substituting [itemId]
     * can remove nothing or the wrong repeated occurrence, so a missing entry id fails closed.
     */
    suspend fun removeItemFromMediaContainer(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        itemId: String,
        playlistItemId: String? = null,
    ): Result<Unit> =
        embyApiCall("remove_item_from_media_container") {
            when (kind) {
                MediaContainerKind.BoxSet ->
                    client.delete(
                        "${server.baseUrl}/Collections/${containerId.encodeURLPathPart()}/Items",
                    ) {
                        header("X-Emby-Token", server.accessToken)
                        parameter("Ids", itemId)
                    }

                MediaContainerKind.Playlist -> {
                    val entryId =
                        requireNotNull(playlistItemId?.takeIf(String::isNotBlank)) {
                            "PlaylistItemId is required to remove a playlist entry"
                        }
                    client.delete(
                        "${server.baseUrl}/Playlists/${containerId.encodeURLPathPart()}/Items",
                    ) {
                        header("X-Emby-Token", server.accessToken)
                        parameter("EntryIds", entryId)
                    }
                }
            }
        }

    /**
     * Emby has no special “watch later” flag. It is represented by a real
     * user playlist so it follows the account across clients and servers.
     */
    suspend fun addToWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        embyApiCall("add_to_watch_later") {
            val playlistId = findWatchLaterPlaylistId(server)
            if (playlistId != null) {
                client.post("${server.baseUrl}/Playlists/$playlistId/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Ids", itemId)
                    parameter("UserId", server.userId)
                }
            } else {
                val created: PlaylistCreatedDto =
                    client
                        .post("${server.baseUrl}/Playlists") {
                            header("X-Emby-Token", server.accessToken)
                            parameter("UserId", server.userId)
                            parameter("Name", "稍后观看")
                            parameter("Ids", itemId)
                        }.body()
                require(!created.Id.isNullOrBlank()) { "playlist was not created" }
            }
        }

    suspend fun isInWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Boolean> =
        embyApiCall("watch_later_membership") {
            findWatchLaterMembership(server, itemId)?.matched == true
        }

    suspend fun removeFromWatchLater(
        server: SavedServer,
        itemId: String,
    ): Result<Unit> =
        embyApiCall("remove_from_watch_later") {
            val membership = findWatchLaterMembership(server, itemId) ?: return@embyApiCall
            if (!membership.matched) return@embyApiCall
            require(membership.entryIds.isNotEmpty()) {
                "PlaylistItemId is required to remove a watch-later entry"
            }
            client.delete(
                "${server.baseUrl}/Playlists/${membership.playlistId.encodeURLPathPart()}/Items",
            ) {
                header("X-Emby-Token", server.accessToken)
                parameter("EntryIds", membership.entryIds.joinToString(","))
            }
        }

    /**
     * One page inside a real BoxSet or Playlist.
     *
     * PlaylistService deliberately exposes no sort parameters: omitting them preserves the
     * server's hand-arranged order. BoxSet is a folder and therefore uses the normal user Items
     * endpoint with ParentId, where the existing sort and genre controls remain valid.
     */
    suspend fun mediaContainerItems(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
        genre: String? = null,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<LibraryPage> =
        embyApiCall("media_container_items") {
            val dto: ItemsResponseDto =
                when (kind) {
                    MediaContainerKind.BoxSet ->
                        client
                            .get("${server.baseUrl}/Users/${server.userId}/Items") {
                                header("X-Emby-Token", server.accessToken)
                                parameter("ParentId", containerId)
                                parameter("IncludeItemTypes", "Movie,Series,Episode,Video,MusicVideo")
                                parameter("SortBy", sort.sortBy)
                                parameter("SortOrder", if (sort.descending) "Descending" else "Ascending")
                                if (!genre.isNullOrBlank()) parameter("Genres", genre)
                                containerItemParameters(startIndex, limit, includePlaylistItemId = false)
                            }.body()

                    MediaContainerKind.Playlist ->
                        client
                            .get("${server.baseUrl}/Playlists/$containerId/Items") {
                                header("X-Emby-Token", server.accessToken)
                                parameter("UserId", server.userId)
                                // No SortBy/SortOrder here: this endpoint's original order is meaningful.
                                containerItemParameters(startIndex, limit, includePlaylistItemId = true)
                            }.body()
                }
            LibraryPage(
                items = dto.Items.map { it.toMediaItem() },
                totalCount =
                    pageTotal(
                        reportedTotal = dto.TotalRecordCount,
                        startIndex = startIndex,
                        itemCount = dto.Items.size,
                        limit = limit,
                    ),
                startIndex = startIndex,
            )
        }

    /** Genre facets apply to BoxSet folders, never to a hand-ordered Playlist. */
    suspend fun mediaContainerGenres(
        server: SavedServer,
        containerId: String,
        kind: MediaContainerKind,
    ): Result<List<String>> {
        if (kind == MediaContainerKind.Playlist) return Result.success(emptyList())
        return runCatching {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Genres") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("ParentId", containerId)
                        parameter("IncludeItemTypes", "Movie,Series,Episode,Video,MusicVideo")
                        parameter("SortBy", "SortName")
                        parameter("SortOrder", "Ascending")
                        parameter("Limit", LIBRARY_GENRE_LIMIT)
                    }.body()
            dedupeBilingualGenreLabels(
                dto.Items.mapNotNull { it.Name?.takeIf(String::isNotBlank) },
            )
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.warning(
                category = "emby",
                event = "container_genres_unavailable",
                message = "Collection genre facet is unavailable",
                throwable = it,
                attributes = mapOf("containerId" to containerId),
            )
        }
    }

    /**
     * One page of a library, for the "see all" grid.
     *
     * Ordering, genre filtering and paging all run on the server. The previous version
     * asked for a fixed first 120 rows ordered by name and reordered them on the device,
     * so anything past those 120 had no route into the app at all, and 「最近添加」 was
     * really "the first 120 titles alphabetically, left in that order".
     */
    suspend fun libraryItems(
        server: SavedServer,
        libraryId: String,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
        genre: String? = null,
        startIndex: Int = 0,
        limit: Int = LIBRARY_PAGE_SIZE,
    ): Result<LibraryPage> =
        embyApiCall("library_items") {
            when (libraryId) {
                FAVORITES_COLLECTION_ID ->
                    return@embyApiCall fetchFavorites(server, limit, startIndex, sort)
                        .toLibraryPage(startIndex)
                // The playlist's own order is the one the user arranged, so 稍后观看 ignores
                // [sort] rather than overriding that with a column of its own choosing.
                WATCH_LATER_COLLECTION_ID ->
                    return@embyApiCall fetchWatchLater(server, limit, startIndex)
                        .toLibraryPage(startIndex)
            }
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Users/${server.userId}/Items") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("ParentId", libraryId)
                        parameter("Recursive", true)
                        parameter("IncludeItemTypes", "Movie,Series")
                        parameter("SortBy", sort.sortBy)
                        parameter("SortOrder", if (sort.descending) "Descending" else "Ascending")
                        if (!genre.isNullOrBlank()) parameter("Genres", genre)
                        parameter(
                            "Fields",
                            "ProductionYear,BackdropImageTags,ParentBackdropItemId," +
                                "ParentBackdropImageTags,SeriesPrimaryImageTag",
                        )
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                        parameter("StartIndex", startIndex)
                        parameter("Limit", limit)
                    }.body()
            LibraryPage(
                items = dto.Items.map { it.toMediaItem() },
                totalCount =
                    pageTotal(
                        reportedTotal = dto.TotalRecordCount,
                        startIndex = startIndex,
                        itemCount = dto.Items.size,
                        limit = limit,
                    ),
                startIndex = startIndex,
            )
        }

    /**
     * The genres present in one library, for the grid's filter row.
     *
     * Failure stays separate from a valid empty facet so the grid can keep showing its
     * content while still allowing an explicit retry to recover the filter row.
     */
    suspend fun libraryGenres(
        server: SavedServer,
        libraryId: String,
    ): Result<List<String>> {
        if (libraryId == FAVORITES_COLLECTION_ID || libraryId == WATCH_LATER_COLLECTION_ID) {
            return Result.success(emptyList())
        }
        return runCatching {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Genres") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("ParentId", libraryId)
                        parameter("IncludeItemTypes", "Movie,Series")
                        parameter("SortBy", "SortName")
                        parameter("SortOrder", "Ascending")
                        parameter("Limit", LIBRARY_GENRE_LIMIT)
                    }.body()
            dedupeBilingualGenreLabels(
                dto.Items.mapNotNull { it.Name?.takeIf(String::isNotBlank) },
            )
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.warning(
                category = "emby",
                event = "library_genres_unavailable",
                message = "Library genre facet is unavailable; the filter row stays hidden",
                throwable = it,
                attributes = mapOf("libraryId" to libraryId),
            )
        }
    }

    private suspend fun findWatchLaterPlaylistId(server: SavedServer): String? {
        val playlists: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Recursive", true)
                    parameter("IncludeItemTypes", "Playlist")
                    parameter("SearchTerm", "稍后观看")
                    parameter("Limit", 20)
                }.body()
        return playlists.Items
            .firstOrNull { it.Name.equals("稍后观看", ignoreCase = true) }
            ?.Id
    }

    private suspend fun findWatchLaterMembership(
        server: SavedServer,
        itemId: String,
    ): WatchLaterMembership? {
        val playlistId = findWatchLaterPlaylistId(server) ?: return null
        val entryIds = mutableListOf<String>()
        var matched = false
        var startIndex = 0
        repeat(MAX_WATCH_LATER_MEMBERSHIP_PAGES) {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Playlists/${playlistId.encodeURLPathPart()}/Items") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("Fields", "PlaylistItemId")
                        if (startIndex > 0) parameter("StartIndex", startIndex)
                        parameter("Limit", WATCH_LATER_MEMBERSHIP_PAGE_SIZE)
                    }.body()
            dto.Items
                .filter { it.Id == itemId }
                .forEach { item ->
                    matched = true
                    item.PlaylistItemId
                        ?.takeIf(String::isNotBlank)
                        ?.let(entryIds::add)
                }
            val pageSize = dto.Items.size
            val total = dto.TotalRecordCount
            if (
                pageSize == 0 ||
                pageSize < WATCH_LATER_MEMBERSHIP_PAGE_SIZE ||
                (total != null && startIndex + pageSize >= total)
            ) {
                return WatchLaterMembership(playlistId, matched, entryIds.distinct())
            }
            startIndex += pageSize
        }
        return WatchLaterMembership(playlistId, matched, entryIds.distinct())
    }

    internal suspend fun fetchMediaContainers(
        server: SavedServer,
        kind: MediaContainerKind?,
        startIndex: Int,
        limit: Int,
    ): MediaContainerPage {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Recursive", true)
                    parameter(
                        "IncludeItemTypes",
                        when (kind) {
                            MediaContainerKind.BoxSet -> "BoxSet"
                            MediaContainerKind.Playlist -> "Playlist"
                            null -> "BoxSet,Playlist"
                        },
                    )
                    parameter("SortBy", "SortName")
                    parameter("SortOrder", "Ascending")
                    parameter("Fields", "ChildCount")
                    parameter("EnableImageTypes", "Primary")
                    parameter("ImageTypeLimit", 1)
                    if (startIndex > 0) parameter("StartIndex", startIndex)
                    parameter("Limit", limit)
                }.body()
        val containers =
            dto.Items.mapNotNull { item ->
                val kind =
                    when (item.Type) {
                        "BoxSet" -> MediaContainerKind.BoxSet
                        "Playlist" -> MediaContainerKind.Playlist
                        else -> null
                    } ?: return@mapNotNull null
                MediaContainer(
                    id = item.Id,
                    title = item.Name?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                    kind = kind,
                    serverId = server.id,
                    posterTag = item.ImageTags?.get("Primary"),
                    itemCount = item.ChildCount,
                )
            }
        return MediaContainerPage(
            containers = containers,
            totalCount =
                pageTotal(
                    reportedTotal = dto.TotalRecordCount,
                    startIndex = startIndex,
                    itemCount = dto.Items.size,
                    limit = limit,
                ),
            startIndex = startIndex,
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.containerItemParameters(
        startIndex: Int,
        limit: Int,
        includePlaylistItemId: Boolean,
    ) {
        val fields =
            buildString {
                append(
                    "ProductionYear,Overview,ProviderIds,BackdropImageTags,ParentBackdropItemId," +
                        "ParentBackdropImageTags,SeriesPrimaryImageTag,UserData",
                )
                if (includePlaylistItemId) append(",PlaylistItemId")
            }
        parameter("Fields", fields)
        parameter("EnableImageTypes", "Primary,Backdrop")
        parameter("EnableUserData", true)
        parameter("ImageTypeLimit", 2)
        if (startIndex > 0) parameter("StartIndex", startIndex)
        parameter("Limit", limit)
    }

    internal suspend fun fetchFavorites(
        server: SavedServer,
        limit: Int,
        startIndex: Int = 0,
        sort: LibrarySort = LibrarySort.RecentlyAdded,
    ): PersonalCollection {
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("Recursive", true)
                    parameter("Filters", "IsFavorite")
                    parameter("IncludeItemTypes", "Movie,Series")
                    parameter("SortBy", sort.sortBy)
                    parameter("SortOrder", if (sort.descending) "Descending" else "Ascending")
                    personalCollectionParameters(limit, startIndex)
                }.body()
        return dto.toPersonalCollection(startIndex, limit)
    }

    internal suspend fun fetchWatchLater(
        server: SavedServer,
        limit: Int,
        startIndex: Int = 0,
    ): PersonalCollection {
        val playlistId =
            findWatchLaterPlaylistId(server)
                ?: return PersonalCollection(emptyList(), 0)
        val dto: ItemsResponseDto =
            client
                .get("${server.baseUrl}/Playlists/$playlistId/Items") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                    personalCollectionParameters(limit, startIndex)
                }.body()
        return dto.toPersonalCollection(startIndex, limit)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.personalCollectionParameters(
        limit: Int,
        startIndex: Int = 0,
    ) {
        parameter(
            "Fields",
            "ProductionYear,Overview,ProviderIds,BackdropImageTags,ParentBackdropItemId," +
                "ParentBackdropImageTags,SeriesPrimaryImageTag,UserData",
        )
        parameter("EnableImageTypes", "Primary,Backdrop")
        parameter("ImageTypeLimit", 2)
        if (startIndex > 0) parameter("StartIndex", startIndex)
        parameter("Limit", limit)
    }

    private fun ItemsResponseDto.toPersonalCollection(
        startIndex: Int,
        limit: Int,
    ): PersonalCollection =
        PersonalCollection(
            items = Items.map { it.toMediaItem() },
            totalCount =
                pageTotal(
                    reportedTotal = TotalRecordCount,
                    startIndex = startIndex,
                    itemCount = Items.size,
                    limit = limit,
                ),
        )

    private fun PersonalCollection.toLibraryPage(startIndex: Int) =
        LibraryPage(
            items = items,
            totalCount = totalCount,
            startIndex = startIndex,
        )
}

private data class WatchLaterMembership(
    val playlistId: String,
    val matched: Boolean,
    val entryIds: List<String>,
)

private const val WATCH_LATER_MEMBERSHIP_PAGE_SIZE = 200
private const val MAX_WATCH_LATER_MEMBERSHIP_PAGES = 50
