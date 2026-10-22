package com.yfuse.watch

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64

/**
 * A room as it survives a restart: who may come back (memberships with their account ids and
 * resume-capability digests), who hosts (the host's id, epoch and capability digest), and what
 * was playing (timeline anchor and playlist with its revision). Online sockets, chat history and
 * pacing do not survive; no capability itself is ever stored, only digests.
 */
@Serializable
internal data class PersistedRoom(
    val code: String,
    val creatorIp: String,
    val creatorAccountUserId: String,
    val hostId: String,
    val hostCapabilityDigest: String,
    val hostEpoch: Long,
    val controlMode: String,
    val moderatorKeys: List<String> = emptyList(),
    val removedAccountUserIds: List<String> = emptyList(),
    val removedMemberKeys: List<String> = emptyList(),
    val memberships: List<PersistedMembership> = emptyList(),
    val mediaKey: String,
    val anchorPositionMs: Long,
    val anchorAtServerMs: Long,
    val rate: Float,
    val paused: Boolean,
    val seq: Long,
    val playlist: List<WatchWirePlaylistEntry> = emptyList(),
    val playlistRevision: Long,
    val roomRevision: Long,
)

@Serializable
internal data class PersistedMembership(
    val clientId: String,
    val accountUserId: String,
    val resumeCapabilityDigest: String,
    val sessionGeneration: Long,
    val admittedAtMs: Long,
)

/** One 手机遥控 pairing token, as the digest a television's admission was bound to. */
internal data class PersistedPairing(
    val key: String,
    val tokenDigest: ByteArray,
    val lastUsedAtMs: Long,
)

/** Durable watch state; SQLite in production, nothing at all in most tests. */
internal interface WatchStateStore : AutoCloseable {
    /** Rooms saved at or after [savedSinceMs]; older ones are deleted rather than returned. */
    fun loadRooms(savedSinceMs: Long): List<PersistedRoom>

    fun saveRoom(
        room: PersistedRoom,
        savedAtMs: Long,
    )

    fun deleteRooms(codes: Collection<String>)

    /** Pairings used at or after [usedSinceMs]; older ones are deleted rather than returned. */
    fun loadPairings(usedSinceMs: Long): List<PersistedPairing>

    fun savePairing(pairing: PersistedPairing)
}

private val persistenceJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/**
 * Its own file (`WATCH_STATE_DB_PATH`), so the account database never takes the write load of
 * timeline anchors. WAL, like the other stores, so the nightly `.backup` can copy it online.
 */
internal class SqliteWatchStateStore private constructor(
    private val connection: Connection,
) : WatchStateStore {
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = NORMAL")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS watch_rooms (
                        code TEXT PRIMARY KEY,
                        state_json TEXT NOT NULL,
                        saved_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS remote_pairings (
                        pairing_key TEXT PRIMARY KEY,
                        token_digest BLOB NOT NULL,
                        last_used_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    override fun loadRooms(savedSinceMs: Long): List<PersistedRoom> =
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM watch_rooms WHERE saved_at_ms < ?").use { statement ->
                statement.setLong(1, savedSinceMs)
                statement.executeUpdate()
            }
            connection.prepareStatement("SELECT state_json FROM watch_rooms").use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            // A row this build cannot read is skipped, not fatal: the room is lost,
                            // the relay still starts.
                            runCatching {
                                persistenceJson.decodeFromString(PersistedRoom.serializer(), result.getString(1))
                            }.getOrNull()?.let(::add)
                        }
                    }
                }
            }
        }

    override fun saveRoom(
        room: PersistedRoom,
        savedAtMs: Long,
    ) {
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    INSERT INTO watch_rooms(code, state_json, saved_at_ms) VALUES (?, ?, ?)
                    ON CONFLICT(code) DO UPDATE SET state_json = excluded.state_json, saved_at_ms = excluded.saved_at_ms
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, room.code)
                    statement.setString(2, persistenceJson.encodeToString(PersistedRoom.serializer(), room))
                    statement.setLong(3, savedAtMs)
                    statement.executeUpdate()
                }
        }
    }

    override fun deleteRooms(codes: Collection<String>) {
        if (codes.isEmpty()) return
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM watch_rooms WHERE code = ?").use { statement ->
                codes.forEach { code ->
                    statement.setString(1, code)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    override fun loadPairings(usedSinceMs: Long): List<PersistedPairing> =
        synchronized(lock) {
            connection.prepareStatement("DELETE FROM remote_pairings WHERE last_used_at_ms < ?").use { statement ->
                statement.setLong(1, usedSinceMs)
                statement.executeUpdate()
            }
            connection
                .prepareStatement(
                    "SELECT pairing_key, token_digest, last_used_at_ms FROM remote_pairings ORDER BY last_used_at_ms",
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(PersistedPairing(result.getString(1), result.getBytes(2), result.getLong(3)))
                            }
                        }
                    }
                }
        }

    override fun savePairing(pairing: PersistedPairing) {
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    INSERT INTO remote_pairings(pairing_key, token_digest, last_used_at_ms) VALUES (?, ?, ?)
                    ON CONFLICT(pairing_key) DO UPDATE SET
                        token_digest = excluded.token_digest, last_used_at_ms = excluded.last_used_at_ms
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, pairing.key)
                    statement.setBytes(2, pairing.tokenDigest)
                    statement.setLong(3, pairing.lastUsedAtMs)
                    statement.executeUpdate()
                }
        }
    }

    override fun close() = synchronized(lock) { connection.close() }

    companion object {
        fun sqlite(file: File): SqliteWatchStateStore {
            file.absoluteFile.parentFile?.mkdirs()
            return SqliteWatchStateStore(open("jdbc:sqlite:${file.absoluteFile.path}"))
        }

        /** In-memory for tests; WAL is ignored there, which SQLite allows. */
        fun inMemory(): SqliteWatchStateStore = SqliteWatchStateStore(open("jdbc:sqlite::memory:"))

        private fun open(url: String): Connection {
            Class.forName("org.sqlite.JDBC")
            return DriverManager.getConnection(url)
        }
    }
}

private val digestEncoder = Base64.getEncoder()
private val digestDecoder = Base64.getDecoder()

/** Must run under the room lock. */
internal fun Room.toPersisted(): PersistedRoom =
    PersistedRoom(
        code = code,
        creatorIp = creatorIp,
        creatorAccountUserId = creatorAccountUserId,
        hostId = hostId,
        hostCapabilityDigest = digestEncoder.encodeToString(hostCapabilityDigest),
        hostEpoch = hostEpoch,
        controlMode = controlMode.wireValue,
        moderatorKeys = moderatorKeys.toList(),
        removedAccountUserIds = removedAccountUserIds.toList(),
        removedMemberKeys = removedMemberKeys.toList(),
        memberships =
            memberships.values.map { member ->
                PersistedMembership(
                    clientId = member.clientId,
                    accountUserId = member.accountUserId,
                    resumeCapabilityDigest = digestEncoder.encodeToString(member.resumeCapabilityDigest),
                    sessionGeneration = member.sessionGeneration,
                    admittedAtMs = member.admittedAtMs,
                )
            },
        mediaKey = timeline.mediaKey,
        anchorPositionMs = timeline.anchorPositionMs,
        anchorAtServerMs = timeline.anchorAtServerMs,
        rate = timeline.rate,
        paused = timeline.paused,
        seq = timeline.seq,
        playlist = playlist.toList(),
        playlistRevision = playlistRevision,
        roomRevision = roomRevision,
    )

/**
 * The room as the relay restarts with it: nobody online, so the usual grace periods start now —
 * the room stays for its members to reconnect, and the host's seat waits for the host before
 * a member who was already there takes it. Null for a row that no longer validates.
 */
internal fun PersistedRoom.toRoom(restoredAtMs: Long): Room? {
    if (!WatchProtocol.isValidRoomCode(code) || !WatchProtocol.isValidMediaKey(mediaKey)) return null
    if (!WatchProtocol.isValidPlaylist(playlist) || playlistRevision < 0L) return null
    val mode = ControlMode.fromWire(controlMode) ?: return null
    return runCatching {
        Room(
            code = code,
            creatorIp = creatorIp,
            creatorAccountUserId = creatorAccountUserId,
            hostId = hostId,
            hostCapabilityDigest = digestDecoder.decode(hostCapabilityDigest),
            hostEpoch = hostEpoch,
            timeline =
                Timeline(
                    mediaKey = mediaKey,
                    anchorPositionMs = anchorPositionMs,
                    anchorAtServerMs = anchorAtServerMs,
                    rate = rate,
                    paused = paused,
                    seq = seq,
                ),
            controlMode = mode,
            moderatorKeys = moderatorKeys.toCollection(linkedSetOf()),
            removedAccountUserIds = removedAccountUserIds.toCollection(linkedSetOf()),
            removedMemberKeys = removedMemberKeys.toCollection(linkedSetOf()),
            playlist = playlist.toMutableList(),
            playlistRevision = playlistRevision,
            emptySinceMs = restoredAtMs,
            hostAbsentSinceMs = restoredAtMs,
            roomRevision = roomRevision,
        ).also { room ->
            memberships.forEach { member ->
                val restored =
                    Membership(
                        clientId = member.clientId,
                        accountUserId = member.accountUserId,
                        resumeCapabilityDigest = digestDecoder.decode(member.resumeCapabilityDigest),
                        sessionGeneration = member.sessionGeneration,
                        admittedAtMs = member.admittedAtMs,
                    )
                room.memberships[restored.key] = restored
            }
        }
    }.getOrNull()
}

/**
 * Keeps [WatchStateStore] in step with the room store: rooms whose persisted form changed are
 * written, live ones are re-stamped every [resaveIntervalMs] so their age measures activity, and
 * swept ones are deleted. Runs off the socket threads, on the maintenance loop.
 */
internal class RoomStatePersister(
    private val store: WatchStateStore,
    private val restoreTtlMs: Long = ROOM_RESTORE_TTL_MS,
    private val resaveIntervalMs: Long = ROOM_RESAVE_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Saved(
        val state: PersistedRoom,
        val savedAtMs: Long,
    )

    private val saved = HashMap<String, Saved>()

    /** Loads what is recent enough to rejoin and forgets the rest. */
    @Synchronized
    fun restore(): List<Room> {
        val restoredAtMs = now()
        return store.loadRooms(savedSinceMs = restoredAtMs - restoreTtlMs).mapNotNull { persisted ->
            persisted.toRoom(restoredAtMs)?.also { saved[persisted.code] = Saved(persisted, restoredAtMs) }
        }
    }

    @Synchronized
    fun sync(
        rooms: Collection<Room>,
        everything: Boolean = false,
    ) {
        val nowMs = now()
        val current = HashMap<String, PersistedRoom>()
        rooms.forEach { room ->
            val state = synchronized(room) { room.toPersisted() to room.participants.isNotEmpty() }
            current[room.code] = state.first
            val previous = saved[room.code]
            val due =
                previous == null ||
                    previous.state != state.first ||
                    everything ||
                    (state.second && nowMs - previous.savedAtMs >= resaveIntervalMs)
            if (due) {
                store.saveRoom(state.first, nowMs)
                saved[room.code] = Saved(state.first, nowMs)
            }
        }
        val gone = saved.keys.filter { it !in current }
        if (gone.isNotEmpty()) {
            store.deleteRooms(gone)
            gone.forEach(saved::remove)
        }
    }
}

/**
 * A room survives a restart this long after it was last saved. Live rooms are re-saved every
 * [ROOM_RESAVE_INTERVAL_MS], so only rooms nobody was in for a while are dropped.
 */
internal const val ROOM_RESTORE_TTL_MS = 30 * 60_000L
internal const val ROOM_RESAVE_INTERVAL_MS = 5 * 60_000L

/** How often room state is written; a timeline anchor is at most this stale after a restart. */
internal const val ROOM_PERSIST_INTERVAL_MS = 2_000L
