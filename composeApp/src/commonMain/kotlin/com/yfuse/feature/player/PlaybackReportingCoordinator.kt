package com.yfuse.feature.player

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackEventOutbox
import com.yfuse.core.data.PlaybackOutboxEvent
import com.yfuse.core.data.PlaybackOutboxEventKind
import com.yfuse.core.data.PlaybackOutboxFlushResult
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** One bounded pass over every server lane, suitable for a network-constrained background job. */
internal data class PlaybackOutboxFlushSummary(
    val deliveredCount: Int,
    val pendingCount: Int,
    val authenticationRequired: Boolean,
    val unavailableServer: Boolean,
    val hasRetryablePending: Boolean,
)

/** Common retry scheduler; an Android network-constrained worker can wake the same queue later. */
class PlaybackReportingCoordinator(
    private val repository: EmbyRepository,
    private val registry: ServerRegistry,
    private val outbox: PlaybackEventOutbox,
    /**
     * Optional so the retry-scheduling tests can build a coordinator without one. Absent, the
     * 服务器 grid simply has no 「上次观看」 to show.
     */
    private val activity: ServerActivityStore? = null,
    private val progressSyncEnabled: StateFlow<Boolean>? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val jobsLock = Any()
    private val jobs = mutableMapOf<String, Job>()

    init {
        // Re-enabling is explicit consent to resume the durable reports retained while disabled.
        // The first value is handled by normal application startup, avoiding duplicate wake-ups.
        progressSyncEnabled?.let { enabled ->
            scope.launch {
                enabled.drop(1).filter { it }.collect { flushPending() }
            }
        }
    }

    /** Called once at application startup; server queues stay isolated during replay. */
    fun flushPending() {
        if (!remoteProgressSyncEnabled()) return
        if (outbox.pendingServerIds().isNotEmpty()) schedulePlaybackOutboxFlush()
        outbox.pendingServerIds().forEach(::wake)
    }

    /**
     * Runs one bounded pass without sleeping for the queue's next retry timestamp.
     *
     * WorkManager owns the process-level retry clock while the app is backgrounded. The outbox
     * still owns event ordering, acknowledgement and per-server exclusion, so this can safely
     * overlap a foreground [runLoop] without delivering the same payload twice.
     */
    internal suspend fun flushPendingOnce(): PlaybackOutboxFlushSummary =
        if (!remoteProgressSyncEnabled()) {
            val pending = outbox.events.value
            PlaybackOutboxFlushSummary(
                deliveredCount = 0,
                pendingCount = pending.size,
                authenticationRequired = pending.any { it.authenticationRequired },
                unavailableServer = pending.any { registry.serverById(it.serverId) == null },
                hasRetryablePending = false,
            )
        } else {
            coroutineScope {
                val attempts =
                    outbox
                        .pendingServerIds()
                        .map { serverId ->
                            async {
                                val result = flushServerOnce(serverId)
                                ServerFlushAttempt(
                                    available = result != null,
                                    result = result,
                                )
                            }
                        }.awaitAll()
                // Re-read durable heads after the pass so the worker can report whether another
                // WorkManager attempt is needed without waiting inside this process.
                val remainingEvents = outbox.events.value
                val remainingHeads =
                    remainingEvents
                        .groupBy(PlaybackOutboxEvent::serverId)
                        .mapValues { (_, events) -> events.minBy(PlaybackOutboxEvent::order) }
                PlaybackOutboxFlushSummary(
                    deliveredCount = attempts.sumOf { it.result?.deliveredCount ?: 0 },
                    pendingCount = remainingEvents.size,
                    authenticationRequired = remainingHeads.values.any { it.authenticationRequired },
                    unavailableServer = remainingHeads.keys.any { registry.serverById(it) == null },
                    hasRetryablePending =
                        remainingHeads.any { (serverId, head) ->
                            registry.serverById(serverId) != null && !head.authenticationRequired
                        },
                )
            }
        }

    /** Successful server authentication is the user-controlled exit from an auth-blocked lane. */
    fun resumeAfterAuthentication(serverId: String) {
        val currentServerId = registry.serverById(serverId)?.id ?: serverId
        matchingPendingIds(currentServerId).forEach { pendingId ->
            outbox.resumeAfterAuthentication(pendingId)
            wake(pendingId)
        }
    }

    /**
     * A new explicit playback proves this saved server is usable enough to try again. It clears
     * only that server's authentication block and retains the reporting identity selected when
     * the player was launched, even if the user changes the default server meanwhile.
     */
    internal fun sinkFor(serverId: String): PlaybackEventSink? {
        val server = registry.serverById(serverId) ?: return null
        // The one point in the app that knows playback is starting *and* which saved server
        // it belongs to. Recorded against the canonical id so a server that has since been
        // re-addressed keeps its history rather than starting again as a stranger.
        activity?.recordWatch(server.id, nowEpochMs())
        resumeAfterAuthentication(server.id)
        val direct = EmbyPlaybackEventSink(repository, server)
        return ReliablePlaybackEventSink(
            serverId = serverId,
            outbox = outbox,
            directSink = direct,
            wakeDelivery = ::wake,
            progressSyncEnabled = ::remoteProgressSyncEnabled,
        ).also { wake(serverId) }
    }

    private fun matchingPendingIds(currentServerId: String): Set<String> =
        outbox.pendingServerIds().filterTo(linkedSetOf()) { pendingId ->
            registry.serverById(pendingId)?.id == currentServerId
        }

    private fun wake(serverId: String) {
        if (!remoteProgressSyncEnabled()) return
        // WorkManager KEEP semantics coalesce frequent progress wakes into one unique job. While
        // this process is alive, the foreground lane below remains the immediate delivery path.
        schedulePlaybackOutboxFlush()
        synchronized(jobsLock) {
            if (jobs[serverId]?.isActive == true) return
            lateinit var job: Job
            job = scope.launch { runLoop(serverId) }
            jobs[serverId] = job
            job.invokeOnCompletion {
                val relaunch =
                    synchronized(jobsLock) {
                        if (jobs[serverId] === job) jobs.remove(serverId)
                        registry.serverById(serverId) != null &&
                            outbox.events.value.any {
                                it.serverId == serverId &&
                                    !it.authenticationRequired &&
                                    it.nextAttemptAtEpochMs <= nowEpochMs()
                            }
                    }
                // Covers an enqueue racing with the final empty-queue observation.
                if (relaunch) wake(serverId)
            }
        }
    }

    private suspend fun runLoop(serverId: String) {
        while (remoteProgressSyncEnabled()) {
            val result = flushServerOnce(serverId) ?: return
            if (result.pendingCount == 0 || result.authenticationRequired) return
            val nextAttempt = result.nextAttemptAtEpochMs ?: return
            delay((nextAttempt - nowEpochMs()).coerceAtLeast(1L))
        }
    }

    private suspend fun flushServerOnce(serverId: String) =
        if (!remoteProgressSyncEnabled()) {
            null
        } else {
            registry.serverById(serverId)?.let { server ->
                val sink = EmbyPlaybackEventSink(repository, server)
                outbox.flush(serverId) { event -> deliver(sink, event) }
            } ?: run {
                AppLog.warning(
                    category = "playback.outbox",
                    event = "server_missing",
                    message = "Playback reports are waiting for a removed server",
                    attributes = mapOf("serverId" to serverId),
                )
                null
            }
        }

    private fun remoteProgressSyncEnabled(): Boolean = progressSyncEnabled?.value != false

    private data class ServerFlushAttempt(
        val available: Boolean,
        val result: PlaybackOutboxFlushResult?,
    )

    private suspend fun deliver(
        sink: PlaybackEventSink,
        event: PlaybackOutboxEvent,
    ): Result<Unit> =
        runCatching {
            when (event.kind) {
                PlaybackOutboxEventKind.Started ->
                    sink.startedWithMethod(
                        event.itemId,
                        event.sessionId,
                        event.positionTicks,
                        event.isPaused,
                        event.playMethod,
                    )

                PlaybackOutboxEventKind.Progress ->
                    sink.progressWithMethod(
                        event.itemId,
                        event.sessionId,
                        event.positionTicks,
                        event.isPaused,
                        event.playMethod,
                    )

                PlaybackOutboxEventKind.Stopped ->
                    sink.stoppedWithMethod(
                        event.itemId,
                        event.sessionId,
                        event.positionTicks,
                        event.isPaused,
                        event.playMethod,
                    )
            }
        }
}
