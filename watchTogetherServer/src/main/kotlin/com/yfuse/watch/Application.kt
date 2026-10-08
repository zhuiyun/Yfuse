package com.yfuse.watch

import com.yfuse.watch.account.AccountBackend
import com.yfuse.watch.account.AccountExecutionPolicy
import com.yfuse.watch.account.AccountProblem
import com.yfuse.watch.account.AccountRateLimiter
import com.yfuse.watch.account.AccountServiceException
import com.yfuse.watch.account.AccountWorkExecutor
import com.yfuse.watch.account.AccountWorkRejectedException
import com.yfuse.watch.account.AuthenticatedAccount
import com.yfuse.watch.account.PlaybackRelayStoreProvider
import com.yfuse.watch.account.accountRoutes
import com.yfuse.watch.migration.MigrationRelayBackend
import com.yfuse.watch.migration.migrationRelayRoutes
import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireChatMessage
import com.yfuse.watch.protocol.WatchWireMessage
import com.yfuse.watch.protocol.WatchWirePlaylistEntry
import io.ktor.http.ContentType
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
import io.ktor.server.websocket.DefaultWebSocketServerSession
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
 * Latency and drift changes are coalesced into one room update per interval: they change
 * every second on every guest, and a full snapshot per change made steady-state traffic
 * grow with the square of the room size.
 */
private const val PRESENCE_BROADCAST_INTERVAL_MS = 5_000L

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
private const val DEFAULT_PENDING_FLOOR = 8
private const val DEFAULT_PENDING_PER_IP = 4
private const val MAX_CONFIGURED_WATCH_CONNECTIONS = 10_000
private const val MAX_PARTICIPANTS_PER_ROOM = 12
private const val MAX_MEMBERSHIPS_PER_ROOM = 64
private const val MAX_REMOVED_ACCOUNT_IDS_PER_ROOM = 256
private const val MAX_MESSAGES_PER_WINDOW = 240

/** Rooms that emptied past their grace are released this often even when nobody says hello. */
private const val ROOM_SWEEP_INTERVAL_MS = 60_000L

/** A paced-out `sync` reaches the room as one trailing timeline broadcast after this. */
private const val TRAILING_SYNC_DELAY_MS = 1_000L
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
    /**
     * Sockets that have not authenticated yet get their own, smaller pool: without one, a few
     * addresses holding unauthenticated connections open could fill the whole global quota.
     */
    private val pendingLimit: Int = maxOf(globalLimit / 4, minOf(globalLimit, DEFAULT_PENDING_FLOOR)),
    private val pendingPerIpLimit: Int = minOf(perIpLimit, DEFAULT_PENDING_PER_IP),
) {
    private val lock = Any()
    private var active = 0
    private var pending = 0
    private val activeByIp = mutableMapOf<String, Int>()
    private val pendingByIp = mutableMapOf<String, Int>()
    private val activeByAccount = mutableMapOf<String, Int>()

    init {
        require(globalLimit > 0)
        require(perIpLimit in 1..globalLimit)
        require(perAccountLimit in 1..globalLimit)
        require(pendingLimit in 1..globalLimit)
        require(pendingPerIpLimit in 1..perIpLimit)
    }

    fun tryAcquire(clientIp: String): Lease? =
        synchronized(lock) {
            val ipActive = activeByIp[clientIp] ?: 0
            val ipPending = pendingByIp[clientIp] ?: 0
            if (
                active + pending >= globalLimit ||
                ipActive + ipPending >= perIpLimit ||
                pending >= pendingLimit ||
                ipPending >= pendingPerIpLimit
            ) {
                return@synchronized null
            }
            pending++
            pendingByIp[clientIp] = ipPending + 1
            Lease(clientIp)
        }

    internal inner class Lease internal constructor(
        private val clientIp: String,
    ) : AutoCloseable {
        private var accountUserId: String? = null
        private var promoted = false
        private var released = false

        /** Moves the socket from the pending pool to the active pool once its account is known. */
        fun tryBindAccount(userId: String): Boolean =
            synchronized(lock) {
                check(!released) { "connection lease is already released" }
                val current = accountUserId
                if (current != null) return@synchronized current == userId
                val accountCount = activeByAccount[userId] ?: 0
                if (accountCount >= perAccountLimit) return@synchronized false
                activeByAccount[userId] = accountCount + 1
                accountUserId = userId
                promoteLocked()
                true
            }

        /** Test-only unauthenticated mode: the socket is admitted without an account. */
        fun promote() {
            synchronized(lock) {
                check(!released) { "connection lease is already released" }
                promoteLocked()
            }
        }

        private fun promoteLocked() {
            if (promoted) return
            promoted = true
            pending--
            decrement(pendingByIp, clientIp)
            active++
            activeByIp[clientIp] = (activeByIp[clientIp] ?: 0) + 1
        }

        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                if (promoted) {
                    active--
                    decrement(activeByIp, clientIp)
                } else {
                    pending--
                    decrement(pendingByIp, clientIp)
                }
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
    RateLimited,
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
    /** Bearer token for `/watch/metrics`; null limits it to on-box callers that bypass the proxy. */
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
    /** Durable room state; null keeps rooms in memory only, as tests do. */
    roomStateStore: WatchStateStore? = null,
    /** 手机遥控's pairings; injectable so tests can look at them. */
    remoteControlRelay: RemoteControlRelay<WebSocketSession> = RemoteControlRelay(),
    /** Injectable so tests can observe coalescing without waiting. */
    roomUpdateMinIntervalMs: Long = ROOM_UPDATE_MIN_INTERVAL_MS,
    /** Injectable so tests can see an emptied room released without a `hello`. */
    roomSweepIntervalMs: Long = ROOM_SWEEP_INTERVAL_MS,
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
    val roomUpdates = RoomUpdateBroadcaster(appScope, roomUpdateMinIntervalMs)
    val broadcastRoomUpdate: suspend (Room) -> Unit = roomUpdates::request
    val roomPersister = roomStateStore?.let(::RoomStatePersister)
    if (roomPersister != null) {
        // Rooms used to live only in memory, so every deploy ended every room. Members whose
        // saved capabilities still match rejoin the same room once the relay is back.
        runCatching { roomStore.restore(roomPersister.restore()) }
            .onSuccess { restored ->
                restored.forEach { room -> appScope.scheduleHostHandover(room, hostGraceMs, broadcastRoomUpdate) }
                if (restored.isNotEmpty()) ServerLog.info("watch_rooms_restored", "rooms" to restored.size)
            }.onFailure { failure -> ServerLog.error("watch_rooms_restore_failed", throwable = failure) }
        monitor.subscribe(ApplicationStopped) {
            try {
                roomPersister.sync(roomStore.allRooms(), everything = true)
            } catch (failure: Exception) {
                ServerLog.error("watch_rooms_save_failed", throwable = failure)
            } finally {
                roomStateStore.close()
            }
        }
    }
    appScope.launchRoomMaintenance(roomStore, roomPersister, roomSweepIntervalMs)
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
    // Account, migration and QoE handlers set `no-store` themselves; the calendar feed is the one
    // `/api` response meant to be cached, so no blanket prefix rule belongs here.
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
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
            if (!metricsRequestAllowed(call.request.origin.remoteHost, call.request.headers, metricsToken)) {
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
                                WatchMetrics.authFailures.incrementAndGet()
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
                WatchMetrics.connectionsRejected.incrementAndGet()
                close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "account_connection_limit"))
                return@webSocket
            }
            if (!requireWatchAuthentication) connectionLease.promote()
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
            val pacer = WatchMessagePacer()
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
                            json.decodeFromString(WatchWireMessage.serializer(), textFrame.readText())
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
                                    val failureKeys = joinFailureKeys(clientIp, membershipAccountUserId)
                                    if (failureKeys.any { key -> joinFailureLimiter.isPenalized(key) }) {
                                        WatchMetrics.joinsRejected.incrementAndGet()
                                        return@consumeEach sendError(
                                            "加入失败次数过多，请稍后再试",
                                            "join_rate_limited",
                                        )
                                    }
                                    roomStore.find(requestedRoomCode)
                                        ?: run {
                                            // Both keys count every miss; no short-circuit.
                                            val penalized =
                                                failureKeys.map { key ->
                                                    joinFailureLimiter.recordFailure(key)
                                                }
                                            if (true in penalized) {
                                                ServerLog.warn("room_join_penalized", "ip" to clientIp)
                                            }
                                            return@consumeEach sendError("房间不存在或已关闭", "room_not_found")
                                        }
                                }

                            val wantsRoomDeltas =
                                WatchProtocol.CAPABILITY_ROOM_REVISION in message.capabilities.orEmpty()
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
                                    val membership = room.memberships[memberKey(membershipAccountUserId, clientId)]
                                    val isHost = clientId == room.hostId
                                    staleSession = room.participants[clientId]?.session
                                    val activeMembership =
                                        membership ?: newMembership(
                                            roomCode = room.code,
                                            clientId = clientId,
                                            accountUserId = membershipAccountUserId,
                                        ).let { (createdMembership, capability) ->
                                            room.memberships[createdMembership.key] = createdMembership
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
                                            roomDeltas = wantsRoomDeltas,
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
                                        room.hostGraceExpired(hostGraceMs) &&
                                        membership?.predates(room.hostAbsentSinceMs) == true
                                    ) {
                                        // The host slot points at someone who left and did not
                                        // come back in time. A member who was already here when
                                        // the host left takes it, rather than leaving the room
                                        // locked to a host who may never return. Someone who
                                        // only now found the code does not inherit the room.
                                        issuedHostCapability = room.transferHostTo(participant)
                                    }
                                }
                            if (!roomStillCurrent) {
                                return@consumeEach sendError("房间不存在或已关闭", "room_not_found")
                            }
                            joinRejected?.let { rejection ->
                                sendError(rejection.message, rejection.code)
                                rejection.closeReason?.let { reason ->
                                    val code =
                                        if (rejection.retryLater) {
                                            CloseReason.Codes.TRY_AGAIN_LATER
                                        } else {
                                            CloseReason.Codes.VIOLATED_POLICY
                                        }
                                    close(CloseReason(code, reason))
                                }
                                return@consumeEach
                            }
                            // Only an admission forgives, and only the address: see the limiter.
                            if (message.roomCode != null) joinFailureLimiter.clear(joinFailureAddressKey(clientIp))
                            staleSession?.let {
                                withTimeoutOrNull(BROADCAST_SEND_TIMEOUT_MS) {
                                    runCatching {
                                        it.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "reconnected"))
                                    }
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
                                        anchorAtServerMs = WatchClock.nowMs(),
                                        rate = message.rate!!,
                                        paused = message.paused!!,
                                        seq = room.timeline.seq + 1,
                                    ).also { room.timeline = it }
                                }
                            if (controlDenied) {
                                sendError("当前没有播放控制权限", "control_denied")
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
                            // The anchor is already the room's; past the pace only its broadcast waits,
                            // and goes out once for everything that arrived meanwhile.
                            if (pacer.admit(PacedAction.Sync)) {
                                broadcastSync(room, timeline)
                            } else {
                                appScope.scheduleTrailingSync(room)
                            }
                        }

                        // Control handoff. Guests used to have no way to ask for the
                        // timeline and hosts no way to give it up, so a room whose host had
                        // stopped paying attention could not be steered by anyone.
                        "requestControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            if (!pacer.admit(PacedAction.Control)) return@consumeEach
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
                            hostSession?.deliverDirect(
                                WatchWireMessage(
                                    type = "controlRequested",
                                    clientId = clientId,
                                    name = askerName,
                                ),
                            )
                        }

                        "grantControl" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val target =
                                normalizeClientId(message.targetClientId)
                                    ?: return@consumeEach
                            if (!pacer.admit(PacedAction.Control)) {
                                return@consumeEach sendError("操作太频繁，请稍后再试", "control_rate_limited")
                            }
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
                                // The target may be gone or stalled; that is its problem, not the granting
                                // host's, whose read loop an exception here used to end.
                                targetSession?.deliverDirect(
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
                            targetSession?.deliverDirect(WatchWireMessage(type = "controlDenied"))
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
                            if (!pacer.admit(PacedAction.Control)) {
                                return@consumeEach sendError("操作太频繁，请稍后再试", "control_rate_limited")
                            }
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
                            if (!pacer.admit(PacedAction.Control)) {
                                return@consumeEach sendError("操作太频繁，请稍后再试", "control_rate_limited")
                            }
                            val changed =
                                synchronized(room) {
                                    val actor =
                                        room.participants[clientId]
                                            ?.takeIf { it.session === this }
                                    val targetMember = room.participants[target]
                                    if (
                                        actor == null ||
                                        !room.isAuthorizedHost(actor) ||
                                        target == room.hostId ||
                                        targetMember == null
                                    ) {
                                        return@synchronized false
                                    }
                                    if (enabled) {
                                        room.moderatorKeys.add(targetMember.memberKey)
                                    } else {
                                        room.moderatorKeys.remove(targetMember.memberKey)
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
                            if (!pacer.admit(PacedAction.Playlist)) {
                                return@consumeEach finishPlaylistMutation(
                                    room,
                                    PlaylistMutationResult.RateLimited,
                                    broadcastRoomUpdate,
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
                            finishPlaylistMutation(room, result, broadcastRoomUpdate)
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
                            if (!pacer.admit(PacedAction.Playlist)) {
                                return@consumeEach finishPlaylistMutation(
                                    room,
                                    PlaylistMutationResult.RateLimited,
                                    broadcastRoomUpdate,
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
                            finishPlaylistMutation(room, result, broadcastRoomUpdate)
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
                            if (!pacer.admit(PacedAction.Playlist)) {
                                return@consumeEach finishPlaylistMutation(
                                    room,
                                    PlaylistMutationResult.RateLimited,
                                    broadcastRoomUpdate,
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
                            finishPlaylistMutation(room, result, broadcastRoomUpdate)
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
                            if (!pacer.admit(PacedAction.Playlist)) {
                                return@consumeEach finishPlaylistMutation(
                                    room,
                                    PlaylistMutationResult.RateLimited,
                                    broadcastRoomUpdate,
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
                            finishPlaylistMutation(room, result, broadcastRoomUpdate)
                        }

                        "kickParticipant" -> {
                            val room = joinedRoom ?: return@consumeEach
                            val clientId = joinedClientId ?: return@consumeEach
                            val target =
                                normalizeClientId(message.targetClientId)
                                    ?: return@consumeEach
                            if (!pacer.admit(PacedAction.Control)) {
                                return@consumeEach sendError("操作太频繁，请稍后再试", "control_rate_limited")
                            }
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
                                session.deliverDirect(
                                    WatchWireMessage(
                                        type = "kicked",
                                        message = "你已被房主移出当前房间",
                                        errorCode = "removed_by_host",
                                    ),
                                )
                                withTimeoutOrNull(BROADCAST_SEND_TIMEOUT_MS) {
                                    runCatching {
                                        session.close(
                                            CloseReason(
                                                CloseReason.Codes.VIOLATED_POLICY,
                                                "removed by host",
                                            ),
                                        )
                                    }
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
                                // Past the pace a readiness flip still lands, on the coalesced presence update.
                                PlaybackStatusChange.Readiness ->
                                    if (pacer.admit(PacedAction.Readiness)) {
                                        broadcastRoomUpdate(room)
                                    } else {
                                        appScope.schedulePresenceBroadcast(
                                            room,
                                            presenceBroadcastIntervalMs,
                                            broadcastRoomUpdate,
                                        )
                                    }
                                PlaybackStatusChange.Presence ->
                                    appScope.schedulePresenceBroadcast(
                                        room,
                                        presenceBroadcastIntervalMs,
                                        broadcastRoomUpdate,
                                    )
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
                                    room.participants[clientId]
                                        ?.let { sender -> room.memberships[sender.memberKey] }
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
                        broadcastRoomUpdate = broadcastRoomUpdate,
                        scheduleHostHandover = { room ->
                            appScope.scheduleHostHandover(room, hostGraceMs, broadcastRoomUpdate)
                        },
                    )
                }
            }
        }
    }
}

private suspend fun cleanupSocket(
    authWatchdog: Job?,
    joinedRoom: Room?,
    joinedClientId: String?,
    session: DefaultWebSocketServerSession,
    connectionLease: AutoCloseable,
    broadcastRoomUpdate: suspend (Room) -> Unit,
    scheduleHostHandover: (Room) -> Unit,
) {
    try {
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
                    val isActiveSession = room.participants[clientId]?.session === session
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
                scheduleHostHandover(room)
            }
            if (removedNow && room.participants.isNotEmpty()) broadcastRoomUpdate(room)
        }
    } finally {
        connectionLease.close()
    }
}

/**
 * Promotes the first remaining participant who was already in the room when the host left, once
 * a disconnected host's reconnect window expires. The room is checked again after the delay
 * because the host may have reconnected, or a newer disconnect may have started a fresh grace
 * window, while this coroutine slept.
 */
private fun CoroutineScope.scheduleHostHandover(
    room: Room,
    graceMs: Long,
    broadcastRoomUpdate: suspend (Room) -> Unit,
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
                    val leftAtMs = room.hostAbsentSinceMs
                    val nextHost =
                        room.participants.values.firstOrNull { participant ->
                            room.memberships[participant.memberKey]?.predates(leftAtMs) == true
                        }
                    if (nextHost == null) {
                        null
                    } else {
                        grantedCapability = room.transferHostTo(nextHost)
                        nextHost
                    }
                }
            }
        if (newHost != null) {
            newHost.session.deliverDirect(
                WatchWireMessage(
                    type = "hostCapabilityGranted",
                    hostCapability = grantedCapability,
                ),
            )
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
        // Its own revision: a room update taken before it, still on its way, is older than this.
        val revision = nextRoomRevision()
        participants[clientId]?.playlistRevisionSent = playlistRevision
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
            roomRevision = revision,
        )
    }

private suspend fun WebSocketSession.sendMessage(message: WatchWireMessage) {
    send(encodeWatchMessage(message))
}

/** Which part of a `playbackStatus` report changed, and therefore how urgently it is fanned out. */
private enum class PlaybackStatusChange {
    /** Ready, buffering, media or duration changed: the room's start gate depends on it. */
    Readiness,

    /** Only latency or drift changed: informational, coalesced per [PRESENCE_BROADCAST_INTERVAL_MS]. */
    Presence,
    None,
}

/**
 * Coalesces latency/drift-only updates: the first report arms one delayed room update, and
 * every further report inside the window rides on it.
 */
private fun CoroutineScope.schedulePresenceBroadcast(
    room: Room,
    intervalMs: Long,
    broadcastRoomUpdate: suspend (Room) -> Unit,
) {
    val armed =
        synchronized(room) {
            if (room.presenceBroadcastPending) {
                false
            } else {
                room.presenceBroadcastPending = true
                true
            }
        }
    if (!armed) return
    launch {
        try {
            delay(intervalMs)
        } finally {
            synchronized(room) { room.presenceBroadcastPending = false }
        }
        val stillPopulated = synchronized(room) { room.participants.isNotEmpty() }
        if (stillPopulated) broadcastRoomUpdate(room)
    }
}

/** One trailing `sync` with the room's latest anchor, for anchors that arrived past the pace. */
private fun CoroutineScope.scheduleTrailingSync(room: Room) {
    val armed =
        synchronized(room) {
            if (room.trailingSyncPending) {
                false
            } else {
                room.trailingSyncPending = true
                true
            }
        }
    if (!armed) return
    launch {
        try {
            delay(TRAILING_SYNC_DELAY_MS)
        } finally {
            synchronized(room) { room.trailingSyncPending = false }
        }
        val timeline = synchronized(room) { room.timeline.takeIf { room.participants.isNotEmpty() } }
        if (timeline != null) broadcastSync(room, timeline)
    }
}

/**
 * Releases rooms whose grace ran out even when nobody says hello — they used to be swept only on
 * the next `hello` — and keeps their persisted state in step.
 */
private fun CoroutineScope.launchRoomMaintenance(
    roomStore: RoomStore,
    persister: RoomStatePersister?,
    sweepIntervalMs: Long,
) = launch(Dispatchers.IO) {
    val intervalMs = if (persister != null) minOf(ROOM_PERSIST_INTERVAL_MS, sweepIntervalMs) else sweepIntervalMs
    var lastSweepAtMs = monotonicMs()
    while (isActive) {
        delay(intervalMs)
        if (monotonicMs() - lastSweepAtMs >= sweepIntervalMs) {
            roomStore.sweepExpiredRooms()
            lastSweepAtMs = monotonicMs()
        }
        if (persister != null) {
            try {
                persister.sync(roomStore.allRooms())
            } catch (failure: Exception) {
                ServerLog.warn("watch_rooms_save_failed", "reason" to failure::class.simpleName)
            }
        }
    }
}

/** Every error carries a stable [errorCode] and says whether the same request may succeed later. */
private suspend fun WebSocketSession.sendError(
    message: String,
    errorCode: String,
    clientMessageId: String? = null,
) {
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            retryable = WatchProtocol.isRetryableErrorCode(errorCode),
            clientMessageId = clientMessageId,
        ),
    )
}

private suspend fun WebSocketSession.finishPlaylistMutation(
    room: Room,
    result: PlaylistMutationResult,
    broadcastRoomUpdate: suspend (Room) -> Unit,
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
            PlaylistMutationResult.RateLimited ->
                "播放列表操作太频繁，请稍后再试" to "playlist_rate_limited"
            PlaylistMutationResult.Changed -> error("handled above")
        }
    val snapshot = synchronized(room) { room.playlist.toList() to room.playlistRevision }
    sendMessage(
        WatchWireMessage(
            type = "error",
            message = message,
            errorCode = errorCode,
            retryable = WatchProtocol.isRetryableErrorCode(errorCode),
            playlist = snapshot.first,
            playlistRevision = snapshot.second,
        ),
    )
}

private suspend fun broadcastChat(
    room: Room,
    chat: WatchWireChatMessage,
) {
    broadcastToRoom(room, WatchWireMessage(type = "chat", chat = chat))
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
    broadcastToRoom(
        room,
        WatchWireMessage(
            type = "reaction",
            clientId = clientId,
            name = name,
            reaction = reaction,
        ),
    )
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
    broadcastToRoom(
        room,
        WatchWireMessage(
            type = "sync",
            roomCode = room.code,
            mediaKey = timeline.mediaKey,
            positionMs = timeline.anchorPositionMs,
            paused = timeline.paused,
            rate = timeline.rate,
            seq = timeline.seq,
            anchorAtMs = timeline.anchorAtServerMs,
        ),
    )
}
