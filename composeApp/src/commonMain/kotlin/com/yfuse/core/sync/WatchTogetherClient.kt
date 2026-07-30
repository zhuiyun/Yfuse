package com.yfuse.core.sync

import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.network.embyHttpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire shape — hand-mirrored from the server's `WireMessage`; see that file for why. */
@Serializable
private data class WatchWireMessage(
    val type: String,
    val clientId: String? = null,
    val name: String? = null,
    val roomCode: String? = null,
    val isHost: Boolean? = null,
    val participantCount: Int? = null,
    val mediaKey: String? = null,
    val positionMs: Long? = null,
    val paused: Boolean? = null,
    val rate: Float? = null,
    val seq: Long? = null,
    val anchorAtMs: Long? = null,
    val serverAtMs: Long? = null,
    val clientSentAtMs: Long? = null,
    val targetClientId: String? = null,
    val message: String? = null,
)

/** A guest asking the host for the timeline. Surfaced to the host so it can answer. */
data class ControlRequest(val clientId: String, val name: String)

data class WatchTogetherState(
    /** Handshaking on a brand-new `createRoom`/`joinRoom` call — no room has ever answered yet. */
    val connecting: Boolean = false,
    /** In a room right now, whether or not the socket under it happens to be up this instant. */
    val connected: Boolean = false,
    /** In a room, but the socket dropped and a retry is in flight. UI should keep showing
     *  the room (code, participants) rather than reverting to the join form. */
    val reconnecting: Boolean = false,
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val participantCount: Int = 0,
    val mediaKey: String? = null,
    val error: String? = null,
    /**
     * Set by the player when the room's media matches nothing in the local queue. The room is
     * real and the socket is up; this device just can't follow the timeline. Kept apart from
     * [error] because a room snapshot clears that one on arrival, and this condition is
     * discovered locally rather than reported by the server.
     */
    val syncWarning: String? = null,
    /** A guest has asked for control. Only ever non-null for the host, until it answers. */
    val controlRequest: ControlRequest? = null,
    /** This device asked for control and is still waiting to hear back. */
    val controlRequested: Boolean = false,
)

/**
 * The room's shared "what's playing and where", mirroring the server's `Timeline`. Position
 * is only meaningful *at* [anchorAtServerMs] — [expectedPositionMs] projects it forward (or
 * leaves it be, if paused) to any other point on the server's clock.
 */
data class WatchTimeline(
    val mediaKey: String,
    val anchorPositionMs: Long,
    val anchorAtServerMs: Long,
    val rate: Float,
    val paused: Boolean,
    val seq: Long,
) {
    fun expectedPositionMs(serverNowMs: Long): Long {
        if (paused) return anchorPositionMs
        val elapsedMs = (serverNowMs - anchorAtServerMs).coerceAtLeast(0L)
        return anchorPositionMs + (elapsedMs * rate).toLong()
    }
}

private class RoomUnavailableException(message: String) : Exception(message)

/**
 * Estimates the offset between this device's clock and the server's, from `ping`/`pong`
 * round trips. Comparing the two devices' own wall clocks directly — what v1 did — breaks
 * the moment they disagree by more than a couple of seconds, which is common enough on
 * real phones that it made the latency compensation pure noise.
 *
 * Single-sample offset (classic NTP): the server's timestamp is assumed to correspond to
 * the midpoint of the round trip on *our* clock. A rolling median over the last few
 * samples smooths out any one slow round trip; samples with implausible RTT are dropped
 * outright rather than allowed to skew the median.
 */
private class ClockSync {
    private val lock = Any()
    private val samples = ArrayDeque<Long>()
    private val inFlight = HashMap<Long, TimeSource.Monotonic.ValueTimeMark>()
    @Volatile private var offsetMs: Long = 0L

    /**
     * Remembers when a ping actually left, on a clock that cannot be corrected underneath us.
     * The wall-clock value is only the correlation key — it's what the server echoes back.
     */
    fun recordPingSent(clientSentAtMs: Long) {
        synchronized(lock) {
            // A dropped socket leaves its unanswered pings behind; they are never useful again.
            if (inFlight.size > MAX_IN_FLIGHT) inFlight.clear()
            inFlight[clientSentAtMs] = TimeSource.Monotonic.markNow()
        }
    }

    fun recordPong(clientSentAtMs: Long, serverAtMs: Long, clientReceivedAtMs: Long) {
        val mark = synchronized(lock) { inFlight.remove(clientSentAtMs) } ?: return
        val rtt = mark.elapsedNow().inWholeMilliseconds
        if (rtt < 0 || rtt > MAX_ACCEPTABLE_RTT_MS) return
        // The server's timestamp is assumed to land at the midpoint of the round trip. Deriving
        // that midpoint from the arrival reading minus half a *monotonically* measured trip
        // stops a wall-clock correction mid-flight from masquerading as latency — which, on a
        // phone, is exactly what an NTP sync looks like to two subtracted wall-clock reads.
        val clientNowAtServerSample = clientReceivedAtMs - rtt / 2
        val offset = serverAtMs - clientNowAtServerSample
        synchronized(lock) {
            // A device clock correction shifts every future sample at once. The earlier ones
            // aren't noise to be averaged away, they're simply describing a clock that no
            // longer exists, so they go rather than dragging the median for a minute.
            if (samples.isNotEmpty() && abs(offset - offsetMs) > CLOCK_JUMP_MS) samples.clear()
            samples.addLast(offset)
            if (samples.size > MAX_SAMPLES) samples.removeFirst()
            offsetMs = samples.sorted()[samples.size / 2]
        }
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            inFlight.clear()
        }
        offsetMs = 0L
    }

    fun serverNow(deviceNowMs: Long): Long = deviceNowMs + offsetMs

    private companion object {
        const val MAX_SAMPLES = 7
        const val MAX_ACCEPTABLE_RTT_MS = 4_000L
        const val MAX_IN_FLIGHT = 16
        const val CLOCK_JUMP_MS = 2_000L
    }
}

class WatchTogetherClient(private val preferences: WatchTogetherPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val client = HttpClient(embyHttpEngine()) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sendMutex = Mutex()
    private val clock = ClockSync()

    private var connectionJob: Job? = null
    private var currentSession: DefaultClientWebSocketSession? = null

    // Parameters for the room currently being held, replayed on every reconnect attempt.
    private var pendingUrl: String? = null
    private var pendingRoomCode: String? = null
    private var pendingMediaKey: String? = null
    private var pendingName: String = ""

    /** True once any attempt for the current room has been welcomed. Failures before this
     *  point are the user's initial attempt going wrong (bad address, room doesn't exist) —
     *  surfaced once, not retried. Failures after it are a live room dropping a connection,
     *  which retries indefinitely until [leave] is called. */
    private var everWelcomed = false
    private var reconnectAttempt = 0

    private val _state = MutableStateFlow(WatchTogetherState())
    val state: StateFlow<WatchTogetherState> = _state.asStateFlow()
    private val _timeline = MutableStateFlow<WatchTimeline?>(null)
    val timeline: StateFlow<WatchTimeline?> = _timeline.asStateFlow()

    /** Best estimate of the server's clock right now, for projecting [timeline] forward. */
    fun estimatedServerNow(): Long = clock.serverNow(System.currentTimeMillis())

    fun createRoom(endpoint: String, mediaKey: String, name: String = "房主") {
        preferences.setEndpoint(endpoint)
        start(endpoint, roomCode = null, mediaKey = mediaKey, name = name)
    }

    fun joinRoom(endpoint: String, roomCode: String, mediaKey: String, name: String = "访客") {
        preferences.setEndpoint(endpoint)
        start(endpoint, roomCode.uppercase(), mediaKey, name)
    }

    /**
     * Joins using the relay named inside an invite link, for this room only.
     *
     * Deliberately not persisted: a link is an untrusted input, and writing its relay to
     * settings would let one shared link silently repoint every room the user later hosts at
     * someone else's server — long after the sheet that disclosed it is gone.
     */
    fun joinRoomFromInvite(
        endpoint: String,
        roomCode: String,
        mediaKey: String,
        name: String = "访客",
    ) {
        start(endpoint, roomCode.uppercase(), mediaKey, name)
    }

    /** Asks the host to hand over control. No-op for a host, or outside a room. */
    fun requestControl() {
        val state = _state.value
        if (!state.connected || state.isHost) return
        _state.update { it.copy(controlRequested = true) }
        send(WatchWireMessage(type = "requestControl"))
    }

    /** Host-only: hand the timeline to the participant that asked for it. */
    fun grantControl(clientId: String) {
        if (!_state.value.isHost) return
        _state.update { it.copy(controlRequest = null) }
        send(WatchWireMessage(type = "grantControl", targetClientId = clientId))
    }

    /** Host-only: refuse a pending request, telling the asker rather than just ignoring it. */
    fun denyControl(clientId: String) {
        if (!_state.value.isHost) return
        _state.update { it.copy(controlRequest = null) }
        send(WatchWireMessage(type = "denyControl", targetClientId = clientId))
    }

    /**
     * Reported by the player when the room's media matches nothing it can play. Kept out of
     * [WatchTogetherState.error] so an arriving room snapshot doesn't wipe it.
     */
    fun setSyncWarning(message: String?) {
        _state.update { if (it.syncWarning == message) it else it.copy(syncWarning = message) }
    }

    /** Host-only: publish a new anchor. Call this on every play/pause/seek/rate/media change
     *  — not on a timer — the room's position between calls is entirely extrapolated. */
    fun publishTimeline(mediaKey: String, positionMs: Long, paused: Boolean, rate: Float = 1f) {
        if (!_state.value.isHost) return
        send(
            WatchWireMessage(
                type = "sync",
                mediaKey = mediaKey,
                positionMs = positionMs,
                paused = paused,
                rate = rate,
            ),
        )
    }

    fun leave() {
        leaveInternal()
        _state.value = WatchTogetherState()
        _timeline.value = null
    }

    private fun leaveInternal() {
        val job = connectionJob
        val session = currentSession
        connectionJob = null
        currentSession = null
        job?.cancel()
        if (session != null) {
            scope.launch {
                runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, "leave")) }
            }
        }
    }

    private fun start(endpoint: String, roomCode: String?, mediaKey: String, name: String) {
        val url = endpoint.toWebSocketUrl()
        if (url == null) {
            _state.value = WatchTogetherState(error = "一起看服务地址无效")
            return
        }
        leaveInternal()
        clock.reset()
        everWelcomed = false
        reconnectAttempt = 0
        pendingUrl = url
        pendingRoomCode = roomCode
        pendingMediaKey = mediaKey
        pendingName = name
        _state.value = WatchTogetherState(connecting = true)
        _timeline.value = null
        connectionJob = scope.launch { connectionLoop() }
    }

    /** Runs sessions back-to-back until [leave] cancels this coroutine. Each pass is one
     *  connection attempt; how a failure is handled depends on whether *this room* has ever
     *  been successfully entered (see [everWelcomed]). */
    private suspend fun connectionLoop() {
        while (currentCoroutineContext().isActive) {
            val outcome = runCatching { runSession() }
            if (!currentCoroutineContext().isActive) return

            val roomGone = outcome.exceptionOrNull() is RoomUnavailableException
            if (roomGone || !everWelcomed) {
                _state.value = WatchTogetherState(
                    error = outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        ?: "一起看连接失败",
                )
                return
            }

            reconnectAttempt++
            _state.update { it.copy(connecting = false, reconnecting = true, error = null) }
            delay(backoffDelayMs(reconnectAttempt))
        }
    }

    private suspend fun runSession() {
        val url = pendingUrl ?: return
        try {
            client.webSocket(urlString = url) {
                currentSession = this
                send(
                    json.encodeToString(
                        WatchWireMessage.serializer(),
                        WatchWireMessage(
                            type = "hello",
                            clientId = preferences.clientId,
                            name = pendingName,
                            roomCode = pendingRoomCode,
                            mediaKey = pendingMediaKey,
                        ),
                    ),
                )
                val pingJob = launch {
                    while (isActive) {
                        val sentAt = System.currentTimeMillis()
                        clock.recordPingSent(sentAt)
                        runCatching {
                            send(
                                json.encodeToString(
                                    WatchWireMessage.serializer(),
                                    WatchWireMessage(type = "ping", clientSentAtMs = sentAt),
                                ),
                            )
                        }
                        delay(PING_INTERVAL_MS)
                    }
                }
                try {
                    var welcomedThisAttempt = false
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val receivedAt = System.currentTimeMillis()
                        val wire = runCatching {
                            json.decodeFromString(WatchWireMessage.serializer(), frame.readText())
                        }.getOrNull() ?: continue

                        wire.serverAtMs?.let { serverAtMs ->
                            // Every server message is timestamped, but only `pong` carries
                            // the matching `clientSentAtMs` needed to correct for one-way
                            // latency — anything else can't be turned into an offset sample.
                            if (wire.type == "pong") {
                                wire.clientSentAtMs?.let { sentAt ->
                                    clock.recordPong(sentAt, serverAtMs, receivedAt)
                                }
                            }
                        }

                        when (wire.type) {
                            "welcome", "roomUpdate", "sync" -> {
                                if (wire.type == "welcome") {
                                    welcomedThisAttempt = true
                                    everWelcomed = true
                                    reconnectAttempt = 0
                                }
                                applyRoomSnapshot(wire)
                            }
                            "controlRequested" -> {
                                val requesterId = wire.clientId ?: continue
                                _state.update {
                                    if (!it.isHost) {
                                        it
                                    } else {
                                        it.copy(
                                            controlRequest = ControlRequest(
                                                clientId = requesterId,
                                                name = wire.name?.takeIf { name -> name.isNotBlank() }
                                                    ?: "访客",
                                            ),
                                        )
                                    }
                                }
                            }
                            "controlDenied" -> {
                                _state.update {
                                    it.copy(controlRequested = false, syncWarning = "房主暂时保留控制权")
                                }
                            }
                            "error" -> {
                                if (!welcomedThisAttempt) {
                                    throw RoomUnavailableException(wire.message ?: "房间不存在或已关闭")
                                }
                                _state.update { it.copy(error = wire.message ?: "一起看服务返回错误") }
                            }
                        }
                    }
                } finally {
                    pingJob.cancel()
                }
            }
        } finally {
            currentSession = null
        }
    }

    private fun applyRoomSnapshot(wire: WatchWireMessage) {
        _state.update { current ->
            val isHost = wire.isHost ?: current.isHost
            // Any change of hands settles every outstanding negotiation: a granted request
            // has been answered by definition, and a request inherited from a previous host
            // was never this device's to answer.
            val handedOver = isHost != current.isHost
            current.copy(
                connecting = false,
                connected = true,
                reconnecting = false,
                roomCode = wire.roomCode ?: current.roomCode,
                isHost = isHost,
                participantCount = wire.participantCount ?: current.participantCount,
                mediaKey = wire.mediaKey ?: current.mediaKey,
                error = null,
                syncWarning = if (handedOver) null else current.syncWarning,
                controlRequest = if (handedOver) null else current.controlRequest,
                controlRequested = if (handedOver) false else current.controlRequested,
            )
        }
        val mediaKey = wire.mediaKey ?: return
        val positionMs = wire.positionMs ?: return
        val paused = wire.paused ?: return
        val seq = wire.seq ?: return
        val anchorAtMs = wire.anchorAtMs ?: return
        val rate = wire.rate?.takeIf { it > 0f } ?: 1f
        // Idempotent by seq: a reordered or duplicate delivery (retransmit races, or a
        // `roomUpdate` arriving interleaved with a `sync`) never moves the timeline
        // backwards. Membership fields above are applied unconditionally regardless — they
        // have no seq of their own and a `roomUpdate` carries the *unchanged* timeline seq
        // whenever only participant count changed.
        val current = _timeline.value
        if (current != null && seq <= current.seq) return
        _timeline.value = WatchTimeline(mediaKey, positionMs, anchorAtMs, rate, paused, seq)
    }

    private fun send(message: WatchWireMessage) {
        scope.launch {
            sendMutex.withLock {
                val active = currentSession ?: return@withLock
                runCatching {
                    active.send(json.encodeToString(WatchWireMessage.serializer(), message))
                }
            }
        }
    }

    private companion object {
        const val PING_INTERVAL_MS = 8_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L

        /** Exponential backoff capped at [MAX_BACKOFF_MS], with up to 20% jitter so a whole
         *  room full of guests dropped by the same outage doesn't all hammer the server on
         *  the same tick once it comes back. */
        fun backoffDelayMs(attempt: Int): Long {
            val exponent = (attempt - 1).coerceIn(0, 5)
            val capped = (BASE_BACKOFF_MS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS)
            val jitter = (capped * 0.2 * Random.nextDouble()).toLong()
            return capped + jitter
        }
    }
}

private fun String.toWebSocketUrl(): String? {
    val normalized = trim().trimEnd('/')
    if (normalized.isEmpty()) return null
    val websocket = when {
        normalized.startsWith("ws://") || normalized.startsWith("wss://") -> normalized
        normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}"
        normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}"
        else -> return null
    }
    return if (websocket.endsWith("/watch")) websocket else "$websocket/watch"
}
