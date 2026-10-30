package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState

/** One actor survives engine replacement, so its final clock supplier must survive it too. */
@Composable
internal fun BindPlaybackReporterLifetime(
    reporter: PlaybackProgressReporter,
    state: State<PlaybackState>,
) {
    val latest = rememberUpdatedState(state)
    DisposableEffect(reporter) {
        onDispose { reporter.close(latest.value.value) }
    }
}
