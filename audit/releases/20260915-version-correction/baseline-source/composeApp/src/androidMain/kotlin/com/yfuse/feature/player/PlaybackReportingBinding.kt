package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.yfuse.core.cast.CastManager
import com.yfuse.core.cast.CastState
import com.yfuse.core.cast.CastTermination
import com.yfuse.core.model.PlaybackMethod
import kotlinx.coroutines.flow.collect

@Composable
internal fun BindPlaybackReporting(
    engine: VideoEngine,
    castManager: CastManager,
    activeItems: List<PlayerMediaItem>,
    playbackSink: PlaybackEventSink?,
    localState: State<PlaybackState>,
    castState: State<CastState>,
    completedHandoff: State<Long?>,
    latestState: State<PlaybackState>,
    playbackGate: WatchGatedPlayback,
    onPlaybackState: (PlaybackState, PlayerMediaItem?) -> Unit,
) {
    val latestItems = rememberUpdatedState(activeItems)
    val latestCallback by rememberUpdatedState(onPlaybackState)
    // One actor owns the entire reporting lifetime. Rebinding it serializes a version switch as
    // stop-old → start-new, while a tail append only extends its queue and leaves the current
    // encoding alone. Recreating two independent reporters cannot guarantee either property.
    val reporter =
        remember(playbackSink) {
            playbackSink?.let { PlaybackProgressReporter(activeItems, it) }
        }
    // Keep one reporting collector alive instead of cancelling and recreating a LaunchedEffect
    // for every 500 ms position tick. The snapshot still follows local/cast authority changes.
    LaunchedEffect(engine, castManager, activeItems, reporter) {
        snapshotFlow {
            val currentLocal = localState.value
            val currentCast = castState.value
            val authoritative =
                currentCast.hasActiveSession ||
                    (
                        currentCast.termination == CastTermination.Unexpected &&
                            completedHandoff.value != currentCast.sessionRevision
                    )
            val item = latestItems.value.getOrNull(currentLocal.currentIndex)
            val playMethod =
                if (item?.transcodeUrl?.isNotBlank() == true) {
                    PlaybackMethod.Transcode.label
                } else {
                    item?.playMethod?.label ?: PlaybackMethod.DirectPlay.label
                }
            if (authoritative) currentLocal.withRemoteCast(currentCast, playMethod) else currentLocal
        }.collect { observedState ->
            val items = latestItems.value
            reporter?.rebind(items, observedState)
            reporter?.update(observedState)
            latestCallback(observedState, items.getOrNull(observedState.currentIndex))
            playbackGate.onPlaybackIndexChanged(observedState.currentIndex)
        }
    }
    DisposableEffect(reporter) {
        onDispose {
            reporter?.close(latestState.value)
        }
    }
}
