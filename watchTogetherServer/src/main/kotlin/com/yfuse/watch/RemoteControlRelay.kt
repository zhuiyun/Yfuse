package com.yfuse.watch

import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.protocol.RemoteSignInServer
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireCredential
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.websocket.CloseReason
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.send
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID

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

    /** The television asks before a phone may press anything, and has not let this one in. */
    NotAdmitted("电视尚未允许这部手机遥控", "remote_not_admitted"),
    RateLimited("操作太快，请稍后再试", "remote_rate_limited"),
    NotAsking("电视没有在等待手机登录，请先在电视的「添加服务器」里选择「用手机登录」", "remote_sign_in_unavailable"),
    SignInBusy("另一部手机正在为这台电视登录", "remote_sign_in_busy"),
}

/**
 * A phone on 手机遥控, as its television hears of it. [deviceId] is the phone's own id for its
 * install, the same on every connection, or — for a phone that named none — one made up for this
 * connection alone (see [WatchProtocol.REMOTE_EPHEMERAL_DEVICE_PREFIX]). [name] is what the phone
 * calls itself, for the television to show when it asks whether to let it in.
 */
internal data class RemotePhone(
    val deviceId: String,
    val name: String? = null,
) {
    companion object {
        /** A stand-in for a phone that named none; it lasts this one connection. */
        fun unnamed(): RemotePhone = RemotePhone(WatchProtocol.REMOTE_EPHEMERAL_DEVICE_PREFIX + UUID.randomUUID())
    }
}

internal sealed interface RemoteAdmission<out S> {
    /** [replaced] is the television's previous socket, still open after it reconnected. */
    data class Hosted<S>(
        val replaced: S?,
        val phones: Int,
    ) : RemoteAdmission<S>

    /**
     * [fresh] is false when the phone was already on this television. [waiting] is true while its
     * television, one that asks before a phone may press anything, has not let it in.
     */
    data class Joined<S>(
        val host: S,
        val phones: Int,
        val fresh: Boolean,
        val phone: RemotePhone,
        val waiting: Boolean = false,
    ) : RemoteAdmission<S>

    /** One key or text from [phone], for its television, [host]. */
    data class Input<S>(
        val host: S,
        val phone: RemotePhone,
    ) : RemoteAdmission<S>

    /** A television let [phones] go; [remaining] are still on it. */
    data class Released<S>(
        val phones: List<S>,
        val remaining: Int,
    ) : RemoteAdmission<S>

    /**
     * A television let [phones] in: each of them, and no other, is told. [grants] are the phones
     * among them to hand a new pairing token, by the pairing it binds ([remotePairingKey]).
     */
    data class Admitted<S>(
        val phones: List<S>,
        val grants: Map<String, List<S>> = emptyMap(),
    ) : RemoteAdmission<S>

    /**
     * A television asks for a server. [shown] is what a phone had already put before it, to show
     * again on a socket that has just replaced the old one. [dropped] is the phone of an earlier
     * sign-in this ask replaced, spent or lapsed, which has to hear it ended; [droppedSent] says
     * whether its session had gone.
     */
    data class SignInAsked<S>(
        val shown: SignInShown?,
        val dropped: S? = null,
        val droppedSent: Boolean = false,
    ) : RemoteAdmission<S>

    /** A phone puts a server before its television, [host], which is to show [shown]. */
    data class SignInOffered<S>(
        val host: S,
        val shown: SignInShown,
    ) : RemoteAdmission<S>

    /** The session for what [phone] offered goes to its television, [host] — this once. */
    data class SignInSent<S>(
        val host: S,
        val phone: RemotePhone,
    ) : RemoteAdmission<S>

    /** A television stopped asking; [phone] has to hear how it went, and [sent] is whether its session went. */
    data class SignInEnded<S>(
        val phone: S?,
        val sent: Boolean,
    ) : RemoteAdmission<S>

    data class Refused(
        val reason: RemoteRefusal,
    ) : RemoteAdmission<Nothing>
}

/** What a television shows on 用手机登录: which phone offers which server, without its session. */
internal data class SignInShown(
    val phone: RemotePhone,
    val server: RemoteSignInServer,
)

internal sealed interface RemoteDeparture<out S> {
    data object None : RemoteDeparture<Nothing>

    /** [signingIn] is a phone whose 用手机登录 with this television is cut short. */
    data class HostLeft<S>(
        val phones: List<S>,
        val signingIn: S? = null,
    ) : RemoteDeparture<S>

    /** The phone that had put a server before [host] left before it sent the session. */
    data class SignInWithdrawn<S>(
        val host: S,
        val phone: RemotePhone,
    ) : RemoteDeparture<S>

    data class PhoneLeft<S>(
        val host: S,
        val phones: Int,
        val phone: RemotePhone,
    ) : RemoteDeparture<S>
}

/**
 * 手机遥控's pairings. A television hosts under its own account session and a phone joins it by
 * that session id. Every lookup is keyed by the joining socket's own account, so a television of
 * another account is never found: it reads exactly like one that is not online. Input only ever
 * goes from phones to their television, named with the phone it came from, and only a television
 * lets one of its phones go — which is how it refuses one it has not agreed to — or in, which the
 * phone is told so that it stops waiting.
 *
 * 用手机登录 rides the same pairings: only a television, on the socket it hosts on, asks for a
 * server, and only a phone of its account — on a socket of its own, never one that hosts or
 * controls — may offer one, while it asks. The session goes once, from the phone that offered, for
 * exactly the server the television was shown; after that the ask is spent.
 *
 * Generic over the socket so the bookkeeping is testable without a network.
 */
internal class RemoteControlRelay<S : Any>(
    /** Binds a television's admission of a phone to a token only that phone holds. */
    val pairingTokens: RemotePairingTokens = RemotePairingTokens(),
    private val maxHosts: Int = MAX_REMOTE_HOSTS,
    private val maxControllersPerHost: Int = MAX_REMOTE_CONTROLLERS_PER_HOST,
    private val maxInputsPerWindow: Int = MAX_REMOTE_INPUTS_PER_WINDOW,
    private val inputWindowMs: Long = REMOTE_INPUT_WINDOW_MS,
    private val signInAskMs: Long = WatchProtocol.REMOTE_SIGN_IN_ASK_MS,
) {
    private data class HostKey(
        val userId: String,
        val sessionId: String,
    )

    private class Host<S>(
        val socket: S,
        /** It asks before a phone may press anything, and says when it lets one in. */
        val asks: Boolean,
    ) {
        val controllers = LinkedHashSet<S>()

        /** Its 用手机登录, from the ask until it says how it went. */
        var signIn: SignIn<S>? = null
    }

    private class Controller(
        val host: HostKey,
        val phone: RemotePhone,
        /** The stable id a pairing token for this phone is bound to; null when it cannot hold one. */
        val pairingDeviceId: String?,
        /** It presented the token on record for [pairingDeviceId]; nothing new to hand it. */
        var verified: Boolean,
    ) {
        val recentInputsAtMs = ArrayDeque<Long>()

        /** Its television said it may press keys; only one that [Host.asks] says so. */
        var admitted = false
    }

    private class SignIn<S>(
        val askedAtMs: Long,
    ) {
        /** The phone whose server the television shows now, and the socket it offered on. */
        var offer: SignInOffer<S>? = null

        /** The session went to the television: nothing more is offered or sent on this ask. */
        var sent = false
    }

    private class SignInOffer<S>(
        val socket: S,
        val shown: SignInShown,
    )

    /** A phone's socket on 用手机登录, paced like a phone's keys. */
    private class Signer(
        val host: HostKey,
    ) {
        val recentOffersAtMs = ArrayDeque<Long>()
    }

    private val hosts = mutableMapOf<HostKey, Host<S>>()
    private val hostedBy = mutableMapOf<S, HostKey>()
    private val controllers = mutableMapOf<S, Controller>()
    private val signers = mutableMapOf<S, Signer>()

    @Synchronized
    fun involves(socket: S): Boolean = socket in hostedBy || socket in controllers || socket in signers

    /** Phones currently on the television of [userId]'s [sessionId]; for tests and metrics. */
    @Synchronized
    fun phonesOn(
        userId: String,
        sessionId: String,
    ): Int = hosts[HostKey(userId, sessionId)]?.controllers?.size ?: 0

    /** [asks] is what the television said as it hosted: it asks before a phone may press anything. */
    @Synchronized
    fun host(
        userId: String,
        sessionId: String,
        socket: S,
        asks: Boolean = false,
    ): RemoteAdmission<S> {
        if (socket in controllers || socket in signers) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val key = HostKey(userId, sessionId)
        val existing = hosts[key]
        if (existing == null && hosts.size >= maxHosts) return RemoteAdmission.Refused(RemoteRefusal.Full)
        // A television that reconnects keeps its phones, and what it asked for: they follow the
        // session, not the socket.
        val replaced = existing?.socket?.takeIf { it != socket }
        val host = Host(socket, asks)
        existing?.let {
            host.controllers.addAll(it.controllers)
            host.signIn = it.signIn
        }
        replaced?.let(hostedBy::remove)
        hosts[key] = host
        hostedBy[socket] = key
        return RemoteAdmission.Hosted(replaced, host.controllers.size)
    }

    /**
     * [phone] is who the joining socket says it is, as the television is to see it; a socket
     * already on this television stays who it was. [pairingDeviceId] is the stable id a pairing
     * token would be bound to, and [verified] whether the phone already proved it holds that token.
     */
    @Synchronized
    fun join(
        userId: String,
        ownSessionId: String,
        targetSessionId: String,
        socket: S,
        phone: RemotePhone,
        pairingDeviceId: String? = null,
        verified: Boolean = false,
    ): RemoteAdmission<S> {
        if (socket in hostedBy || socket in signers) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val key = HostKey(userId, targetSessionId)
        val previous = controllers[socket]
        // One television per phone socket; a phone that wants another one opens a new socket.
        if (previous != null && previous.host != key) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val host =
            hosts[key]?.takeIf { targetSessionId != ownSessionId }
                ?: return RemoteAdmission.Refused(RemoteRefusal.Unavailable)
        if (previous != null) {
            return RemoteAdmission.Joined(
                host.socket,
                host.controllers.size,
                fresh = false,
                phone = previous.phone,
                waiting = host.asks && !previous.admitted,
            )
        }
        if (host.controllers.size >= maxControllersPerHost) return RemoteAdmission.Refused(RemoteRefusal.Busy)
        host.controllers += socket
        controllers[socket] = Controller(key, phone, pairingDeviceId, verified)
        return RemoteAdmission.Joined(
            host.socket,
            host.controllers.size,
            fresh = true,
            phone = phone,
            waiting = host.asks,
        )
    }

    /** Admits one key or text from [socket], a phone, and names the television it goes to. */
    @Synchronized
    fun admitInput(
        socket: S,
        nowMs: Long,
    ): RemoteAdmission<S> {
        val controller = controllers[socket] ?: return RemoteAdmission.Refused(RemoteRefusal.NotJoined)
        val host = hosts[controller.host] ?: return RemoteAdmission.Refused(RemoteRefusal.Unavailable)
        // The television's own check was the only one; a phone that did not wait for its answer
        // must not reach the screen, whatever the television's app does with the key.
        if (host.asks && !controller.admitted) return RemoteAdmission.Refused(RemoteRefusal.NotAdmitted)
        val recent = controller.recentInputsAtMs
        while (recent.isNotEmpty() && nowMs - recent.first() >= inputWindowMs) recent.removeFirst()
        if (recent.size >= maxInputsPerWindow) return RemoteAdmission.Refused(RemoteRefusal.RateLimited)
        recent.addLast(nowMs)
        return RemoteAdmission.Input(host.socket, controller.phone)
    }

    /**
     * The television on [socket] lets every one of its phones named [deviceId] go. Only the socket
     * a television hosts on may; a phone cannot let another one go, nor a stale television socket.
     */
    @Synchronized
    fun release(
        socket: S,
        deviceId: String,
    ): RemoteAdmission<S> {
        val key = hostedBy[socket] ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val host = hosts[key]?.takeIf { it.socket == socket } ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val leaving = host.controllers.filter { controllers[it]?.phone?.deviceId == deviceId }
        leaving.forEach { phone ->
            host.controllers.remove(phone)
            controllers.remove(phone)
        }
        return RemoteAdmission.Released(leaving, host.controllers.size)
    }

    /**
     * The television on [socket] lets every one of its phones named [deviceId] in. As for
     * [release], only the socket a television hosts on may: a phone cannot let itself in.
     */
    @Synchronized
    fun admit(
        socket: S,
        deviceId: String,
    ): RemoteAdmission<S> {
        val key = hostedBy[socket] ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val host = hosts[key]?.takeIf { it.socket == socket } ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val entering =
            host.controllers.filter { phone ->
                val controller = controllers[phone]?.takeIf { it.phone.deviceId == deviceId }
                controller?.admitted = true
                controller != null
            }
        // Each phone that can hold a token and has not shown one gets one, bound to this pairing.
        val grants =
            entering
                .filter { phone -> controllers[phone]?.let { !it.verified && it.pairingDeviceId != null } == true }
                .groupBy { phone ->
                    remotePairingKey(key.userId, key.sessionId, checkNotNull(controllers[phone]?.pairingDeviceId))
                }
        grants.values.flatten().forEach { phone -> controllers[phone]?.verified = true }
        return RemoteAdmission.Admitted(entering, grants)
    }

    /**
     * The television on [socket] asks for a server. Asking again while it asks — as it does on a
     * socket that has just replaced its old one — keeps what a phone already offered, to show it
     * again; an ask that is spent or has lapsed is replaced, and its phone let go.
     */
    @Synchronized
    fun askSignIn(
        socket: S,
        nowMs: Long,
    ): RemoteAdmission<S> {
        val host = hostOn(socket) ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val current = host.signIn
        if (current != null && current.live(nowMs)) return RemoteAdmission.SignInAsked(current.offer?.shown)
        host.signIn = SignIn(nowMs)
        val dropped = current?.offer?.socket?.also(signers::remove)
        return RemoteAdmission.SignInAsked(shown = null, dropped = dropped, droppedSent = current?.sent == true)
    }

    /**
     * A phone offers [server] — without its session — to the television of its own account's
     * [targetSessionId], while that television asks. One phone at a time: another is refused until
     * it has gone; the same one may offer another of its servers instead, and stays who it said it was.
     */
    @Synchronized
    fun offerSignIn(
        userId: String,
        ownSessionId: String,
        targetSessionId: String,
        socket: S,
        phone: RemotePhone,
        server: RemoteSignInServer,
        nowMs: Long,
    ): RemoteAdmission<S> {
        if (socket in hostedBy || socket in controllers) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val key = HostKey(userId, targetSessionId)
        val signer = signers[socket]
        // One television per phone socket, as for 手机遥控.
        if (signer != null && signer.host != key) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val host =
            hosts[key]?.takeIf { targetSessionId != ownSessionId }
                ?: return RemoteAdmission.Refused(RemoteRefusal.Unavailable)
        val ask = host.signIn?.takeIf { it.live(nowMs) } ?: return RemoteAdmission.Refused(RemoteRefusal.NotAsking)
        val offered = ask.offer
        if (offered != null && offered.socket != socket) return RemoteAdmission.Refused(RemoteRefusal.SignInBusy)
        val pacing = signer ?: Signer(key)
        val recent = pacing.recentOffersAtMs
        while (recent.isNotEmpty() && nowMs - recent.first() >= inputWindowMs) recent.removeFirst()
        if (recent.size >= maxInputsPerWindow) return RemoteAdmission.Refused(RemoteRefusal.RateLimited)
        recent.addLast(nowMs)
        val shown = SignInShown(offered?.shown?.phone ?: phone, server)
        ask.offer = SignInOffer(socket, shown)
        signers[socket] = pacing
        return RemoteAdmission.SignInOffered(host.socket, shown)
    }

    /**
     * The phone on [socket] sends the session for what it offered. Only that phone, only while the
     * television still asks, and only for exactly the server it was shown; then the ask is spent.
     */
    @Synchronized
    fun sendSignIn(
        socket: S,
        server: RemoteSignInServer,
        nowMs: Long,
    ): RemoteAdmission<S> {
        val signer = signers[socket] ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val host = hosts[signer.host] ?: return RemoteAdmission.Refused(RemoteRefusal.Unavailable)
        val ask = host.signIn?.takeIf { it.live(nowMs) } ?: return RemoteAdmission.Refused(RemoteRefusal.NotAsking)
        val offer = ask.offer?.takeIf { it.socket == socket } ?: return RemoteAdmission.Refused(RemoteRefusal.NotAsking)
        if (server.summary != offer.shown.server) return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        ask.sent = true
        return RemoteAdmission.SignInSent(host.socket, offer.shown.phone)
    }

    /** The television on [socket] stops asking, and names the phone that has to hear how it went. */
    @Synchronized
    fun endSignIn(socket: S): RemoteAdmission<S> {
        val host = hostOn(socket) ?: return RemoteAdmission.Refused(RemoteRefusal.WrongRole)
        val ask = host.signIn ?: return RemoteAdmission.SignInEnded(null, sent = false)
        host.signIn = null
        val phone = ask.offer?.socket?.also(signers::remove)
        return RemoteAdmission.SignInEnded(phone, ask.sent)
    }

    /** Forgets [socket] and says who has to hear that it went. */
    @Synchronized
    fun depart(socket: S): RemoteDeparture<S> {
        controllers.remove(socket)?.let { controller ->
            val host = hosts[controller.host] ?: return RemoteDeparture.None
            host.controllers.remove(socket)
            return RemoteDeparture.PhoneLeft(host.socket, host.controllers.size, controller.phone)
        }
        signers.remove(socket)?.let { signer ->
            val host = hosts[signer.host] ?: return RemoteDeparture.None
            val ask = host.signIn ?: return RemoteDeparture.None
            val offer = ask.offer?.takeIf { it.socket == socket } ?: return RemoteDeparture.None
            // Once the session went the television has it, and answers whether or not anyone hears.
            if (ask.sent) return RemoteDeparture.None
            ask.offer = null
            return RemoteDeparture.SignInWithdrawn(host.socket, offer.shown.phone)
        }
        val key = hostedBy.remove(socket) ?: return RemoteDeparture.None
        val host = hosts[key]?.takeIf { it.socket == socket } ?: return RemoteDeparture.None
        hosts.remove(key)
        host.controllers.forEach(controllers::remove)
        val signingIn = host.signIn?.offer?.socket
        signingIn?.let(signers::remove)
        return RemoteDeparture.HostLeft(host.controllers.toList(), signingIn)
    }

    /** The television whose current socket is [socket]; a replaced one hosts nothing. */
    private fun hostOn(socket: S): Host<S>? {
        val key = hostedBy[socket] ?: return null
        return hosts[key]?.takeIf { it.socket == socket }
    }

    /** Whether an ask still takes offers: not spent, and not older than [signInAskMs]. */
    private fun SignIn<S>.live(nowMs: Long): Boolean = !sent && nowMs - askedAtMs < signInAskMs
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
    if (message.type in REMOTE_SIGN_IN_MESSAGE_TYPES) return handleSignIn(socket, account, message, nowMs)
    // A room's fields, or a server to sign in to, which only 用手机登录's own messages carry.
    val strayRoomFields =
        message.roomCode != null ||
            message.clientId != null ||
            message.mediaKey != null ||
            message.signInServer != null
    when (message.type) {
        "remoteHost" -> {
            // What the television says it does; it may say nothing, as one from before did.
            val declared = message.capabilities
            if (
                strayRoomFields ||
                message.remoteSessionId != null ||
                message.remoteKey != null ||
                message.remoteDeviceId != null ||
                message.text != null ||
                (declared != null && !WatchProtocol.isValidDeclaredCapabilities(declared))
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            val asks = WatchProtocol.CAPABILITY_REMOTE_PAIRING in declared.orEmpty()
            when (val admission = host(account.userId, account.sessionId, socket, asks)) {
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
            // A phone that names itself must do so in the shape a television may keep; one that
            // names none is an older app, and gets a stand-in for this connection.
            val declared = message.remoteDeviceId
            val phoneCapabilities = message.capabilities
            if (
                target == null ||
                strayRoomFields ||
                message.remoteKey != null ||
                message.text != null ||
                (declared != null && !WatchProtocol.isStableRemoteDeviceId(declared)) ||
                (phoneCapabilities != null && !WatchProtocol.isValidDeclaredCapabilities(phoneCapabilities))
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            // The name is only shown: one the relay would not take as a name is left out, not refused.
            val name = message.name?.takeIf(WatchProtocol::isValidOptionalName)
            val holdsTokens = WatchProtocol.CAPABILITY_REMOTE_PAIRING_TOKEN in phoneCapabilities.orEmpty()
            val check =
                declared?.let {
                    pairingTokens.check(
                        remotePairingKey(account.userId, target, it),
                        message.credential?.pairingToken,
                    )
                } ?: PairingCheck.Unknown
            // An id with a token on record, claimed without it, reaches the television as an unknown
            // phone: the television asks, and a phone it lets in this way is paired afresh.
            val phone =
                when {
                    declared == null -> RemotePhone.unnamed()
                    check == PairingCheck.Mismatch -> RemotePhone.unnamed().copy(name = name)
                    else -> RemotePhone(declared, name)
                }
            when (
                val admission =
                    join(
                        userId = account.userId,
                        ownSessionId = account.sessionId,
                        targetSessionId = target,
                        socket = socket,
                        phone = phone,
                        pairingDeviceId = declared?.takeIf { holdsTokens },
                        verified = check == PairingCheck.Verified,
                    )
            ) {
                is RemoteAdmission.Joined -> {
                    socket.remoteSend(
                        WatchWireMessage(
                            type = "remoteJoined",
                            capabilities = WatchProtocol.SERVER_CAPABILITIES,
                            participantCount = admission.phones,
                            // Said before the television hears of the phone, so it cannot be let in first.
                            ready = false.takeIf { admission.waiting },
                        ),
                    )
                    if (admission.fresh) {
                        admission.host.deliver(
                            WatchWireMessage(
                                type = "remoteConnected",
                                participantCount = admission.phones,
                                remoteDeviceId = admission.phone.deviceId,
                                name = admission.phone.name,
                            ),
                        )
                    }
                }
                is RemoteAdmission.Refused -> socket.refuse(admission.reason)
                else -> Unit
            }
        }
        "remoteKey" -> {
            val key = message.remoteKey?.takeIf(WatchProtocol::isValidRemoteKey)
            if (
                key == null ||
                strayRoomFields ||
                message.remoteSessionId != null ||
                message.remoteDeviceId != null ||
                message.text != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            forward(socket, nowMs) { phone ->
                WatchWireMessage(type = "remoteKey", remoteKey = key, remoteDeviceId = phone.deviceId)
            }
        }
        "remoteText" -> {
            val text = message.text?.takeIf(WatchProtocol::isValidRemoteText)
            if (
                text == null ||
                strayRoomFields ||
                message.remoteSessionId != null ||
                message.remoteDeviceId != null ||
                message.remoteKey != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            forward(socket, nowMs) { phone ->
                WatchWireMessage(type = "remoteText", text = text, remoteDeviceId = phone.deviceId)
            }
        }
        "remoteRelease" -> {
            val deviceId = message.remoteDeviceId?.takeIf(WatchProtocol::isValidRemoteDeviceId)
            // The only reason a television may give for letting a phone go; anything else is refused.
            val refused = message.errorCode == WatchProtocol.REMOTE_REFUSED_CODE
            if (
                deviceId == null ||
                strayRoomFields ||
                message.remoteSessionId != null ||
                message.remoteKey != null ||
                message.text != null ||
                (message.errorCode != null && !refused)
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val admission = release(socket, deviceId)) {
                is RemoteAdmission.Released -> {
                    // The relay's own words: a television says only whether it refused or let go.
                    val notice =
                        if (refused) {
                            WatchWireMessage(
                                type = "remoteDisconnected",
                                message = "电视拒绝了这部手机的遥控",
                                errorCode = WatchProtocol.REMOTE_REFUSED_CODE,
                            )
                        } else {
                            WatchWireMessage(
                                type = "remoteDisconnected",
                                message = "电视已断开手机遥控",
                                errorCode = "remote_released",
                            )
                        }
                    admission.phones.forEach { phone -> phone.deliver(notice) }
                    if (admission.phones.isNotEmpty()) {
                        socket.remoteSend(
                            WatchWireMessage(
                                type = "remoteDisconnected",
                                participantCount = admission.remaining,
                                remoteDeviceId = deviceId,
                            ),
                        )
                    }
                }
                is RemoteAdmission.Refused -> socket.refuse(admission.reason)
                else -> Unit
            }
        }
        "remoteAdmit" -> {
            val deviceId = message.remoteDeviceId?.takeIf(WatchProtocol::isValidRemoteDeviceId)
            if (
                deviceId == null ||
                strayRoomFields ||
                message.remoteSessionId != null ||
                message.remoteKey != null ||
                message.text != null ||
                message.errorCode != null ||
                message.capabilities != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val admission = admit(socket, deviceId)) {
                // Only the phone let in hears it; the television already knows, and the others wait on.
                is RemoteAdmission.Admitted -> {
                    val tokens = HashMap<WebSocketSession, String>()
                    admission.grants.forEach { (key, phones) ->
                        val token = pairingTokens.issue(key)
                        phones.forEach { phone -> tokens[phone] = token }
                    }
                    admission.phones.forEach { phone ->
                        phone.deliver(
                            WatchWireMessage(
                                type = "remoteAdmitted",
                                credential = tokens[phone]?.let { WatchWireCredential(pairingToken = it) },
                            ),
                        )
                    }
                }
                is RemoteAdmission.Refused -> socket.refuse(admission.reason)
                else -> Unit
            }
        }
        // A hosting or controlling socket is not a room member: `hello` and room commands stop here.
        else -> socket.refuse(RemoteRefusal.WrongRole)
    }
}

/** Runs from the socket's cleanup; tells the other side of every pairing [socket] was part of. */
internal suspend fun RemoteControlRelay<WebSocketSession>.leave(socket: WebSocketSession) {
    when (val departure = depart(socket)) {
        is RemoteDeparture.HostLeft -> {
            departure.phones.forEach { phone ->
                phone.deliver(
                    WatchWireMessage(
                        type = "remoteDisconnected",
                        message = "电视已断开手机遥控",
                        errorCode = "remote_host_left",
                    ),
                )
            }
            departure.signingIn?.deliver(
                WatchWireMessage(type = "remoteSignInEnded", message = "电视已断开", errorCode = "remote_host_left"),
            )
        }
        is RemoteDeparture.PhoneLeft ->
            departure.host.deliver(
                WatchWireMessage(
                    type = "remoteDisconnected",
                    participantCount = departure.phones,
                    remoteDeviceId = departure.phone.deviceId,
                ),
            )
        // The television goes back to waiting for a phone; nothing it showed is coming.
        is RemoteDeparture.SignInWithdrawn ->
            departure.host.deliver(
                WatchWireMessage(type = "remoteSignInWithdrawn", remoteDeviceId = departure.phone.deviceId),
            )
        RemoteDeparture.None -> Unit
    }
}

private suspend fun RemoteControlRelay<WebSocketSession>.forward(
    socket: WebSocketSession,
    nowMs: Long,
    message: (RemotePhone) -> WatchWireMessage,
) {
    when (val admission = admitInput(socket, nowMs)) {
        is RemoteAdmission.Input ->
            if (!admission.host.deliver(message(admission.phone))) socket.refuse(RemoteRefusal.Unavailable)
        is RemoteAdmission.Refused -> socket.refuse(admission.reason)
        else -> Unit
    }
}

internal suspend fun WebSocketSession.deliver(message: WatchWireMessage): Boolean =
    withTimeoutOrNull(REMOTE_SEND_TIMEOUT_MS) { runCatching { remoteSend(message) }.isSuccess } ?: false

internal suspend fun WebSocketSession.refuse(reason: RemoteRefusal) {
    runCatching {
        remoteSend(
            WatchWireMessage(
                type = "error",
                message = reason.message,
                errorCode = reason.errorCode,
                retryable = WatchProtocol.isRetryableErrorCode(reason.errorCode),
            ),
        )
    }
}

internal suspend fun WebSocketSession.remoteSend(message: WatchWireMessage) {
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
