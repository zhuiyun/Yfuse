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
 * [serverAtMs] is stamped on *every* outgoing message (via [sendMessage]), not just `pong`,
 * so the client can keep refining its clock-offset estimate from ordinary traffic instead
 * of only from dedicated pings.
 */
@Serializable
private data class WireMessage(
    val type: String,
    // hello / welcome / roomUpdate
    val clientId: String? = null,
    val name: String? = null,
    val roomCode: String? = null,
    val isHost: Boolean? = null,
    val participantCount: Int? = null,
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
    // error
    val message: String? = null,
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
                            val name = message.name?.take(24).orEmpty().ifBlank { "访客" }

                            sweepExpiredRooms()

                            val room = if (message.roomCode == null) {
                                val mediaKey = message.mediaKey?.takeIf { it.isNotBlank() }
                                    ?: return@consumeEach sendError("缺少媒体标识")
                                if (rooms.size >= MAX_ROOMS) {
                                    return@consumeEach sendError("一起看服务房间已满,请稍后再试")
                                }
                                createRoom(mediaKey, clientId)
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
                                room.participants[clientId] = Participant(clientId, name, this)
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

private fun createRoom(mediaKey: String, hostId: String): Room {
    while (true) {
        val code = buildString {
            repeat(ROOM_CODE_LENGTH) { append(ROOM_CODE_ALPHABET[Random.nextInt(ROOM_CODE_ALPHABET.length)]) }
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

private suspend fun WebSocketSession.sendError(message: String) {
    sendMessage(WireMessage(type = "error", message = message))
}

/** Membership or host changed; every member gets the current timeline too so a client that missed a `sync` can resync from this alone. */
private suspend fun broadcastRoomUpdate(room: Room) {
    val (members, timeline, hostId) = synchronized(room) {
        Triple(room.participants.values.toList(), room.timeline, room.hostId)
    }
    members.forEach { member ->
        val payload = WireMessage(
            type = "roomUpdate",
            roomCode = room.code,
            isHost = member.id == hostId,
            participantCount = members.size,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
        )
        runCatching { member.session.sendMessage(payload) }
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
