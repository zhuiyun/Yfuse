package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWireParticipant
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
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
import java.util.EnumMap

private val broadcastJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

/**
 * A member whose socket cannot take a broadcast within this window is dropped. Broadcasts
 * run on the sender's read loop, so a stalled receiver used to freeze the whole room.
 */
internal const val BROADCAST_SEND_TIMEOUT_MS = 2_000L

/** Room updates are at most this frequent; changes inside the gap ride on the next one. */
internal const val ROOM_UPDATE_MIN_INTERVAL_MS = 100L

/**
 * Per-room outbound allowance, refilled continuously. A full snapshot to a room of older clients
 * is the 64-entry playlist once per member — tens of kilobytes each — so a member toggling ready
 * in a loop could make the relay send megabytes a second. Past the allowance, room updates wait
 * (and merge) until it refills; chat, reactions and timeline syncs are paced on their own.
 */
internal const val ROOM_OUTBOUND_BURST_BYTES = 4L * 1024 * 1024
internal const val ROOM_OUTBOUND_BYTES_PER_SECOND = 1024L * 1024

/** Encodes once, stamping the relay's protocol version and clock as every outgoing message has. */
internal fun encodeWatchMessage(message: WatchWireMessage): String =
    broadcastJson.encodeToString(
        WatchWireMessage.serializer(),
        message.copy(
            protocolVersion = message.protocolVersion ?: WatchProtocol.VERSION,
            serverAtMs = WatchClock.nowMs(),
        ),
    )

/**
 * Sends an already encoded frame to one member for a broadcast, dropping the member instead of
 * waiting on a socket that will not take it. The broadcaster's read loop is what runs this, so a
 * slow receiver otherwise stalls every other member's commands behind it.
 */
internal suspend fun Participant.deliverEncoded(frame: ByteArray): Boolean {
    val delivered =
        withTimeoutOrNull(BROADCAST_SEND_TIMEOUT_MS) {
            runCatching { session.send(Frame.Text(true, frame)) }.isSuccess
        } ?: false
    if (!delivered) {
        WatchMetrics.broadcastDrops.incrementAndGet()
        ServerLog.warn("broadcast_member_dropped", "reason" to "send_timeout")
        // Cancelling the session job runs the handler's cleanup, which removes the member.
        runCatching { session.cancel(CancellationException("broadcast timed out")) }
    }
    return delivered
}

/** A direct reply to one socket that must not stall or fail the sender's own read loop. */
internal suspend fun WebSocketSession.deliverDirect(message: WatchWireMessage): Boolean {
    val frame = encodeWatchMessage(message).encodeToByteArray()
    return withTimeoutOrNull(BROADCAST_SEND_TIMEOUT_MS) {
        runCatching { send(Frame.Text(true, frame)) }.isSuccess
    } ?: false
}

/**
 * Fans frames out concurrently, bounded by [BROADCAST_SEND_TIMEOUT_MS] overall, and returns the
 * bytes put on the wire. Members that share a payload share one encoded frame.
 */
internal suspend fun broadcastFrames(
    members: List<Participant>,
    frameFor: (Participant) -> ByteArray,
): Long {
    if (members.isEmpty()) return 0L
    var bytes = 0L
    coroutineScope {
        members
            .map { member ->
                val frame = frameFor(member)
                bytes += frame.size
                async { member.deliverEncoded(frame) }
            }.awaitAll()
    }
    WatchMetrics.outboundBroadcastBytes.addAndGet(bytes)
    return bytes
}

/** One payload for every member of [room]; encoded once. */
internal suspend fun broadcastToRoom(
    room: Room,
    message: WatchWireMessage,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    if (members.isEmpty()) return
    val frame = encodeWatchMessage(message).encodeToByteArray()
    val bytes = broadcastFrames(members) { frame }
    synchronized(room) { room.outbound.spend(bytes, monotonicMs()) }
}

/** A token bucket that may run into debt: a snapshot's size is only known once it is built. */
internal class RoomOutboundBudget(
    private val burstBytes: Long = ROOM_OUTBOUND_BURST_BYTES,
    private val bytesPerSecond: Long = ROOM_OUTBOUND_BYTES_PER_SECOND,
) {
    private var available = burstBytes
    private var updatedAtMs: Long? = null

    init {
        require(burstBytes > 0L && bytesPerSecond > 0L)
    }

    /** How long a room update has to wait for the allowance to be positive again. */
    fun waitMs(nowMs: Long): Long {
        refill(nowMs)
        return if (available > 0L) 0L else (-available * 1_000L) / bytesPerSecond + 1L
    }

    fun spend(
        bytes: Long,
        nowMs: Long,
    ) {
        refill(nowMs)
        available -= bytes
    }

    private fun refill(nowMs: Long) {
        val last = updatedAtMs
        if (last != null && nowMs > last) {
            available = minOf(burstBytes, available + (nowMs - last) * bytesPerSecond / 1_000L)
        }
        if (last == null || nowMs > last) updatedAtMs = nowMs
    }
}

/** What one member's `roomUpdate` depends on; members that agree share one encoded frame. */
private data class RoomUpdateVariant(
    val isHost: Boolean,
    val canControl: Boolean,
    val withPlaylist: Boolean,
)

private class RoomUpdateSnapshot(
    val revision: Long,
    val variants: Map<Participant, RoomUpdateVariant>,
    val participants: List<WatchWireParticipant>,
    val timeline: Timeline,
    val controlMode: ControlMode,
    val playlist: List<WatchWirePlaylistEntry>,
    val playlistRevision: Long,
)

/**
 * Room updates for every room, one flush at a time per room.
 *
 * Every membership, host, permission, readiness or playlist change used to broadcast a full
 * snapshot on its own, concurrently with any other: a burst of changes became a burst of
 * snapshots, and two of them could reach a member in the opposite order to the one they were
 * taken in. Now a change marks the room dirty. The caller flushes straight away when no flush is
 * running and the room's pace allows it; otherwise the running flush, or one scheduled for when
 * the pace allows, picks the change up. Snapshots carry a [Room.roomRevision] in the order they
 * were taken, so a client can still discard one that arrives late.
 */
internal class RoomUpdateBroadcaster(
    private val scope: CoroutineScope,
    private val minIntervalMs: Long = ROOM_UPDATE_MIN_INTERVAL_MS,
    private val now: () -> Long = ::monotonicMs,
) {
    suspend fun request(room: Room) {
        val flushNow =
            synchronized(room) {
                room.updateDirty = true
                if (room.updateFlushing || room.updateFlushScheduled) return@synchronized false
                val waitMs = room.updateWaitMs(now())
                if (waitMs > 0L) {
                    scheduleLocked(room, waitMs)
                    false
                } else {
                    room.updateFlushing = true
                    true
                }
            }
        if (flushNow) flush(room)
    }

    private fun scheduleLocked(
        room: Room,
        waitMs: Long,
    ) {
        room.updateFlushScheduled = true
        WatchMetrics.roomUpdatesDeferred.incrementAndGet()
        scope.launch {
            delay(waitMs)
            val flushNow =
                synchronized(room) {
                    room.updateFlushScheduled = false
                    if (room.updateFlushing || !room.updateDirty) {
                        false
                    } else {
                        room.updateFlushing = true
                        true
                    }
                }
            if (flushNow) flush(room)
        }
    }

    private suspend fun flush(room: Room) {
        try {
            while (true) {
                val snapshot =
                    synchronized(room) {
                        val nowMs = now()
                        val waitMs = room.updateWaitMs(nowMs)
                        if (!room.updateDirty || waitMs > 0L) {
                            room.updateFlushing = false
                            if (room.updateDirty && !room.updateFlushScheduled) scheduleLocked(room, waitMs)
                            return
                        }
                        room.updateDirty = false
                        room.lastUpdateFlushAtMs = nowMs
                        room.takeUpdateSnapshot()
                    }
                val bytes = deliver(room, snapshot)
                synchronized(room) { room.outbound.spend(bytes, now()) }
            }
        } catch (failure: Throwable) {
            synchronized(room) {
                room.updateFlushing = false
                if (room.updateDirty && !room.updateFlushScheduled) scheduleLocked(room, minIntervalMs)
            }
            throw failure
        }
    }

    private fun Room.updateWaitMs(nowMs: Long): Long {
        val sinceLast = lastUpdateFlushAtMs?.let { minIntervalMs - (nowMs - it) } ?: 0L
        return maxOf(sinceLast, outbound.waitMs(nowMs), 0L)
    }

    private suspend fun deliver(
        room: Room,
        snapshot: RoomUpdateSnapshot,
    ): Long {
        val frames =
            snapshot.variants.values.toSet().associateWith { variant ->
                encodeWatchMessage(
                    WatchWireMessage(
                        type = "roomUpdate",
                        roomCode = room.code,
                        isHost = variant.isHost,
                        canControl = variant.canControl,
                        controlMode = snapshot.controlMode.wireValue,
                        participantCount = snapshot.variants.size,
                        participants = snapshot.participants,
                        playlist = snapshot.playlist.takeIf { variant.withPlaylist },
                        playlistRevision = snapshot.playlistRevision.takeIf { variant.withPlaylist },
                        mediaKey = snapshot.timeline.mediaKey,
                        positionMs = snapshot.timeline.anchorPositionMs,
                        paused = snapshot.timeline.paused,
                        rate = snapshot.timeline.rate,
                        seq = snapshot.timeline.seq,
                        anchorAtMs = snapshot.timeline.anchorAtServerMs,
                        roomRevision = snapshot.revision,
                    ),
                ).encodeToByteArray()
            }
        WatchMetrics.roomUpdatesSent.incrementAndGet()
        return broadcastFrames(
            snapshot.variants.keys.toList(),
        ) { member -> frames.getValue(snapshot.variants.getValue(member)) }
    }

    /** Must run under the room lock: it assigns the revision and records what each member was sent. */
    private fun Room.takeUpdateSnapshot(): RoomUpdateSnapshot {
        val revision = nextRoomRevision()
        val variants =
            participants.values.associateWith { member ->
                // Older clients expect the playlist in every snapshot; newer ones only when it moved.
                val withPlaylist = !member.roomDeltas || member.playlistRevisionSent != playlistRevision
                member.playlistRevisionSent = playlistRevision
                RoomUpdateVariant(
                    isHost = member.id == hostId,
                    canControl = canControl(member),
                    withPlaylist = withPlaylist,
                )
            }
        return RoomUpdateSnapshot(
            revision = revision,
            variants = variants,
            participants = wireParticipants(),
            timeline = timeline,
            controlMode = controlMode,
            playlist = playlist.toList(),
            playlistRevision = playlistRevision,
        )
    }
}

/** Must run under the room lock. */
internal fun Room.wireParticipants(): List<WatchWireParticipant> =
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
            isModerator = isModerator(participant),
        )
    }

/**
 * Message kinds paced per connection on top of the overall frame budget. Each one makes the relay
 * broadcast to the whole room, so its pace — not just the sender's frame count — sets the cost.
 */
internal enum class PacedAction(
    val maxPerWindow: Int,
    val windowMs: Long,
) {
    /** Host timeline anchors; scrubbing makes bursts, so past this they merge instead of failing. */
    Sync(40, 10_000L),

    /** Ready, buffering, media and duration changes; past this they ride the presence update. */
    Readiness(20, 10_000L),

    /** Control mode, moderators, grants and kicks. */
    Control(10, 10_000L),

    Playlist(20, 10_000L),
}

/** Sliding windows per [PacedAction], for one connection's read loop. */
internal class WatchMessagePacer(
    private val now: () -> Long = ::monotonicMs,
) {
    private val recent = EnumMap<PacedAction, ArrayDeque<Long>>(PacedAction::class.java)

    fun admit(action: PacedAction): Boolean {
        val nowMs = now()
        val times = recent.getOrPut(action) { ArrayDeque() }
        while (times.isNotEmpty() && nowMs - times.first() >= action.windowMs) times.removeFirst()
        if (times.size >= action.maxPerWindow) return false
        times.addLast(nowMs)
        return true
    }
}
