package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val PLAYBACK_OUTBOX_KEY = "playback.event.outbox.v1"
private const val DEFAULT_MAX_PLAYBACK_OUTBOX_EVENTS = 256
private const val MAX_FLUSH_BATCH = 64
private const val INITIAL_RETRY_DELAY_MS = 5_000L
private const val MAX_RETRY_DELAY_MS = 15L * 60L * 1_000L

@Serializable
enum class PlaybackOutboxEventKind {
    @SerialName("started")
    Started,

    @SerialName("progress")
    Progress,

    @SerialName("stopped")
    Stopped,
}

/**
 * A replayable Emby playback report. It deliberately contains neither a server URL nor a token;
 * delivery resolves [serverId] through the current encrypted [ServerRegistry] entry.
 */
@Serializable
data class PlaybackOutboxEvent(
    val order: Long,
    val kind: PlaybackOutboxEventKind,
    val serverId: String,
    val itemId: String,
    val sessionId: String,
    val positionTicks: Long,
    val isPaused: Boolean,
    val playMethod: String,
    val createdAtEpochMs: Long,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long = 0L,
    val authenticationRequired: Boolean = false,
)

@Serializable
private data class PersistedPlaybackOutbox(
    val nextOrder: Long = 1L,
    val events: List<PlaybackOutboxEvent> = emptyList(),
)

data class PlaybackOutboxFlushResult(
    val deliveredCount: Int,
    val pendingCount: Int,
    val authenticationRequired: Boolean,
    val nextAttemptAtEpochMs: Long?,
)

enum class PlaybackDeliveryFailure {
    Authentication,
    Retryable,
}

/** 401 and 403 need user action; treating either as a transient outage creates a busy loop. */
internal fun Throwable.playbackDeliveryFailure(): PlaybackDeliveryFailure {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is EmbyErrorException -> when (current.error) {
                EmbyError.Unauthorized, is EmbyError.AccessDenied ->
                    return PlaybackDeliveryFailure.Authentication
                else -> Unit
            }

            is ResponseException -> if (current.response.status.value == 401 ||
                current.response.status.value == 403
            ) {
                return PlaybackDeliveryFailure.Authentication
            }
        }
        current = current.cause
    }
    return PlaybackDeliveryFailure.Retryable
}

internal fun playbackOutboxBackoffMs(attemptCount: Int): Long {
    val exponent = (attemptCount - 1).coerceIn(0, 20)
    var delayMs = INITIAL_RETRY_DELAY_MS
    repeat(exponent) {
        delayMs = (delayMs * 2L).coerceAtMost(MAX_RETRY_DELAY_MS)
    }
    return delayMs
}

/**
 * Settings-backed, process-death-safe playback-event queue.
 *
 * Events are globally ordered but flushed per server. Progress for one playback session is a
 * snapshot and is therefore merged in place. Stopped is terminal: it removes redundant progress,
 * is never overwritten by a late started/progress event, and is the last event considered by the
 * absolute storage safety valve.
 */
class PlaybackEventOutbox(
    private val settings: Settings,
    private val maxEvents: Int = DEFAULT_MAX_PLAYBACK_OUTBOX_EVENTS,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stateLock = Any()
    /** One slow server must never hold another server's delivery lane. */
    private val serverFlushMutexes = mutableMapOf<String, Mutex>()
    private var persisted = load()
    private val _events = MutableStateFlow(persisted.events.sortedBy(PlaybackOutboxEvent::order))
    val events: StateFlow<List<PlaybackOutboxEvent>> = _events.asStateFlow()

    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
    }

    fun enqueue(
        kind: PlaybackOutboxEventKind,
        serverId: String,
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ): PlaybackOutboxEvent? {
        if (serverId.isBlank() || itemId.isBlank() || sessionId.isBlank()) return null
        return synchronized(stateLock) {
            val current = persisted.events.toMutableList()
            val sameSession: (PlaybackOutboxEvent) -> Boolean = {
                it.serverId == serverId && it.itemId == itemId && it.sessionId == sessionId
            }
            val stopped = current.firstOrNull {
                sameSession(it) && it.kind == PlaybackOutboxEventKind.Stopped
            }

            // A delayed callback from the old reporter must not resurrect a session which is
            // already durably terminal.
            if (stopped != null && kind != PlaybackOutboxEventKind.Stopped) {
                return@synchronized stopped
            }

            val existing = current.firstOrNull { sameSession(it) && it.kind == kind }
            val event = if (existing != null) {
                if (kind == PlaybackOutboxEventKind.Started) {
                    // The first start position is the lifecycle boundary; a duplicate is stale.
                    existing
                } else {
                    existing.copy(
                        positionTicks = positionTicks.coerceAtLeast(0L),
                        isPaused = isPaused,
                        playMethod = playMethod,
                        createdAtEpochMs = nowEpochMs(),
                    )
                }
            } else {
                PlaybackOutboxEvent(
                    order = persisted.nextOrder,
                    kind = kind,
                    serverId = serverId,
                    itemId = itemId,
                    sessionId = sessionId,
                    positionTicks = positionTicks.coerceAtLeast(0L),
                    isPaused = isPaused,
                    playMethod = playMethod,
                    createdAtEpochMs = nowEpochMs(),
                )
            }

            if (existing != null) current[current.indexOf(existing)] = event else current += event
            if (kind == PlaybackOutboxEventKind.Stopped) {
                current.removeAll { sameSession(it) && it.kind == PlaybackOutboxEventKind.Progress }
            }
            val bounded = bound(current)
            persisted = PersistedPlaybackOutbox(
                nextOrder = if (existing == null) persisted.nextOrder + 1L else persisted.nextOrder,
                events = bounded,
            )
            persistLocked()
            event.takeIf { candidate -> bounded.any { it.order == candidate.order } }
        }
    }

    /** A successful re-login or explicit new play is allowed to retry that server immediately. */
    fun resumeAfterAuthentication(serverId: String) {
        synchronized(stateLock) {
            val resumed = persisted.events.map { event ->
                if (event.serverId == serverId && event.authenticationRequired) {
                    event.copy(
                        authenticationRequired = false,
                        attemptCount = 0,
                        nextAttemptAtEpochMs = 0L,
                    )
                } else {
                    event
                }
            }
            if (resumed == persisted.events) return
            persisted = persisted.copy(events = resumed)
            persistLocked()
        }
    }

    /** Server identities with pending work; callers still resolve each id independently. */
    fun pendingServerIds(): Set<String> = synchronized(stateLock) {
        persisted.events.mapTo(linkedSetOf(), PlaybackOutboxEvent::serverId)
    }

    suspend fun flush(
        serverId: String,
        deliver: suspend (PlaybackOutboxEvent) -> Result<Unit>,
    ): PlaybackOutboxFlushResult {
        val serverMutex = synchronized(stateLock) {
            serverFlushMutexes.getOrPut(serverId) { Mutex() }
        }
        return serverMutex.withLock {
            var deliveredCount = 0
            repeat(MAX_FLUSH_BATCH) {
                val head = synchronized(stateLock) {
                    persisted.events.filter { it.serverId == serverId }.minByOrNull { it.order }
                } ?: return@withLock resultFor(serverId, deliveredCount)

                val now = nowEpochMs()
                if (head.authenticationRequired || head.nextAttemptAtEpochMs > now) {
                    return@withLock resultFor(serverId, deliveredCount)
                }

                val outcome = try {
                    deliver(head)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                outcome.onSuccess {
                    synchronized(stateLock) {
                        // A progress/stop snapshot may have advanced while this request was in
                        // flight. Acknowledge only the exact payload that reached the server.
                        val current = persisted.events.firstOrNull { it.order == head.order }
                        if (current == head) {
                            persisted = persisted.copy(
                                events = persisted.events.filterNot { it.order == head.order },
                            )
                            persistLocked()
                        }
                    }
                    deliveredCount++
                }.onFailure { error ->
                    synchronized(stateLock) {
                        val current = persisted.events.firstOrNull { it.order == head.order }
                            ?: return@synchronized
                        val failure = error.playbackDeliveryFailure()
                        val attempts = if (current.attemptCount == Int.MAX_VALUE) {
                            Int.MAX_VALUE
                        } else {
                            current.attemptCount + 1
                        }
                        val updated = current.copy(
                            attemptCount = attempts,
                            authenticationRequired =
                                failure == PlaybackDeliveryFailure.Authentication,
                            nextAttemptAtEpochMs =
                                if (failure == PlaybackDeliveryFailure.Authentication) {
                                    Long.MAX_VALUE
                                } else {
                                    safeAdd(now, playbackOutboxBackoffMs(attempts))
                                },
                        )
                        persisted = persisted.copy(
                            events = persisted.events.map {
                                if (it.order == head.order) updated else it
                            },
                        )
                        persistLocked()
                    }
                    return@withLock resultFor(serverId, deliveredCount)
                }
            }
            resultFor(serverId, deliveredCount)
        }
    }

    private fun resultFor(serverId: String, deliveredCount: Int): PlaybackOutboxFlushResult =
        synchronized(stateLock) {
            val serverEvents = persisted.events.filter { it.serverId == serverId }
            val head = serverEvents.minByOrNull(PlaybackOutboxEvent::order)
            PlaybackOutboxFlushResult(
                deliveredCount = deliveredCount,
                pendingCount = serverEvents.size,
                authenticationRequired = head?.authenticationRequired == true,
                nextAttemptAtEpochMs = head
                    ?.takeUnless { it.authenticationRequired }
                    ?.nextAttemptAtEpochMs,
            )
        }

    private fun bound(source: List<PlaybackOutboxEvent>): List<PlaybackOutboxEvent> {
        val result = source.sortedBy(PlaybackOutboxEvent::order).toMutableList()
        var terminalEvicted = false
        while (result.size > maxEvents) {
            val removableIndex = result.indexOfFirst {
                it.kind == PlaybackOutboxEventKind.Progress
            }.takeIf { it >= 0 } ?: result.indexOfFirst { candidate ->
                candidate.kind == PlaybackOutboxEventKind.Started && result.any {
                    it.serverId == candidate.serverId &&
                        it.itemId == candidate.itemId &&
                        it.sessionId == candidate.sessionId &&
                        it.kind == PlaybackOutboxEventKind.Stopped
                }
            }.takeIf { it >= 0 } ?: result.indexOfFirst {
                it.kind == PlaybackOutboxEventKind.Started
            }.takeIf { it >= 0 } ?: 0.also { terminalEvicted = true }
            result.removeAt(removableIndex)
        }
        if (terminalEvicted) {
            AppLog.error(
                category = "playback.outbox",
                event = "terminal_capacity_exhausted",
                message = "Playback outbox reached its absolute terminal-event safety limit",
                attributes = mapOf("maxEvents" to maxEvents.toString()),
            )
        }
        return result
    }

    private fun persistLocked() {
        _events.value = persisted.events.sortedBy(PlaybackOutboxEvent::order)
        runCatching {
            settings.putString(PLAYBACK_OUTBOX_KEY, json.encodeToString(persisted))
        }.onFailure {
            AppLog.error(
                category = "playback.outbox",
                event = "persist_failed",
                message = "Failed to persist playback reporting outbox",
                throwable = it,
                attributes = mapOf("pendingCount" to persisted.events.size.toString()),
            )
        }
    }

    private fun load(): PersistedPlaybackOutbox {
        val raw = settings.getStringOrNull(PLAYBACK_OUTBOX_KEY) ?: return PersistedPlaybackOutbox()
        return runCatching {
            json.decodeFromString(PersistedPlaybackOutbox.serializer(), raw)
        }.onFailure {
            AppLog.warning(
                category = "playback.outbox",
                event = "stored_outbox_invalid",
                message = "Stored playback reporting outbox could not be decoded",
                throwable = it,
            )
        }.getOrDefault(PersistedPlaybackOutbox())
    }
}

private fun safeAdd(value: Long, increment: Long): Long =
    if (increment > Long.MAX_VALUE - value) Long.MAX_VALUE else value + increment
