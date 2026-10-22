package com.yfuse.watch

import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal suspend fun cleanupSocket(
    authWatchdog: Job?,
    joinedRoom: Room?,
    joinedClientId: String?,
    session: DefaultWebSocketServerSession,
    connectionLease: AutoCloseable,
    broadcastRoomUpdate: suspend (Room) -> Unit,
    scheduleHostHandover: (Room) -> Unit,
) {
    try {
        authWatchdog?.cancelAndJoin()
        val room = joinedRoom
        val clientId = joinedClientId
        if (room != null && clientId != null) {
            // Guard against a stale connection's own cleanup evicting a client that
            // has already reconnected on a new session: only the session currently
            // on record for `clientId` is allowed to remove it.
            var hostWentAbsent = false
            var announcedMemberLeft = false
            val removedNow =
                synchronized(room) {
                    val participant = room.participants[clientId]
                    val isActiveSession = participant?.session === session
                    if (isActiveSession) {
                        announcedMemberLeft = participant?.readyForBroadcast == true
                        room.participants.remove(clientId)
                        if (room.hostId == clientId) {
                            // The host keeps the room while it is away. Handing over the
                            // instant the socket dropped turned a few seconds of no
                            // signal into a permanent loss of control; a delayed
                            // handover still covers a host that genuinely doesn't return.
                            room.hostAbsentSinceMs = System.currentTimeMillis()
                            hostWentAbsent = true
                        }
                        if (room.participants.isEmpty()) {
                            room.emptySinceMs = System.currentTimeMillis()
                        }
                    }
                    isActiveSession
                }
            if (hostWentAbsent) {
                scheduleHostHandover(room)
            }
            if (removedNow &&
                announcedMemberLeft &&
                synchronized(room) { room.participants.values.any { it.readyForBroadcast } }
            ) {
                broadcastRoomUpdate(room)
            }
        }
    } finally {
        connectionLease.close()
    }
}

/**
 * Promotes the first remaining participant once a disconnected host's reconnect window
 * expires. The room is checked again after the delay because the host may have reconnected,
 * or a newer disconnect may have started a fresh grace window, while this coroutine slept.
 */
internal fun CoroutineScope.scheduleHostHandover(
    room: Room,
    graceMs: Long,
    broadcastRoomUpdate: suspend (Room) -> Unit,
) {
    launch {
        delay(graceMs)
        var grantedCapability: String? = null
        val newHost =
            synchronized(room) {
                if (
                    room.participants.containsKey(room.hostId) ||
                    !room.hostGraceExpired(graceMs)
                ) {
                    null
                } else {
                    val leftAtMs = room.hostAbsentSinceMs
                    val nextHost =
                        room.participants.values.firstOrNull { participant ->
                            room.memberships[participant.memberKey]?.predates(leftAtMs) == true
                        }
                    if (nextHost == null) {
                        null
                    } else {
                        grantedCapability = room.transferHostTo(nextHost)
                        nextHost
                    }
                }
            }
        if (newHost != null) {
            newHost.session.deliverDirect(
                WatchWireMessage(
                    type = "hostCapabilityGranted",
                    hostCapability = grantedCapability,
                ),
            )
            broadcastRoomUpdate(room)
        }
    }
}
