package com.yfuse.core.sync

import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.embyHttpEngine
import com.yfuse.core.util.graphemeCount
import com.yfuse.core.util.withoutControlCharacters
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
    val protocolVersion: Int? = null,
    val clientId: String? = null,
    val name: String? = null,
    val avatarId: Int? = null,
    val roomCode: String? = null,
    val isHost: Boolean? = null,
    val canControl: Boolean? = null,
    val controlMode: String? = null,
    val moderator: Boolean? = null,
    val participantCount: Int? = null,
    val participants: List<WatchWireParticipant>? = null,
    val ready: Boolean? = null,
    val buffering: Boolean? = null,
    val mediaAvailable: Boolean? = null,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    val mediaKey: String? = null,
    val positionMs: Long? = null,
    val paused: Boolean? = null,
    val rate: Float? = null,
    val seq: Long? = null,
    val anchorAtMs: Long? = null,
    val serverAtMs: Long? = null,
    val clientSentAtMs: Long? = null,
    val targetClientId: String? = null,
    val text: String? = null,
    val clientMessageId: String? = null,
    val chat: WatchWireChatMessage? = null,
    val chatHistory: List<WatchWireChatMessage>? = null,
    val message: String? = null,
    val errorCode: String? = null,
)

@Serializable
private data class WatchWireParticipant(
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
private data class WatchWireChatMessage(
    val id: Long,
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val text: String,
    val sentAtMs: Long,
    val clientMessageId: String? = null,
)

/** A guest asking the host for the timeline. Surfaced to the host so it can answer. */
data class ControlRequest(val clientId: String, val name: String)

enum class WatchControlMode(val wireValue: String, val label: String) {
    HostOnly("hostOnly", "仅房主"),
    Everyone("everyone", "共同控制"),
    Moderators("moderators", "指定管理员"),
    ;

    companion object {
        fun fromWire(value: String?): WatchControlMode =
            entries.firstOrNull { it.wireValue == value } ?: HostOnly
    }
}

enum class ChatDeliveryState {
    Sent,
    Pending,
    Failed,
}

enum class WatchNetworkQuality(val label: String) {
    Excellent("网络优"),
    Fair("网络一般"),
    Poor("网络较差"),
    Unknown("检测中"),
}

data class WatchParticipant(
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val isHost: Boolean,
    val isSelf: Boolean,
    val statusKnown: Boolean = false,
    val ready: Boolean = false,
    val buffering: Boolean = false,
    val mediaAvailable: Boolean = true,
    val latencyMs: Long? = null,
    val syncDriftMs: Long? = null,
    val canControl: Boolean = false,
    val isModerator: Boolean = false,
) {
    val playbackStatusLabel: String
        get() = when {
            !statusKnown -> "状态未知"
            !mediaAvailable -> "缺少影片"
            buffering -> "缓冲中"
            ready -> "已就绪"
            else -> "准备中"
        }

    val networkQuality: WatchNetworkQuality
        get() {
            val latency = latencyMs ?: return WatchNetworkQuality.Unknown
            val drift = abs(syncDriftMs ?: 0L)
            return when {
                buffering -> WatchNetworkQuality.Poor
                latency <= 120L && drift <= 300L -> WatchNetworkQuality.Excellent
                latency <= 350L && drift <= 1_000L -> WatchNetworkQuality.Fair
                else -> WatchNetworkQuality.Poor
            }
        }

    val networkStatusLabel: String
        get() {
            val latency = latencyMs ?: return WatchNetworkQuality.Unknown.label
            val drift = syncDriftMs?.let { " · 偏差${abs(it)}ms" }.orEmpty()
            return "${networkQuality.label} · ${latency}ms$drift"
        }
}

data class WatchChatMessage(
    val id: Long,
    val clientId: String,
    val name: String,
    val avatarId: Int,
    val text: String,
    val sentAtMs: Long,
    val isMine: Boolean,
    val clientMessageId: String? = null,
    val deliveryState: ChatDeliveryState = ChatDeliveryState.Sent,
)

/**
 * Reconciles an optimistic local chat row with the authoritative server echo. Retries can
 * also produce the same echo more than once, so both the client correlation id and the
 * final server id are treated idempotently.
 */
internal fun mergeIncomingWatchChat(
    messages: List<WatchChatMessage>,
    incoming: WatchChatMessage,
    maxHistory: Int,
): List<WatchChatMessage> {
    val withoutOptimistic = incoming.clientMessageId?.let { messageId ->
        messages.filterNot {
            it.clientId == incoming.clientId &&
                it.clientMessageId == messageId &&
                it.deliveryState != ChatDeliveryState.Sent
        }
    } ?: messages
    if (withoutOptimistic.any { it.id == incoming.id }) {
        return if (withoutOptimistic.size == messages.size) messages else withoutOptimistic
    }
    return (withoutOptimistic + incoming).takeLast(maxHistory)
}

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
    val canControl: Boolean = false,
    val controlMode: WatchControlMode = WatchControlMode.HostOnly,
    val participantCount: Int = 0,
    val participants: List<WatchParticipant> = emptyList(),
    val chatMessages: List<WatchChatMessage> = emptyList(),
    val chatError: String? = null,
    val mediaKey: String? = null,
    val error: String? = null,
    /**
     * Set by the player when the room's media matches nothing in the local queue. The room is
     * real and the socket is up; this device just can't follow the timeline. Kept apart from
     * [error] because a room snapshot clears that one on arrival, and this condition is
     * discovered locally rather than reported by the server.
     */
    val syncWarning: String? = null,
    val localMediaAvailable: Boolean = true,
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
 * Maps the server's epoch clock onto this process's monotonic clock from `ping`/`pong`
 * round trips. Comparing two device wall clocks directly — what v1 did — breaks when they
 * disagree, while retaining a wall-clock offset also breaks when Android applies an NTP
 * correction after the sample. A server epoch plus a monotonic mark survives both.
 *
 * Single-sample offset (classic NTP): the server's timestamp is assumed to correspond to
 * the midpoint of the round trip on *our* clock. A rolling median over the last few
 * samples smooths out any one slow round trip; samples with implausible RTT are dropped
 * outright rather than allowed to skew the median.
 */
private class ClockSync {
    private data class ServerSample(
        val serverAtArrivalMs: Long,
        val receivedAt: TimeSource.Monotonic.ValueTimeMark,
    )

    private val lock = Any()
    private val samples = ArrayDeque<ServerSample>()
    private val rttSamples = ArrayDeque<Long>()
    private val inFlight = HashMap<Long, TimeSource.Monotonic.ValueTimeMark>()
    private var nextPingId = 0L

    /** Returns an opaque correlation id (the wire keeps its legacy `clientSentAtMs` name). */
    fun startPing(): Long = synchronized(lock) {
        val pingId = ++nextPingId
        if (inFlight.size > MAX_IN_FLIGHT) {
            // A dropped socket leaves its unanswered pings behind; they are never useful again.
            inFlight.clear()
        }
        inFlight[pingId] = TimeSource.Monotonic.markNow()
        pingId
    }

    fun recordPong(pingId: Long, serverAtMs: Long): Long? {
        val mark = synchronized(lock) { inFlight.remove(pingId) } ?: return null
        val rtt = mark.elapsedNow().inWholeMilliseconds
        if (rtt < 0 || rtt > MAX_ACCEPTABLE_RTT_MS) return null
        val sample = ServerSample(
            // The server stamps the pong around the round-trip midpoint. Project it to the
            // receive instant, then advance only with monotonic elapsed time from here on.
            serverAtArrivalMs = serverAtMs + rtt / 2,
            receivedAt = TimeSource.Monotonic.markNow(),
        )
        synchronized(lock) {
            samples.addLast(sample)
            if (samples.size > MAX_SAMPLES) samples.removeFirst()
            rttSamples.addLast(rtt)
            if (rttSamples.size > MAX_SAMPLES) rttSamples.removeFirst()
        }
        return latencyMs()
    }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            rttSamples.clear()
            inFlight.clear()
        }
    }

    fun latencyMs(): Long? = synchronized(lock) {
        if (rttSamples.isEmpty()) null else rttSamples.sorted()[rttSamples.size / 2]
    }

    fun serverNow(): Long = synchronized(lock) {
        if (samples.isEmpty()) return@synchronized System.currentTimeMillis()
        samples
            .map { it.serverAtArrivalMs + it.receivedAt.elapsedNow().inWholeMilliseconds }
            .sorted()[samples.size / 2]
    }

    private companion object {
        const val MAX_SAMPLES = 7
        const val MAX_ACCEPTABLE_RTT_MS = 4_000L
        const val MAX_IN_FLIGHT = 16
    }
}

private data class LocalPlaybackStatus(
    val ready: Boolean = false,
    val buffering: Boolean = true,
    val mediaAvailable: Boolean = true,
    val syncDriftMs: Long? = null,
)

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
    private var pendingAvatarId: Int = 0
    private var localPlaybackStatus = LocalPlaybackStatus()
    private var lastSentPlaybackStatus: WatchWireMessage? = null
    private var nextLocalChatId = -1L

    /** True once any attempt for the current room has been welcomed. Failures before this
     *  point are the user's initial attempt going wrong (bad address, room doesn't exist) —
     *  surfaced once, not retried. Failures after it are a live room dropping a connection,
     *  which retries until it succeeds, the caller [leave]s, or the bounds below are hit. */
    private var everWelcomed = false
    private var reconnectAttempt = 0

    /**
     * When the current run of failures started, on the wall clock, or null while connected.
     *
     * The retry loop used to be unbounded, and it lives on a scope that outlives any screen.
     * A backgrounded process suspends mid-`delay` rather than being cancelled, so a room left
     * reconnecting was observed resuming and rejoining two and a half days later — on its
     * third attempt, having held a socket and its ping timer open across the whole gap. Wall
     * clock rather than a monotonic mark because that gap is exactly what has to be measured,
     * and a frozen process does not accumulate monotonic time.
     */
    private var reconnectingSinceEpochMs: Long? = null

    private val _state = MutableStateFlow(WatchTogetherState())
    val state: StateFlow<WatchTogetherState> = _state.asStateFlow()
    private val _timeline = MutableStateFlow<WatchTimeline?>(null)
    val timeline: StateFlow<WatchTimeline?> = _timeline.asStateFlow()

    /** Best estimate of the server's clock right now, for projecting [timeline] forward. */
    fun estimatedServerNow(): Long = clock.serverNow()

    fun createRoom(endpoint: String, mediaKey: String, name: String? = null) {
        start(endpoint, roomCode = null, mediaKey = mediaKey, name = name)
    }

    fun joinRoom(endpoint: String, roomCode: String, mediaKey: String, name: String? = null) {
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
        name: String? = null,
    ) {
        start(endpoint, roomCode.uppercase(), mediaKey, name)
    }

    /** Asks the host to hand over control. No-op for a host, or outside a room. */
    fun requestControl() {
        val state = _state.value
        if (!state.connected || state.canControl) return
        _state.update { it.copy(controlRequested = true) }
        send(WatchWireMessage(type = "requestControl"))
    }

    fun setControlMode(mode: WatchControlMode) {
        if (!_state.value.isHost) return
        send(WatchWireMessage(type = "setControlMode", controlMode = mode.wireValue))
    }

    fun setModerator(clientId: String, enabled: Boolean) {
        if (!_state.value.isHost) return
        send(
            WatchWireMessage(
                type = "setModerator",
                targetClientId = clientId,
                moderator = enabled,
            ),
        )
    }

    /** Host-only: remove a participant and prevent that client from rejoining this room. */
    fun kickParticipant(clientId: String) {
        val state = _state.value
        if (!state.isHost) return
        val target = state.participants.firstOrNull { it.clientId == clientId } ?: return
        if (target.isHost || target.isSelf) return
        _state.update {
            if (it.controlRequest?.clientId == clientId) it.copy(controlRequest = null) else it
        }
        send(WatchWireMessage(type = "kickParticipant", targetClientId = clientId))
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

    /** Updates the identity used by this room and by every future reconnect. */
    fun updateProfile(name: String, avatarId: Int) {
        pendingName = name
        pendingAvatarId = avatarId
        if (_state.value.connected) {
            send(WatchWireMessage(type = "updateProfile", name = name, avatarId = avatarId))
        }
    }

    fun sendChat(raw: String): Boolean {
        val state = _state.value
        if (!state.connected) return false
        if (state.reconnecting) {
            _state.update { it.copy(chatError = "正在重连，连接恢复后再发送") }
            return false
        }
        val text = raw.replace('\r', ' ')
            .replace('\n', ' ')
            .withoutControlCharacters()
            .trim()
        val error = when {
            text.isEmpty() -> "请输入消息"
            text.graphemeCount() > MAX_CHAT_GRAPHEMES -> "每条消息最多 30 字"
            text.encodeToByteArray().size > MAX_CHAT_BYTES -> "消息内容过长"
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(chatError = error) }
            return false
        }
        val clientMessageId = newClientMessageId()
        val pending = WatchChatMessage(
            id = nextLocalChatId--,
            clientId = preferences.clientId,
            name = pendingName,
            avatarId = pendingAvatarId,
            text = text,
            sentAtMs = System.currentTimeMillis(),
            isMine = true,
            clientMessageId = clientMessageId,
            deliveryState = ChatDeliveryState.Pending,
        )
        _state.update {
            it.copy(
                chatMessages = (it.chatMessages + pending).takeLast(MAX_CHAT_HISTORY),
                chatError = null,
            )
        }
        sendPendingChat(clientMessageId, text)
        return true
    }

    fun retryChat(clientMessageId: String) {
        val message = _state.value.chatMessages.firstOrNull {
            it.clientMessageId == clientMessageId && it.deliveryState == ChatDeliveryState.Failed
        } ?: return
        _state.update { state ->
            state.copy(
                chatMessages = state.chatMessages.map {
                    if (it.clientMessageId == clientMessageId) {
                        it.copy(deliveryState = ChatDeliveryState.Pending)
                    } else {
                        it
                    }
                },
                chatError = null,
            )
        }
        sendPendingChat(clientMessageId, message.text)
    }

    fun clearChatError() {
        _state.update { if (it.chatError == null) it else it.copy(chatError = null) }
    }

    /**
     * Reported by the player when the room's media matches nothing it can play. Kept out of
     * [WatchTogetherState.error] so an arriving room snapshot doesn't wipe it.
     */
    fun setSyncWarning(message: String?) {
        _state.update {
            if (it.syncWarning == message && it.localMediaAvailable == (message == null)) {
                it
            } else {
                it.copy(syncWarning = message, localMediaAvailable = message == null)
            }
        }
    }

    fun updatePlaybackStatus(
        ready: Boolean,
        buffering: Boolean,
        mediaAvailable: Boolean,
        syncDriftMs: Long? = localPlaybackStatus.syncDriftMs,
    ) {
        localPlaybackStatus = LocalPlaybackStatus(
            ready = ready && mediaAvailable && !buffering,
            buffering = buffering && mediaAvailable,
            mediaAvailable = mediaAvailable,
            syncDriftMs = syncDriftMs?.coerceIn(-30_000L, 30_000L),
        )
        sendPlaybackStatus()
    }

    fun updateSyncDrift(syncDriftMs: Long?) {
        val rounded = syncDriftMs?.let { (it / DRIFT_REPORT_BUCKET_MS) * DRIFT_REPORT_BUCKET_MS }
        if (localPlaybackStatus.syncDriftMs == rounded) return
        localPlaybackStatus = localPlaybackStatus.copy(syncDriftMs = rounded)
        sendPlaybackStatus()
    }

    /** Controller-only: publish a new anchor. Call this on every play/pause/seek/rate/media change
     *  — not on a timer — the room's position between calls is entirely extrapolated. */
    fun publishTimeline(mediaKey: String, positionMs: Long, paused: Boolean, rate: Float = 1f) {
        if (!_state.value.canControl) return
        val current = _timeline.value
        _timeline.value = WatchTimeline(
            mediaKey = mediaKey,
            anchorPositionMs = positionMs,
            anchorAtServerMs = estimatedServerNow(),
            rate = rate,
            paused = paused,
            seq = current?.seq ?: 0L,
        )
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
        if (_state.value.roomCode != null || _state.value.connected || _state.value.connecting) {
            AppLog.info(
                category = "watch_together",
                event = "room_left",
                message = "Left watch-together room",
                attributes = mapOf("wasHost" to _state.value.isHost.toString()),
            )
        }
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
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "leave"))
                }.onFailure {
                    AppLog.warning(
                        category = "watch_together",
                        event = "socket_close_failed",
                        message = "Failed to close watch-together socket cleanly",
                        throwable = it,
                    )
                }
            }
        }
    }

    private fun start(endpoint: String, roomCode: String?, mediaKey: String, name: String?) {
        val url = endpoint.toWebSocketUrl()
        if (url == null) {
            AppLog.warning(
                category = "watch_together",
                event = "endpoint_invalid",
                message = "Watch-together endpoint is invalid",
            )
            _state.value = WatchTogetherState(error = "一起看服务地址无效")
            return
        }
        leaveInternal()
        clock.reset()
        everWelcomed = false
        reconnectAttempt = 0
        reconnectingSinceEpochMs = null
        localPlaybackStatus = LocalPlaybackStatus()
        lastSentPlaybackStatus = null
        nextLocalChatId = -1L
        pendingUrl = url
        pendingRoomCode = roomCode
        pendingMediaKey = mediaKey
        pendingName = name ?: preferences.nickname.value
        pendingAvatarId = preferences.avatarId.value
        _state.value = WatchTogetherState(connecting = true)
        _timeline.value = null
        AppLog.info(
            category = "watch_together",
            event = "connection_started",
            message = "Watch-together connection started",
            attributes = mapOf(
                "role" to if (roomCode == null) "host" else "guest",
                "secure" to url.startsWith("wss://").toString(),
            ),
        )
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
                AppLog.error(
                    category = "watch_together",
                    event = if (roomGone) "room_unavailable" else "initial_connection_failed",
                    message = "Watch-together connection could not enter a room",
                    throwable = outcome.exceptionOrNull(),
                    attributes = mapOf("roomGone" to roomGone.toString()),
                )
                _state.value = WatchTogetherState(
                    error = outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                        ?: "一起看连接失败",
                )
                return
            }

            reconnectAttempt++
            val since = reconnectingSinceEpochMs
                ?: System.currentTimeMillis().also { reconnectingSinceEpochMs = it }
            val offlineMs = (System.currentTimeMillis() - since).coerceAtLeast(0L)
            if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS || offlineMs > MAX_RECONNECT_WINDOW_MS) {
                AppLog.warning(
                    category = "watch_together",
                    event = "reconnect_abandoned",
                    message = "Watch-together reconnection gave up; room released",
                    throwable = outcome.exceptionOrNull(),
                    attributes = mapOf(
                        "attempt" to reconnectAttempt.toString(),
                        "offlineMs" to offlineMs.toString(),
                    ),
                )
                _state.value = WatchTogetherState(error = "一起看连接已断开，请重新加入房间")
                _timeline.value = null
                return
            }
            val retryDelayMs = backoffDelayMs(reconnectAttempt)
            AppLog.warning(
                category = "watch_together",
                event = "connection_lost",
                message = "Watch-together connection lost; reconnect scheduled",
                throwable = outcome.exceptionOrNull(),
                attributes = mapOf(
                    "attempt" to reconnectAttempt.toString(),
                    "retryDelayMs" to retryDelayMs.toString(),
                    "offlineMs" to offlineMs.toString(),
                ),
            )
            _state.update { it.copy(connecting = false, reconnecting = true, error = null) }
            delay(retryDelayMs)
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
                            protocolVersion = WATCH_PROTOCOL_VERSION,
                            clientId = preferences.clientId,
                            name = pendingName,
                            avatarId = pendingAvatarId,
                            roomCode = pendingRoomCode,
                            mediaKey = pendingMediaKey,
                        ),
                    ),
                )
                val pingJob = launch {
                    while (isActive) {
                        val sentAt = clock.startPing()
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
                        val frameText = frame.readText()
                        val decoded = runCatching {
                            json.decodeFromString(WatchWireMessage.serializer(), frameText)
                        }
                        if (decoded.isFailure) {
                            AppLog.warning(
                                category = "watch_together",
                                event = "message_invalid",
                                message = "Watch-together server sent an invalid message",
                                throwable = decoded.exceptionOrNull(),
                                attributes = mapOf("frameChars" to frameText.length.toString()),
                            )
                            continue
                        }
                        val wire = decoded.getOrThrow()

                        wire.serverAtMs?.let { serverAtMs ->
                            // Every server message is timestamped, but only `pong` carries
                            // the matching `clientSentAtMs` needed to correct for one-way
                            // latency — anything else can't be turned into an offset sample.
                            if (wire.type == "pong") {
                                wire.clientSentAtMs?.let { sentAt ->
                                    if (clock.recordPong(sentAt, serverAtMs) != null) {
                                        sendPlaybackStatus()
                                    }
                                }
                            }
                        }

                        when (wire.type) {
                            "welcome", "roomUpdate", "sync" -> {
                                if (wire.type == "welcome") {
                                    val serverProtocol = wire.protocolVersion
                                    if (serverProtocol != WATCH_PROTOCOL_VERSION) {
                                        val detail = if (serverProtocol == null ||
                                            serverProtocol < WATCH_PROTOCOL_VERSION
                                        ) {
                                            "一起看服务器版本过旧，请先更新服务器"
                                        } else {
                                            "当前 App 版本过旧，请先更新 App"
                                        }
                                        throw RoomUnavailableException(detail)
                                    }
                                    welcomedThisAttempt = true
                                    everWelcomed = true
                                    reconnectAttempt = 0
                                    reconnectingSinceEpochMs = null
                                    AppLog.info(
                                        category = "watch_together",
                                        event = "room_joined",
                                        message = "Watch-together room joined",
                                        attributes = mapOf(
                                            "isHost" to (wire.isHost ?: false).toString(),
                                            "participantCount" to
                                                (wire.participantCount ?: 0).toString(),
                                        ),
                                    )
                                }
                                applyRoomSnapshot(wire)
                                if (wire.type == "welcome") {
                                    lastSentPlaybackStatus = null
                                    sendPlaybackStatus()
                                }
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
                            "kicked" -> {
                                throw RoomUnavailableException(
                                    wire.message ?: "你已被房主移出当前房间",
                                )
                            }
                            "chat" -> {
                                val chat = wire.chat?.toDomain() ?: continue
                                _state.update { current ->
                                    val merged = mergeIncomingWatchChat(
                                        messages = current.chatMessages,
                                        incoming = chat,
                                        maxHistory = MAX_CHAT_HISTORY,
                                    )
                                    if (merged === current.chatMessages) current else {
                                        current.copy(
                                            chatMessages = merged,
                                            chatError = null,
                                        )
                                    }
                                }
                            }
                            "error" -> {
                                AppLog.warning(
                                    category = "watch_together",
                                    event = "server_error",
                                    message = "Watch-together server reported an error",
                                    attributes = mapOf(
                                        "duringHandshake" to (!welcomedThisAttempt).toString(),
                                    ),
                                )
                                if (!welcomedThisAttempt) {
                                    throw RoomUnavailableException(wire.message ?: "房间不存在或已关闭")
                                }
                                if (wire.errorCode?.startsWith("chat_") == true) {
                                    wire.clientMessageId?.let { messageId ->
                                        markChatFailed(
                                            messageId,
                                            wire.message ?: "消息发送失败，请点击重试",
                                        )
                                    }
                                    _state.update {
                                        it.copy(chatError = wire.message ?: "消息发送失败")
                                    }
                                } else {
                                    _state.update {
                                        it.copy(error = wire.message ?: "一起看服务返回错误")
                                    }
                                }
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
        // Only `welcome` used to be logged, and a host is alone at welcome by definition — so
        // every host's diagnostics read `participantCount=1` for the whole session and there
        // was no way to tell a room nobody joined from one that filled up and emptied again.
        val previousCount = _state.value.participantCount
        wire.participantCount?.takeIf { it != previousCount && wire.type != "welcome" }?.let {
            AppLog.info(
                category = "watch_together",
                event = "participants_changed",
                message = "Watch-together room membership changed",
                attributes = mapOf(
                    "participantCount" to it.toString(),
                    "previousCount" to previousCount.toString(),
                    "isHost" to (wire.isHost ?: _state.value.isHost).toString(),
                ),
            )
        }
        _state.update { current ->
            val isHost = wire.isHost ?: current.isHost
            val canControl = wire.canControl ?: current.canControl
            val history = wire.chatHistory?.map { it.toDomain() }
            val unconfirmed = if (history == null) {
                emptyList()
            } else {
                current.chatMessages.filter { local ->
                    local.deliveryState != ChatDeliveryState.Sent &&
                        history.none {
                            it.clientId == local.clientId &&
                                it.clientMessageId == local.clientMessageId
                        }
                }
            }
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
                canControl = canControl,
                controlMode = wire.controlMode?.let(WatchControlMode::fromWire)
                    ?: current.controlMode,
                participantCount = wire.participantCount ?: current.participantCount,
                participants = wire.participants?.map { it.toDomain() } ?: current.participants,
                chatMessages = history
                    ?.let { (it + unconfirmed).takeLast(MAX_CHAT_HISTORY) }
                    ?: current.chatMessages,
                chatError = if (wire.type == "welcome") null else current.chatError,
                mediaKey = wire.mediaKey ?: current.mediaKey,
                error = null,
                syncWarning = if (handedOver) null else current.syncWarning,
                controlRequest = if (handedOver) null else current.controlRequest,
                controlRequested = if (handedOver || canControl) false else current.controlRequested,
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

    private fun newClientMessageId(): String =
        "${preferences.clientId}-${Random.nextLong()}"

    private fun sendPendingChat(clientMessageId: String, text: String) {
        scope.launch {
            val sent = sendMutex.withLock {
                val active = currentSession ?: return@withLock false
                runCatching {
                    active.send(
                        json.encodeToString(
                            WatchWireMessage.serializer(),
                            WatchWireMessage(
                                type = "chat",
                                text = text,
                                clientMessageId = clientMessageId,
                            ),
                        ),
                    )
                }.isSuccess
            }
            if (!sent) {
                markChatFailed(clientMessageId, "消息发送失败，请点击重试")
                return@launch
            }
            delay(CHAT_ACK_TIMEOUT_MS)
            markChatFailed(clientMessageId, "消息发送超时，请点击重试")
        }
    }

    private fun markChatFailed(clientMessageId: String, error: String) {
        var changed = false
        _state.update { state ->
            val messages = state.chatMessages.map { message ->
                if (
                    message.clientMessageId == clientMessageId &&
                    message.deliveryState == ChatDeliveryState.Pending
                ) {
                    changed = true
                    message.copy(deliveryState = ChatDeliveryState.Failed)
                } else {
                    message
                }
            }
            if (changed) state.copy(chatMessages = messages, chatError = error) else state
        }
    }

    private fun sendPlaybackStatus() {
        val state = _state.value
        if (!state.connected || state.reconnecting) return
        val status = localPlaybackStatus
        val message = WatchWireMessage(
            type = "playbackStatus",
            ready = status.ready,
            buffering = status.buffering,
            mediaAvailable = status.mediaAvailable,
            latencyMs = clock.latencyMs()?.let { (it / LATENCY_REPORT_BUCKET_MS) * LATENCY_REPORT_BUCKET_MS },
            syncDriftMs = status.syncDriftMs,
        )
        if (message == lastSentPlaybackStatus) return
        lastSentPlaybackStatus = message
        send(message)
    }

    private fun send(message: WatchWireMessage) {
        scope.launch {
            sendMutex.withLock {
                val active = currentSession ?: return@withLock
                runCatching {
                    active.send(json.encodeToString(WatchWireMessage.serializer(), message))
                }.onFailure {
                    if (message.type != "sync") {
                        AppLog.warning(
                            category = "watch_together",
                            event = "send_failed",
                            message = "Failed to send watch-together message",
                            throwable = it,
                            attributes = mapOf("messageType" to message.type),
                        )
                    }
                }
            }
        }
    }

    private fun WatchWireParticipant.toDomain(): WatchParticipant = WatchParticipant(
        clientId = clientId,
        name = name,
        avatarId = avatarId.coerceIn(0, WatchTogetherPreferences.AVATAR_COUNT - 1),
        isHost = isHost,
        isSelf = clientId == preferences.clientId,
        statusKnown = statusKnown,
        ready = ready,
        buffering = buffering,
        mediaAvailable = mediaAvailable,
        latencyMs = latencyMs,
        syncDriftMs = syncDriftMs,
        canControl = canControl,
        isModerator = isModerator,
    )

    private fun WatchWireChatMessage.toDomain(): WatchChatMessage = WatchChatMessage(
        id = id,
        clientId = clientId,
        name = name,
        avatarId = avatarId.coerceIn(0, WatchTogetherPreferences.AVATAR_COUNT - 1),
        text = text,
        sentAtMs = sentAtMs,
        isMine = clientId == preferences.clientId,
        clientMessageId = clientMessageId,
        deliveryState = ChatDeliveryState.Sent,
    )

    private companion object {
        const val PING_INTERVAL_MS = 8_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L
        const val MAX_CHAT_GRAPHEMES = 30
        const val MAX_CHAT_BYTES = 768
        const val MAX_CHAT_HISTORY = 50
        const val WATCH_PROTOCOL_VERSION = 3
        const val CHAT_ACK_TIMEOUT_MS = 8_000L

        /**
         * How hard a dropped room is chased before it is declared gone.
         *
         * Two bounds rather than one: the count catches a server that accepts and immediately
         * closes the socket, and the window catches a process that was frozen mid-backoff and
         * would otherwise wake up hours later still holding a room nobody is in.
         */
        const val MAX_RECONNECT_ATTEMPTS = 10
        const val MAX_RECONNECT_WINDOW_MS = 5 * 60 * 1000L
        const val LATENCY_REPORT_BUCKET_MS = 10L
        const val DRIFT_REPORT_BUCKET_MS = 50L

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
