package com.yfuse.feature.player

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackEventOutbox
import com.yfuse.core.data.PlaybackOutboxEvent
import com.yfuse.core.data.PlaybackOutboxEventKind
import com.yfuse.core.data.ServerActivityStore
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val jobsLock = Any()
    private val jobs = mutableMapOf<String, Job>()

    /** Called once at application startup; server queues stay isolated during replay. */
    fun flushPending() {
        outbox.pendingServerIds().forEach(::wake)
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
        matchingPendingIds(server.id).forEach { pendingId ->
            outbox.resumeAfterAuthentication(pendingId)
            wake(pendingId)
        }
        val direct = EmbyPlaybackEventSink(repository, server)
        return ReliablePlaybackEventSink(
            serverId = serverId,
            outbox = outbox,
            directSink = direct,
            wakeDelivery = ::wake,
        ).also { wake(serverId) }
    }

    private fun matchingPendingIds(currentServerId: String): Set<String> =
        outbox.pendingServerIds().filterTo(linkedSetOf()) { pendingId ->
            registry.serverById(pendingId)?.id == currentServerId
        }

    private fun wake(serverId: String) {
        synchronized(jobsLock) {
            if (jobs[serverId]?.isActive == true) return
            lateinit var job: Job
            job = scope.launch { runLoop(serverId) }
            jobs[serverId] = job
            job.invokeOnCompletion {
                val relaunch = synchronized(jobsLock) {
                    if (jobs[serverId] === job) jobs.remove(serverId)
                    registry.serverById(serverId) != null && outbox.events.value.any {
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
        while (true) {
            val server = registry.serverById(serverId)
            if (server == null) {
                AppLog.warning(
                    category = "playback.outbox",
                    event = "server_missing",
                    message = "Playback reports are waiting for a removed server",
                    attributes = mapOf("serverId" to serverId),
                )
                return
            }
            val sink = EmbyPlaybackEventSink(repository, server)
            val result = outbox.flush(serverId) { event -> deliver(sink, event) }
            if (result.pendingCount == 0 || result.authenticationRequired) return
            val nextAttempt = result.nextAttemptAtEpochMs ?: return
            delay((nextAttempt - nowEpochMs()).coerceAtLeast(1L))
        }
    }

    private suspend fun deliver(
        sink: PlaybackEventSink,
        event: PlaybackOutboxEvent,
    ): Result<Unit> = runCatching {
        when (event.kind) {
            PlaybackOutboxEventKind.Started -> sink.startedWithMethod(
                event.itemId,
                event.sessionId,
                event.positionTicks,
                event.isPaused,
                event.playMethod,
            )

            PlaybackOutboxEventKind.Progress -> sink.progressWithMethod(
                event.itemId,
                event.sessionId,
                event.positionTicks,
                event.isPaused,
                event.playMethod,
            )

            PlaybackOutboxEventKind.Stopped -> sink.stoppedWithMethod(
                event.itemId,
                event.sessionId,
                event.positionTicks,
                event.isPaused,
                event.playMethod,
            )
        }
    }
}
