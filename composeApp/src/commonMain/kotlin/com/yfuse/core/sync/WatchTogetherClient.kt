package com.yfuse.core.sync

import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.embyHttpEngine
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.client.HttpClient
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
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.TimeSource

/** Owns one watch-together room session and coordinates reconnect, timeline, chat and reactions. */
class WatchTogetherClient(
    private val preferences: WatchTogetherPreferences,
    private val accountTokens: AccountAccessTokenSource,
    private val resumeStore: WatchRoomResumeStore = WatchRoomResumeStore(null),
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val client = HttpClient(embyHttpEngine()) { install(WebSockets) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionOwnership = WatchConnectionOwnership<DefaultClientWebSocketSession>()

    private var reactionSequence = 0L
    private val clock = ClockSync()

    private var connectionJob: Job? = null
    private var currentSession: DefaultClientWebSocketSession? = null

    // Session parameters are written by UI callers and read on the connection coroutine;
    // each is a single reference or primitive, so volatile publication is what they need.
    // Status reports additionally compose several fields and go through [statusLock].
    @Volatile private var pendingUrl: String? = null

    @Volatile private var pendingRoomCode: String? = null

    @Volatile private var pendingMediaKey: String? = null

    @Volatile private var pendingName: String = ""

    @Volatile private var pendingAvatarId: Int = 0

    @Volatile private var pendingResumeCapability: String? = null

    @Volatile private var pendingHostCapability: String? = null
    private val statusLock = Any()

    @Volatile private var localPlaybackStatus = LocalPlaybackStatus()

    @Volatile private var lastSentPlaybackStatus: WatchWireMessage? = null

    @Volatile private var lastDriftReportAt: TimeSource.Monotonic.ValueTimeMark? = null

    @Volatile private var nextLocalChatId = -1L

    @Volatile private var everWelcomed = false

    @Volatile private var reconnectAttempt = 0

    @Volatile private var authenticationRetryUsed = false

    @Volatile private var reconnectingSinceEpochMs: Long? = null

    private val _state = MutableStateFlow(WatchTogetherState())
    val state: StateFlow<WatchTogetherState> = _state.asStateFlow()
    private val _timeline = MutableStateFlow<WatchTimeline?>(null)
    val timeline: StateFlow<WatchTimeline?> = _timeline.asStateFlow()

    /**
     * The room this device was last welcomed into and has not left, surviving process death.
     * Null once the member leaves, is removed, the room ends, or the account signs out.
     */
    private val _resumableRoom = MutableStateFlow(resumeStore.load())
    val resumableRoom: StateFlow<PersistedRoomResume?> = _resumableRoom.asStateFlow()
    val roomPlaylist = WatchRoomPlaylistController(::send)

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

    fun estimatedServerNow(): Long = clock.serverNow()

    /**
     * The server clock estimate, or null until the first pong. Anything that would seek the
     * player on server time must wait for this rather than trust the device wall clock.
     */
    fun estimatedServerNowOrNull(): Long? = clock.serverNowOrNull()

    /** Re-enters the room persisted by [resumableRoom] with the capabilities it was granted. */
    fun rejoinPersistedRoom(endpoint: String) {
        val resume = _resumableRoom.value ?: return
        start(
            endpoint = endpoint,
            roomCode = resume.roomCode,
            mediaKey = resume.mediaKey,
            name = null,
            resumeCapability = resume.resumeCapability,
            hostCapability = resume.hostCapability,
        )
    }

    /** Forgets the persisted room without contacting the server. */
    fun discardPersistedRoom() {
        clearPersistedRoom()
    }

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

    fun joinRoomFromInvite(
        endpoint: String,
        roomCode: String,
        mediaKey: String,
        name: String? = null,
    ) {
        start(endpoint, roomCode.uppercase(), mediaKey, name)
    }

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

    fun grantControl(clientId: String) {
        if (!_state.value.isHost) return
        if (send(WatchWireMessage(type = "grantControl", targetClientId = clientId))) {
            _state.update { it.copy(controlRequest = null) }
        }
    }

    fun denyControl(clientId: String) {
        if (!_state.value.isHost) return
        if (send(WatchWireMessage(type = "denyControl", targetClientId = clientId))) {
            _state.update { it.copy(controlRequest = null) }
        }
    }

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

    /** A warning about this device's media; by default it also marks the media unavailable. */
    fun setSyncWarning(message: String?) = setSyncWarning(message, mediaAvailable = message == null)

    fun setSyncWarning(
        message: String?,
        mediaAvailable: Boolean,
    ) {
        _state.update {
            if (it.syncWarning == message && it.localMediaAvailable == mediaAvailable) {
                it
            } else {
                it.copy(syncWarning = message, localMediaAvailable = mediaAvailable)
            }
        }
    }

    fun updatePlaybackStatus(
        ready: Boolean,
        buffering: Boolean,
        mediaAvailable: Boolean,
        syncDriftMs: Long? = localPlaybackStatus.syncDriftMs,
        durationMs: Long? = localPlaybackStatus.durationMs,
    ) {
        synchronized(statusLock) {
            localPlaybackStatus =
                LocalPlaybackStatus(
                    ready = ready && mediaAvailable && !buffering,
                    buffering = buffering && mediaAvailable,
                    mediaAvailable = mediaAvailable,
                    syncDriftMs = syncDriftMs?.coerceIn(-30_000L, 30_000L),
                    durationMs = durationMs?.takeIf { it > 0L },
                )
        }
        sendPlaybackStatus()
    }

    /**
     * Drift is measured every guest tick, but it is informational: it goes out at most once per
     * [DRIFT_REPORT_INTERVAL_MS] unless it crosses the hard-seek band, which the host should
     * see at once.
     */
    fun updateSyncDrift(syncDriftMs: Long?) {
        val rounded = syncDriftMs?.let { (it / DRIFT_REPORT_BUCKET_MS) * DRIFT_REPORT_BUCKET_MS }
        val send =
            synchronized(statusLock) {
                val previous = localPlaybackStatus.syncDriftMs
                if (previous == rounded) return
                val crossedBand =
                    (previous == null) != (rounded == null) ||
                        (abs(previous ?: 0L) >= DRIFT_URGENT_MS) != (abs(rounded ?: 0L) >= DRIFT_URGENT_MS)
                val due =
                    lastDriftReportAt?.let {
                        it.elapsedNow().inWholeMilliseconds >= DRIFT_REPORT_INTERVAL_MS
                    } ?: true
                localPlaybackStatus = localPlaybackStatus.copy(syncDriftMs = rounded)
                if (crossedBand || due) {
                    lastDriftReportAt = TimeSource.Monotonic.markNow()
                    true
                } else {
                    false
                }
            }
        if (send) sendPlaybackStatus()
    }

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
        clearPersistedRoom()
        _state.value = WatchTogetherState()
        _timeline.value = null
        roomPlaylist.reset()
    }

    private fun clearPersistedRoom() {
        resumeStore.clear()
        _resumableRoom.value = null
    }

    private fun persistRoom() {
        val roomCode = pendingRoomCode ?: return
        val resume =
            PersistedRoomResume(
                roomCode = roomCode,
                mediaKey = pendingMediaKey.orEmpty(),
                resumeCapability = pendingResumeCapability,
                hostCapability = pendingHostCapability,
            )
        resumeStore.save(resume)
        _resumableRoom.value = resume.takeIf { it.isWellFormed }
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
        resumeCapability: String? = null,
        hostCapability: String? = null,
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
        roomPlaylist.reset()
        everWelcomed = false
        reconnectAttempt = 0
        authenticationRetryUsed = false
        reconnectingSinceEpochMs = null
        synchronized(statusLock) {
            localPlaybackStatus = LocalPlaybackStatus()
            lastSentPlaybackStatus = null
            lastDriftReportAt = null
        }
        nextLocalChatId = -1L
        pendingUrl = url
        pendingRoomCode = roomCode
        pendingMediaKey = mediaKey
        pendingResumeCapability = resumeCapability
        pendingHostCapability = hostCapability
        if (resumeCapability == null && hostCapability == null) clearPersistedRoom()
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
                    throwable = failure,
                    attributes = mapOf("roomGone" to roomGone.toString()),
                )
                clearPersistedRoom()
                _state.value =
                    WatchTogetherState(
                        error = failure?.message?.takeIf(String::isNotBlank) ?: "一起看连接失败",
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
                    throwable = failure,
                    attributes =
                        mapOf(
                            "attempt" to reconnectAttempt.toString(),
                            "offlineMs" to offlineMs.toString(),
                        ),
                )
                // The membership is still valid server-side; the persisted capabilities let
                // 「回到房间」 rejoin without a new invite.
                _state.value = WatchTogetherState(error = "一起看连接已断开，请重新加入房间")
                _timeline.value = null
                return
            }
            val retryDelayMs = backoffDelayMs(reconnectAttempt)
            AppLog.warning(
                category = "watch_together",
                event = "connection_lost",
                message = "Watch-together connection lost; reconnect scheduled",
                throwable = failure,
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
                                            if (serverProtocol == null || serverProtocol < WatchProtocol.VERSION) {
                                                "一起看服务器版本过旧，请先更新服务器"
                                            } else {
                                                "当前 App 版本过旧，请先更新 App"
                                            }
                                        throw RoomUnavailableException(detail)
                                    }
                                    wire.roomCode
                                        ?.takeIf(WatchProtocol::isValidRoomCode)
                                        ?.let { pendingRoomCode = it }
                                    wire.resumeCapability?.let { pendingResumeCapability = it }
                                    wire.hostCapability?.let { pendingHostCapability = it }
                                    wire.mediaKey
                                        ?.takeIf {
                                            pendingMediaKey.isNullOrEmpty() &&
                                                WatchProtocol.isValidMediaKey(it)
                                        }?.let { pendingMediaKey = it }
                                    persistRoom()
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
                                    synchronized(statusLock) { lastSentPlaybackStatus = null }
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
                                                        wire.name?.takeIf(String::isNotBlank)
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
                                persistRoom()
                            }

                            "kicked" -> {
                                throw RoomUnavailableException(
                                    wire.message ?: "你已被房主移出当前房间",
                                )
                            }

                            "chat" -> {
                                val chat =
                                    wire.chat?.toDomain(
                                        selfClientId = preferences.clientId,
                                        serverNowMs = wire.serverAtMs ?: estimatedServerNow(),
                                    ) ?: continue
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
                                val reaction = WatchReaction.fromWire(wire.reaction) ?: continue
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
                                when {
                                    wire.errorCode?.startsWith("chat_") == true -> {
                                        wire.clientMessageId?.let { messageId ->
                                            markChatFailed(
                                                messageId,
                                                wire.message ?: "消息发送失败，请点击重试",
                                            )
                                        }
                                        _state.update {
                                            it.copy(chatError = wire.message ?: "消息发送失败")
                                        }
                                    }

                                    wire.errorCode?.startsWith("playlist_") == true -> {
                                        roomPlaylist.applyServerError(wire)
                                    }

                                    else -> {
                                        _state.update {
                                            it.copy(error = wire.message ?: "一起看服务返回错误")
                                        }
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
        clearPersistedRoom()
        _state.value = WatchTogetherState(error = "登录已失效，请重新登录后使用一起看")
        _timeline.value = null
        roomPlaylist.reset()
    }

    private fun applyRoomSnapshot(wire: WatchWireMessage) {
        roomPlaylist.applySnapshot(wire)
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
        val snapshotServerNowMs = estimatedServerNow()
        _state.update { current ->
            val isHost = wire.isHost ?: current.isHost
            val canControl = wire.canControl ?: current.canControl
            val history =
                wire.chatHistory?.mapNotNull {
                    it.toDomain(preferences.clientId, snapshotServerNowMs)
                }
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
                    wire.participants?.mapNotNull { it.toDomain(preferences.clientId) }
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
            // The message's own stamp is the reference: before the first pong the device clock
            // is all the clock sync has, and a wrong one must not reject a valid timeline.
            !WatchProtocol.isReasonableServerTime(anchorAtMs, wire.serverAtMs ?: estimatedServerNow())
        ) {
            AppLog.warning(
                category = "watch_together",
                event = "timeline_invalid",
                message = "Watch-together server sent an invalid timeline",
            )
            return
        }
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
        val message =
            synchronized(statusLock) {
                val status = localPlaybackStatus
                val candidate =
                    WatchWireMessage(
                        type = "playbackStatus",
                        ready = status.ready,
                        buffering = status.buffering,
                        mediaAvailable = status.mediaAvailable,
                        latencyMs =
                            clock.latencyMs()?.let { (it / LATENCY_REPORT_BUCKET_MS) * LATENCY_REPORT_BUCKET_MS },
                        syncDriftMs = status.syncDriftMs,
                        durationMs = status.durationMs,
                    )
                if (candidate == lastSentPlaybackStatus) return
                lastSentPlaybackStatus = candidate
                candidate
            }
        if (!send(message)) {
            synchronized(statusLock) {
                if (lastSentPlaybackStatus == message) lastSentPlaybackStatus = null
            }
        }
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
}
