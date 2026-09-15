package com.yfuse.core.handoff

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

data class ActiveHandoffPlayback(
    val media: HandoffMedia,
    val ready: Boolean,
    val playing: Boolean,
)

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

    suspend fun awaitPlaying(
        expected: HandoffMedia,
        timeoutMs: Long = 15_000,
    ): ActiveHandoffPlayback =
        withTimeout(timeoutMs) {
            activePlayback.first { active ->
                active != null &&
                    active.ready &&
                    active.playing &&
                    active.media.serverId == expected.serverId &&
                    active.media.itemId == expected.itemId &&
                    active.media.mediaKey == expected.mediaKey &&
                    (expected.mediaSourceId == null || expected.mediaSourceId == active.media.mediaSourceId)
            }!!
        }

    override fun finishTransfer() {
        try {
            preparedReceiver?.transferCompleted()
        } finally {
            pausedSource = null
            preparedReceiver = null
        }
    }

    override fun snapshot() = source?.snapshot()

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

    override suspend fun releasePrepared() {
        val prepared = preparedReceiver
        preparedReceiver = null
        prepared?.release()
    }
}
