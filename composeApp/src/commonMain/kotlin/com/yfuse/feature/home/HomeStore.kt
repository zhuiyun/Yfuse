package com.yfuse.feature.home

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = true,
    val content: TmdbHome = TmdbHome(),
    val resolving: Boolean = false,
    val error: String? = null,
)

sealed interface HomeIntent {
    data object Retry : HomeIntent

    /** Tapping a TMDB pick: play it if the library has it, else show its info. */
    data class Open(val item: TmdbItem) : HomeIntent
}

sealed interface HomeLabel {
    data class OpenEmbyItem(val itemId: String) : HomeLabel
    data class OpenTmdbItem(val item: TmdbItem) : HomeLabel
}

private sealed interface Action { data object Load : Action }

private sealed interface Msg {
    data object Loading : Msg
    data class Loaded(val content: TmdbHome) : Msg
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
            }
        }

        private fun load() {
            dispatch(Msg.Loading)
            scope.launch {
                tmdb.home()
                    .onSuccess { dispatch(Msg.Loaded(it)) }
                    .onFailure { dispatch(Msg.Failed(it.toUserMessage("推荐内容加载失败"))) }
            }
        }

        private fun open(item: TmdbItem) {
            if (state().resolving) return
            val server = registry.defaultServer
            if (server == null) {
                publish(HomeLabel.OpenTmdbItem(item))
                return
            }
            dispatch(Msg.Resolving(true))
            scope.launch {
                val match = emby.search(server, item.title)
                    .getOrDefault(emptyList())
                    .firstOrNull { it.title.equals(item.title, ignoreCase = true) }
                dispatch(Msg.Resolving(false))
                if (match != null) {
                    publish(HomeLabel.OpenEmbyItem(match.id))
                } else {
                    publish(HomeLabel.OpenTmdbItem(item))
                }
            }
        }
    }

    private object ReducerImpl : Reducer<HomeState, Msg> {
        override fun HomeState.reduce(msg: Msg): HomeState = when (msg) {
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Loaded -> copy(loading = false, content = msg.content)
            is Msg.Failed -> copy(loading = false, error = msg.message)
            is Msg.Resolving -> copy(resolving = msg.value)
        }
    }
}
