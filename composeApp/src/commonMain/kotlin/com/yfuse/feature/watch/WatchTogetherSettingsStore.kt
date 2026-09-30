package com.yfuse.feature.watch

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.arkivanov.mvikotlin.extensions.coroutines.coroutineBootstrapper
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.WatchTogetherState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** What 一起看's settings do to the room: [WatchTogetherClient] in the app, see the function of the same name. */
interface WatchRoom {
    val state: StateFlow<WatchTogetherState>

    fun joinRoom(
        endpoint: String,
        roomCode: String,
        mediaKey: String,
    )

    fun leave()

    fun updateProfile(
        name: String,
        avatarId: Int,
    )
}

fun WatchRoom(client: WatchTogetherClient): WatchRoom =
    object : WatchRoom {
        override val state = client.state

        override fun joinRoom(
            endpoint: String,
            roomCode: String,
            mediaKey: String,
        ) = client.joinRoom(endpoint, roomCode, mediaKey)

        override fun leave() = client.leave()

        override fun updateProfile(
            name: String,
            avatarId: Int,
        ) = client.updateProfile(name, avatarId)
    }

/** What 一起看's settings page, its 加入一起看 dialog and its 一起看资料 dialog show. */
data class WatchTogetherSettingsState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val roomCode: String? = null,
    val participantCount: Int = 0,
    val isHost: Boolean = false,
    /** What the join dialog reports: the room's own error, or else what keeps it from following along. */
    val problem: String? = null,
    val nickname: String = WatchTogetherPreferences.DEFAULT_NICKNAME,
    val avatarId: Int = 0,
    val chatDanmaku: Boolean = true,
    val chatPreview: Boolean = true,
)

sealed interface WatchTogetherSettingsIntent {
    /** 加入: the room [roomCode] names. */
    data class Join(
        val roomCode: String,
    ) : WatchTogetherSettingsIntent

    /** 退出房间, once confirmed. */
    data object Leave : WatchTogetherSettingsIntent

    /** 一起看资料's 保存. */
    data class SaveProfile(
        val nickname: String,
        val avatarId: Int,
    ) : WatchTogetherSettingsIntent

    /** 聊天弹幕. */
    data class SetChatDanmaku(
        val enabled: Boolean,
    ) : WatchTogetherSettingsIntent

    /** 聊天消息浮层. */
    data class SetChatPreview(
        val enabled: Boolean,
    ) : WatchTogetherSettingsIntent
}

private sealed interface Action {
    data class Settings(
        val state: WatchTogetherSettingsState,
    ) : Action
}

private sealed interface Msg {
    data class Settings(
        val state: WatchTogetherSettingsState,
    ) : Msg
}

/** 一起看's settings: the room as [room] has it, and this device's room profile and chat display. */
class WatchTogetherSettingsStoreFactory(
    private val storeFactory: StoreFactory,
    private val room: WatchRoom,
    private val preferences: WatchTogetherPreferences,
) {
    fun create(): Store<WatchTogetherSettingsIntent, WatchTogetherSettingsState, Nothing> =
        storeFactory.create(
            name = "WatchTogetherSettingsStore",
            initialState =
                settingsState(
                    room.state.value,
                    preferences.nickname.value,
                    preferences.avatarId.value,
                    preferences.chatDanmakuEnabled.value,
                    preferences.chatPreviewEnabled.value,
                ),
            bootstrapper =
                coroutineBootstrapper<Action> {
                    // The room's state moves with every chat line and reaction; the page follows
                    // only what it shows.
                    combine(
                        room.state,
                        preferences.nickname,
                        preferences.avatarId,
                        preferences.chatDanmakuEnabled,
                        preferences.chatPreviewEnabled,
                        ::settingsState,
                    ).distinctUntilChanged()
                        .onEach { dispatch(Action.Settings(it)) }
                        .launchIn(this)
                },
            executorFactory = ::ExecutorImpl,
            reducer = ReducerImpl,
        )

    private inner class ExecutorImpl :
        CoroutineExecutor<WatchTogetherSettingsIntent, Action, WatchTogetherSettingsState, Msg, Nothing>() {
        override fun executeAction(action: Action) =
            when (action) {
                is Action.Settings -> dispatch(Msg.Settings(action.state))
            }

        override fun executeIntent(intent: WatchTogetherSettingsIntent) {
            when (intent) {
                // A code brings no title: once in, the room's own timeline decides what opens.
                is WatchTogetherSettingsIntent.Join ->
                    room.joinRoom(preferences.endpoint.value, intent.roomCode, mediaKey = "")
                WatchTogetherSettingsIntent.Leave -> room.leave()
                is WatchTogetherSettingsIntent.SaveProfile -> {
                    preferences.setProfile(intent.nickname, intent.avatarId)
                    // The room hears what the preferences kept: trimmed, cut to length, the default
                    // name for a blank one.
                    room.updateProfile(preferences.nickname.value, preferences.avatarId.value)
                }
                is WatchTogetherSettingsIntent.SetChatDanmaku -> preferences.setChatDanmakuEnabled(intent.enabled)
                is WatchTogetherSettingsIntent.SetChatPreview -> preferences.setChatPreviewEnabled(intent.enabled)
            }
        }
    }

    private object ReducerImpl : Reducer<WatchTogetherSettingsState, Msg> {
        override fun WatchTogetherSettingsState.reduce(msg: Msg): WatchTogetherSettingsState =
            when (msg) {
                is Msg.Settings -> msg.state
            }
    }
}

private fun settingsState(
    room: WatchTogetherState,
    nickname: String,
    avatarId: Int,
    chatDanmaku: Boolean,
    chatPreview: Boolean,
): WatchTogetherSettingsState =
    WatchTogetherSettingsState(
        connected = room.connected,
        connecting = room.connecting,
        roomCode = room.roomCode,
        participantCount = room.participantCount,
        isHost = room.isHost,
        problem = room.error ?: room.syncWarning,
        nickname = nickname,
        avatarId = avatarId,
        chatDanmaku = chatDanmaku,
        chatPreview = chatPreview,
    )
