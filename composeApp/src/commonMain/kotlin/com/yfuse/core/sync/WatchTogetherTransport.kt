package com.yfuse.core.sync

import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import kotlin.time.TimeSource

internal class RoomUnavailableException(
    message: String,
) : Exception(message)

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

internal data class LocalPlaybackStatus(
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
internal const val MAX_CHAT_HISTORY = 50
internal const val WATCH_OUTGOING_QUEUE_CAPACITY = 64
internal const val MAX_LIVE_REACTIONS = 12
internal const val CHAT_ACK_TIMEOUT_MS = 8_000L
internal const val MAX_RECONNECT_ATTEMPTS = 10
internal const val MAX_RECONNECT_WINDOW_MS = 5 * 60 * 1000L
internal const val LATENCY_REPORT_BUCKET_MS = 10L
internal const val DRIFT_REPORT_BUCKET_MS = 50L
internal val WATCH_AUTH_CLOSE_REASONS = setOf("account_auth_required", "account_auth_expired")

private const val BASE_BACKOFF_MS = 1_000L
private const val MAX_BACKOFF_MS = 20_000L
private const val BACKOFF_JITTER_RATIO = 0.2
