package com.yfuse.feature.home

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val content: TmdbHome = TmdbHome(),
    /** 继续观看 — the signed-in server's in-progress items. */
    val resume: List<MediaItem> = emptyList(),
    val resolving: Boolean = false,
    val error: String? = null,
)

sealed interface HomeIntent {
    data object Retry : HomeIntent

    /** Tapping a TMDB pick: play it if the library has it, else show its info. */
    data class Open(val item: TmdbItem) : HomeIntent

    /** Tapping a 继续观看 card goes straight to the library item. */
    data class OpenResume(val item: MediaItem) : HomeIntent
}

sealed interface HomeLabel {
    data class OpenEmbyItem(val itemId: String) : HomeLabel
    data class OpenTmdbItem(val item: TmdbItem, val embyItemId: String?) : HomeLabel
}

private sealed interface Action { data object Load : Action }

private sealed interface Msg {
    data object Loading : Msg
    data class Loaded(val content: TmdbHome) : Msg
    data class ResumeLoaded(val items: List<MediaItem>) : Msg
    data class Failed(val message: String) : Msg
    data class Resolving(val value: Boolean) : Msg
}

class HomeStoreFactory(
    private val storeFactory: StoreFactory,
    private val tmdb: TmdbRepository,
    private val emby: EmbyRepository,
    private val registry: ServerRegistry,
) {
    fun create(): Store<HomeIntent, HomeState, HomeLabel> =
        storeFactory.create(
            name = "HomeStore",
            initialState = HomeState(),
            bootstrapper = coroutineBootstrapper<Action> { dispatch(Action.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<HomeIntent, Action, HomeState, Msg, HomeLabel>() {

        override fun executeAction(action: Action) = load()

        override fun executeIntent(intent: HomeIntent) {
            when (intent) {
                HomeIntent.Retry -> load()
                is HomeIntent.Open -> open(intent.item)
                is HomeIntent.OpenResume -> publish(HomeLabel.OpenEmbyItem(intent.item.id))
            }
        }

        private fun load() {
            dispatch(Msg.Loading)
            scope.launch {
                tmdb.home()
                    .onSuccess { dispatch(Msg.Loaded(it)) }
                    .onFailure { dispatch(Msg.Failed(it.toUserMessage("推荐内容加载失败"))) }
            }
            // 继续观看 comes from the signed-in server; a failure here just leaves
            // the row empty rather than failing the whole screen.
            val server = registry.defaultServer ?: return
            scope.launch {
                emby.homeContent(server).onSuccess { dispatch(Msg.ResumeLoaded(it.resume)) }
            }
        }

        private fun open(item: TmdbItem) {
            if (state().resolving) return
            val server = registry.defaultServer
            if (server == null) {
                publish(HomeLabel.OpenTmdbItem(item, null))
                return
            }
            dispatch(Msg.Resolving(true))
            scope.launch {
                val exactProviderMatch = emby.findByTmdbId(server, item.id, item.mediaType)
                    .getOrNull()
                val titleCandidates = if (exactProviderMatch == null) {
                    emby.search(server, item.title).getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val match = exactProviderMatch ?: titleCandidates.firstOrNull { candidate ->
                    val titleMatches = candidate.title.equals(item.title, ignoreCase = true)
                    val yearMatches = item.year?.toIntOrNull()?.let { candidate.year == it } ?: true
                    val typeMatches = if (item.mediaType == "tv") {
                        candidate.type == "Series"
                    } else {
                        candidate.type == "Movie"
                    }
                    titleMatches && yearMatches && typeMatches
                }
                dispatch(Msg.Resolving(false))
                // The visual detail always comes from TMDB; an Emby match only
                // enables the play action.
                publish(HomeLabel.OpenTmdbItem(item, match?.id))
            }
        }
    }

    private object ReducerImpl : Reducer<HomeState, Msg> {
        override fun HomeState.reduce(msg: Msg): HomeState = when (msg) {
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Loaded -> copy(loading = false, content = msg.content)
            is Msg.ResumeLoaded -> copy(resume = msg.items)
            is Msg.Failed -> copy(loading = false, error = msg.message)
            is Msg.Resolving -> copy(resolving = msg.value)
        }
    }
}
