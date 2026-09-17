package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind

/**
 * The part of a [PlaybackState] that the system surfaces read: notification text and actions,
 * media-session transport state and metadata, picture-in-picture aspect ratio and auto-enter.
 *
 * Position and buffer depth are deliberately absent. They move on every 500 ms tick, and a
 * consumer that rebuilds a notification or resets media-session metadata each time they move
 * turns steady playback into a stream of Binder calls. Anything that needs the position reads it
 * from the progress path instead.
 */
internal data class PlaybackPresentationKey(
    val playing: Boolean,
    val buffering: Boolean,
    val ended: Boolean,
    val error: String?,
    val errorKind: PlaybackFailureKind?,
    val currentIndex: Int,
    val itemCount: Int,
    val durationMs: Long,
    val speed: Float,
    val videoWidth: Int,
    val videoHeight: Int,
    val transcoding: Boolean,
)

internal fun PlaybackState.presentationKey(): PlaybackPresentationKey =
    PlaybackPresentationKey(
        playing = playing,
        buffering = buffering,
        ended = ended,
        error = error,
        errorKind = errorKind,
        currentIndex = currentIndex,
        itemCount = itemCount,
        durationMs = durationMs,
        speed = speed,
        videoWidth = diagnostics.videoWidth,
        videoHeight = videoHeight,
        transcoding = transcoding,
    )

/**
 * Splits one timeline into presentation changes and progress ticks.
 *
 * [onPresentationChange] fires for the first state and whenever [presentationKey] differs from the
 * previous one; [onProgress] fires for every state, presentation changes included, and always
 * after the presentation callback for the same state.
 */
internal class PlaybackStateFanout(
    private val onPresentationChange: (PlaybackState, PlayerMediaItem?) -> Unit,
    private val onProgress: (PlaybackState, PlayerMediaItem?) -> Unit,
) {
    private var lastKey: PlaybackPresentationKey? = null

    fun dispatch(
        state: PlaybackState,
        item: PlayerMediaItem?,
    ) {
        val key = state.presentationKey()
        if (key != lastKey) {
            lastKey = key
            onPresentationChange(state, item)
        }
        onProgress(state, item)
    }
}

/**
 * Decides when the media session's position must be re-published outside a presentation change.
 *
 * The system extrapolates position from the last published sample and the playback speed, so a
 * steady stream needs a refresh only every [intervalMs]. A seek breaks that extrapolation; it
 * shows up as the position landing outside the window the last sample predicts, and is
 * re-published at once.
 */
internal class MediaSessionPositionSync(
    private val intervalMs: Long = MEDIA_SESSION_POSITION_INTERVAL_MS,
    private val toleranceMs: Long = MEDIA_SESSION_POSITION_TOLERANCE_MS,
) {
    private var lastSyncElapsedMs = Long.MIN_VALUE
    private var lastPositionMs = 0L
    private var lastSpeed = 1f
    private var lastPlaying = false

    /** Records a sample the media session has just been given. */
    fun published(
        state: PlaybackState,
        nowElapsedMs: Long,
    ) {
        lastSyncElapsedMs = nowElapsedMs
        lastPositionMs = state.positionMs
        lastSpeed = state.speed
        lastPlaying = state.playing
    }

    fun shouldPublish(
        state: PlaybackState,
        nowElapsedMs: Long,
    ): Boolean {
        if (lastSyncElapsedMs == Long.MIN_VALUE) return true
        val elapsed = (nowElapsedMs - lastSyncElapsedMs).coerceAtLeast(0L)
        if (elapsed >= intervalMs) return true
        val expected = if (lastPlaying) lastPositionMs + (elapsed * lastSpeed).toLong() else lastPositionMs
        return kotlin.math.abs(state.positionMs - expected) > toleranceMs
    }
}

internal const val MEDIA_SESSION_POSITION_INTERVAL_MS = 10_000L
internal const val MEDIA_SESSION_POSITION_TOLERANCE_MS = 1_500L
