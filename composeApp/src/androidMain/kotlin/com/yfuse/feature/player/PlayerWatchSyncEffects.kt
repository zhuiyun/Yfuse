package com.yfuse.feature.player

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.yfuse.core.sync.WatchTogetherClient
import com.yfuse.core.sync.WatchTogetherState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.time.TimeSource

/** Maximum time to wait for a requested seek or item switch to land before trying again. */
private const val CORRECTION_SETTLE_TIMEOUT_MS = 8_000L

/** A guest that remains buffering this long gets one active recovery attempt. */
private const val GUEST_BUFFER_RECOVERY_MS = 15_000L

/** Loading completion realigns more aggressively than ordinary in-play drift. */
private const val POST_BUFFER_SEEK_THRESHOLD_MS = 300L

/**
 * Keeps a watch-together member aligned with the room and reports its local readiness.
 *
 * This is deliberately an effect-only composable: room protocol state and high-frequency
 * engine state no longer add more state branches to the already large player root.
 */
@SuppressLint("RememberReturnType")
@Composable
internal fun PlayerWatchSyncEffects(
    items: List<PlayerMediaItem>,
    engine: VideoEngine,
    playbackState: PlaybackState,
    watchState: WatchTogetherState,
    castAuthoritative: Boolean,
    watchTogether: WatchTogetherClient,
    playbackGate: WatchGatedPlayback,
    onRemotePlayRequested: () -> Boolean,
) {
    val latestEngine by rememberUpdatedState(engine)
    val latestPlaybackState by rememberUpdatedState(playbackState)
    val latestRemotePlayRequested by rememberUpdatedState(onRemotePlayRequested)
    val mediaMatcher =
        remember(watchTogether) {
            WatchMediaMatcher(
                onWarning = { warning -> watchTogether.setSyncWarning(warning) },
            )
        }

    // The room timeline is server-authoritative and mostly silent between events, so guests
    // reconcile on their own tick. Small drift is ignored, medium drift is closed with a
    // short speed nudge, and only a large gap performs a visible seek.
    LaunchedEffect(
        watchState.connected,
        watchState.reconnecting,
        watchState.isHost,
        castAuthoritative,
    ) {
        if (castAuthoritative) {
            mediaMatcher.reset()
            return@LaunchedEffect
        }
        if (!watchState.connected || watchState.isHost) {
            mediaMatcher.reset()
            if (watchState.connected) watchTogether.updateSyncDrift(0L)
            return@LaunchedEffect
        }
        var lastAppliedRate: Float? = null
        var lastNominalRate: Float? = null
        var awaitedPositionMs: Long? = null
        var awaitedIndex: Int? = null
        var awaitingSince = TimeSource.Monotonic.markNow()
        var bufferingSince = TimeSource.Monotonic.markNow()
        var wasBuffering = true

        fun awaitCorrection(
            positionMs: Long?,
            index: Int?,
        ) {
            awaitedPositionMs = positionMs
            awaitedIndex = index
            awaitingSince = TimeSource.Monotonic.markNow()
        }

        try {
            while (isActive) {
                val timeline = watchTogether.timeline.value
                if (timeline != null) {
                    lastNominalRate = timeline.rate
                    val targetIndex = mediaMatcher.resolve(items, timeline.mediaKey)
                    if (targetIndex != null) {
                        val position = latestEngine.currentPositionMs()
                        val landed =
                            awaitedIndex.let {
                                it == null || it == latestPlaybackState.currentIndex
                            } &&
                                awaitedPositionMs.let {
                                    it == null || abs(position - it) < HARD_SEEK_THRESHOLD_MS
                                }
                        val settling =
                            !landed &&
                                awaitingSince.elapsedNow().inWholeMilliseconds <
                                CORRECTION_SETTLE_TIMEOUT_MS
                        if (landed) awaitCorrection(null, null)
                        if (settling) {
                            if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        if (latestPlaybackState.buffering) {
                            if (!wasBuffering) bufferingSince = TimeSource.Monotonic.markNow()
                            wasBuffering = true
                            if (
                                bufferingSince.elapsedNow().inWholeMilliseconds >=
                                GUEST_BUFFER_RECOVERY_MS
                            ) {
                                latestEngine.retry()
                                bufferingSince = TimeSource.Monotonic.markNow()
                            }
                            if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        val recoveredFromBuffer = wasBuffering
                        wasBuffering = false

                        if (targetIndex != latestPlaybackState.currentIndex) {
                            latestEngine.selectItem(targetIndex)
                            awaitCorrection(positionMs = null, index = targetIndex)
                            delay(GUEST_RECONCILE_TICK_MS)
                            continue
                        }
                        val expected = timeline.expectedPositionMs(watchTogether.estimatedServerNow())
                        val diff = expected - position
                        watchTogether.updateSyncDrift(diff)
                        val desiredRate =
                            when {
                                abs(diff) >= HARD_SEEK_THRESHOLD_MS ||
                                    (
                                        recoveredFromBuffer &&
                                            abs(diff) >= POST_BUFFER_SEEK_THRESHOLD_MS
                                    ) -> {
                                    latestEngine.seekTo(expected)
                                    awaitCorrection(positionMs = expected, index = null)
                                    timeline.rate
                                }
                                abs(diff) >= NUDGE_THRESHOLD_MS ->
                                    timeline.rate *
                                        (1f + if (diff > 0) NUDGE_FRACTION else -NUDGE_FRACTION)
                                else -> timeline.rate
                            }
                        if (
                            lastAppliedRate == null ||
                            abs(desiredRate - lastAppliedRate!!) > RATE_EPSILON
                        ) {
                            latestEngine.setSpeed(desiredRate)
                            lastAppliedRate = desiredRate
                        }
                        if (timeline.paused && latestPlaybackState.playing) latestEngine.pause()
                        if (!timeline.paused && !latestPlaybackState.playing) {
                            if (latestRemotePlayRequested()) latestEngine.play()
                        }
                    }
                }
                delay(GUEST_RECONCILE_TICK_MS)
            }
        } finally {
            mediaMatcher.reset()
            lastNominalRate?.let(latestEngine::setSpeed)
        }
    }

    LaunchedEffect(
        watchState.connected,
        watchState.reconnecting,
        watchState.localMediaAvailable,
        watchState.canControl,
        playbackState.buffering,
        playbackState.error,
        playbackState.currentIndex,
    ) {
        if (watchState.connected && !watchState.reconnecting) {
            watchTogether.updatePlaybackStatus(
                ready =
                    watchState.localMediaAvailable &&
                        !playbackState.buffering &&
                        playbackState.error == null,
                buffering = playbackState.buffering,
                mediaAvailable = watchState.localMediaAvailable,
                syncDriftMs = if (watchState.isHost) 0L else null,
            )
        }
    }

    // A reconnect or host promotion needs a fresh anchor; user actions publish at their own
    // call sites through WatchGatedPlayback.
    LaunchedEffect(watchState.connected, watchState.reconnecting, watchState.isHost) {
        if (watchState.connected && !watchState.reconnecting && watchState.isHost) {
            playbackGate.publishCurrent()
        }
    }
}
