package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.StateFlow

/** Timeline stabilization runs in the collector; only structural transitions restart the runtime tree. */
@Composable
internal fun PlaybackRuntimeContent(
    owner: Any,
    source: StateFlow<PlaybackState>,
    items: List<PlayerMediaItem>,
    content: @Composable (PlaybackState, State<PlaybackState>) -> Unit,
) {
    val latestItems = rememberUpdatedState(items)
    val memory = remember { arrayOf(PlaybackTimelineMemory()) }
    val live =
        remember(owner, source) {
            val reported = source.value
            val item = items.getOrNull(reported.currentIndex)
            // A new backend must never render the previous backend's ended/error flags, even for
            // its first composition. Only the timeline is retained across a same-item handover.
            mutableStateOf(
                stabilizePlaybackTimeline(
                    memory[0],
                    item?.let { PlaybackTimelineIdentity(reported.currentIndex, it.serverId, it.id) },
                    reported,
                ).state,
            )
        }
    LaunchedEffect(owner, source) {
        source.collect { reported ->
            val item = latestItems.value.getOrNull(reported.currentIndex)
            val resolution =
                stabilizePlaybackTimeline(
                    memory = memory[0],
                    media = item?.let { PlaybackTimelineIdentity(reported.currentIndex, it.serverId, it.id) },
                    reported = reported,
                )
            memory[0] = resolution.memory
            live.value = resolution.state
        }
    }
    val runtime = remember(live) { derivedStateOf { live.value.runtimeProjection() } }
    content(runtime.value, live)
}
