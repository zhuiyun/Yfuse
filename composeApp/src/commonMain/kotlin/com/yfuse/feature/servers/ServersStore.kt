package com.yfuse.feature.servers

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class LoginForm(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !submitting
}

data class ServersState(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
    val dialogVisible: Boolean = false,
    val form: LoginForm = LoginForm(),
)

sealed interface ServersIntent {
    data object OpenAddDialog : ServersIntent
    data object DismissDialog : ServersIntent
    data class UrlChanged(val value: String) : ServersIntent
    data class UsernameChanged(val value: String) : ServersIntent
    data class PasswordChanged(val value: String) : ServersIntent
    data object Submit : ServersIntent
    data class SelectDefault(val id: String) : ServersIntent
    data class Remove(val id: String) : ServersIntent
}

sealed interface ServersLabel {
    /** A server was just added/logged in; the shell may jump to the library tab. */
    data object ServerAdded : ServersLabel
}

private sealed interface Action {
    data class Data(val servers: List<SavedServer>, val defaultId: String?) : Action
}

private sealed interface Msg {
    data class Data(val servers: List<SavedServer>, val defaultId: String?) : Msg
    data object DialogOpen : Msg
    data object DialogClose : Msg
    data class Url(val v: String) : Msg
    data class Username(val v: String) : Msg
    data class Password(val v: String) : Msg
    data object Submitting : Msg
    data object SubmitDone : Msg
    data class SubmitError(val m: String) : Msg
}

class ServersStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
) {
    fun create(): Store<ServersIntent, ServersState, ServersLabel> =
        storeFactory.create(
            name = "ServersStore",
            initialState = ServersState(),
            bootstrapper = coroutineBootstrapper<Action> {
                registry.data
                    .onEach { dispatch(Action.Data(it.servers, it.defaultServerId)) }
                    .launchIn(this)
            },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<ServersIntent, Action, ServersState, Msg, ServersLabel>() {

        override fun executeAction(action: Action) = when (action) {
            is Action.Data -> dispatch(Msg.Data(action.servers, action.defaultId))
        }

        override fun executeIntent(intent: ServersIntent) {
            when (intent) {
                ServersIntent.OpenAddDialog -> dispatch(Msg.DialogOpen)
                ServersIntent.DismissDialog -> dispatch(Msg.DialogClose)
                is ServersIntent.UrlChanged -> dispatch(Msg.Url(intent.value))
                is ServersIntent.UsernameChanged -> dispatch(Msg.Username(intent.value))
                is ServersIntent.PasswordChanged -> dispatch(Msg.Password(intent.value))
                ServersIntent.Submit -> submit()
                is ServersIntent.SelectDefault -> registry.setDefault(intent.id)
                is ServersIntent.Remove -> registry.remove(intent.id)
            }
        }

        private fun submit() {
            val form = state().form
            if (!form.canSubmit) return
            dispatch(Msg.Submitting)
            scope.launch {
                repo.authenticate(form.url, form.username.trim(), form.password)
                    .onSuccess {
                        registry.addOrUpdate(it.toSavedServer())
                        dispatch(Msg.SubmitDone)
                        publish(ServersLabel.ServerAdded)
                    }
                    .onFailure { dispatch(Msg.SubmitError(it.toUserMessage("登录失败"))) }
            }
        }
    }

    private object ReducerImpl : Reducer<ServersState, Msg> {
        override fun ServersState.reduce(msg: Msg): ServersState = when (msg) {
            is Msg.Data -> copy(servers = msg.servers, defaultServerId = msg.defaultId)
            Msg.DialogOpen -> copy(dialogVisible = true, form = LoginForm())
            Msg.DialogClose -> copy(dialogVisible = false, form = LoginForm())
            is Msg.Url -> copy(form = form.copy(url = msg.v, error = null))
            is Msg.Username -> copy(form = form.copy(username = msg.v, error = null))
            is Msg.Password -> copy(form = form.copy(password = msg.v, error = null))
            Msg.Submitting -> copy(form = form.copy(submitting = true, error = null))
            Msg.SubmitDone -> copy(dialogVisible = false, form = LoginForm())
            is Msg.SubmitError -> copy(form = form.copy(submitting = false, error = msg.m))
        }
    }
}
