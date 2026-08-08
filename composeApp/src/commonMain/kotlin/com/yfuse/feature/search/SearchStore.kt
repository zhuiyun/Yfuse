package com.yfuse.feature.search

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.toUserMessage
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

/** 影片 / 剧集 narrowing, applied to results that are already in hand. */
enum class SearchType(val label: String, val embyType: String?) {
    All(label = "全部", embyType = null),
    Movie(label = "影片", embyType = "Movie"),
    Series(label = "剧集", embyType = "Series"),
}

/**
 * A cast member the query matched, and the server that holds them.
 *
 * People live per server, so the server travels with the hit: opening one has to ask the
 * server that returned it, not whichever happens to be first in the registry.
 */
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
    /** Cast matches for the current query — the 演员 row above the titles. */
    val people: List<PersonHit> = emptyList(),
    /** Set while the results are one person's filmography rather than a title search. */
    val person: PersonHit? = null,
    val type: SearchType = SearchType.All,
    /** Terms that returned results, newest first — fills the chip row. */
    val recent: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasSearched: Boolean get() = searchedQuery.isNotEmpty()

    /**
     * The groups actually drawn, with [type] applied. A server left with nothing by the
     * filter drops out; one that failed stays, because its failure is still news.
     */
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

    /**
     * How many titles are on screen. The heading used to report every item across every
     * server while the list below it was grouped per server and could be filtered, so the
     * two numbers disagreed whenever they were worth reading.
     */
    val visibleCount: Int get() = visibleGroups.sumOf { it.items.size }

    /** Which narrowings are worth offering: a type nobody matched is not one. */
    val availableTypes: List<SearchType>
        get() {
            val present = groups.flatMap { it.items }.mapTo(HashSet()) { it.type }
            return SearchType.entries.filter { candidate ->
                val embyType = candidate.embyType
                embyType == null || embyType in present
            }
        }
}

/** The prototype searches as you type; this is the settle time before firing. */
private const val DEBOUNCE_MS = 300L

private const val RECENT_LIMIT = 8

sealed interface SearchIntent {
    data class QueryChanged(val value: String) : SearchIntent
    data object Submit : SearchIntent
    data object Retry : SearchIntent
    data object Clear : SearchIntent

    /** Removes one term from 搜索记录. */
    data class ForgetRecent(val term: String) : SearchIntent

    data object ClearRecent : SearchIntent

    data class SetType(val type: SearchType) : SearchIntent

    /** Null returns to the title results for the current query. */
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
    data class PersonLoading(val person: PersonHit) : SearchMsg
    data class PersonLoaded(val person: PersonHit, val group: ServerSearchGroup) : SearchMsg
    data class PersonFailed(val person: PersonHit, val message: String) : SearchMsg
    data object Cleared : SearchMsg
}

class SearchStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    /** Null in tests that do not care about persistence. */
    private val history: SearchHistory? = null,
) {
    fun create(): Store<SearchIntent, SearchState, Nothing> =
        storeFactory.create(
            name = "SearchStore",
            initialState = SearchState(recent = history?.load().orEmpty()),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<SearchIntent, Nothing, SearchState, SearchMsg, Nothing>() {

        private var debounceJob: Job? = null
        private var personJob: Job? = null

        override fun executeIntent(intent: SearchIntent) {
            when (intent) {
                is SearchIntent.QueryChanged -> {
                    dispatch(SearchMsg.QueryChanged(intent.value))
                    debouncedSearch(intent.value)
                }
                SearchIntent.Submit -> search(state().query)
                SearchIntent.Retry -> search(state().searchedQuery.ifEmpty { state().query })
                SearchIntent.Clear -> {
                    debounceJob?.cancel()
                    personJob?.cancel()
                    dispatch(SearchMsg.Cleared)
                }
                is SearchIntent.ForgetRecent ->
                    dispatch(SearchMsg.Recent(history?.remove(intent.term) ?: state().recent))
                SearchIntent.ClearRecent ->
                    dispatch(SearchMsg.Recent(history?.clear() ?: emptyList()))
                is SearchIntent.SetType -> dispatch(SearchMsg.Type(intent.type))
                is SearchIntent.SelectPerson -> selectPerson(intent.person)
            }
        }

        private fun debouncedSearch(rawQuery: String) {
            debounceJob?.cancel()
            if (rawQuery.isBlank()) {
                dispatch(SearchMsg.Cleared)
                return
            }
            debounceJob = scope.launch {
                delay(DEBOUNCE_MS)
                debounceJob = null
                search(rawQuery)
            }
        }

        /**
         * Swaps the results for one person's filmography, or puts the title results back.
         *
         * The titles are not kept aside while a person is open: re-running the query is a
         * request the user already waited for once, and holding two result sets in the
         * state was the more expensive of the two mistakes to make.
         */
        private fun selectPerson(person: PersonHit?) {
            personJob?.cancel()
            debounceJob?.cancel()
            if (person == null) {
                search(state().searchedQuery.ifEmpty { state().query })
                return
            }
            // Ahead of the lookup so the failure below has a person to be about: every
            // person message is ignored unless it matches the one the state is showing.
            dispatch(SearchMsg.PersonLoading(person))
            val server = registry.data.value.servers.firstOrNull { it.id == person.serverId }
            if (server == null) {
                dispatch(SearchMsg.PersonFailed(person, "这台服务器已经不在列表里了"))
                return
            }
            personJob = scope.launch {
                repo.itemsByPerson(server, person.personId)
                    .onSuccess {
                        dispatch(
                            SearchMsg.PersonLoaded(
                                person = person,
                                group = ServerSearchGroup(
                                    serverId = server.id,
                                    serverName = server.serverName,
                                    items = it,
                                ),
                            ),
                        )
                    }
                    .onFailure {
                        AppLog.warning(
                            category = "feature.search",
                            event = "person_items_failed",
                            message = "Person filmography failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(
                            SearchMsg.PersonFailed(person, it.toUserMessage("加载作品失败")),
                        )
                    }
            }
        }

        private fun search(rawQuery: String) {
            val query = rawQuery.trim()
            if (query.isEmpty()) {
                debounceJob?.cancel()
                dispatch(SearchMsg.Cleared)
                return
            }

            val servers = registry.data.value.servers
            if (servers.isEmpty()) {
                AppLog.warning(
                    category = "feature.search",
                    event = "server_missing",
                    message = "Search could not start because no server is available",
                )
                dispatch(SearchMsg.Failed(query, "还没有可用的服务器，请先到「我的」添加服务器"))
                return
            }

            debounceJob?.cancel()
            personJob?.cancel()
            dispatch(SearchMsg.Loading(query))
            scope.launch {
                val groups = coroutineScope {
                    servers.map { server ->
                        async {
                            val first = repo.search(server, query)
                            // Search is read-only and cheap; one delayed retry absorbs a
                            // recycled reverse-proxy connection without marking the whole
                            // server disconnected on the page.
                            val result = if (first.isFailure) {
                                delay(300L)
                                repo.search(server, query)
                            } else {
                                first
                            }
                            result.fold(
                                onSuccess = {
                                    ServerSearchGroup(
                                        serverId = server.id,
                                        serverName = server.serverName,
                                        items = it,
                                    )
                                },
                                onFailure = {
                                    ServerSearchGroup(
                                        serverId = server.id,
                                        serverName = server.serverName,
                                        error = it.toUserMessage("搜索失败"),
                                    )
                                },
                            )
                        }
                    }.awaitAll()
                }
                val failedCount = groups.count { it.error != null }
                if (groups.all { it.error != null }) {
                    AppLog.error(
                        category = "feature.search",
                        event = "all_servers_failed",
                        message = "Search failed on every configured server",
                        attributes = mapOf("serverCount" to servers.size.toString()),
                    )
                    dispatch(SearchMsg.Failed(query, "所有服务器均无法完成搜索"))
                } else {
                    if (failedCount > 0) {
                        AppLog.warning(
                            category = "feature.search",
                            event = "partial_failure",
                            message = "Search completed with server failures",
                            attributes = mapOf(
                                "serverCount" to servers.size.toString(),
                                "failedCount" to failedCount.toString(),
                            ),
                        )
                    }
                    dispatch(SearchMsg.Loaded(query, groups))
                    if (groups.any { it.items.isNotEmpty() }) {
                        history?.remember(query)?.let { terms ->
                            dispatch(SearchMsg.Recent(terms))
                        }
                    }
                }
            }
            // Cast runs beside the titles rather than gating them: it is an extra row, and
            // a server without the /Persons route should not hold up the results.
            scope.launch {
                val people = coroutineScope {
                    servers.map { server ->
                        async {
                            repo.searchPeople(server, query).map { person ->
                                PersonHit(
                                    serverId = server.id,
                                    serverName = server.serverName,
                                    personId = person.id,
                                    name = person.name,
                                    imageTag = person.primaryImageTag,
                                )
                            }
                        }
                    }.awaitAll().flatten()
                }
                if (state().searchedQuery == query) dispatch(SearchMsg.People(people))
            }
        }
    }

    private object ReducerImpl : Reducer<SearchState, SearchMsg> {
        override fun SearchState.reduce(msg: SearchMsg): SearchState = when (msg) {
            is SearchMsg.QueryChanged -> copy(query = msg.value, error = null)
            is SearchMsg.Loading -> copy(
                query = msg.query,
                searchedQuery = msg.query,
                loading = true,
                error = null,
                // A fresh query invalidates both the cast row and any person opened from
                // the previous one.
                people = emptyList(),
                person = null,
            )
            is SearchMsg.Loaded -> {
                if (msg.query != query.trim()) {
                    this
                } else {
                    val allItems = msg.groups.flatMap { it.items }
                    copy(
                        searchedQuery = msg.query,
                        loading = false,
                        items = allItems,
                        groups = msg.groups,
                        person = null,
                        recent = if (allItems.isEmpty()) {
                            recent
                        } else {
                            (listOf(msg.query) + recent.filterNot { it == msg.query })
                                .take(RECENT_LIMIT)
                        },
                        error = null,
                    )
                }
            }
            is SearchMsg.Failed -> if (msg.query != query.trim()) this else copy(
                searchedQuery = msg.query,
                loading = false,
                items = emptyList(),
                groups = emptyList(),
                error = msg.message,
            )
            is SearchMsg.People -> copy(people = msg.values)
            is SearchMsg.Recent -> copy(recent = msg.terms)
            is SearchMsg.Type -> copy(type = msg.value)
            // The title results are cleared rather than left standing: they belong to the
            // query, not to the person, and holding them under a 演员 banner would say the
            // filmography had loaded and was this.
            is SearchMsg.PersonLoading -> copy(
                loading = true,
                person = msg.person,
                items = emptyList(),
                groups = emptyList(),
                error = null,
            )
            is SearchMsg.PersonLoaded -> if (person != msg.person) this else copy(
                loading = false,
                items = msg.group.items,
                groups = listOf(msg.group),
                // The person's filmography is its own result set; a type narrowing chosen
                // for the title results does not carry over to it.
                type = SearchType.All,
                error = null,
            )
            is SearchMsg.PersonFailed -> if (person != msg.person) this else copy(
                loading = false,
                items = emptyList(),
                groups = emptyList(),
                error = msg.message,
            )
            // Clearing the field keeps the chip row so it can be tapped again.
            SearchMsg.Cleared -> SearchState(recent = recent)
        }
    }
}
