package com.yfuse.feature.login

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.launch

data class LoginState(
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = username.isNotBlank() && password.isNotBlank() && !loading
}

sealed interface LoginIntent {
    data class UsernameChanged(val value: String) : LoginIntent
    data class PasswordChanged(val value: String) : LoginIntent
    data object Submit : LoginIntent
}

sealed interface LoginLabel {
    data object NavigateHome : LoginLabel
}

private sealed interface Msg {
    data class UsernameSet(val value: String) : Msg
    data class PasswordSet(val value: String) : Msg
    data object Loading : Msg
    data class Failed(val message: String) : Msg
    data object Done : Msg
}

/** Creates the [Store] driving the login screen. */
class LoginStoreFactory(
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val baseUrl: String,
) {
    fun create(): Store<LoginIntent, LoginState, LoginLabel> =
        storeFactory.create(
            name = "LoginStore",
            initialState = LoginState(),
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<LoginIntent, Nothing, LoginState, Msg, LoginLabel>() {

        override fun executeIntent(intent: LoginIntent) {
            when (intent) {
                is LoginIntent.UsernameChanged -> dispatch(Msg.UsernameSet(intent.value))
                is LoginIntent.PasswordChanged -> dispatch(Msg.PasswordSet(intent.value))
                LoginIntent.Submit -> submit()
            }
        }

        private fun submit() {
            val s = state()
            if (s.loading || s.username.isBlank() || s.password.isBlank()) return
            dispatch(Msg.Loading)
            scope.launch {
                repo.login(baseUrl, s.username.trim(), s.password)
                    .onSuccess {
                        dispatch(Msg.Done)
                        publish(LoginLabel.NavigateHome)
                    }
                    .onFailure { dispatch(Msg.Failed(it.toUserMessage("登录失败"))) }
            }
        }
    }

    private object ReducerImpl : Reducer<LoginState, Msg> {
        override fun LoginState.reduce(msg: Msg): LoginState = when (msg) {
            is Msg.UsernameSet -> copy(username = msg.value, error = null)
            is Msg.PasswordSet -> copy(password = msg.value, error = null)
            Msg.Loading -> copy(loading = true, error = null)
            is Msg.Failed -> copy(loading = false, error = msg.message)
            Msg.Done -> copy(loading = false)
        }
    }
}
