package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWireParticipant
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Wire protocol v6, wire-compatible with authenticated v5 for a rolling server-first upgrade.
 * The DTO and its validation limits live in `:watchTogetherProtocol`, so client and relay cannot
 * silently drift. Version 5 requires an authenticated Yfuse account for
 * every room connection and binds membership to its immutable account id. Room-scoped resume and
 * host capabilities continue to protect reconnection and host authority; public client ids never
 * authenticate either operation.
 *
 * The core change from v1 is that the server is the **timeline authority**: instead of the
 * host broadcasting its position once a second and guests correcting toward a moving
 * target, the host tells the room "here is the new anchor" only when something actually
 * changes (play/pause/seek/rate/media), and everyone else extrapolates
 * `anchorPositionMs + (serverNow - anchorAtMs) * rate` locally between anchors. This is both
 * cheaper (near-zero traffic while steady-state playing) and more precise (no 1-second
 * quantization) — see [Timeline].
 *
 * [serverAtMs] is stamped on every outgoing message for diagnostics and future extensions.
 * Clock samples use `pong` specifically, because only it echoes the correlation id needed
 * to measure round-trip latency with the client's monotonic clock.
 */
internal val watchWireJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

/**
 * A member whose socket cannot take a broadcast within this window is dropped. Broadcasts
 * run on the sender's read loop, so a stalled receiver used to freeze the whole room.
 */
private const val BROADCAST_SEND_TIMEOUT_MS = 2_000L

internal enum class PlaylistMutationResult {
    Changed,
    Forbidden,
    Stale,
    Full,
    Duplicate,
    NotFound,
    IndexInvalid,
    Unchanged,
    RevisionExhausted,
}

internal fun Room.mutatePlaylist(
    clientId: String,
    session: WebSocketSession,
    expectedRevision: Long,
    mutation: (MutableList<WatchWirePlaylistEntry>) -> PlaylistMutationResult,
): PlaylistMutationResult =
    synchronized(this) {
        val actor =
            participants[clientId]
                ?.takeIf { it.session === session }
                ?: return@synchronized PlaylistMutationResult.Forbidden
        if (!canEditPlaylist(actor)) return@synchronized PlaylistMutationResult.Forbidden
        if (playlistRevision != expectedRevision) return@synchronized PlaylistMutationResult.Stale
        if (playlistRevision == Long.MAX_VALUE) {
            return@synchronized PlaylistMutationResult.RevisionExhausted
        }
        mutation(playlist).also { result ->
            if (result == PlaylistMutationResult.Changed) playlistRevision++
        }
    }

internal fun Room.welcomeMessage(
    clientId: String,
    resumeCapability: String?,
    hostCapability: String?,
    protocolVersion: Int,
): WatchWireMessage =
    synchronized(this) {
        WatchWireMessage(
            type = "welcome",
            protocolVersion = protocolVersion,
            capabilities = WatchProtocol.SERVER_CAPABILITIES,
            roomCode = code,
            resumeCapability = resumeCapability,
            hostCapability = hostCapability,
            isHost = hostId == clientId,
            canControl = participants[clientId]?.let(::canControl) ?: false,
            controlMode = controlMode.wireValue,
            participantCount = participants.size,
            participants = wireParticipants(),
            chatHistory = chatHistory.toList(),
            playlist = playlist.toList(),
            playlistRevision = playlistRevision,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
        )
    }

internal suspend fun WebSocketSession.sendMessage(message: WatchWireMessage) {
    send(
        watchWireJson.encodeToString(
            WatchWireMessage.serializer(),
            message.copy(
                protocolVersion = message.protocolVersion ?: WatchProtocol.VERSION,
                serverAtMs = System.currentTimeMillis(),
            ),
        ),
    )
}

/** Which part of a `playbackStatus` report changed, and therefore how urgently it is fanned out. */
internal enum class PlaybackStatusChange {
    /** Ready, buffering, media or duration changed: the room's start gate depends on it. */
    Readiness,

    /** Only latency or drift changed: informational, coalesced per [PRESENCE_BROADCAST_INTERVAL_MS]. */
    Presence,
    None,
}

/**
 * Sends [message] to one member for a broadcast, dropping the member instead of waiting on
 * a socket that will not take it. The broadcaster's read loop is what runs this, so a slow
 * receiver otherwise stalls every other member's commands behind it.
 */
private suspend fun Participant.deliverBroadcast(message: WatchWireMessage): Boolean {
    val delivered =
        withTimeoutOrNull(BROADCAST_SEND_TIMEOUT_MS) {
            runCatching { session.sendMessage(message) }.isSuccess
        } ?: false
    if (!delivered) {
        WatchMetrics.broadcastDrops.incrementAndGet()
        ServerLog.warn("broadcast_member_dropped", "reason" to "send_timeout")
        // Cancelling the session job runs the handler's cleanup, which removes the member.
        runCatching { session.cancel(CancellationException("broadcast timed out")) }
    }
    return delivered
}

/** Fans one payload per member out concurrently, bounded by [BROADCAST_SEND_TIMEOUT_MS] overall. */
private suspend fun broadcastTo(
    members: List<Participant>,
    payloadFor: (Participant) -> WatchWireMessage,
) {
    if (members.isEmpty()) return
    coroutineScope {
        members
            .map { member -> async { member.deliverBroadcast(payloadFor(member)) } }
            .awaitAll()
    }
}

/**
 * Coalesces latency/drift-only updates: the first report arms one delayed room update, and
 * every further report inside the window rides on it.
 */
internal fun CoroutineScope.schedulePresenceBroadcast(
    room: Room,
    intervalMs: Long,
) {
    val armed =
        synchronized(room) {
            if (room.presenceBroadcastPending) {
                false
            } else {
                room.presenceBroadcastPending = true
                true
            }
        }
    if (!armed) return
    launch {
        try {
            delay(intervalMs)
        } finally {
            synchronized(room) { room.presenceBroadcastPending = false }
        }
        val stillPopulated = synchronized(room) { room.participants.isNotEmpty() }
        if (stillPopulated) broadcastRoomUpdate(room)
    }
}

internal suspend fun WebSocketSession.sendError(
    message: String,
    errorCode: String? = null,
    clientMessageId: String? = null,
) {
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            clientMessageId = clientMessageId,
        ),
    )
}

internal suspend fun WebSocketSession.finishPlaylistMutation(
    room: Room,
    result: PlaylistMutationResult,
) {
    if (result == PlaylistMutationResult.Changed) {
        broadcastRoomUpdate(room)
        return
    }
    val (message, errorCode) =
        when (result) {
            PlaylistMutationResult.Forbidden ->
                "仅主持人和管理员可以编辑播放列表" to "playlist_forbidden"
            PlaylistMutationResult.Stale ->
                "播放列表已更新，请基于最新版本重试" to "playlist_stale"
            PlaylistMutationResult.Full -> "播放列表已满" to "playlist_full"
            PlaylistMutationResult.Duplicate -> "播放列表项目已存在" to "playlist_duplicate"
            PlaylistMutationResult.NotFound -> "播放列表项目不存在" to "playlist_not_found"
            PlaylistMutationResult.IndexInvalid -> "播放列表位置无效" to "playlist_index_invalid"
            PlaylistMutationResult.Unchanged -> "播放列表没有变化" to "playlist_unchanged"
            PlaylistMutationResult.RevisionExhausted ->
                "播放列表版本已耗尽" to "playlist_revision_exhausted"
            PlaylistMutationResult.Changed -> error("handled above")
        }
    val snapshot = synchronized(room) { room.playlist.toList() to room.playlistRevision }
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            playlist = snapshot.first,
            playlistRevision = snapshot.second,
        ),
    )
}

/** Membership or host changed; every member gets the current timeline too so a client that missed a `sync` can resync from this alone. */
internal suspend fun broadcastRoomUpdate(room: Room) {
    val snapshot =
        synchronized(room) {
            RoomBroadcastSnapshot(
                members = room.participants.values.toList(),
                participants = room.wireParticipants(),
                timeline = room.timeline,
                hostId = room.hostId,
                controlMode = room.controlMode,
                playlist = room.playlist.toList(),
                playlistRevision = room.playlistRevision,
                canControlIds =
                    room.participants.keys
                        .filterTo(linkedSetOf()) { id ->
                            room.participants[id]?.let(room::canControl) == true
                        },
            )
        }
    broadcastTo(snapshot.members) { member ->
        WatchWireMessage(
            type = "roomUpdate",
            roomCode = room.code,
            isHost = member.id == snapshot.hostId,
            canControl = member.id in snapshot.canControlIds,
            controlMode = snapshot.controlMode.wireValue,
            participantCount = snapshot.members.size,
            participants = snapshot.participants,
            playlist = snapshot.playlist,
            playlistRevision = snapshot.playlistRevision,
            mediaKey = snapshot.timeline.mediaKey,
            positionMs = snapshot.timeline.anchorPositionMs,
            paused = snapshot.timeline.paused,
            rate = snapshot.timeline.rate,
            seq = snapshot.timeline.seq,
            anchorAtMs = snapshot.timeline.anchorAtServerMs,
        )
    }
}

private data class RoomBroadcastSnapshot(
    val members: List<Participant>,
    val participants: List<WatchWireParticipant>,
    val timeline: Timeline,
    val hostId: String,
    val controlMode: ControlMode,
    val playlist: List<WatchWirePlaylistEntry>,
    val playlistRevision: Long,
    val canControlIds: Set<String>,
)

private fun Room.wireParticipants(): List<WatchWireParticipant> =
    participants.values.map { participant ->
        WatchWireParticipant(
            clientId = participant.id,
            name = participant.name,
            avatarId = participant.avatarId,
            isHost = participant.id == hostId,
            statusKnown = participant.statusKnown,
            ready = participant.ready,
            buffering = participant.buffering,
            mediaAvailable = participant.mediaAvailable,
            latencyMs = participant.latencyMs,
            syncDriftMs = participant.syncDriftMs,
            durationMs = participant.durationMs,
            canControl = canControl(participant),
            isModerator = participant.id in moderatorIds,
        )
    }

internal suspend fun broadcastChat(
    room: Room,
    chat: WatchWireChatMessage,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    val payload = WatchWireMessage(type = "chat", chat = chat)
    broadcastTo(members) { payload }
}

/**
 * Reactions are not kept anywhere: no history, no replay on join. Someone who was not in
 * the room when it happened has missed it, which is the whole idea.
 */
internal suspend fun broadcastReaction(
    room: Room,
    clientId: String,
    name: String,
    reaction: String,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    val payload =
        WatchWireMessage(
            type = "reaction",
            clientId = clientId,
            name = name,
            reaction = reaction,
        )
    broadcastTo(members) { payload }
}

internal suspend fun broadcastSync(
    room: Room,
    timeline: Timeline,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    val payload =
        WatchWireMessage(
            type = "sync",
            roomCode = room.code,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
        )
    broadcastTo(members) { payload }
}
