package com.yfuse.feature.server

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class ServerState(
    val url: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canConnect: Boolean get() = url.isNotBlank() && !loading
}

sealed interface ServerIntent {
    data class UrlChanged(val value: String) : ServerIntent
    data object Connect : ServerIntent
}

sealed interface ServerLabel {
    data class Connected(val baseUrl: String, val serverName: String) : ServerLabel
}

private sealed interface Msg {
    data class UrlSet(val value: String) : Msg
    data object Loading : Msg
    data class Failed(val message: String) : Msg
    data object Done : Msg
}

/** Creates the [Store] driving the server-connection screen. */
class ServerStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
) {
    fun create(): Store<ServerIntent, ServerState, ServerLabel> =
        storeFactory.create(
            name = "ServerStore",
            initialState = ServerState(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<ServerIntent, Nothing, ServerState, Msg, ServerLabel>() {

        override fun executeIntent(intent: ServerIntent) {
            when (intent) {
                is ServerIntent.UrlChanged -> dispatch(Msg.UrlSet(intent.value))
                ServerIntent.Connect -> connect()
            }
        }

        private fun connect() {
            val s = state()
            if (s.loading || s.url.isBlank()) return
            val url = s.url.trim()
            dispatch(Msg.Loading)
            scope.launch {
                repo.checkServer(url)
                    .onSuccess {
                        dispatch(Msg.Done)
                        publish(ServerLabel.Connected(url, it))
                    }
                    .onFailure { dispatch(Msg.Failed(it.toUserMessage("无法连接服务器"))) }
            }
        }
    }

    private object ReducerImpl : Reducer<ServerState, Msg> {
        override fun ServerState.reduce(msg: Msg): ServerState = when (msg) {
            is Msg.UrlSet -> copy(url = msg.value, error = null)
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Failed -> copy(loading = false, error = msg.message)
            Msg.Done -> copy(loading = false)
        }
    }
}
