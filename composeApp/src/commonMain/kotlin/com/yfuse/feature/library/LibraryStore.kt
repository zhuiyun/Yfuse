package com.yfuse.feature.library

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class LibraryState(
    val servers: List<SavedServer> = emptyList(),
    val currentServer: SavedServer? = null,
    val loading: Boolean = false,
    val content: HomeContent = HomeContent(),
    val error: String? = null,
)

sealed interface LibraryIntent {
    data class SelectServer(val id: String) : LibraryIntent
    data object Retry : LibraryIntent
}

private sealed interface Action {
    data class Data(val servers: List<SavedServer>, val default: SavedServer?) : Action
}

private sealed interface Msg {
    data class Data(val servers: List<SavedServer>, val current: SavedServer?) : Msg
    data object Loading : Msg
    data class Loaded(val content: HomeContent) : Msg
    data class Failed(val message: String) : Msg
}

class LibraryStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
) {
    fun create(): Store<LibraryIntent, LibraryState, Nothing> =
        storeFactory.create(
            name = "LibraryStore",
            initialState = LibraryState(),
            bootstrapper = coroutineBootstrapper<Action> {
                registry.data
                    .onEach { dispatch(Action.Data(it.servers, it.defaultServer)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<LibraryIntent, Action, LibraryState, Msg, Nothing>() {

        private var loadedServerId: String? = null

        override fun executeAction(action: Action) {
            when (action) {
                is Action.Data -> {
                    dispatch(Msg.Data(action.servers, action.default))
                    val server = action.default
                    if (server == null) {
                        loadedServerId = null
                    } else if (server.id != loadedServerId) {
                        loadedServerId = server.id
                        load(server)
                    }
                }
            }
        }

        override fun executeIntent(intent: LibraryIntent) {
            when (intent) {
                is LibraryIntent.SelectServer -> registry.setDefault(intent.id)
                LibraryIntent.Retry -> state().currentServer?.let {
                    loadedServerId = it.id
                    load(it)
                }
            }
        }

        private fun load(server: SavedServer) {
            dispatch(Msg.Loading)
            scope.launch {
                repo.homeContent(server)
                    .onSuccess { dispatch(Msg.Loaded(it)) }
                    .onFailure { dispatch(Msg.Failed(it.toUserMessage("加载失败"))) }
            }
        }
    }

    private object ReducerImpl : Reducer<LibraryState, Msg> {
        override fun LibraryState.reduce(msg: Msg): LibraryState = when (msg) {
            is Msg.Data -> copy(
                servers = msg.servers,
                currentServer = msg.current,
                content = if (msg.current?.id != currentServer?.id) HomeContent() else content,
            )
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Loaded -> copy(loading = false, content = msg.content, error = null)
            is Msg.Failed -> copy(loading = false, error = msg.message)
        }
    }
}
