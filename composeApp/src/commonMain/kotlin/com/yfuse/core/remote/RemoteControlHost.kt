package com.yfuse.core.remote

import com.yfuse.core.sync.AccountRequiredForWatchException
import com.yfuse.core.sync.backoffDelayMs
import com.yfuse.core.sync.isWatchAuthenticationFailure
import com.yfuse.watch.protocol.RemoteControlKey
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** What a phone did on 手机遥控, as the television hears it. */
sealed interface RemoteControlEvent {
    data class Key(
        val key: RemoteControlKey,
    ) : RemoteControlEvent

    /** The phone's whole field; empty clears it. */
    data class Text(
        val text: String,
    ) : RemoteControlEvent

    /** A phone connected ([joined]) or left; [phones] is how many are on this television now. */
    data class Phones(
        val phones: Int,
        val joined: Boolean,
    ) : RemoteControlEvent
}

/**
 * The television's side of 手机遥控. While [setActive] holds and an account is signed in, it keeps
 * one relay socket hosting a remote session under this device's account session and reconnects
 * when that drops. [hosting] is what the handoff heartbeat advertises; phones' keys and text arrive
 * on [events]. A relay that does not offer [WatchProtocol.CAPABILITY_REMOTE_CONTROL] is not asked
 * again until the next activation, and a lapsed account is left to the account screens.
 */
class RemoteControlHost(
    private val signedIn: StateFlow<Boolean>,
    private val accessToken: suspend () -> String?,
    private val refreshAccessToken: suspend () -> String?,
    private val url: String? = REMOTE_RELAY_URL,
    private val connector: RemoteRelayConnector = KtorRemoteRelayConnector,
    private val retryDelayMs: (Int) -> Long = ::backoffDelayMs,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val active = MutableStateFlow(false)
    private val _hosting = MutableStateFlow(false)
    val hosting: StateFlow<Boolean> = _hosting.asStateFlow()
    private val _events = MutableSharedFlow<RemoteControlEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RemoteControlEvent> = _events.asSharedFlow()

    init {
        scope.launch {
            combine(active, signedIn) { foreground, account -> foreground && account }
                .distinctUntilChanged()
                .collectLatest { run -> if (run) host() }
        }
    }

    /** The app is in the foreground: host while it is, and stop as soon as it is not. */
    fun setActive(value: Boolean) {
        active.value = value
    }

    private suspend fun host() {
        var failures = 0
        var refreshed = false
        try {
            while (true) {
                val failure =
                    runCatching {
                        hostOnce {
                            failures = 0
                            refreshed = false
                        }
                    }.exceptionOrNull()
                _hosting.value = false
                if (failure is CancellationException) throw failure
                if (failure is RemoteControlRefusedException && !failure.supported) return
                if (failure != null && failure.isWatchAuthenticationFailure()) {
                    if (refreshed || refreshAccessToken() == null) return
                    refreshed = true
                    continue
                }
                failures++
                delay(retryDelayMs(failures))
            }
        } finally {
            _hosting.value = false
        }
    }

    private suspend fun hostOnce(onHosting: () -> Unit) {
        val relay = url ?: throw RemoteControlRefusedException("手机遥控服务地址无效", supported = false)
        val token = accessToken() ?: throw AccountRequiredForWatchException()
        connector.connect(relay, token) { channel ->
            channel.send(WatchWireMessage(type = "remoteHost"))
            while (true) {
                val message = channel.receive() ?: return@connect
                when (message.type) {
                    "remoteHosting" -> {
                        if (WatchProtocol.CAPABILITY_REMOTE_CONTROL !in message.capabilities.orEmpty()) {
                            throw RemoteControlRefusedException("服务器暂不支持手机遥控", supported = false)
                        }
                        onHosting()
                        _hosting.value = true
                    }
                    "remoteConnected", "remoteDisconnected" ->
                        _events.tryEmit(
                            RemoteControlEvent.Phones(
                                phones = message.participantCount ?: 0,
                                joined = message.type == "remoteConnected",
                            ),
                        )
                    "remoteKey" ->
                        RemoteControlKey.fromWireName(message.remoteKey)?.let {
                            _events.tryEmit(RemoteControlEvent.Key(it))
                        }
                    "remoteText" ->
                        message.text?.takeIf(WatchProtocol::isValidRemoteText)?.let {
                            _events.tryEmit(RemoteControlEvent.Text(it))
                        }
                    // Before the relay has taken the session an error is its answer; after, it is noise.
                    "error" -> if (!_hosting.value) throw message.remoteRefusal("电视未能开启手机遥控")
                }
            }
        }
    }

    private companion object {
        const val EVENT_BUFFER = 64
    }
}
