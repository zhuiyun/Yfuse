package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountRateLimiter
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.account.PlaybackRelayStoreProvider
import com.yfuse.watch.account.accountRoutes
import com.yfuse.watch.account.isLoopbackHost
import com.yfuse.watch.migration.MigrationRelayBackend
import com.yfuse.watch.migration.migrationRelayRoutes
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

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
 * Latency and drift changes are coalesced into one room update per interval: they change
 * every second on every guest, and a full snapshot per change made steady-state traffic
 * grow with the square of the room size.
 */
internal const val PRESENCE_BROADCAST_INTERVAL_MS = 5_000L

/**
 * Caps, so one client can't exhaust a small shared box. All three are far above anything a
 * real watch-along does; they exist to bound the damage from a loop or a scanner, not to
 * ration normal use.
 */
internal const val MAX_ROOMS = 500
private const val DEFAULT_MAX_ACTIVE_ROOMS_PER_IP = 8
private const val DEFAULT_MAX_WATCH_CONNECTIONS = 256
private const val DEFAULT_MAX_WATCH_CONNECTIONS_PER_IP = 32
private const val DEFAULT_MAX_WATCH_CONNECTIONS_PER_ACCOUNT = 8
private const val MAX_CONFIGURED_WATCH_CONNECTIONS = 10_000
private const val MAX_PARTICIPANTS_PER_ROOM = 12
private const val MAX_MEMBERSHIPS_PER_ROOM = 64
private const val MAX_MESSAGES_PER_WINDOW = 240
private const val RATE_WINDOW_MS = 10_000L
private const val MAX_CHAT_HISTORY = 50
private const val MAX_CHAT_MESSAGES_PER_WINDOW = 3
private const val CHAT_RATE_WINDOW_MS = 3_000L
private const val CHAT_MUTE_AFTER_REJECTIONS = 5
private const val CHAT_REJECTION_WINDOW_MS = 30_000L
private const val CHAT_MUTE_MS = 60_000L

/**
 * The reactions a client may send.
 *
 * A closed set rather than arbitrary text: a reaction is broadcast to everyone in the
 * room without moderation and never stored, so the only safe thing to relay is something
 * the server itself chose. It also keeps the wire tiny — reactions are the one message
 * that can arrive several times a second.
 */
private val REACTIONS = setOf("😂", "😮", "😍", "😭", "👏", "🔥", "🤔", "💀")

/** Bursts are the point, so this is looser than chat — but still bounded. */
private const val MAX_REACTIONS_PER_WINDOW = 6
private const val REACTION_RATE_WINDOW_MS = 3_000L
private const val PROFILE_UPDATE_COOLDOWN_MS = 1_000L
private const val ACCOUNT_REVALIDATION_MS = 10_000L

private val AUTHENTICATED_MESSAGE_TYPES =
    setOf(
        "sync",
        "requestControl",
        "grantControl",
        "denyControl",
        "setControlMode",
        "setModerator",
        "kickParticipant",
        "updateProfile",
        "playbackStatus",
        "chat",
        "reaction",
        "playlistAdd",
        "playlistUpdate",
        "playlistRemove",
        "playlistReorder",
    )

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = resolveServerHost(System.getenv("HOST"))
    val accountBackend =
        AccountBackend.sqlite(
            File(System.getenv("ACCOUNT_DB_PATH") ?: "/var/lib/yfuse/account.db"),
        )
    val migrationRelayBackend = MigrationRelayBackend.fromEnvironment()
    val server =
        embeddedServer(CIO, host = host, port = port) {
            productionWatchTogetherModule(
                accountBackend = accountBackend,
                migrationRelayBackend = migrationRelayBackend,
                requireWatchAuthentication = true,
            )
        }
    // SIGTERM from systemd reaches the JVM as a shutdown hook. Without this the process
    // simply exits: the ApplicationStopped subscribers that close the SQLite connections
    // never run, and every open socket is cut without a close frame.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(gracePeriodMillis = 3_000, timeoutMillis = 10_000)
        },
    )
    server.start(wait = true)
}

internal fun resolveServerHost(raw: String?): String {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return "127.0.0.1"
    require(value.length <= 255 && value.none { it.isWhitespace() || Character.isISOControl(it) }) {
        "HOST is invalid"
    }
    return value
}

internal fun Application.watchTogetherModule(
    updateRoot: File = File(System.getenv("UPDATE_ROOT") ?: "/srv/yfuse-update/yfuse"),
    /** Injectable so tests can exercise the handover without waiting out the real window. */
    hostGraceMs: Long = HOST_GRACE_MS,
    /** Empty rooms retain their code briefly for reconnects, then release quota on sweep. */
    roomGraceMs: Long = ROOM_GRACE_MS,
    /** Injectable so tests can observe presence coalescing without waiting out the real window. */
    presenceBroadcastIntervalMs: Long = PRESENCE_BROADCAST_INTERVAL_MS,
    maxActiveRoomsPerIp: Int =
        System
            .getenv("WATCH_MAX_ACTIVE_ROOMS_PER_IP")
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_ROOMS)
            ?: DEFAULT_MAX_ACTIVE_ROOMS_PER_IP,
    maxActiveRoomsPerAccount: Int =
        System
            .getenv("WATCH_MAX_ACTIVE_ROOMS_PER_ACCOUNT")
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_ROOMS)
            ?: DEFAULT_MAX_ACTIVE_ROOMS_PER_ACCOUNT,
    /** Slows room-code guessing; injectable so tests can trip it quickly. */
    joinFailureLimiter: WatchJoinFailureLimiter = WatchJoinFailureLimiter(),
    /** Bearer token that unlocks `/watch/metrics` off-box; null limits it to loopback. */
    metricsToken: String? = System.getenv("WATCH_METRICS_TOKEN")?.trim()?.takeIf { it.length >= 16 },
    maxWatchConnections: Int =
        System
            .getenv("WATCH_MAX_CONNECTIONS")
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_CONFIGURED_WATCH_CONNECTIONS)
            ?: DEFAULT_MAX_WATCH_CONNECTIONS,
    maxWatchConnectionsPerIp: Int =
        System
            .getenv("WATCH_MAX_CONNECTIONS_PER_IP")
            ?.toIntOrNull()
            ?.coerceIn(1, maxWatchConnections)
            ?: minOf(DEFAULT_MAX_WATCH_CONNECTIONS_PER_IP, maxWatchConnections),
    maxWatchConnectionsPerAccount: Int =
        System
            .getenv("WATCH_MAX_CONNECTIONS_PER_ACCOUNT")
            ?.toIntOrNull()
            ?.coerceIn(1, maxWatchConnections)
            ?: minOf(DEFAULT_MAX_WATCH_CONNECTIONS_PER_ACCOUNT, maxWatchConnections),
    connectionGate: WatchConnectionGate =
        WatchConnectionGate(
            globalLimit = maxWatchConnections,
            perIpLimit = maxWatchConnectionsPerIp,
            perAccountLimit = maxWatchConnectionsPerAccount,
        ),
    /** Only enable when the reverse proxy overwrites client-supplied forwarding headers. */
    trustProxyHeaders: Boolean =
        System
            .getenv("WATCH_TRUST_PROXY_HEADERS")
            ?.equals("true", ignoreCase = true)
            ?: false,
    /** Test seam; production normally uses the socket/proxy-aware resolver below. */
    clientIpResolver: ((ApplicationCall) -> String)? = null,
    /** Account persistence is independent of the ephemeral watch-room store. */
    accountBackend: AccountBackend = AccountBackend.inMemory(),
    /** Authentication limiter is application-local and injectable for deterministic tests. */
    accountRateLimiter: AccountRateLimiter = AccountRateLimiter(),
    migrationRelayBackend: MigrationRelayBackend = MigrationRelayBackend.inMemory(),
    migrationRelayWorkExecutor: AccountWorkExecutor =
        AccountWorkExecutor(
            AccountExecutionPolicy(workerThreads = 2, maxConcurrentOperations = 2),
        ),
    /** Test-only seam for exercising the room protocol independently; production stays true. */
    requireWatchAuthentication: Boolean = false,
    /** Independent watchdog interval; keeps silent sockets subject to session revocation. */
    watchAuthRevalidationMs: Long = ACCOUNT_REVALIDATION_MS,
    /** Test seam for deterministic account-store failure and recovery scenarios. */
    watchAccountAuthenticator: suspend (String) -> AuthenticatedAccount =
        accountBackend::authenticateAccessToken,
    /** Revocation checks deliberately avoid touching session activity every ten seconds. */
    watchAccountRevalidator: suspend (String) -> AuthenticatedAccount =
        accountBackend::validateAccessToken,
    /** Test seam; production retries transient account-store failures with capped jitter. */
    watchAuthRetryDelayMs: (Int) -> Long = ::watchAuthTransientRetryDelayMs,
    /** Test seam shared by expiry checks; production uses the wall clock encoded in tokens. */
    watchAuthClock: () -> Long = System::currentTimeMillis,
    /** Signed official schedule feed; null keeps the public endpoint safely unavailable. */
    calendarScheduleSigner: CalendarScheduleSigner? = CalendarScheduleSigner.fromEnvironment(),
    /** Shared schedule database; user Emby credentials never enter this store. */
    calendarScheduleStore: CalendarScheduleStore = NoOpCalendarScheduleStore,
    /** 手机遥控's pairings; injectable so tests can look at them. */
    remoteControlRelay: RemoteControlRelay<WebSocketSession> = RemoteControlRelay(),
) {
    require(roomGraceMs >= 0L) { "roomGraceMs must not be negative" }
    require(maxActiveRoomsPerIp in 1..MAX_ROOMS) {
        "maxActiveRoomsPerIp must be between 1 and $MAX_ROOMS"
    }
    require(maxWatchConnections in 1..MAX_CONFIGURED_WATCH_CONNECTIONS)
    require(maxWatchConnectionsPerIp in 1..maxWatchConnections)
    require(maxWatchConnectionsPerAccount in 1..maxWatchConnections)
    require(watchAuthRevalidationMs > 0L) { "watchAuthRevalidationMs must be positive" }
    val roomStore =
        RoomStore(
            roomGraceMs = roomGraceMs,
            maxActiveRoomsPerIp = maxActiveRoomsPerIp,
            maxActiveRoomsPerAccount = maxActiveRoomsPerAccount,
        )
    // Outlives any one socket, which is what a delayed host handover needs: the connection
    // whose loss starts the clock is precisely the one that can't run the timer.
    val appScope: CoroutineScope = this
    // Import the previous JSON publication exactly once when a production database is empty.
    if (calendarScheduleStore !== NoOpCalendarScheduleStore) {
        runCatching {
            if (calendarScheduleStore.current() == null) {
                calendarScheduleStore.replace(loadCalendarPublication())
            }
        }.onFailure { failure ->
            ServerLog.error("calendar_database_bootstrap_failed", throwable = failure)
        }
    }
    // Disabled unless ingestion config and at least one durable output are configured. The
    // collector fails closed and keeps the current database revision on upstream/OCR failures.
    appScope.launchCalendarIngestionFromEnvironment(calendarScheduleStore)
    monitor.subscribe(ApplicationStopped) { calendarScheduleStore.close() }
    monitor.subscribe(ApplicationStopped) { PlaybackRelayStoreProvider.closeIfStarted() }
    monitor.subscribe(ApplicationStopped) {
        try {
            accountBackend.close()
        } finally {
            try {
                migrationRelayBackend.close()
            } finally {
                migrationRelayWorkExecutor.close()
            }
        }
    }
    install(WebSockets) {
        pingPeriodMillis = 20_000L
        timeoutMillis = 40_000L
        maxFrameSize = 64 * 1024L
        masking = false
    }
    // Request log: method, path (never the query, which can carry invite codes), status and
    // duration. Sockets log once when they close, with their whole lifetime as the duration.
    intercept(ApplicationCallPipeline.Monitoring) {
        val startedAt = System.nanoTime()
        WatchMetrics.httpRequests.incrementAndGet()
        try {
            proceed()
        } finally {
            val status = call.response.status()?.value
            if (status != null && status >= 500) WatchMetrics.httpServerErrors.incrementAndGet()
            ServerLog.info(
                "http_request",
                "method" to call.request.httpMethod.value,
                "path" to call.request.path(),
                "status" to (status ?: "-"),
                "ms" to (System.nanoTime() - startedAt) / 1_000_000,
            )
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        if (call.request.path().startsWith("/account")) {
            call.response.header(HttpHeaders.CacheControl, "no-store")
        }
    }
    routing {
        calendarScheduleRoutes(calendarScheduleSigner, calendarScheduleStore)
        accountRoutes(accountBackend, accountRateLimiter)
        migrationRelayRoutes(
            backend = migrationRelayBackend,
            workExecutor = migrationRelayWorkExecutor,
            clientIpResolver = clientIpResolver,
            trustProxyHeaders = trustProxyHeaders,
        )
        get("/health") {
            call.respondText("ok")
        }
        get("/watch/version") {
            call.respondText(
                """{"protocolVersion":${WatchProtocol.VERSION},"minProtocolVersion":${WatchProtocol.MIN_SUPPORTED_VERSION},"capabilities":[${WatchProtocol.SERVER_CAPABILITIES.joinToString {
                    "\"$it\""
                }}],"gitSha":"${BuildInfo.gitSha}"}""",
                ContentType.Application.Json,
            )
        }
        get("/watch/metrics") {
            val presented =
                call.request.headers["Authorization"]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substringAfter(' ')
                    ?.trim()
            val allowed =
                when {
                    metricsToken != null -> presented != null && constantTimeEquals(presented, metricsToken)
                    else -> isLoopbackHost(call.request.origin.remoteHost)
                }
            if (!allowed) {
                call.respondText("forbidden", status = HttpStatusCode.Forbidden)
                return@get
            }
            call.respondText(
                WatchMetrics.render(
                    activeRooms = roomStore.activeRoomCount(),
                    activeParticipants = roomStore.activeParticipantCount(),
                ),
                ContentType.Text.Plain,
            )
        }
        staticFiles("/yfuse", updateRoot)
        webSocket("/watch") {
            if (!call.isSecureServiceTransport(trustProxyHeaders)) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "secure_transport_required"))
                return@webSocket
            }
            val clientIp =
                clientIpResolver
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
            val connectionLease = connectionGate.tryAcquire(clientIp)
            if (connectionLease == null) {
                WatchMetrics.connectionsRejected.incrementAndGet()
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "connection_limit"))
                return@webSocket
            }
            WatchMetrics.connectionsAccepted.incrementAndGet()
            currentCoroutineContext()
                .job
                .invokeOnCompletion {
                    connectionLease.close()
                }
            val accessToken =
                call.request.headers["Authorization"]
                    ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                    ?.substringAfter(' ')
                    ?.takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
            if (requireWatchAuthentication && accessToken == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "account_auth_required"))
                return@webSocket
            }
            val authenticatedAccount =
                if (requireWatchAuthentication) {
                    authenticateWatchConnection(
                        accessToken = checkNotNull(accessToken),
                        authenticator = watchAccountAuthenticator,
                        retryDelayMs = watchAuthRetryDelayMs,
                    ) ?: return@webSocket
                } else {
                    AuthenticatedAccount(
                        userId = "watch-test-account",
                        sessionId = "watch-test-session",
                        username = "watch-test",
                        nickname = "Watch Test",
                        avatarId = 0,
                        accessExpiresAtEpochMs = Long.MAX_VALUE,
                    )
                }
            if (
                requireWatchAuthentication &&
                !connectionLease.tryBindAccount(authenticatedAccount.userId)
            ) {
                WatchMetrics.connectionsRejected.incrementAndGet()
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "account_connection_limit"))
                return@webSocket
            }
            if (!requireWatchAuthentication) connectionLease.promote()
            val authWatchdog =
                if (requireWatchAuthentication) {
                    launchWatchAccountRevalidation(
                        account = authenticatedAccount,
                        accessToken = checkNotNull(accessToken),
                        revalidator = watchAccountRevalidator,
                        revalidationMs = watchAuthRevalidationMs,
                        retryDelayMs = watchAuthRetryDelayMs,
                        clock = watchAuthClock,
                    )
                } else {
                    null
                }
            var joinedRoom: Room? = null
            var joinedClientId: String? = null
            var windowStartedAtMs = System.currentTimeMillis()
            var messagesInWindow = 0
            val recentReactionAtMs = ArrayDeque<Long>()
            var lastProfileUpdateAtMs = 0L
            try {
                incoming.consumeEach { frame ->
                    WatchMetrics.messagesHandled.incrementAndGet()
                    if (frame !is Frame.Text && frame !is Frame.Binary) return@consumeEach

                    // Flood guard, counted per connection over a rolling window rather than
                    // per message type — unsupported binary data consumes the same budget before
                    // the connection is rejected, so it cannot bypass text-message admission.
                    val receivedAtMs = System.currentTimeMillis()
                    if (receivedAtMs - windowStartedAtMs > RATE_WINDOW_MS) {
                        windowStartedAtMs = receivedAtMs
                        messagesInWindow = 0
                    }
                    if (++messagesInWindow > MAX_MESSAGES_PER_WINDOW) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "rate limit"))
                        return@consumeEach
                    }
                    if (frame is Frame.Binary) {
                        close(
                            CloseReason(
                                CloseReason.Codes.VIOLATED_POLICY,
                                "binary_frames_not_supported",
                            ),
                        )
                        return@consumeEach
                    }
                    val textFrame = frame as Frame.Text

                    val message =
                        runCatching {
                            watchWireJson.decodeFromString(WatchWireMessage.serializer(), textFrame.readText())
                        }.getOrNull() ?: return@consumeEach sendError(
                            "消息格式无效",
                            "message_invalid",
                        )

                    if (message.type !in WatchProtocol.CLIENT_MESSAGE_TYPES) {
                        return@consumeEach sendError("消息类型无效", "message_type_invalid")
                    }
                    // 手机遥控 sockets are their own kind: hosting or controlling never joins a room,
                    // and a room member never carries a remote.
                    if (remoteControlRelay.claims(this@webSocket, message)) {
                        if (joinedRoom != null) {
                            return@consumeEach sendError("一起看连接不能用作遥控", "remote_invalid")
                        }
                        remoteControlRelay.handle(this@webSocket, authenticatedAccount, message)
                        return@consumeEach
                    }
                    if (message.type in AUTHENTICATED_MESSAGE_TYPES) {
                        val room =
                            joinedRoom ?: return@consumeEach sendError(
                                "请先加入房间",
                                "not_joined",
                            )
                        val clientId = joinedClientId ?: return@consumeEach
                        val active =
                            synchronized(room) {
                                room.participants[clientId]?.session === this
                            }
                        if (!active) {
                            close(
                                CloseReason(
                                    CloseReason.Codes.VIOLATED_POLICY,
                                    "session superseded",
                                ),
                            )
                            return@consumeEach
                        }
                    }

                    when (message.type) {
                        "hello" -> {
                            if (joinedRoom != null) return@consumeEach
                            if (!WatchProtocol.isSupportedVersion(message.protocolVersion)) {
                                return@consumeEach sendError(
                                    "一起看协议版本不兼容，请更新 App 或服务器",
                                    "protocol_incompatible",
                                )
                            }
                            val negotiatedProtocolVersion = checkNotNull(message.protocolVersion)
                            val clientId =
                                normalizeClientId(message.clientId)
                                    ?: return@consumeEach sendError(
                                        "客户端标识无效",
                                        "client_id_invalid",
                                    )
                            val membershipAccountUserId =
                                if (requireWatchAuthentication) {
                                    authenticatedAccount.userId
                                } else {
                                    // The unauthenticated mode exists only for protocol tests. Give
                                    // each public id a distinct synthetic identity so legacy tests can
                                    // still model multiple people without weakening production rules.
                                    "test-client:$clientId"
                                }
                            val name =
                                if (requireWatchAuthentication) {
                                    authenticatedAccount.nickname
                                } else {
                                    normalizeName(message.name)
                                }
                            val avatarId =
                                if (requireWatchAuthentication) {
                                    authenticatedAccount.avatarId
                                } else {
                                    normalizeAvatarId(message.avatarId, clientId)
                                }
                            if (
                                message.playlistRevision != null ||
                                message.playlistEntry != null ||
                                message.playlistEntryId != null ||
                                message.playlistIndex != null
                            ) {
                                return@consumeEach sendError(
                                    "建房播放列表字段无效",
                                    "playlist_invalid",
                                )
                            }

                            roomStore.sweepExpiredRooms()

                            val room =
                                if (message.roomCode == null) {
                                    if (message.resumeCapability != null || message.hostCapability != null) {
                                        return@consumeEach sendError("建房凭据无效", "credential_invalid")
                                    }
                                    val mediaKey =
                                        message.mediaKey
                                            ?.takeIf(WatchProtocol::isValidMediaKey)
                                            ?: return@consumeEach sendError(
                                                "媒体标识无效",
                                                "media_key_invalid",
                                            )
                                    val initialPlaylist = message.playlist ?: emptyList()
                                    if (!WatchProtocol.isValidPlaylist(initialPlaylist)) {
                                        return@consumeEach sendError(
                                            "房间播放列表无效",
                                            "playlist_invalid",
                                        )
                                    }
                                    when (
                                        val created =
                                            roomStore.createRoom(
                                                mediaKey = mediaKey,
                                                hostId = clientId,
                                                creatorIp = clientIp,
                                                creatorAccountUserId = membershipAccountUserId,
                                                initialPlaylist = initialPlaylist,
                                            )
                                    ) {
                                        is RoomCreationResult.Created -> {
                                            WatchMetrics.roomsCreated.incrementAndGet()
                                            created.room
                                        }
                                        RoomCreationResult.IpLimitReached -> {
                                            return@consumeEach sendError(
                                                "当前网络创建的活跃房间过多，请稍后再试",
                                                "room_ip_limit",
                                            )
                                        }
                                        RoomCreationResult.AccountLimitReached -> {
                                            return@consumeEach sendError(
                                                "你创建的活跃房间过多，请先关闭旧房间",
                                                "room_account_limit",
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
                                    if (message.playlist != null) {
                                        return@consumeEach sendError(
                                            "仅创建房间时可以设置初始播放列表",
                                            "playlist_initial_only",
                                        )
                                    }
                                    val requestedRoomCode =
                                        message.roomCode
                                            ?: return@consumeEach sendError(
                                                "房间码无效",
                                                "room_code_invalid",
                                            )
                                    if (!WatchProtocol.isValidRoomCode(requestedRoomCode)) {
                                        return@consumeEach sendError("房间码无效", "room_code_invalid")
                                    }
                                    if (joinFailureLimiter.isPenalized(clientIp)) {
                                        WatchMetrics.joinsRejected.incrementAndGet()
                                        return@consumeEach sendError(
                                            "加入失败次数过多，请稍后再试",
                                            "join_rate_limited",
                                        )
                                    }
                                    roomStore.find(requestedRoomCode)
                                        ?: run {
                                            if (joinFailureLimiter.recordFailure(clientIp)) {
                                                ServerLog.warn("room_join_penalized", "ip" to clientIp)
                                            }
                                            return@consumeEach sendError("房间不存在或已关闭")
                                        }
                                }
                            joinFailureLimiter.clear(clientIp)

                            var joinRejected: RoomJoinRejection? = null
                            var staleSession: WebSocketSession? = null
                            var issuedResumeCapability: String? = null
                            var issuedHostCapability: String? = null
                            val roomStillCurrent =
                                roomStore.mutateIfCurrent(room) {
                                    joinRejected =
                                        room.validateJoin(
                                            clientId = clientId,
                                            accountUserId = membershipAccountUserId,
                                            resumeCapability = message.resumeCapability,
                                            hostCapability = message.hostCapability,
                                            creatingRoom = message.roomCode == null,
                                            maxParticipants = MAX_PARTICIPANTS_PER_ROOM,
                                            maxMemberships = MAX_MEMBERSHIPS_PER_ROOM,
                                        )
                                    if (joinRejected != null) return@mutateIfCurrent
                                    val membership = room.memberships[clientId]
                                    val isHost = clientId == room.hostId
                                    staleSession = room.participants[clientId]?.session
                                    val activeMembership =
                                        membership ?: newMembership(
                                            roomCode = room.code,
                                            clientId = clientId,
                                            accountUserId = membershipAccountUserId,
                                        ).let { (createdMembership, capability) ->
                                            room.memberships[clientId] = createdMembership
                                            issuedResumeCapability = capability
                                            createdMembership
                                        }
                                    activeMembership.sessionGeneration++
                                    val participant =
                                        Participant(
                                            clientId,
                                            name,
                                            avatarId,
                                            this,
                                            sessionGeneration = activeMembership.sessionGeneration,
                                            accountUserId = membershipAccountUserId,
                                            authorizedHostEpoch = if (isHost) room.hostEpoch else null,
                                        )
                                    room.participants[clientId] = participant
                                    room.emptySinceMs = null
                                    if (isHost) {
                                        // The host is back inside its grace window; the slot was
                                        // being held open for exactly this.
                                        room.hostAbsentSinceMs = null
                                        if (membership == null) {
                                            issuedHostCapability = room.initialHostCapability
                                            room.initialHostCapability = null
                                        }
                                    } else if (
                                        !room.participants.containsKey(room.hostId) &&
                                        room.hostGraceExpired(hostGraceMs)
                                    ) {
                                        // The host slot points at someone who left and did not
                                        // come back in time. Whoever joins now takes it, rather
                                        // than leaving the room locked to a host who may never
                                        // return.
                                        issuedHostCapability = room.transferHostTo(participant)
                                    }
                                }
                            if (!roomStillCurrent) {
                                return@consumeEach sendError("房间不存在或已关闭")
                            }
                            joinRejected?.let { rejection ->
                                sendError(rejection.message, rejection.code)
                                rejection.closeReason?.let { reason ->
                                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
                                }
                                return@consumeEach
                            }
                            staleSession?.let {
                                runCatching {
                                    it.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "reconnected"))
                                }
                            }

                            joinedRoom = room
                            joinedClientId = clientId
                            sendMessage(
                                room.welcomeMessage(
                                    clientId = clientId,
                                    resumeCapability = issuedResumeCapability,
                                    hostCapability = issuedHostCapability,
                                    protocolVersion = negotiatedProtocolVersion,
                                ),
                            )
                            broadcastRoomUpdate(room)
                        }

                        "sync" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            var controlDenied = false
                            val timeline =
                                synchronized(room) {
                                    val participant =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                            ?: return@synchronized null
                                    if (!room.canControl(participant)) {
                                        controlDenied = true
                                        return@synchronized null
                                    }
                                    if (!WatchProtocol.isValidTimeline(
                                            message.positionMs,
                                            message.paused,
                                            message.rate,
                                        ) ||
                                        (
                                            message.mediaKey != null &&
                                                !WatchProtocol.isValidMediaKey(message.mediaKey)
                                        ) ||
                                        message.seq != null ||
                                        message.anchorAtMs != null ||
                                        message.serverAtMs != null
                                    ) {
                                        return@synchronized null
                                    }
                                    Timeline(
                                        mediaKey = message.mediaKey ?: room.timeline.mediaKey,
                                        anchorPositionMs = message.positionMs!!,
                                        anchorAtServerMs = System.currentTimeMillis(),
                                        rate = message.rate!!,
                                        paused = message.paused!!,
                                        seq = room.timeline.seq + 1,
                                    ).also { room.timeline = it }
                                }
                            if (controlDenied) {
                                sendError("当前没有播放控制权限")
                                return@consumeEach
                            }
                            if (timeline == null &&
                                !WatchProtocol.isValidTimeline(
                                    message.positionMs,
                                    message.paused,
                                    message.rate,
                                ) ||
                                (
                                    message.mediaKey != null &&
                                        !WatchProtocol.isValidMediaKey(message.mediaKey)
                                ) ||
                                message.seq != null ||
                                message.anchorAtMs != null ||
                                message.serverAtMs != null
                            ) {
                                return@consumeEach sendError(
                                    "播放时间线无效",
                                    "timeline_invalid",
                                )
                            }
                            if (timeline == null) return@consumeEach
                            broadcastSync(room, timeline)
                        }

                        // Control handoff. Guests used to have no way to ask for the
                        // timeline and hosts no way to give it up, so a room whose host had
                        // stopped paying attention could not be steered by anyone.
                        "requestControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val (hostSession, askerName) =
                                synchronized(room) {
                                    val participant =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                            ?: return@consumeEach
                                    if (room.canControl(participant)) return@consumeEach
                                    room.participants[room.hostId]?.session to
                                        room.participants[clientId]?.name
                                }
                            hostSession?.let { session ->
                                runCatching {
                                    session.sendMessage(
                                        WatchWireMessage(
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
                            val target =
                                normalizeClientId(message.targetClientId)
                                    ?: return@consumeEach
                            var grantedHostCapability: String? = null
                            val handed =
                                synchronized(room) {
                                    val actor =
                                        room.participants[joinedClientId]
                                            ?.takeIf { it.session === this }
                                    val isHost = actor != null && room.isAuthorizedHost(actor)
                                    val present = room.participants.containsKey(target)
                                    if (isHost && present) {
                                        grantedHostCapability =
                                            room.transferHostTo(
                                                room.participants.getValue(target),
                                            )
                                    }
                                    isHost && present
                                }
                            if (handed) {
                                val targetSession =
                                    synchronized(room) {
                                        room.participants[target]?.session
                                    }
                                targetSession?.sendMessage(
                                    WatchWireMessage(
                                        type = "hostCapabilityGranted",
                                        hostCapability = grantedHostCapability,
                                    ),
                                )
                                broadcastRoomUpdate(room)
                            }
                        }

                        "denyControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val target =
                                normalizeClientId(message.targetClientId)
                                    ?: return@consumeEach
                            val targetSession =
                                synchronized(room) {
                                    val actor =
                                        room.participants[joinedClientId]
                                            ?.takeIf { it.session === this }
                                    if (actor == null || !room.isAuthorizedHost(actor)) {
                                        null
                                    } else {
                                        room.participants[target]?.session
                                    }
                                }
                            targetSession?.let { session ->
                                runCatching {
                                    session.sendMessage(WatchWireMessage(type = "controlDenied"))
                                }
                            }
                        }

                        "setControlMode" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val requested =
                                ControlMode.fromWire(message.controlMode)
                                    ?: return@consumeEach sendError(
                                        "控制权限模式无效",
                                        "control_mode_invalid",
                                    )
                            val changed =
                                synchronized(room) {
                                    val actor =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                    if (actor == null ||
                                        !room.isAuthorizedHost(actor) ||
                                        room.controlMode == requested
                                    ) {
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
                            val target =
                                normalizeClientId(message.targetClientId)
                                    ?: return@consumeEach
                            val enabled = message.moderator ?: return@consumeEach
                            val changed =
                                synchronized(room) {
                                    val actor =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                    if (
                                        actor == null ||
                                        !room.isAuthorizedHost(actor) ||
                                        target == room.hostId ||
                                        !room.participants.containsKey(target)
                                    ) {
                                        return@synchronized false
                                    }
                                    if (enabled) {
                                        room.moderatorIds.add(target)
                                    } else {
                                        room.moderatorIds.remove(target)
                                    }
                                }
                            if (changed) broadcastRoomUpdate(room)
                        }

                        "playlistAdd" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val entry = message.playlistEntry
                            val expectedRevision = message.playlistRevision
                            if (
                                !WatchProtocol.isValidPlaylistEntry(entry) ||
                                !WatchProtocol.isValidPlaylistRevision(expectedRevision) ||
                                message.playlist != null ||
                                message.playlistEntryId != null
                            ) {
                                return@consumeEach sendError(
                                    "播放列表新增请求无效",
                                    "playlist_invalid",
                                )
                            }
                            val result =
                                room.mutatePlaylist(
                                    clientId = clientId,
                                    session = this,
                                    expectedRevision = expectedRevision!!,
                                ) { playlist ->
                                    when {
                                        playlist.any { it.id == entry!!.id } ->
                                            PlaylistMutationResult.Duplicate
                                        playlist.size >= WatchProtocol.MAX_PLAYLIST_ENTRIES ->
                                            PlaylistMutationResult.Full
                                        message.playlistIndex != null &&
                                            message.playlistIndex !in 0..playlist.size ->
                                            PlaylistMutationResult.IndexInvalid
                                        else -> {
                                            playlist.add(
                                                message.playlistIndex ?: playlist.size,
                                                entry!!,
                                            )
                                            PlaylistMutationResult.Changed
                                        }
                                    }
                                }
                            finishPlaylistMutation(room, result)
                        }

                        "playlistUpdate" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val entry = message.playlistEntry
                            val expectedRevision = message.playlistRevision
                            if (
                                !WatchProtocol.isValidPlaylistEntry(entry) ||
                                !WatchProtocol.isValidPlaylistRevision(expectedRevision) ||
                                message.playlist != null ||
                                message.playlistEntryId != null ||
                                message.playlistIndex != null
                            ) {
                                return@consumeEach sendError(
                                    "播放列表更新请求无效",
                                    "playlist_invalid",
                                )
                            }
                            val result =
                                room.mutatePlaylist(
                                    clientId = clientId,
                                    session = this,
                                    expectedRevision = expectedRevision!!,
                                ) { playlist ->
                                    val index = playlist.indexOfFirst { it.id == entry!!.id }
                                    when {
                                        index < 0 -> PlaylistMutationResult.NotFound
                                        playlist[index] == entry -> PlaylistMutationResult.Unchanged
                                        else -> {
                                            playlist[index] = entry!!
                                            PlaylistMutationResult.Changed
                                        }
                                    }
                                }
                            finishPlaylistMutation(room, result)
                        }

                        "playlistRemove" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val entryId = message.playlistEntryId
                            val expectedRevision = message.playlistRevision
                            if (
                                !WatchProtocol.isValidPlaylistEntryId(entryId) ||
                                !WatchProtocol.isValidPlaylistRevision(expectedRevision) ||
                                message.playlist != null ||
                                message.playlistEntry != null ||
                                message.playlistIndex != null
                            ) {
                                return@consumeEach sendError(
                                    "播放列表删除请求无效",
                                    "playlist_invalid",
                                )
                            }
                            val result =
                                room.mutatePlaylist(
                                    clientId = clientId,
                                    session = this,
                                    expectedRevision = expectedRevision!!,
                                ) { playlist ->
                                    val index = playlist.indexOfFirst { it.id == entryId }
                                    if (index < 0) {
                                        PlaylistMutationResult.NotFound
                                    } else {
                                        playlist.removeAt(index)
                                        PlaylistMutationResult.Changed
                                    }
                                }
                            finishPlaylistMutation(room, result)
                        }

                        "playlistReorder" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val entryId = message.playlistEntryId
                            val destination = message.playlistIndex
                            val expectedRevision = message.playlistRevision
                            if (
                                !WatchProtocol.isValidPlaylistEntryId(entryId) ||
                                destination == null ||
                                !WatchProtocol.isValidPlaylistRevision(expectedRevision) ||
                                message.playlist != null ||
                                message.playlistEntry != null
                            ) {
                                return@consumeEach sendError(
                                    "播放列表排序请求无效",
                                    "playlist_invalid",
                                )
                            }
                            val result =
                                room.mutatePlaylist(
                                    clientId = clientId,
                                    session = this,
                                    expectedRevision = expectedRevision!!,
                                ) { playlist ->
                                    val source = playlist.indexOfFirst { it.id == entryId }
                                    when {
                                        source < 0 -> PlaylistMutationResult.NotFound
                                        destination !in playlist.indices ->
                                            PlaylistMutationResult.IndexInvalid
                                        source == destination -> PlaylistMutationResult.Unchanged
                                        else -> {
                                            val entryToMove = playlist.removeAt(source)
                                            playlist.add(destination, entryToMove)
                                            PlaylistMutationResult.Changed
                                        }
                                    }
                                }
                            finishPlaylistMutation(room, result)
                        }

                        "kickParticipant" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val target =
                                normalizeClientId(message.targetClientId)
                                    ?: return@consumeEach
                            var denied = false
                            var removalLimitReached = false
                            val removedParticipants =
                                synchronized(room) {
                                    val actor =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                    if (actor == null || !room.isAuthorizedHost(actor)) {
                                        denied = true
                                        return@synchronized null
                                    }
                                    if (target == room.hostId) return@synchronized null
                                    val participant =
                                        room.participants[target]
                                            ?: return@synchronized null
                                    room
                                        .removeMemberDevices(actor, participant, MAX_REMOVED_ACCOUNT_IDS_PER_ROOM)
                                        .also { if (it == null) removalLimitReached = true }
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
                            removedParticipants?.forEach { participant ->
                                val session = participant.session
                                runCatching {
                                    session.sendMessage(
                                        WatchWireMessage(
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
                            }
                            if (!removedParticipants.isNullOrEmpty()) broadcastRoomUpdate(room)
                        }

                        "updateProfile" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            if (requireWatchAuthentication &&
                                (message.name != null || message.avatarId != null)
                            ) {
                                return@consumeEach sendError("个人资料无效", "profile_invalid")
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastProfileUpdateAtMs < PROFILE_UPDATE_COOLDOWN_MS) {
                                return@consumeEach
                            }
                            lastProfileUpdateAtMs = now
                            synchronized(room) {
                                val current =
                                    room.participants[clientId]
                                        ?.takeIf { it.session === this }
                                        ?: return@synchronized
                                current.name =
                                    if (requireWatchAuthentication) {
                                        authenticatedAccount.nickname
                                    } else {
                                        normalizeName(message.name)
                                    }
                                current.avatarId =
                                    if (requireWatchAuthentication) {
                                        authenticatedAccount.avatarId
                                    } else {
                                        normalizeAvatarId(message.avatarId, clientId)
                                    }
                            }
                            broadcastRoomUpdate(room)
                        }

                        "playbackStatus" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            if (message.latencyMs != null &&
                                message.latencyMs !in 0L..WatchProtocol.MAX_LATENCY_MS ||
                                message.syncDriftMs != null &&
                                message.syncDriftMs !in
                                -WatchProtocol.MAX_SYNC_DRIFT_MS..WatchProtocol.MAX_SYNC_DRIFT_MS ||
                                message.durationMs != null &&
                                message.durationMs !in 0L..WatchProtocol.MAX_TIMELINE_POSITION_MS
                            ) {
                                return@consumeEach sendError(
                                    "播放状态数据无效",
                                    "playback_status_invalid",
                                )
                            }
                            val change =
                                synchronized(room) {
                                    val participant =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                            ?: return@synchronized PlaybackStatusChange.None
                                    val nextMediaAvailable = message.mediaAvailable ?: true
                                    val nextBuffering = message.buffering == true && nextMediaAvailable
                                    val nextReady =
                                        message.ready == true &&
                                            nextMediaAvailable &&
                                            !nextBuffering
                                    val nextLatencyMs = message.latencyMs
                                    val nextSyncDriftMs = message.syncDriftMs
                                    val nextDurationMs = message.durationMs?.takeIf { it > 0L }
                                    val readinessDiffers =
                                        !participant.statusKnown ||
                                            participant.ready != nextReady ||
                                            participant.buffering != nextBuffering ||
                                            participant.mediaAvailable != nextMediaAvailable ||
                                            participant.durationMs != nextDurationMs
                                    val presenceDiffers =
                                        participant.latencyMs != nextLatencyMs ||
                                            participant.syncDriftMs != nextSyncDriftMs
                                    participant.statusKnown = true
                                    participant.ready = nextReady
                                    participant.buffering = nextBuffering
                                    participant.mediaAvailable = nextMediaAvailable
                                    participant.durationMs = nextDurationMs
                                    participant.latencyMs = nextLatencyMs
                                    participant.syncDriftMs = nextSyncDriftMs
                                    when {
                                        readinessDiffers -> PlaybackStatusChange.Readiness
                                        presenceDiffers -> PlaybackStatusChange.Presence
                                        else -> PlaybackStatusChange.None
                                    }
                                }
                            when (change) {
                                PlaybackStatusChange.Readiness -> broadcastRoomUpdate(room)
                                PlaybackStatusChange.Presence ->
                                    appScope.schedulePresenceBroadcast(room, presenceBroadcastIntervalMs)
                                PlaybackStatusChange.None -> Unit
                            }
                        }

                        "chat" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            if (!WatchProtocol.isValidChat(message.text)) {
                                return@consumeEach sendError(
                                    "消息为空、超过 30 字或内容无效",
                                    "chat_invalid",
                                    normalizeClientMessageId(message.clientMessageId),
                                )
                            }
                            if (message.clientMessageId != null &&
                                !WatchProtocol.isValidClientMessageId(message.clientMessageId)
                            ) {
                                return@consumeEach sendError(
                                    "消息标识无效，请重试",
                                    "chat_invalid",
                                )
                            }
                            val clientMessageId = normalizeClientMessageId(message.clientMessageId)
                            if (message.clientMessageId != null && clientMessageId == null) {
                                return@consumeEach sendError(
                                    "消息标识无效，请重试",
                                    "chat_invalid",
                                )
                            }
                            val text =
                                normalizeChat(message.text)
                                    ?: return@consumeEach sendError(
                                        "消息为空、超过 30 字或内容过长",
                                        "chat_invalid",
                                        clientMessageId,
                                    )

                            val existing =
                                clientMessageId?.let { requestedId ->
                                    synchronized(room) {
                                        room.chatHistory.firstOrNull {
                                            it.clientId == clientId &&
                                                it.clientMessageId == requestedId
                                        }
                                    }
                                }
                            if (existing != null) {
                                sendMessage(WatchWireMessage(type = "chat", chat = existing))
                                return@consumeEach
                            }

                            val now = System.currentTimeMillis()
                            val admission =
                                synchronized(room) {
                                    room.memberships[clientId]
                                        ?.admitChat(
                                            nowMs = now,
                                            maxPerWindow = MAX_CHAT_MESSAGES_PER_WINDOW,
                                            windowMs = CHAT_RATE_WINDOW_MS,
                                            muteAfterRejections = CHAT_MUTE_AFTER_REJECTIONS,
                                            rejectionWindowMs = CHAT_REJECTION_WINDOW_MS,
                                            muteMs = CHAT_MUTE_MS,
                                        ) ?: ChatAdmission.RateLimited
                                }
                            when (admission) {
                                ChatAdmission.Allowed -> Unit
                                ChatAdmission.RateLimited -> {
                                    WatchMetrics.chatRejected.incrementAndGet()
                                    return@consumeEach sendError(
                                        "发送太快了，请稍后再试",
                                        "chat_rate_limited",
                                        clientMessageId,
                                    )
                                }
                                is ChatAdmission.Muted -> {
                                    WatchMetrics.chatRejected.incrementAndGet()
                                    val seconds = ((admission.untilMs - now + 999L) / 1_000L).coerceAtLeast(1L)
                                    return@consumeEach sendError(
                                        "发送过于频繁，已暂停发言 $seconds 秒",
                                        "chat_muted",
                                        clientMessageId,
                                    )
                                }
                            }

                            val chat =
                                synchronized(room) {
                                    val sender = room.participants[clientId] ?: return@synchronized null
                                    WatchWireChatMessage(
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
                            val reaction =
                                message.reaction?.takeIf { it in REACTIONS }
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

                            val sender =
                                synchronized(room) { room.participants[clientId] }
                                    ?: return@consumeEach
                            broadcastReaction(room, clientId, sender.name, reaction)
                        }

                        "ping" -> {
                            if (message.serverAtMs != null ||
                                message.anchorAtMs != null ||
                                message.seq != null
                            ) {
                                return@consumeEach sendError("时钟消息无效", "clock_invalid")
                            }
                            val sentAt =
                                message.clientSentAtMs
                                    ?.takeIf { it in 0L..Long.MAX_VALUE }
                                    ?: return@consumeEach sendError("时钟序号无效", "clock_invalid")
                            sendMessage(WatchWireMessage(type = "pong", clientSentAtMs = sentAt))
                        }
                    }
                }
            } finally {
                // Runs even when this handler is itself being cancelled (shutdown, upstream
                // job cancelled): a suspending call that throws here would skip the member
                // removal below and leave a ghost that keeps the room alive forever.
                withContext(NonCancellable) {
                    remoteControlRelay.leave(this@webSocket)
                    cleanupSocket(
                        authWatchdog = authWatchdog,
                        joinedRoom = joinedRoom,
                        joinedClientId = joinedClientId,
                        session = this@webSocket,
                        connectionLease = connectionLease,
                        broadcastRoomUpdate = ::broadcastRoomUpdate,
                        scheduleHostHandover = { room -> appScope.scheduleHostHandover(room, hostGraceMs) },
                    )
                }
            }
        }
    }
}
