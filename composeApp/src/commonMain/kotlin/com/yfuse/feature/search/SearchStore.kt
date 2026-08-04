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

data class SearchState(
    val query: String = "",
    val searchedQuery: String = "",
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val groups: List<ServerSearchGroup> = emptyList(),
    /** Terms that returned results, newest first — fills the chip row. */
    val recent: List<String> = emptyList(),
    val error: String? = null,
) {
    val hasSearched: Boolean get() = searchedQuery.isNotEmpty()
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
}

private sealed interface SearchMsg {
    data class QueryChanged(val value: String) : SearchMsg
    data class Loading(val query: String) : SearchMsg
    data class Loaded(val query: String, val groups: List<ServerSearchGroup>) : SearchMsg
    data class Failed(val query: String, val message: String) : SearchMsg
    data class Recent(val terms: List<String>) : SearchMsg
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
                    dispatch(SearchMsg.Cleared)
                }
                is SearchIntent.ForgetRecent ->
                    dispatch(SearchMsg.Recent(history?.remove(intent.term) ?: state().recent))
                SearchIntent.ClearRecent ->
                    dispatch(SearchMsg.Recent(history?.clear() ?: emptyList()))
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
                error = msg.message,
            )
            is SearchMsg.Recent -> copy(recent = msg.terms)
            // Clearing the field keeps the chip row so it can be tapped again.
            SearchMsg.Cleared -> SearchState(recent = recent)
        }
    }
}
