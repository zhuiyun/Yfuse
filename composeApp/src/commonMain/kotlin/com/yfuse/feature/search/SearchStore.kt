package com.yfuse.feature.search

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val searchedQuery: String = "",
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
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
    data class Loaded(val query: String, val items: List<MediaItem>) : SearchMsg
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

        private var searchJob: Job? = null

        override fun executeIntent(intent: SearchIntent) {
            when (intent) {
                is SearchIntent.QueryChanged -> {
                    dispatch(SearchMsg.QueryChanged(intent.value))
                    debouncedSearch(intent.value)
                }
                SearchIntent.Submit -> search(state().query)
                SearchIntent.Retry -> search(state().searchedQuery.ifEmpty { state().query })
                SearchIntent.Clear -> {
                    searchJob?.cancel()
                    dispatch(SearchMsg.Cleared)
                }
                is SearchIntent.ForgetRecent ->
                    dispatch(SearchMsg.Recent(history?.remove(intent.term) ?: state().recent))
                SearchIntent.ClearRecent ->
                    dispatch(SearchMsg.Recent(history?.clear() ?: emptyList()))
            }
        }

        private fun debouncedSearch(rawQuery: String) {
            searchJob?.cancel()
            if (rawQuery.isBlank()) {
                dispatch(SearchMsg.Cleared)
                return
            }
            searchJob = scope.launch {
                delay(DEBOUNCE_MS)
                // Detach first: search() cancels searchJob, which is this coroutine.
                searchJob = null
                search(rawQuery)
            }
        }

        private fun search(rawQuery: String) {
            val query = rawQuery.trim()
            if (query.isEmpty()) {
                searchJob?.cancel()
                dispatch(SearchMsg.Cleared)
                return
            }

            val server = registry.defaultServer
            if (server == null) {
                dispatch(SearchMsg.Failed(query, "还没有可用的服务器，请先到「我的」添加服务器"))
                return
            }

            searchJob?.cancel()
            dispatch(SearchMsg.Loading(query))
            searchJob = scope.launch {
                repo.search(server, query)
                    .onSuccess {
                        dispatch(SearchMsg.Loaded(query, it))
                        if (it.isNotEmpty()) {
                            history?.remember(query)?.let { terms ->
                                dispatch(SearchMsg.Recent(terms))
                            }
                        }
                    }
                    .onFailure { dispatch(SearchMsg.Failed(query, it.toUserMessage("搜索失败"))) }
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
            is SearchMsg.Loaded -> copy(
                searchedQuery = msg.query,
                loading = false,
                items = msg.items,
                recent = if (msg.items.isEmpty()) {
                    recent
                } else {
                    (listOf(msg.query) + recent.filterNot { it == msg.query }).take(RECENT_LIMIT)
                },
                error = null,
            )
            is SearchMsg.Failed -> copy(
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
