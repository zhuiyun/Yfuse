package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.SyncedUserItem
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.coroutines.CancellationException

/** User-scoped mutations and paged user-state snapshots. */
internal class EmbyUserDataService(
    private val client: HttpClient,
) {
    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        favorite: Boolean,
    ): Result<Unit> =
        embyApiCall("set_favorite") {
            val url = "${server.baseUrl}/Users/${server.userId}/FavoriteItems/$itemId"
            if (favorite) {
                client.post(url) { header("X-Emby-Token", server.accessToken) }
            } else {
                client.delete(url) { header("X-Emby-Token", server.accessToken) }
            }
        }

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        played: Boolean,
    ): Result<Unit> =
        embyApiCall("set_played") {
            val url = "${server.baseUrl}/Users/${server.userId}/PlayedItems/$itemId"
            if (played) {
                client.post(url) { header("X-Emby-Token", server.accessToken) }
            } else {
                client.delete(url) { header("X-Emby-Token", server.accessToken) }
            }
        }

    suspend fun snapshot(
        server: SavedServer,
        includeProgress: Boolean = true,
        includeFavorites: Boolean = true,
    ): Result<List<SyncedUserItem>> =
        embyApiCall("user_library_snapshot") {
            val collected = linkedMapOf<String, SyncedUserItem>()
            val queries =
                buildList {
                    if (includeFavorites) add(UserSnapshotQuery.Favorite)
                    if (includeProgress) {
                        add(UserSnapshotQuery.Resumable)
                        add(UserSnapshotQuery.Played)
                    }
                }
            queries.forEach { query ->
                collectUserState(server, query, collected, includeProgress)
            }
            collected.values.toList()
        }

    private suspend fun collectUserState(
        server: SavedServer,
        query: UserSnapshotQuery,
        collected: MutableMap<String, SyncedUserItem>,
        includeProgress: Boolean,
    ) {
        val seenResponseIds = HashSet<String>()
        var startIndex = 0
        var total = Int.MAX_VALUE
        var pagesRead = 0
        while (startIndex < total && pagesRead < SNAPSHOT_MAX_PAGES_PER_QUERY) {
            val dto: ItemsResponseDto =
                try {
                    client
                        .get("${server.baseUrl}/Users/${server.userId}/Items") {
                            header("X-Emby-Token", server.accessToken)
                            parameter("Recursive", true)
                            parameter("IncludeItemTypes", "Movie,Series,Episode")
                            parameter("Fields", "UserData,DateModified")
                            parameter("EnableImages", false)
                            parameter("SortBy", "Id")
                            parameter("StartIndex", startIndex)
                            parameter("Limit", SNAPSHOT_PAGE_SIZE)
                            when (query) {
                                UserSnapshotQuery.Favorite -> parameter("Filters", "IsFavorite")
                                UserSnapshotQuery.Resumable -> parameter("Filters", "IsResumable")
                                UserSnapshotQuery.Played -> parameter("IsPlayed", true)
                            }
                        }.body()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    // A large library needs dozens of pages, so a single slow or dropped page is
                    // likely on a flaky link. Discarding the pages that already succeeded means the
                    // snapshot never completes at all on such a server; keep them and stop here.
                    if (pagesRead == 0) throw failure
                    AppLog.warning(
                        category = "emby",
                        event = "user_state_snapshot_page_failed",
                        message = "User-state snapshot stopped early and kept the pages already read",
                        throwable = failure,
                        attributes =
                            mapOf(
                                "serverId" to server.id,
                                "query" to query.name,
                                "pagesRead" to pagesRead.toString(),
                                "startIndex" to startIndex.toString(),
                            ),
                    )
                    return
                }
            if (dto.Items.isEmpty()) break
            val newResponseItems = dto.Items.filter { seenResponseIds.add(it.Id) }
            check(newResponseItems.isNotEmpty()) {
                "服务器分页未前进，已取消本次同步"
            }
            newResponseItems
                .asSequence()
                .filter(query::matches)
                .forEach { item ->
                    collected[item.Id] =
                        SyncedUserItem(
                            id = item.Id,
                            title = item.Name.orEmpty(),
                            favorite = item.UserData?.IsFavorite == true,
                            played = includeProgress && item.UserData?.Played == true,
                            positionTicks =
                                if (includeProgress) {
                                    item.UserData?.PlaybackPositionTicks ?: 0L
                                } else {
                                    0L
                                },
                            dateModified = item.DateModified,
                        )
                }
            dto.TotalRecordCount?.takeIf { it >= 0 }?.let { total = it }
            startIndex += dto.Items.size
            pagesRead++
        }
        if (
            userLibrarySnapshotPageBudgetExhausted(
                pagesRead = pagesRead,
                startIndex = startIndex,
                reportedTotal = total,
                maxPages = SNAPSHOT_MAX_PAGES_PER_QUERY,
            )
        ) {
            AppLog.warning(
                category = "emby",
                event = "user_state_snapshot_page_budget_exhausted",
                message = "User-state snapshot exceeded the safe paging budget",
                attributes =
                    mapOf(
                        "serverId" to server.id,
                        "query" to query.name,
                        "pagesRead" to pagesRead.toString(),
                        "reportedTotal" to total.toString(),
                    ),
            )
            error("媒体库用户状态分页异常，本次同步已取消")
        }
    }

    private enum class UserSnapshotQuery {
        Favorite,
        Resumable,
        Played,
        ;

        fun matches(item: BaseItemDto): Boolean =
            when (this) {
                Favorite -> item.UserData?.IsFavorite == true
                Resumable -> (item.UserData?.PlaybackPositionTicks ?: 0L) > 0L
                Played -> item.UserData?.Played == true
            }
    }
}
