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
