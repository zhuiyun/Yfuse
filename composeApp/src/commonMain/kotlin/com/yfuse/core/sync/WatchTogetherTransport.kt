package com.yfuse.core.sync

import com.yfuse.watch.protocol.WatchProtocol
import com.yfuse.watch.protocol.WatchWireCredential
import com.yfuse.watch.protocol.WatchWireMessage
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class RoomUnavailableException(
    message: String,
) : Exception(message)

/**
 * The relay refused to let this connection in for now — pacing, a full room, another device
 * under the same id — and said so ([WatchProtocol.isRetryableError]). Unlike
 * [RoomUnavailableException] the room is kept: a reconnect or 回到房间 later can still enter it.
 */
internal class RoomTemporarilyUnavailableException(
    message: String,
) : Exception(message)

/** The relay stopped answering pings on a socket that still looks open. */
internal class WatchConnectionStaleException : CancellationException("watch relay stopped answering")

internal class AccountRequiredForWatchException : Exception("请先登录 Yfuse 账号后使用一起看")

internal class WatchAuthenticationException : Exception("一起看登录状态已失效")

/** Maps the server epoch clock onto the process monotonic clock using ping/pong samples. */
internal class ClockSync {
    private data class ServerSample(
        val serverAtArrivalMs: Long,
        val receivedAt: TimeSource.Monotonic.ValueTimeMark,
    )

    private val lock = Any()
    private val samples = ArrayDeque<ServerSample>()
    private val rttSamples = ArrayDeque<Long>()
    private val inFlight = HashMap<Long, TimeSource.Monotonic.ValueTimeMark>()
    private var nextPingId = 0L

    fun startPing(): Long =
        synchronized(lock) {
            val pingId = ++nextPingId
            if (inFlight.size > MAX_IN_FLIGHT) inFlight.clear()
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

    /** The server clock estimate, or null until at least one pong has been recorded. */
    fun serverNowOrNull(): Long? =
        synchronized(lock) {
            if (samples.isEmpty()) return@synchronized null
            samples
                .map { it.serverAtArrivalMs + it.receivedAt.elapsedNow().inWholeMilliseconds }
                .sorted()[samples.size / 2]
        }

    /**
     * Falls back to the device clock before any sample exists. Callers that would seek on this
     * value must use [serverNowOrNull] instead: a wrong device clock is not a room timeline.
     */
    fun serverNow(): Long = serverNowOrNull() ?: System.currentTimeMillis()

    private companion object {
        const val MAX_SAMPLES = 7
        const val MAX_ACCEPTABLE_RTT_MS = 4_000L
        const val MAX_IN_FLIGHT = 16
    }
}

internal data class LocalPlaybackStatus(
    val ready: Boolean = false,
    val buffering: Boolean = true,
    val mediaAvailable: Boolean = true,
    val syncDriftMs: Long? = null,
    /** Local media length when the player knows it; null until then. */
    val durationMs: Long? = null,
)

private data class QueuedWatchMessage<Owner : Any, Message>(
    val owner: Owner,
    val message: Message,
    val onResult: ((Boolean) -> Unit)? = null,
)

/** Bounded single-consumer outbox tied to a specific connection owner. */
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

/** Tracks the currently armed ACK deadline for each optimistic chat row. */
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

/**
 * Whether a watch socket still hears the relay. The app pings every [intervalMs] and the relay
 * answers each with `pong`; a socket that has heard nothing for [missedIntervals] intervals is
 * half-open — the network went away without closing it — and only looked connected, for minutes,
 * until the operating system gave up on it. Any frame counts as hearing the relay.
 */
internal class WatchSocketLiveness(
    private val intervalMs: Long = PING_INTERVAL_MS,
    private val missedIntervals: Int = PONG_MISSED_INTERVALS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    @Volatile
    private var lastHeard: TimeMark = timeSource.markNow()

    fun heard() {
        lastHeard = timeSource.markNow()
    }

    fun isStale(): Boolean = lastHeard.elapsedNow().inWholeMilliseconds >= intervalMs * missedIntervals
}

/**
 * Keeps one relay socket's account access fresh without reconnecting, under
 * [WatchProtocol.CAPABILITY_REAUTHENTICATE]: a relay that knows it says when the socket's access
 * lapses ([renewed]), and this sends `reauthenticate` with a fresh token [leadMs] before then. A
 * token some other part of the app already refreshed is sent as it is ([currentToken]); otherwise
 * the account is refreshed ([refreshToken]). When the relay finds the socket's token replaced or
 * revoked it says so ([required]) and this renews at once. A relay that says nothing never hears
 * from it, and its sockets close at expiry and reconnect as before.
 */
internal class WatchAccessRenewal(
    initialToken: String,
    private val currentToken: suspend () -> String?,
    private val refreshToken: suspend () -> String?,
    private val leadMs: Long = WATCH_REAUTH_LEAD_MS,
    private val retryMs: Long = WATCH_REAUTH_RETRY_MS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var sentToken: String = initialToken

    @Volatile
    private var dueAt: TimeMark? = null

    @Volatile
    private var urgent = false

    /** `reauthenticated`: the socket lasts until [authExpiresAtMs] on the relay clock that stamped [serverAtMs]. */
    fun renewed(
        authExpiresAtMs: Long?,
        serverAtMs: Long?,
    ) {
        if (authExpiresAtMs == null || serverAtMs == null) return
        val untilDue = (authExpiresAtMs - serverAtMs - leadMs).coerceAtLeast(0L)
        dueAt = timeSource.markNow() + untilDue.milliseconds
        wake.trySend(Unit)
    }

    /** `reauth_required`: renew now. */
    fun required() {
        urgent = true
        wake.trySend(Unit)
    }

    /** Another `reauth_*` error: try again soon when it may pass, else leave it to expiry. */
    fun failed(retryable: Boolean) {
        dueAt = if (retryable) timeSource.markNow() + retryMs.milliseconds else null
        wake.trySend(Unit)
    }

    /** Runs for the socket's life, sending through [send]; never throws for a failed token fetch. */
    suspend fun run(send: suspend (WatchWireMessage) -> Unit) {
        while (true) {
            if (!urgent) {
                val due = dueAt
                if (due == null) {
                    wake.receive()
                    continue
                }
                val waitMs = -due.elapsedNow().inWholeMilliseconds
                if (waitMs > 0L) {
                    withTimeoutOrNull(waitMs) { wake.receive() }
                    continue
                }
            }
            urgent = false
            dueAt = null
            val token =
                try {
                    currentToken()?.takeIf { it != sentToken } ?: refreshToken()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (token == null) {
                // Signed out or offline: try once more shortly; the relay closes the socket at
                // expiry otherwise, and the usual reconnect takes over.
                dueAt = timeSource.markNow() + retryMs.milliseconds
                continue
            }
            sentToken = token
            send(WatchWireMessage(type = "reauthenticate", credential = WatchWireCredential(accessToken = token)))
        }
    }
}

/** Feeds a `reauth_*` error to [renewal]; false for any other error. */
internal fun WatchAccessRenewal.handles(error: WatchWireMessage): Boolean {
    val code = error.errorCode ?: return false
    if (!code.startsWith("reauth_")) return false
    if (code == "reauth_required") required() else failed(WatchProtocol.isRetryableError(error))
    return true
}

internal fun Throwable.isWatchAuthenticationFailure(): Boolean {
    if (this is WatchAuthenticationException || this is AccountRequiredForWatchException) return true
    if (this is ResponseException && response.status.value == 401) return true
    val detail = message.orEmpty()
    return "401" in detail || detail.contains("unauthorized", ignoreCase = true)
}

internal fun backoffDelayMs(attempt: Int): Long {
    val exponent = (attempt - 1).coerceIn(0, 5)
    val capped = (BASE_BACKOFF_MS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS)
    val jitter = (capped * BACKOFF_JITTER_RATIO * Random.nextDouble()).toLong()
    return capped + jitter
}

internal fun String.toWebSocketUrl(): String? {
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

internal const val PING_INTERVAL_MS = 8_000L

/** Pings without any answer before a socket counts as dead. */
internal const val PONG_MISSED_INTERVALS = 3

/** A socket's access is renewed this long before it lapses. */
internal const val WATCH_REAUTH_LEAD_MS = 60_000L
internal const val WATCH_REAUTH_RETRY_MS = 5_000L

/** What the app tells the watch relay it understands, in its `hello`. */
internal val WATCH_CLIENT_CAPABILITIES =
    listOf(WatchProtocol.CAPABILITY_ROOM_REVISION, WatchProtocol.CAPABILITY_REAUTHENTICATE)
internal const val MAX_CHAT_HISTORY = 50
internal const val WATCH_OUTGOING_QUEUE_CAPACITY = 64
internal const val MAX_LIVE_REACTIONS = 12
internal const val CHAT_ACK_TIMEOUT_MS = 8_000L
internal const val MAX_RECONNECT_ATTEMPTS = 10
internal const val MAX_RECONNECT_WINDOW_MS = 5 * 60 * 1000L
internal const val LATENCY_REPORT_BUCKET_MS = 10L
internal const val DRIFT_REPORT_BUCKET_MS = 50L

/** Drift-only status reports are spaced out this much; the server coalesces them further. */
internal const val DRIFT_REPORT_INTERVAL_MS = 5_000L

/** Drift at or beyond this is a visible seek on the guest and is reported without waiting. */
internal const val DRIFT_URGENT_MS = 2_000L
internal val WATCH_AUTH_CLOSE_REASONS = setOf("account_auth_required", "account_auth_expired")

private const val BASE_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 20_000L
private const val BACKOFF_JITTER_RATIO = 0.2
