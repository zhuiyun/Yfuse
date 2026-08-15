package com.yfuse.core.data

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

/** User-scoped mutations and complete sync snapshots. */
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
            Unit
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
            Unit
        }

    suspend fun snapshot(server: SavedServer): Result<List<SyncedUserItem>> =
        embyApiCall("user_library_snapshot") {
            val collected = mutableListOf<SyncedUserItem>()
            val seenIds = HashSet<String>()
            var startIndex = 0
            var total = Int.MAX_VALUE
            while (startIndex < total && collected.size < SNAPSHOT_MAX_ITEMS) {
                val dto: ItemsResponseDto =
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
                        }.body()
                if (dto.Items.isEmpty()) break
                check(dto.Items.all { seenIds.add(it.Id) }) {
                    "服务器分页未前进，已取消本次同步"
                }
                collected +=
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
                dto.TotalRecordCount?.takeIf { it > 0 }?.let { total = it }
                startIndex += dto.Items.size
            }
            if (userLibrarySnapshotIsTruncated(collected.size, total, SNAPSHOT_MAX_ITEMS)) {
                AppLog.warning(
                    category = "emby",
                    event = "library_snapshot_truncated",
                    message = "User library snapshot hit the client ceiling and is incomplete",
                    attributes =
                        mapOf(
                            "serverId" to server.id,
                            "collected" to collected.size.toString(),
                            "total" to total.toString(),
                        ),
                )
                error("媒体库项目过多，本次同步已取消以避免使用不完整快照")
            }
            collected
        }
}
