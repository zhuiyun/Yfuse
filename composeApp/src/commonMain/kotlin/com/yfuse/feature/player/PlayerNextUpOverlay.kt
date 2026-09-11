package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

/**
 * The countdown owns its live timeline subscription, separately from gesture and menu state.
 *
 * Everything the card draws is derived from the playhead, so reading `playback.value` in this
 * scope put the whole overlay on the timeline's clock — the [ChromeVisibility] wrapper, its
 * transition and both tap closures were rebuilt twice a second for the entire film, to draw a
 * card that is only on screen for the last ten seconds of it. The boolean that decides whether
 * the card exists is a [derivedStateOf] instead, so this scope recomposes when the answer
 * changes rather than when the position does; only [NextUpContent] follows the clock.
 */
@Composable
internal fun PlayerNextUpOverlay(
    playback: State<PlaybackState>,
    episodes: List<EpisodeCard>,
    dismissed: Boolean,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    val showNextUp by remember(playback, dismissed) {
        derivedStateOf {
            val state = playback.value
            state.hasNext &&
                state.durationMs > 0L &&
                !dismissed &&
                (state.durationMs - state.positionMs) in 1L..NEXT_UP_WINDOW_MS
        }
    }
    ChromeVisibility(
        visible = showNextUp,
        edge = ChromeEdge.End,
        modifier = modifier,
    ) {
        NextUpContent(
            playback = playback,
            episodes = episodes,
            active = showNextUp,
            onPlayNow = onPlayNow,
            onDismiss = onDismiss,
        )
    }
}

/**
 * The part of the card that genuinely changes with the playhead.
 *
 * The two closures are remembered and read the live state when they fire, rather than closing
 * over the frame that built them: they used to be a fresh pair of lambdas per tick, which the
 * card could never skip on.
 */
@Composable
private fun NextUpContent(
    playback: State<PlaybackState>,
    episodes: List<EpisodeCard>,
    active: Boolean,
    onPlayNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = playback.value
    val currentIndex = state.currentIndex
    val latestActive by rememberUpdatedState(active)
    val latestIndex by rememberUpdatedState(currentIndex)
    val latestOnPlayNow by rememberUpdatedState(onPlayNow)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val playNow =
        remember(playback) {
            {
                val current = playback.value
                if (
                    latestActive &&
                    current.currentIndex == latestIndex &&
                    current.remainingMs in 1L..NEXT_UP_WINDOW_MS
                ) {
                    latestOnPlayNow()
                }
            }
        }
    val dismiss =
        remember(playback) {
            { if (latestActive && playback.value.currentIndex == latestIndex) latestOnDismiss() }
        }
    NextUpCard(
        title = episodes.getOrNull(currentIndex + 1)?.title.orEmpty(),
        remainingMs = (state.durationMs - state.positionMs).coerceIn(0L, NEXT_UP_WINDOW_MS),
        // Identity only — remembered so the ring is not handed a new key on every tick.
        playbackKey =
            remember(currentIndex, episodes) {
                currentIndex to episodes.getOrNull(currentIndex)?.watchKey
            },
        advancing = active && state.playing && !state.buffering && !state.ended && state.error == null,
        speed = state.speed,
        onPlayNow = playNow,
        onDismiss = dismiss,
    )
}
