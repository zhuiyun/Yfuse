package com.yfuse.feature.player

import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.PlaybackEventOutbox
import com.yfuse.core.data.PlaybackOutboxEventKind
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer

internal interface PlaybackEventSink {
    suspend fun started(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    )

    suspend fun progress(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    )

    suspend fun stopped(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    )

    suspend fun startedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = started(itemId, sessionId, positionTicks, isPaused)

    suspend fun progressWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = progress(itemId, sessionId, positionTicks, isPaused)

    suspend fun stoppedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = stopped(itemId, sessionId, positionTicks, isPaused)

    /** Ends only the server's encoder job; the logical playback session remains active. */
    suspend fun stopEncoding(sessionId: String): Boolean = true
}

internal class EmbyPlaybackEventSink(
    private val repo: EmbyRepository,
    private val server: SavedServer,
) : PlaybackEventSink {
    override suspend fun started(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        repo.reportPlaybackStarted(server, itemId, sessionId, positionTicks, isPaused).getOrThrow()
    }

    override suspend fun progress(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        repo.reportPlaybackProgress(server, itemId, sessionId, positionTicks, isPaused).getOrThrow()
    }

    override suspend fun stopped(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        repo.reportPlaybackStopped(server, itemId, sessionId, positionTicks, isPaused).getOrThrow()
        stopEncoding(sessionId)
    }

    override suspend fun startedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        repo
            .reportPlaybackStarted(
                server,
                itemId,
                sessionId,
                positionTicks,
                isPaused,
                playMethod,
            ).getOrThrow()
    }

    override suspend fun progressWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        repo
            .reportPlaybackProgress(
                server,
                itemId,
                sessionId,
                positionTicks,
                isPaused,
                playMethod,
            ).getOrThrow()
    }

    override suspend fun stoppedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        repo
            .reportPlaybackStopped(
                server,
                itemId,
                sessionId,
                positionTicks,
                isPaused,
                playMethod,
            ).getOrThrow()
        stopEncoding(sessionId)
    }

    override suspend fun stopEncoding(sessionId: String): Boolean = repo.stopTranscoding(server, sessionId).isSuccess
}

/**
 * Persists before returning to the reporter actor. Network delivery happens on the coordinator's
 * application scope, so a failed request cannot terminate the actor or discard a later stop.
 */
internal class ReliablePlaybackEventSink(
    private val serverId: String,
    private val outbox: PlaybackEventOutbox,
    private val directSink: PlaybackEventSink,
    private val wakeDelivery: (String) -> Unit,
) : PlaybackEventSink {
    override suspend fun started(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) = submitDefault(
        PlaybackOutboxEventKind.Started,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
    )

    override suspend fun progress(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) = submitDefault(
        PlaybackOutboxEventKind.Progress,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
    )

    override suspend fun stopped(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) = submitDefault(
        PlaybackOutboxEventKind.Stopped,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
    )

    override suspend fun startedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = submit(
        PlaybackOutboxEventKind.Started,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
        playMethod,
    )

    override suspend fun progressWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = submit(
        PlaybackOutboxEventKind.Progress,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
        playMethod,
    )

    override suspend fun stoppedWithMethod(
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) = submit(
        PlaybackOutboxEventKind.Stopped,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
        playMethod,
    )

    override suspend fun stopEncoding(sessionId: String): Boolean = directSink.stopEncoding(sessionId)

    private fun submitDefault(
        kind: PlaybackOutboxEventKind,
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
    ) = submit(
        kind,
        itemId,
        sessionId,
        positionTicks,
        isPaused,
        DEFAULT_PLAY_METHOD,
    )

    private fun submit(
        kind: PlaybackOutboxEventKind,
        itemId: String,
        sessionId: String,
        positionTicks: Long,
        isPaused: Boolean,
        playMethod: String,
    ) {
        val accepted =
            outbox.enqueue(
                kind = kind,
                serverId = serverId,
                itemId = itemId,
                sessionId = sessionId,
                positionTicks = positionTicks,
                isPaused = isPaused,
                playMethod = playMethod,
            )
        if (accepted == null) {
            AppLog.error(
                category = "playback.outbox",
                event = "event_rejected",
                message = "Playback report could not be queued",
                attributes =
                    mapOf(
                        "serverId" to serverId,
                        "kind" to kind.name,
                    ),
            )
            return
        }
        wakeDelivery(serverId)
    }
}

private const val DEFAULT_PLAY_METHOD = "DirectPlay"
