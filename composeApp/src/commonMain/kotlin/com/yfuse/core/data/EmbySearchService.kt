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
                        filter.played?.let { parameter("IsPlayed", it) }
                        if (filter.resumable) parameter("Filters", "IsResumable")
                        filter.sortBy?.let {
                            parameter("SortBy", it)
                            parameter("SortOrder", if (filter.descending) "Descending" else "Ascending")
                        }
                        parameter(
                            "Fields",
                            "ProductionYear,ProviderIds,SeriesPrimaryImageTag,UserData",
                        )
                        parameter("EnableImageTypes", "Primary")
                        parameter("ImageTypeLimit", 1)
                        if (offset > 0) parameter("StartIndex", offset)
                        parameter("Limit", limit)
                    }.body()

            val exactPage = request(query, startIndex)
            if (exactPage.Items.isNotEmpty() || startIndex > 0) {
                val items = exactPage.Items.map { it.toMediaItem() }
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
                    .take(limit)
                    .map { it.toMediaItem() }
                    .toList()
            MediaSearchPage(
                items = rankSearchResults(items, query),
                totalCount = items.size,
                startIndex = 0,
            )
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
                            "ProductionYear,Overview,ProviderIds,BackdropImageTags," +
                                "ParentBackdropItemId,ParentBackdropImageTags,SeriesPrimaryImageTag",
                        )
                        parameter("EnableImageTypes", "Primary,Backdrop")
                        parameter("ImageTypeLimit", 2)
                        parameter("Limit", limit)
                    }.body()
            dto.Items.map { it.toMediaItem() }
        }
}
