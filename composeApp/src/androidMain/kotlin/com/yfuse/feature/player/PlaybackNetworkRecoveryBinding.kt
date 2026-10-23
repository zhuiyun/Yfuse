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

/** Item/version ownership remains outside engine replacement so an in-flight recovery survives handover. */
internal class PlaybackNetworkRecoveryState {
    val controller = PlaybackNetworkRecoveryController()
    var attempts by mutableIntStateOf(0)
    var successes by mutableIntStateOf(0)
    var pending by mutableStateOf(false)
    var resumePositionMs by mutableStateOf<Long?>(null)
}

/** What the recovery controller reads; a change in any of them is one observation. */
private data class NetworkRecoveryInputs(
    val networkClass: PlaybackNetworkClass,
    val playbackRequested: Boolean,
    val positionMs: Long,
    val error: String?,
    val ended: Boolean,
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
            )
        }.collect { inputs ->
            val decision =
                recovery.controller.observe(
                    networkClass = inputs.networkClass,
                    playbackRequested = inputs.playbackRequested,
                    positionMs = player.currentPositionMs(),
                    ended = inputs.ended,
                )
            if (!decision.retry) return@collect
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
            recovery.attempts++
            recovery.pending = true
            recovery.resumePositionMs = decision.resumePositionMs
            player.seekTo(decision.resumePositionMs)
            player.retry()
        }
    }
    LaunchedEffect(recovery, localPlayback, player) {
        // Keep pending through synthetic seek ticks and short-lived output that re-buffers.
        snapshotFlow {
            val resumePosition = recovery.resumePositionMs
            if (recovery.pending && resumePosition != null) {
                localPlayback.value.networkRecoveryOutcome(
                    resumePosition,
                    currentProbe,
                    videoSuspended = player.videoOutputSuspended,
                )
            } else {
                null
            }
        }.collect { resumed ->
            if (resumed == null) return@collect
            if (resumed) recovery.successes++
            recovery.pending = false
            recovery.resumePositionMs = null
        }
    }
}
