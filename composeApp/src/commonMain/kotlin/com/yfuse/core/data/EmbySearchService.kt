package com.yfuse.core.data

import com.yfuse.core.data.dto.ItemsResponseDto
import com.yfuse.core.data.dto.toMediaItem
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.Person
import com.yfuse.core.model.SavedServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException

/** Server-side title and person search, including real paging boundaries. */
internal class EmbySearchService(
    private val client: HttpClient,
    private val progress: PlaybackProgressProjection = PlaybackProgressProjection(),
) {
    /** Genre facet for search; [parentId] narrows it to one library when selected. */
    suspend fun genres(
        server: SavedServer,
        parentId: String? = null,
    ): Result<List<String>> =
        embyApiCall("search_genres") {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Genres") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parentId?.let { parameter("ParentId", it) }
                        parameter("IncludeItemTypes", "Movie,Series")
                        parameter("SortBy", "SortName")
                        parameter("SortOrder", "Ascending")
                        parameter("Limit", LIBRARY_GENRE_LIMIT)
                    }.body()
            dedupeBilingualGenreLabels(dto.Items.mapNotNull { it.Name?.takeIf(String::isNotBlank) })
        }

    suspend fun searchPage(
        server: SavedServer,
        query: String,
        startIndex: Int,
        limit: Int,
        filter: MediaSearchFilter,
    ): Result<MediaSearchPage> =
        embyApiCall("search") {
            suspend fun request(
                term: String,
                offset: Int,
                requestLimit: Int = limit,
            ): ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Users/${server.userId}/Items") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("SearchTerm", term)
                        parameter("Recursive", true)
                        parameter("IncludeItemTypes", filter.includeItemTypes)
                        filter.parentId?.let { parameter("ParentId", it) }
                        filter.productionYear?.let { parameter("ProductionYear", it) }
                        filter.genre?.takeIf { it.isNotBlank() }?.let { parameter("Genres", it) }
                        filter.sortBy?.let {
                            parameter("SortBy", it)
                            parameter("SortOrder", if (filter.descending) "Descending" else "Ascending")
                        }
                        // ResultRow already renders MediaItem.overview. Ask the server for it here
                        // rather than making every tapped result perform a second detail request.
                        parameter(
                            "Fields",
                            "ProductionYear,CommunityRating,Overview,ProviderIds,SeriesPrimaryImageTag,UserData",
                        )
                        parameter("EnableImageTypes", "Primary")
                        parameter("ImageTypeLimit", 1)
                        if (offset > 0) parameter("StartIndex", offset)
                        parameter("Limit", requestLimit)
                    }.body()

            val localProgressFilter = filter.played != null || filter.resumable
            val exactPage =
                request(
                    query,
                    if (localProgressFilter) 0 else startIndex,
                    if (localProgressFilter) LOCAL_PROGRESS_SCAN_PAGE_SIZE else limit,
                )
            if (exactPage.Items.isNotEmpty() || startIndex > 0) {
                if (localProgressFilter) {
                    val all = exactPage.Items.toMutableList()
                    var offset = all.size
                    var pages = 1
                    var reportedTotal = exactPage.TotalRecordCount
                    while (
                        pages < MAX_LOCAL_PROGRESS_SCAN_PAGES &&
                        all.isNotEmpty() &&
                        (reportedTotal?.let { offset < it } ?: (offset % LOCAL_PROGRESS_SCAN_PAGE_SIZE == 0))
                    ) {
                        val page = request(query, offset, LOCAL_PROGRESS_SCAN_PAGE_SIZE)
                        if (page.Items.isEmpty()) break
                        all += page.Items
                        offset += page.Items.size
                        reportedTotal = page.TotalRecordCount ?: reportedTotal
                        pages++
                        if (page.Items.size < LOCAL_PROGRESS_SCAN_PAGE_SIZE) break
                    }
                    val matching =
                        all
                            .asSequence()
                            .distinctBy { it.Id }
                            .map { progress.project(server, it).toMediaItem() }
                            .filter { it.matchesProgressFilter(filter) }
                            .toList()
                    val pageItems = matching.drop(startIndex).take(limit)
                    return@embyApiCall MediaSearchPage(
                        items = if (filter.sortBy == null) rankSearchResults(pageItems, query) else pageItems,
                        totalCount = matching.size,
                        startIndex = startIndex,
                    )
                }
                val items = exactPage.Items.map { progress.project(server, it).toMediaItem() }
                return@embyApiCall MediaSearchPage(
                    items = if (filter.sortBy == null) rankSearchResults(items, query) else items,
                    totalCount =
                        pageTotal(
                            reportedTotal = exactPage.TotalRecordCount,
                            startIndex = startIndex,
                            itemCount = items.size,
                            limit = limit,
                        ),
                    startIndex = startIndex,
                )
            }

            // Some compatible search indexes reject a full CJK title although a stable suffix
            // returns it. Fallback results still have to contain the original query.
            val normalizedQuery = query.trim()
            val fallbackTerms =
                buildList {
                    addAll(normalizedQuery.split(Regex("\\s+")).filter { it.length >= 2 })
                    if (normalizedQuery.length >= 3) add(normalizedQuery.takeLast(2))
                    if (normalizedQuery.length >= 4) {
                        add(normalizedQuery.drop(normalizedQuery.length / 2))
                        add(normalizedQuery.take(normalizedQuery.length / 2))
                    }
                }.distinct().filterNot { it.equals(normalizedQuery, ignoreCase = true) }

            val fallbackItems =
                buildList {
                    fallbackTerms.forEach { term -> addAll(request(term, 0).Items) }
                }
            val items =
                fallbackItems
                    .asSequence()
                    .distinctBy { it.Id }
                    .filter { it.Name?.contains(normalizedQuery, ignoreCase = true) == true }
                    .map { progress.project(server, it).toMediaItem() }
                    .filter { !localProgressFilter || it.matchesProgressFilter(filter) }
                    .take(limit)
                    .toList()
            MediaSearchPage(
                items = rankSearchResults(items, query),
                totalCount = items.size,
                startIndex = 0,
            )
        }

    private fun MediaItem.matchesProgressFilter(filter: MediaSearchFilter): Boolean {
        if (filter.played != null && played != filter.played) return false
        if (filter.resumable && (played || (resumePositionTicks ?: 0L) <= 0L)) return false
        return true
    }

    private companion object {
        const val LOCAL_PROGRESS_SCAN_PAGE_SIZE = 200
        const val MAX_LOCAL_PROGRESS_SCAN_PAGES = 250
    }

    suspend fun searchPeople(
        server: SavedServer,
        query: String,
        limit: Int,
    ): List<Person> =
        runCatching {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Persons") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("SearchTerm", query)
                        parameter("EnableImageTypes", "Primary")
                        parameter("ImageTypeLimit", 1)
                        parameter("Limit", limit)
                    }.body()
            dto.Items
                .filter { !it.Name.isNullOrBlank() }
                .map { Person(it.Id, it.Name.orEmpty(), null, it.ImageTags?.get("Primary")) }
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.warning(
                category = "emby",
                event = "person_search_unavailable",
                message = "Person search is unavailable; the 演员 row stays hidden",
                throwable = it,
                attributes = mapOf("serverId" to server.id),
            )
        }.getOrDefault(emptyList())

    suspend fun itemsByPerson(
        server: SavedServer,
        personId: String,
        limit: Int,
    ): Result<List<MediaItem>> =
        embyApiCall("items_by_person") {
            val dto: ItemsResponseDto =
                client
                    .get("${server.baseUrl}/Users/${server.userId}/Items") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("PersonIds", personId)
                        parameter("Recursive", true)
                        parameter("IncludeItemTypes", "Movie,Series")
                        parameter("SortBy", "ProductionYear,SortName")
                        parameter("SortOrder", "Descending")
                        parameter(
                            "Fields",
                            "ProductionYear,CommunityRating,Overview,ProviderIds,BackdropImageTags," +
                                "ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag",
                        )
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                        parameter("Limit", limit)
                    }.body()
            dto.Items.map { progress.project(server, it).toMediaItem() }
        }
}
