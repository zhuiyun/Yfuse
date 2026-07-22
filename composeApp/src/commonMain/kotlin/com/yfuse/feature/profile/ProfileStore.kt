package com.yfuse.feature.profile

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.APP_VERSION
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class ProfileState(
    val currentServer: SavedServer? = null,
    val serverCount: Int = 0,
    val appVersion: String = APP_VERSION,
)

sealed interface ProfileIntent {
    /** Logs out of (removes) the current default server. */
    data object Logout : ProfileIntent
}

private sealed interface Action {
    data class Data(val current: SavedServer?, val count: Int) : Action
}

private sealed interface Msg {
    data class Data(val current: SavedServer?, val count: Int) : Msg
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
                    .onEach { dispatch(Action.Data(it.defaultServer, it.servers.size)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<ProfileIntent, Action, ProfileState, Msg, Nothing>() {

        override fun executeAction(action: Action) = when (action) {
            is Action.Data -> dispatch(Msg.Data(action.current, action.count))
        }

        override fun executeIntent(intent: ProfileIntent) {
            when (intent) {
                ProfileIntent.Logout -> state().currentServer?.let { registry.remove(it.id) }
            }
        }
    }

    private object ReducerImpl : Reducer<ProfileState, Msg> {
        override fun ProfileState.reduce(msg: Msg): ProfileState = when (msg) {
            is Msg.Data -> copy(currentServer = msg.current, serverCount = msg.count)
        }
    }
}
