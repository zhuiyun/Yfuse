package com.yfuse.core.sync

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel

/** Shared by watch and remote control: only an orderly socket end can become a null message. */
internal suspend fun receiveRelayText(
    incoming: ReceiveChannel<Frame>,
    closeReason: Deferred<CloseReason?>,
): String? {
    while (true) {
        val received = incoming.receiveCatching()
        val frame = received.getOrNull()
        if (frame == null) {
            received.exceptionOrNull()?.let { throw it }
            val closed = closeReason.await()
            if (closed?.code == CloseReason.Codes.VIOLATED_POLICY.code &&
                closed.message in WATCH_AUTH_CLOSE_REASONS
            ) {
                throw WatchAuthenticationException()
            }
            return null
        }
        if (frame is Frame.Text) return frame.readText()
    }
}
