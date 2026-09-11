package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier

/** The countdown owns its live timeline subscription, separately from gesture and menu state. */
@Composable
internal fun PlayerNextUpOverlay(
    playback: State<PlaybackState>,
    episodes: List<EpisodeCard>,
    dismissed: Boolean,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val state = playback.value
    val nextUpRemainingMs = state.durationMs - state.positionMs
    val showNextUp =
        state.hasNext &&
            state.durationMs > 0L &&
            !dismissed &&
            nextUpRemainingMs in 1L..NEXT_UP_WINDOW_MS
    ChromeVisibility(
        visible = showNextUp,
        edge = ChromeEdge.End,
        modifier =
        modifier,
    ) {
        NextUpCard(
            title = episodes.getOrNull(state.currentIndex + 1)?.title.orEmpty(),
            remainingMs = nextUpRemainingMs.coerceIn(0L, NEXT_UP_WINDOW_MS),
            playbackKey = state.currentIndex to episodes.getOrNull(state.currentIndex)?.watchKey,
            advancing = showNextUp && state.playing && !state.buffering && !state.ended && state.error == null,
            speed = state.speed,
            onPlayNow = {
                val current = playback.value
                if (
                    showNextUp &&
                    current.currentIndex == state.currentIndex &&
                    current.remainingMs in 1L..NEXT_UP_WINDOW_MS
                ) {
                    onPlayNow()
                }
            },
            onDismiss = {
                if (showNextUp && playback.value.currentIndex == state.currentIndex) onDismiss()
            },
        )
    }
}
