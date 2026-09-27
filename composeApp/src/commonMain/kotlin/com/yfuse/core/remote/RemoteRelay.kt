package com.yfuse.core.remote

import com.yfuse.watch.protocol.WatchWireMessage

/** One open 手机遥控 socket: messages out, decoded messages in. */
interface RemoteRelayChannel {
    suspend fun send(message: WatchWireMessage)

    /** The next message from the relay; null once the socket has closed. */
    suspend fun receive(): WatchWireMessage?
}

/**
 * Opens one relay socket, runs [session] on it and returns when either ends. Throws
 * `WatchAuthenticationException` when the relay closed the socket over the account itself.
 */
fun interface RemoteRelayConnector {
    suspend fun connect(
        url: String,
        accessToken: String,
        session: suspend (RemoteRelayChannel) -> Unit,
    )
}

/** The relay refused 手机遥控 for a reason another attempt will not change. */
internal class RemoteControlRefusedException(
    message: String,
    /** False when the relay does not offer 手机遥控 at all. */
    val supported: Boolean = true,
) : Exception(message)

/** What an `error` from the relay means for 手机遥控; `message_type_invalid` is a relay without it. */
internal fun WatchWireMessage.remoteRefusal(fallback: String): RemoteControlRefusedException =
    if (errorCode == "message_type_invalid") {
        RemoteControlRefusedException("服务器暂不支持手机遥控，请等待服务端更新", supported = false)
    } else {
        RemoteControlRefusedException(message?.takeIf(String::isNotBlank) ?: fallback)
    }
