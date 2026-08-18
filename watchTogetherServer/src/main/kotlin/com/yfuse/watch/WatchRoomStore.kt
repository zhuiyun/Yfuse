package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

internal sealed interface RoomCreationResult {
    data class Created(
        val room: Room,
    ) : RoomCreationResult

    data object IpLimitReached : RoomCreationResult

    data object ServiceFull : RoomCreationResult
}

/** Atomic room index and creation quota owner. */
internal class RoomStore(
    private val roomGraceMs: Long,
    private val maxActiveRoomsPerIp: Int,
    private val roomCodeRandom: SecureRandom = SecureRandom(),
) {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val creationLock = Any()

    fun find(code: String): Room? = rooms[code]

    fun mutateIfCurrent(
        room: Room,
        block: (Room) -> Unit,
    ): Boolean =
        synchronized(creationLock) {
            if (rooms[room.code] !== room) return@synchronized false
            synchronized(room) { block(room) }
            true
        }

    fun createRoom(
        mediaKey: String,
        hostId: String,
        creatorIp: String,
        initialPlaylist: List<WatchWirePlaylistEntry>,
    ): RoomCreationResult =
        synchronized(creationLock) {
            sweepExpiredRoomsLocked(System.currentTimeMillis())
            if (rooms.size >= MAX_ROOMS) return@synchronized RoomCreationResult.ServiceFull
            if (rooms.values.count { it.creatorIp == creatorIp } >= maxActiveRoomsPerIp) {
                return@synchronized RoomCreationResult.IpLimitReached
            }
            while (true) {
                val code =
                    buildString(WatchProtocol.ROOM_CODE_LENGTH) {
                        repeat(WatchProtocol.ROOM_CODE_LENGTH) {
                            append(
                                WatchProtocol.ROOM_CODE_ALPHABET[
                                    roomCodeRandom.nextInt(WatchProtocol.ROOM_CODE_ALPHABET.length),
                                ],
                            )
                        }
                    }
                val hostCredential = newCapability()
                val room =
                    Room(
                        code = code,
                        creatorIp = creatorIp,
                        hostId = hostId,
                        hostCapabilityDigest =
                            capabilityDigest(code, hostId, CapabilityKind.Host, hostCredential),
                        initialHostCapability = hostCredential,
                        timeline =
                            Timeline(
                                mediaKey = mediaKey,
                                anchorPositionMs = 0L,
                                anchorAtServerMs = System.currentTimeMillis(),
                            ),
                        playlist = initialPlaylist.toMutableList(),
                    )
                if (rooms.putIfAbsent(code, room) == null) {
                    return@synchronized RoomCreationResult.Created(room)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            RoomCreationResult.ServiceFull
        }

    fun sweepExpiredRooms(nowMs: Long = System.currentTimeMillis()) {
        synchronized(creationLock) {
            sweepExpiredRoomsLocked(nowMs)
        }
    }

    private fun sweepExpiredRoomsLocked(nowMs: Long) {
        rooms.values.forEach { room ->
            synchronized(room) {
                val since = room.emptySinceMs
                if (since != null && nowMs - since >= roomGraceMs) {
                    rooms.remove(room.code, room)
                }
            }
        }
    }
}
