from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    text = read(path)
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:180]!r}")
    write(path, text.replace(old, new, count))


if (ROOT / "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailPlaybackPanel.kt").exists():
    print("phase2a already applied")
    raise SystemExit(0)

# ---------------------------------------------------------------- advanced server-side search model
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/core/data/MediaSearchFilter.kt",
    '''package com.yfuse.core.data

/** Query parameters supported by Emby's /Users/{id}/Items search endpoint. */
data class MediaSearchFilter(
    val parentId: String? = null,
    val includeItemTypes: String = "Movie,Series",
    val productionYear: Int? = null,
    val genre: String? = null,
    val played: Boolean? = null,
    val resumable: Boolean = false,
    val sortBy: String = "SortName",
    val descending: Boolean = false,
)
''',
)

repo = "composeApp/src/commonMain/kotlin/com/yfuse/core/data/EmbyRepository.kt"
replace(
    repo,
    '    /** Title search, used by the search tab and to match TMDB picks to the library. */\n    suspend fun search(server: SavedServer, query: String, limit: Int = 24): Result<List<MediaItem>> =\n',
    '''    /** Libraries available to advanced search filters. */
    suspend fun mediaLibraries(server: SavedServer): Result<List<MediaLibrary>> =
        call("search_libraries") { fetchViews(server) }

    /** Genre facet for search; parentId narrows it to one library when selected. */
    suspend fun searchGenres(server: SavedServer, parentId: String? = null): Result<List<String>> =
        call("search_genres") {
            val dto: ItemsResponseDto = client.get("${server.baseUrl}/Genres") {
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

    /** Server-wide next episodes for the 首页「下一集」shelf. */
    suspend fun nextUpEpisodes(server: SavedServer, limit: Int = 12): Result<List<MediaItem>> =
        call("next_up") {
            val dto: ItemsResponseDto = client.get("${server.baseUrl}/Shows/NextUp") {
                header("X-Emby-Token", server.accessToken)
                parameter("UserId", server.userId)
                parameter("Limit", limit)
                parameter(
                    "Fields",
                    "ProductionYear,Overview,ProviderIds,BackdropImageTags,ParentBackdropItemId," +
                        "ParentBackdropImageTags,SeriesPrimaryImageTag,UserData",
                )
                parameter("EnableImageTypes", "Primary,Backdrop")
                parameter("ImageTypeLimit", 2)
            }.body()
            dto.Items.map { it.toMediaItem() }
        }

    /** Title search with filters executed by Emby rather than against a truncated client list. */
    suspend fun search(
        server: SavedServer,
        query: String,
        limit: Int = 24,
        filter: MediaSearchFilter = MediaSearchFilter(),
    ): Result<List<MediaItem>> =
''',
)
replace(
    repo,
    '''                parameter("SearchTerm", term)
                parameter("Recursive", true)
                parameter("IncludeItemTypes", "Movie,Series")
''',
    '''                parameter("SearchTerm", term)
                parameter("Recursive", true)
                parameter("IncludeItemTypes", filter.includeItemTypes)
                filter.parentId?.let { parameter("ParentId", it) }
                filter.productionYear?.let { parameter("ProductionYear", it) }
                filter.genre?.takeIf { it.isNotBlank() }?.let { parameter("Genres", it) }
                filter.played?.let { parameter("IsPlayed", it) }
                if (filter.resumable) parameter("Filters", "IsResumable")
                parameter("SortBy", filter.sortBy)
                parameter("SortOrder", if (filter.descending) "Descending" else "Ascending")
''',
)

# ---------------------------------------------------------------- Search Store: server/library/year/genre/status/sort facets
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchStore.kt",
    r'''package com.yfuse.feature.search

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
    val visibleGroups: List<ServerSearchGroup> get() = groups
    val visibleCount: Int get() = groups.sumOf { it.items.size }
    val availableTypes: List<SearchType> get() = SearchType.entries.toList()
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
            debounceJob?.cancel(); cancelInFlight(); dispatch(SearchMsg.Loading(query))
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
            is SearchMsg.QueryChanged -> copy(query = msg.value, error = null)
            is SearchMsg.Loading -> copy(query = msg.query, searchedQuery = msg.query, loading = true, error = null, people = emptyList(), person = null)
            is SearchMsg.Loaded -> if (msg.query != query.trim()) this else {
                val all = msg.groups.flatMap { it.items }
                copy(loading = false, items = all, groups = msg.groups, person = null,
                    recent = if (all.isEmpty()) recent else (listOf(msg.query) + recent.filterNot { it == msg.query }).take(RECENT_LIMIT), error = null)
            }
            is SearchMsg.Failed -> if (msg.query != query.trim()) this else copy(loading = false, items = emptyList(), groups = emptyList(), error = msg.message)
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
''',
)

# ---------------------------------------------------------------- Search filter UI is split from SearchScreen.
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchFilters.kt",
    r'''package com.yfuse.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalAccent
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc

internal enum class SearchFilterSheet { Server, Library, Year, Genre, Status, Sort }

@Composable
internal fun SearchFilterBar(
    state: SearchState,
    onOpen: (SearchFilterSheet) -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalPalette.current
    val accent = LocalAccent.current.color
    val values = listOf(
        SearchFilterSheet.Server to (state.serverOptions.firstOrNull { it.id == state.serverId }?.label ?: "服务器"),
        SearchFilterSheet.Library to (state.libraryOptions.firstOrNull { it.id == state.libraryId }?.label ?: "媒体库"),
        SearchFilterSheet.Year to (state.year?.toString() ?: "年份"),
        SearchFilterSheet.Genre to (state.genre ?: "流派"),
        SearchFilterSheet.Status to state.watchStatus.label,
        SearchFilterSheet.Sort to state.sort.label,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(values) { (sheet, label) ->
            val active = when (sheet) {
                SearchFilterSheet.Server -> state.serverId != null
                SearchFilterSheet.Library -> state.libraryId != null
                SearchFilterSheet.Year -> state.year != null
                SearchFilterSheet.Genre -> state.genre != null
                SearchFilterSheet.Status -> state.watchStatus != SearchWatchStatus.All
                SearchFilterSheet.Sort -> state.sort != SearchSort.Relevance
            }
            Text(
                label,
                style = sc(12f, if (active) 700 else 500),
                color = if (active) accent else palette.body,
                modifier = Modifier
                    .pressable(onClick = { onOpen(sheet) })
                    .glass(
                        shape = GlassShapes.chip,
                        fill = if (active) accent.copy(alpha = 0.13f) else palette.card2,
                        border = if (active) accent.copy(alpha = 0.28f) else palette.border,
                    )
                    .then(Modifier)
                    .let { it },
            )
        }
        if (state.filterCount > 0) {
            item {
                Text(
                    "清除 ${state.filterCount}",
                    style = sc(12f, 600),
                    color = Color.White,
                    modifier = Modifier
                        .pressable(onClick = onClear)
                        .glass(GlassShapes.chip, accent.copy(alpha = 0.82f), accent)
                        .then(Modifier),
                )
            }
        }
    }
}

@Composable
internal fun SearchFilterDialog(
    state: SearchState,
    sheet: SearchFilterSheet,
    onIntent: (SearchIntent) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = when (sheet) {
                SearchFilterSheet.Server -> "服务器"
                SearchFilterSheet.Library -> "媒体库"
                SearchFilterSheet.Year -> "年份"
                SearchFilterSheet.Genre -> "流派"
                SearchFilterSheet.Status -> "观看状态"
                SearchFilterSheet.Sort -> "排序"
            },
            subtitle = if (sheet == SearchFilterSheet.Library && state.serverId == null) "先选择一台服务器" else null,
            onClose = onDismiss,
        )
        when (sheet) {
            SearchFilterSheet.Server -> optionList(
                listOf(SearchOption("", "全部服务器")) + state.serverOptions,
                state.serverId.orEmpty(),
            ) { onIntent(SearchIntent.SetServer(it.ifBlank { null })); onDismiss() }
            SearchFilterSheet.Library -> optionList(
                listOf(SearchOption("", "全部媒体库")) + state.libraryOptions,
                state.libraryId.orEmpty(),
            ) { onIntent(SearchIntent.SetLibrary(it.ifBlank { null })); onDismiss() }
            SearchFilterSheet.Year -> {
                OverlayOptionRow("全部年份", state.year == null, onClick = { onIntent(SearchIntent.SetYear(null)); onDismiss() })
                state.yearOptions.take(45).forEach { year ->
                    OverlayOptionRow(year.toString(), year == state.year, onClick = { onIntent(SearchIntent.SetYear(year)); onDismiss() })
                }
            }
            SearchFilterSheet.Genre -> {
                OverlayOptionRow("全部流派", state.genre == null, onClick = { onIntent(SearchIntent.SetGenre(null)); onDismiss() })
                state.genreOptions.forEach { genre ->
                    OverlayOptionRow(genre, genre == state.genre, onClick = { onIntent(SearchIntent.SetGenre(genre)); onDismiss() })
                }
            }
            SearchFilterSheet.Status -> SearchWatchStatus.entries.forEach { value ->
                OverlayOptionRow(value.label, value == state.watchStatus, onClick = { onIntent(SearchIntent.SetWatchStatus(value)); onDismiss() })
            }
            SearchFilterSheet.Sort -> SearchSort.entries.forEach { value ->
                OverlayOptionRow(value.label, value == state.sort, onClick = { onIntent(SearchIntent.SetSort(value)); onDismiss() })
            }
        }
    }
}

@Composable
private fun optionList(values: List<SearchOption>, selected: String, onSelect: (String) -> Unit) {
    Column {
        values.forEach { value ->
            OverlayOptionRow(value.label, value.id == selected, onClick = { onSelect(value.id) })
        }
    }
}
''',
)

# Fix padding on filter chips with a simple source post-pass (keeps the UI compact).
filters = "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchFilters.kt"
text = read(filters)
text = text.replace('import androidx.compose.foundation.layout.PaddingValues\n', 'import androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.foundation.layout.padding\n')
text = text.replace('                    .then(Modifier)\n                    .let { it },', '                    .padding(horizontal = 13.dp, vertical = 7.dp),')
text = text.replace('                        .then(Modifier),', '                        .padding(horizontal = 13.dp, vertical = 7.dp),')
write(filters, text)

search_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/search/SearchScreen.kt"
replace(search_screen, '    val routeVisible = LocalRouteVisible.current\n', '    val routeVisible = LocalRouteVisible.current\n    var filterSheet by remember { mutableStateOf<SearchFilterSheet?>(null) }\n')
replace(
    search_screen,
    '''            item {
                SearchField(
                    query = state.query,
                    onQueryChange = { store.accept(SearchIntent.QueryChanged(it)) },
                    onSubmit = { store.accept(SearchIntent.Submit) },
                    onClear = { store.accept(SearchIntent.Clear) },
                    focusRequester = fieldFocusRequester,
                )
            }
''',
    '''            item {
                SearchField(
                    query = state.query,
                    onQueryChange = { store.accept(SearchIntent.QueryChanged(it)) },
                    onSubmit = { store.accept(SearchIntent.Submit) },
                    onClear = { store.accept(SearchIntent.Clear) },
                    focusRequester = fieldFocusRequester,
                )
            }
            item {
                SearchFilterBar(
                    state = state,
                    onOpen = { filterSheet = it },
                    onClear = { store.accept(SearchIntent.ClearFilters) },
                )
                filterSheet?.let { sheet ->
                    SearchFilterDialog(
                        state = state,
                        sheet = sheet,
                        onIntent = store::accept,
                        onDismiss = { filterSheet = null },
                    )
                }
            }
''',
)
# Search accent follows the user's accent instead of the fixed brand blue.
replace(search_screen, 'import com.yfuse.core.designsystem.LocalPalette\n', 'import com.yfuse.core.designsystem.LocalPalette\nimport com.yfuse.core.designsystem.LocalAccent\n')
replace(search_screen, '    val palette = LocalPalette.current\n    val shape = continuousRounded(25.dp)\n', '    val palette = LocalPalette.current\n    val accent = LocalAccent.current.color\n    val shape = continuousRounded(25.dp)\n', count=1)
replace(search_screen, '.glass(shape, palette.card3, Brand.Primary.copy(alpha = 0.4f))', '.glass(shape, palette.card3, accent.copy(alpha = 0.4f))')
replace(search_screen, 'Icon(AppIcons.Search, null, tint = Brand.Primary, modifier = Modifier.size(15.dp))', 'Icon(AppIcons.Search, null, tint = accent, modifier = Modifier.size(15.dp))')
replace(search_screen, 'cursorBrush = SolidColor(Brand.Primary)', 'cursorBrush = SolidColor(accent)')

# ---------------------------------------------------------------- Detail source selection becomes structured + coordinated.
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailSelection.kt",
    r'''package com.yfuse.feature.detail

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.Season
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import kotlinx.coroutines.delay

sealed interface SourceSelectionFailure {
    data object Timeout : SourceSelectionFailure
    data object NetworkUnavailable : SourceSelectionFailure
    data object AuthRequired : SourceSelectionFailure
    data class Server(val code: Int) : SourceSelectionFailure
    data class EpisodeMissing(val season: Int?, val episode: Int?) : SourceSelectionFailure
    data object InvalidResponse : SourceSelectionFailure
}

internal class SourceSelectionTimeoutException : Exception("Cross-server source selection timed out")
internal class EpisodeUnavailableException(val seasonNumber: Int?, val episodeNumber: Int?) :
    Exception("The selected source does not contain the current episode")

internal fun Throwable.toSourceSelectionFailure(): SourceSelectionFailure = when (this) {
    is SourceSelectionTimeoutException -> SourceSelectionFailure.Timeout
    is EpisodeUnavailableException -> SourceSelectionFailure.EpisodeMissing(seasonNumber, episodeNumber)
    else -> when (val error = (this as? EmbyErrorException)?.error) {
        EmbyError.Network -> SourceSelectionFailure.NetworkUnavailable
        EmbyError.Unauthorized, is EmbyError.AccessDenied -> SourceSelectionFailure.AuthRequired
        is EmbyError.Server -> SourceSelectionFailure.Server(error.code)
        else -> SourceSelectionFailure.InvalidResponse
    }
}

internal fun SourceSelectionFailure.toDetailMessage(): String = when (this) {
    SourceSelectionFailure.Timeout -> "资源切换等待超时，请检查网络后再试"
    SourceSelectionFailure.NetworkUnavailable -> "资源服务器暂时无法连接，已保留当前播放版本"
    SourceSelectionFailure.AuthRequired -> "资源服务器登录已失效，请到服务器管理重新登录"
    is SourceSelectionFailure.Server -> "资源服务器暂时异常（HTTP $code），请稍后再试"
    is SourceSelectionFailure.EpisodeMissing -> {
        val coordinate = if (season != null && episode != null) "第 ${season} 季第 ${episode} 集" else "当前剧集"
        "该资源没有$coordinate，请选择其他播放版本"
    }
    SourceSelectionFailure.InvalidResponse -> "资源信息无法解析，请刷新后重试"
}

/** Retry policy is isolated from the Store so source selection can be unit-tested independently. */
internal class SourceSelectionCoordinator(
    private val repo: EmbyRepository,
    private val maxAttempts: Int = 3,
    private val retryBaseDelayMs: Long = 250L,
) {
    suspend fun <T> resolve(
        server: SavedServer,
        sourceItemId: String,
        stillCurrent: () -> Boolean,
        resolveDetail: suspend (MediaDetail) -> Result<T>,
    ): Result<T> {
        var attempt = 1
        while (true) {
            val result = repo.itemDetail(server, sourceItemId).fold(
                onSuccess = { resolveDetail(it) },
                onFailure = { Result.failure(it) },
            )
            val failure = result.exceptionOrNull()
            if (result.isSuccess || attempt >= maxAttempts || failure?.isTransientSourceFailure() != true) return result
            delay(retryBaseDelayMs shl (attempt - 1))
            if (!stillCurrent()) return result
            attempt++
        }
    }
}

private fun Throwable.isTransientSourceFailure(): Boolean = when (val error = (this as? EmbyErrorException)?.error) {
    EmbyError.Network -> true
    is EmbyError.Server -> error.code in 500..599
    else -> false
}

internal data class SeriesCatalog(
    val seasons: List<Season>,
    val selectedSeasonId: String?,
    val episodes: List<Episode>,
)

internal class SeriesCatalogLoader(private val repo: EmbyRepository) {
    suspend fun load(
        server: SavedServer,
        seriesId: String,
        target: MediaDetail,
        allEpisodes: List<Episode>?,
    ): SeriesCatalog {
        val seasons = repo.seasons(server, seriesId).getOrThrow()
        val targetEpisode = allEpisodes?.firstOrNull { it.id == target.id }
        val selectedSeasonId = targetEpisode?.seasonId
            ?: seasons.firstOrNull { it.indexNumber == target.seasonNumber }?.id
            ?: seasons.firstOrNull()?.id
        val episodes = allEpisodes
            ?.filter { selectedSeasonId == null || it.seasonId == selectedSeasonId }
            ?: repo.episodes(server, seriesId, selectedSeasonId).getOrThrow()
        return SeriesCatalog(seasons, selectedSeasonId, episodes)
    }
}
''',
)

detail_store = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailStore.kt"
replace(detail_store, '    val actionMessage: String? = null,\n', '    val actionMessage: String? = null,\n    val sourceFailure: SourceSelectionFailure? = null,\n')
# Remove old private model/exceptions now owned by DetailSelection.kt.
text = read(detail_store)
for start_marker, end_marker in [
    ('private data class SeriesCatalog(', 'private const val SOURCE_SELECTION_MAX_ATTEMPTS'),
]:
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    # Preserve EpisodeCoordinate and constants by rebuilding the small middle explicitly.
    middle = '''private data class EpisodeCoordinate(\n    val seasonNumber: Int?,\n    val episodeNumber: Int?,\n)\n\n'''
    text = text[:start] + middle + text[end:]
# Remove obsolete retry constants + exception declarations but keep timeout.
text = text.replace('private const val SOURCE_SELECTION_MAX_ATTEMPTS = 3\nprivate const val SOURCE_SELECTION_RETRY_BASE_DELAY_MS = 250L\n', '')
start = text.find('private class SourceSelectionTimeoutException')
if start >= 0:
    end = text.index('private sealed interface DetailMsg', start)
    text = text[:start] + text[end:]
write(detail_store, text)
replace(detail_store, '    data class ActionMessage(val value: String?) : DetailMsg\n', '    data class ActionMessage(val value: String?) : DetailMsg\n    data class SourceFailure(val value: SourceSelectionFailure?) : DetailMsg\n')
replace(detail_store, '        private var relatedLoadGeneration = 0L\n', '        private var relatedLoadGeneration = 0L\n        private val sourceCoordinator = SourceSelectionCoordinator(repo)\n        private val seriesCatalogLoader = SeriesCatalogLoader(repo)\n')
# Replace catalog implementation with loader.
old_catalog = '''        private suspend fun loadSeriesCatalog(
            server: SavedServer,
            seriesId: String,
            target: MediaDetail,
            allEpisodes: List<Episode>?,
        ): SeriesCatalog {
            val seasons = repo.seasons(server, seriesId).getOrThrow()
            val targetEpisode = allEpisodes?.firstOrNull { it.id == target.id }
            val selectedSeasonId = targetEpisode?.seasonId
                ?: seasons.firstOrNull { it.indexNumber == target.seasonNumber }?.id
                ?: seasons.firstOrNull()?.id
            val episodes = allEpisodes
                ?.filter { selectedSeasonId == null || it.seasonId == selectedSeasonId }
                ?: repo.episodes(server, seriesId, selectedSeasonId).getOrThrow()
            return SeriesCatalog(seasons, selectedSeasonId, episodes)
        }
'''
new_catalog = '''        private suspend fun loadSeriesCatalog(
            server: SavedServer,
            seriesId: String,
            target: MediaDetail,
            allEpisodes: List<Episode>?,
        ): SeriesCatalog = seriesCatalogLoader.load(server, seriesId, target, allEpisodes)
'''
replace(detail_store, old_catalog, new_catalog)
# Replace retry loop function body dynamically.
text = read(detail_store)
start = text.index('        private suspend fun resolveSelectedSourceWithRetry(')
end = text.index('        private fun selectEpisode(', start)
signature_end = text.index('        ): Result<ResolvedPlaybackSelection> {', start) + len('        ): Result<ResolvedPlaybackSelection> {')
new_func = text[start:signature_end] + '''
            return sourceCoordinator.resolve(
                server = server,
                sourceItemId = sourceItemId,
                stillCurrent = { operation == sourceSelectionOperation },
            ) { sourceDetail ->
                resolvePlaybackSelection(
                    server = server,
                    sourceDetail = sourceDetail,
                    preferredEpisode = coordinate,
                    preferredPlaybackItemId = preferredPlaybackItemId,
                )
            }
        }

'''
text = text[:start] + new_func + text[end:]
write(detail_store, text)
# Source failures become typed state rather than Store-authored Chinese.
replace(
    detail_store,
    '''                            dispatch(
                                DetailMsg.ActionMessage(
                                    "资源切换失败：${it.toSourceSelectionUserMessage()}",
                                ),
                            )
''',
    '                            dispatch(DetailMsg.SourceFailure(it.toSourceSelectionFailure()))\n',
)
replace(detail_store, '            is DetailMsg.SelectionLoading -> copy(selectionLoading = msg.value)\n', '            is DetailMsg.SelectionLoading -> copy(selectionLoading = msg.value, sourceFailure = if (msg.value) null else sourceFailure)\n')
replace(detail_store, '            is DetailMsg.ActionMessage -> copy(actionMessage = msg.value)\n', '            is DetailMsg.ActionMessage -> copy(actionMessage = msg.value)\n            is DetailMsg.SourceFailure -> copy(sourceFailure = msg.value, selectionLoading = false)\n')
replace(detail_store, '                    actionMessage = null,\n                ).withSelectedVersion(versionId)', '                    actionMessage = null,\n                    sourceFailure = null,\n                ).withSelectedVersion(versionId)')
# Remove obsolete mapper at bottom.
text = read(detail_store)
marker = 'private fun Throwable.toSourceSelectionUserMessage(): String ='
if marker in text:
    start = text.index(marker)
    end = text.index('private suspend inline fun <T> cancellableResult', start)
    text = text[:start] + text[end:]
write(detail_store, text)

# ---------------------------------------------------------------- unified Detail「播放版本」panel
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailPlaybackPanel.kt",
    r'''package com.yfuse.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yfuse.core.data.PlaybackTrackRequest
import com.yfuse.core.designsystem.Dimens
import com.yfuse.core.designsystem.GlassDialog
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.LocalPalette
import com.yfuse.core.designsystem.OverlayHeader
import com.yfuse.core.designsystem.OverlayOptionRow
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.ServerSource

internal fun playbackVersionSummary(
    serverName: String?,
    version: MediaVersion?,
    audioLanguage: String?,
    subtitleLanguage: String?,
): String = listOfNotNull(
    serverName?.takeIf { it.isNotBlank() },
    version?.qualityLabel,
    audioLanguage?.takeIf { it.isNotBlank() }?.let { "$it 音轨" },
    when (subtitleLanguage) {
        PlaybackTrackRequest.SUBTITLES_OFF -> "字幕关闭"
        null -> null
        else -> "$subtitleLanguage 字幕"
    },
).joinToString(" · ").ifBlank { "自动选择最佳播放版本" }

@Composable
internal fun PlaybackVersionSection(
    summary: String,
    switching: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.pageHorizontal)
            .pressable(onClick = onClick)
            .glass(GlassShapes.card, palette.card2, palette.border)
            .padding(horizontal = 15.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("播放版本", style = sc(13f, 700), color = palette.text)
            Spacer(Modifier.height(3.dp))
            Text(
                if (switching) "正在切换资源…" else summary,
                style = mr(11f, 500),
                color = palette.sub,
                maxLines = 2,
            )
        }
        Text("›", style = sc(18f, 500), color = palette.sub2)
    }
}

@Composable
internal fun PlaybackVersionDialog(
    title: String,
    sources: List<ServerSource>,
    selectedServerId: String?,
    selectedItemId: String?,
    versions: List<MediaVersion>,
    selectedVersionId: String?,
    selectedAudioLanguage: String?,
    selectedSubtitleLanguage: String?,
    switching: Boolean,
    onSelectSource: (String, String) -> Unit,
    onSelectVersion: (String) -> Unit,
    onSelectAudio: (String?) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(onDismiss = onDismiss) {
        OverlayHeader(
            title = "播放版本",
            subtitle = if (switching) "正在解析所选资源" else title,
            onClose = onDismiss,
        )
        val selectableSources = sources.filter { it.reachable && it.itemId != null && it.source != null }
        if (selectableSources.size > 1) {
            Text("播放来源", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            selectableSources.forEach { source ->
                OverlayOptionRow(
                    label = listOfNotNull(source.serverName, source.source?.let { it.qualityLabel }).joinToString(" · "),
                    selected = source.serverId == selectedServerId && source.itemId == selectedItemId,
                    onClick = { source.itemId?.let { onSelectSource(source.serverId, it) } },
                )
            }
        }
        if (versions.size > 1) {
            Text("文件版本", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            versions.forEach { version ->
                OverlayOptionRow(
                    label = listOf(version.name, version.summary).filter { it.isNotBlank() }.joinToString(" · "),
                    selected = version.id == selectedVersionId,
                    onClick = { onSelectVersion(version.id) },
                )
            }
        }
        val version = versions.firstOrNull { it.id == selectedVersionId } ?: versions.firstOrNull()
        version?.audioTracks?.takeIf { it.size > 1 }?.let { tracks ->
            Text("音轨", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            OverlayOptionRow("文件默认", selectedAudioLanguage == null, onClick = { onSelectAudio(null) })
            tracks.forEach { track ->
                OverlayOptionRow(track.label, track.language == selectedAudioLanguage, onClick = { onSelectAudio(track.language) })
            }
        }
        version?.subtitleTracks?.takeIf { it.isNotEmpty() }?.let { tracks ->
            Text("字幕", style = mr(11f, 700), color = Color.White.copy(alpha = 0.62f))
            OverlayOptionRow("文件默认", selectedSubtitleLanguage == null, onClick = { onSelectSubtitle(null) })
            OverlayOptionRow("关闭字幕", selectedSubtitleLanguage == PlaybackTrackRequest.SUBTITLES_OFF, onClick = { onSelectSubtitle(PlaybackTrackRequest.SUBTITLES_OFF) })
            tracks.forEach { track ->
                OverlayOptionRow(track.label, track.language == selectedSubtitleLanguage, onClick = { onSelectSubtitle(track.language) })
            }
        }
    }
}
''',
)

detail_screen = "composeApp/src/commonMain/kotlin/com/yfuse/feature/detail/DetailScreen.kt"
replace(detail_screen, '    var sourceListOpen by remember { mutableStateOf(false) }\n', '    var playbackVersionOpen by remember { mutableStateOf(false) }\n')
text = read(detail_screen)
# Replace the three separate main-page blocks with one summary row.
start = text.index('                if (playableVersions.isNotEmpty()) {')
end = text.index('                if (detail.genres.isNotEmpty()) {', start)
unified = '''                if (playableVersions.isNotEmpty() || comparableSources.any { it.reachable && it.itemId != null }) {
                    item(key = "playback-version") {
                        PlaybackVersionSection(
                            summary = playbackVersionSummary(
                                serverName = state.playServer?.serverName,
                                version = selectedVersion,
                                audioLanguage = state.preferredAudioLanguage,
                                subtitleLanguage = state.preferredSubtitleLanguage,
                            ),
                            switching = state.selectionLoading,
                            onClick = { playbackVersionOpen = true },
                            modifier = Modifier.padding(top = Dimens.sectionGap),
                        )
                    }
                }

'''
text = text[:start] + unified + text[end:]
# Replace source-only dialog with unified dialog.
start = text.index('        if (sourceListOpen) {')
end = text.index('        // A layer rather than a route:', start)
dialog = '''        if (playbackVersionOpen && detail != null) {
            PlaybackVersionDialog(
                title = detail.title,
                sources = comparableSources,
                selectedServerId = state.selectedSourceServerId,
                selectedItemId = state.selectedSourceItemId,
                versions = playableVersions,
                selectedVersionId = state.selectedVersionId,
                selectedAudioLanguage = state.preferredAudioLanguage,
                selectedSubtitleLanguage = state.preferredSubtitleLanguage,
                switching = state.selectionLoading,
                onSelectSource = { serverId, itemId ->
                    component.store.accept(DetailIntent.SelectSource(serverId, itemId))
                },
                onSelectVersion = { component.store.accept(DetailIntent.SelectVersion(it)) },
                onSelectAudio = { component.store.accept(DetailIntent.SelectAudioLanguage(it)) },
                onSelectSubtitle = { component.store.accept(DetailIntent.SelectSubtitleLanguage(it)) },
                onDismiss = { playbackVersionOpen = false },
            )
        }

'''
text = text[:start] + dialog + text[end:]
text = text.replace('            message = state.actionMessage,', '            message = state.actionMessage ?: state.sourceFailure?.toDetailMessage(),')
write(detail_screen, text)

# ---------------------------------------------------------------- Player Settings IA: Playback / Tracks / Picture / Danmaku / Cast / Advanced.
player_controls = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerControls.kt"
text = read(player_controls)
old_enum = '''internal enum class Tab(val label: String) {
    Danmaku("弹幕"),
    Subtitle("字幕"),
    Cast("投屏"),
    Diagnostics("诊断"),
    More("更多"),
}

'''
if old_enum not in text:
    raise SystemExit("player tab enum anchor missing")
text = text.replace(old_enum, '', 1)
text = text.replace('onClick = { onOpenTab(Tab.More) }', 'onClick = { onOpenTab(Tab.Playback) }')
# SettingsPanel receives aspect state for the new Picture tab.
call_anchor = '                onSpeed = { onSpeed(it); settingsTab = null },\n'
if call_anchor not in text:
    raise SystemExit("settings panel call anchor missing")
text = text.replace(call_anchor, call_anchor + '                filled = filled,\n                onToggleFill = onToggleFill,\n', 1)
write(player_controls, text)

player_settings = "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerSettingsPanel.kt"
text = read(player_settings)
insert_at = text.index('/**\n * Settings panel')
text = text[:insert_at] + '''internal enum class Tab(val label: String) {
    Playback("播放"),
    Tracks("音轨"),
    Picture("画面"),
    Danmaku("弹幕"),
    Cast("投屏"),
    Advanced("高级"),
}

''' + text[insert_at:]
text = text.replace('    onSpeed: (Float) -> Unit,\n', '    onSpeed: (Float) -> Unit,\n    filled: Boolean,\n    onToggleFill: () -> Unit,\n')
old_tabs = '''    val tabs = buildList {
        add(Tab.Danmaku)
        // A lone audio track is not a choice — matching the chip's condition keeps the
        // tab from appearing with nothing switchable in it.
        if (state.subtitleTracks.isNotEmpty() || state.audioTracks.size > 1) add(Tab.Subtitle)
        add(Tab.Cast)
        add(Tab.Diagnostics)
        add(Tab.More)
    }
'''
new_tabs = '''    val tabs = buildList {
        add(Tab.Playback)
        if (state.subtitleTracks.isNotEmpty() || state.audioTracks.size > 1) add(Tab.Tracks)
        add(Tab.Picture)
        add(Tab.Danmaku)
        add(Tab.Cast)
        add(Tab.Advanced)
    }
'''
if old_tabs not in text:
    raise SystemExit("player settings tabs anchor missing")
text = text.replace(old_tabs, new_tabs, 1)
text = text.replace('Tab.Subtitle ->', 'Tab.Tracks ->')
text = text.replace('Tab.More ->', 'Tab.Playback ->')
text = text.replace('Tab.Diagnostics ->', 'Tab.Advanced ->')
# Pull low-frequency skip + engine blocks out of Playback and prepend them to Advanced.
skip_start = text.index('                        // Only for a series:')
speed_start = text.index('                        GroupLabel("播放速度")', skip_start)
skip_block = text[skip_start:speed_start]
text = text[:skip_start] + text[speed_start:]
engine_start = text.index('                        if (engineOptions.isNotEmpty() || transcodeLabel != null) {')
advanced_marker = '                    Tab.Advanced -> {\n'
advanced_pos = text.index(advanced_marker)
engine_end = advanced_pos
engine_block_with_close = text[engine_start:engine_end]
# Drop the closing brace of Playback from this captured chunk, preserving it in source.
last_close = engine_block_with_close.rfind('                    }\n\n')
if last_close < 0:
    raise SystemExit("player engine block close missing")
engine_block = engine_block_with_close[:last_close]
text = text[:engine_start] + '                    }\n\n' + text[advanced_pos:]
advanced_pos = text.index(advanced_marker) + len(advanced_marker)
advanced_intro = '''                        GroupLabel("高级播放")
''' + skip_block + engine_block + '''
                        GroupLabel("诊断")
'''
text = text[:advanced_pos] + advanced_intro + text[advanced_pos:]
# Picture gets only actual picture/runtime choices, not engine diagnostics.
picture_case = '''                    Tab.Picture -> {
                        GroupLabel("画面")
                        OptionRow(if (filled) "填充屏幕" else "适应画面", filled, onClick = onToggleFill)
                        if (transcodeLabel != null) {
                            GroupLabel("兼容播放")
                            OptionRow(transcodeLabel, transcodeActive, onClick = onTranscode)
                        }
                    }

'''
cast_pos = text.index('                    Tab.Cast -> {')
text = text[:cast_pos] + picture_case + text[cast_pos:]
write(player_settings, text)

# Split NextUp overlay out of the giant PlayerControls file.
controls = read(player_controls)
start = controls.index('/** How long before the end 下一集 announces itself. */')
end = controls.index('private fun Long.asClock()', start)
nextup_block = controls[start:end].replace('@Composable\nprivate fun NextUpCard', '@Composable\ninternal fun NextUpCard')
write(player_controls, controls[:start] + controls[end:])
write(
    "composeApp/src/commonMain/kotlin/com/yfuse/feature/player/PlayerNextUp.kt",
    '''package com.yfuse.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yfuse.core.designsystem.AppIcons
import com.yfuse.core.designsystem.GlassShapes
import com.yfuse.core.designsystem.HapticSignal
import com.yfuse.core.designsystem.PlayerTokens
import com.yfuse.core.designsystem.Shadows
import com.yfuse.core.designsystem.glass
import com.yfuse.core.designsystem.mr
import com.yfuse.core.designsystem.pressable
import com.yfuse.core.designsystem.sc
import com.yfuse.core.designsystem.shadow

''' + nextup_block,
)

print("phase2a patch applied")
