package com.yfuse.feature.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yfuse.core.playback.PlaybackOptimizationMode
import com.yfuse.core.playback.PlaybackOptimizationRecovery
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.ResolvedPlaybackOptimization
import com.yfuse.core.playback.resolvePlaybackOptimization
import kotlinx.coroutines.delay

@Composable
internal fun rememberPlaybackOptimization(
    requested: PlaybackOptimizationMode,
    environment: PlaybackRuntimeEnvironment,
): ResolvedPlaybackOptimization {
    val target = resolvePlaybackOptimization(requested, environment)
    // An explicit user change starts a fresh policy and never waits for an old recovery timer.
    val recovery = remember(requested) { PlaybackOptimizationRecovery() }
    var applied by remember(recovery) {
        mutableStateOf(recovery.update(target, SystemClock.elapsedRealtime()))
    }
    LaunchedEffect(recovery, environment.pressure) {
        applied = recovery.update(target, SystemClock.elapsedRealtime())
        val remainingMs = recovery.remainingMs(SystemClock.elapsedRealtime())
        if (remainingMs > 0L) {
            delay(remainingMs)
            applied = recovery.update(target, SystemClock.elapsedRealtime())
        }
    }
    // Safety takes effect in this composition, before the effect gets a chance to run.
    return if (target.mode == PlaybackOptimizationMode.PowerSaver) target else applied
}
