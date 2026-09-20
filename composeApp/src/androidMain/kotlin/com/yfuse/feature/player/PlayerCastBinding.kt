package com.yfuse.feature.player

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastPlaybackStatus
import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.CastTermination
import com.yfuse.core.cast.castRecoveryDecision
import com.yfuse.core.cast.formatDlnaTime
import com.yfuse.core.model.PlaybackMethod
import com.yfuse.core2.api.YPlayer

/**
 * A receiver owns the timeline while it holds a session — and, after a connection that dropped
 * unexpectedly, until that session has been handed back to the local player.
 */
internal fun CastState.isAuthoritative(completedHandoffRevision: Long?): Boolean {
    val pendingUnexpectedHandoff =
        termination == CastTermination.Unexpected &&
            completedHandoffRevision != sessionRevision
    return hasActiveSession || pendingUnexpectedHandoff
}

/** What the receiver is told it is playing: a transcode URL outranks the entry's own play method. */
internal fun PlayerMediaItem?.castPlayMethodLabel(): String =
    if (this?.transcodeUrl?.isNotBlank() == true) {
        PlaybackMethod.Transcode.label
    } else {
        this?.playMethod?.label ?: PlaybackMethod.DirectPlay.label
    }

/** A cast session that ended on its own hands its position and play state back to this device, once. */
@Composable
internal fun RecoverLocalPlaybackAfterCast(
    castState: CastState,
    liveCastState: State<CastState>,
    liveLocalState: State<PlaybackState>,
    completedCastHandoffRevisionState: MutableState<Long?>,
    player: YPlayer,
) {
    val context = LocalContext.current
    var completedCastHandoffRevision by completedCastHandoffRevisionState
    LaunchedEffect(castState.sessionRevision, castState.termination) {
        val decision =
            castRecoveryDecision(
                state = liveCastState.value,
                fallbackPositionMs = liveLocalState.value.positionMs,
            ) ?: return@LaunchedEffect
        if (completedCastHandoffRevision == castState.sessionRevision) return@LaunchedEffect
        player.seekTo(decision.positionMs)
        if (decision.resumePlayback) player.play() else player.pause()
        completedCastHandoffRevision = castState.sessionRevision
        Toast
            .makeText(
                context,
                "投屏连接已断开，已回到本机 ${decision.positionMs / 1000} 秒",
                Toast.LENGTH_LONG,
            ).show()
    }
}

/**
 * The receiver plays one entry at a time, so the queue advances from here when it reports the end
 * — unless the sleep timer was armed for exactly that entry, which pauses instead.
 */
@Composable
internal fun AdvanceCastQueue(
    castState: CastState,
    localState: PlaybackState,
    activeItems: List<PlayerMediaItem>,
    autoNext: Boolean,
    sleepTimerOption: SleepTimerOption,
    sleepTimerEndIndex: Int?,
    sleepTimerEndSessionRevision: Long?,
    pauseForSleepTimer: (String) -> Unit,
    loadCastItem: suspend (deviceId: String, index: Int, positionMs: Long) -> Boolean,
) {
    var autoAdvancedCastRevision by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(
        castState.status,
        castState.sessionRevision,
        localState.currentIndex,
        autoNext,
        sleepTimerOption,
        sleepTimerEndIndex,
        sleepTimerEndSessionRevision,
    ) {
        if (
            sleepTimerOption == SleepTimerOption.EndOfEpisode &&
            shouldCompleteCastEndOfEpisodeTimer(
                armedIndex = sleepTimerEndIndex,
                armedSessionRevision = sleepTimerEndSessionRevision,
                currentIndex = localState.currentIndex,
                currentSessionRevision = castState.sessionRevision,
                castEnded = castState.status == CastPlaybackStatus.Ended,
            )
        ) {
            autoAdvancedCastRevision = castState.sessionRevision
            pauseForSleepTimer("本集已结束，投屏已暂停")
            return@LaunchedEffect
        }
        if (
            !autoNext ||
            castState.status != CastPlaybackStatus.Ended ||
            autoAdvancedCastRevision == castState.sessionRevision
        ) {
            return@LaunchedEffect
        }
        val deviceId = castState.activeDeviceId ?: return@LaunchedEffect
        val next = localState.currentIndex + 1
        if (next !in activeItems.indices) return@LaunchedEffect
        autoAdvancedCastRevision = castState.sessionRevision
        loadCastItem(deviceId, next, 0L)
    }
}

/** Play/pause while casting: an errored receiver that was playing is treated as still playing. */
internal suspend fun toggleCastPlayback(
    castManager: CastManager,
    castState: CastState,
) {
    if (
        castState.status == CastPlaybackStatus.Playing ||
        castState.status == CastPlaybackStatus.Buffering ||
        (
            castState.status == CastPlaybackStatus.Error &&
                castState.lastRemoteWasPlaying
        )
    ) {
        castManager.pause()
    } else {
        castManager.resume()
    }
}

/** Ends the session and continues on this device from the receiver's confirmed position. */
internal suspend fun stopCastAndResumeLocally(
    castManager: CastManager,
    castState: CastState,
    liveCastState: State<CastState>,
    liveLocalState: State<PlaybackState>,
    player: YPlayer,
) {
    val handoffPosition =
        if (castState.positionConfirmed) {
            liveCastState.value.positionMs
        } else {
            liveLocalState.value.positionMs
        }
    val resumeLocally = castState.lastRemoteWasPlaying
    if (castManager.stop()) {
        player.seekTo(handoffPosition)
        if (resumeLocally) player.play() else player.pause()
    }
}

/** 「设备名 · 状态」 for the cast panel; null while nothing is casting. */
internal fun CastState.deviceStatusLabel(): String? = activeDevice?.let { "${it.name} · ${status.label}" }

/** The receiver's own clock, read where it is drawn; it is not part of the projected cast state. */
internal fun CastState.positionLabel(): String? =
    activeDevice?.let {
        if (!positionConfirmed) {
            "等待接收端确认"
        } else {
            buildString {
                append(formatDlnaTime(positionMs))
                if (durationMs > 0L) {
                    append(" / ")
                    append(formatDlnaTime(durationMs))
                }
            }
        }
    }

internal fun CastState.capabilitiesLabel(): String? =
    activeDevice?.let {
        "播放 ${capabilities.playPause.label} · " +
            "跳转 ${capabilities.seek.label} · " +
            "音量 ${capabilities.volume.label} · " +
            "轨道 ${capabilities.trackSelection.label} · " +
            "队列 ${capabilities.queue.label} · " +
            "DV ${capabilities.dolbyVision.label} · " +
            "Atmos ${capabilities.dolbyAtmos.label}"
    }
