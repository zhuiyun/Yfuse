package com.yfuse.feature.profile

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class ProfileState(
    val currentServer: SavedServer? = null,
    /** All saved servers — the 我的服务器 list. */
    val servers: List<SavedServer> = emptyList(),
) {
    val serverCount: Int get() = servers.size
}

sealed interface ProfileIntent {
    /** Logs out of (removes) the current default server. */
    data object Logout : ProfileIntent

    /** Makes another saved server the active one. */
    data class SwitchServer(val id: String) : ProfileIntent
}

private sealed interface Action {
    data class Data(val current: SavedServer?, val servers: List<SavedServer>) : Action
}

private sealed interface Msg {
    data class Data(val current: SavedServer?, val servers: List<SavedServer>) : Msg
}

class ProfileStoreFactory(
    private val storeFactory: StoreFactory,
    private val registry: ServerRegistry,
) {
    fun create(): Store<ProfileIntent, ProfileState, Nothing> =
        storeFactory.create(
            name = "ProfileStore",
            initialState = ProfileState(),
            bootstrapper = coroutineBootstrapper<Action> {
                registry.data
                    .onEach { dispatch(Action.Data(it.defaultServer, it.servers)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<ProfileIntent, Action, ProfileState, Msg, Nothing>() {

        override fun executeAction(action: Action) = when (action) {
            is Action.Data -> dispatch(Msg.Data(action.current, action.servers))
        }

        override fun executeIntent(intent: ProfileIntent) {
            when (intent) {
                ProfileIntent.Logout -> state().currentServer?.let { registry.remove(it.id) }
                is ProfileIntent.SwitchServer -> registry.setDefault(intent.id)
            }
        }
    }

    private object ReducerImpl : Reducer<ProfileState, Msg> {
        override fun ProfileState.reduce(msg: Msg): ProfileState = when (msg) {
            is Msg.Data -> copy(currentServer = msg.current, servers = msg.servers)
        }
    }
}
