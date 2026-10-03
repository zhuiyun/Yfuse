package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.yfuse.core.cast.CastState

/**
 * 睡眠定时 on this device: the countdown, the arming of 本集结束 as the armed item nears its end, and
 * the pause once it has played out. [pauseForSleepTimer] pauses playback, turns the timer off and
 * says why; a cast session's end of item is noticed by PlayerRoot's cast auto-advance instead.
 */
@Composable
internal fun PlayerSleepTimerEffects(
    sleepTimer: PlayerSleepTimer,
    playing: Boolean,
    localState: PlaybackState,
    liveLocalState: State<PlaybackState>,
    castState: State<CastState>,
    pauseForSleepTimer: (String) -> Unit,
) {
    val sleepTimerPlaying by rememberUpdatedState(playing)
    LaunchedEffect(sleepTimer.option, sleepTimer.revision) {
        val durationMs = sleepTimer.option.durationMs ?: return@LaunchedEffect
        awaitSleepTimerPlayback(durationMs) { sleepTimerPlaying }
        pauseForSleepTimer("睡眠定时已到，播放已暂停")
    }
    LaunchedEffect(sleepTimer.option, sleepTimer.endIndex, liveLocalState) {
        snapshotFlow { liveLocalState.value }.collect { current -> sleepTimer.observe(current) }
    }
    LaunchedEffect(
        sleepTimer.option,
        sleepTimer.endIndex,
        sleepTimer.armedItemReachedEnd,
        localState.currentIndex,
        localState.ended,
        localState.playing,
    ) {
        if (sleepTimer.option != SleepTimerOption.EndOfEpisode || castState.value.hasActiveSession) {
            return@LaunchedEffect
        }
        if (
            shouldCompleteLocalEndOfEpisodeTimer(
                armedIndex = sleepTimer.endIndex,
                currentIndex = localState.currentIndex,
                ended = localState.ended,
                playing = localState.playing,
                armedItemReachedEnd = sleepTimer.armedItemReachedEnd,
            )
        ) {
            pauseForSleepTimer("本集已结束，播放已暂停")
        }
    }
}
