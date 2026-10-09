package com.yfuse.core.remote

import com.yfuse.backend.BackendAccess
import com.yfuse.backend.BackendFeature
import com.yfuse.backend.BackendUnavailableException
import com.yfuse.core.sync.AccountRequiredForWatchException
import com.yfuse.core.sync.backoffDelayMs
import com.yfuse.core.sync.isWatchAuthenticationFailure
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Where the phone's 遥控器 stands. */
sealed interface PhoneRemoteState {
    data object Idle : PhoneRemoteState

    data object Connecting : PhoneRemoteState

    /**
     * 等待电视确认: on the television, which asks before a phone may press anything and has not
     * answered yet. Nothing this phone sent would count, so nothing is sent.
     */
    data object Waiting : PhoneRemoteState

    data object Connected : PhoneRemoteState

    /** [retryable] is false where 重新连接 cannot help: no 手机遥控 on the relay, or a lapsed account. */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
    ) : PhoneRemoteState
}

/**
 * The phone's side of 手机遥控: joins one television of the same account over the watch relay and
 * sends it keys and text. Keys go one per press. Text goes whole, once typing pauses for
 * [textDebounceMs], and again after every reconnect, so the television always ends up showing
 * exactly the phone's field whatever was lost on the way.
 *
 * The phone names itself as it joins — [identity], this install unless a test says otherwise — so
 * the television can ask about it by name before anything it sends is let through, and remember it
 * when told to. A phone that cannot say who it is still joins, as one the relay names for it. Where
 * the relay says the television is asking, the phone waits ([PhoneRemoteState.Waiting]) until it
 * hears it was let in; a relay or a television from before that says nothing, and it is in at once.
 */
class PhoneRemoteClient(
    private val accessToken: suspend () -> String?,
    private val refreshAccessToken: suspend () -> String?,
    private val url: String? = REMOTE_RELAY_URL,
    private val connector: RemoteRelayConnector = KtorRemoteRelayConnector,
    private val textDebounceMs: Long = REMOTE_TEXT_DEBOUNCE_MS,
    private val retryDelayMs: (Int) -> Long = ::backoffDelayMs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val backendAccess: BackendAccess = BackendAccess.Default,
    identity: () -> RemotePhoneIdentity? = ::localRemotePhoneIdentity,
) {
    private val me by lazy { runCatching(identity).getOrNull() }
    private val _state = MutableStateFlow<PhoneRemoteState>(PhoneRemoteState.Idle)
    val state: StateFlow<PhoneRemoteState> = _state.asStateFlow()
    private val keys = Channel<RemoteControlKey>(capacity = KEY_BUFFER)
    private val text = MutableStateFlow<String?>(null)
    private var session: Job? = null

    /** Joins the television of this account's [televisionSessionId]; a previous one is left first. */
    fun connect(televisionSessionId: String) {
        session?.cancel()
        drainKeys()
        if (!backendAccess.enabled) {
            session = null
            _state.value =
                PhoneRemoteState.Failed(
                    BackendUnavailableException(BackendFeature.RemoteControl).message.orEmpty(),
                    retryable = false,
                )
            return
        }
        _state.value = PhoneRemoteState.Connecting
        session = scope.launch { run(televisionSessionId) }
    }

    /** Sends one key; false when no television is connected to take it. */
    fun sendKey(key: RemoteControlKey): Boolean =
        _state.value == PhoneRemoteState.Connected && keys.trySend(key).isSuccess

    /** The phone's whole field. A value the relay would refuse is not sent at all. */
    fun updateText(value: String) {
        if (WatchProtocol.isValidRemoteText(value)) text.value = value
    }

    fun close() {
        session?.cancel()
        session = null
        drainKeys()
        _state.value = PhoneRemoteState.Idle
    }

    private suspend fun run(target: String) {
        var drops = 0
        var refreshed = false
        while (true) {
            val failure =
                runCatching {
                    join(target) {
                        drops = 0
                        refreshed = false
                    }
                }.exceptionOrNull()
            if (failure is CancellationException) throw failure
            if (failure is BackendUnavailableException) {
                _state.value = PhoneRemoteState.Failed(failure.message.orEmpty(), retryable = false)
                return
            }
            if (failure is RemoteControlRefusedException) {
                _state.value = PhoneRemoteState.Failed(failure.message ?: "无法连接电视", retryable = failure.supported)
                return
            }
            if (failure != null && failure.isWatchAuthenticationFailure()) {
                if (!refreshed) {
                    when (refreshRemoteToken(refreshAccessToken)) {
                        RemoteTokenRefresh.Renewed -> {
                            refreshed = true
                            continue
                        }
                        RemoteTokenRefresh.Unavailable -> {
                            _state.value = PhoneRemoteState.Failed("登录服务暂时不可用，请重试")
                            return
                        }
                        RemoteTokenRefresh.Expired -> Unit
                    }
                }
                _state.value = PhoneRemoteState.Failed("登录状态已失效，请重新登录鱼服账号", retryable = false)
                return
            }
            drops++
            if (drops > MAX_RECONNECTS) {
                _state.value = PhoneRemoteState.Failed("与电视的连接已断开")
                return
            }
            _state.value = PhoneRemoteState.Connecting
            delay(retryDelayMs(drops))
        }
    }

    private suspend fun join(
        target: String,
        onJoined: () -> Unit,
    ) {
        backendAccess.requireEnabled(BackendFeature.RemoteControl)
        val relay = url ?: throw RemoteControlRefusedException("手机遥控服务地址无效", supported = false)
        val token = accessToken() ?: throw AccountRequiredForWatchException()
        val self = me
        connector.connect(relay, token) { channel ->
            channel.send(
                WatchWireMessage(
                    type = "remoteJoin",
                    remoteSessionId = target,
                    // An id the relay would refuse would cost the whole join; better unnamed than that.
                    remoteDeviceId = self?.deviceId?.takeIf(WatchProtocol::isStableRemoteDeviceId),
                    name = self?.name,
                ),
            )
            coroutineScope {
                var joined = false
                var sending: Job? = null

                fun letIn() {
                    if (sending != null) return
                    // Keys pressed while reconnecting, or while the television asked, would land
                    // somewhere the viewer has left.
                    drainKeys()
                    _state.value = PhoneRemoteState.Connected
                    sending = launch { sendInput(channel) }
                }
                try {
                    while (true) {
                        val message = channel.receive() ?: break
                        when (message.type) {
                            "remoteJoined" -> {
                                val offered = message.capabilities.orEmpty()
                                if (WatchProtocol.CAPABILITY_REMOTE_CONTROL !in offered) {
                                    throw RemoteControlRefusedException("服务器暂不支持手机遥控", supported = false)
                                }
                                if (!joined) {
                                    joined = true
                                    onJoined()
                                    // Only a relay that passes on the television's answer says to wait for it.
                                    val asked =
                                        message.ready == false && WatchProtocol.CAPABILITY_REMOTE_PAIRING in offered
                                    if (asked) _state.value = PhoneRemoteState.Waiting else letIn()
                                }
                            }
                            "remoteAdmitted" -> if (joined) letIn()
                            "remoteDisconnected" ->
                                throw RemoteControlRefusedException(message.message ?: "电视已断开手机遥控")
                            // Before joining an error is the relay's answer; after, only a vanished
                            // television ends the session — pacing and a stray key are not worth it.
                            "error" ->
                                if (!joined || message.errorCode in SESSION_ENDING_ERRORS) {
                                    throw message.remoteRefusal("无法连接电视")
                                }
                        }
                    }
                } finally {
                    sending?.cancel()
                }
            }
        }
    }

    private suspend fun sendInput(channel: RemoteRelayChannel) {
        coroutineScope {
            launch {
                for (key in keys) channel.send(WatchWireMessage(type = "remoteKey", remoteKey = key.wireName))
            }
            text.filterNotNull().collectLatest { value ->
                delay(textDebounceMs)
                channel.send(WatchWireMessage(type = "remoteText", text = value))
            }
        }
    }

    private fun drainKeys() {
        while (keys.tryReceive().isSuccess) Unit
    }

    private companion object {
        const val KEY_BUFFER = 16
        const val MAX_RECONNECTS = 3
        val SESSION_ENDING_ERRORS = setOf("remote_unavailable", "remote_not_joined")
    }
}

/** Typing on the phone reaches the television this long after the last keystroke. */
const val REMOTE_TEXT_DEBOUNCE_MS = 150L
