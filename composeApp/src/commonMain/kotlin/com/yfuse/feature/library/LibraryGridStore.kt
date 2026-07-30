package com.yfuse.feature.library

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.MediaItem
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class GridState(
    val loading: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val error: String? = null,
)

sealed interface GridIntent {
    data object Retry : GridIntent
}

private sealed interface GridAction { data object Load : GridAction }

private sealed interface GridMsg {
    data object Loading : GridMsg
    data class Loaded(val items: List<MediaItem>) : GridMsg
    data class Failed(val message: String) : GridMsg
}

class LibraryGridStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val libraryId: String,
) {
    fun create(): Store<GridIntent, GridState, Nothing> =
        storeFactory.create(
            name = "LibraryGridStore",
            initialState = GridState(),
            bootstrapper = coroutineBootstrapper<GridAction> { dispatch(GridAction.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<GridIntent, GridAction, GridState, GridMsg, Nothing>() {

        override fun executeAction(action: GridAction) = load()
        override fun executeIntent(intent: GridIntent) = load()

        private fun load() {
            val server = registry.defaultServer
            dispatch(GridMsg.Loading)
            scope.launch {
                if (server == null) {
                    AppLog.warning(
                        category = "feature.library",
                        event = "grid_server_missing",
                        message = "Library grid could not load because no server is available",
                    )
                    dispatch(GridMsg.Failed("没有可用的服务器"))
                    return@launch
                }
                repo.libraryItems(server, libraryId)
                    .onSuccess { dispatch(GridMsg.Loaded(it)) }
                    .onFailure {
                        AppLog.warning(
                            category = "feature.library",
                            event = "grid_load_failed",
                            message = "Library grid failed to load",
                            throwable = it,
                            attributes = mapOf("serverId" to server.id),
                        )
                        dispatch(GridMsg.Failed(it.toUserMessage("加载失败")))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<GridState, GridMsg> {
        override fun GridState.reduce(msg: GridMsg): GridState = when (msg) {
            GridMsg.Loading -> copy(loading = true, error = null)
            is GridMsg.Loaded -> copy(loading = false, items = msg.items)
            is GridMsg.Failed -> copy(loading = false, error = msg.message)
        }
    }
}
