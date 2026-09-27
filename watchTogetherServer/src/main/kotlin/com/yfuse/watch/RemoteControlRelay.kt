package com.yfuse.watch

import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/** Televisions hosting at once; each is also a socket the connection gate has admitted. */
private const val MAX_REMOTE_HOSTS = 4_096

/** Phones on one television: a household's, not an audience. */
private const val MAX_REMOTE_CONTROLLERS_PER_HOST = 4

/**
 * Keys and text a phone may send per window. A held direction repeats about seven times a second
 * and typing is debounced, so this is well above use; past it input is refused, not queued.
 */
private const val MAX_REMOTE_INPUTS_PER_WINDOW = 40
private const val REMOTE_INPUT_WINDOW_MS = 3_000L

/** Like a room broadcast: a television that cannot take a key within this is not there. */
private const val REMOTE_SEND_TIMEOUT_MS = 2_000L

private val remoteJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

internal enum class RemoteRefusal(
    val message: String,
    val errorCode: String,
) {
    WrongRole("这个连接不能这样使用遥控", "remote_invalid"),
    Full("遥控服务繁忙，请稍后再试", "remote_service_full"),
    Unavailable("电视不在线或未开启手机遥控", "remote_unavailable"),
    Busy("这台电视已连接多部手机", "remote_busy"),
    NotJoined("请先连接电视", "remote_not_joined"),
    RateLimited("操作太快，请稍后再试", "remote_rate_limited"),
}

internal sealed interface RemoteAdmission<out S> {
    /** [replaced] is the television's previous socket, still open after it reconnected. */
    data class Hosted<S>(
        val replaced: S?,
        val phones: Int,
    ) : RemoteAdmission<S>

    /** [fresh] is false when the phone was already on this television. */
    data class Joined<S>(
        val host: S,
        val phones: Int,
        val fresh: Boolean,
    ) : RemoteAdmission<S>

    data class Input<S>(
        val host: S,
    ) : RemoteAdmission<S>

    data class Refused(
        val reason: RemoteRefusal,
    ) : RemoteAdmission<Nothing>
}

internal sealed interface RemoteDeparture<out S> {
    data object None : RemoteDeparture<Nothing>

    data class HostLeft<S>(
        val phones: List<S>,
    ) : RemoteDeparture<S>

    data class PhoneLeft<S>(
        val host: S,
        val phones: Int,
    ) : RemoteDeparture<S>
}

/**
 * 手机遥控's pairings. A television hosts under its own account session and a phone joins it by
 * that session id. Every lookup is keyed by the joining socket's own account, so a television of
 * another account is never found: it reads exactly like one that is not online. Input only ever
 * goes from phones to their television.
 *
 * Generic over the socket so the bookkeeping is testable without a network.
 */
internal class RemoteControlRelay<S : Any>(
    private val maxHosts: Int = MAX_REMOTE_HOSTS,
    private val maxControllersPerHost: Int = MAX_REMOTE_CONTROLLERS_PER_HOST,
    private val maxInputsPerWindow: Int = MAX_REMOTE_INPUTS_PER_WINDOW,
    private val inputWindowMs: Long = REMOTE_INPUT_WINDOW_MS,
) {
    private data class HostKey(
        val userId: String,
        val sessionId: String,
    )

    private class Host<S>(
        val socket: S,
    ) {
        val controllers = LinkedHashSet<S>()
    }

    private class Controller(
        val host: HostKey,
    ) {
        val recentInputsAtMs = ArrayDeque<Long>()
    }

    private val hosts = mutableMapOf<HostKey, Host<S>>()
    private val hostedBy = mutableMapOf<S, HostKey>()
    private val controllers = mutableMapOf<S, Controller>()

    @Synchronized
    fun involves(socket: S): Boolean = socket in hostedBy || socket in controllers

    /** Phones currently on the television of [userId]'s [sessionId]; for tests and metrics. */
    @Synchronized
    fun phonesOn(
        userId: String,
        sessionId: String,
    ): Int = hosts[HostKey(userId, sessionId)]?.controllers?.size ?: 0

    @Synchronized
    fun host(
        userId: String,
        sessionId: String,
        socket: S,
    ): RemoteAdmission<S> {
        if (socket in controllers) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val key = HostKey(userId, sessionId)
        val existing = hosts[key]
        if (existing == null && hosts.size >= maxHosts) return RemoteAdmission.Refused(RemoteRefusal.Full)
        // A television that reconnects keeps its phones: they follow the session, not the socket.
        val replaced = existing?.socket?.takeIf { it != socket }
        val host = Host(socket)
        existing?.let { host.controllers.addAll(it.controllers) }
        replaced?.let(hostedBy::remove)
        hosts[key] = host
        hostedBy[socket] = key
        return RemoteAdmission.Hosted(replaced, host.controllers.size)
    }

    @Synchronized
    fun join(
        userId: String,
        ownSessionId: String,
        targetSessionId: String,
        socket: S,
    ): RemoteAdmission<S> {
        if (socket in hostedBy) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val key = HostKey(userId, targetSessionId)
        val previous = controllers[socket]
        // One television per phone socket; a phone that wants another one opens a new socket.
        if (previous != null && previous.host != key) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val host =
            hosts[key]?.takeIf { targetSessionId != ownSessionId }
                ?: return RemoteAdmission.Refused(RemoteRefusal.Unavailable)
        if (previous != null) return RemoteAdmission.Joined(host.socket, host.controllers.size, fresh = false)
        if (host.controllers.size >= maxControllersPerHost) return RemoteAdmission.Refused(RemoteRefusal.Busy)
        host.controllers += socket
        controllers[socket] = Controller(key)
        return RemoteAdmission.Joined(host.socket, host.controllers.size, fresh = true)
    }

    /** Admits one key or text from [socket], a phone, and names the television it goes to. */
    @Synchronized
    fun admitInput(
        socket: S,
        nowMs: Long,
    ): RemoteAdmission<S> {
        val controller = controllers[socket] ?: return RemoteAdmission.Refused(RemoteRefusal.NotJoined)
        val host = hosts[controller.host] ?: return RemoteAdmission.Refused(RemoteRefusal.Unavailable)
        val recent = controller.recentInputsAtMs
        while (recent.isNotEmpty() && nowMs - recent.first() >= inputWindowMs) recent.removeFirst()
        if (recent.size >= maxInputsPerWindow) return RemoteAdmission.Refused(RemoteRefusal.RateLimited)
        recent.addLast(nowMs)
        return RemoteAdmission.Input(host.socket)
    }

    /** Forgets [socket] and says who has to hear that it went. */
    @Synchronized
    fun depart(socket: S): RemoteDeparture<S> {
        controllers.remove(socket)?.let { controller ->
            val host = hosts[controller.host] ?: return RemoteDeparture.None
            host.controllers.remove(socket)
            return RemoteDeparture.PhoneLeft(host.socket, host.controllers.size)
        }
        val key = hostedBy.remove(socket) ?: return RemoteDeparture.None
        val host = hosts[key]?.takeIf { it.socket == socket } ?: return RemoteDeparture.None
        hosts.remove(key)
        host.controllers.forEach(controllers::remove)
        return RemoteDeparture.HostLeft(host.controllers.toList())
    }
}

/** Whether [message] is 手机遥控's rather than the room protocol's; pings stay the room's. */
internal fun RemoteControlRelay<WebSocketSession>.claims(
    socket: WebSocketSession,
    message: WatchWireMessage,
): Boolean =
    message.type != "ping" &&
        (message.type in WatchProtocol.REMOTE_CLIENT_MESSAGE_TYPES || involves(socket))

/** One 手机遥控 message from [socket]'s read loop, for the account that socket authenticated as. */
internal suspend fun RemoteControlRelay<WebSocketSession>.handle(
    socket: WebSocketSession,
    account: AuthenticatedAccount,
    message: WatchWireMessage,
    nowMs: Long = System.currentTimeMillis(),
) {
    val strayRoomFields =
        message.roomCode != null ||
            message.clientId != null ||
            message.mediaKey != null
    when (message.type) {
        "remoteHost" -> {
            if (
                strayRoomFields ||
                message.remoteSessionId != null ||
                message.remoteKey != null ||
                message.text != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val admission = host(account.userId, account.sessionId, socket)) {
                is RemoteAdmission.Hosted -> {
                    admission.replaced?.let { stale ->
                        withTimeoutOrNull(REMOTE_SEND_TIMEOUT_MS) {
                            runCatching {
                                stale.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "session superseded"))
                            }
                        }
                    }
                    socket.remoteSend(
                        WatchWireMessage(
                            type = "remoteHosting",
                            capabilities = WatchProtocol.SERVER_CAPABILITIES,
                            participantCount = admission.phones,
                        ),
                    )
                }
                is RemoteAdmission.Refused -> socket.refuse(admission.reason)
                else -> Unit
            }
        }
        "remoteJoin" -> {
            val target = message.remoteSessionId?.takeIf(WatchProtocol::isValidRemoteSessionId)
            if (target == null || strayRoomFields || message.remoteKey != null || message.text != null) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val admission = join(account.userId, account.sessionId, target, socket)) {
                is RemoteAdmission.Joined -> {
                    socket.remoteSend(
                        WatchWireMessage(
                            type = "remoteJoined",
                            capabilities = WatchProtocol.SERVER_CAPABILITIES,
                            participantCount = admission.phones,
                        ),
                    )
                    if (admission.fresh) {
                        admission.host.deliver(
                            WatchWireMessage(type = "remoteConnected", participantCount = admission.phones),
                        )
                    }
                }
                is RemoteAdmission.Refused -> socket.refuse(admission.reason)
                else -> Unit
            }
        }
        "remoteKey" -> {
            val key = message.remoteKey?.takeIf(WatchProtocol::isValidRemoteKey)
            if (key == null || strayRoomFields || message.remoteSessionId != null || message.text != null) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            forward(socket, nowMs, WatchWireMessage(type = "remoteKey", remoteKey = key))
        }
        "remoteText" -> {
            val text = message.text?.takeIf(WatchProtocol::isValidRemoteText)
            if (text == null || strayRoomFields || message.remoteSessionId != null || message.remoteKey != null) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            forward(socket, nowMs, WatchWireMessage(type = "remoteText", text = text))
        }
        // A hosting or controlling socket is not a room member: `hello` and room commands stop here.
        else -> socket.refuse(RemoteRefusal.WrongRole)
    }
}

/** Runs from the socket's cleanup; tells the other side of every pairing [socket] was part of. */
internal suspend fun RemoteControlRelay<WebSocketSession>.leave(socket: WebSocketSession) {
    when (val departure = depart(socket)) {
        is RemoteDeparture.HostLeft ->
            departure.phones.forEach { phone ->
                phone.deliver(
                    WatchWireMessage(
                        type = "remoteDisconnected",
                        message = "电视已断开手机遥控",
                        errorCode = "remote_host_left",
                    ),
                )
            }
        is RemoteDeparture.PhoneLeft ->
            departure.host.deliver(
                WatchWireMessage(type = "remoteDisconnected", participantCount = departure.phones),
            )
        RemoteDeparture.None -> Unit
    }
}

private suspend fun RemoteControlRelay<WebSocketSession>.forward(
    socket: WebSocketSession,
    nowMs: Long,
    message: WatchWireMessage,
) {
    when (val admission = admitInput(socket, nowMs)) {
        is RemoteAdmission.Input ->
            if (!admission.host.deliver(message)) socket.refuse(RemoteRefusal.Unavailable)
        is RemoteAdmission.Refused -> socket.refuse(admission.reason)
        else -> Unit
    }
}

private suspend fun WebSocketSession.deliver(message: WatchWireMessage): Boolean =
    withTimeoutOrNull(REMOTE_SEND_TIMEOUT_MS) { runCatching { remoteSend(message) }.isSuccess } ?: false

private suspend fun WebSocketSession.refuse(reason: RemoteRefusal) {
    runCatching {
        remoteSend(WatchWireMessage(type = "error", message = reason.message, errorCode = reason.errorCode))
    }
}

private suspend fun WebSocketSession.remoteSend(message: WatchWireMessage) {
    send(
        remoteJson.encodeToString(
            WatchWireMessage.serializer(),
            message.copy(
                protocolVersion = message.protocolVersion ?: WatchProtocol.VERSION,
                serverAtMs = System.currentTimeMillis(),
            ),
        ),
    )
}
