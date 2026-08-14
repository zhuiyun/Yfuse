package com.yfuse.core.sync

import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.embyHttpEngine
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWireParticipant
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * One reaction, alive only long enough to float up the screen.
 *
 * The set is closed and mirrored on the server, which relays nothing it did not choose:
 * reactions are broadcast to a whole room unmoderated, so arbitrary text has no business
 * on this channel.
 */
enum class WatchReaction(
    val emoji: String,
) {
    Laugh("😂"),
    Wow("😮"),
    Love("😍"),
    Cry("😭"),
    Clap("👏"),
    Fire("🔥"),
    Think("🤔"),
    Dead("💀"),
    ;

    companion object {
        fun fromWire(value: String?): WatchReaction? = entries.firstOrNull { it.emoji == value }
    }
}

/** A reaction that has arrived and not yet drifted off the top of the screen. */
data class WatchReactionBurst(
    /** Monotonic per-client, so the overlay can key each bubble. */
    val id: Long,
    val reaction: WatchReaction,
    val name: String,
    val isMine: Boolean,
)

/** A guest asking the host for the timeline. Surfaced to the host so it can answer. */
data class ControlRequest(
    val clientId: String,
    val name: String,
)

enum class WatchControlMode(
    val wireValue: String,
    val label: String,
) {
    HostOnly("hostOnly", "仅房主"),
    Everyone("everyone", "共同控制"),
    Moderators("moderators", "指定管理员"),
    ;

    companion object {
        fun fromWire(value: String?): WatchControlMode = entries.firstOrNull { it.wireValue == value } ?: HostOnly
    }
}

enum class ChatDeliveryState {
    Sent,
    Pending,
    Failed,
}

enum class WatchNetworkQuality(
    val label: String,
) {
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
        get() =
            when {
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
    val withoutOptimistic =
        incoming.clientMessageId?.let { messageId ->
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
    /** False until a compatible server explicitly advertises the reaction wire message. */
    val reactionsSupported: Boolean = false,
    /**
     * Reactions still in flight up the screen. Bounded because a room that spams them
     * must not be able to grow this without limit; the overlay drops each one itself once
     * its bubble has finished.
     */
    val reactions: List<WatchReactionBurst> = emptyList(),
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

internal const val WATCH_CAPABILITY_REACTIONS = WatchProtocol.CAPABILITY_REACTIONS

internal fun supportsWatchReactions(capabilities: List<String>?): Boolean =
    WATCH_CAPABILITY_REACTIONS in capabilities.orEmpty()

internal fun WatchTogetherState.canSendReaction(): Boolean = connected && !reconnecting && reactionsSupported

internal data class WatchChatValidation(
    val text: String,
    val error: String?,
)

/** Shared by the sender and sticker tests, so the tray cannot drift past the real wire limit. */
internal fun validateWatchChat(raw: String): WatchChatValidation {
    val text = raw.trim()
    val error =
        when {
            text.isEmpty() -> "请输入消息"
            !WatchProtocol.isValidChat(text) && text.encodeToByteArray().size > MAX_WATCH_CHAT_BYTES ->
                "消息内容过长"
            !WatchProtocol.isValidChat(text) ->
                "每条消息最多 $MAX_WATCH_CHAT_GRAPHEMES 字"
            else -> null
        }
    return WatchChatValidation(text, error)
}

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

private class RoomUnavailableException(
    message: String,
) : Exception(message)

private class AccountRequiredForWatchException : Exception("请先登录 Yfuse 账号后使用一起看")

private class WatchAuthenticationException : Exception("一起看登录状态已失效")

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
    fun startPing(): Long =
        synchronized(lock) {
            val pingId = ++nextPingId
            if (inFlight.size > MAX_IN_FLIGHT) {
                // A dropped socket leaves its unanswered pings behind; they are never useful again.
                inFlight.clear()
            }
            inFlight[pingId] = TimeSource.Monotonic.markNow()
            pingId
        }

    fun recordPong(
        pingId: Long,
        serverAtMs: Long,
    ): Long? {
        val mark = synchronized(lock) { inFlight.remove(pingId) } ?: return null
        val rtt = mark.elapsedNow().inWholeMilliseconds
        if (rtt < 0 || rtt > MAX_ACCEPTABLE_RTT_MS) return null
        val sample =
            ServerSample(
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

    fun latencyMs(): Long? =
        synchronized(lock) {
            if (rttSamples.isEmpty()) null else rttSamples.sorted()[rttSamples.size / 2]
        }

    fun serverNow(): Long =
        synchronized(lock) {
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

private data class QueuedWatchMessage<Owner : Any, Message>(
    val owner: Owner,
    val message: Message,
    val onResult: ((Boolean) -> Unit)? = null,
)

/**
 * A bounded, single-consumer outbox. The owner is captured when a message is admitted, and is
 * checked again immediately before sending, so work queued for a disconnected socket can never
 * migrate to its replacement. Successfully admitted messages are consumed in FIFO order.
 */
internal class WatchOutgoingQueue<Owner : Any, Message>(
    scope: CoroutineScope,
    capacity: Int,
    private val isCurrentOwner: (Owner) -> Boolean,
    private val sender: suspend (Owner, Message) -> Boolean,
) {
    private val messages = Channel<QueuedWatchMessage<Owner, Message>>(capacity = capacity)

    init {
        require(capacity > 0) { "Watch outgoing queue capacity must be positive" }
        scope.launch {
            for (queued in messages) {
                val sent =
                    if (!isCurrentOwner(queued.owner)) {
                        false
                    } else {
                        runCatching { sender(queued.owner, queued.message) }.getOrDefault(false)
                    }
                runCatching { queued.onResult?.invoke(sent) }
            }
        }
    }

    /** Returns false immediately when bounded capacity has been exhausted. */
    fun tryEnqueue(
        owner: Owner,
        message: Message,
        onResult: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val accepted = messages.trySend(QueuedWatchMessage(owner, message, onResult)).isSuccess
        if (!accepted) runCatching { onResult?.invoke(false) }
        return accepted
    }
}

/**
 * Tracks the currently armed ACK deadline for each optimistic chat row. Re-arming the same
 * correlation id invalidates the older timer, which is essential when a failed message is retried.
 */
internal class WatchChatAckTimeouts(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val onTimeout: (String) -> Unit,
) {
    private val lock = Any()
    private val attempts = mutableMapOf<String, Long>()
    private var nextAttempt = 0L

    init {
        require(timeoutMs >= 0L) { "Chat ACK timeout must not be negative" }
    }

    /** Starts the deadline only after the corresponding frame has actually been sent. */
    fun arm(clientMessageId: String) {
        val attempt =
            synchronized(lock) {
                (++nextAttempt).also { attempts[clientMessageId] = it }
            }
        scope.launch {
            delay(timeoutMs)
            val expired =
                synchronized(lock) {
                    if (attempts[clientMessageId] != attempt) {
                        false
                    } else {
                        attempts.remove(clientMessageId)
                        true
                    }
                }
            if (expired) onTimeout(clientMessageId)
        }
    }

    fun complete(clientMessageId: String) {
        synchronized(lock) { attempts.remove(clientMessageId) }
    }
}

class WatchTogetherClient(
    private val preferences: WatchTogetherPreferences,
    private val accountTokens: AccountAccessTokenSource,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val client = HttpClient(embyHttpEngine()) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionOwnership = WatchConnectionOwnership<DefaultClientWebSocketSession>()

    /** Local, monotonic; only ever used to key a bubble in the overlay. */
    private var reactionSequence = 0L
    private val clock = ClockSync()

    private var connectionJob: Job? = null
    private var currentSession: DefaultClientWebSocketSession? = null

    // Parameters for the room currently being held, replayed on every reconnect attempt.
    private var pendingUrl: String? = null
    private var pendingRoomCode: String? = null
    private var pendingMediaKey: String? = null
    private var pendingName: String = ""
    private var pendingAvatarId: Int = 0

    /** Room-scoped secrets; intentionally memory-only and cleared on an explicit new room. */
    private var pendingResumeCapability: String? = null
    private var pendingHostCapability: String? = null
    private var localPlaybackStatus = LocalPlaybackStatus()
    private var lastSentPlaybackStatus: WatchWireMessage? = null
    private var nextLocalChatId = -1L

    /** True once any attempt for the current room has been welcomed. Failures before this
     *  point are the user's initial attempt going wrong (bad address, room doesn't exist) —
     *  surfaced once, not retried. Failures after it are a live room dropping a connection,
     *  which retries until it succeeds, the caller [leave]s, or the bounds below are hit. */
    private var everWelcomed = false
    private var reconnectAttempt = 0
    private var authenticationRetryUsed = false

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

    private val outgoingMessages =
        WatchOutgoingQueue<DefaultClientWebSocketSession, WatchWireMessage>(
            scope = scope,
            capacity = WATCH_OUTGOING_QUEUE_CAPACITY,
            isCurrentOwner = { sessionOwnership.current() === it },
            sender = { session, message ->
                runCatching {
                    session.send(
                        json.encodeToString(
                            WatchWireMessage.serializer(),
                            message,
                        ),
                    )
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
                }.isSuccess
            },
        )
    private val chatAckTimeouts =
        WatchChatAckTimeouts(
            scope = scope,
            timeoutMs = CHAT_ACK_TIMEOUT_MS,
            onTimeout = { markChatFailed(it, "消息发送超时，请点击重试") },
        )

    init {
        scope.launch {
            var hadAccountSession = false
            accountTokens.sessionAvailable.collect { available ->
                if (available) {
                    hadAccountSession = true
                } else if (hadAccountSession) {
                    hadAccountSession = false
                    invalidateAccountSession()
                }
            }
        }
    }

    /** Best estimate of the server's clock right now, for projecting [timeline] forward. */
    fun estimatedServerNow(): Long = clock.serverNow()

    fun createRoom(
        endpoint: String,
        mediaKey: String,
        name: String? = null,
    ) {
        start(endpoint, roomCode = null, mediaKey = mediaKey, name = name)
    }

    fun joinRoom(
        endpoint: String,
        roomCode: String,
        mediaKey: String,
        name: String? = null,
    ) {
        start(endpoint, roomCode.uppercase(), mediaKey, name)
    }

    /**
     * Legacy invite entry point. Protocol v5 still parses old links so it can explain why a
     * third-party relay is unsupported, but [start] accepts only the official account-service
     * base and therefore never forwards an account token to the endpoint supplied by a link.
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
        if (!send(WatchWireMessage(type = "requestControl"))) {
            _state.update { it.copy(controlRequested = false) }
        }
    }

    fun setControlMode(mode: WatchControlMode) {
        if (!_state.value.isHost) return
        send(WatchWireMessage(type = "setControlMode", controlMode = mode.wireValue))
    }

    fun setModerator(
        clientId: String,
        enabled: Boolean,
    ) {
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
        if (send(WatchWireMessage(type = "kickParticipant", targetClientId = clientId))) {
            _state.update {
                if (it.controlRequest?.clientId == clientId) it.copy(controlRequest = null) else it
            }
        }
    }

    /** Host-only: hand the timeline to the participant that asked for it. */
    fun grantControl(clientId: String) {
        if (!_state.value.isHost) return
        if (send(WatchWireMessage(type = "grantControl", targetClientId = clientId))) {
            _state.update { it.copy(controlRequest = null) }
        }
    }

    /** Host-only: refuse a pending request, telling the asker rather than just ignoring it. */
    fun denyControl(clientId: String) {
        if (!_state.value.isHost) return
        if (send(WatchWireMessage(type = "denyControl", targetClientId = clientId))) {
            _state.update { it.copy(controlRequest = null) }
        }
    }

    /** Updates the identity used by this room and by every future reconnect. */
    fun updateProfile(
        name: String,
        avatarId: Int,
    ) {
        pendingName = name
        pendingAvatarId = avatarId
        if (_state.value.connected) {
            send(WatchWireMessage(type = "updateProfile", name = name, avatarId = avatarId))
        }
    }

    /**
     * Sends one reaction and shows it locally straight away.
     *
     * The server echoes it back like everyone else's, and that echo is ignored for our own
     * client id — a reaction that waited for a round trip would lag the tap that caused it,
     * which is the one thing this feature cannot afford.
     */
    fun sendReaction(reaction: WatchReaction): Boolean {
        val state = _state.value
        if (!state.canSendReaction()) return false
        val myName =
            state.participants
                .firstOrNull { it.isSelf }
                ?.name
                .orEmpty()
        if (!send(WatchWireMessage(type = "reaction", reaction = reaction.emoji))) return false
        pushReaction(reaction, myName, isMine = true)
        return true
    }

    /** Drops a bubble once it has floated off, so [WatchTogetherState.reactions] stays small. */
    fun clearReaction(id: Long) {
        _state.update { current ->
            if (current.reactions.none { it.id == id }) {
                current
            } else {
                current.copy(reactions = current.reactions.filterNot { it.id == id })
            }
        }
    }

    private fun pushReaction(
        reaction: WatchReaction,
        name: String,
        isMine: Boolean,
    ) {
        val burst =
            WatchReactionBurst(
                id = ++reactionSequence,
                reaction = reaction,
                name = name,
                isMine = isMine,
            )
        _state.update { current ->
            current.copy(
                reactions = (current.reactions + burst).takeLast(MAX_LIVE_REACTIONS),
            )
        }
    }

    fun sendChat(raw: String): Boolean {
        val state = _state.value
        if (!state.connected) return false
        if (state.reconnecting) {
            _state.update { it.copy(chatError = "正在重连，连接恢复后再发送") }
            return false
        }
        val validation = validateWatchChat(raw)
        if (validation.error != null) {
            _state.update { it.copy(chatError = validation.error) }
            return false
        }
        val text = validation.text
        val clientMessageId = newClientMessageId()
        val pending =
            WatchChatMessage(
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
        val message =
            _state.value.chatMessages.firstOrNull {
                it.clientMessageId == clientMessageId && it.deliveryState == ChatDeliveryState.Failed
            } ?: return
        _state.update { state ->
            state.copy(
                chatMessages =
                    state.chatMessages.map {
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
        localPlaybackStatus =
            LocalPlaybackStatus(
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
    fun publishTimeline(
        mediaKey: String,
        positionMs: Long,
        paused: Boolean,
        rate: Float = 1f,
    ) {
        if (!_state.value.canControl) return
        if (!WatchProtocol.isValidMediaKey(mediaKey) ||
            !WatchProtocol.isValidTimeline(positionMs, paused, rate)
        ) {
            _state.update { it.copy(error = "播放时间线参数无效") }
            return
        }
        val current = _timeline.value
        _timeline.value =
            WatchTimeline(
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
        val (job, session) =
            synchronized(this) {
                sessionOwnership.advance()
                val captured = connectionJob to currentSession
                connectionJob = null
                currentSession = null
                captured
            }
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

    private fun start(
        endpoint: String,
        roomCode: String?,
        mediaKey: String,
        name: String?,
    ) {
        if (!WatchTogetherPreferences.isOfficialEndpoint(endpoint) || !accountTokens.trusts(endpoint)) {
            _state.value =
                WatchTogetherState(
                    error = "一起看协议 v5 仅支持 Yfuse 账号服务的官方 HTTPS 地址",
                )
            return
        }
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
        authenticationRetryUsed = false
        reconnectingSinceEpochMs = null
        localPlaybackStatus = LocalPlaybackStatus()
        lastSentPlaybackStatus = null
        nextLocalChatId = -1L
        pendingUrl = url
        pendingRoomCode = roomCode
        pendingMediaKey = mediaKey
        pendingResumeCapability = null
        pendingHostCapability = null
        pendingName = name ?: preferences.nickname.value
        pendingAvatarId = preferences.avatarId.value
        _state.value = WatchTogetherState(connecting = true)
        _timeline.value = null
        AppLog.info(
            category = "watch_together",
            event = "connection_started",
            message = "Watch-together connection started",
            attributes =
                mapOf(
                    "role" to if (roomCode == null) "host" else "guest",
                    "secure" to url.startsWith("wss://").toString(),
                ),
        )
        val generation = sessionOwnership.advance()
        connectionJob = scope.launch { connectionLoop(generation) }
    }

    /** Runs sessions back-to-back until [leave] cancels this coroutine. Each pass is one
     *  connection attempt; how a failure is handled depends on whether *this room* has ever
     *  been successfully entered (see [everWelcomed]). */
    private suspend fun connectionLoop(generation: Long) {
        while (currentCoroutineContext().isActive && isCurrentGeneration(generation)) {
            val outcome = runCatching { runSession(generation) }
            if (!currentCoroutineContext().isActive) return

            val failure = outcome.exceptionOrNull()
            if (failure != null && failure.isWatchAuthenticationFailure()) {
                val endpoint = pendingUrl?.substringBeforeLast("/watch")
                val refreshed =
                    if (!authenticationRetryUsed && endpoint != null) {
                        authenticationRetryUsed = true
                        runCatching { accountTokens.refreshAccessTokenFor(endpoint) }.getOrNull()
                    } else {
                        null
                    }
                if (refreshed != null) continue
                invalidateAccountSession()
                return
            }

            val roomGone = failure is RoomUnavailableException
            if (roomGone || !everWelcomed) {
                AppLog.error(
                    category = "watch_together",
                    event = if (roomGone) "room_unavailable" else "initial_connection_failed",
                    message = "Watch-together connection could not enter a room",
                    throwable = outcome.exceptionOrNull(),
                    attributes = mapOf("roomGone" to roomGone.toString()),
                )
                _state.value =
                    WatchTogetherState(
                        error =
                            outcome.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                                ?: "一起看连接失败",
                    )
                return
            }

            reconnectAttempt++
            val since =
                reconnectingSinceEpochMs
                    ?: System.currentTimeMillis().also { reconnectingSinceEpochMs = it }
            val offlineMs = (System.currentTimeMillis() - since).coerceAtLeast(0L)
            if (reconnectAttempt > MAX_RECONNECT_ATTEMPTS || offlineMs > MAX_RECONNECT_WINDOW_MS) {
                AppLog.warning(
                    category = "watch_together",
                    event = "reconnect_abandoned",
                    message = "Watch-together reconnection gave up; room released",
                    throwable = outcome.exceptionOrNull(),
                    attributes =
                        mapOf(
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
                attributes =
                    mapOf(
                        "attempt" to reconnectAttempt.toString(),
                        "retryDelayMs" to retryDelayMs.toString(),
                        "offlineMs" to offlineMs.toString(),
                    ),
            )
            _state.update { it.copy(connecting = false, reconnecting = true, error = null) }
            delay(retryDelayMs)
        }
    }

    private suspend fun runSession(generation: Long) {
        val url = pendingUrl ?: return
        val endpoint = url.substringBeforeLast("/watch")
        val accessToken =
            accountTokens.validAccessTokenFor(endpoint)
                ?: throw AccountRequiredForWatchException()
        try {
            client.webSocket(
                urlString = url,
                request = { bearerAuth(accessToken) },
            ) {
                if (!claimSession(generation, this)) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "superseded"))
                    return@webSocket
                }
                send(
                    json.encodeToString(
                        WatchWireMessage.serializer(),
                        WatchWireMessage(
                            type = "hello",
                            protocolVersion = WatchProtocol.VERSION,
                            clientId = preferences.clientId,
                            name = pendingName,
                            avatarId = pendingAvatarId,
                            roomCode = pendingRoomCode,
                            resumeCapability = pendingResumeCapability,
                            hostCapability = pendingHostCapability,
                            mediaKey = pendingMediaKey,
                        ),
                    ),
                )
                val pingJob =
                    launch {
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
                        val decoded =
                            runCatching {
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
                                    if (serverProtocol != WatchProtocol.VERSION ||
                                        WatchProtocol.CAPABILITY_AUTHENTICATED_RESUME !in
                                        wire.capabilities.orEmpty() ||
                                        WatchProtocol.CAPABILITY_HOST_CREDENTIAL !in
                                        wire.capabilities.orEmpty() ||
                                        WatchProtocol.CAPABILITY_STRICT_VALIDATION !in
                                        wire.capabilities.orEmpty()
                                    ) {
                                        val detail =
                                            if (serverProtocol == null ||
                                                serverProtocol < WatchProtocol.VERSION
                                            ) {
                                                "一起看服务器版本过旧，请先更新服务器"
                                            } else {
                                                "当前 App 版本过旧，请先更新 App"
                                            }
                                        throw RoomUnavailableException(detail)
                                    }
                                    wire.resumeCapability?.let { pendingResumeCapability = it }
                                    wire.hostCapability?.let { pendingHostCapability = it }
                                    welcomedThisAttempt = true
                                    everWelcomed = true
                                    authenticationRetryUsed = false
                                    reconnectAttempt = 0
                                    reconnectingSinceEpochMs = null
                                    AppLog.info(
                                        category = "watch_together",
                                        event = "room_joined",
                                        message = "Watch-together room joined",
                                        attributes =
                                            mapOf(
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
                                            controlRequest =
                                                ControlRequest(
                                                    clientId = requesterId,
                                                    name =
                                                        wire.name?.takeIf { name -> name.isNotBlank() }
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
                            "hostCapabilityGranted" -> {
                                val capability = wire.hostCapability
                                if (!WatchProtocol.isValidCapability(capability)) {
                                    throw RoomUnavailableException("服务器下发的主持凭据无效")
                                }
                                pendingHostCapability = capability
                            }
                            "kicked" -> {
                                throw RoomUnavailableException(
                                    wire.message ?: "你已被房主移出当前房间",
                                )
                            }
                            "chat" -> {
                                val chat = wire.chat?.toDomain() ?: continue
                                if (chat.isMine) {
                                    chat.clientMessageId?.let(chatAckTimeouts::complete)
                                }
                                _state.update { current ->
                                    val merged =
                                        mergeIncomingWatchChat(
                                            messages = current.chatMessages,
                                            incoming = chat,
                                            maxHistory = MAX_CHAT_HISTORY,
                                        )
                                    if (merged === current.chatMessages) {
                                        current
                                    } else {
                                        current.copy(
                                            chatMessages = merged,
                                            chatError = null,
                                        )
                                    }
                                }
                            }
                            "reaction" -> {
                                val reaction =
                                    WatchReaction.fromWire(wire.reaction)
                                        ?: continue
                                // Our own reaction was already shown when it was tapped;
                                // the echo would double it.
                                val mine =
                                    wire.clientId != null &&
                                        _state.value.participants
                                            .firstOrNull { it.isSelf }
                                            ?.clientId == wire.clientId
                                if (!mine) {
                                    pushReaction(
                                        reaction = reaction,
                                        name = wire.name.orEmpty(),
                                        isMine = false,
                                    )
                                }
                            }
                            "error" -> {
                                AppLog.warning(
                                    category = "watch_together",
                                    event = "server_error",
                                    message = "Watch-together server reported an error",
                                    attributes =
                                        mapOf(
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
                    val closed = closeReason.await()
                    if (closed?.code == CloseReason.Codes.VIOLATED_POLICY.code &&
                        closed.message in WATCH_AUTH_CLOSE_REASONS
                    ) {
                        throw WatchAuthenticationException()
                    }
                } finally {
                    pingJob.cancel()
                }
            }
        } finally {
            clearSessionIfOwned(generation)
        }
    }

    private fun Throwable.isWatchAuthenticationFailure(): Boolean {
        if (this is WatchAuthenticationException || this is AccountRequiredForWatchException) {
            return true
        }
        if (this is ResponseException && response.status.value == 401) return true
        val detail = message.orEmpty()
        return "401" in detail || detail.contains("unauthorized", ignoreCase = true)
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        synchronized(this) {
            sessionOwnership.isCurrent(generation)
        }

    private fun claimSession(
        generation: Long,
        session: DefaultClientWebSocketSession,
    ): Boolean =
        synchronized(this) {
            if (!sessionOwnership.claim(generation, session)) return@synchronized false
            currentSession = session
            true
        }

    private fun clearSessionIfOwned(generation: Long) {
        synchronized(this) {
            if (sessionOwnership.isCurrent(generation)) currentSession = null
            sessionOwnership.clear(generation)
        }
    }

    private fun invalidateAccountSession() {
        leaveInternal()
        pendingUrl = null
        pendingRoomCode = null
        pendingMediaKey = null
        pendingResumeCapability = null
        pendingHostCapability = null
        everWelcomed = false
        authenticationRetryUsed = false
        _state.value = WatchTogetherState(error = "登录已失效，请重新登录后使用一起看")
        _timeline.value = null
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
                attributes =
                    mapOf(
                        "participantCount" to it.toString(),
                        "previousCount" to previousCount.toString(),
                        "isHost" to (wire.isHost ?: _state.value.isHost).toString(),
                    ),
            )
        }
        _state.update { current ->
            val isHost = wire.isHost ?: current.isHost
            val canControl = wire.canControl ?: current.canControl
            val history = wire.chatHistory?.mapNotNull { it.toDomain() }
            val unconfirmed =
                if (history == null) {
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
                controlMode =
                    wire.controlMode?.let(WatchControlMode::fromWire)
                        ?: current.controlMode,
                participantCount = wire.participantCount ?: current.participantCount,
                participants =
                    wire.participants?.mapNotNull { it.toDomain() }
                        ?: current.participants,
                chatMessages =
                    history
                        ?.let { (it + unconfirmed).takeLast(MAX_CHAT_HISTORY) }
                        ?: current.chatMessages,
                chatError = if (wire.type == "welcome") null else current.chatError,
                reactionsSupported =
                    if (wire.type == "welcome") {
                        supportsWatchReactions(wire.capabilities)
                    } else {
                        current.reactionsSupported
                    },
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
        val rate = wire.rate
        if (!WatchProtocol.isValidMediaKey(mediaKey) ||
            !WatchProtocol.isValidTimeline(positionMs, paused, rate) ||
            !WatchProtocol.isValidSequence(seq) ||
            !WatchProtocol.isReasonableServerTime(anchorAtMs, estimatedServerNow())
        ) {
            AppLog.warning(
                category = "watch_together",
                event = "timeline_invalid",
                message = "Watch-together server sent an invalid timeline",
            )
            return
        }
        // Idempotent by seq: a reordered or duplicate delivery (retransmit races, or a
        // `roomUpdate` arriving interleaved with a `sync`) never moves the timeline
        // backwards. Membership fields above are applied unconditionally regardless — they
        // have no seq of their own and a `roomUpdate` carries the *unchanged* timeline seq
        // whenever only participant count changed.
        val current = _timeline.value
        if (current != null && seq <= current.seq) return
        _timeline.value = WatchTimeline(mediaKey, positionMs, anchorAtMs, rate!!, paused, seq)
    }

    private fun newClientMessageId(): String = "${preferences.clientId}-${Random.nextLong()}"

    private fun sendPendingChat(
        clientMessageId: String,
        text: String,
    ) {
        enqueue(
            message =
                WatchWireMessage(
                    type = "chat",
                    text = text,
                    clientMessageId = clientMessageId,
                ),
            onResult = { sent ->
                if (!sent) {
                    markChatFailed(clientMessageId, "消息发送失败，请点击重试")
                } else if (
                    _state.value.chatMessages.any {
                        it.clientMessageId == clientMessageId &&
                            it.deliveryState == ChatDeliveryState.Pending
                    }
                ) {
                    chatAckTimeouts.arm(clientMessageId)
                }
            },
        )
    }

    private fun markChatFailed(
        clientMessageId: String,
        error: String,
    ) {
        chatAckTimeouts.complete(clientMessageId)
        var changed = false
        _state.update { state ->
            val messages =
                state.chatMessages.map { message ->
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
        val message =
            WatchWireMessage(
                type = "playbackStatus",
                ready = status.ready,
                buffering = status.buffering,
                mediaAvailable = status.mediaAvailable,
                latencyMs = clock.latencyMs()?.let { (it / LATENCY_REPORT_BUCKET_MS) * LATENCY_REPORT_BUCKET_MS },
                syncDriftMs = status.syncDriftMs,
            )
        if (message == lastSentPlaybackStatus) return
        if (send(message)) lastSentPlaybackStatus = message
    }

    private fun send(message: WatchWireMessage): Boolean {
        val accepted = enqueue(message)
        if (!accepted && message.type != "sync") {
            AppLog.warning(
                category = "watch_together",
                event = "send_not_queued",
                message = "Watch-together message could not be queued",
                attributes = mapOf("messageType" to message.type),
            )
        }
        return accepted
    }

    private fun enqueue(
        message: WatchWireMessage,
        onResult: ((Boolean) -> Unit)? = null,
    ): Boolean {
        val session = sessionOwnership.current()
        if (session == null) {
            runCatching { onResult?.invoke(false) }
            return false
        }
        return outgoingMessages.tryEnqueue(session, message, onResult)
    }

    private fun WatchWireParticipant.toDomain(): WatchParticipant? {
        if (!WatchProtocol.isValidClientId(clientId) ||
            !WatchProtocol.isValidOptionalName(name) ||
            name.isEmpty() ||
            !WatchProtocol.isValidAvatarId(avatarId) ||
            latencyMs != null &&
            latencyMs !in 0L..WatchProtocol.MAX_LATENCY_MS ||
            syncDriftMs != null &&
            syncDriftMs !in
            -WatchProtocol.MAX_SYNC_DRIFT_MS..WatchProtocol.MAX_SYNC_DRIFT_MS
        ) {
            return null
        }
        return WatchParticipant(
            clientId = clientId,
            name = name,
            avatarId = avatarId,
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
    }

    private fun WatchWireChatMessage.toDomain(): WatchChatMessage? {
        if (id < 0L ||
            !WatchProtocol.isValidClientId(clientId) ||
            !WatchProtocol.isValidOptionalName(name) ||
            name.isEmpty() ||
            !WatchProtocol.isValidAvatarId(avatarId) ||
            !WatchProtocol.isValidChat(text) ||
            !WatchProtocol.isReasonableServerTime(sentAtMs, estimatedServerNow()) ||
            clientMessageId != null &&
            !WatchProtocol.isValidClientMessageId(clientMessageId)
        ) {
            return null
        }
        return WatchChatMessage(
            id = id,
            clientId = clientId,
            name = name,
            avatarId = avatarId,
            text = text,
            sentAtMs = sentAtMs,
            isMine = clientId == preferences.clientId,
            clientMessageId = clientMessageId,
            deliveryState = ChatDeliveryState.Sent,
        )
    }

    private companion object {
        const val PING_INTERVAL_MS = 8_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 20_000L
        const val MAX_CHAT_HISTORY = 50
        const val WATCH_OUTGOING_QUEUE_CAPACITY = 64

        /** Bubbles on screen at once. A room cannot grow this by reacting harder. */
        const val MAX_LIVE_REACTIONS = 12
        const val CHAT_ACK_TIMEOUT_MS = 8_000L

        /**
         * How hard a dropped room is chased before it is declared gone.
         *
         * Two bounds rather than one: the count catches a server that accepts and immediately
         * closes the socket, and the window catches a process that was frozen mid-backoff and
         * would otherwise wake up hours later still holding a room nobody is in.
         */
        const val MAX_RECONNECT_ATTEMPTS = 10
        val WATCH_AUTH_CLOSE_REASONS = setOf("account_auth_required", "account_auth_expired")
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

internal const val MAX_WATCH_CHAT_GRAPHEMES = WatchProtocol.MAX_CHAT_GRAPHEMES
internal const val MAX_WATCH_CHAT_BYTES = WatchProtocol.MAX_CHAT_BYTES

private fun String.toWebSocketUrl(): String? {
    val normalized = trim().trimEnd('/')
    if (normalized.isEmpty()) return null
    val websocket =
        when {
            normalized.startsWith("ws://") || normalized.startsWith("wss://") -> normalized
            normalized.startsWith("http://") -> "ws://${normalized.removePrefix("http://")}"
            normalized.startsWith("https://") -> "wss://${normalized.removePrefix("https://")}"
            else -> return null
        }
    return if (websocket.endsWith("/watch")) websocket else "$websocket/watch"
}
