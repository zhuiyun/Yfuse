package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountRateLimiter
import com.yfuse.watch.account.accountRoutes
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.origin
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
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol v3 — one flat, nullable-field shape shared by every message type, mirrored
 * by hand in [com.yfuse.core.sync.WatchTogetherClient] (no shared module between this JVM
 * backend and the KMP client). A hello without a version is accepted as legacy v2, while
 * newer unsupported versions are rejected explicitly so a mismatched rollout fails clearly.
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
    /** Present on every v3 server response. Missing on hello means a legacy v2 client. */
    val protocolVersion: Int? = null,
    /** Optional feature negotiation; old clients ignore it and old servers omit it. */
    val capabilities: List<String>? = null,
    // hello / welcome / roomUpdate
    val clientId: String? = null,
    val name: String? = null,
    val avatarId: Int? = null,
    val roomCode: String? = null,
    val isHost: Boolean? = null,
    val canControl: Boolean? = null,
    val controlMode: String? = null,
    val moderator: Boolean? = null,
    val participantCount: Int? = null,
    val participants: List<WireParticipant>? = null,
    // participant playback readiness
    val ready: Boolean? = null,
    val buffering: Boolean? = null,
    val mediaAvailable: Boolean? = null,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
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
    /** 一起看 reaction — one of [REACTIONS], broadcast and never kept. */
    val reaction: String? = null,
    val clientMessageId: String? = null,
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
    val statusKnown: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val mediaAvailable: Boolean = true,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    val canControl: Boolean = false,
    val isModerator: Boolean = false,
)

@Serializable
private data class WireChatMessage(
    val id: Long,
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val text: String,
    val sentAtMs: Long,
    val clientMessageId: String? = null,
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
    var name: String,
    var avatarId: Int,
    val session: WebSocketSession,
    var statusKnown: Boolean = false,
    var ready: Boolean = false,
    var buffering: Boolean = false,
    var mediaAvailable: Boolean = true,
    var latencyMs: Long? = null,
    var syncDriftMs: Long? = null,
)

private enum class ControlMode(val wireValue: String) {
    HostOnly("hostOnly"),
    Everyone("everyone"),
    Moderators("moderators"),
    ;

    companion object {
        fun fromWire(value: String?): ControlMode? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * All mutable fields are only ever read or written from inside `synchronized(this)` —
 * matching the discipline the v1 server already used. [hostId] is a client id, not a
 * session, so replacing a stale session for the same id (reconnect) never disturbs who is
 * host: the room's notion of "who's in charge" survives a network blip untouched.
 */
private class Room(
    val code: String,
    /** Network identity that consumed the creation quota for this room. */
    val creatorIp: String,
    var hostId: String,
    var timeline: Timeline,
    var controlMode: ControlMode = ControlMode.HostOnly,
    val moderatorIds: MutableSet<String> = linkedSetOf(),
    /** Client ids removed by the host cannot reconnect until this room expires. */
    val removedClientIds: MutableSet<String> = linkedSetOf(),
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
    fun canControl(clientId: String): Boolean = when (controlMode) {
        ControlMode.HostOnly -> clientId == hostId
        ControlMode.Everyone -> participants.containsKey(clientId)
        ControlMode.Moderators -> clientId == hostId || clientId in moderatorIds
    }

    /** True once an absent host has been gone long enough to lose the room. */
    fun hostGraceExpired(graceMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val since = hostAbsentSinceMs ?: return true
        return nowMs - since >= graceMs
    }
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

private const val ROOM_CODE_LENGTH = 6
private const val ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
private const val WATCH_PROTOCOL_VERSION = 3
private const val MIN_SUPPORTED_PROTOCOL_VERSION = 2

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
private const val DEFAULT_MAX_ACTIVE_ROOMS_PER_IP = 8
private const val MAX_PARTICIPANTS_PER_ROOM = 12
private const val MAX_CLIENT_ID_BYTES = 128
private const val MAX_REMOVED_CLIENT_IDS_PER_ROOM = 256
private const val MAX_MESSAGES_PER_WINDOW = 240
private const val RATE_WINDOW_MS = 10_000L
private const val AVATAR_COUNT = 8
private const val MAX_NAME_BYTES = 128
private const val MAX_CHAT_GRAPHEMES = 30
private const val MAX_CHAT_BYTES = 768
private const val MAX_CHAT_HISTORY = 50
private const val MAX_CHAT_MESSAGES_PER_WINDOW = 3
private const val CHAT_RATE_WINDOW_MS = 3_000L

/**
 * The reactions a client may send.
 *
 * A closed set rather than arbitrary text: a reaction is broadcast to everyone in the
 * room without moderation and never stored, so the only safe thing to relay is something
 * the server itself chose. It also keeps the wire tiny — reactions are the one message
 * that can arrive several times a second.
 */
private val REACTIONS = setOf("😂", "😮", "😍", "😭", "👏", "🔥", "🤔", "💀")
private val SERVER_CAPABILITIES = listOf("reactions")

/** Bursts are the point, so this is looser than chat — but still bounded. */
private const val MAX_REACTIONS_PER_WINDOW = 6
private const val REACTION_RATE_WINDOW_MS = 3_000L
private const val PROFILE_UPDATE_COOLDOWN_MS = 1_000L
private const val MAX_CLIENT_MESSAGE_ID_BYTES = 128
private val graphemeRegex = Regex("\\X")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = resolveServerHost(System.getenv("HOST"))
    val accountBackend = AccountBackend.sqlite(
        File(System.getenv("ACCOUNT_DB_PATH") ?: "/var/lib/yfuse/account.db"),
    )
    embeddedServer(CIO, host = host, port = port) {
        watchTogetherModule(accountBackend = accountBackend)
    }.start(wait = true)
}

internal fun resolveServerHost(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "127.0.0.1"
    require(value.length <= 255 && value.none { it.isWhitespace() || Character.isISOControl(it) }) {
        "HOST is invalid"
    }
    return value
}

fun Application.watchTogetherModule(
    updateRoot: File = File(System.getenv("UPDATE_ROOT") ?: "/srv/yfuse-update/yfuse"),
    /** Injectable so tests can exercise the handover without waiting out the real window. */
    hostGraceMs: Long = HOST_GRACE_MS,
    /** Empty rooms retain their code briefly for reconnects, then release quota on sweep. */
    roomGraceMs: Long = ROOM_GRACE_MS,
    maxActiveRoomsPerIp: Int = System.getenv("WATCH_MAX_ACTIVE_ROOMS_PER_IP")
        ?.toIntOrNull()
        ?.coerceIn(1, MAX_ROOMS)
        ?: DEFAULT_MAX_ACTIVE_ROOMS_PER_IP,
    /** Only enable when the reverse proxy overwrites client-supplied forwarding headers. */
    trustProxyHeaders: Boolean = System.getenv("WATCH_TRUST_PROXY_HEADERS")
        ?.equals("true", ignoreCase = true)
        ?: false,
    /** Test seam; production normally uses the socket/proxy-aware resolver below. */
    clientIpResolver: ((ApplicationCall) -> String)? = null,
    /** Account persistence is independent of the ephemeral watch-room store. */
    accountBackend: AccountBackend = AccountBackend.inMemory(),
    /** Authentication limiter is application-local and injectable for deterministic tests. */
    accountRateLimiter: AccountRateLimiter = AccountRateLimiter(),
) {
    require(roomGraceMs >= 0L) { "roomGraceMs must not be negative" }
    require(maxActiveRoomsPerIp in 1..MAX_ROOMS) {
        "maxActiveRoomsPerIp must be between 1 and $MAX_ROOMS"
    }
    val roomStore = RoomStore(
        roomGraceMs = roomGraceMs,
        maxActiveRoomsPerIp = maxActiveRoomsPerIp,
    )
    // Outlives any one socket, which is what a delayed host handover needs: the connection
    // whose loss starts the clock is precisely the one that can't run the timer.
    val appScope: CoroutineScope = this
    monitor.subscribe(ApplicationStopped) { accountBackend.close() }
    install(WebSockets) {
        pingPeriodMillis = 20_000L
        timeoutMillis = 40_000L
        maxFrameSize = 64 * 1024L
        masking = false
    }
    routing {
        accountRoutes(accountBackend, accountRateLimiter)
        get("/health") {
            call.respondText("ok")
        }
        get("/watch/version") {
            call.respondText(
                """{"protocolVersion":$WATCH_PROTOCOL_VERSION}""",
                ContentType.Application.Json,
            )
        }
        staticFiles("/yfuse", updateRoot)
        webSocket("/watch") {
            val clientIp = clientIpResolver
                ?.invoke(call)
                ?.trim()
                ?.take(128)
                ?.ifBlank { null }
                ?: resolveClientIp(
                    remoteHost = call.request.origin.remoteHost,
                    xForwardedFor = call.request.headers["X-Forwarded-For"],
                    forwarded = call.request.headers["Forwarded"],
                    trustProxyHeaders = trustProxyHeaders,
                )
            var joinedRoom: Room? = null
            var joinedClientId: String? = null
            var windowStartedAtMs = System.currentTimeMillis()
            var messagesInWindow = 0
            val recentChatAtMs = ArrayDeque<Long>()
            val recentReactionAtMs = ArrayDeque<Long>()
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
                            val requestedProtocol = message.protocolVersion
                            if (
                                requestedProtocol != null &&
                                requestedProtocol !in MIN_SUPPORTED_PROTOCOL_VERSION..WATCH_PROTOCOL_VERSION
                            ) {
                                return@consumeEach sendError(
                                    "一起看协议版本不兼容，请更新 App 或服务器",
                                    "protocol_incompatible",
                                )
                            }
                            val clientId = normalizeClientId(message.clientId)
                                ?: return@consumeEach sendError(
                                    "客户端标识无效",
                                    "client_id_invalid",
                                )
                            val name = normalizeName(message.name)
                            val avatarId = normalizeAvatarId(message.avatarId, clientId)

                            roomStore.sweepExpiredRooms()

                            val room = if (message.roomCode == null) {
                                val mediaKey = message.mediaKey?.takeIf { it.isNotBlank() }
                                    ?: return@consumeEach sendError("缺少媒体标识")
                                when (
                                    val created = roomStore.createRoom(
                                        mediaKey = mediaKey,
                                        hostId = clientId,
                                        creatorIp = clientIp,
                                    )
                                ) {
                                    is RoomCreationResult.Created -> created.room
                                    RoomCreationResult.IpLimitReached -> {
                                        return@consumeEach sendError(
                                            "当前网络创建的活跃房间过多，请稍后再试",
                                            "room_ip_limit",
                                        )
                                    }
                                    RoomCreationResult.ServiceFull -> {
                                        return@consumeEach sendError(
                                            "一起看服务房间已满，请稍后再试",
                                            "room_service_full",
                                        )
                                    }
                                }
                            } else {
                                roomStore.find(message.roomCode.uppercase())
                                    ?: return@consumeEach sendError("房间不存在或已关闭")
                            }

                            // A reconnect under the same clientId replaces its old session
                            // rather than being rejected — that's what lets the *same*
                            // person resume as host after a network blip instead of losing
                            // control to whoever happened to still be connected.
                            var roomFull = false
                            var removedByHost = false
                            var staleSession: WebSocketSession? = null
                            val roomStillCurrent = roomStore.mutateIfCurrent(room) {
                                if (clientId in room.removedClientIds) {
                                    removedByHost = true
                                    return@mutateIfCurrent
                                }
                                val rejoining = room.participants.containsKey(clientId)
                                if (!rejoining &&
                                    room.participants.size >= MAX_PARTICIPANTS_PER_ROOM
                                ) {
                                    roomFull = true
                                    return@mutateIfCurrent
                                }
                                staleSession = room.participants[clientId]?.session
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
                            }
                            if (!roomStillCurrent) {
                                return@consumeEach sendError("房间不存在或已关闭")
                            }
                            if (removedByHost) {
                                sendError("你已被房主移出当前房间", "removed_by_host")
                                close(
                                    CloseReason(
                                        CloseReason.Codes.VIOLATED_POLICY,
                                        "removed by host",
                                    ),
                                )
                                return@consumeEach
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
                            val clientId = joinedClientId ?: return@consumeEach
                            if (!synchronized(room) { room.canControl(clientId) }) {
                                sendError("当前没有播放控制权限")
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
                                if (room.canControl(clientId)) return@consumeEach
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
                            val target = normalizeClientId(message.targetClientId)
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

                        "setControlMode" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val requested = ControlMode.fromWire(message.controlMode)
                                ?: return@consumeEach sendError(
                                    "控制权限模式无效",
                                    "control_mode_invalid",
                                )
                            val changed = synchronized(room) {
                                if (room.hostId != clientId || room.controlMode == requested) {
                                    return@synchronized false
                                }
                                room.controlMode = requested
                                true
                            }
                            if (changed) broadcastRoomUpdate(room)
                        }

                        "setModerator" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val target = message.targetClientId?.takeIf { it.isNotBlank() }
                                ?: return@consumeEach
                            val enabled = message.moderator ?: return@consumeEach
                            val changed = synchronized(room) {
                                if (
                                    room.hostId != clientId ||
                                    target == room.hostId ||
                                    !room.participants.containsKey(target)
                                ) {
                                    return@synchronized false
                                }
                                if (enabled) room.moderatorIds.add(target)
                                else room.moderatorIds.remove(target)
                            }
                            if (changed) broadcastRoomUpdate(room)
                        }

                        "kickParticipant" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val target = normalizeClientId(message.targetClientId)
                                ?: return@consumeEach
                            var denied = false
                            var removalLimitReached = false
                            val targetSession = synchronized(room) {
                                if (room.hostId != clientId) {
                                    denied = true
                                    return@synchronized null
                                }
                                if (target == room.hostId) return@synchronized null
                                val participant = room.participants[target]
                                    ?: return@synchronized null
                                if (!rememberRemovedClientId(room.removedClientIds, target)) {
                                    removalLimitReached = true
                                    return@synchronized null
                                }
                                room.participants.remove(target)
                                room.moderatorIds.remove(target)
                                participant.session
                            }
                            if (denied) {
                                sendError("仅房主可以移出成员", "host_only")
                                return@consumeEach
                            }
                            if (removalLimitReached) {
                                sendError(
                                    "当前房间移出记录已达上限，请创建新房间后继续",
                                    "kick_limit_reached",
                                )
                                return@consumeEach
                            }
                            targetSession?.let { session ->
                                runCatching {
                                    session.sendMessage(
                                        WireMessage(
                                            type = "kicked",
                                            message = "你已被房主移出当前房间",
                                        ),
                                    )
                                    session.close(
                                        CloseReason(
                                            CloseReason.Codes.VIOLATED_POLICY,
                                            "removed by host",
                                        ),
                                    )
                                }
                                broadcastRoomUpdate(room)
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
                                current.name = normalizeName(message.name)
                                current.avatarId = normalizeAvatarId(message.avatarId, clientId)
                            }
                            broadcastRoomUpdate(room)
                        }

                        "playbackStatus" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val changed = synchronized(room) {
                                val participant = room.participants[clientId]
                                    ?: return@synchronized false
                                val nextMediaAvailable = message.mediaAvailable ?: true
                                val nextBuffering = message.buffering == true && nextMediaAvailable
                                val nextReady = message.ready == true &&
                                    nextMediaAvailable && !nextBuffering
                                val nextLatencyMs = message.latencyMs?.coerceIn(0L, 10_000L)
                                val nextSyncDriftMs = message.syncDriftMs?.coerceIn(-30_000L, 30_000L)
                                val differs = !participant.statusKnown ||
                                    participant.ready != nextReady ||
                                    participant.buffering != nextBuffering ||
                                    participant.mediaAvailable != nextMediaAvailable ||
                                    participant.latencyMs != nextLatencyMs ||
                                    participant.syncDriftMs != nextSyncDriftMs
                                participant.statusKnown = true
                                participant.ready = nextReady
                                participant.buffering = nextBuffering
                                participant.mediaAvailable = nextMediaAvailable
                                participant.latencyMs = nextLatencyMs
                                participant.syncDriftMs = nextSyncDriftMs
                                differs
                            }
                            if (changed) broadcastRoomUpdate(room)
                        }

                        "chat" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val clientMessageId = normalizeClientMessageId(message.clientMessageId)
                            if (message.clientMessageId != null && clientMessageId == null) {
                                return@consumeEach sendError(
                                    "消息标识无效，请重试",
                                    "chat_invalid",
                                )
                            }
                            val text = normalizeChat(message.text)
                                ?: return@consumeEach sendError(
                                    "消息为空、超过 30 字或内容过长",
                                    "chat_invalid",
                                    clientMessageId,
                                )

                            val existing = clientMessageId?.let { requestedId ->
                                synchronized(room) {
                                    room.chatHistory.firstOrNull {
                                        it.clientId == clientId &&
                                            it.clientMessageId == requestedId
                                    }
                                }
                            }
                            if (existing != null) {
                                sendMessage(WireMessage(type = "chat", chat = existing))
                                return@consumeEach
                            }

                            val now = System.currentTimeMillis()
                            while (
                                recentChatAtMs.isNotEmpty() &&
                                now - recentChatAtMs.first() >= CHAT_RATE_WINDOW_MS
                            ) {
                                recentChatAtMs.removeFirst()
                            }
                            if (recentChatAtMs.size >= MAX_CHAT_MESSAGES_PER_WINDOW) {
                                return@consumeEach sendError(
                                    "发送太快了，请稍后再试",
                                    "chat_rate_limited",
                                    clientMessageId,
                                )
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
                                    clientMessageId = clientMessageId,
                                ).also { item ->
                                    room.chatHistory.addLast(item)
                                    while (room.chatHistory.size > MAX_CHAT_HISTORY) {
                                        room.chatHistory.removeFirst()
                                    }
                                }
                            } ?: return@consumeEach
                            broadcastChat(room, chat)
                        }

                        "reaction" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val reaction = message.reaction?.takeIf { it in REACTIONS }
                                ?: return@consumeEach sendError(
                                    "不支持这个表情",
                                    "reaction_invalid",
                                )

                            val now = System.currentTimeMillis()
                            while (
                                recentReactionAtMs.isNotEmpty() &&
                                now - recentReactionAtMs.first() >= REACTION_RATE_WINDOW_MS
                            ) {
                                recentReactionAtMs.removeFirst()
                            }
                            // Dropped rather than reported: a reaction is a flourish, and an
                            // error toast for tapping one too fast is worse than nothing
                            // happening.
                            if (recentReactionAtMs.size >= MAX_REACTIONS_PER_WINDOW) {
                                return@consumeEach
                            }
                            recentReactionAtMs.addLast(now)

                            val sender = synchronized(room) { room.participants[clientId] }
                                ?: return@consumeEach
                            broadcastReaction(room, clientId, sender.name, reaction)
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

private sealed interface RoomCreationResult {
    data class Created(val room: Room) : RoomCreationResult
    data object IpLimitReached : RoomCreationResult
    data object ServiceFull : RoomCreationResult
}

/**
 * Application-local room index. Quota is derived from the rooms that are actually present
 * instead of a second mutable counter, so every successful expiry/removal releases it in
 * the same atomic operation and a failed creation can never leak a slot.
 */
private class RoomStore(
    private val roomGraceMs: Long,
    private val maxActiveRoomsPerIp: Int,
    private val roomCodeRandom: SecureRandom = SecureRandom(),
) {
    private val rooms = ConcurrentHashMap<String, Room>()

    /** Serializes expiry, quota checks, and insertion under concurrent create bursts. */
    private val creationLock = Any()

    fun find(code: String): Room? = rooms[code]

    /**
     * Registers a join while holding the same store→room lock order used by expiry. A room
     * found just before its grace elapsed therefore cannot be removed between lookup and
     * clearing `emptySinceMs`.
     */
    fun mutateIfCurrent(room: Room, block: (Room) -> Unit): Boolean =
        synchronized(creationLock) {
            if (rooms[room.code] !== room) return@synchronized false
            synchronized(room) { block(room) }
            true
        }

    fun createRoom(
        mediaKey: String,
        hostId: String,
        creatorIp: String,
    ): RoomCreationResult = synchronized(creationLock) {
        sweepExpiredRoomsLocked(System.currentTimeMillis())
        if (rooms.size >= MAX_ROOMS) return@synchronized RoomCreationResult.ServiceFull
        if (rooms.values.count { it.creatorIp == creatorIp } >= maxActiveRoomsPerIp) {
            return@synchronized RoomCreationResult.IpLimitReached
        }
        while (true) {
            val code = buildString(ROOM_CODE_LENGTH) {
                repeat(ROOM_CODE_LENGTH) {
                    append(ROOM_CODE_ALPHABET[roomCodeRandom.nextInt(ROOM_CODE_ALPHABET.length)])
                }
            }
            val room = Room(
                code = code,
                creatorIp = creatorIp,
                hostId = hostId,
                timeline = Timeline(
                    mediaKey = mediaKey,
                    anchorPositionMs = 0L,
                    anchorAtServerMs = System.currentTimeMillis(),
                ),
            )
            if (rooms.putIfAbsent(code, room) == null) {
                return@synchronized RoomCreationResult.Created(room)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        RoomCreationResult.ServiceFull
    }

    /**
     * Swept opportunistically on `hello`: no timer is required, but stale rooms and their
     * per-IP quota are reclaimed before any lookup or new creation can consume capacity.
     */
    fun sweepExpiredRooms(nowMs: Long = System.currentTimeMillis()) {
        synchronized(creationLock) {
            sweepExpiredRoomsLocked(nowMs)
        }
    }

    private fun sweepExpiredRoomsLocked(nowMs: Long) {
        rooms.values.forEach { room ->
            // Registration uses the same store→room lock order, so an expired room cannot
            // be removed between lookup and clearing `emptySinceMs`.
            synchronized(room) {
                val since = room.emptySinceMs
                if (since != null && nowMs - since >= roomGraceMs) {
                    rooms.remove(room.code, room)
                }
            }
        }
    }
}

/**
 * Resolves the quota identity. Forwarding headers are intentionally ignored unless the
 * deployment opts in; otherwise a direct client could rotate a spoofed header to bypass
 * the limit. The trusted proxy must overwrite, rather than append to, inbound headers.
 */
internal fun resolveClientIp(
    remoteHost: String,
    xForwardedFor: String?,
    forwarded: String?,
    trustProxyHeaders: Boolean,
): String {
    val direct = remoteHost.trim().take(128).ifBlank { "unknown" }
    if (!trustProxyHeaders) return direct

    val forwardedFor = xForwardedFor
        ?.substringBefore(',')
        ?.let(::normalizeForwardedAddress)
        ?: forwarded
            ?.substringBefore(',')
            ?.split(';')
            ?.firstNotNullOfOrNull { part ->
                part.substringAfter('=', missingDelimiterValue = "")
                    .takeIf { part.substringBefore('=').trim().equals("for", ignoreCase = true) }
                    ?.let(::normalizeForwardedAddress)
            }
    return forwardedFor ?: direct
}

private fun normalizeForwardedAddress(raw: String): String? {
    var value = raw.trim().removeSurrounding("\"")
    if (value.equals("unknown", ignoreCase = true) || value.startsWith('_')) return null
    if (value.startsWith('[')) {
        value = value.substringAfter('[').substringBefore(']')
    } else if (value.count { it == ':' } == 1 && value.substringBeforeLast(':').contains('.')) {
        value = value.substringBeforeLast(':')
    }
    return value
        .trim()
        .lowercase()
        .takeIf { it.isNotBlank() && it.length <= 128 }
}

private fun Room.welcomeMessage(clientId: String): WireMessage = synchronized(this) {
    WireMessage(
        type = "welcome",
        capabilities = SERVER_CAPABILITIES,
        roomCode = code,
        isHost = hostId == clientId,
        canControl = canControl(clientId),
        controlMode = controlMode.wireValue,
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
    send(
        json.encodeToString(
            WireMessage.serializer(),
            message.copy(
                protocolVersion = WATCH_PROTOCOL_VERSION,
                serverAtMs = System.currentTimeMillis(),
            ),
        ),
    )
}

private suspend fun WebSocketSession.sendError(
    message: String,
    errorCode: String? = null,
    clientMessageId: String? = null,
) {
    sendMessage(
        WireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            clientMessageId = clientMessageId,
        ),
    )
}

/** Membership or host changed; every member gets the current timeline too so a client that missed a `sync` can resync from this alone. */
private suspend fun broadcastRoomUpdate(room: Room) {
    val snapshot = synchronized(room) {
        RoomBroadcastSnapshot(
            members = room.participants.values.toList(),
            participants = room.wireParticipants(),
            timeline = room.timeline,
            hostId = room.hostId,
            controlMode = room.controlMode,
            canControlIds = room.participants.keys
                .filterTo(linkedSetOf()) { room.canControl(it) },
        )
    }
    snapshot.members.forEach { member ->
        val payload = WireMessage(
            type = "roomUpdate",
            roomCode = room.code,
            isHost = member.id == snapshot.hostId,
            canControl = member.id in snapshot.canControlIds,
            controlMode = snapshot.controlMode.wireValue,
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
    val controlMode: ControlMode,
    val canControlIds: Set<String>,
)

private fun Room.wireParticipants(): List<WireParticipant> = participants.values.map { participant ->
    WireParticipant(
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
        canControl = canControl(participant.id),
        isModerator = participant.id in moderatorIds,
    )
}

private suspend fun broadcastChat(room: Room, chat: WireChatMessage) {
    val members = synchronized(room) { room.participants.values.toList() }
    members.forEach { member ->
        runCatching { member.session.sendMessage(WireMessage(type = "chat", chat = chat)) }
    }
}

/**
 * Reactions are not kept anywhere: no history, no replay on join. Someone who was not in
 * the room when it happened has missed it, which is the whole idea.
 */
private suspend fun broadcastReaction(
    room: Room,
    clientId: String,
    name: String,
    reaction: String,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    members.forEach { member ->
        runCatching {
            member.session.sendMessage(
                WireMessage(
                    type = "reaction",
                    clientId = clientId,
                    name = name,
                    reaction = reaction,
                ),
            )
        }
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

internal fun normalizeClientId(raw: String?): String? {
    if (raw == null) return null
    val value = raw.trim()
    if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_CLIENT_ID_BYTES) {
        return null
    }
    if (value.any { it.code in 0x00..0x20 || it.code in 0x7F..0x9F }) return null
    return value
}

internal fun rememberRemovedClientId(
    removedClientIds: MutableSet<String>,
    clientId: String,
    limit: Int = MAX_REMOVED_CLIENT_IDS_PER_ROOM,
): Boolean {
    require(limit > 0) { "limit must be positive" }
    if (clientId in removedClientIds) return true
    if (removedClientIds.size >= limit) return false
    removedClientIds.add(clientId)
    return true
}

private fun normalizeClientMessageId(raw: String?): String? {
    if (raw == null) return null
    val value = raw.trim()
    if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_CLIENT_MESSAGE_ID_BYTES) {
        return null
    }
    if (value.any { it.code in 0x00..0x20 || it.code in 0x7F..0x9F }) return null
    return value
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
