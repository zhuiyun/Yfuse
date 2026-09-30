package com.yfuse.feature.handoff

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.handoff.HandoffController
import com.yfuse.core.handoff.HandoffUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface DeviceHandoffIntent {
    /**
     * The page itself is on screen — not a 遥控器 or a 登录服务器到电视 opened in its place — or has
     * stopped being. While it is, the account's devices are asked for more often than the heartbeat.
     */
    data object PageShown : DeviceHandoffIntent

    data object PageHidden : DeviceHandoffIntent

    /** 接力播放 to the device with [sessionId]. */
    data class Send(
        val sessionId: String,
    ) : DeviceHandoffIntent

    /** 取消接力: stops the transfer under way. */
    data object CancelTransfer : DeviceHandoffIntent
}

private sealed interface Action {
    data class Devices(
        val state: HandoffUiState,
    ) : Action
}

private sealed interface Msg {
    data class Devices(
        val state: HandoffUiState,
    ) : Msg
}

/**
 * 设备接力: what [controller] knows of the account's devices and the transfer under way, and the
 * page's requests of it. The transfer itself is the controller's; it outlives the page.
 */
class DeviceHandoffStoreFactory(
    private val storeFactory: StoreFactory,
    private val controller: HandoffController,
) {
    fun create(): Store<DeviceHandoffIntent, HandoffUiState, Nothing> =
        storeFactory.create(
            name = "DeviceHandoffStore",
            initialState = controller.state.value,
            bootstrapper =
                coroutineBootstrapper<Action> {
                    controller.state
                        .onEach { dispatch(Action.Devices(it)) }
                        .launchIn(this)
                },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl : CoroutineExecutor<DeviceHandoffIntent, Action, HandoffUiState, Msg, Nothing>() {
        private var presence: Job? = null

        override fun executeAction(action: Action) =
            when (action) {
                is Action.Devices -> dispatch(Msg.Devices(action.state))
            }

        override fun executeIntent(intent: DeviceHandoffIntent) {
            when (intent) {
                DeviceHandoffIntent.PageShown -> refreshPresenceWhileShown()
                DeviceHandoffIntent.PageHidden -> {
                    presence?.cancel()
                    presence = null
                }
                is DeviceHandoffIntent.Send -> controller.send(intent.sessionId)
                DeviceHandoffIntent.CancelTransfer -> controller.cancelTransfer()
            }
        }

        // While this page is open a television that starts asking for a server, or hosting 手机遥控,
        // shows within seconds rather than at the next heartbeat.
        private fun refreshPresenceWhileShown() {
            if (presence?.isActive == true) return
            presence =
                scope.launch {
                    while (true) {
                        controller.refreshPresence()
                        delay(PRESENCE_REFRESH_MS)
                    }
                }
        }
    }

    private object ReducerImpl : Reducer<HandoffUiState, Msg> {
        override fun HandoffUiState.reduce(msg: Msg): HandoffUiState =
            when (msg) {
                is Msg.Devices -> msg.state
            }
    }
}

/** How often 设备接力, while open, asks for the account's devices; the heartbeat alone waits 10s. */
internal const val PRESENCE_REFRESH_MS = 5_000L
