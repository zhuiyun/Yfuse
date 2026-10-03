package com.yfuse.feature.trakt

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.trakt.TraktRepository
import com.yfuse.core.trakt.TraktUiState
import io.ktor.http.Url
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the Trakt page shows: the connection as [TraktRepository] has it, and how this device authorizes. */
data class TraktSettingsState(
    val trakt: TraktUiState = TraktUiState(),
    /** A television authorizes with a code shown on screen; a phone opens Trakt's own page. */
    val television: Boolean = false,
) {
    /** 连接 Trakt: signed in, nothing under way, and the authorization this device uses configured. */
    val canConnect: Boolean
        get() {
            val configuration = trakt.configuration
            val available = if (television) configuration?.deviceAvailable else configuration?.oauthAvailable
            return trakt.signedIn && !trakt.busy && available == true
        }

    /** The authorization's page, offered as 打开授权网页 only when it is Trakt's own; see [trustedTraktPage]. */
    val verificationPage: String?
        get() = trakt.challenge?.verificationUrl?.takeIf(::trustedTraktPage)
}

/** Whether [url] is one of Trakt's own pages, over HTTPS: the only kind 打开授权网页 hands to the system. */
internal fun trustedTraktPage(url: String): Boolean {
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    return parsed.protocol.name == "https" && parsed.host in setOf("auth.trakt.tv", "trakt.tv", "app.trakt.tv")
}

sealed interface TraktSettingsIntent {
    /**
     * The page is on screen, or has left it. While it is, the configuration is read again whenever
     * the account signs in or out; leaving ends an authorization still under way.
     */
    data object PageShown : TraktSettingsIntent

    data object PageHidden : TraktSettingsIntent

    /** 连接 Trakt. */
    data object Connect : TraktSettingsIntent

    /** 取消授权. */
    data object CancelAuthorization : TraktSettingsIntent

    /** 断开 Trakt. */
    data object Disconnect : TraktSettingsIntent

    data object ImportWatchlist : TraktSettingsIntent

    data object ImportHistory : TraktSettingsIntent

    /** 播放上报. */
    data class SetScrobbling(
        val enabled: Boolean,
    ) : TraktSettingsIntent

    /** 重试待上报内容. */
    data object RetryPending : TraktSettingsIntent
}

private sealed interface Action {
    data class Trakt(
        val state: TraktUiState,
    ) : Action
}

private sealed interface Msg {
    data class Trakt(
        val state: TraktUiState,
    ) : Msg
}

/** The Trakt page for [repository]; [television] picks the device-code authorization over OAuth. */
class TraktSettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val repository: TraktRepository,
    private val television: Boolean,
) {
    fun create(): Store<TraktSettingsIntent, TraktSettingsState, Nothing> =
        storeFactory.create(
            name = "TraktSettingsStore",
            initialState = TraktSettingsState(repository.state.value, television),
            bootstrapper =
                coroutineBootstrapper<Action> {
                    repository.state
                        .onEach { dispatch(Action.Trakt(it)) }
                        .launchIn(this)
                },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<TraktSettingsIntent, Action, TraktSettingsState, Msg, Nothing>() {
        /** Reads the configuration again as the account changes, for as long as the page shows. */
        private var configuration: Job? = null

        override fun executeAction(action: Action) =
            when (action) {
                is Action.Trakt -> dispatch(Msg.Trakt(action.state))
            }

        override fun executeIntent(intent: TraktSettingsIntent) {
            when (intent) {
                TraktSettingsIntent.PageShown -> followConfiguration()
                TraktSettingsIntent.PageHidden -> {
                    configuration?.cancel()
                    configuration = null
                    repository.cancelAuthorization()
                }
                TraktSettingsIntent.Connect -> repository.connect(television)
                TraktSettingsIntent.CancelAuthorization -> repository.cancelAuthorization()
                TraktSettingsIntent.Disconnect -> repository.disconnect()
                TraktSettingsIntent.ImportWatchlist -> repository.importWatchlist()
                TraktSettingsIntent.ImportHistory -> repository.importHistory()
                is TraktSettingsIntent.SetScrobbling -> repository.setScrobbling(intent.enabled)
                TraktSettingsIntent.RetryPending -> repository.retryPending()
            }
        }

        /** A page that closes ends an authorization still under way. */
        override fun dispose() {
            repository.cancelAuthorization()
            super.dispose()
        }

        // On arrival, and again each time the account signs in or out; a read still in flight when
        // that changes gives way to the new one.
        private fun followConfiguration() {
            if (configuration?.isActive == true) return
            configuration =
                scope.launch {
                    repository.state
                        .map { it.signedIn }
                        .distinctUntilChanged()
                        .collectLatest { repository.refreshConfiguration() }
                }
        }
    }

    private object ReducerImpl : Reducer<TraktSettingsState, Msg> {
        override fun TraktSettingsState.reduce(msg: Msg): TraktSettingsState =
            when (msg) {
                is Msg.Trakt -> copy(trakt = msg.state)
            }
    }
}
