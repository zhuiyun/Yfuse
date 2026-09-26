package com.yfuse.core.handoff

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class ActiveHandoffPlayback(
    val media: HandoffMedia,
    val ready: Boolean,
    val playing: Boolean,
    /** The player gave up on this item; waiting longer cannot help. */
    val failed: Boolean = false,
    /** Changes whenever startup moves - a new phase, more media buffered. Its value means nothing. */
    val progress: Long = 0L,
    val networkBitsPerSecond: Long = 0L,
    val sourceBitsPerSecond: Long = 0L,
)

/** How a receiving player's startup ended. [last] is the final state it published, if any. */
sealed interface HandoffStartResult {
    val last: ActiveHandoffPlayback?

    data class Playing(
        override val last: ActiveHandoffPlayback,
    ) : HandoffStartResult

    data class Failed(
        override val last: ActiveHandoffPlayback,
    ) : HandoffStartResult

    data class Stalled(
        override val last: ActiveHandoffPlayback?,
    ) : HandoffStartResult
}

/**
 * How long a receiving player may go without any startup progress. It is not a limit on startup
 * itself: a slow link that keeps delivering keeps the wait alive until the request expires.
 */
const val HANDOFF_STARTUP_STALL_MS = 20_000L

/** Receiver belongs to the Activity/navigation owner; source belongs to the mounted player. */
class HandoffPlaybackRegistry : HandoffPlaybackBridge {
    interface Source {
        fun snapshot(): HandoffMedia?

        suspend fun pauseAndSnapshot(): HandoffMedia?

        suspend fun resume()
    }

    interface Receiver {
        suspend fun prepare(media: HandoffMedia): Boolean

        suspend fun start(media: HandoffMedia): Boolean

        suspend fun release()

        fun transferCompleted() {}

        /** Why the last [prepare] or [start] did not succeed, in words for this device's viewer. */
        fun failureReason(): String? = null
    }

    var source: Source? = null
    var receiver: Receiver? = null
    private var pausedSource: Source? = null
    private var preparedReceiver: Receiver? = null
    private val _activePlayback = MutableStateFlow<ActiveHandoffPlayback?>(null)
    val activePlayback: StateFlow<ActiveHandoffPlayback?> = _activePlayback.asStateFlow()
    private val _pendingPreferences = MutableStateFlow<HandoffMedia?>(null)
    val pendingPreferences: StateFlow<HandoffMedia?> = _pendingPreferences.asStateFlow()

    fun offerPreferences(media: HandoffMedia) {
        _pendingPreferences.value = media
    }

    fun clearPreferences(expectedMedia: HandoffMedia) {
        _pendingPreferences.compareAndSet(expectedMedia, null)
    }

    fun publish(
        source: Source,
        value: ActiveHandoffPlayback?,
    ) {
        if (this.source === source) _activePlayback.value = value
    }

    /**
     * Waits for [expected] to actually play, for as long as its startup keeps moving.
     *
     * This used to be a fixed 15 s. A 13 Mbps title over a 2 Mbps link was still loading - bytes
     * arriving, nothing wrong - when that ran out, twice in one evening, and each time the
     * receiver closed a working player while the source sat paused. Only a player failure or
     * [stallMs] without any progress ends the wait now; the caller's request lifetime bounds it.
     */
    suspend fun awaitPlayback(
        expected: HandoffMedia,
        stallMs: Long = HANDOFF_STARTUP_STALL_MS,
    ): HandoffStartResult {
        var seen: ActiveHandoffPlayback? = null
        while (true) {
            val previous = seen
            val next =
                withTimeoutOrNull(stallMs) {
                    activePlayback.first { active ->
                        active != null && active.plays(expected) && active.movedFrom(previous)
                    }
                } ?: return HandoffStartResult.Stalled(previous)
            when {
                next.failed -> return HandoffStartResult.Failed(next)
                next.ready && next.playing -> return HandoffStartResult.Playing(next)
            }
            seen = next
        }
    }

    private fun ActiveHandoffPlayback.plays(expected: HandoffMedia): Boolean =
        media.serverId == expected.serverId &&
            media.itemId == expected.itemId &&
            media.mediaKey == expected.mediaKey &&
            (expected.mediaSourceId == null || expected.mediaSourceId == media.mediaSourceId)

    private fun ActiveHandoffPlayback.movedFrom(previous: ActiveHandoffPlayback?): Boolean =
        previous == null ||
            progress != previous.progress ||
            ready != previous.ready ||
            playing != previous.playing ||
            failed != previous.failed

    override fun finishTransfer() {
        try {
            preparedReceiver?.transferCompleted()
        } finally {
            pausedSource = null
            preparedReceiver = null
        }
    }

    override fun snapshot() = source?.snapshot()

    override fun nowPlaying(): HandoffMedia? =
        activePlayback.value?.takeIf { it.ready && it.playing && !it.failed }?.let { snapshot() }

    override suspend fun pauseAndSnapshot(): HandoffMedia? {
        val current = source ?: return null
        pausedSource = current
        return current.pauseAndSnapshot()
    }

    override suspend fun resumeSource() {
        val paused = pausedSource
        pausedSource = null
        if (paused != null && source === paused) paused.resume()
    }

    override suspend fun prepare(media: HandoffMedia): Boolean {
        val current = receiver ?: return false
        preparedReceiver = current
        return current.prepare(media)
    }

    override suspend fun startPrepared(media: HandoffMedia): Boolean = preparedReceiver?.start(media) ?: false

    override fun receiveFailureReason(): String? = preparedReceiver?.failureReason()

    override suspend fun releasePrepared() {
        val prepared = preparedReceiver
        preparedReceiver = null
        prepared?.release()
    }
}
