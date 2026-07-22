package com.yfuse.feature.home

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.model.MediaLibrary
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class HomeState(
    val loading: Boolean = false,
    val libraries: List<MediaLibrary> = emptyList(),
    val error: String? = null,
)

sealed interface HomeIntent {
    data object Load : HomeIntent
}

private sealed interface Msg {
    data object Loading : Msg
    data class Loaded(val libraries: List<MediaLibrary>) : Msg
    data class Failed(val message: String) : Msg
}

/** Creates the [Store] driving the home (library list) screen. */
class HomeStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
) {
    fun create(): Store<HomeIntent, HomeState, Nothing> =
        storeFactory.create(
            name = "HomeStore",
            initialState = HomeState(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<HomeIntent, Nothing, HomeState, Msg, Nothing>() {

        override fun executeIntent(intent: HomeIntent) {
            when (intent) {
                HomeIntent.Load -> load()
            }
        }

        private fun load() {
            if (state().loading) return
            dispatch(Msg.Loading)
            scope.launch {
                repo.libraries()
                    .onSuccess { dispatch(Msg.Loaded(it)) }
                    .onFailure { dispatch(Msg.Failed(it.toUserMessage("加载失败"))) }
            }
        }
    }

    private object ReducerImpl : Reducer<HomeState, Msg> {
        override fun HomeState.reduce(msg: Msg): HomeState = when (msg) {
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Loaded -> copy(loading = false, libraries = msg.libraries, error = null)
            is Msg.Failed -> copy(loading = false, error = msg.message)
        }
    }
}
