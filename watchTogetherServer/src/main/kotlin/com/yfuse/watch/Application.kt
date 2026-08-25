package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountRateLimiter
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.account.AccountWorkRejectedException
import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.account.accountRoutes
import com.yfuse.watch.migration.MigrationRelayBackend
import com.yfuse.watch.migration.migrationRelayRoutes
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWireParticipant
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.SQLTransientException
import java.util.concurrent.ThreadLocalRandom

/**
 * Wire protocol v6, wire-compatible with authenticated v5 for a rolling server-first upgrade.
 * The DTO and its validation limits live in `:watchTogetherProtocol`, so client and relay cannot
 * silently drift. Version 5 requires an authenticated Yfuse account for
 * every room connection and binds membership to its immutable account id. Room-scoped resume and
 * host capabilities continue to protect reconnection and host authority; public client ids never
 * authenticate either operation.
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

private val json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

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
internal const val MAX_ROOMS = 500
private const val DEFAULT_MAX_ACTIVE_ROOMS_PER_IP = 8
private const val DEFAULT_MAX_WATCH_CONNECTIONS = 256
private const val DEFAULT_MAX_WATCH_CONNECTIONS_PER_IP = 32
private const val DEFAULT_MAX_WATCH_CONNECTIONS_PER_ACCOUNT = 8
private const val MAX_CONFIGURED_WATCH_CONNECTIONS = 10_000
private const val MAX_PARTICIPANTS_PER_ROOM = 12
private const val MAX_MEMBERSHIPS_PER_ROOM = 64
private const val MAX_REMOVED_ACCOUNT_IDS_PER_ROOM = 256
private const val MAX_MESSAGES_PER_WINDOW = 240
private const val RATE_WINDOW_MS = 10_000L
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

/** Bursts are the point, so this is looser than chat — but still bounded. */
private const val MAX_REACTIONS_PER_WINDOW = 6
private const val REACTION_RATE_WINDOW_MS = 3_000L
private const val PROFILE_UPDATE_COOLDOWN_MS = 1_000L
private const val ACCOUNT_REVALIDATION_MS = 10_000L
private const val ACCOUNT_AUTH_RETRY_BASE_MS = 100L
private const val ACCOUNT_AUTH_RETRY_MAX_MS = 5_000L
private const val ACCOUNT_AUTH_RETRY_MAX_EXPONENT = 6
private const val ACCOUNT_AUTH_ATTEMPT_TIMEOUT_MS = 10_000L
private const val ACCOUNT_INITIAL_AUTH_MAX_TRANSIENT_FAILURES = 8
private val graphemeRegex = Regex("\\X")

/**
 * Bounds both handshakes and established sockets. A lease first consumes the global and network
 * identity budgets, then binds the authenticated account before any per-socket watchdog starts.
 * Every mutation is under one small lock and [Lease.close] is idempotent, so normal cleanup and the
 * WebSocket coroutine's completion callback can safely converge on the same release path.
 */
internal class WatchConnectionGate(
    private val globalLimit: Int,
    private val perIpLimit: Int,
    private val perAccountLimit: Int,
) {
    private val lock = Any()
    private var active = 0
    private val activeByIp = mutableMapOf<String, Int>()
    private val activeByAccount = mutableMapOf<String, Int>()

    init {
        require(globalLimit > 0)
        require(perIpLimit in 1..globalLimit)
        require(perAccountLimit in 1..globalLimit)
    }

    fun tryAcquire(clientIp: String): Lease? =
        synchronized(lock) {
            val ipCount = activeByIp[clientIp] ?: 0
            if (active >= globalLimit || ipCount >= perIpLimit) return@synchronized null
            active++
            activeByIp[clientIp] = ipCount + 1
            Lease(clientIp)
        }

    internal inner class Lease internal constructor(
        private val clientIp: String,
    ) : AutoCloseable {
        private var accountUserId: String? = null
        private var released = false

        fun tryBindAccount(userId: String): Boolean =
            synchronized(lock) {
                check(!released) { "connection lease is already released" }
                val current = accountUserId
                if (current != null) return@synchronized current == userId
                val accountCount = activeByAccount[userId] ?: 0
                if (accountCount >= perAccountLimit) return@synchronized false
                activeByAccount[userId] = accountCount + 1
                accountUserId = userId
                true
            }

        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                active--
                decrement(activeByIp, clientIp)
                accountUserId?.let { decrement(activeByAccount, it) }
            }
        }
    }

    private fun decrement(
        counts: MutableMap<String, Int>,
        key: String,
    ) {
        val remaining = checkNotNull(counts[key]) - 1
        if (remaining == 0) counts.remove(key) else counts[key] = remaining
    }
}

private sealed interface WatchAccountAuthentication {
    data class Accepted(
        val account: AuthenticatedAccount,
    ) : WatchAccountAuthentication

    data object Rejected : WatchAccountAuthentication

    data object TemporarilyUnavailable : WatchAccountAuthentication

    data object Failed : WatchAccountAuthentication
}

private suspend fun authenticateWatchAccount(
    authenticator: suspend (String) -> AuthenticatedAccount,
    accessToken: String,
): WatchAccountAuthentication =
    try {
        WatchAccountAuthentication.Accepted(
            withTimeout(ACCOUNT_AUTH_ATTEMPT_TIMEOUT_MS) {
                authenticator(accessToken)
            },
        )
    } catch (failure: AccountServiceException) {
        if (failure.problem == AccountProblem.Unauthorized) {
            WatchAccountAuthentication.Rejected
        } else {
            WatchAccountAuthentication.Failed
        }
    } catch (_: AccountWorkRejectedException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (_: TimeoutCancellationException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (_: SQLTransientException) {
        WatchAccountAuthentication.TemporarilyUnavailable
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: Exception) {
        WatchAccountAuthentication.Failed
    }

private fun nextWatchAuthFailureCount(current: Int): Int =
    (current + 1).coerceAtMost(ACCOUNT_AUTH_RETRY_MAX_EXPONENT + 1)

/** Full-jitter exponential retry, bounded so an account outage cannot create a retry storm. */
internal fun watchAuthTransientRetryDelayMs(failureCount: Int): Long {
    require(failureCount > 0) { "failureCount must be positive" }
    val exponent = (failureCount - 1).coerceAtMost(ACCOUNT_AUTH_RETRY_MAX_EXPONENT)
    val ceiling =
        (ACCOUNT_AUTH_RETRY_BASE_MS shl exponent)
            .coerceAtMost(ACCOUNT_AUTH_RETRY_MAX_MS)
    val floor = (ceiling / 2L).coerceAtLeast(1L)
    return ThreadLocalRandom.current().nextLong(floor, ceiling + 1L)
}

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

private enum class PlaylistMutationResult {
    Changed,
    Forbidden,
    Stale,
    Full,
    Duplicate,
    NotFound,
    IndexInvalid,
    Unchanged,
    RevisionExhausted,
}

private fun Room.mutatePlaylist(
    clientId: String,
    session: WebSocketSession,
    expectedRevision: Long,
    mutation: (MutableList<WatchWirePlaylistEntry>) -> PlaylistMutationResult,
): PlaylistMutationResult =
    synchronized(this) {
        val actor =
            participants[clientId]
                ?.takeIf { it.session === session }
                ?: return@synchronized PlaylistMutationResult.Forbidden
        if (!canEditPlaylist(actor)) return@synchronized PlaylistMutationResult.Forbidden
        if (playlistRevision != expectedRevision) return@synchronized PlaylistMutationResult.Stale
        if (playlistRevision == Long.MAX_VALUE) {
            return@synchronized PlaylistMutationResult.RevisionExhausted
        }
        mutation(playlist).also { result ->
            if (result == PlaylistMutationResult.Changed) playlistRevision++
        }
    }

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val host = resolveServerHost(System.getenv("HOST"))
    val accountBackend =
        AccountBackend.sqlite(
            File(System.getenv("ACCOUNT_DB_PATH") ?: "/var/lib/yfuse/account.db"),
        )
    val migrationRelayBackend = MigrationRelayBackend.fromEnvironment()
    embeddedServer(CIO, host = host, port = port) {
        productionWatchTogetherModule(
            accountBackend = accountBackend,
            migrationRelayBackend = migrationRelayBackend,
            requireWatchAuthentication = true,
        )
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

internal fun Application.watchTogetherModule(
    updateRoot: File = File(System.getenv("UPDATE_ROOT") ?: "/srv/yfuse-update/yfuse"),
    /** Injectable so tests can exercise the handover without waiting out the real window. */
    hostGraceMs: Long = HOST_GRACE_MS,
    /** Empty rooms retain their code briefly for reconnects, then release quota on sweep. */
    roomGraceMs: Long = ROOM_GRACE_MS,
    maxActiveRoomsPerIp: Int =
        System
            .getenv("WATCH_MAX_ACTIVE_ROOMS_PER_IP")
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_ROOMS)
            ?: DEFAULT_MAX_ACTIVE_ROOMS_PER_IP,
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
        )
    // Outlives any one socket, which is what a delayed host handover needs: the connection
    // whose loss starts the clock is precisely the one that can't run the timer.
    val appScope: CoroutineScope = this
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
    routing {
        calendarScheduleRoutes(calendarScheduleSigner)
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
                }}]}""",
                ContentType.Application.Json,
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
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "connection_limit"))
                return@webSocket
            }
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
                    var transientFailures = 0
                    var acceptedAccount: AuthenticatedAccount? = null
                    while (acceptedAccount == null) {
                        when (
                            val authentication =
                                authenticateWatchAccount(
                                    watchAccountAuthenticator,
                                    checkNotNull(accessToken),
                                )
                        ) {
                            is WatchAccountAuthentication.Accepted -> {
                                acceptedAccount = authentication.account
                            }
                            WatchAccountAuthentication.Rejected -> {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.VIOLATED_POLICY,
                                        "account_auth_expired",
                                    ),
                                )
                                return@webSocket
                            }
                            WatchAccountAuthentication.TemporarilyUnavailable -> {
                                transientFailures++
                                if (transientFailures >= ACCOUNT_INITIAL_AUTH_MAX_TRANSIENT_FAILURES) {
                                    close(
                                        CloseReason(
                                            CloseReason.Codes.TRY_AGAIN_LATER,
                                            "account_auth_temporarily_unavailable",
                                        ),
                                    )
                                    return@webSocket
                                }
                                delay(watchAuthRetryDelayMs(transientFailures).coerceAtLeast(1L))
                            }
                            WatchAccountAuthentication.Failed -> {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.INTERNAL_ERROR,
                                        "account_auth_unavailable",
                                    ),
                                )
                                return@webSocket
                            }
                        }
                    }
                    checkNotNull(acceptedAccount)
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
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "account_connection_limit"))
                return@webSocket
            }
            val authWatchdog =
                if (requireWatchAuthentication) {
                    launch {
                        var transientFailures = 0
                        while (true) {
                            val untilExpiry =
                                authenticatedAccount.accessExpiresAtEpochMs -
                                    watchAuthClock()
                            if (untilExpiry <= 0L) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.VIOLATED_POLICY,
                                        "account_auth_expired",
                                    ),
                                )
                                break
                            }
                            val delayMs =
                                if (transientFailures == 0) {
                                    watchAuthRevalidationMs
                                } else {
                                    watchAuthRetryDelayMs(transientFailures).coerceAtLeast(1L)
                                }
                            delay(minOf(delayMs, untilExpiry))
                            if (watchAuthClock() >= authenticatedAccount.accessExpiresAtEpochMs) {
                                close(
                                    CloseReason(
                                        CloseReason.Codes.VIOLATED_POLICY,
                                        "account_auth_expired",
                                    ),
                                )
                                break
                            }
                            when (
                                val authentication =
                                    authenticateWatchAccount(
                                        watchAccountRevalidator,
                                        checkNotNull(accessToken),
                                    )
                            ) {
                                is WatchAccountAuthentication.Accepted -> {
                                    if (
                                        authentication.account.sessionId !=
                                        authenticatedAccount.sessionId ||
                                        authentication.account.userId != authenticatedAccount.userId
                                    ) {
                                        close(
                                            CloseReason(
                                                CloseReason.Codes.VIOLATED_POLICY,
                                                "account_auth_expired",
                                            ),
                                        )
                                        break
                                    }
                                    transientFailures = 0
                                }
                                WatchAccountAuthentication.Rejected -> {
                                    close(
                                        CloseReason(
                                            CloseReason.Codes.VIOLATED_POLICY,
                                            "account_auth_expired",
                                        ),
                                    )
                                    break
                                }
                                WatchAccountAuthentication.TemporarilyUnavailable -> {
                                    transientFailures = nextWatchAuthFailureCount(transientFailures)
                                }
                                WatchAccountAuthentication.Failed -> {
                                    close(
                                        CloseReason(
                                            CloseReason.Codes.INTERNAL_ERROR,
                                            "account_auth_unavailable",
                                        ),
                                    )
                                    break
                                }
                            }
                        }
                    }
                } else {
                    null
                }
            var joinedRoom: Room? = null
            var joinedClientId: String? = null
            var windowStartedAtMs = System.currentTimeMillis()
            var messagesInWindow = 0
            val recentChatAtMs = ArrayDeque<Long>()
            val recentReactionAtMs = ArrayDeque<Long>()
            var lastProfileUpdateAtMs = 0L
            try {
                incoming.consumeEach { frame ->
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
                            json.decodeFromString(WatchWireMessage.serializer(), textFrame.readText())
                        }.getOrNull() ?: return@consumeEach sendError(
                            "消息格式无效",
                            "message_invalid",
                        )

                    if (message.type !in WatchProtocol.CLIENT_MESSAGE_TYPES) {
                        return@consumeEach sendError("消息类型无效", "message_type_invalid")
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
                                                initialPlaylist = initialPlaylist,
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
                                    roomStore.find(requestedRoomCode)
                                        ?: return@consumeEach sendError("房间不存在或已关闭")
                                }

                            var roomFull = false
                            var removedByHost = false
                            var authenticationFailed = false
                            var accountIdentityConflict = false
                            var hostAuthenticationFailed = false
                            var staleSession: WebSocketSession? = null
                            var issuedResumeCapability: String? = null
                            var issuedHostCapability: String? = null
                            val roomStillCurrent =
                                roomStore.mutateIfCurrent(room) {
                                    if (membershipAccountUserId in room.removedAccountUserIds) {
                                        removedByHost = true
                                        return@mutateIfCurrent
                                    }
                                    val membership = room.memberships[membershipAccountUserId]
                                    if (membership != null && membership.clientId != clientId) {
                                        accountIdentityConflict = true
                                        return@mutateIfCurrent
                                    }
                                    if (membership != null &&
                                        !capabilityMatches(
                                            room.code,
                                            clientId,
                                            CapabilityKind.Resume,
                                            message.resumeCapability,
                                            membership.resumeCapabilityDigest,
                                        )
                                    ) {
                                        authenticationFailed = true
                                        return@mutateIfCurrent
                                    }
                                    if (membership == null && message.resumeCapability != null) {
                                        authenticationFailed = true
                                        return@mutateIfCurrent
                                    }
                                    if (membership == null &&
                                        room.memberships.size >= MAX_MEMBERSHIPS_PER_ROOM
                                    ) {
                                        roomFull = true
                                        return@mutateIfCurrent
                                    }
                                    val isHost = clientId == room.hostId
                                    if (isHost &&
                                        membership != null &&
                                        !capabilityMatches(
                                            room.code,
                                            clientId,
                                            CapabilityKind.Host,
                                            message.hostCapability,
                                            room.hostCapabilityDigest,
                                        )
                                    ) {
                                        hostAuthenticationFailed = true
                                        return@mutateIfCurrent
                                    }
                                    val rejoining = membership != null
                                    if (!rejoining &&
                                        room.participants.size >= MAX_PARTICIPANTS_PER_ROOM
                                    ) {
                                        roomFull = true
                                        return@mutateIfCurrent
                                    }
                                    staleSession = room.participants[clientId]?.session
                                    val activeMembership =
                                        membership ?: newMembership(
                                            roomCode = room.code,
                                            clientId = clientId,
                                            accountUserId = membershipAccountUserId,
                                        ).let { (createdMembership, capability) ->
                                            room.memberships[membershipAccountUserId] = createdMembership
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
                            if (accountIdentityConflict) {
                                sendError(
                                    "当前账号已使用另一个客户端身份加入房间",
                                    "account_membership_conflict",
                                )
                                close(
                                    CloseReason(
                                        CloseReason.Codes.VIOLATED_POLICY,
                                        "account membership conflict",
                                    ),
                                )
                                return@consumeEach
                            }
                            if (authenticationFailed || hostAuthenticationFailed) {
                                sendError(
                                    if (hostAuthenticationFailed) "主持人凭据无效" else "重连凭据无效",
                                    if (hostAuthenticationFailed) "host_auth_failed" else "resume_auth_failed",
                                )
                                close(
                                    CloseReason(
                                        CloseReason.Codes.VIOLATED_POLICY,
                                        "credential rejected",
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
                            val targetSession =
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
                                    if (
                                        !rememberRemovedAccountUserId(
                                            room.removedAccountUserIds,
                                            participant.accountUserId,
                                        )
                                    ) {
                                        removalLimitReached = true
                                        return@synchronized null
                                    }
                                    room.participants.remove(target)
                                    room.memberships.remove(participant.accountUserId)
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
                                broadcastRoomUpdate(room)
                            }
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
                                -WatchProtocol.MAX_SYNC_DRIFT_MS..WatchProtocol.MAX_SYNC_DRIFT_MS
                            ) {
                                return@consumeEach sendError(
                                    "播放状态数据无效",
                                    "playback_status_invalid",
                                )
                            }
                            val changed =
                                synchronized(room) {
                                    val participant =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                            ?: return@synchronized false
                                    val nextMediaAvailable = message.mediaAvailable ?: true
                                    val nextBuffering = message.buffering == true && nextMediaAvailable
                                    val nextReady =
                                        message.ready == true &&
                                            nextMediaAvailable &&
                                            !nextBuffering
                                    val nextLatencyMs = message.latencyMs
                                    val nextSyncDriftMs = message.syncDriftMs
                                    val differs =
                                        !participant.statusKnown ||
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
                authWatchdog?.cancelAndJoin()
                val room = joinedRoom
                val clientId = joinedClientId
                if (room != null && clientId != null) {
                    // Guard against a stale connection's own cleanup evicting a client that
                    // has already reconnected on a new session: only the session currently
                    // on record for `clientId` is allowed to remove it.
                    var hostWentAbsent = false
                    val removedNow =
                        synchronized(room) {
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
                connectionLease.close()
            }
        }
    }
}

/**
 * Promotes the first remaining participant once a disconnected host's reconnect window
 * expires. The room is checked again after the delay because the host may have reconnected,
 * or a newer disconnect may have started a fresh grace window, while this coroutine slept.
 */
private fun CoroutineScope.scheduleHostHandover(
    room: Room,
    graceMs: Long,
) {
    launch {
        delay(graceMs)
        var grantedCapability: String? = null
        val newHost =
            synchronized(room) {
                if (
                    room.participants.containsKey(room.hostId) ||
                    !room.hostGraceExpired(graceMs)
                ) {
                    null
                } else {
                    val nextHost = room.participants.values.firstOrNull()
                    if (nextHost == null) {
                        null
                    } else {
                        grantedCapability = room.transferHostTo(nextHost)
                        nextHost
                    }
                }
            }
        if (newHost != null) {
            runCatching {
                newHost.session.sendMessage(
                    WatchWireMessage(
                        type = "hostCapabilityGranted",
                        hostCapability = grantedCapability,
                    ),
                )
            }
            broadcastRoomUpdate(room)
        }
    }
}

private fun Room.welcomeMessage(
    clientId: String,
    resumeCapability: String?,
    hostCapability: String?,
    protocolVersion: Int,
): WatchWireMessage =
    synchronized(this) {
        WatchWireMessage(
            type = "welcome",
            protocolVersion = protocolVersion,
            capabilities = WatchProtocol.SERVER_CAPABILITIES,
            roomCode = code,
            resumeCapability = resumeCapability,
            hostCapability = hostCapability,
            isHost = hostId == clientId,
            canControl = participants[clientId]?.let(::canControl) ?: false,
            controlMode = controlMode.wireValue,
            participantCount = participants.size,
            participants = wireParticipants(),
            chatHistory = chatHistory.toList(),
            playlist = playlist.toList(),
            playlistRevision = playlistRevision,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
        )
    }

private suspend fun WebSocketSession.sendMessage(message: WatchWireMessage) {
    send(
        json.encodeToString(
            WatchWireMessage.serializer(),
            message.copy(
                protocolVersion = message.protocolVersion ?: WatchProtocol.VERSION,
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
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            clientMessageId = clientMessageId,
        ),
    )
}

private suspend fun WebSocketSession.finishPlaylistMutation(
    room: Room,
    result: PlaylistMutationResult,
) {
    if (result == PlaylistMutationResult.Changed) {
        broadcastRoomUpdate(room)
        return
    }
    val (message, errorCode) =
        when (result) {
            PlaylistMutationResult.Forbidden ->
                "仅主持人和管理员可以编辑播放列表" to "playlist_forbidden"
            PlaylistMutationResult.Stale ->
                "播放列表已更新，请基于最新版本重试" to "playlist_stale"
            PlaylistMutationResult.Full -> "播放列表已满" to "playlist_full"
            PlaylistMutationResult.Duplicate -> "播放列表项目已存在" to "playlist_duplicate"
            PlaylistMutationResult.NotFound -> "播放列表项目不存在" to "playlist_not_found"
            PlaylistMutationResult.IndexInvalid -> "播放列表位置无效" to "playlist_index_invalid"
            PlaylistMutationResult.Unchanged -> "播放列表没有变化" to "playlist_unchanged"
            PlaylistMutationResult.RevisionExhausted ->
                "播放列表版本已耗尽" to "playlist_revision_exhausted"
            PlaylistMutationResult.Changed -> error("handled above")
        }
    val snapshot = synchronized(room) { room.playlist.toList() to room.playlistRevision }
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            playlist = snapshot.first,
            playlistRevision = snapshot.second,
        ),
    )
}

/** Membership or host changed; every member gets the current timeline too so a client that missed a `sync` can resync from this alone. */
private suspend fun broadcastRoomUpdate(room: Room) {
    val snapshot =
        synchronized(room) {
            RoomBroadcastSnapshot(
                members = room.participants.values.toList(),
                participants = room.wireParticipants(),
                timeline = room.timeline,
                hostId = room.hostId,
                controlMode = room.controlMode,
                playlist = room.playlist.toList(),
                playlistRevision = room.playlistRevision,
                canControlIds =
                    room.participants.keys
                        .filterTo(linkedSetOf()) { id ->
                            room.participants[id]?.let(room::canControl) == true
                        },
            )
        }
    snapshot.members.forEach { member ->
        val payload =
            WatchWireMessage(
                type = "roomUpdate",
                roomCode = room.code,
                isHost = member.id == snapshot.hostId,
                canControl = member.id in snapshot.canControlIds,
                controlMode = snapshot.controlMode.wireValue,
                participantCount = snapshot.members.size,
                participants = snapshot.participants,
                playlist = snapshot.playlist,
                playlistRevision = snapshot.playlistRevision,
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
    val participants: List<WatchWireParticipant>,
    val timeline: Timeline,
    val hostId: String,
    val controlMode: ControlMode,
    val playlist: List<WatchWirePlaylistEntry>,
    val playlistRevision: Long,
    val canControlIds: Set<String>,
)

private fun Room.wireParticipants(): List<WatchWireParticipant> =
    participants.values.map { participant ->
        WatchWireParticipant(
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
            canControl = canControl(participant),
            isModerator = participant.id in moderatorIds,
        )
    }

private suspend fun broadcastChat(
    room: Room,
    chat: WatchWireChatMessage,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    members.forEach { member ->
        runCatching { member.session.sendMessage(WatchWireMessage(type = "chat", chat = chat)) }
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
                WatchWireMessage(
                    type = "reaction",
                    clientId = clientId,
                    name = name,
                    reaction = reaction,
                ),
            )
        }
    }
}

private fun normalizeName(raw: String?): String =
    raw
        .orEmpty()
        .replace('\r', ' ')
        .replace('\n', ' ')
        .filterNot { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }
        .trim()
        .takeGraphemes(WatchProtocol.MAX_NAME_GRAPHEMES)
        .takeGraphemesWithinUtf8Bytes(WatchProtocol.MAX_NAME_BYTES)
        .ifBlank { "影友" }

private fun normalizeAvatarId(
    raw: Int?,
    clientId: String,
): Int =
    raw?.takeIf { it in 0 until WatchProtocol.AVATAR_COUNT }
        ?: ((clientId.hashCode() and Int.MAX_VALUE) % WatchProtocol.AVATAR_COUNT)

private fun normalizeChat(raw: String?): String? = raw?.takeIf(WatchProtocol::isValidChat)

internal fun normalizeClientId(raw: String?): String? = raw?.takeIf(WatchProtocol::isValidClientId)

internal fun rememberRemovedAccountUserId(
    removedAccountUserIds: MutableSet<String>,
    accountUserId: String,
    limit: Int = MAX_REMOVED_ACCOUNT_IDS_PER_ROOM,
): Boolean {
    require(limit > 0) { "limit must be positive" }
    if (accountUserId in removedAccountUserIds) return true
    if (removedAccountUserIds.size >= limit) return false
    removedAccountUserIds.add(accountUserId)
    return true
}

private fun normalizeClientMessageId(raw: String?): String? = raw?.takeIf(WatchProtocol::isValidClientMessageId)

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

private suspend fun broadcastSync(
    room: Room,
    timeline: Timeline,
) {
    val members = synchronized(room) { room.participants.values.toList() }
    val payload =
        WatchWireMessage(
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
