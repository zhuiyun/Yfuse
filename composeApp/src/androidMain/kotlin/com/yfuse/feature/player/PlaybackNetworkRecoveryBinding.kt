package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.logging.AppLog
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackNetworkRecoveryController
import com.yfuse.core2.api.YPlayer
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Item/version ownership remains outside engine replacement so an in-flight recovery survives handover. */
internal class PlaybackNetworkRecoveryState {
    val controller = PlaybackNetworkRecoveryController()
    var attempts by mutableIntStateOf(0)
    var successes by mutableIntStateOf(0)
    var pending by mutableStateOf(false)
    var resumePositionMs by mutableStateOf<Long?>(null)
    var initialError: String? = null
        private set

    /** Both UI recovery entry points run on Main and must acquire this before issuing player commands. */
    fun begin(
        positionMs: Long,
        error: String?,
    ): Boolean {
        if (pending) return false
        initialError = error
        resumePositionMs = positionMs
        attempts++
        pending = true
        return true
    }

    fun finish(resumed: Boolean) {
        if (pending && resumed) successes++
        pending = false
        resumePositionMs = null
        initialError = null
    }
}

/** What the recovery controller reads; a change in any of them is one observation. */
private data class NetworkRecoveryInputs(
    val networkClass: PlaybackNetworkClass,
    val playbackRequested: Boolean,
    val positionMs: Long,
    val error: String?,
    val ended: Boolean,
    val remoteSource: Boolean,
    val needsRecovery: Boolean,
    val recoveryInProgress: Boolean,
)

@Composable
internal fun BindPlaybackNetworkRecovery(
    recovery: PlaybackNetworkRecoveryState,
    playbackNetworkClass: PlaybackNetworkClass,
    engine: VideoEngine,
    player: YPlayer,
    localPlayback: State<PlaybackState>,
    castAuthoritative: Boolean,
    attachedEngineLabel: String,
    probe: PlaybackMediaProbe,
) {
    val currentNetworkClass by rememberUpdatedState(playbackNetworkClass)
    val currentEngineLabel by rememberUpdatedState(attachedEngineLabel)
    val currentProbe by rememberUpdatedState(probe)
    // Both effects follow the live position, which ticks twice a second. Keyed on it, each effect was
    // cancelled and relaunched on every tick; these collectors stay put and re-read their inputs, and
    // this binding no longer recomposes with the position either.
    LaunchedEffect(recovery.controller, engine, player, localPlayback, castAuthoritative) {
        if (castAuthoritative) return@LaunchedEffect
        snapshotFlow {
            val live = localPlayback.value
            NetworkRecoveryInputs(
                networkClass = currentNetworkClass,
                playbackRequested = player.playbackRequested,
                positionMs = live.positionMs,
                error = live.error,
                ended = live.ended,
                remoteSource = !currentProbe.localSource,
                needsRecovery = live.buffering || live.error != null,
                recoveryInProgress = recovery.pending,
            )
        }.collect { inputs ->
            val decision =
                recovery.controller.observe(
                    networkClass = inputs.networkClass,
                    playbackRequested = inputs.playbackRequested,
                    positionMs = player.currentPositionMs(),
                    ended = inputs.ended,
                    remoteSource = inputs.remoteSource,
                    needsRecovery = inputs.needsRecovery,
                    recoveryInProgress = inputs.recoveryInProgress,
                )
            if (!decision.retry || !recovery.begin(decision.resumePositionMs, inputs.error)) return@collect
            AppLog.info(
                category = "player.network",
                event = "connectivity_restored",
                message = "YCore resumed the active backend after connectivity returned",
                attributes =
                    mapOf(
                        "engine" to currentEngineLabel,
                        "positionMs" to decision.resumePositionMs.toString(),
                        "network" to inputs.networkClass.name,
                    ),
            )
            player.seekTo(decision.resumePositionMs)
            player.retry()
        }
    }
    LaunchedEffect(recovery, recovery.attempts, localPlayback, player) {
        val resumePosition = recovery.resumePositionMs ?: return@LaunchedEffect
        if (!recovery.pending) return@LaunchedEffect
        val initialError = recovery.initialError
        // Keep pending through synthetic seek ticks and short-lived output that re-buffers.
        // Do not treat the old error, still visible before the queued retry starts, as its result.
        val resumed =
            withTimeoutOrNull(35_000L) {
                snapshotFlow { localPlayback.value }
                    .dropWhile { initialError != null && it.error == initialError }
                    .map { live ->
                        live.networkRecoveryOutcome(
                            resumePosition,
                            currentProbe,
                            videoSuspended = player.videoOutputSuspended,
                        )
                    }.filterNotNull()
                    .first()
            }
        recovery.finish(resumed == true)
    }
}
