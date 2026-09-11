package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.logging.AppLog
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

@Composable
internal fun BindPlaybackNetworkRecovery(
    recovery: PlaybackNetworkRecoveryState,
    playbackNetworkClass: PlaybackNetworkClass,
    engine: VideoEngine,
    player: YPlayer,
    localPlayback: State<PlaybackState>,
    castAuthoritative: Boolean,
    attachedEngineLabel: String,
) {
    val localState = localPlayback.value
    LaunchedEffect(
        recovery.controller,
        playbackNetworkClass,
        engine,
        player.playbackRequested,
        localState.positionMs,
        localState.error,
        localState.ended,
        castAuthoritative,
    ) {
        if (castAuthoritative) return@LaunchedEffect
        val decision =
            recovery.controller.observe(
                networkClass = playbackNetworkClass,
                playbackRequested = player.playbackRequested,
                positionMs = player.currentPositionMs(),
                ended = localState.ended,
            )
        if (!decision.retry) return@LaunchedEffect
        AppLog.info(
            category = "player.network",
            event = "connectivity_restored",
            message = "YCore resumed the active backend after connectivity returned",
            attributes =
                mapOf(
                    "engine" to attachedEngineLabel,
                    "positionMs" to decision.resumePositionMs.toString(),
                    "network" to playbackNetworkClass.name,
                ),
        )
        recovery.attempts++
        recovery.pending = true
        recovery.resumePositionMs = decision.resumePositionMs
        player.seekTo(decision.resumePositionMs)
        player.retry()
    }
    LaunchedEffect(
        recovery.pending,
        recovery.resumePositionMs,
        localState.playing,
        localState.buffering,
        localState.positionMs,
        localState.error,
        localState.ended,
    ) {
        if (recovery.pending && localState.error != null) {
            recovery.pending = false
            recovery.resumePositionMs = null
        } else if (
            recovery.pending &&
            !localState.buffering &&
            localState.error == null &&
            (
                localState.ended ||
                    (
                        localState.playing &&
                            localState.positionMs > (recovery.resumePositionMs ?: Long.MAX_VALUE)
                    )
            )
        ) {
            recovery.successes++
            recovery.pending = false
            recovery.resumePositionMs = null
        }
    }
}
