package com.yfuse.watch

import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.websocket.WebSocketSession

/** 用手机登录's own messages; [handle] passes each of them here. */
internal val REMOTE_SIGN_IN_MESSAGE_TYPES =
    setOf("remoteSignInAsk", "remoteSignInEnd", "remoteSignInOffer", "remoteSignInSend")

/**
 * One 用手机登录 message from [socket]'s read loop, for the account that socket authenticated as.
 * Every field a message may not carry is refused, as for the rest of 手机遥控, and the server a
 * phone sends is never logged: the relay passes it on and keeps only what the television showed.
 */
internal suspend fun RemoteControlRelay<WebSocketSession>.handleSignIn(
    socket: WebSocketSession,
    account: AuthenticatedAccount,
    message: WatchWireMessage,
    nowMs: Long,
) {
    val stray =
        message.roomCode != null ||
            message.clientId != null ||
            message.mediaKey != null ||
            message.remoteKey != null ||
            message.text != null ||
            message.capabilities != null
    when (message.type) {
        "remoteSignInAsk" -> {
            if (
                stray ||
                message.remoteSessionId != null ||
                message.remoteDeviceId != null ||
                message.errorCode != null ||
                message.signInServer != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val step = askSignIn(socket, nowMs)) {
                is RemoteAdmission.SignInAsked -> {
                    // The television never said how an earlier one went: to its phone, it did not.
                    step.dropped?.deliver(signInEnded(step.droppedSent, WatchProtocol.REMOTE_SIGN_IN_FAILED_CODE))
                    // A television that reconnected is shown again what a phone had put before it.
                    step.shown?.let { shown -> socket.remoteSend(signInOffer(shown)) }
                }
                is RemoteAdmission.Refused -> socket.refuse(step.reason)
                else -> Unit
            }
        }
        "remoteSignInEnd" -> {
            // The only reasons a television may give; anything else is refused.
            val code = message.errorCode
            val known =
                code == null ||
                    code == WatchProtocol.REMOTE_SIGN_IN_CANCELLED_CODE ||
                    code == WatchProtocol.REMOTE_SIGN_IN_FAILED_CODE
            if (
                !known ||
                stray ||
                message.remoteSessionId != null ||
                message.remoteDeviceId != null ||
                message.signInServer != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val step = endSignIn(socket)) {
                is RemoteAdmission.SignInEnded -> step.phone?.deliver(signInEnded(step.sent, code))
                is RemoteAdmission.Refused -> socket.refuse(step.reason)
                else -> Unit
            }
        }
        "remoteSignInOffer" -> offer(socket, account, message, stray, nowMs)
        "remoteSignInSend" -> {
            val server = message.signInServer?.takeIf(WatchProtocol::isValidRemoteSignInCredentials)
            if (
                server == null ||
                stray ||
                message.remoteSessionId != null ||
                message.remoteDeviceId != null ||
                message.errorCode != null
            ) {
                return socket.refuse(RemoteRefusal.WrongRole)
            }
            when (val step = sendSignIn(socket, server, nowMs)) {
                is RemoteAdmission.SignInSent -> {
                    val sent =
                        WatchWireMessage(
                            type = "remoteSignInSend",
                            remoteDeviceId = step.phone.deviceId,
                            signInServer = server,
                        )
                    if (!step.host.deliver(sent)) socket.refuse(RemoteRefusal.Unavailable)
                }
                is RemoteAdmission.Refused -> socket.refuse(step.reason)
                else -> Unit
            }
        }
    }
}

private suspend fun RemoteControlRelay<WebSocketSession>.offer(
    socket: WebSocketSession,
    account: AuthenticatedAccount,
    message: WatchWireMessage,
    stray: Boolean,
    nowMs: Long,
) {
    val target = message.remoteSessionId?.takeIf(WatchProtocol::isValidRemoteSessionId)
    val server = message.signInServer?.takeIf(WatchProtocol::isValidRemoteSignInOffer)
    // As on remoteJoin: a phone that names itself does so in a shape a television may keep.
    val declared = message.remoteDeviceId
    if (
        target == null ||
        server == null ||
        stray ||
        message.errorCode != null ||
        (declared != null && !WatchProtocol.isStableRemoteDeviceId(declared))
    ) {
        return socket.refuse(RemoteRefusal.WrongRole)
    }
    val phone =
        declared?.let { RemotePhone(it, message.name?.takeIf(WatchProtocol::isValidOptionalName)) }
            ?: RemotePhone.unnamed()
    when (val step = offerSignIn(account.userId, account.sessionId, target, socket, phone, server, nowMs)) {
        is RemoteAdmission.SignInOffered -> {
            if (!step.host.deliver(signInOffer(step.shown))) return socket.refuse(RemoteRefusal.Unavailable)
            socket.remoteSend(
                WatchWireMessage(type = "remoteSignInOffered", capabilities = WatchProtocol.SERVER_CAPABILITIES),
            )
        }
        is RemoteAdmission.Refused -> socket.refuse(step.reason)
        else -> Unit
    }
}

/** What a television is shown: which phone offers which server, with no session in it. */
private fun signInOffer(shown: SignInShown): WatchWireMessage =
    WatchWireMessage(
        type = "remoteSignInOffer",
        remoteDeviceId = shown.phone.deviceId,
        name = shown.phone.name,
        signInServer = shown.server,
    )

/**
 * How a phone hears its television stopped asking, in the relay's own words. A television says only
 * whether it saved what it was sent; one that stopped before anything was sent cancelled.
 */
internal fun signInEnded(
    sent: Boolean,
    errorCode: String?,
): WatchWireMessage =
    when {
        !sent ->
            WatchWireMessage(
                type = "remoteSignInEnded",
                message = "电视已取消用手机登录",
                errorCode = WatchProtocol.REMOTE_SIGN_IN_CANCELLED_CODE,
            )
        errorCode == null -> WatchWireMessage(type = "remoteSignInEnded", message = "电视已登录这台服务器")
        else ->
            WatchWireMessage(
                type = "remoteSignInEnded",
                message = "电视未能用这份登录连接服务器",
                errorCode = WatchProtocol.REMOTE_SIGN_IN_FAILED_CODE,
            )
    }
