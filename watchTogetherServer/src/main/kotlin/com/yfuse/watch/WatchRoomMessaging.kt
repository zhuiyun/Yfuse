package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal val watchWireJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
private const val TRAILING_SYNC_DELAY_MS = 1_000L

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
    RateLimited,
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
        // Its own revision: a room update taken before it, still on its way, is older than this.
        val revision = nextRoomRevision()
        val visibleParticipants = wireParticipants(includeClientId = clientId)
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
            participantCount = visibleParticipants.size,
            participants = visibleParticipants,
            chatHistory = chatHistory.toList(),
            playlist = playlist.toList(),
            playlistRevision = playlistRevision,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
            roomRevision = revision,
        )
    }

/** Publishes this session only once its initial snapshot and capabilities have been queued first. */
internal suspend fun WebSocketSession.sendRoomWelcome(
    room: Room,
    clientId: String,
    resumeCapability: String?,
    hostCapability: String?,
    protocolVersion: Int,
): Boolean {
    val welcome = room.welcomeMessage(clientId, resumeCapability, hostCapability, protocolVersion)
    sendMessage(welcome)
    var lastChatId = welcome.chatHistory?.maxOfOrNull { it.id } ?: -1L
    while (true) {
        val pending =
            synchronized(room) {
                val participant = room.participants[clientId]?.takeIf { it.session === this } ?: return false
                val messages = room.chatHistory.filter { it.id > lastChatId }
                if (messages.isEmpty()) {
                    participant.readyForBroadcast = true
                    // The first update catches playlist edits made while welcome was suspended.
                    participant.playlistRevisionSent = -1L
                    return true
                }
                messages
            }
        // Replay the bounded gap as normal chat messages before admitting live broadcasts.
        // Replacing history in a later snapshot could erase a newer message already received.
        for (message in pending) {
            sendMessage(WatchWireMessage(type = "chat", chat = message))
            lastChatId = maxOf(lastChatId, message.id)
        }
    }
}

internal suspend fun WebSocketSession.sendMessage(message: WatchWireMessage) {
    send(encodeWatchMessage(message))
}

internal enum class PlaybackStatusChange {
    /** Ready, buffering, media or duration changed: the room's start gate depends on it. */
    Readiness,

    /** Only latency or drift changed: informational, coalesced per [PRESENCE_BROADCAST_INTERVAL_MS]. */
    Presence,
    None,
}

internal fun CoroutineScope.schedulePresenceBroadcast(
    room: Room,
    intervalMs: Long,
    broadcastRoomUpdate: suspend (Room) -> Unit,
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

internal fun CoroutineScope.scheduleTrailingSync(room: Room) {
    val armed =
        synchronized(room) {
            if (room.trailingSyncPending) {
                false
            } else {
                room.trailingSyncPending = true
                true
            }
        }
    if (!armed) return
    launch {
        try {
            delay(TRAILING_SYNC_DELAY_MS)
        } finally {
            synchronized(room) { room.trailingSyncPending = false }
        }
        val timeline = synchronized(room) { room.timeline.takeIf { room.participants.isNotEmpty() } }
        if (timeline != null) broadcastSync(room, timeline)
    }
}

internal suspend fun WebSocketSession.sendError(
    message: String,
    errorCode: String,
    clientMessageId: String? = null,
) {
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            retryable = WatchProtocol.isRetryableErrorCode(errorCode),
            clientMessageId = clientMessageId,
        ),
    )
}

internal suspend fun WebSocketSession.finishPlaylistMutation(
    room: Room,
    result: PlaylistMutationResult,
    broadcastRoomUpdate: suspend (Room) -> Unit,
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
            PlaylistMutationResult.RateLimited ->
                "播放列表操作太频繁，请稍后再试" to "playlist_rate_limited"
            PlaylistMutationResult.Changed -> error("handled above")
        }
    val snapshot = synchronized(room) { room.playlist.toList() to room.playlistRevision }
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            retryable = WatchProtocol.isRetryableErrorCode(errorCode),
            playlist = snapshot.first,
            playlistRevision = snapshot.second,
        ),
    )
}

internal suspend fun broadcastChat(
    room: Room,
    chat: WatchWireChatMessage,
) {
    broadcastToRoom(room, WatchWireMessage(type = "chat", chat = chat))
}

internal suspend fun broadcastReaction(
    room: Room,
    clientId: String,
    name: String,
    reaction: String,
) {
    broadcastToRoom(
        room,
        WatchWireMessage(
            type = "reaction",
            clientId = clientId,
            name = name,
            reaction = reaction,
        ),
    )
}

internal suspend fun broadcastSync(
    room: Room,
    timeline: Timeline,
) {
    broadcastToRoom(
        room,
        WatchWireMessage(
            type = "sync",
            roomCode = room.code,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
        ),
    )
}
