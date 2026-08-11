package com.yfuse.feature.search

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.MediaSearchFilter
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.toUserMessage
import com.yfuse.core.util.currentIsoDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class ServerSearchGroup(
    val serverId: String,
    val serverName: String,
    val items: List<MediaItem> = emptyList(),
    val error: String? = null,
)

data class SearchOption(val id: String, val label: String)

enum class SearchType(val label: String, val embyType: String?) {
    All("全部", null), Movie("影片", "Movie"), Series("剧集", "Series")
}

enum class SearchWatchStatus(val label: String) {
    All("全部状态"), Unplayed("未看"), Played("已看"), Resumable("未看完")
}

enum class SearchSort(val label: String, val sortBy: String, val descending: Boolean) {
    Relevance("相关度", "SortName", false),
    RecentlyAdded("最近添加", "DateCreated", true),
    YearNewest("年份", "ProductionYear", true),
    Rating("评分", "CommunityRating", true),
    Name("名称", "SortName", false),
}

data class PersonHit(
    val serverId: String,
    val serverName: String,
    val personId: String,
    val name: String,
    val imageTag: String?,
)

data class SearchState(
    val query: String = "",
    val searchedQuery: String = "",
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val groups: List<ServerSearchGroup> = emptyList(),
    val people: List<PersonHit> = emptyList(),
    val person: PersonHit? = null,
    val type: SearchType = SearchType.All,
    val serverOptions: List<SearchOption> = emptyList(),
    val serverId: String? = null,
    val libraryOptions: List<SearchOption> = emptyList(),
    val libraryId: String? = null,
    val year: Int? = null,
    val genreOptions: List<String> = emptyList(),
    val genre: String? = null,
    val watchStatus: SearchWatchStatus = SearchWatchStatus.All,
    val sort: SearchSort = SearchSort.Relevance,
    val recent: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasSearched: Boolean get() = searchedQuery.isNotEmpty()
    val visibleGroups: List<ServerSearchGroup>
        get() = if (type == SearchType.All) {
            groups
        } else {
            groups.mapNotNull { group ->
                if (group.error != null) {
                    group
                } else {
                    group.copy(items = group.items.filter { it.type == type.embyType })
                        .takeIf { it.items.isNotEmpty() }
                }
            }
        }
    val visibleCount: Int get() = visibleGroups.sumOf { it.items.size }
    val availableTypes: List<SearchType>
        get() {
            // A selected type is fetched remotely, so keep every type reachable while it is
            // active. The unfiltered response can safely hide types that no server returned.
            if (type != SearchType.All) return SearchType.entries.toList()
            val presentTypes = groups.flatMap { it.items }.mapTo(HashSet()) { it.type }
            return SearchType.entries.filter { candidate ->
                candidate.embyType == null || candidate.embyType in presentTypes
            }
        }
    val filterCount: Int
        get() = listOf(
            serverId != null,
            libraryId != null,
            year != null,
            genre != null,
            watchStatus != SearchWatchStatus.All,
            sort != SearchSort.Relevance,
            type != SearchType.All,
        ).count { it }
    val yearOptions: List<Int>
        get() {
            val current = currentIsoDate().take(4).toIntOrNull() ?: 2026
            return (current downTo 1950).toList()
        }
}

private const val DEBOUNCE_MS = 300L
private const val RECENT_LIMIT = 8

sealed interface SearchIntent {
    data class QueryChanged(val value: String) : SearchIntent
    data object Submit : SearchIntent
    data object Retry : SearchIntent
    data object Clear : SearchIntent
    data class ForgetRecent(val term: String) : SearchIntent
    data object ClearRecent : SearchIntent
    data class SetType(val type: SearchType) : SearchIntent
    data class SetServer(val serverId: String?) : SearchIntent
    data class SetLibrary(val libraryId: String?) : SearchIntent
    data class SetYear(val year: Int?) : SearchIntent
    data class SetGenre(val genre: String?) : SearchIntent
    data class SetWatchStatus(val status: SearchWatchStatus) : SearchIntent
    data class SetSort(val sort: SearchSort) : SearchIntent
    data object ClearFilters : SearchIntent
    data class SelectPerson(val person: PersonHit?) : SearchIntent
}

private sealed interface SearchMsg {
    data class QueryChanged(val value: String) : SearchMsg
    data class Loading(val query: String) : SearchMsg
    data class Loaded(val query: String, val groups: List<ServerSearchGroup>) : SearchMsg
    data class Failed(val query: String, val message: String) : SearchMsg
    data class People(val values: List<PersonHit>) : SearchMsg
    data class Recent(val terms: List<String>) : SearchMsg
    data class Type(val value: SearchType) : SearchMsg
    data class ServerOptions(val values: List<SearchOption>) : SearchMsg
    data class ServerFilter(val value: String?) : SearchMsg
    data class Libraries(val values: List<SearchOption>) : SearchMsg
    data class LibraryFilter(val value: String?) : SearchMsg
    data class Genres(val values: List<String>) : SearchMsg
    data class YearFilter(val value: Int?) : SearchMsg
    data class GenreFilter(val value: String?) : SearchMsg
    data class WatchFilter(val value: SearchWatchStatus) : SearchMsg
    data class SortFilter(val value: SearchSort) : SearchMsg
    data class PersonLoading(val person: PersonHit) : SearchMsg
    data class PersonLoaded(val person: PersonHit, val group: ServerSearchGroup) : SearchMsg
    data class PersonFailed(val person: PersonHit, val message: String) : SearchMsg
    data object FiltersCleared : SearchMsg
    data object Cleared : SearchMsg
}

class SearchStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val history: SearchHistory? = null,
) {
    private fun serverOptions() = registry.data.value.servers.map { SearchOption(it.id, it.serverName) }

    fun create(): Store<SearchIntent, SearchState, Nothing> = storeFactory.create(
        name = "SearchStore",
        initialState = SearchState(recent = history?.load().orEmpty(), serverOptions = serverOptions()),
        executorFactory = ::ExecutorImpl,
        reducer = ReducerImpl,
    )

    private inner class ExecutorImpl :
        CoroutineExecutor<SearchIntent, Nothing, SearchState, SearchMsg, Nothing>() {
        private var debounceJob: Job? = null
        private var personJob: Job? = null
        private var searchJob: Job? = null
        private var peopleJob: Job? = null
        private var facetJob: Job? = null

        private fun cancelInFlight() {
            searchJob?.cancel(); peopleJob?.cancel(); personJob?.cancel()
        }

        override fun executeIntent(intent: SearchIntent) {
            when (intent) {
                is SearchIntent.QueryChanged -> {
                    dispatch(SearchMsg.QueryChanged(intent.value)); debouncedSearch(intent.value)
                }
                SearchIntent.Submit -> search(state().query)
                SearchIntent.Retry -> search(state().searchedQuery.ifEmpty { state().query })
                SearchIntent.Clear -> {
                    debounceJob?.cancel(); cancelInFlight(); dispatch(SearchMsg.Cleared)
                }
                is SearchIntent.ForgetRecent ->
                    dispatch(SearchMsg.Recent(history?.remove(intent.term) ?: state().recent))
                SearchIntent.ClearRecent -> dispatch(SearchMsg.Recent(history?.clear() ?: emptyList()))
                is SearchIntent.SetType -> {
                    dispatch(SearchMsg.Type(intent.type)); refreshCurrent()
                }
                is SearchIntent.SetServer -> {
                    dispatch(SearchMsg.ServerOptions(serverOptions()))
                    dispatch(SearchMsg.ServerFilter(intent.serverId))
                    loadFacets(intent.serverId, null)
                    refreshCurrent()
                }
                is SearchIntent.SetLibrary -> {
                    dispatch(SearchMsg.LibraryFilter(intent.libraryId))
                    loadFacets(state().serverId, intent.libraryId, librariesAlreadyKnown = true)
                    refreshCurrent()
                }
                is SearchIntent.SetYear -> { dispatch(SearchMsg.YearFilter(intent.year)); refreshCurrent() }
                is SearchIntent.SetGenre -> { dispatch(SearchMsg.GenreFilter(intent.genre)); refreshCurrent() }
                is SearchIntent.SetWatchStatus -> { dispatch(SearchMsg.WatchFilter(intent.status)); refreshCurrent() }
                is SearchIntent.SetSort -> { dispatch(SearchMsg.SortFilter(intent.sort)); refreshCurrent() }
                SearchIntent.ClearFilters -> {
                    dispatch(SearchMsg.FiltersCleared); facetJob?.cancel(); refreshCurrent()
                }
                is SearchIntent.SelectPerson -> selectPerson(intent.person)
            }
        }

        private fun refreshCurrent() {
            val value = state().searchedQuery.ifBlank { state().query }.trim()
            if (value.isNotEmpty()) search(value)
        }

        private fun loadFacets(serverId: String?, libraryId: String?, librariesAlreadyKnown: Boolean = false) {
            facetJob?.cancel()
            if (serverId == null) {
                dispatch(SearchMsg.Libraries(emptyList())); dispatch(SearchMsg.Genres(emptyList())); return
            }
            val server = registry.serverById(serverId) ?: return
            facetJob = scope.launch {
                if (!librariesAlreadyKnown) {
                    val libraries = repo.mediaLibraries(server).getOrDefault(emptyList())
                        .map { SearchOption(it.id, it.name) }
                    if (state().serverId == serverId) dispatch(SearchMsg.Libraries(libraries))
                }
                val genres = repo.searchGenres(server, libraryId).getOrDefault(emptyList())
                if (state().serverId == serverId && state().libraryId == libraryId) {
                    dispatch(SearchMsg.Genres(genres))
                }
            }
        }

        private fun debouncedSearch(rawQuery: String) {
            debounceJob?.cancel()
            if (rawQuery.isBlank()) { cancelInFlight(); dispatch(SearchMsg.Cleared); return }
            debounceJob = scope.launch { delay(DEBOUNCE_MS); debounceJob = null; search(rawQuery) }
        }

        private fun selectPerson(person: PersonHit?) {
            debounceJob?.cancel(); cancelInFlight()
            if (person == null) { search(state().searchedQuery.ifEmpty { state().query }); return }
            dispatch(SearchMsg.PersonLoading(person))
            val server = registry.serverById(person.serverId)
            if (server == null) { dispatch(SearchMsg.PersonFailed(person, "这台服务器已经不在列表里了")); return }
            personJob = scope.launch {
                repo.itemsByPerson(server, person.personId)
                    .onSuccess {
                        dispatch(SearchMsg.PersonLoaded(person, ServerSearchGroup(server.id, server.serverName, it)))
                    }
                    .onFailure {
                        AppLog.warning("feature.search", "person_items_failed", "Person filmography failed", it)
                        dispatch(SearchMsg.PersonFailed(person, it.toUserMessage("加载作品失败")))
                    }
            }
        }

        private fun search(rawQuery: String) {
            val query = rawQuery.trim()
            if (query.isEmpty()) { cancelInFlight(); dispatch(SearchMsg.Cleared); return }
            debounceJob?.cancel()
            cancelInFlight()
            dispatch(SearchMsg.ServerOptions(serverOptions()))
            val allServers = registry.data.value.servers
            val servers = state().serverId?.let { selected -> allServers.filter { it.id == selected } } ?: allServers
            if (servers.isEmpty()) {
                dispatch(SearchMsg.Failed(query, "还没有可用的服务器，请先到「我的」添加服务器")); return
            }
            val snapshot = state()
            val filter = MediaSearchFilter(
                parentId = snapshot.libraryId,
                includeItemTypes = snapshot.type.embyType ?: "Movie,Series",
                productionYear = snapshot.year,
                genre = snapshot.genre,
                played = when (snapshot.watchStatus) {
                    SearchWatchStatus.Played -> true
                    SearchWatchStatus.Unplayed -> false
                    else -> null
                },
                resumable = snapshot.watchStatus == SearchWatchStatus.Resumable,
                sortBy = snapshot.sort.sortBy,
                descending = snapshot.sort.descending,
            )
            dispatch(SearchMsg.Loading(query))
            searchJob = scope.launch {
                val groups = coroutineScope {
                    servers.map { server -> async {
                        val first = repo.search(server, query, filter = filter)
                        val result = if (first.isFailure) { delay(300L); repo.search(server, query, filter = filter) } else first
                        result.fold(
                            onSuccess = { ServerSearchGroup(server.id, server.serverName, it) },
                            onFailure = { ServerSearchGroup(server.id, server.serverName, error = it.toUserMessage("搜索失败")) },
                        )
                    } }.awaitAll()
                }
                if (groups.all { it.error != null }) {
                    dispatch(SearchMsg.Failed(query, "所选服务器无法完成搜索"))
                } else {
                    dispatch(SearchMsg.Loaded(query, groups))
                    if (groups.any { it.items.isNotEmpty() }) history?.remember(query)?.let { dispatch(SearchMsg.Recent(it)) }
                }
            }
            val advanced = snapshot.libraryId != null || snapshot.year != null || snapshot.genre != null ||
                snapshot.watchStatus != SearchWatchStatus.All
            peopleJob = scope.launch {
                if (advanced) { dispatch(SearchMsg.People(emptyList())); return@launch }
                val people = coroutineScope {
                    servers.map { server -> async {
                        repo.searchPeople(server, query).map { person ->
                            PersonHit(server.id, server.serverName, person.id, person.name, person.primaryImageTag)
                        }
                    } }.awaitAll().flatten()
                }
                if (state().searchedQuery == query) dispatch(SearchMsg.People(people))
            }
        }
    }

    private object ReducerImpl : Reducer<SearchState, SearchMsg> {
        override fun SearchState.reduce(msg: SearchMsg): SearchState = when (msg) {
            is SearchMsg.QueryChanged -> copy(
                query = msg.value,
                error = null,
                type = if (msg.value.trim() == query.trim()) type else SearchType.All,
            )
            is SearchMsg.Loading -> copy(query = msg.query, searchedQuery = msg.query, loading = true, error = null, people = emptyList(), person = null)
            is SearchMsg.Loaded -> if (msg.query != query.trim()) this else {
                val all = msg.groups.flatMap { it.items }
                copy(loading = false, items = all, groups = msg.groups, person = null,
                    recent = if (all.isEmpty()) recent else (listOf(msg.query) + recent.filterNot { it == msg.query }).take(RECENT_LIMIT), error = null)
            }
            is SearchMsg.Failed -> if (msg.query != query.trim()) this else copy(
                searchedQuery = msg.query,
                loading = false,
                items = emptyList(),
                groups = emptyList(),
                people = emptyList(),
                person = null,
                error = msg.message,
            )
            is SearchMsg.People -> copy(people = msg.values)
            is SearchMsg.Recent -> copy(recent = msg.terms)
            is SearchMsg.Type -> copy(type = msg.value)
            is SearchMsg.ServerOptions -> copy(serverOptions = msg.values)
            is SearchMsg.ServerFilter -> copy(serverId = msg.value, libraryId = null, libraryOptions = emptyList(), genre = null, genreOptions = emptyList())
            is SearchMsg.Libraries -> copy(libraryOptions = msg.values)
            is SearchMsg.LibraryFilter -> copy(libraryId = msg.value, genre = null, genreOptions = emptyList())
            is SearchMsg.Genres -> copy(genreOptions = msg.values)
            is SearchMsg.YearFilter -> copy(year = msg.value)
            is SearchMsg.GenreFilter -> copy(genre = msg.value)
            is SearchMsg.WatchFilter -> copy(watchStatus = msg.value)
            is SearchMsg.SortFilter -> copy(sort = msg.value)
            is SearchMsg.PersonLoading -> copy(loading = true, person = msg.person, items = emptyList(), groups = emptyList(), error = null)
            is SearchMsg.PersonLoaded -> if (person != msg.person) this else copy(loading = false, items = msg.group.items, groups = listOf(msg.group), error = null)
            is SearchMsg.PersonFailed -> if (person != msg.person) this else copy(loading = false, items = emptyList(), groups = emptyList(), error = msg.message)
            SearchMsg.FiltersCleared -> copy(type = SearchType.All, serverId = null, libraryId = null, libraryOptions = emptyList(), year = null, genre = null, genreOptions = emptyList(), watchStatus = SearchWatchStatus.All, sort = SearchSort.Relevance)
            SearchMsg.Cleared -> SearchState(recent = recent, serverOptions = serverOptions, type = type, serverId = serverId, libraryId = libraryId, libraryOptions = libraryOptions, year = year, genre = genre, genreOptions = genreOptions, watchStatus = watchStatus, sort = sort)
        }
    }
}
