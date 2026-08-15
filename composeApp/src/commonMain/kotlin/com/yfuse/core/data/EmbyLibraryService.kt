package com.yfuse.core.data

import com.yfuse.core.data.dto.ItemCountsDto
import com.yfuse.core.data.dto.ViewsDto
import com.yfuse.core.model.LibraryCounts
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.model.SavedServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter

/** Lightweight library topology and aggregate counts shared by browse, search, and home. */
internal class EmbyLibraryService(
    private val client: HttpClient,
) {
    suspend fun views(server: SavedServer): List<MediaLibrary> {
        val dto: ViewsDto =
            client
                .get("${server.baseUrl}/Users/${server.userId}/Views") {
                    header("X-Emby-Token", server.accessToken)
                }.body()
        return dto.Items.map { MediaLibrary(it.Id, it.Name, it.CollectionType) }
    }

    suspend fun counts(server: SavedServer): LibraryCounts {
        val dto: ItemCountsDto =
            client
                .get("${server.baseUrl}/Items/Counts") {
                    header("X-Emby-Token", server.accessToken)
                    parameter("UserId", server.userId)
                }.body()
        return LibraryCounts(
            movieCount = dto.MovieCount.coerceAtLeast(0),
            seriesCount = dto.SeriesCount.coerceAtLeast(0),
        )
    }
}
