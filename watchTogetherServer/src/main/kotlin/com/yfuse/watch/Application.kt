package com.yfuse.watch

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.http.content.staticFiles
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol v2 — one flat, nullable-field shape shared by every message type, mirrored
 * by hand in [com.yfuse.core.sync.WatchTogetherClient] (no shared module between this JVM
 * backend and the KMP client). No v1 compatibility is kept: this is a single-deployment
 * personal service, so the server and app ship together rather than carrying two protocols.
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
@Serializable
private data class WireMessage(
    val type: String,
    // hello / welcome / roomUpdate
    val clientId: String? = null,
    val name: String? = null,
    val avatarId: Int? = null,
    val roomCode: String? = null,
    val isHost: Boolean? = null,
    val participantCount: Int? = null,
    val participants: List<WireParticipant>? = null,
    // timeline snapshot — present on welcome / roomUpdate / sync
    val mediaKey: String? = null,
    val positionMs: Long? = null,
    val paused: Boolean? = null,
    val rate: Float? = null,
    val seq: Long? = null,
    val anchorAtMs: Long? = null,
    // clock sync — stamped on every server -> client message; echoed on pong
    val serverAtMs: Long? = null,
    val clientSentAtMs: Long? = null,
    // control handoff — who a grant/deny is aimed at
    val targetClientId: String? = null,
    // room chat
    val text: String? = null,
    val chat: WireChatMessage? = null,
    val chatHistory: List<WireChatMessage>? = null,
    // error
    val message: String? = null,
    val errorCode: String? = null,
)

@Serializable
private data class WireParticipant(
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val isHost: Boolean,
)

@Serializable
private data class WireChatMessage(
    val id: Long,
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val text: String,
    val sentAtMs: Long,
)

/**
 * The room's shared understanding of "what's playing and where". Replaced wholesale (not
 * mutated in place) on every host action, so a reference to one [Timeline] is always a
 * consistent, immutable snapshot — no torn reads across its fields.
 *
 * Position is never itself a live number: [anchorPositionMs] is only valid *at*
 * [anchorAtServerMs]. Anyone who wants "the position right now" computes
 * `anchorPositionMs + (serverNow - anchorAtServerMs) * rate` when [paused] is false, or just
 * `anchorPositionMs` when paused.
 */
@Serializable
private data class Timeline(
    val mediaKey: String,
    val anchorPositionMs: Long,
    val anchorAtServerMs: Long,
    val rate: Float = 1f,
    val paused: Boolean = true,
    val seq: Long = 0L,
)

private class Participant(
    val id: String,
    val name: String,
    val avatarId: Int,
    val session: WebSocketSession,
)

/**
 * All mutable fields are only ever read or written from inside `synchronized(this)` —
 * matching the discipline the v1 server already used. [hostId] is a client id, not a
 * session, so replacing a stale session for the same id (reconnect) never disturbs who is
 * host: the room's notion of "who's in charge" survives a network blip untouched.
 */
private class Room(
    val code: String,
    var hostId: String,
    var timeline: Timeline,
    val participants: LinkedHashMap<String, Participant> = linkedMapOf(),
    /** Recent text only; discarded with the room and bounded independently of frame size. */
    val chatHistory: ArrayDeque<WireChatMessage> = ArrayDeque(),
    var nextChatId: Long = 0L,
    /** Null while occupied; set to the moment the last participant left. */
    var emptySinceMs: Long? = null,
    /**
     * Null while the host is connected; set to the moment it dropped. For as long as this is
     * inside [HOST_GRACE_MS], [hostId] keeps pointing at the absent host and nobody else may
     * claim the slot — see [HOST_GRACE_MS] for why.
     */
    var hostAbsentSinceMs: Long? = null,
) {
    /** True once an absent host has been gone long enough to lose the room. */
    fun hostGraceExpired(graceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val since = hostAbsentSinceMs ?: return true
        return nowMs - since >= graceMs
    }
}

private val rooms = ConcurrentHashMap<String, Room>()
/** Serializes the size check with insertion so [MAX_ROOMS] remains a hard cap under bursts. */
private val roomCreationLock = Any()
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private const val ROOM_CODE_LENGTH = 6
private const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/**
 * How long an emptied room is kept around for its last occupants to reconnect into before
 * it's swept. Without this, a host's brief network drop (the single most common failure
 * mode on mobile data) destroyed the room instantly — the whole party had to re-share and
 * re-enter a fresh code to keep watching.
 */
private const val ROOM_GRACE_MS = 5 * 60_000L

/**
 * How long a disconnected host keeps the room before control passes to whoever is still in it.
 *
 * Handing over the instant the socket dropped made the single most common failure mode on
 * mobile — a few seconds of no signal — permanently take the room away from the person who
 * started it, with no way to get it back while anyone else remained connected. The window is
 * short enough that a host who has genuinely walked away doesn't strand the room.
 */
private const val HOST_GRACE_MS = 20_000L

/**
 * Caps, so one client can't exhaust a small shared box. All three are far above anything a
 * real watch-along does; they exist to bound the damage from a loop or a scanner, not to
 * ration normal use.
 */
private const val MAX_ROOMS = 500
private const val MAX_PARTICIPANTS_PER_ROOM = 12
private const val MAX_MESSAGES_PER_WINDOW = 240
private const val RATE_WINDOW_MS = 10_000L
private const val AVATAR_COUNT = 8
private const val MAX_NAME_BYTES = 128
private const val MAX_CHAT_GRAPHEMES = 30
private const val MAX_CHAT_BYTES = 768
private const val MAX_CHAT_HISTORY = 50
private const val MAX_CHAT_MESSAGES_PER_WINDOW = 3
private const val CHAT_RATE_WINDOW_MS = 3_000L
private const val PROFILE_UPDATE_COOLDOWN_MS = 1_000L
private val graphemeRegex = Regex("\\X")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(CIO, host = "0.0.0.0", port = port) {
        watchTogetherModule()
    }.start(wait = true)
}

fun Application.watchTogetherModule(
    updateRoot: File = File(System.getenv("UPDATE_ROOT") ?: "/srv/yfuse-update/yfuse"),
    /** Injectable so tests can exercise the handover without waiting out the real window. */
    hostGraceMs: Long = HOST_GRACE_MS,
) {
    // Outlives any one socket, which is what a delayed host handover needs: the connection
    // whose loss starts the clock is precisely the one that can't run the timer.
    val appScope: CoroutineScope = this
    install(WebSockets) {
        pingPeriodMillis = 20_000L
        timeoutMillis = 40_000L
        maxFrameSize = 64 * 1024L
        masking = false
    }
    routing {
        get("/health") {
            call.respondText("ok")
        }
        staticFiles("/yfuse", updateRoot)
        webSocket("/watch") {
            var joinedRoom: Room? = null
            var joinedClientId: String? = null
            var windowStartedAtMs = System.currentTimeMillis()
            var messagesInWindow = 0
            val recentChatAtMs = ArrayDeque<Long>()
            var lastProfileUpdateAtMs = 0L
            try {
                incoming.consumeEach { frame ->
                    if (frame !is Frame.Text) return@consumeEach

                    // Flood guard, counted per connection over a rolling window rather than
                    // per message type — a runaway client is a problem whatever it sends.
                    val receivedAtMs = System.currentTimeMillis()
                    if (receivedAtMs - windowStartedAtMs > RATE_WINDOW_MS) {
                        windowStartedAtMs = receivedAtMs
                        messagesInWindow = 0
                    }
                    if (++messagesInWindow > MAX_MESSAGES_PER_WINDOW) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "rate limit"))
                        return@consumeEach
                    }

                    val message = runCatching {
                        json.decodeFromString(WireMessage.serializer(), frame.readText())
                    }.getOrNull() ?: return@consumeEach

                    when (message.type) {
                        "hello" -> {
                            if (joinedRoom != null) return@consumeEach
                            val clientId = message.clientId?.takeIf { it.isNotBlank() }
                                ?: return@consumeEach sendError("缺少客户端标识")
                            val name = normalizeName(message.name)
                            val avatarId = normalizeAvatarId(message.avatarId, clientId)

                            sweepExpiredRooms()

                            val room = if (message.roomCode == null) {
                                val mediaKey = message.mediaKey?.takeIf { it.isNotBlank() }
                                    ?: return@consumeEach sendError("缺少媒体标识")
                                createRoom(mediaKey, clientId)
                                    ?: return@consumeEach sendError(
                                        "一起看服务房间已满，请稍后再试",
                                    )
                            } else {
                                rooms[message.roomCode.uppercase()]
                                    ?: return@consumeEach sendError("房间不存在或已关闭")
                            }

                            // A reconnect under the same clientId replaces its old session
                            // rather than being rejected — that's what lets the *same*
                            // person resume as host after a network blip instead of losing
                            // control to whoever happened to still be connected.
                            var roomFull = false
                            val staleSession = synchronized(room) {
                                val rejoining = room.participants.containsKey(clientId)
                                if (!rejoining &&
                                    room.participants.size >= MAX_PARTICIPANTS_PER_ROOM
                                ) {
                                    roomFull = true
                                    return@synchronized null
                                }
                                val stale = room.participants[clientId]?.session
                                room.participants[clientId] = Participant(
                                    clientId,
                                    name,
                                    avatarId,
                                    this,
                                )
                                room.emptySinceMs = null
                                if (clientId == room.hostId) {
                                    // The host is back inside its grace window; the slot was
                                    // being held open for exactly this.
                                    room.hostAbsentSinceMs = null
                                } else if (
                                    !room.participants.containsKey(room.hostId) &&
                                    room.hostGraceExpired(hostGraceMs)
                                ) {
                                    // The host slot points at someone who left and did not
                                    // come back in time. Whoever joins now takes it, rather
                                    // than leaving the room locked to a host who may never
                                    // return.
                                    room.hostId = clientId
                                    room.hostAbsentSinceMs = null
                                }
                                stale
                            }
                            if (roomFull) return@consumeEach sendError("房间人数已满")
                            staleSession?.let {
                                runCatching {
                                    it.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "reconnected"))
                                }
                            }

                            joinedRoom = room
                            joinedClientId = clientId
                            sendMessage(room.welcomeMessage(clientId))
                            broadcastRoomUpdate(room)
                        }

                        "sync" -> {
                            val room = joinedRoom ?: return@consumeEach
                            if (joinedClientId != synchronized(room) { room.hostId }) {
                                sendError("仅房主可以控制播放")
                                return@consumeEach
                            }
                            val timeline = synchronized(room) {
                                val next = Timeline(
                                    mediaKey = message.mediaKey?.takeIf { it.isNotBlank() }
                                        ?: room.timeline.mediaKey,
                                    anchorPositionMs = message.positionMs ?: 0L,
                                    anchorAtServerMs = System.currentTimeMillis(),
                                    rate = message.rate?.takeIf { it > 0f } ?: 1f,
                                    paused = message.paused ?: true,
                                    seq = room.timeline.seq + 1,
                                )
                                room.timeline = next
                                next
                            }
                            broadcastSync(room, timeline)
                        }

                        // Control handoff. Guests used to have no way to ask for the
                        // timeline and hosts no way to give it up, so a room whose host had
                        // stopped paying attention could not be steered by anyone.
                        "requestControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val (hostSession, askerName) = synchronized(room) {
                                if (room.hostId == clientId) return@consumeEach
                                room.participants[room.hostId]?.session to
                                    room.participants[clientId]?.name
                            }
                            hostSession?.let { session ->
                                runCatching {
                                    session.sendMessage(
                                        WireMessage(
                                            type = "controlRequested",
                                            clientId = clientId,
                                            name = askerName,
                                        ),
                                    )
                                }
                            }
                        }

                        "grantControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val target = message.targetClientId?.takeIf { it.isNotBlank() }
                                ?: return@consumeEach
                            val handed = synchronized(room) {
                                val isHost = room.hostId == joinedClientId
                                val present = room.participants.containsKey(target)
                                if (isHost && present) {
                                    room.hostId = target
                                    room.hostAbsentSinceMs = null
                                }
                                isHost && present
                            }
                            if (handed) broadcastRoomUpdate(room)
                        }

                        "denyControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val target = message.targetClientId?.takeIf { it.isNotBlank() }
                                ?: return@consumeEach
                            val targetSession = synchronized(room) {
                                if (room.hostId != joinedClientId) {
                                    null
                                } else {
                                    room.participants[target]?.session
                                }
                            }
                            targetSession?.let { session ->
                                runCatching {
                                    session.sendMessage(WireMessage(type = "controlDenied"))
                                }
                            }
                        }

                        "updateProfile" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val now = System.currentTimeMillis()
                            if (now - lastProfileUpdateAtMs < PROFILE_UPDATE_COOLDOWN_MS) {
                                return@consumeEach
                            }
                            lastProfileUpdateAtMs = now
                            synchronized(room) {
                                val current = room.participants[clientId] ?: return@synchronized
                                room.participants[clientId] = Participant(
                                    id = current.id,
                                    name = normalizeName(message.name),
                                    avatarId = normalizeAvatarId(message.avatarId, clientId),
                                    session = current.session,
                                )
                            }
                            broadcastRoomUpdate(room)
                        }

                        "chat" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val text = normalizeChat(message.text)
                                ?: return@consumeEach sendError(
                                    "消息为空、超过 30 字或内容过长",
                                    "chat_invalid",
                                )

                            val now = System.currentTimeMillis()
                            while (
                                recentChatAtMs.isNotEmpty() &&
                                now - recentChatAtMs.first() >= CHAT_RATE_WINDOW_MS
                            ) {
                                recentChatAtMs.removeFirst()
                            }
                            if (recentChatAtMs.size >= MAX_CHAT_MESSAGES_PER_WINDOW) {
                                return@consumeEach sendError("发送太快了，请稍后再试", "chat_rate_limited")
                            }
                            recentChatAtMs.addLast(now)

                            val chat = synchronized(room) {
                                val sender = room.participants[clientId] ?: return@synchronized null
                                WireChatMessage(
                                    id = ++room.nextChatId,
                                    clientId = clientId,
                                    name = sender.name,
                                    avatarId = sender.avatarId,
                                    text = text,
                                    sentAtMs = now,
                                ).also { item ->
                                    room.chatHistory.addLast(item)
                                    while (room.chatHistory.size > MAX_CHAT_HISTORY) {
                                        room.chatHistory.removeFirst()
                                    }
                                }
                            } ?: return@consumeEach
                            broadcastChat(room, chat)
                        }

                        "ping" -> {
                            val sentAt = message.clientSentAtMs ?: return@consumeEach
                            sendMessage(WireMessage(type = "pong", clientSentAtMs = sentAt))
                        }
                    }
                }
            } finally {
                val room = joinedRoom
                val clientId = joinedClientId
                if (room != null && clientId != null) {
                    // Guard against a stale connection's own cleanup evicting a client that
                    // has already reconnected on a new session: only the session currently
                    // on record for `clientId` is allowed to remove it.
                    var hostWentAbsent = false
                    val removedNow = synchronized(room) {
                        val isActiveSession = room.participants[clientId]?.session === this
                        if (isActiveSession) {
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
                        appScope.scheduleHostHandover(room, hostGraceMs)
                    }
                    if (removedNow && room.participants.isNotEmpty()) broadcastRoomUpdate(room)
                }
            }
        }
    }
}

/**
 * Promotes the first remaining participant once a disconnected host's reconnect window
 * expires. The room is checked again after the delay because the host may have reconnected,
 * or a newer disconnect may have started a fresh grace window, while this coroutine slept.
 */
private fun CoroutineScope.scheduleHostHandover(room: Room, graceMs: Long) {
    launch {
        delay(graceMs)
        val handedOver = synchronized(room) {
            if (
                room.participants.containsKey(room.hostId) ||
                !room.hostGraceExpired(graceMs)
            ) {
                false
            } else {
                val nextHostId = room.participants.keys.firstOrNull()
                if (nextHostId == null) {
                    false
                } else {
                    room.hostId = nextHostId
                    room.hostAbsentSinceMs = null
                    true
                }
            }
        }
        if (handedOver) broadcastRoomUpdate(room)
    }
}

private fun createRoom(mediaKey: String, hostId: String): Room? {
    synchronized(roomCreationLock) {
        if (rooms.size >= MAX_ROOMS) return null
        while (true) {
            val code = buildString {
                repeat(ROOM_CODE_LENGTH) {
                    append(ROOM_CODE_ALPHABET[Random.nextInt(ROOM_CODE_ALPHABET.length)])
                }
            }
            val room = Room(
                code = code,
                hostId = hostId,
                timeline = Timeline(
                    mediaKey = mediaKey,
                    anchorPositionMs = 0L,
                    anchorAtServerMs = System.currentTimeMillis(),
                ),
            )
            if (rooms.putIfAbsent(code, room) == null) return room
        }
    }
}

/**
 * Swept opportunistically on `hello` rather than on a background timer: this is a small,
 * single-process, in-memory store, and there is no user-facing requirement to reclaim a
 * dead room's memory on a fixed schedule — only to reclaim it *before* its code could
 * plausibly be reused. Piggybacking on the one call site that actually creates new rooms
 * (and therefore could collide with an old code) avoids owning a lifecycle-scoped
 * background job at all.
 */
private fun sweepExpiredRooms() {
    val now = System.currentTimeMillis()
    rooms.values.forEach { room ->
        // The only other writer of `emptySinceMs` (a reconnect clearing it in the `hello`
        // handler) also takes this same lock, so checking and removing under one
        // `synchronized` block leaves no window for a room to be reoccupied and then
        // evicted anyway.
        synchronized(room) {
            val since = room.emptySinceMs
            if (since != null && now - since > ROOM_GRACE_MS) {
                rooms.remove(room.code, room)
            }
        }
    }
}

private fun Room.welcomeMessage(clientId: String): WireMessage = synchronized(this) {
    WireMessage(
        type = "welcome",
        roomCode = code,
        isHost = hostId == clientId,
        participantCount = participants.size,
        participants = wireParticipants(),
        chatHistory = chatHistory.toList(),
        mediaKey = timeline.mediaKey,
        positionMs = timeline.anchorPositionMs,
        paused = timeline.paused,
        rate = timeline.rate,
        seq = timeline.seq,
        anchorAtMs = timeline.anchorAtServerMs,
    )
}

private suspend fun WebSocketSession.sendMessage(message: WireMessage) {
    send(json.encodeToString(WireMessage.serializer(), message.copy(serverAtMs = System.currentTimeMillis())))
}

private suspend fun WebSocketSession.sendError(message: String, errorCode: String? = null) {
    sendMessage(WireMessage(type = "error", message = message, errorCode = errorCode))
}

/** Membership or host changed; every member gets the current timeline too so a client that missed a `sync` can resync from this alone. */
private suspend fun broadcastRoomUpdate(room: Room) {
    val snapshot = synchronized(room) {
        RoomBroadcastSnapshot(
            members = room.participants.values.toList(),
            participants = room.wireParticipants(),
            timeline = room.timeline,
            hostId = room.hostId,
        )
    }
    snapshot.members.forEach { member ->
        val payload = WireMessage(
            type = "roomUpdate",
            roomCode = room.code,
            isHost = member.id == snapshot.hostId,
            participantCount = snapshot.members.size,
            participants = snapshot.participants,
            mediaKey = snapshot.timeline.mediaKey,
            positionMs = snapshot.timeline.anchorPositionMs,
            paused = snapshot.timeline.paused,
            rate = snapshot.timeline.rate,
            seq = snapshot.timeline.seq,
            anchorAtMs = snapshot.timeline.anchorAtServerMs,
        )
        runCatching { member.session.sendMessage(payload) }
    }
}

private data class RoomBroadcastSnapshot(
    val members: List<Participant>,
    val participants: List<WireParticipant>,
    val timeline: Timeline,
    val hostId: String,
)

private fun Room.wireParticipants(): List<WireParticipant> = participants.values.map { participant ->
    WireParticipant(
        clientId = participant.id,
        name = participant.name,
        avatarId = participant.avatarId,
        isHost = participant.id == hostId,
    )
}

private suspend fun broadcastChat(room: Room, chat: WireChatMessage) {
    val members = synchronized(room) { room.participants.values.toList() }
    members.forEach { member ->
        runCatching { member.session.sendMessage(WireMessage(type = "chat", chat = chat)) }
    }
}

private fun normalizeName(raw: String?): String = raw
    .orEmpty()
    .replace('\r', ' ')
    .replace('\n', ' ')
    .filterNot { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }
    .trim()
    .takeGraphemes(24)
    .takeGraphemesWithinUtf8Bytes(MAX_NAME_BYTES)
    .ifBlank { "影友" }

private fun normalizeAvatarId(raw: Int?, clientId: String): Int =
    raw?.takeIf { it in 0 until AVATAR_COUNT }
        ?: ((clientId.hashCode() and Int.MAX_VALUE) % AVATAR_COUNT)

private fun normalizeChat(raw: String?): String? {
    val text = raw
        ?.replace('\r', ' ')
        ?.replace('\n', ' ')
        ?.filterNot { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    if (text.toByteArray(Charsets.UTF_8).size > MAX_CHAT_BYTES) return null
    if (graphemeRegex.findAll(text).count() > MAX_CHAT_GRAPHEMES) return null
    return text
}

private fun String.takeGraphemes(limit: Int): String =
    graphemeRegex.findAll(this).take(limit).joinToString(separator = "") { it.value }

private fun String.takeGraphemesWithinUtf8Bytes(limit: Int): String {
    var usedBytes = 0
    return buildString {
        for (match in graphemeRegex.findAll(this@takeGraphemesWithinUtf8Bytes)) {
            val bytes = match.value.toByteArray(Charsets.UTF_8).size
            if (usedBytes + bytes > limit) break
            append(match.value)
            usedBytes += bytes
        }
    }
}

private suspend fun broadcastSync(room: Room, timeline: Timeline) {
    val members = synchronized(room) { room.participants.values.toList() }
    val payload = WireMessage(
        type = "sync",
        roomCode = room.code,
        mediaKey = timeline.mediaKey,
        positionMs = timeline.anchorPositionMs,
        paused = timeline.paused,
        rate = timeline.rate,
        seq = timeline.seq,
        anchorAtMs = timeline.anchorAtServerMs,
    )
    members.forEach { member -> runCatching { member.session.sendMessage(payload) } }
}
