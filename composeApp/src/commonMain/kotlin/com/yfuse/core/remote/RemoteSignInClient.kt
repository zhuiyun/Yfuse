package com.yfuse.core.remote

import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.sync.AccountRequiredForWatchException
import com.yfuse.core.sync.isWatchAuthenticationFailure
import com.yfuse.watch.protocol.RemoteSignInServer
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where this phone's 用手机登录 stands, for the one server it is handing a television. */
sealed interface RemoteSignInState {
    data object Idle : RemoteSignInState

    /** Putting the server before the television. */
    data object Offering : RemoteSignInState

    /** The television shows the server. Nothing secret leaves this phone until [RemoteSignInClient.send]. */
    data object Confirming : RemoteSignInState

    /** The session went, once; the television is having its server accept it. */
    data object Sending : RemoteSignInState

    /** The television saved the server. */
    data object Done : RemoteSignInState

    /** [retryable] is false where trying again cannot help: no 用手机登录 on the relay, or a lapsed account. */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
    ) : RemoteSignInState
}

/**
 * The phone's side of 用手机登录: hands one saved server to a television of the same account that
 * asked for one, over the relay 手机遥控 uses. [offer] puts the server before the television without
 * its session, so the television can show what is coming; only [send] — the person's own 发送, once
 * they have checked that — lets the session go, and it goes once. Nothing is resent after that: a
 * connection lost from then on is a failure to try again by hand.
 *
 * Its calls and its session share one thread — the screen's [scope] — so a session that [close] or
 * a new [offer] ended never writes [state] again.
 */
class RemoteSignInClient(
    private val accessToken: suspend () -> String?,
    private val refreshAccessToken: suspend () -> String?,
    private val scope: CoroutineScope,
    private val url: String? = REMOTE_RELAY_URL,
    private val connector: RemoteRelayConnector = KtorRemoteRelayConnector,
    private val answerTimeoutMs: Long = REMOTE_SIGN_IN_ANSWER_MS,
    identity: () -> RemotePhoneIdentity? = ::localRemotePhoneIdentity,
) {
    private val me by lazy { runCatching(identity).getOrNull() }
    private val _state = MutableStateFlow<RemoteSignInState>(RemoteSignInState.Idle)
    val state: StateFlow<RemoteSignInState> = _state.asStateFlow()
    private var session: Job? = null
    private var confirmation: CompletableDeferred<Unit>? = null

    /**
     * Puts [server] before the television of [televisionSessionId]; an earlier offer is withdrawn
     * first. False, with nothing sent, for a server a television cannot be handed.
     */
    fun offer(
        televisionSessionId: String,
        server: SavedServer,
    ): Boolean {
        val handed = server.toRemoteSignInServer() ?: return false
        session?.cancel()
        val confirm = CompletableDeferred<Unit>()
        confirmation = confirm
        _state.value = RemoteSignInState.Offering
        session = scope.launch { run(televisionSessionId, handed, confirm) }
        return true
    }

    /** 发送: the person checked what the television shows. The session goes now, and only now. */
    fun send() {
        if (_state.value == RemoteSignInState.Confirming) confirmation?.complete(Unit)
    }

    /** Leaves: a server still only offered is withdrawn from the television's screen. */
    fun close() {
        session?.cancel()
        session = null
        confirmation = null
        _state.value = RemoteSignInState.Idle
    }

    private suspend fun run(
        target: String,
        server: RemoteSignInServer,
        confirm: CompletableDeferred<Unit>,
    ) {
        val sent = SentFlag()
        var refreshed = false
        while (true) {
            val failure = runCatching { exchange(target, server, confirm, sent) }.exceptionOrNull() ?: return
            if (failure is CancellationException) throw failure
            // The account's session lapsed before anything secret went: sign in to the relay again, once.
            if (failure.isWatchAuthenticationFailure() && !sent.value && !refreshed && refreshAccessToken() != null) {
                refreshed = true
                continue
            }
            _state.value = failure.asSignInFailure()
            return
        }
    }

    private suspend fun exchange(
        target: String,
        server: RemoteSignInServer,
        confirm: CompletableDeferred<Unit>,
        sent: SentFlag,
    ) {
        val relay = url ?: throw RemoteControlRefusedException("手机遥控服务地址无效", supported = false)
        if (!remoteSignInCarried(relay)) throw RemoteControlRefusedException("用手机登录需要加密连接", supported = false)
        val token = accessToken() ?: throw AccountRequiredForWatchException()
        val self = me
        connector.connect(relay, token) { channel ->
            channel.send(
                WatchWireMessage(
                    type = "remoteSignInOffer",
                    remoteSessionId = target,
                    remoteDeviceId = self?.deviceId?.takeIf(WatchProtocol::isStableRemoteDeviceId),
                    name = self?.name,
                    signInServer = server.summary,
                ),
            )
            coroutineScope {
                var sending: Job? = null
                try {
                    while (true) {
                        val message = channel.receive() ?: throw RemoteControlRefusedException("与电视的连接已断开")
                        when (message.type) {
                            "remoteSignInOffered" -> {
                                if (WatchProtocol.CAPABILITY_REMOTE_SIGN_IN !in message.capabilities.orEmpty()) {
                                    throw RemoteControlRefusedException(REMOTE_SIGN_IN_UNSUPPORTED, supported = false)
                                }
                                if (sending == null) {
                                    _state.value = RemoteSignInState.Confirming
                                    sending = launch { sendOnce(channel, server, confirm, sent) }
                                }
                            }
                            "remoteSignInEnded" -> {
                                // Saved only if the session went and the television says nothing went wrong.
                                if (sent.value && message.errorCode == null) {
                                    _state.value = RemoteSignInState.Done
                                    return@coroutineScope
                                }
                                throw RemoteControlRefusedException(message.message ?: "电视已取消用手机登录")
                            }
                            "error" -> throw message.remoteSignInRefusal()
                        }
                    }
                } finally {
                    sending?.cancel()
                }
            }
        }
    }

    /** Waits for 发送, sends the session once, and gives the television a while to answer. */
    private suspend fun sendOnce(
        channel: RemoteRelayChannel,
        server: RemoteSignInServer,
        confirm: CompletableDeferred<Unit>,
        sent: SentFlag,
    ) {
        confirm.await()
        sent.value = true
        _state.value = RemoteSignInState.Sending
        channel.send(WatchWireMessage(type = "remoteSignInSend", signInServer = server))
        // The television has its server accept the session before it answers.
        delay(answerTimeoutMs)
        throw RemoteControlRefusedException("电视没有回应，请在电视上查看是否已登录")
    }

    /** Whether the session left this phone; from then on nothing is sent again. */
    private class SentFlag {
        var value = false
    }
}

/**
 * What a television of the same account is handed for this server on 用手机登录: its identity
 * address, its user and the session this phone signed in with — never a password, which the app
 * does not keep. Null for a server a television cannot take this way: Plex keeps its own PIN
 * sign-in, and details the relay would refuse are not offered at all.
 */
fun SavedServer.toRemoteSignInServer(): RemoteSignInServer? {
    val wireKind =
        when (kind) {
            MediaServerKind.Emby -> "Emby"
            MediaServerKind.Jellyfin -> "Jellyfin"
            MediaServerKind.Plex -> return null
        }
    return RemoteSignInServer(
        kind = wireKind,
        serverName = serverName.trim(),
        // The identity address, not a backup route the phone happens to be on: the television
        // then saves the very server this phone has.
        baseUrl = primaryUrl.trim().trimEnd('/'),
        userName = userName.trim(),
        userId = userId,
        accessToken = accessToken,
    ).takeIf(WatchProtocol::isValidRemoteSignInCredentials)
}

/**
 * How long a phone waits, once the session is sent, for the television to say how it went: longer
 * than the television gives its server ([REMOTE_SIGN_IN_RECEIVE_MS]), so the phone hears the
 * television's answer rather than its own time running out.
 */
const val REMOTE_SIGN_IN_ANSWER_MS = 60_000L

private const val REMOTE_SIGN_IN_UNSUPPORTED = "服务器暂不支持用手机登录，请等待服务端更新"

/** What an `error` from the relay means for 用手机登录; `message_type_invalid` is a relay without it. */
internal fun WatchWireMessage.remoteSignInRefusal(): RemoteControlRefusedException =
    if (errorCode == "message_type_invalid") {
        RemoteControlRefusedException(REMOTE_SIGN_IN_UNSUPPORTED, supported = false)
    } else {
        RemoteControlRefusedException(message?.takeIf(String::isNotBlank) ?: "无法把登录发送到电视")
    }

private fun Throwable.asSignInFailure(): RemoteSignInState.Failed =
    when {
        this is RemoteControlRefusedException ->
            RemoteSignInState.Failed(message ?: "无法把登录发送到电视", retryable = supported)
        this is AccountRequiredForWatchException -> RemoteSignInState.Failed("请先登录鱼服账号", retryable = false)
        isWatchAuthenticationFailure() ->
            RemoteSignInState.Failed("登录状态已失效，请重新登录鱼服账号", retryable = false)
        else -> RemoteSignInState.Failed("无法连接电视，请检查网络后重试")
    }
