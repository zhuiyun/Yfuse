package com.yfuse.feature.detail

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class DetailState(
    val loading: Boolean = false,
    val detail: MediaDetail? = null,
    val server: SavedServer? = null,
    val resolvingPlay: Boolean = false,
    val error: String? = null,
)

sealed interface DetailIntent {
    data object Retry : DetailIntent
    data object Play : DetailIntent
}

sealed interface DetailLabel {
    /** Resolved playable target; the component turns this into navigation. */
    data class Play(val itemId: String, val startPositionTicks: Long) : DetailLabel
}

private sealed interface DetailAction { data object Load : DetailAction }

private sealed interface DetailMsg {
    data object Loading : DetailMsg
    data class Loaded(val detail: MediaDetail, val server: SavedServer) : DetailMsg
    data class Failed(val message: String) : DetailMsg
    data class Resolving(val value: Boolean) : DetailMsg
}

class DetailStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val itemId: String,
) {
    fun create(): Store<DetailIntent, DetailState, DetailLabel> =
        storeFactory.create(
            name = "DetailStore",
            initialState = DetailState(),
            bootstrapper = coroutineBootstrapper<DetailAction> { dispatch(DetailAction.Load) },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<DetailIntent, DetailAction, DetailState, DetailMsg, DetailLabel>() {

        override fun executeAction(action: DetailAction) = load()

        override fun executeIntent(intent: DetailIntent) {
            when (intent) {
                DetailIntent.Retry -> load()
                DetailIntent.Play -> play()
            }
        }

        private fun load() {
            val server = registry.defaultServer
            dispatch(DetailMsg.Loading)
            scope.launch {
                if (server == null) {
                    dispatch(DetailMsg.Failed("没有可用的服务器"))
                    return@launch
                }
                repo.itemDetail(server, itemId)
                    .onSuccess { dispatch(DetailMsg.Loaded(it, server)) }
                    .onFailure { dispatch(DetailMsg.Failed(it.toUserMessage("加载失败"))) }
            }
        }

        private fun play() {
            val current = state()
            val detail = current.detail ?: return
            val server = current.server ?: return
            if (current.resolvingPlay) return
            dispatch(DetailMsg.Resolving(true))
            scope.launch {
                repo.resolvePlayTarget(server, detail)
                    .onSuccess {
                        dispatch(DetailMsg.Resolving(false))
                        publish(DetailLabel.Play(it.itemId, it.startPositionTicks))
                    }
                    .onFailure {
                        dispatch(DetailMsg.Resolving(false))
                        dispatch(DetailMsg.Failed(it.toUserMessage("无法播放")))
                    }
            }
        }
    }

    private object ReducerImpl : Reducer<DetailState, DetailMsg> {
        override fun DetailState.reduce(msg: DetailMsg): DetailState = when (msg) {
            DetailMsg.Loading -> copy(loading = true, error = null)
            is DetailMsg.Loaded -> copy(loading = false, detail = msg.detail, server = msg.server)
            is DetailMsg.Failed -> copy(loading = false, resolvingPlay = false, error = msg.message)
            is DetailMsg.Resolving -> copy(resolvingPlay = msg.value)
        }
    }
}
